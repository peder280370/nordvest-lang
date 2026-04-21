package nv.compiler.codegen

import nv.compiler.parser.*
import nv.compiler.typecheck.Type
import nv.compiler.typecheck.isFloatLike

// ─────────────────────────────────────────────────────────────────────────────
// Expressions — all emitXxxExpr methods plus the top-level emitExpr dispatcher.
// ─────────────────────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitExpr(expr: Expr): String = when (expr) {
    is IntLitExpr             -> expr.text.trimEnd('L', 'l').toLongOrNull()?.let { it.toString() } ?: "0"
    is FloatLitExpr           -> normalizeFloat(expr.text)
    is BoolLitExpr            -> if (expr.value) "1" else "0"
    is NilExpr                -> "null"
    is ConstPiExpr            -> "3.141592653589793"
    is ConstEExpr             -> "2.718281828459045"
    is ConstInfExpr           -> "0x7FF0000000000000"
    is CharLitExpr            -> emitCharLit(expr)
    is RawStringExpr          -> {
        // Token text includes delimiters: r"..." or r"""...""" — strip them.
        val raw = when {
            expr.text.startsWith("r\"\"\"") -> expr.text.removePrefix("r\"\"\"").removeSuffix("\"\"\"")
            expr.text.startsWith("r\"")     -> expr.text.removePrefix("r\"").removeSuffix("\"")
            else                            -> expr.text
        }
        stringConst(raw)
    }
    is InterpolatedStringExpr -> emitInterpolatedString(expr)
    is IdentExpr              -> emitIdentExpr(expr)
    is ParenExpr              -> emitExpr(expr.inner)
    is BinaryExpr             -> emitBinaryExpr(expr)
    is UnaryExpr              -> emitUnaryExpr(expr)
    is CallExpr               -> emitCallExpr(expr)
    is MemberAccessExpr       -> emitMemberAccessExpr(expr)
    is SafeNavExpr            -> emitSafeNavExpr(expr)
    is InlineIfExpr           -> emitInlineIfExpr(expr)
    is ForceUnwrapExpr        -> emitForceUnwrapExpr(expr)
    is ResultPropagateExpr    -> emitExpr(expr.operand)
    is TypeTestExpr           -> emitTypeTestExpr(expr)
    is SafeCastExpr           -> emitExpr(expr.operand)
    is ForceCastExpr          -> emitExpr(expr.operand)
    is ArrayLiteralExpr       -> emitArrayLiteral(expr)
    is MapLiteralExpr         -> "null"
    is EmptyMapExpr           -> "null"
    is TupleLiteralExpr       -> emitTupleLiteral(expr)
    is RangeExpr              -> "null"
    is MatchExpr              -> { emitMatchExpr(expr); "0" }
    is LambdaExpr             -> "null"
    is WildcardExpr           -> "0"
    is IndexExpr              -> emitIndexExpr(expr)
    is QuantifierExpr         -> emitQuantifierExpr(expr)
    is ListComprehensionExpr  -> "null"
    is AwaitExpr              -> emitAwaitExpr(expr)
    is SpawnExpr              -> emitSpawnExprValue(expr)
    is BuilderCallExpr        -> emitBuilderCallExpr(expr)
    else                      -> "0"
}

// ── Literals ──────────────────────────────────────────────────────────────────

private fun emitCharLit(expr: CharLitExpr): String {
    val text = expr.text
    return when {
        text.length == 1 -> text[0].code.toString()
        text.startsWith("\\") -> when (text) {
            "\\n"  -> "10"; "\\t" -> "9"; "\\r" -> "13"; "\\0" -> "0"
            "\\\\" -> "92"; "\\'" -> "39"; "\\\"" -> "34"
            else   -> "0"
        }
        else -> "0"
    }
}

private fun normalizeFloat(text: String): String {
    val stripped = text.trimEnd('f', 'F')
    return if ('.' in stripped || 'e' in stripped.lowercase()) stripped else "$stripped.0"
}

// ── Identifier ────────────────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitIdentExpr(expr: IdentExpr): String {
    val name = expr.name
    val entry = varAllocas[name]
    if (entry != null) {
        val (allocaReg, lt) = entry
        if (lt == "void") return "0"
        return emitLoad(lt, allocaReg)
    }
    val selfType = varTypes["self"]
    if (selfType is Type.TNamed) {
        val ownerName = selfType.qualifiedName.substringAfterLast('.')
        val allFields = structLayouts[ownerName]
        val ctorCount = structCtorParamCount[ownerName] ?: 0
        if (allFields != null) {
            val fieldIdx = allFields.indexOfFirst { it.first == name && allFields.indexOf(it) < ctorCount }
            if (fieldIdx in 0 until ctorCount) {
                val (_, ft) = allFields[fieldIdx]
                val lt = llvmType(ft)
                val fieldOffset = if (ownerName in classTypeNames) 2 else 0
                val gepIdx = fieldIdx + fieldOffset
                val structType = "%struct.$ownerName"
                val selfReg = emitLoad("i8*", varAllocas["self"]!!.first)
                val castR = fresh("idf.cast")
                emit("  $castR = bitcast i8* $selfReg to $structType*")
                val fpR = fresh("idf.fp")
                emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
                val fvR = fresh("idf.fv")
                emit("  $fvR = load $lt, $lt* $fpR, align ${llvmTypeAlign(lt)}")
                return fvR
            }
        }
    }
    val symType = tcModule.resolvedModule.moduleScope.lookup(name)?.resolvedType
    if (symType is Type.TNamed) {
        val res = fresh("call")
        emit("  $res = call i8* @nv_$name()")
        return res
    }
    return "0"
}

