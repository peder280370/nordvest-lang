package nv.compiler.codegen

import nv.compiler.parser.*
import nv.compiler.typecheck.Type

// ─────────────────────────────────────────────────────────────────────────────
// Statements — all emitXxxStmt methods plus for-loop helpers.
// ─────────────────────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitStmt(stmt: Stmt) {
    when (stmt) {
        is LetStmt        -> emitLetVarStmt(stmt.binding, stmt.typeAnnotation, stmt.initializer, mutable = false)
        is VarStmt        -> emitLetVarStmt(stmt.binding, stmt.typeAnnotation, stmt.initializer, mutable = true)
        is ReturnStmt     -> emitReturnStmt(stmt)
        is ExprStmt       -> { emitExpr(stmt.expr); Unit }
        is IfStmt         -> emitIfStmt(stmt)
        is WhileStmt      -> emitWhileStmt(stmt)
        is ForStmt        -> emitForStmt(stmt)
        is AssignStmt     -> emitAssignStmt(stmt)
        is MatchStmt      -> emitMatchExpr(stmt.expr)
        is BreakStmt      -> emitBreakStmt(stmt)
        is ContinueStmt   -> emitContinueStmt(stmt)
        is GuardLetStmt   -> emitGuardLetStmt(stmt)
        is UnsafeBlock    -> stmt.stmts.forEach { emitStmt(it) }
        is AsmStmt        -> emitAsmStmt(stmt)
        is BytesStmt      -> emitBytesStmt(stmt)
        is CBlockStmt     -> {
            emit("  ; @c block (passthrough to C compiler)")
            for (line in stmt.lines) emit("  ; $line")
        }
        is CppBlockStmt   -> {
            emit("  ; @cpp block (passthrough to C++ compiler)")
            for (line in stmt.lines) emit("  ; $line")
        }
        is DeferStmt      -> { /* Phase 1.5: defer not fully supported */ }
        is TryCatchStmt   -> emitTryCatchStmt(stmt)
        is ThrowStmt      -> { emitExpr(stmt.expr); Unit }
        is GoStmt         -> emitGoStmt(stmt)
        is SpawnStmt      -> { emitExpr(stmt.expr); Unit }
        is SelectStmt     -> emitSelectStmt(stmt)
        else              -> { /* unsupported */ }
    }
}

private fun LlvmIrEmitter.emitLetVarStmt(
    binding: Binding,
    typeAnnotation: TypeNode?,
    initializer: Expr?,
    mutable: Boolean,
) {
    when (binding) {
        is IdentBinding -> {
            val name   = binding.name
            val initReg = initializer?.let { emitExpr(it) }
            val ty = when {
                typeAnnotation != null -> resolveTypeNode(typeAnnotation)
                initializer != null    -> typeOf(initializer)
                else                   -> Type.TUnknown
            }
            val lt = if (llvmType(ty) == "void") "i64" else llvmType(ty)
            val allocaReg = emitAlloca(name, lt)
            varAllocas[name] = Pair(allocaReg, lt)
            if (ty != Type.TUnknown) varTypes[name] = ty
            if (initReg != null) emitStore(lt, initReg, allocaReg)
        }
        is TupleBinding -> {
            val initReg = initializer?.let { emitExpr(it) }
            for (name in binding.names) {
                val allocaReg = emitAlloca(name, "i64")
                varAllocas[name] = Pair(allocaReg, "i64")
            }
            if (initReg != null && binding.names.isNotEmpty()) {
                val (firstReg, _) = varAllocas[binding.names.first()]!!
                emitStore("i64", initReg, firstReg)
            }
        }
    }
}

