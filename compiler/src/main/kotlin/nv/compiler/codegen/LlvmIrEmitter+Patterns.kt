package nv.compiler.codegen

import nv.compiler.parser.*
import nv.compiler.typecheck.Type

// ─────────────────────────────────────────────────────────────────────────────
// Patterns — match expression codegen and pattern-check helpers.
// ─────────────────────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitMatchExpr(expr: MatchExpr) {
    val subjectReg  = emitExpr(expr.subject)
    val subjectType = typeOf(expr.subject)
    val mergeLabel  = freshLabel("match.merge")

    for ((idx, arm) in expr.arms.withIndex()) {
        val bodyLabel = freshLabel("match.arm")
        val nextLabel = if (idx < expr.arms.size - 1) freshLabel("match.next") else mergeLabel

        val matched = emitPatternCheck(subjectReg, subjectType, arm.pattern)
        val condI1  = coerceToI1(matched, Type.TBool)

        val finalCond = if (arm.guard != null) {
            val guardReg = emitExpr(arm.guard)
            val guardI1  = coerceToI1(guardReg, typeOf(arm.guard))
            val both     = fresh("guard")
            emit("  $both = and i1 $condI1, $guardI1")
            both
        } else condI1

        emit("  br i1 $finalCond, label %$bodyLabel, label %$nextLabel")
        emitRaw("$bodyLabel:")
        isTerminated = false

        when (val body = arm.body) {
            is ExprMatchArmBody  -> {
                val valReg  = emitExpr(body.expr)
                val valType = typeOf(body.expr)
                val valLt   = llvmType(valType)
                if (valType != Type.TUnit && valLt != "void" && fnReturnType != "void") {
                    val coerced = coerceToType(valReg, valType, fnReturnType)
                    terminate("  ret $fnReturnType $coerced")
                }
            }
            is BlockMatchArmBody -> body.stmts.forEach { emitStmt(it) }
        }
        if (!isTerminated) emit("  br label %$mergeLabel")

        if (idx < expr.arms.size - 1) {
            emitRaw("$nextLabel:")
            isTerminated = false
        }
    }

    emitRaw("$mergeLabel:")
    isTerminated = false
}

internal fun LlvmIrEmitter.emitPatternCheck(subjectReg: String, subjectType: Type, pattern: Pattern): String {
    return when (pattern) {
        is WildcardPattern -> "1"
        is BindingPattern  -> {
            val lt = llvmType(subjectType)
            if (lt != "void") {
                val allocaReg = emitAlloca(pattern.name, lt)
                varAllocas[pattern.name] = Pair(allocaReg, lt)
                emitStore(lt, subjectReg, allocaReg)
            }
            "1"
        }
        is NilPattern -> {
            val lt  = llvmType(subjectType)
            val res = fresh("nilchk")
            emit("  $res = icmp eq $lt $subjectReg, ${nullConstant(lt)}")
            res
        }
        is LiteralPattern -> {
            val patReg  = emitExpr(pattern.expr)
            val patType = typeOf(pattern.expr)
            val lt      = llvmType(patType)
            val res     = fresh("litchk")
            if (patType == Type.TStr || subjectType == Type.TStr) {
                emit("  $res = call i1 @nv_str_eq(i8* $subjectReg, i8* $patReg)")
            } else {
                val lhs = coerceToType(subjectReg, subjectType, lt)
                emit("  $res = icmp eq $lt $lhs, $patReg")
            }
            res
        }
        is RangePattern -> {
            val range    = pattern.range
            val startReg = emitExpr(range.start)
            val endReg   = emitExpr(range.end)
            val subI64   = coerceToI64(subjectReg, subjectType)
            val startI64 = coerceToI64(startReg, typeOf(range.start))
            val endI64   = coerceToI64(endReg, typeOf(range.end))
            val lo = fresh("lo"); val hi = fresh("hi"); val res = fresh("rngchk")
            emit("  $lo = icmp sge i64 $subI64, $startI64")
            when (range.kind) {
                RangeKind.CLOSED, RangeKind.HALF_OPEN_LEFT -> emit("  $hi = icmp sle i64 $subI64, $endI64")
                else                                        -> emit("  $hi = icmp slt i64 $subI64, $endI64")
            }
            emit("  $res = and i1 $lo, $hi")
            res
        }
        is OrPattern -> {
            val checks = pattern.alternatives.map { emitPatternCheck(subjectReg, subjectType, it) }
            checks.reduce { acc, c ->
                val res = fresh("or"); emit("  $res = or i1 $acc, $c"); res
            }
        }
        is TypePattern -> emitTypePatternCheck(subjectReg, subjectType, pattern)
        else -> "1"
    }
}

private fun LlvmIrEmitter.emitTypePatternCheck(subjectReg: String, subjectType: Type, pattern: TypePattern): String {
    val variantName = pattern.typeName.text

    val tag: Int
    val payloadParamType: Type?
    when (variantName) {
        "Ok"  -> { tag = 0; payloadParamType = null }
        "Err" -> { tag = 1; payloadParamType = Type.TStr }
        else  -> {
            val sealedDecl = sealedClassDecls.values.firstOrNull { sd -> sd.variants.any { it.name == variantName } }
                ?: return "1"
            val variantIdx = sealedDecl.variants.indexOfFirst { it.name == variantName }
            tag = variantIdx
            payloadParamType = sealedDecl.variants[variantIdx].params.firstOrNull()?.let { resolveTypeNode(it.type) }
        }
    }

    val castReg = fresh("vcast")
    emit("  $castReg = bitcast i8* $subjectReg to i64*")
    val tagReg = fresh("vtag")
    emit("  $tagReg = load i64, i64* $castReg, align 8")
    val matchedReg = fresh("vmatch")
    emit("  $matchedReg = icmp eq i64 $tagReg, $tag")

    val posArgs = pattern.args as? PositionalTypePatternArgs
    if (posArgs != null && posArgs.patterns.isNotEmpty()) {
        val vpReg = fresh("vpay")
        emit("  $vpReg = getelementptr i64, i64* $castReg, i64 1")
        val payI64Reg = fresh("pay_i64")
        emit("  $payI64Reg = load i64, i64* $vpReg, align 8")

        for (subPat in posArgs.patterns) {
            if (subPat is BindingPattern) {
                val paramLt = payloadParamType?.let { llvmType(it) } ?: "i8*"
                val extractedReg = when (paramLt) {
                    "i8*"    -> { val r = fresh("extract"); emit("  $r = inttoptr i64 $payI64Reg to i8*"); r }
                    "i1"     -> { val r = fresh("extract"); emit("  $r = trunc i64 $payI64Reg to i1"); r }
                    "double" -> { val r = fresh("extract"); emit("  $r = bitcast i64 $payI64Reg to double"); r }
                    else     -> payI64Reg
                }
                val allocaReg = emitAlloca(subPat.name, paramLt)
                varAllocas[subPat.name] = Pair(allocaReg, paramLt)
                emitStore(paramLt, extractedReg, allocaReg)
                val innerNvType: Type? = when {
                    subjectType is Type.TResult && variantName == "Ok" -> subjectType.okType
                    payloadParamType != null -> payloadParamType
                    else -> null
                }
                if (innerNvType != null) varTypes[subPat.name] = innerNvType
            }
        }
    }
    return matchedReg
}