// ── Interpolated string ───────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitInterpolatedString(expr: InterpolatedStringExpr): String {
    if (expr.parts.isEmpty()) return stringConst("")
    if (expr.parts.size == 1 && expr.parts[0] is StringTextPart) {
        return stringConst((expr.parts[0] as StringTextPart).text)
    }
    var acc: String? = null
    for (part in expr.parts) {
        val partStr = when (part) {
            is StringTextPart -> {
                if (part.text.isEmpty()) continue
                stringConst(part.text)
            }
            is StringInterpolationPart -> {
                val valReg = emitExpr(part.expr)
                convertToStr(valReg, typeOf(part.expr))
            }
            else -> continue
        }
        acc = if (acc == null) partStr
              else {
                  val res = fresh("concat")
                  emit("  $res = call i8* @nv_str_concat(i8* $acc, i8* $partStr)")
                  res
              }
    }
    return acc ?: stringConst("")
}

// ── String conversion ─────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.convertToStr(reg: String, ty: Type): String = when (ty) {
    Type.TStr                  -> reg
    Type.TInt, Type.TInt64     -> { val r = fresh("i2s"); emit("  $r = call i8* @nv_int_to_str(i64 $reg)"); r }
    Type.TFloat, Type.TFloat64 -> { val r = fresh("f2s"); emit("  $r = call i8* @nv_float_to_str(double $reg)"); r }
    Type.TFloat32              -> {
        val ext = fresh("fext"); emit("  $ext = fpext float $reg to double")
        val r = fresh("f2s"); emit("  $r = call i8* @nv_float_to_str(double $ext)"); r
    }
    Type.TBool                 -> { val r = fresh("b2s"); emit("  $r = call i8* @nv_bool_to_str(i1 $reg)"); r }
    else                       -> reg
}

// ── Binary expressions ────────────────────────────────────────────────────────

private fun binaryOpSymbol(op: BinaryOp): String = when (op) {
    BinaryOp.PLUS -> "+"; BinaryOp.MINUS -> "-"; BinaryOp.STAR -> "*"
    BinaryOp.SLASH -> "/"; BinaryOp.EQ -> "=="; BinaryOp.NEQ -> "!="
    BinaryOp.LT -> "<"; BinaryOp.GT -> ">"; BinaryOp.LEQ -> "<="
    BinaryOp.GEQ -> ">="; BinaryOp.MOD -> "%"; BinaryOp.POWER -> "^"
    BinaryOp.BIT_AND -> "&"; BinaryOp.BIT_OR -> "|"; BinaryOp.BIT_XOR -> "⊕"
    else -> op.name
}

internal fun operatorToSuffix(name: String): String? = when (name) {
    "+"  -> "plus"; "-" -> "minus"; "*" -> "mul"; "/" -> "div"
    "==" -> "eq"; "!=" -> "neq"; "<" -> "lt"; ">" -> "gt"
    "<=" -> "le"; ">=" -> "ge"; "%" -> "mod"; "^" -> "pow"
    "&"  -> "bitand"; "|" -> "bitor"; "⊕" -> "xor"
    else -> null
}

