package nv.compiler.codegen

import nv.compiler.parser.Expr
import nv.compiler.typecheck.Type

// ─────────────────────────────────────────────────────────────────────────────
// Derives — @derive(Show/Eq/Hash/Compare/Copy), @config, @lazy, by-delegation,
// and class destructor synthesis.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sets up per-function IR state, calls [block] to emit IR, then flushes the
 * result to [userFns].  Mirrors emitFunctionDecl / emitMethodDecl.
 */
internal fun LlvmIrEmitter.emitSyntheticMethod(
    mangledName: String,
    paramList: String,
    retType: String,
    block: () -> Unit,
) {
    fnBody = StringBuilder(); fnAllocas = StringBuilder()
    varAllocas.clear(); varTypes.clear()
    tempIdx = 0; labelIdx = 0; isTerminated = false; loopStack.clear()
    fnReturnType = retType
    block()
    if (!isTerminated) {
        when (retType) {
            "void"   -> terminate("  ret void")
            "i64"    -> terminate("  ret i64 0")
            "i1"     -> terminate("  ret i1 1")
            "double" -> terminate("  ret double 0.0")
            else     -> terminate("  ret i8* null")
        }
    }
    userFns.appendLine("define $retType $mangledName($paramList) {")
    userFns.appendLine("entry:")
    userFns.append(fnAllocas)
    userFns.append(fnBody)
    userFns.appendLine("}")
    userFns.appendLine()
}

// ── @derive(Show) ─────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitDerivedShow(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
    val structType  = "%struct.$typeName"
    val fieldOffset = if (isClass) 2 else 0
    emitSyntheticMethod("@nv_${typeName}_toString", "i8* %self", "i8*") {
        val castReg = fresh("show.cast")
        emit("  $castReg = bitcast i8* %self to $structType*")
        var acc = stringConst("$typeName(")
        for ((idx, fp) in fields.withIndex()) {
            val (fname, ft) = fp
            val gepIdx  = idx + fieldOffset
            val fieldLt = llvmType(ft)
            val sep = if (idx == 0) "$fname: " else ", $fname: "
            val sepC = stringConst(sep)
            val r1 = fresh("show.sep")
            emit("  $r1 = call i8* @nv_str_concat(i8* $acc, i8* $sepC)")
            val fpR = fresh("show.fp"); val fvR = fresh("show.fv")
            emit("  $fpR = getelementptr $structType, $structType* $castReg, i32 0, i32 $gepIdx")
            emit("  $fvR = load $fieldLt, $fieldLt* $fpR, align ${llvmTypeAlign(fieldLt)}")
            val fStr = convertToStr(fvR, ft)
            val r2 = fresh("show.acc")
            emit("  $r2 = call i8* @nv_str_concat(i8* $r1, i8* $fStr)")
            acc = r2
        }
        val closeC = stringConst(")")
        val result = fresh("show.result")
        emit("  $result = call i8* @nv_str_concat(i8* $acc, i8* $closeC)")
        terminate("  ret i8* $result")
    }
    methodReturnTypes["${typeName}_toString"] = "i8*"
}