private fun LlvmIrEmitter.emitReturnStmt(stmt: ReturnStmt) {
    if (stmt.value == null) {
        if (fnReturnType == "i32") terminate("  ret i32 0")
        else terminate("  ret void")
        return
    }
    val valueType = typeOf(stmt.value)
    val isUnitReturn = fnReturnType == "void" ||
                       valueType == Type.TUnit ||
                       (stmt.value is TupleLiteralExpr && (stmt.value as TupleLiteralExpr).elements.isEmpty())
    if (isUnitReturn) {
        if (stmt.value != null && stmt.value !is TupleLiteralExpr) emitExpr(stmt.value)
        if (fnReturnType == "i32") terminate("  ret i32 0")
        else terminate("  ret void")
        return
    }
    if (stmt.value is NilExpr) {
        terminate("  ret $fnReturnType ${nullConstant(fnReturnType)}")
        return
    }
    val valReg = emitExpr(stmt.value)
    val coerced = coerceToType(valReg, valueType, fnReturnType)
    terminate("  ret $fnReturnType $coerced")
}

private fun LlvmIrEmitter.emitIfStmt(stmt: IfStmt) {
    val thenLabel  = freshLabel("if.then")
    val mergeLabel = freshLabel("if.merge")

    if (stmt.letBinding != null) {
        val srcReg  = emitExpr(stmt.condition)
        val srcType = typeOf(stmt.condition)
        val innerLt = llvmType((srcType as? Type.TNullable)?.inner ?: srcType)

        val elseLabel = freshLabel("if.else")
        val isNull = fresh("isnull")
        emit("  $isNull = icmp eq $innerLt $srcReg, ${nullConstant(innerLt)}")
        emit("  br i1 $isNull, label %$elseLabel, label %$thenLabel")
        emitRaw("$thenLabel:")
        isTerminated = false

        val bindName = stmt.letBinding.name
        val allocaReg = emitAlloca(bindName, innerLt)
        varAllocas[bindName] = Pair(allocaReg, innerLt)
        emitStore(innerLt, srcReg, allocaReg)

        stmt.thenBody.forEach { emitStmt(it) }
        if (!isTerminated) emit("  br label %$mergeLabel")

        emitRaw("$elseLabel:")
        isTerminated = false
        stmt.elseBody?.forEach { emitStmt(it) }
        if (!isTerminated) emit("  br label %$mergeLabel")

        emitRaw("$mergeLabel:")
        isTerminated = false
        return
    }

    val condReg = emitExpr(stmt.condition)
    val condI1  = coerceToI1(condReg, typeOf(stmt.condition))

    val hasElse = stmt.elseBody != null || stmt.elseIfClauses.isNotEmpty()
    val elseLabel = if (hasElse) freshLabel("if.else") else mergeLabel

    emit("  br i1 $condI1, label %$thenLabel, label %$elseLabel")
    emitRaw("$thenLabel:")
    isTerminated = false
    stmt.thenBody.forEach { emitStmt(it) }
    if (!isTerminated) emit("  br label %$mergeLabel")

    var currentElse = elseLabel
    val clauseLabels = stmt.elseIfClauses.mapIndexed { i, clause ->
        Triple(clause, freshLabel("elif.then"), mergeLabel)
    }

    if (stmt.elseIfClauses.isNotEmpty()) {
        emitRaw("$currentElse:")
        isTerminated = false
        for ((idx, triple) in clauseLabels.withIndex()) {
            val (clause, clauseThen, _) = triple
            val nextElse = if (idx < clauseLabels.size - 1) {
                freshLabel("elif.else")
            } else if (stmt.elseBody != null) {
                freshLabel("if.else.final")
            } else {
                mergeLabel
            }
            val clauseCond = emitExpr(clause.condition)
            val clauseI1   = coerceToI1(clauseCond, typeOf(clause.condition))
            emit("  br i1 $clauseI1, label %$clauseThen, label %$nextElse")
            emitRaw("$clauseThen:")
            isTerminated = false
            clause.body.forEach { emitStmt(it) }
            if (!isTerminated) emit("  br label %$mergeLabel")
            currentElse = nextElse
            if (idx < clauseLabels.size - 1) {
                emitRaw("$nextElse:")
                isTerminated = false
            }
        }
        if (stmt.elseBody != null) {
            emitRaw("$currentElse:")
            isTerminated = false
            stmt.elseBody.forEach { emitStmt(it) }
            if (!isTerminated) emit("  br label %$mergeLabel")
        }
    } else if (stmt.elseBody != null) {
        emitRaw("$elseLabel:")
        isTerminated = false
        stmt.elseBody.forEach { emitStmt(it) }
        if (!isTerminated) emit("  br label %$mergeLabel")
    }

    emitRaw("$mergeLabel:")
    isTerminated = false
}