private fun LlvmIrEmitter.emitBinaryExpr(expr: BinaryExpr): String {
    if (expr.op == BinaryOp.AND) return emitShortCircuit(expr, isAnd = true)
    if (expr.op == BinaryOp.OR)  return emitShortCircuit(expr, isAnd = false)
    if (expr.op == BinaryOp.NULL_COALESCE) return emitNullCoalesce(expr)
    if (expr.op == BinaryOp.PIPELINE) return emitPipeline(expr)

    val leftTypeForOp = typeOf(expr.left)
    if (leftTypeForOp is Type.TNamed) {
        val opSym = binaryOpSymbol(expr.op)
        val opKey = "${leftTypeForOp.qualifiedName}.$opSym"
        val overload = tcModule.memberTypeMap[opKey]
        if (overload is Type.TFun && overload.params.size == 1) {
            val lReg = emitExpr(expr.left)
            val rReg = emitExpr(expr.right)
            val rType = typeOf(expr.right)
            val retLt = llvmType(overload.returnType)
            val typeName = leftTypeForOp.qualifiedName.substringAfterLast('.')
            val suffix = operatorToSuffix(opSym)
            val mangledFn = if (suffix != null) "@nv_${typeName}_op_$suffix" else "@nv_${typeName}_op_custom"
            val res = fresh("opol")
            emit("  $res = call $retLt $mangledFn(i8* noundef $lReg, ${llvmType(rType)} noundef $rReg)")
            return res
        }
    }

    val leftReg  = emitExpr(expr.left)
    val rightReg = emitExpr(expr.right)
    val lt = typeOf(expr.left)
    val rt = typeOf(expr.right)

    if (expr.op == BinaryOp.PLUS && (lt == Type.TStr || rt == Type.TStr)) {
        val lStr = convertToStr(leftReg, lt)
        val rStr = convertToStr(rightReg, rt)
        val res = fresh("strcat")
        emit("  $res = call i8* @nv_str_concat(i8* $lStr, i8* $rStr)")
        return res
    }

    if (expr.op == BinaryOp.EQ && lt == Type.TStr && rt == Type.TStr) {
        val res = fresh("streq")
        emit("  $res = call i1 @nv_str_eq(i8* $leftReg, i8* $rightReg)")
        return res
    }
    if (expr.op == BinaryOp.NEQ && lt == Type.TStr && rt == Type.TStr) {
        val eq = fresh("streq")
        emit("  $eq = call i1 @nv_str_eq(i8* $leftReg, i8* $rightReg)")
        val res = fresh("strneq")
        emit("  $res = xor i1 $eq, 1")
        return res
    }

    val isFloat = lt.isFloatLike || rt.isFloatLike
    val llvmLt  = if (isFloat) "double" else "i64"
    val lCoerced = coerceForArith(leftReg, lt, isFloat)
    val rCoerced = coerceForArith(rightReg, rt, isFloat)

    val res = fresh("binop")
    when (expr.op) {
        BinaryOp.PLUS    -> emit("  $res = ${if (isFloat) "fadd" else "add"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.MINUS   -> emit("  $res = ${if (isFloat) "fsub" else "sub"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.STAR    -> emit("  $res = ${if (isFloat) "fmul" else "mul"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.SLASH   -> emit("  $res = ${if (isFloat) "fdiv" else "sdiv"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.INT_DIV -> emit("  $res = sdiv i64 $lCoerced, $rCoerced")
        BinaryOp.MOD     -> emit("  $res = ${if (isFloat) "frem" else "srem"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.POWER   -> {
            val la = coerceForArith(leftReg, lt, forceFloat = true)
            val ra = coerceForArith(rightReg, rt, forceFloat = true)
            emit("  $res = call double @pow(double noundef $la, double noundef $ra)")
            return res
        }
        BinaryOp.BIT_AND -> emit("  $res = and i64 $lCoerced, $rCoerced")
        BinaryOp.BIT_OR  -> emit("  $res = or i64 $lCoerced, $rCoerced")
        BinaryOp.BIT_XOR -> emit("  $res = xor i64 $lCoerced, $rCoerced")
        BinaryOp.LSHIFT  -> emit("  $res = shl i64 $lCoerced, $rCoerced")
        BinaryOp.RSHIFT  -> emit("  $res = ashr i64 $lCoerced, $rCoerced")
        BinaryOp.EQ      -> emit("  $res = ${if (isFloat) "fcmp oeq" else "icmp eq"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.NEQ     -> emit("  $res = ${if (isFloat) "fcmp one" else "icmp ne"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.LT      -> emit("  $res = ${if (isFloat) "fcmp olt" else "icmp slt"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.GT      -> emit("  $res = ${if (isFloat) "fcmp ogt" else "icmp sgt"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.LEQ     -> emit("  $res = ${if (isFloat) "fcmp ole" else "icmp sle"} $llvmLt $lCoerced, $rCoerced")
        BinaryOp.GEQ     -> emit("  $res = ${if (isFloat) "fcmp oge" else "icmp sge"} $llvmLt $lCoerced, $rCoerced")
        else             -> emit("  $res = add i64 0, 0")
    }
    return res
}

private fun LlvmIrEmitter.emitShortCircuit(expr: BinaryExpr, isAnd: Boolean): String {
    val lhsReg  = emitExpr(expr.left)
    val lhsI1   = coerceToI1(lhsReg, typeOf(expr.left))
    val rhsLabel   = freshLabel(if (isAnd) "and.rhs" else "or.rhs")
    val shortLabel = freshLabel(if (isAnd) "and.short" else "or.short")
    val mergeLabel = freshLabel(if (isAnd) "and.merge" else "or.merge")

    if (isAnd) emit("  br i1 $lhsI1, label %$rhsLabel, label %$shortLabel")
    else        emit("  br i1 $lhsI1, label %$shortLabel, label %$rhsLabel")
    val lhsBlock = shortLabel

    emitRaw("$rhsLabel:")
    isTerminated = false
    val rhsReg = emitExpr(expr.right)
    val rhsI1  = coerceToI1(rhsReg, typeOf(expr.right))
    emit("  br label %$mergeLabel")

    emitRaw("$lhsBlock:")
    isTerminated = false
    emit("  br label %$mergeLabel")

    emitRaw("$mergeLabel:")
    isTerminated = false
    val res = fresh("scres")
    val shortVal = if (isAnd) "0" else "1"
    emit("  $res = phi i1 [ $rhsI1, %$rhsLabel ], [ $shortVal, %$lhsBlock ]")
    return res
}

private fun LlvmIrEmitter.emitNullCoalesce(expr: BinaryExpr): String {
    val lhsReg  = emitExpr(expr.left)
    val lhsType = typeOf(expr.left)
    val innerLt = llvmType((lhsType as? Type.TNullable)?.inner ?: lhsType)

    val notNullLabel = freshLabel("nn.ok")
    val nullLabel    = freshLabel("nn.null")
    val mergeLabel   = freshLabel("nn.merge")

    val isNull = fresh("isnull")
    emit("  $isNull = icmp eq $innerLt $lhsReg, ${nullConstant(innerLt)}")
    emit("  br i1 $isNull, label %$nullLabel, label %$notNullLabel")

    emitRaw("$notNullLabel:"); isTerminated = false
    emit("  br label %$mergeLabel")

    emitRaw("$nullLabel:"); isTerminated = false
    val rhsReg = emitExpr(expr.right)
    emit("  br label %$mergeLabel")

    emitRaw("$mergeLabel:"); isTerminated = false
    val res = fresh("coalesce")
    emit("  $res = phi $innerLt [ $lhsReg, %$notNullLabel ], [ $rhsReg, %$nullLabel ]")
    return res
}

private fun LlvmIrEmitter.emitPipeline(expr: BinaryExpr): String {
    val lhsReg  = emitExpr(expr.left)
    val lhsType = typeOf(expr.left)
    val lhsLt   = llvmType(lhsType).let { if (it == "void") "i64" else it }
    val pipeSlot = emitAlloca("pipe.${tempIdx}", lhsLt)
    emitStore(lhsLt, lhsReg, pipeSlot)

    return when (val rhs = expr.right) {
        is CallExpr -> {
            val hasWildcard = rhs.args.any { it.expr is WildcardExpr }
            val argRegs   = mutableListOf<String>()
            val argTypes  = mutableListOf<Type>()
            if (!hasWildcard) { argRegs.add(lhsReg); argTypes.add(lhsType) }
            for (arg in rhs.args) {
                if (arg.expr is WildcardExpr) { argRegs.add(lhsReg); argTypes.add(lhsType) }
                else { argRegs.add(emitExpr(arg.expr)); argTypes.add(typeOf(arg.expr)) }
            }
            if (rhs.callee is IdentExpr) {
                val fnName = rhs.callee.name
                when (fnName) {
                    "print"   -> { for ((r, t) in argRegs.zip(argTypes)) emitPrintValue(r, t, false); "0" }
                    "println" -> { for ((r, t) in argRegs.zip(argTypes)) emitPrintValue(r, t, true);  "0" }
                    else      -> emitBuiltinOrUserCall(fnName, argRegs, argTypes)
                }
            } else {
                emitBuiltinOrUserCall("unknown", argRegs, argTypes)
            }
        }
        is IdentExpr -> emitBuiltinOrUserCall(rhs.name, listOf(lhsReg), listOf(lhsType))
        else -> lhsReg
    }
}

// ── Unary ─────────────────────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitUnaryExpr(expr: UnaryExpr): String {
    val operandReg = emitExpr(expr.operand)
    val ty = typeOf(expr.operand)
    val res = fresh("unary")
    when (expr.op) {
        UnaryOp.NEGATE -> {
            if (ty.isFloatLike) emit("  $res = fneg double $operandReg")
            else emit("  $res = sub i64 0, $operandReg")
        }
        UnaryOp.NOT -> {
            val i1 = coerceToI1(operandReg, ty)
            emit("  $res = xor i1 $i1, 1")
            return res
        }
        UnaryOp.BIT_NOT -> emit("  $res = xor i64 $operandReg, -1")
    }
    return res
}

// ── Call expressions ──────────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitCallExpr(expr: CallExpr): String {
    if (expr.callee is IdentExpr) {
        when (expr.callee.name) {
            "print"   -> return emitPrintCall(expr, newline = false)
            "println" -> return emitPrintCall(expr, newline = true)
            "str"     -> return emitStrConversion(expr)
            // Defer to @extern declaration if the user re-declares assert (e.g. std.test's nv_assert).
            "assert"  -> if ("assert" !in externFunctions) return emitAssert(expr)
            "panic"   -> return emitPanicCall(expr)
            "len"     -> return emitLenCall(expr)
        }
    }
    if (expr.callee is MemberAccessExpr) return emitMethodCall(expr, expr.callee)
    if (expr.callee is IdentExpr) {
        val fnName = expr.callee.name
        val argRegs  = expr.args.map { emitExpr(it.expr) }
        val argTypes = expr.args.map { typeOf(it.expr) }
        return emitBuiltinOrUserCall(fnName, argRegs, argTypes)
    }
    return "0"
}

internal fun LlvmIrEmitter.emitBuiltinOrUserCall(fnName: String, argRegs: List<String>, argTypes: List<Type>): String {
    val ext = externFunctions[fnName]
    if (ext != null) {
        val ptrSig = inlineRuntimeFnPtrSigs[ext.cSymbol]
        if (ptrSig != null) {
            val (actualRetLt, actualParamLts) = ptrSig
            val castArgList = argRegs.indices.joinToString(", ") { i ->
                val r = argRegs[i]
                val paramLt = actualParamLts.getOrElse(i) { llvmType(argTypes.getOrElse(i) { Type.TInt }) }
                val srcLt = argTypes.getOrNull(i)?.let { llvmType(it) } ?: "i64"
                if (paramLt == "i8*" && srcLt != "i8*") {
                    val castReg = fresh("itp")
                    emit("  $castReg = inttoptr $srcLt $r to i8*")
                    "i8* $castReg"
                } else {
                    "$paramLt $r"
                }
            }
            if (actualRetLt == "void") { emit("  call void @${ext.cSymbol}($castArgList)"); return "0" }
            val callRes = fresh("extcall")
            emit("  $callRes = call $actualRetLt @${ext.cSymbol}($castArgList)")
            val declRetLt = llvmType(ext.retType)
            if (declRetLt == "i64" && actualRetLt == "i8*") {
                val ptiReg = fresh("pti")
                emit("  $ptiReg = ptrtoint i8* $callRes to i64")
                return ptiReg
            }
            return callRes
        }
        val retLt = llvmType(ext.retType)
        val argList = if (ext.paramTypes.isNotEmpty()) {
            argRegs.zip(ext.paramTypes).joinToString(", ") { (r, t) -> "${llvmType(t)} noundef $r" }
        } else {
            argRegs.zip(argTypes).joinToString(", ") { (r, t) -> "${llvmType(t)} noundef $r" }
        }
        if (retLt == "void") { emit("  call void @${ext.cSymbol}($argList)"); return "0" }
        val res = fresh("extcall")
        emit("  $res = call $retLt @${ext.cSymbol}($argList)")
        return res
    }

    when (fnName) {
        "eprintln" -> {
            val strReg = if (argRegs.isNotEmpty()) {
                val r = argRegs[0]; val t = argTypes.getOrElse(0) { Type.TStr }
                if (t == Type.TStr) r else convertToStr(r, t)
            } else stringConst("")
            emit("  call void @nv_eprintln(i8* $strReg)")
            return "0"
        }
        "readLine" -> { val res = fresh("rl"); emit("  $res = call i8* @nv_read_line()"); return res }
        "readAll"  -> { val res = fresh("ra"); emit("  $res = call i8* @nv_read_all()"); return res }
    }

    val mangledName = "@nv_$fnName"
    val fnSymType = tcModule.resolvedModule.moduleScope.lookup(fnName)?.resolvedType
    val fnRetType = (fnSymType as? Type.TFun)?.returnType
    val retLt = when {
        fnName in structLayouts -> "i8*"
        else -> fnRetType?.let { llvmType(it) } ?: "i64"
    }
    val (finalArgRegs, finalArgTypes) = if (fnName in structLayouts) {
        val ctorParamCount = structCtorParamCount[fnName] ?: 0
        val allFields      = structLayouts[fnName]!!.take(ctorParamCount)
        val defaults       = structCtorDefaults[fnName] ?: emptyList()
        val regs  = argRegs.toMutableList()
        val types = argTypes.toMutableList()
        while (regs.size < ctorParamCount) {
            val fi = regs.size
            val (_, ft) = allFields[fi]
            val lt = llvmType(ft)
            val defExpr = defaults.getOrNull(fi)?.second
            regs  += if (defExpr != null) emitExpr(defExpr) else defaultValue(lt)
            types += ft
        }
        regs to types
    } else {
        argRegs to argTypes
    }
    val argList = finalArgRegs.zip(finalArgTypes).joinToString(", ") { (r, t) -> "${llvmType(t)} noundef $r" }
    if (retLt == "void") { emit("  call void $mangledName($argList)"); return "0" }
    val res = fresh("call")
    emit("  $res = call $retLt $mangledName($argList)")
    return res
}

private fun LlvmIrEmitter.emitMethodCall(callExpr: CallExpr, memberExpr: MemberAccessExpr): String {
    val receiverReg  = emitExpr(memberExpr.receiver)
    val receiverType = typeOf(memberExpr.receiver)
    val member       = memberExpr.member

    if (member == "str" || member == "toString") {
        val tn = receiverType.simpleTypeName()
        if (tn.isEmpty() || !methodReturnTypes.containsKey("${tn}_toString")) {
            return convertToStr(receiverReg, receiverType)
        }
    }

    if (member == "configLoad") {
        val typeName = (memberExpr.receiver as? IdentExpr)?.name ?: receiverType.simpleTypeName()
        val res = fresh("cfgld")
        emit("  $res = call i8* @nv_${typeName}_config_load()")
        return res
    }

    if (receiverType == Type.TStr) {
        when (member) {
            "length", "len", "count" -> {
                val res = fresh("strlen")
                emit("  $res = call i64 @nv_str_len(i8*$receiverReg)")
                return res
            }
        }
    }

    val typeName  = receiverType.simpleTypeName()
    val argRegs   = callExpr.args.map { emitExpr(it.expr) }
    val argTypes  = callExpr.args.map { typeOf(it.expr) }
    val mangledFn = "@nv_${typeName}_$member"
    val receiverLt = llvmType(receiverType)
    val argList = buildString {
        append("$receiverLt noundef $receiverReg")
        for ((r, t) in argRegs.zip(argTypes)) append(", ${llvmType(t)} noundef $r")
    }
    val methodRetLt = methodReturnTypes["${typeName}_$member"] ?: "i64"
    if (methodRetLt == "void") { emit("  call void $mangledFn($argList)"); return "0" }
    val res = fresh("mcall")
    emit("  $res = call $methodRetLt $mangledFn($argList)")
    return res
}

// ── Print helpers ─────────────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitPrintCall(expr: CallExpr, newline: Boolean): String {
    val allArgs = expr.args.map { it.expr }
    for (argExpr in allArgs) {
        val argReg = emitExpr(argExpr)
        emitPrintValue(argReg, typeOf(argExpr), newline)
    }
    return "0"
}

private fun LlvmIrEmitter.emitPrintValue(reg: String, ty: Type, newline: Boolean) {
    val suffix = if (newline) "ln" else ""
    when (ty) {
        Type.TStr              -> emit("  call void @nv_print${suffix}(i8* $reg)")
        Type.TInt, Type.TInt64 -> emit("  call void @nv_print${suffix}_int(i64 $reg)")
        Type.TFloat, Type.TFloat64 -> emit("  call void @nv_print${suffix}_float(double $reg)")
        Type.TFloat32          -> {
            val ext = fresh("fext"); emit("  $ext = fpext float $reg to double")
            emit("  call void @nv_print${suffix}_float(double $ext)")
        }
        Type.TBool             -> emit("  call void @nv_print${suffix}_bool(i1 $reg)")
        is Type.TNullable      -> {
            val innerLt = llvmType(ty.inner)
            val notNullLabel = freshLabel("prt.notnull")
            val nullLabel    = freshLabel("prt.null")
            val doneLabel    = freshLabel("prt.done")
            val isNull = fresh("isnull")
            emit("  $isNull = icmp eq $innerLt $reg, ${nullConstant(innerLt)}")
            emit("  br i1 $isNull, label %$nullLabel, label %$notNullLabel")
            emitRaw("$notNullLabel:"); isTerminated = false
            emitPrintValue(reg, ty.inner, newline)
            emit("  br label %$doneLabel")
            emitRaw("$nullLabel:"); isTerminated = false
            val nilGep = stringConst("nil")
            emit("  call void @nv_print${suffix}(i8* $nilGep)")
            emit("  br label %$doneLabel")
            emitRaw("$doneLabel:"); isTerminated = false
        }
        else -> {
            val strReg = convertToStr(reg, ty)
            emit("  call void @nv_print${suffix}(i8* $strReg)")
        }
    }
}

private fun LlvmIrEmitter.emitStrConversion(expr: CallExpr): String {
    val argExpr = expr.args.firstOrNull()?.expr ?: return stringConst("")
    return convertToStr(emitExpr(argExpr), typeOf(argExpr))
}

private fun LlvmIrEmitter.emitAssert(expr: CallExpr): String {
    val condExpr = expr.args.firstOrNull()?.expr ?: return "0"
    val condReg  = emitExpr(condExpr)
    val condI1   = coerceToI1(condReg, typeOf(condExpr))
    val okLabel   = freshLabel("assert.ok")
    val failLabel = freshLabel("assert.fail")
    emit("  br i1 $condI1, label %$okLabel, label %$failLabel")
    emitRaw("$failLabel:"); isTerminated = false
    val msgConst = stringConst("assertion failed")
    emit("  call void @nv_panic(i8*$msgConst)")
    terminate("  unreachable")
    emitRaw("$okLabel:"); isTerminated = false
    return "0"
}

private fun LlvmIrEmitter.emitPanicCall(expr: CallExpr): String {
    val msgExpr = expr.args.firstOrNull()?.expr
    val msgReg = if (msgExpr != null) convertToStr(emitExpr(msgExpr), typeOf(msgExpr))
                 else stringConst("panic")
    emit("  call void @nv_panic(i8*$msgReg)")
    terminate("  unreachable")
    return "0"
}

private fun LlvmIrEmitter.emitLenCall(expr: CallExpr): String {
    val argExpr = expr.args.firstOrNull()?.expr ?: return "0"
    val argReg  = emitExpr(argExpr)
    val argType = typeOf(argExpr)
    if (argType == Type.TStr) {
        val res = fresh("len"); emit("  $res = call i64 @nv_str_len(i8*$argReg)"); return res
    }
    if (argType is Type.TArray) {
        val lenPtr = fresh("lenptr"); emit("  $lenPtr = bitcast i8* $argReg to i64*")
        val lenReg = fresh("arrlen"); emit("  $lenReg = load i64, i64* $lenPtr, align 8"); return lenReg
    }
    return "0"
}

// ── Member access ─────────────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitMemberAccessExpr(expr: MemberAccessExpr): String {
    val receiverReg  = emitExpr(expr.receiver)
    val receiverType = typeOf(expr.receiver)

    return when {
        expr.member == "length" && receiverType == Type.TStr -> {
            val res = fresh("len"); emit("  $res = call i64 @nv_str_len(i8*$receiverReg)"); res
        }
        (expr.member == "count" || expr.member == "length" || expr.member == "len") && receiverType is Type.TArray -> {
            val cntPtr = fresh("arr.cntptr"); emit("  $cntPtr = bitcast i8* $receiverReg to i64*")
            val cntReg = fresh("arr.cnt");    emit("  $cntReg = load i64, i64* $cntPtr, align 8"); cntReg
        }
        expr.member == "str" -> convertToStr(receiverReg, receiverType)
        else -> {
            val typeName = if (receiverType is Type.TNamed) receiverType.qualifiedName.substringAfterLast('.') else ""
            val fields = if (typeName.isNotEmpty()) structLayouts[typeName] else null
            if (fields != null && receiverType is Type.TNamed) {
                val lazyForType = lazyFields[typeName]
                if (lazyForType != null && lazyForType.any { it.first == expr.member }) {
                    val getterRetLt = methodReturnTypes["${typeName}_get_${expr.member}"] ?: llvmType(typeOf(expr))
                    val res = fresh("lzget")
                    emit("  $res = call $getterRetLt @nv_${typeName}_get_${expr.member}(i8* $receiverReg)")
                    return res
                }
                val fieldIdx = fields.indexOfFirst { it.first == expr.member }
                if (fieldIdx >= 0) {
                    val fieldType = fields[fieldIdx].second
                    val fieldLt = llvmType(fieldType)
                    val gepIdx = fieldIdx + (if (typeName in classTypeNames) 2 else 0)
                    val castReg = fresh("sfcast")
                    emit("  $castReg = bitcast i8* $receiverReg to %struct.$typeName*")
                    val ptrReg = fresh("sfptr")
                    emit("  $ptrReg = getelementptr %struct.$typeName, %struct.$typeName* $castReg, i32 0, i32 $gepIdx")
                    val loadReg = fresh("sfld")
                    emit("  $loadReg = load $fieldLt, $fieldLt* $ptrReg, align ${llvmTypeAlign(fieldLt)}")
                    return loadReg
                }
            }
            "0"
        }
    }
}

private fun LlvmIrEmitter.emitSafeNavExpr(expr: SafeNavExpr): String {
    val receiverReg  = emitExpr(expr.receiver)
    val receiverType = typeOf(expr.receiver)
    val innerLt = llvmType((receiverType as? Type.TNullable)?.inner ?: receiverType)

    val okLabel    = freshLabel("safenav.ok")
    val nullLabel  = freshLabel("safenav.null")
    val mergeLabel = freshLabel("safenav.merge")

    val isNull = fresh("isnull")
    emit("  $isNull = icmp eq $innerLt $receiverReg, ${nullConstant(innerLt)}")
    emit("  br i1 $isNull, label %$nullLabel, label %$okLabel")

    emitRaw("$okLabel:"); isTerminated = false
    val memberRes = when (expr.member) {
        "length" -> { val r = fresh("len"); emit("  $r = call i64 @nv_str_len(i8*$receiverReg)"); r }
        else -> "0"
    }
    emit("  br label %$mergeLabel")

    emitRaw("$nullLabel:"); isTerminated = false
    emit("  br label %$mergeLabel")

    emitRaw("$mergeLabel:"); isTerminated = false
    val res = fresh("safenav")
    emit("  $res = phi $innerLt [ $memberRes, %$okLabel ], [ ${nullConstant(innerLt)}, %$nullLabel ]")
    return res
}

private fun LlvmIrEmitter.emitInlineIfExpr(expr: InlineIfExpr): String {
    val condReg = emitExpr(expr.condition)
    val condI1  = coerceToI1(condReg, typeOf(expr.condition))
    val ty      = typeOf(expr)
    val lt      = if (llvmType(ty) == "void") "i64" else llvmType(ty)

    val thenLabel  = freshLabel("iif.then")
    val elseLabel  = freshLabel("iif.else")
    val mergeLabel = freshLabel("iif.merge")

    emit("  br i1 $condI1, label %$thenLabel, label %$elseLabel")

    emitRaw("$thenLabel:"); isTerminated = false
    val thenReg = emitExpr(expr.thenExpr)
    val thenCoerced = coerceToType(thenReg, typeOf(expr.thenExpr), lt)
    emit("  br label %$mergeLabel")

    emitRaw("$elseLabel:"); isTerminated = false
    val elseReg = emitExpr(expr.elseExpr)
    val elseCoerced = coerceToType(elseReg, typeOf(expr.elseExpr), lt)
    emit("  br label %$mergeLabel")

    emitRaw("$mergeLabel:"); isTerminated = false
    val res = fresh("iif")
    emit("  $res = phi $lt [ $thenCoerced, %$thenLabel ], [ $elseCoerced, %$elseLabel ]")
    return res
}

private fun LlvmIrEmitter.emitForceUnwrapExpr(expr: ForceUnwrapExpr): String {
    val reg  = emitExpr(expr.operand)
    val ty   = typeOf(expr.operand)
    val innerLt = llvmType((ty as? Type.TNullable)?.inner ?: ty)
    val okLabel   = freshLabel("unwrap.ok")
    val failLabel = freshLabel("unwrap.fail")
    val isNull = fresh("isnull")
    emit("  $isNull = icmp eq $innerLt $reg, ${nullConstant(innerLt)}")
    emit("  br i1 $isNull, label %$failLabel, label %$okLabel")
    emitRaw("$failLabel:"); isTerminated = false
    val msgConst = stringConst("force unwrap of nil value")
    emit("  call void @nv_panic(i8*$msgConst)")
    terminate("  unreachable")
    emitRaw("$okLabel:"); isTerminated = false
    return reg
}

private fun emitTypeTestExpr(@Suppress("UNUSED_PARAMETER") expr: TypeTestExpr): String = "1"

// ── Array / tuple literals ────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitArrayLiteral(expr: ArrayLiteralExpr): String {
    val count = expr.elements.size.toLong()
    if (expr.elements.isEmpty()) {
        val rawReg = fresh("arr.empty")
        emit("  $rawReg = call i8* @malloc(i64 8)")
        val hdrPtr = fresh("hdrptr")
        emit("  $hdrPtr = bitcast i8* $rawReg to i64*")
        emit("  store i64 0, i64* $hdrPtr, align 8")
        return rawReg
    }
    val elemType = typeOf(expr.elements[0])
    val elemLt   = llvmType(elemType)
    val elemSize = llvmTypeSize(elemLt)
    val totalSize = 8 + elemSize * expr.elements.size
    val rawReg = fresh("arr.raw")
    emit("  $rawReg = call i8* @malloc(i64 $totalSize)")
    val hdrPtr = fresh("hdrptr")
    emit("  $hdrPtr = bitcast i8* $rawReg to i64*")
    emit("  store i64 $count, i64* $hdrPtr, align 8")
    val dataStart = fresh("arr.data")
    emit("  $dataStart = getelementptr i8, i8* $rawReg, i64 8")
    val arrReg = fresh("arr")
    emit("  $arrReg = bitcast i8* $dataStart to ${llvmPtrType(elemLt)}")
    for ((i, elemExpr) in expr.elements.withIndex()) {
        val valReg  = emitExpr(elemExpr)
        val coerced = coerceToType(valReg, typeOf(elemExpr), elemLt)
        val idxReg  = fresh("arrptr")
        emit("  $idxReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $arrReg, i64 $i")
        emit("  store $elemLt $coerced, ${llvmPtrType(elemLt)} $idxReg, align $elemSize")
    }
    return rawReg
}

private fun LlvmIrEmitter.emitTupleLiteral(expr: TupleLiteralExpr): String {
    if (expr.elements.isEmpty()) return "null"
    val rawReg = fresh("tuple.raw")
    emit("  $rawReg = call i8* @malloc(i64 ${expr.elements.size * 8})")
    val arrReg = fresh("tuple")
    emit("  $arrReg = bitcast i8* $rawReg to i64*")
    for ((i, elemExpr) in expr.elements.withIndex()) {
        val valReg = emitExpr(elemExpr)
        val asI64  = coerceToI64(valReg, typeOf(elemExpr))
        val ptrReg = fresh("tptr")
        emit("  $ptrReg = getelementptr i64, i64* $arrReg, i64 $i")
        emit("  store i64 $asI64, i64* $ptrReg, align 8")
    }
    val retReg = fresh("tupret")
    emit("  $retReg = bitcast i64* $arrReg to i8*")
    return retReg
}

// ── Index expression ──────────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitIndexExpr(expr: IndexExpr): String {
    val receiverReg  = emitExpr(expr.receiver)
    val receiverType = typeOf(expr.receiver)
    val idxArg = expr.indices.firstOrNull() ?: return "0"
    val idxReg = if (idxArg.isStar) "0" else (idxArg.expr?.let { emitExpr(it) } ?: "0")
    val elemLt = when (receiverType) {
        is Type.TArray -> llvmType(receiverType.element)
        is Type.TStr   -> "i8"
        else           -> "i64"
    }
    val idxI64 = if (idxArg.expr != null) coerceToI64(idxReg, typeOf(idxArg.expr!!)) else "0"
    val castReg: String
    if (receiverType is Type.TArray) {
        val dataPtr = fresh("arr.data")
        emit("  $dataPtr = getelementptr i8, i8* $receiverReg, i64 8")
        castReg = fresh("idxcast")
        emit("  $castReg = bitcast i8* $dataPtr to ${llvmPtrType(elemLt)}")
    } else {
        castReg = fresh("idxcast")
        emit("  $castReg = bitcast i8* $receiverReg to ${llvmPtrType(elemLt)}")
    }
    val ptrReg = fresh("idxptr")
    emit("  $ptrReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $castReg, i64 $idxI64")
    val res = fresh("idx")
    emit("  $res = load $elemLt, ${llvmPtrType(elemLt)} $ptrReg, align 1")
    return res
}

// ── Quantifier expressions ────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitQuantifierExpr(expr: QuantifierExpr): String {
    val iterableExpr = expr.iterable ?: return when (expr.op) {
        QuantifierOp.FORALL, QuantifierOp.EXISTS -> "0"
        QuantifierOp.SUM, QuantifierOp.PRODUCT   -> "0"
    }
    val bodyExpr = when (val b = expr.body) {
        is InlineQuantifierBody -> b.expr
        else -> return "0"
    }
    val bindName = when (val bind = expr.binding) {
        is IdentBinding -> bind.name
        is TupleBinding -> bind.names.firstOrNull() ?: "_"
        null -> "_"
    }

    val iterableType = typeOf(iterableExpr)
    val elemType = when (iterableType) {
        is Type.TArray -> iterableType.element
        Type.TStr      -> Type.TChar
        else           -> Type.TUnknown
    }
    val elemLt   = llvmType(elemType)
    val elemSize = llvmTypeSize(elemLt).coerceAtLeast(1).toLong()

    val arrReg   = emitExpr(iterableExpr)
    val hdrPtr   = fresh("q.hdr")
    emit("  $hdrPtr = bitcast i8* $arrReg to i64*")
    val countReg = fresh("q.count")
    emit("  $countReg = load i64, i64* $hdrPtr, align 8")

    val condLabel = freshLabel("q.cond")
    val bodyLabel = freshLabel("q.body")
    val endLabel  = freshLabel("q.end")

    val isBoolean = expr.op == QuantifierOp.FORALL || expr.op == QuantifierOp.EXISTS
    val accLt     = if (isBoolean) "i1" else elemLt
    val accReg    = emitAlloca("q.acc.${labelIdx}", accLt)
    val initVal   = when (expr.op) {
        QuantifierOp.FORALL  -> "1"
        QuantifierOp.EXISTS  -> "0"
        QuantifierOp.SUM     -> if (accLt == "double") "0.0" else "0"
        QuantifierOp.PRODUCT -> if (accLt == "double") "1.0" else "1"
    }
    emitStore(accLt, initVal, accReg)

    val idxReg = emitAlloca("q.idx.${labelIdx}", "i64")
    emitStore("i64", "0", idxReg)
    emit("  br label %$condLabel")

    emitRaw("$condLabel:"); isTerminated = false
    val curIdx = fresh("q.i")
    emit("  $curIdx = load i64, i64* $idxReg, align 8")
    val loopCond = fresh("q.cond")
    emit("  $loopCond = icmp slt i64 $curIdx, $countReg")

    if (expr.op == QuantifierOp.FORALL || expr.op == QuantifierOp.EXISTS) {
        val continueLabel = freshLabel("q.cont")
        emit("  br i1 $loopCond, label %$continueLabel, label %$endLabel")
        emitRaw("$continueLabel:"); isTerminated = false
        val accVal    = emitLoad(accLt, accReg, "q.acc.cur")
        val keepGoing = fresh("q.keep")
        if (expr.op == QuantifierOp.FORALL) emit("  $keepGoing = icmp eq i1 $accVal, 1")
        else                                emit("  $keepGoing = icmp eq i1 $accVal, 0")
        emit("  br i1 $keepGoing, label %$bodyLabel, label %$endLabel")
    } else {
        emit("  br i1 $loopCond, label %$bodyLabel, label %$endLabel")
    }

    emitRaw("$bodyLabel:"); isTerminated = false

    val dataStart = fresh("q.data")
    emit("  $dataStart = getelementptr i8, i8* $arrReg, i64 8")
    val elemValReg = if (elemLt == "i8") {
        val ptrReg = fresh("q.eptr"); emit("  $ptrReg = getelementptr i8, i8* $dataStart, i64 $curIdx")
        val r = fresh("q.eval"); emit("  $r = load i8, i8* $ptrReg, align 1"); r
    } else {
        val castReg = fresh("q.ecast"); emit("  $castReg = bitcast i8* $dataStart to ${llvmPtrType(elemLt)}")
        val ptrReg = fresh("q.eptr"); emit("  $ptrReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $castReg, i64 $curIdx")
        val r = fresh("q.eval"); emit("  $r = load $elemLt, ${llvmPtrType(elemLt)} $ptrReg, align $elemSize"); r
    }

    if (bindName != "_") {
        val bindAlloca = emitAlloca(bindName, elemLt)
        varAllocas[bindName] = Pair(bindAlloca, elemLt)
        if (elemType != Type.TUnknown) varTypes[bindName] = elemType
        emitStore(elemLt, elemValReg, bindAlloca)
    }

    val bodyVal = emitExpr(bodyExpr)
    val accCur  = emitLoad(accLt, accReg, "q.acc.prev")
    val accNew  = fresh("q.acc.new")
    when (expr.op) {
        QuantifierOp.FORALL -> {
            val condI1 = coerceToI1(bodyVal, typeOf(bodyExpr))
            emit("  $accNew = and i1 $accCur, $condI1"); emitStore(accLt, accNew, accReg)
        }
        QuantifierOp.EXISTS -> {
            val condI1 = coerceToI1(bodyVal, typeOf(bodyExpr))
            emit("  $accNew = or i1 $accCur, $condI1"); emitStore(accLt, accNew, accReg)
        }
        QuantifierOp.SUM -> {
            val bodyCoerced = coerceToType(bodyVal, typeOf(bodyExpr), accLt)
            val addOp = if (accLt == "double" || accLt == "float") "fadd" else "add"
            emit("  $accNew = $addOp $accLt $accCur, $bodyCoerced"); emitStore(accLt, accNew, accReg)
        }
        QuantifierOp.PRODUCT -> {
            val bodyCoerced = coerceToType(bodyVal, typeOf(bodyExpr), accLt)
            val mulOp = if (accLt == "double" || accLt == "float") "fmul" else "mul"
            emit("  $accNew = $mulOp $accLt $accCur, $bodyCoerced"); emitStore(accLt, accNew, accReg)
        }
    }

    if (!isTerminated) {
        val nextIdx = fresh("q.next")
        emit("  $nextIdx = add i64 $curIdx, 1")
        emitStore("i64", nextIdx, idxReg)
        emit("  br label %$condLabel")
    }

    emitRaw("$endLabel:"); isTerminated = false
    return emitLoad(accLt, accReg, "q.result")
}

// ── Builder call ──────────────────────────────────────────────────────────────

private fun LlvmIrEmitter.emitBuilderCallExpr(expr: BuilderCallExpr): String {
    val typeName = expr.typeName
    val fields   = structLayouts[typeName] ?: return "null"
    val ctorParamCount = structCtorParamCount[typeName] ?: fields.size
    val defaults = structCtorDefaults[typeName] ?: emptyList()
    val assignMap: Map<String, Expr> = expr.assignments.toMap()
    val argParts = fields.take(ctorParamCount).map { (fieldName, fieldType) ->
        val llvmT = llvmType(fieldType)
        val reg = when {
            assignMap.containsKey(fieldName) -> emitExpr(assignMap[fieldName]!!)
            else -> {
                val defExpr = defaults.firstOrNull { it.first == fieldName }?.second
                if (defExpr != null) emitExpr(defExpr) else if (llvmT == "i8*") "null" else "0"
            }
        }
        "$llvmT $reg"
    }
    val result = fresh("bld")
    emit("  $result = call i8* @nv_$typeName(${argParts.joinToString(", ")})")
    return result
}