// ── @derive(Eq) ───────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitDerivedEq(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
    val structType  = "%struct.$typeName"
    val fieldOffset = if (isClass) 2 else 0
    emitSyntheticMethod("@nv_${typeName}_op_eq", "i8* %self, i8* %other", "i1") {
        if (fields.isEmpty()) { terminate("  ret i1 1"); return@emitSyntheticMethod }
        val aC = fresh("eq.a"); val bC = fresh("eq.b")
        emit("  $aC = bitcast i8* %self to $structType*")
        emit("  $bC = bitcast i8* %other to $structType*")
        val falseLabel = freshLabel("eq.false")
        for ((idx, fp) in fields.withIndex()) {
            val (_, ft) = fp
            val gepIdx = idx + fieldOffset
            val fieldLt = llvmType(ft)
            val apR = fresh("eq.ap"); val bpR = fresh("eq.bp")
            val avR = fresh("eq.av"); val bvR = fresh("eq.bv")
            emit("  $apR = getelementptr $structType, $structType* $aC, i32 0, i32 $gepIdx")
            emit("  $bpR = getelementptr $structType, $structType* $bC, i32 0, i32 $gepIdx")
            emit("  $avR = load $fieldLt, $fieldLt* $apR, align ${llvmTypeAlign(fieldLt)}")
            emit("  $bvR = load $fieldLt, $fieldLt* $bpR, align ${llvmTypeAlign(fieldLt)}")
            val cmpR = fresh("eq.cmp")
            when (ft) {
                Type.TStr                  -> emit("  $cmpR = call i1 @nv_str_eq(i8* $avR, i8* $bvR)")
                Type.TFloat, Type.TFloat64 -> emit("  $cmpR = fcmp oeq double $avR, $bvR")
                Type.TFloat32              -> emit("  $cmpR = fcmp oeq float $avR, $bvR")
                Type.TBool                 -> emit("  $cmpR = icmp eq i1 $avR, $bvR")
                else                       -> emit("  $cmpR = icmp eq $fieldLt $avR, $bvR")
            }
            val nextLabel = freshLabel("eq.next$idx")
            emit("  br i1 $cmpR, label %$nextLabel, label %$falseLabel")
            emitRaw("$nextLabel:")
            isTerminated = false
        }
        terminate("  ret i1 1")
        emitRaw("$falseLabel:")
        isTerminated = false
        terminate("  ret i1 0")
    }
    emitSyntheticMethod("@nv_${typeName}_op_neq", "i8* %self, i8* %other", "i1") {
        val eqR = fresh("neq.eq")
        emit("  $eqR = call i1 @nv_${typeName}_op_eq(i8* %self, i8* %other)")
        val notR = fresh("neq.not")
        emit("  $notR = xor i1 $eqR, 1")
        terminate("  ret i1 $notR")
    }
}

// ── @derive(Hash) ─────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitDerivedHash(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
    val structType  = "%struct.$typeName"
    val fieldOffset = if (isClass) 2 else 0
    emitSyntheticMethod("@nv_${typeName}_hash", "i8* %self", "i64") {
        if (fields.isEmpty()) { terminate("  ret i64 0"); return@emitSyntheticMethod }
        val castR = fresh("hash.cast")
        emit("  $castR = bitcast i8* %self to $structType*")
        var seed = "0"
        for ((idx, fp) in fields.withIndex()) {
            val (_, ft) = fp
            val gepIdx = idx + fieldOffset
            val fieldLt = llvmType(ft)
            val fpR = fresh("hash.fp"); val fvR = fresh("hash.fv")
            emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
            emit("  $fvR = load $fieldLt, $fieldLt* $fpR, align ${llvmTypeAlign(fieldLt)}")
            val fhR = fresh("hash.fh")
            when (ft) {
                Type.TStr -> emit("  $fhR = call i64 @nv_hash_fnv1a(i8* $fvR)")
                Type.TFloat, Type.TFloat64 -> {
                    val bcR = fresh("hash.bc")
                    emit("  $bcR = bitcast double $fvR to i64")
                    emit("  $fhR = call i64 @nv_hash_combine(i64 0, i64 $bcR)")
                }
                Type.TFloat32 -> {
                    val extR = fresh("hash.ext"); val bcR = fresh("hash.bc")
                    emit("  $extR = fpext float $fvR to double")
                    emit("  $bcR = bitcast double $extR to i64")
                    emit("  $fhR = call i64 @nv_hash_combine(i64 0, i64 $bcR)")
                }
                Type.TBool -> {
                    val zR = fresh("hash.z")
                    emit("  $zR = zext i1 $fvR to i64")
                    emit("  $fhR = call i64 @nv_hash_combine(i64 0, i64 $zR)")
                }
                else -> emit("  $fhR = call i64 @nv_hash_combine(i64 0, i64 $fvR)")
            }
            val newSeed = fresh("hash.seed")
            emit("  $newSeed = call i64 @nv_hash_combine(i64 $seed, i64 $fhR)")
            seed = newSeed
        }
        terminate("  ret i64 $seed")
    }
    methodReturnTypes["${typeName}_hash"] = "i64"
}