private fun LlvmIrEmitter.emitWhileStmt(stmt: WhileStmt) {
    val condLabel = freshLabel("while.cond")
    val bodyLabel = freshLabel("while.body")
    val endLabel  = freshLabel("while.end")

    loopStack.addLast(LlvmIrEmitter.LoopTarget(condLabel, endLabel, stmt.label))
    emit("  br label %$condLabel")
    emitRaw("$condLabel:")
    isTerminated = false

    val condReg = emitExpr(stmt.condition)
    val condI1  = coerceToI1(condReg, typeOf(stmt.condition))
    emit("  br i1 $condI1, label %$bodyLabel, label %$endLabel")

    emitRaw("$bodyLabel:")
    isTerminated = false
    stmt.body.forEach { emitStmt(it) }
    if (!isTerminated) emit("  br label %$condLabel")

    loopStack.removeLast()
    emitRaw("$endLabel:")
    isTerminated = false
}

private fun LlvmIrEmitter.emitForStmt(stmt: ForStmt) {
    val condLabel = freshLabel("for.cond")
    val bodyLabel = freshLabel("for.body")
    val endLabel  = freshLabel("for.end")

    loopStack.addLast(LlvmIrEmitter.LoopTarget(condLabel, endLabel, stmt.label))

    val iterable = stmt.iterable
    val iterableType = typeOf(iterable)
    when {
        iterable is RangeExpr        -> emitForRange(stmt, iterable, condLabel, bodyLabel, endLabel)
        isUserIterable(iterableType) -> emitForIterator(stmt, iterable, iterableType, condLabel, bodyLabel, endLabel)
        else                         -> emitForArray(stmt, iterable, condLabel, bodyLabel, endLabel)
    }

    loopStack.removeLast()
    emitRaw("$endLabel:")
    isTerminated = false
}

private fun LlvmIrEmitter.emitForRange(
    stmt: ForStmt,
    range: RangeExpr,
    condLabel: String,
    bodyLabel: String,
    endLabel: String,
) {
    val startReg = emitExpr(range.start)
    val endReg   = emitExpr(range.end)
    val startI64 = coerceToI64(startReg, typeOf(range.start))
    val endI64   = coerceToI64(endReg, typeOf(range.end))

    val counterReg = emitAlloca("for.counter.${labelIdx}", "i64")
    emitStore("i64", startI64, counterReg)
    emit("  br label %$condLabel")

    emitRaw("$condLabel:")
    isTerminated = false
    val cv = emitLoad("i64", counterReg, "cv")

    val cond = fresh("for.cond")
    when (range.kind) {
        RangeKind.HALF_OPEN_RIGHT -> emit("  $cond = icmp slt i64 $cv, $endI64")
        RangeKind.CLOSED          -> emit("  $cond = icmp sle i64 $cv, $endI64")
        RangeKind.OPEN            -> emit("  $cond = icmp slt i64 $cv, $endI64")
        RangeKind.HALF_OPEN_LEFT  -> emit("  $cond = icmp sle i64 $cv, $endI64")
    }
    emit("  br i1 $cond, label %$bodyLabel, label %$endLabel")

    emitRaw("$bodyLabel:")
    isTerminated = false

    val bindName = when (val b = stmt.binding) {
        is IdentBinding -> b.name
        is TupleBinding -> b.names.firstOrNull() ?: "_"
    }
    val bindAlloca = emitAlloca(bindName, "i64")
    varAllocas[bindName] = Pair(bindAlloca, "i64")
    emitStore("i64", cv, bindAlloca)

    stmt.body.forEach { emitStmt(it) }

    if (!isTerminated) {
        val next = fresh("next")
        emit("  $next = add i64 $cv, 1")
        emitStore("i64", next, counterReg)
        emit("  br label %$condLabel")
    }
}

