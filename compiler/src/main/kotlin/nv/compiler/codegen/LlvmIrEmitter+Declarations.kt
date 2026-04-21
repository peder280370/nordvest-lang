package nv.compiler.codegen

import nv.compiler.parser.*
import nv.compiler.typecheck.Type

// ─────────────────────────────────────────────────────────────────────────────
// Declarations — sealed variant constructors, extern decls, function/method/
// struct-or-class declaration codegen.
// ─────────────────────────────────────────────────────────────────────────────

/*
 * Each variant is represented as a heap-allocated {i64 tag, i64 value} struct (16 bytes).
 * The tag is the variant's 0-based index within the sealed class.
 * The value is the first constructor param coerced to i64 (0 for no-param variants).
 */
internal fun LlvmIrEmitter.emitSealedVariantConstructor(variant: SealedVariant, tag: Int) {
    val mangledName = "@nv_${variant.name}"
    if (variant.params.isEmpty()) {
        rtFns.appendLine("""
define i8* $mangledName() {
entry:
  %p = call i8* @malloc(i64 16)
  %tp = bitcast i8* %p to i64*
  store i64 $tag, i64* %tp, align 8
  %vp = getelementptr i64, i64* %tp, i64 1
  store i64 0, i64* %vp, align 8
  ret i8* %p
}""".trimIndent())
    } else {
        val paramType = resolveTypeNode(variant.params[0].type)
        val paramLt = llvmType(paramType)
        val storeStmt = when (paramLt) {
            "i8*"    -> "  %ival = ptrtoint i8* %param to i64\n  store i64 %ival, i64* %vp, align 8"
            "i1"     -> "  %ival = zext i1 %param to i64\n  store i64 %ival, i64* %vp, align 8"
            "double" -> "  %ival = bitcast double %param to i64\n  store i64 %ival, i64* %vp, align 8"
            "float"  -> "  %fext = fpext float %param to double\n  %ival = bitcast double %fext to i64\n  store i64 %ival, i64* %vp, align 8"
            else     -> "  store i64 %param, i64* %vp, align 8"
        }
        rtFns.appendLine("""
define i8* $mangledName($paramLt %param) {
entry:
  %p = call i8* @malloc(i64 16)
  %tp = bitcast i8* %p to i64*
  store i64 $tag, i64* %tp, align 8
  %vp = getelementptr i64, i64* %tp, i64 1
$storeStmt
  ret i8* %p
}""".trimIndent())
    }
}

internal fun LlvmIrEmitter.emitExternDecl(fn: FunctionDecl) {
    val cSymbol = fn.annotations.firstOrNull { it.name == "extern" }
        ?.args?.firstOrNull { it.name == "fn" || it.name == null }
        ?.let { arg ->
            when (val v = arg.value) {
                is AnnotationStrValue   -> v.value
                is AnnotationIdentValue -> v.name.text
                else -> null
            }
        } ?: fn.name
    val nvRetType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
    val retLt = llvmType(nvRetType)
    val paramTypes = fn.params.joinToString(", ") { p -> llvmType(resolveTypeNode(p.type)) }
    if (cSymbol !in inlineRuntimeFns) {
        declares.appendLine("declare $retLt @$cSymbol($paramTypes)")
    }
}

internal fun LlvmIrEmitter.emitExternSigDecl(fn: FunctionSignatureDecl) {
    val cSymbol = fn.annotations.firstOrNull { it.name == "extern" }
        ?.args?.firstOrNull { it.name == "fn" || it.name == null }
        ?.let { arg ->
            when (val v = arg.value) {
                is AnnotationStrValue   -> v.value
                is AnnotationIdentValue -> v.name.text
                else -> null
            }
        } ?: fn.name
    val nvRetType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
    val retLt = llvmType(nvRetType)
    val paramTypes = fn.params.joinToString(", ") { p -> llvmType(resolveTypeNode(p.type)) }
    if (cSymbol !in inlineRuntimeFns) {
        declares.appendLine("declare $retLt @$cSymbol($paramTypes)")
    }
}