// ── @derive(Compare) ──────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitDerivedCompare(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
    val structType  = "%struct.$typeName"
    val fieldOffset = if (isClass) 2 else 0
    emitSyntheticMethod("@nv_${typeName}_compare", "i8* %self, i8* %other", "i64") {
        if (fields.isEmpty()) { terminate("  ret i64 0"); return@emitSyntheticMethod }
        val aC = fresh("cmp.a"); val bC = fresh("cmp.b")
        emit("  $aC = bitcast i8* %self to $structType*")
        emit("  $bC = bitcast i8* %other to $structType*")
        for ((idx, fp) in fields.withIndex()) {
            val (_, ft) = fp
            val gepIdx = idx + fieldOffset
            val fieldLt = llvmType(ft)
            val apR = fresh("cmp.ap"); val bpR = fresh("cmp.bp")
            val avR = fresh("cmp.av"); val bvR = fresh("cmp.bv")
            emit("  $apR = getelementptr $structType, $structType* $aC, i32 0, i32 $gepIdx")
            emit("  $bpR = getelementptr $structType, $structType* $bC, i32 0, i32 $gepIdx")
            emit("  $avR = load $fieldLt, $fieldLt* $apR, align ${llvmTypeAlign(fieldLt)}")
            emit("  $bvR = load $fieldLt, $fieldLt* $bpR, align ${llvmTypeAlign(fieldLt)}")
            val ltLabel = freshLabel("cmp.lt$idx"); val gtLabel = freshLabel("cmp.gt$idx")
            val eqLabel = freshLabel("cmp.eq$idx"); val chkGtLabel = "cmp.chkgt.$idx"
            when (ft) {
                Type.TStr -> {
                    val ahR = fresh("cmp.ah"); val bhR = fresh("cmp.bh")
                    emit("  $ahR = call i64 @nv_hash_fnv1a(i8* $avR)")
                    emit("  $bhR = call i64 @nv_hash_fnv1a(i8* $bvR)")
                    val ltR = fresh("cmp.lt"); val eqR = fresh("cmp.eq")
                    emit("  $ltR = icmp slt i64 $ahR, $bhR")
                    emit("  $eqR = icmp eq i64 $ahR, $bhR")
                    emit("  br i1 $ltR, label %$ltLabel, label %$chkGtLabel")
                    emitRaw("$chkGtLabel:"); isTerminated = false
                    emit("  br i1 $eqR, label %$eqLabel, label %$gtLabel")
                }
                Type.TFloat, Type.TFloat64 -> {
                    val ltR = fresh("cmp.lt"); val eqR = fresh("cmp.eq")
                    emit("  $ltR = fcmp olt double $avR, $bvR")
                    emit("  $eqR = fcmp oeq double $avR, $bvR")
                    emit("  br i1 $ltR, label %$ltLabel, label %$chkGtLabel")
                    emitRaw("$chkGtLabel:"); isTerminated = false
                    emit("  br i1 $eqR, label %$eqLabel, label %$gtLabel")
                }
                Type.TBool -> {
                    val aE = fresh("cmp.ae"); val bE = fresh("cmp.be")
                    emit("  $aE = zext i1 $avR to i64"); emit("  $bE = zext i1 $bvR to i64")
                    val ltR = fresh("cmp.lt"); val eqR = fresh("cmp.eq")
                    emit("  $ltR = icmp slt i64 $aE, $bE"); emit("  $eqR = icmp eq i64 $aE, $bE")
                    emit("  br i1 $ltR, label %$ltLabel, label %$chkGtLabel")
                    emitRaw("$chkGtLabel:"); isTerminated = false
                    emit("  br i1 $eqR, label %$eqLabel, label %$gtLabel")
                }
                else -> {
                    val ltR = fresh("cmp.lt"); val eqR = fresh("cmp.eq")
                    emit("  $ltR = icmp slt i64 $avR, $bvR"); emit("  $eqR = icmp eq i64 $avR, $bvR")
                    emit("  br i1 $ltR, label %$ltLabel, label %$chkGtLabel")
                    emitRaw("$chkGtLabel:"); isTerminated = false
                    emit("  br i1 $eqR, label %$eqLabel, label %$gtLabel")
                }
            }
            emitRaw("$ltLabel:"); isTerminated = false; terminate("  ret i64 -1")
            emitRaw("$gtLabel:"); isTerminated = false; terminate("  ret i64 1")
            emitRaw("$eqLabel:"); isTerminated = false
        }
        terminate("  ret i64 0")
    }
    methodReturnTypes["${typeName}_compare"] = "i64"
}