private fun LlvmIrEmitter.emitForArray(
    stmt: ForStmt,
    iterableExpr: Expr,
    condLabel: String,
    bodyLabel: String,
    endLabel: String,
) {
    val arrReg = emitExpr(iterableExpr)
    val iterableType = typeOf(iterableExpr)

    val elemType = when (iterableType) {
        is Type.TArray -> iterableType.element
        Type.TStr      -> Type.TChar
        else           -> Type.TStr
    }
    val elemLt   = llvmType(elemType)
    val elemSize = llvmTypeSize(elemLt).coerceAtLeast(1).toLong()

    val hdrPtr   = fresh("arr.hdr")
    emit("  $hdrPtr = bitcast i8* $arrReg to i64*")
    val countReg = fresh("arr.count")
    emit("  $countReg = load i64, i64* $hdrPtr, align 8")

    val idxReg = emitAlloca("for.idx.${labelIdx}", "i64")
    emitStore("i64", "0", idxReg)
    emit("  br label %$condLabel")

    emitRaw("$condLabel:")
    isTerminated = false
    val curIdx = fresh("for.i")
    emit("  $curIdx = load i64, i64* $idxReg, align 8")
    val cond = fresh("for.cond")
    emit("  $cond = icmp slt i64 $curIdx, $countReg")
    emit("  br i1 $cond, label %$bodyLabel, label %$endLabel")

    emitRaw("$bodyLabel:")
    isTerminated = false

    val dataStart = fresh("arr.data")
    emit("  $dataStart = getelementptr i8, i8* $arrReg, i64 8")
    val elemValReg = if (elemLt == "i8") {
        val ptrReg = fresh("elem.ptr")
        emit("  $ptrReg = getelementptr i8, i8* $dataStart, i64 $curIdx")
        val r = fresh("elem.val")
        emit("  $r = load i8, i8* $ptrReg, align 1")
        r
    } else {
        val castReg = fresh("elem.cast")
        emit("  $castReg = bitcast i8* $dataStart to ${llvmPtrType(elemLt)}")
        val ptrReg = fresh("elem.ptr")
        emit("  $ptrReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $castReg, i64 $curIdx")
        val r = fresh("elem.val")
        emit("  $r = load $elemLt, ${llvmPtrType(elemLt)} $ptrReg, align $elemSize")
        r
    }

    val bindName = when (val b = stmt.binding) {
        is IdentBinding -> b.name
        is TupleBinding -> b.names.firstOrNull() ?: "_"
    }
    if (bindName != "_") {
        val bindAlloca = emitAlloca(bindName, elemLt)
        varAllocas[bindName] = Pair(bindAlloca, elemLt)
        emitStore(elemLt, elemValReg, bindAlloca)
    }

    stmt.body.forEach { emitStmt(it) }

    if (!isTerminated) {
        val nextIdx = fresh("next.i")
        emit("  $nextIdx = add i64 $curIdx, 1")
        emitStore("i64", nextIdx, idxReg)
        emit("  br label %$condLabel")
    }
}

private fun LlvmIrEmitter.isUserIterable(type: Type): Boolean {
    if (type !is Type.TNamed) return false
    val n = type.qualifiedName
    return methodReturnTypes.containsKey("${n}_iter") || methodReturnTypes.containsKey("${n}_next")
}