internal fun LlvmIrEmitter.emitFunctionDecl(fn: FunctionDecl) {
    fnBody    = StringBuilder()
    fnAllocas = StringBuilder()
    varAllocas.clear()
    varTypes.clear()
    usedAllocaNames.clear()
    tempIdx   = 0
    labelIdx  = 0
    isTerminated = false
    loopStack.clear()

    val isMain = fn.name == "main" && fn.params.isEmpty()

    val nvRetType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
    fnReturnType = if (isMain) "i32" else llvmType(nvRetType)

    val paramList = if (isMain) {
        "i32 %argc, i8** %argv"
    } else {
        fn.params.joinToString(", ") { p ->
            val pt = resolveTypeNode(p.type)
            "${llvmType(pt)} %param.${p.name}"
        }
    }

    val mangledName = if (isMain) "@main" else "@nv_${fn.name}"

    emit("")

    if (!isMain) {
        for (p in fn.params) {
            val pt = resolveTypeNode(p.type)
            val lt = llvmType(pt)
            val allocaReg = emitAlloca(p.name, lt)
            varAllocas[p.name] = Pair(allocaReg, lt)
            emitStore(lt, "%param.${p.name}", allocaReg)
        }
    }

    for (stmt in fn.body) {
        emitStmt(stmt)
    }

    if (!isTerminated) {
        when (fnReturnType) {
            "void" -> terminate("  ret void")
            "i32"  -> terminate("  ret i32 0")
            "i1"   -> terminate("  ret i1 0")
            "i64"  -> terminate("  ret i64 0")
            "double" -> terminate("  ret double 0.0")
            "float"  -> terminate("  ret float 0.0")
            else     -> terminate("  ret i8* null")
        }
    }

    val noreturn = if (fn.name == "nv_panic") " noreturn" else ""
    userFns.appendLine("define $fnReturnType $mangledName($paramList)$noreturn {")
    userFns.appendLine("entry:")
    userFns.append(fnAllocas)
    userFns.append(fnBody)
    userFns.appendLine("}")
    userFns.appendLine()
}

internal fun LlvmIrEmitter.emitStructOrClassDecl(
    name: String,
    ctorParams: List<ConstructorParam>,
    members: List<Decl>,
    annotations: List<nv.compiler.parser.Annotation> = emptyList(),
    isClass: Boolean = false,
) {
    val fields = structLayouts[name] ?: return
    if (fields.isEmpty() && !isClass) return

    val userFieldsSize = fields.sumOf { (_, t) -> llvmTypeSize(llvmType(t)) }
    val rcHeaderSize   = if (isClass) 16 else 0
    val allocSize      = maxOf(userFieldsSize + rcHeaderSize, 8)
    val fieldOffset    = if (isClass) 2 else 0

    fnBody    = StringBuilder()
    fnAllocas = StringBuilder()
    varAllocas.clear()
    varTypes.clear()
    usedAllocaNames.clear()
    tempIdx   = 0
    labelIdx  = 0
    isTerminated = false
    loopStack.clear()

    val ctorParamCount = structCtorParamCount[name] ?: fields.size
    val ctorFields = fields.take(ctorParamCount)
    val paramList = ctorFields.joinToString(", ") { (fname, ft) -> "${llvmType(ft)} %ctor.$fname" }

    val objReg = fresh("obj")
    emit("  $objReg = call i8* @malloc(i64 $allocSize)")
    val castReg = fresh("cast")
    val structType = "%struct.$name"
    emit("  $castReg = bitcast i8* $objReg to $structType*")

    if (isClass) {
        val scPtr = fresh("sc.ptr")
        emit("  $scPtr = getelementptr $structType, $structType* $castReg, i32 0, i32 0")
        emit("  store i64 1, i64* $scPtr, align 8")
        val dtorPtr = fresh("dtor.ptr")
        emit("  $dtorPtr = getelementptr $structType, $structType* $castReg, i32 0, i32 1")
        val hasRcFields = fields.any { (_, t) -> isRcType(t) }
        if (hasRcFields) {
            val dtorCast = fresh("dtor.cast")
            emit("  $dtorCast = bitcast void (i8*)* @nv_dtor_$name to i8*")
            emit("  store i8* $dtorCast, i8** $dtorPtr, align 8")
        } else {
            emit("  store i8* null, i8** $dtorPtr, align 8")
        }
    }

    fields.forEachIndexed { idx, (fname, ft) ->
        val fieldLt = llvmType(ft)
        val ptrReg  = fresh("fp")
        val gepIdx  = idx + fieldOffset
        emit("  $ptrReg = getelementptr $structType, $structType* $castReg, i32 0, i32 $gepIdx")
        val valToStore = if (idx < ctorParamCount) "%ctor.$fname" else defaultValue(fieldLt)
        emit("  store $fieldLt $valToStore, $fieldLt* $ptrReg, align ${llvmTypeAlign(fieldLt)}")
    }
    terminate("  ret i8* $objReg")

    userFns.appendLine("define i8* @nv_$name($paramList) {")
    userFns.appendLine("entry:")
    userFns.append(fnAllocas)
    userFns.append(fnBody)
    userFns.appendLine("}")
    userFns.appendLine()

    if (isClass && fields.any { (_, t) -> isRcType(t) }) emitClassDestructor(name, fields)

    for (member in members) {
        if (member is FunctionDecl && !member.annotations.any { it.name == "extern" }) {
            emitMethodDecl(name, member)
        }
    }

    val isNewtype = annotations.any { it.name == "newtype" }
    val deriveAnno = annotations.find { it.name == "derive" }
    val derivedTraits = mutableSetOf<String>()
    if (isNewtype) derivedTraits += setOf("Show", "Eq", "Hash", "Compare")
    if (deriveAnno != null) {
        val traitNames = deriveAnno.args.mapNotNull { arg ->
            (arg.value as? AnnotationIdentValue)?.name?.text
        }
        derivedTraits += if ("All" in traitNames) setOf("Show", "Eq", "Compare", "Hash", "Copy") else traitNames
    }
    if ("Show"    in derivedTraits) emitDerivedShow(name, fields, isClass)
    if ("Eq"      in derivedTraits) emitDerivedEq(name, fields, isClass)
    if ("Hash"    in derivedTraits) emitDerivedHash(name, fields, isClass)
    if ("Compare" in derivedTraits) emitDerivedCompare(name, fields, isClass)
    if ("Copy"    in derivedTraits) emitDerivedCopy(name, fields, isClass)

    val lazyEntries = lazyFields[name] ?: emptyList()
    for ((fieldName, fieldType, initExpr) in lazyEntries) {
        emitLazyGetter(name, fieldName, fieldType, initExpr, isClass)
    }

    if (annotations.any { it.name == "config" }) {
        val ctorParamCount2 = structCtorParamCount[name] ?: fields.size
        emitConfigLoad(name, fields.take(ctorParamCount2), isClass)
    }

    val delegationList = classDelegations[name] ?: emptyList()
    val definedMethods = members.filterIsInstance<FunctionDecl>().map { it.name }.toSet()
    for ((ifaceName, delegateFieldName, concreteTypeName) in delegationList) {
        val ifacePrefix = "$ifaceName."
        for ((key, type) in tcModule.memberTypeMap) {
            if (!key.startsWith(ifacePrefix)) continue
            val methodName = key.removePrefix(ifacePrefix)
            if (methodName in definedMethods) continue
            if (type !is Type.TFun) continue
            emitDelegationForwarder(name, methodName, type, delegateFieldName, concreteTypeName, isClass)
        }
    }
}