// ── @derive(Copy) ─────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitDerivedCopy(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
    val structType  = "%struct.$typeName"
    val fieldOffset = if (isClass) 2 else 0
    emitSyntheticMethod("@nv_${typeName}_copy", "i8* %self", "i8*") {
        val castR = fresh("copy.cast")
        emit("  $castR = bitcast i8* %self to $structType*")
        val fieldRegsAndTypes = fields.mapIndexed { idx, (_, ft) ->
            val gepIdx = idx + fieldOffset; val fieldLt = llvmType(ft)
            val fpR = fresh("copy.fp"); val fvR = fresh("copy.fv")
            emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
            emit("  $fvR = load $fieldLt, $fieldLt* $fpR, align ${llvmTypeAlign(fieldLt)}")
            Pair(fvR, fieldLt)
        }
        val argList = fieldRegsAndTypes.joinToString(", ") { (r, lt) -> "$lt $r" }
        val res = fresh("copy.result")
        emit("  $res = call i8* @nv_$typeName($argList)")
        terminate("  ret i8* $res")
    }
    methodReturnTypes["${typeName}_copy"] = "i8*"
}

// ── @config load ──────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitConfigLoad(typeName: String, ctorFields: List<Pair<String, Type>>, isClass: Boolean) {
    val prefix       = configPrefixes[typeName] ?: typeName
    val envOverrides = configEnvOverrides[typeName] ?: ctorFields.map { (n, _) -> n to null }
    val defaults     = structCtorDefaults[typeName] ?: emptyList()

    emitSyntheticMethod("@nv_${typeName}_config_load", "", "i8*") {
        val fieldRegs = mutableListOf<Pair<String, String>>()

        for ((idx, fieldPair) in ctorFields.withIndex()) {
            val (fieldName, fieldType) = fieldPair
            val llvmT    = llvmType(fieldType)
            val envName  = envOverrides.getOrNull(idx)?.second
                ?: "${toUpperSnake(prefix)}_${toUpperSnake(fieldName)}"
            val defaultExpr = defaults.firstOrNull { it.first == fieldName }?.second
            val hasDefault  = defaultExpr != null || fieldType is Type.TNullable

            val envNameReg = stringConst(envName)
            val rawReg     = fresh("cfg.raw")
            emit("  $rawReg = call i8* @getenv(i8* $envNameReg)")
            val isNullReg  = fresh("cfg.null")
            emit("  $isNullReg = icmp eq i8* $rawReg, null")

            if (!hasDefault) {
                val missLabel = freshLabel("cfg.miss")
                val okLabel   = freshLabel("cfg.ok")
                emit("  br i1 $isNullReg, label %$missLabel, label %$okLabel")
                emitRaw("$missLabel:"); isTerminated = false
                val errMsgReg = stringConst("ConfigError: missing required field '$fieldName' (env: $envName)")
                val errReg = fresh("cfg.err")
                emit("  $errReg = call i8* @nv_Err(i8* $errMsgReg)")
                terminate("  ret i8* $errReg")
                emitRaw("$okLabel:"); isTerminated = false
                fieldRegs.add(parseEnvValue(rawReg, fieldType) to llvmT)
            } else {
                val parseLabel = freshLabel("cfg.prs")
                val defLabel   = freshLabel("cfg.def")
                val doneLabel  = freshLabel("cfg.done")
                emit("  br i1 $isNullReg, label %$defLabel, label %$parseLabel")

                emitRaw("$parseLabel:"); isTerminated = false
                val parsedReg = parseEnvValue(rawReg, fieldType)
                emit("  br label %$doneLabel")

                emitRaw("$defLabel:"); isTerminated = false
                val defReg = when {
                    defaultExpr != null -> emitExpr(defaultExpr)
                    fieldType is Type.TNullable -> "null"
                    else -> nullConstant(llvmT)
                }
                emit("  br label %$doneLabel")

                emitRaw("$doneLabel:"); isTerminated = false
                val phiReg = fresh("cfg.v")
                emit("  $phiReg = phi $llvmT [ $parsedReg, %$parseLabel ], [ $defReg, %$defLabel ]")
                fieldRegs.add(phiReg to llvmT)
            }
        }

        val argList = fieldRegs.joinToString(", ") { (r, lt) -> "$lt $r" }
        val objReg  = fresh("cfg.obj")
        emit("  $objReg = call i8* @nv_$typeName($argList)")
        val okReg = fresh("cfg.ok")
        emit("  $okReg = call i8* @nv_Ok(i8* $objReg)")
        terminate("  ret i8* $okReg")
    }
    methodReturnTypes["${typeName}_config_load"] = "i8*"
}