private fun LlvmIrEmitter.emitForIterator(
    stmt: ForStmt,
    iterableExpr: Expr,
    iterableType: Type,
    condLabel: String,
    bodyLabel: String,
    endLabel: String,
) {
    val iterableTypeName = iterableType.simpleTypeName()
    val hasIterMethod = methodReturnTypes.containsKey("${iterableTypeName}_iter")

    val objReg = emitExpr(iterableExpr)
    val itLt = "i8*"

    val itReg: String = if (hasIterMethod) {
        val r = fresh("it")
        emit("  $r = call i8* @nv_${iterableTypeName}_iter(i8* noundef $objReg)")
        r
    } else {
        objReg
    }

    val itTypeName = if (hasIterMethod) {
        iteratorClassNames[iterableTypeName] ?: iterableTypeName
    } else {
        iterableTypeName
    }

    val itAlloca = emitAlloca("it.ptr.${labelIdx}", itLt)
    emitStore(itLt, itReg, itAlloca)

    emit("  br label %$condLabel")

    emitRaw("$condLabel:")
    isTerminated = false

    val itLoaded = fresh("it.loaded")
    emit("  $itLoaded = load i8*, i8** $itAlloca, align 8")

    val nextRetLt = methodReturnTypes["${itTypeName}_next"] ?: "i8*"
    val nextVal   = fresh("next.val")
    emit("  $nextVal = call $nextRetLt @nv_${itTypeName}_next(i8* noundef $itLoaded)")

    val nilCmp  = fresh("is.nil")
    emit("  $nilCmp = icmp eq $nextRetLt $nextVal, ${nullConstant(nextRetLt)}")
    emit("  br i1 $nilCmp, label %$endLabel, label %$bodyLabel")

    emitRaw("$bodyLabel:")
    isTerminated = false

    val bindName = when (val b = stmt.binding) {
        is IdentBinding -> b.name
        is TupleBinding -> b.names.firstOrNull() ?: "_"
    }
    if (bindName != "_") {
        val bindAlloca = emitAlloca(bindName, nextRetLt)
        varAllocas[bindName] = Pair(bindAlloca, nextRetLt)
        emitStore(nextRetLt, nextVal, bindAlloca)
    }

    stmt.body.forEach { emitStmt(it) }

    if (!isTerminated) emit("  br label %$condLabel")
}

private fun LlvmIrEmitter.emitAssignStmt(stmt: AssignStmt) {
    val valReg = emitExpr(stmt.value)
    when (stmt.target) {
        is IdentExpr -> {
            val name = stmt.target.name
            val entry = varAllocas[name]
            if (entry != null) {
                val (allocaReg, lt) = entry
                val coerced = if (stmt.op == AssignOp.ASSIGN) {
                    coerceToType(valReg, typeOf(stmt.value), lt)
                } else {
                    val cur = emitLoad(lt, allocaReg, "cur")
                    val valCoerced = coerceToType(valReg, typeOf(stmt.value), lt)
                    val res = fresh("res")
                    val op = assignOpToLlvm(stmt.op, lt)
                    emit("  $res = $op $lt $cur, $valCoerced")
                    res
                }
                emitStore(lt, coerced, allocaReg)
            } else {
                emitSelfFieldStore(name, valReg, typeOf(stmt.value), stmt.op)
            }
        }
        is IndexExpr -> { /* Phase 1.5: simplified */ }
        is MemberAccessExpr -> {
            val receiverExpr = stmt.target.receiver
            val fieldName    = stmt.target.member
            val receiverType = typeOf(receiverExpr)
            val typeName     = if (receiverType is Type.TNamed) receiverType.qualifiedName.substringAfterLast('.') else ""
            if (typeName.isNotEmpty() && structLayouts.containsKey(typeName)) {
                val fields = structLayouts[typeName]!!
                val fieldIdx = fields.indexOfFirst { it.first == fieldName }
                if (fieldIdx >= 0) {
                    val fieldType = fields[fieldIdx].second
                    val fieldLt   = llvmType(fieldType)
                    val gepIdx    = fieldIdx + (if (typeName in classTypeNames) 2 else 0)
                    val structType = "%struct.$typeName"
                    val recvReg    = emitExpr(receiverExpr)
                    val castR = fresh("sa.cast")
                    emit("  $castR = bitcast i8* $recvReg to $structType*")
                    val fpR = fresh("sa.fp")
                    emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
                    val coerced = if (stmt.op == AssignOp.ASSIGN) {
                        coerceToType(valReg, typeOf(stmt.value), fieldLt)
                    } else {
                        val cur = emitLoad(fieldLt, fpR, "cur")
                        val valCoerced = coerceToType(valReg, typeOf(stmt.value), fieldLt)
                        val res = fresh("res")
                        val op = assignOpToLlvm(stmt.op, fieldLt)
                        emit("  $res = $op $fieldLt $cur, $valCoerced")
                        res
                    }
                    emit("  store $fieldLt $coerced, $fieldLt* $fpR, align ${llvmTypeAlign(fieldLt)}")
                }
            }
        }
        else -> { /* unsupported lvalue */ }
    }
}