private fun LlvmIrEmitter.emitMethodDecl(typeName: String, fn: FunctionDecl) {
    fnBody    = StringBuilder()
    fnAllocas = StringBuilder()
    varAllocas.clear()
    varTypes.clear()
    usedAllocaNames.clear()
    tempIdx   = 0
    labelIdx  = 0
    isTerminated = false
    loopStack.clear()

    val nvRetType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
    fnReturnType = llvmType(nvRetType)

    val selfAlloca = emitAlloca("self", "i8*")
    varAllocas["self"] = Pair(selfAlloca, "i8*")
    varTypes["self"] = Type.TNamed(typeName)
    emitStore("i8*", "%self", selfAlloca)

    for (p in fn.params) {
        val pt = resolveTypeNode(p.type)
        val lt = llvmType(pt)
        val allocaReg = emitAlloca(p.name, lt)
        varAllocas[p.name] = Pair(allocaReg, lt)
        emitStore(lt, "%param.${p.name}", allocaReg)
    }

    for (stmt in fn.body) emitStmt(stmt)
    if (!isTerminated) {
        when (fnReturnType) {
            "void"   -> terminate("  ret void")
            "i32"    -> terminate("  ret i32 0")
            "i64"    -> terminate("  ret i64 0")
            "i1"     -> terminate("  ret i1 0")
            "double" -> terminate("  ret double 0.0")
            else     -> terminate("  ret i8* null")
        }
    }

    val opSuffix = operatorToSuffix(fn.name)
    val mangledName = if (opSuffix != null) "@nv_${typeName}_op_$opSuffix" else "@nv_${typeName}_${fn.name}"
    val paramList = buildString {
        append("i8* %self")
        for (p in fn.params) append(", ${llvmType(resolveTypeNode(p.type))} %param.${p.name}")
    }

    methodReturnTypes["${typeName}_${fn.name}"] = fnReturnType
    if (fn.name == "iter") {
        val iterNvType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
        val iterTypeName = iterNvType.simpleTypeName()
        if (iterTypeName.isNotEmpty()) iteratorClassNames[typeName] = iterTypeName
    }

    userFns.appendLine("define $fnReturnType $mangledName($paramList) {")
    userFns.appendLine("entry:")
    userFns.append(fnAllocas)
    userFns.append(fnBody)
    userFns.appendLine("}")
    userFns.appendLine()
}