private fun LlvmIrEmitter.parseEnvValue(rawReg: String, fieldType: Type): String = when (fieldType) {
    Type.TStr -> rawReg
    Type.TInt, Type.TInt64 -> {
        val r = fresh("cfg.int"); emit("  $r = call i64 @atoll(i8* $rawReg)"); r
    }
    Type.TFloat, Type.TFloat64 -> {
        val r = fresh("cfg.flt"); emit("  $r = call double @atof(i8* $rawReg)"); r
    }
    Type.TBool -> {
        val trueReg = stringConst("true")
        val boolReg = fresh("cfg.bool")
        emit("  $boolReg = call i1 @nv_str_eq(i8* $rawReg, i8* $trueReg)")
        boolReg
    }
    is Type.TNullable -> rawReg
    else -> rawReg
}

private fun toUpperSnake(s: String): String {
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (c.isUpperCase() && i > 0 && s[i - 1].isLowerCase()) sb.append('_')
        sb.append(c.uppercaseChar())
    }
    return sb.toString()
}

// ── @lazy getter ──────────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitLazyGetter(
    typeName: String,
    fieldName: String,
    fieldType: Type,
    initExpr: Expr,
    isClass: Boolean,
) {
    val retLt      = llvmType(fieldType)
    val structType = "%struct.$typeName"
    val fieldOffset = if (isClass) 2 else 0
    val allFields  = structLayouts[typeName] ?: return
    val ctorCount  = structCtorParamCount[typeName] ?: 0

    val initFlagFieldName = "_lazy_${fieldName}_init"
    val initFlagIdx = allFields.indexOfFirst { it.first == initFlagFieldName }.let { if (it < 0) return else it } + fieldOffset
    val valueIdx    = allFields.indexOfFirst { it.first == fieldName }.let { if (it < 0) return else it } + fieldOffset

    emitSyntheticMethod("@nv_${typeName}_get_$fieldName", "i8* %self", retLt) {
        val selfAlloca = emitAlloca("self", "i8*")
        varAllocas["self"] = Pair(selfAlloca, "i8*")
        emitStore("i8*", "%self", selfAlloca)

        val castR = fresh("lz.cast")
        emit("  $castR = bitcast i8* %self to $structType*")

        val initFlagPtrR = fresh("lz.ifp")
        emit("  $initFlagPtrR = getelementptr $structType, $structType* $castR, i32 0, i32 $initFlagIdx")
        val valPtrR = fresh("lz.vp")
        emit("  $valPtrR = getelementptr $structType, $structType* $castR, i32 0, i32 $valueIdx")

        val initFlagR = fresh("lz.flag")
        emit("  $initFlagR = load i1, i1* $initFlagPtrR, align 1")

        val hitLabel  = freshLabel("lz.hit")
        val missLabel = freshLabel("lz.miss")
        val retLabel  = freshLabel("lz.ret")
        emit("  br i1 $initFlagR, label %$hitLabel, label %$missLabel")

        emitRaw("$hitLabel:")
        isTerminated = false
        val cachedR = fresh("lz.cached")
        emit("  $cachedR = load $retLt, $retLt* $valPtrR, align ${llvmTypeAlign(retLt)}")
        emit("  br label %$retLabel")

        emitRaw("$missLabel:")
        isTerminated = false

        for (i in 0 until ctorCount) {
            val (fname, ft) = allFields[i]
            val fldLt  = llvmType(ft)
            val gepIdx = i + fieldOffset
            val fpR    = fresh("lz.fp")
            emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
            val fvR = fresh("lz.fv")
            emit("  $fvR = load $fldLt, $fldLt* $fpR, align ${llvmTypeAlign(fldLt)}")
            val allocaR = emitAlloca(fname, fldLt)
            varAllocas[fname] = Pair(allocaR, fldLt)
            varTypes[fname]   = ft
            emitStore(fldLt, fvR, allocaR)
        }

        val computedR = emitExpr(initExpr)

        emit("  store $retLt $computedR, $retLt* $valPtrR, align ${llvmTypeAlign(retLt)}")
        emit("  store i1 1, i1* $initFlagPtrR, align 1")
        emit("  br label %$retLabel")

        emitRaw("$retLabel:")
        isTerminated = false
        val resultR = fresh("lz.result")
        emit("  $resultR = phi $retLt [ $cachedR, %$hitLabel ], [ $computedR, %$missLabel ]")
        terminate("  ret $retLt $resultR")
    }
    methodReturnTypes["${typeName}_get_$fieldName"] = retLt
}