private fun LlvmIrEmitter.emitSelfFieldStore(fieldName: String, valReg: String, valType: Type, op: AssignOp) {
    val selfType = varTypes["self"] as? Type.TNamed ?: return
    val ownerName = selfType.qualifiedName.substringAfterLast('.')
    val allFields = structLayouts[ownerName] ?: return
    val fieldIdx  = allFields.indexOfFirst { it.first == fieldName }
    if (fieldIdx < 0) return
    val (_, ft) = allFields[fieldIdx]
    val fieldLt  = llvmType(ft)
    val gepIdx   = fieldIdx + (if (ownerName in classTypeNames) 2 else 0)
    val structType = "%struct.$ownerName"
    val selfReg    = emitLoad("i8*", varAllocas["self"]!!.first)
    val castR = fresh("ssf.cast")
    emit("  $castR = bitcast i8* $selfReg to $structType*")
    val fpR = fresh("ssf.fp")
    emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
    val coerced = if (op == AssignOp.ASSIGN) {
        coerceToType(valReg, valType, fieldLt)
    } else {
        val cur = emitLoad(fieldLt, fpR, "cur")
        val valCoerced = coerceToType(valReg, valType, fieldLt)
        val res = fresh("res")
        val assignOp = assignOpToLlvm(op, fieldLt)
        emit("  $res = $assignOp $fieldLt $cur, $valCoerced")
        res
    }
    emit("  store $fieldLt $coerced, $fieldLt* $fpR, align ${llvmTypeAlign(fieldLt)}")
}

private fun assignOpToLlvm(op: AssignOp, lt: String): String = when (op) {
    AssignOp.PLUS_ASSIGN      -> if (lt == "double" || lt == "float") "fadd" else "add"
    AssignOp.MINUS_ASSIGN     -> if (lt == "double" || lt == "float") "fsub" else "sub"
    AssignOp.STAR_ASSIGN      -> if (lt == "double" || lt == "float") "fmul" else "mul"
    AssignOp.SLASH_ASSIGN     -> if (lt == "double" || lt == "float") "fdiv" else "sdiv"
    AssignOp.INT_DIV_ASSIGN   -> "sdiv"
    AssignOp.MOD_ASSIGN       -> "srem"
    AssignOp.AMP_ASSIGN       -> "and"
    AssignOp.PIPE_ASSIGN      -> "or"
    AssignOp.XOR_ASSIGN       -> "xor"
    AssignOp.LSHIFT_ASSIGN    -> "shl"
    AssignOp.RSHIFT_ASSIGN    -> "ashr"
    else                      -> "add"
}

private fun LlvmIrEmitter.emitBreakStmt(stmt: BreakStmt) {
    val target = if (stmt.label != null) loopStack.lastOrNull { it.userLabel == stmt.label }
                 else loopStack.lastOrNull()
    if (target != null) terminate("  br label %${target.endLabel}")
}