// ── by-delegation forwarder ───────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitDelegationForwarder(
    typeName: String,
    methodName: String,
    methodType: Type.TFun,
    delegateFieldName: String,
    concreteTypeName: String,
    isClass: Boolean,
) {
    val paramTypes  = methodType.params
    val retType     = methodType.returnType
    val retLt       = llvmType(retType)
    val structType  = "%struct.$typeName"
    val fieldOffset = if (isClass) 2 else 0
    val allFields   = structLayouts[typeName] ?: return
    val delegateIdx = allFields.indexOfFirst { it.first == delegateFieldName }
    if (delegateIdx < 0) return
    val gepIdx = delegateIdx + fieldOffset

    val paramList = buildString {
        append("i8* %self")
        paramTypes.forEachIndexed { i, t -> append(", ${llvmType(t)} %fwd.p$i") }
    }

    emitSyntheticMethod("@nv_${typeName}_$methodName", paramList, retLt) {
        val castR = fresh("fwd.cast")
        emit("  $castR = bitcast i8* %self to $structType*")
        val dptrR = fresh("fwd.dptr")
        emit("  $dptrR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
        val delR = fresh("fwd.del")
        emit("  $delR = load i8*, i8** $dptrR, align 8")

        val argList = buildString {
            append("i8* $delR")
            paramTypes.forEachIndexed { i, t -> append(", ${llvmType(t)} %fwd.p$i") }
        }

        if (retLt == "void") {
            emit("  call void @nv_${concreteTypeName}_$methodName($argList)")
            terminate("  ret void")
        } else {
            val res = fresh("fwd.res")
            emit("  $res = call $retLt @nv_${concreteTypeName}_$methodName($argList)")
            terminate("  ret $retLt $res")
        }
    }
    methodReturnTypes["${typeName}_$methodName"] = retLt
}

// ── Class destructor ──────────────────────────────────────────────────────────

internal fun LlvmIrEmitter.emitClassDestructor(name: String, fields: List<Pair<String, Type>>) {
    val structType = "%struct.$name"
    val rcFields   = fields.withIndex().filter { (_, pair) -> isRcType(pair.second) }

    userFns.appendLine("define void @nv_dtor_$name(i8* %ptr) {")
    userFns.appendLine("entry:")
    if (rcFields.isNotEmpty()) {
        userFns.appendLine("  %dtor.cast = bitcast i8* %ptr to $structType*")
        for ((idx, pair) in rcFields) {
            val (fname, _) = pair
            val gepIdx = idx + 2
            userFns.appendLine("  %dtor.fp.$fname = getelementptr $structType, $structType* %dtor.cast, i32 0, i32 $gepIdx")
            userFns.appendLine("  %dtor.fv.$fname = load i8*, i8** %dtor.fp.$fname, align 8")
            userFns.appendLine("  call void @nv_rc_release(i8* %dtor.fv.$fname)")
        }
    }
    userFns.appendLine("  call void @free(i8* %ptr)")
    userFns.appendLine("  ret void")
    userFns.appendLine("}")
    userFns.appendLine()
}