private fun LlvmIrEmitter.emitContinueStmt(stmt: ContinueStmt) {
    val target = if (stmt.label != null) loopStack.lastOrNull { it.userLabel == stmt.label }
                 else loopStack.lastOrNull()
    if (target != null) terminate("  br label %${target.condLabel}")
}

private fun LlvmIrEmitter.emitGuardLetStmt(stmt: GuardLetStmt) {
    val srcReg  = emitExpr(stmt.value)
    val srcType = typeOf(stmt.value)
    val innerLt = llvmType((srcType as? Type.TNullable)?.inner ?: srcType)

    val okLabel   = freshLabel("guard.ok")
    val elseLabel = freshLabel("guard.else")

    val isNull = fresh("isnull")
    emit("  $isNull = icmp eq $innerLt $srcReg, ${nullConstant(innerLt)}")
    emit("  br i1 $isNull, label %$elseLabel, label %$okLabel")

    emitRaw("$elseLabel:")
    isTerminated = false
    stmt.elseBody.forEach { emitStmt(it) }
    if (!isTerminated) {
        if (fnReturnType == "void" || fnReturnType == "i32") terminate("  ret $fnReturnType ${if (fnReturnType == "i32") "0" else ""}")
        else terminate("  ret $fnReturnType ${defaultValue(fnReturnType)}")
    }

    emitRaw("$okLabel:")
    isTerminated = false

    val allocaReg = emitAlloca(stmt.name, innerLt)
    varAllocas[stmt.name] = Pair(allocaReg, innerLt)
    emitStore(innerLt, srcReg, allocaReg)
}

private fun LlvmIrEmitter.emitTryCatchStmt(stmt: TryCatchStmt) {
    stmt.tryBody.forEach { emitStmt(it) }
    stmt.finallyBody?.forEach { emitStmt(it) }
}

// ── Concurrency ───────────────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitGoStmt(stmt: GoStmt) {
    when (val body = stmt.body) {
        is GoExprBody  -> { emitExpr(body.expr); Unit }
        is GoBlockBody -> body.stmts.forEach { emitStmt(it) }
    }
}

private fun LlvmIrEmitter.emitSelectStmt(stmt: SelectStmt) {
    for (arm in stmt.arms) {
        when (arm) {
            is ReceiveSelectArm -> arm.body.forEach { emitStmt(it) }
            is AfterSelectArm   -> arm.body.forEach { emitStmt(it) }
            is DefaultSelectArm -> arm.body.forEach { emitStmt(it) }
        }
    }
}

internal fun LlvmIrEmitter.emitAwaitExpr(expr: AwaitExpr): String = emitExpr(expr.operand)

internal fun LlvmIrEmitter.emitSpawnExprValue(expr: SpawnExpr): String {
    emitExpr(expr.expr)
    val result = fresh("spawn")
    emit("  $result = call i8* @malloc(i64 8)")
    return result
}

// ── Inline assembly / raw bytes (Phase 2.2) ───────────────────────────────────

private fun LlvmIrEmitter.emitAsmStmt(stmt: AsmStmt) {
    if (stmt.arch != targetArch || isTerminated) return
    val asmStr = stmt.instructions.joinToString("\\0A\\09") { escapeAsmStr(it) }
    val clobberConstraints = stmt.clobbers.joinToString(",") { "~{$it}" }
    emit("  call void asm sideeffect \"$asmStr\", \"$clobberConstraints\"()")
}

private fun LlvmIrEmitter.emitBytesStmt(stmt: BytesStmt) {
    if (stmt.arch != targetArch || isTerminated || stmt.bytes.isEmpty()) return
    val byteStr = stmt.bytes.joinToString(", ") { "0x%02x".format(it) }
    emit("  call void asm sideeffect \".byte $byteStr\", \"\"()")
}

private fun escapeAsmStr(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\22").replace("\n", "\\0A").replace("\t", "\\09")
