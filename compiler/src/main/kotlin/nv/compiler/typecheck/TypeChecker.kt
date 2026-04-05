package nv.compiler.typecheck

import nv.compiler.lexer.SourceSpan
import nv.compiler.parser.*
import nv.compiler.resolve.*

/**
 * Phase 1.4 — Bidirectional type checker.
 *
 * Receives a [ResolvedModule] from name resolution and produces a [TypeCheckResult].
 *
 * Design:
 *  - Two-pass over declarations: first pass registers named types and their members;
 *    second pass checks all bodies.
 *  - [synthesize] infers a type bottom-up; [checkExpr] verifies top-down against an expected type.
 *  - Null safety: if-let / guard-let narrow nullable bindings inside their scope.
 *  - Exhaustiveness: match arms on Bool / sealed / enum / nullable are checked for coverage.
 *  - Generics: simple substitution map for concrete instantiations; TVar passes through otherwise.
 */
class TypeChecker(private val resolvedModule: ResolvedModule) {

    // ── State ─────────────────────────────────────────────────────────────

    private val errors   = mutableListOf<TypeCheckError>()
    private val typeMap  = mutableMapOf<Int, Type>()          // expr span-offset → Type

    /** memberTypeMap: "TypeName.fieldName" → Type (populated from declarations). */
    private val memberTypeMap = mutableMapOf<String, Type>()

    /** SealedClass/Enum type name → list of variant/case names. */
    private val sealedVariants = mutableMapOf<String, List<String>>()
    private val enumCases      = mutableMapOf<String, List<String>>()

    /** Current function's declared return type (for checking return statements). */
    private var currentReturnType: Type? = null

    /** Whether the current function is declared async (for validating await expressions). */
    private var currentFunctionIsAsync: Boolean = false

    /** Whether the current function has a @gpu annotation. */
    private var currentFunctionIsGpu: Boolean = false

    /** Whether the current function returns Sequence<T> (for yield validation). */
    private var currentFunctionIsSequence: Boolean = false
    private var currentSequenceElementType: Type = Type.TUnknown

    /** Type parameter names in scope (for resolveTypeNode to produce TVar). */
    private var currentTypeParams: Set<String> = emptySet()

    private fun <T> withTypeParams(params: Set<String>, block: () -> T): T {
        val prev = currentTypeParams
        currentTypeParams = currentTypeParams + params
        return block().also { currentTypeParams = prev }
    }

    // ── Entry point ───────────────────────────────────────────────────────

    fun check(): TypeCheckResult {
        initBuiltinTypes()
        buildTypeRegistry()         // pass 1: named types + member types
        val env = TypeEnv()
        seedEnvFromScope(resolvedModule.moduleScope, env)
        resolvedModule.file.declarations.forEach { checkDecl(it, env) }

        val checked = TypeCheckedModule(resolvedModule, typeMap.toMap(), memberTypeMap.toMap(), errors.toList())
        return when {
            errors.any { it is TypeCheckError.TypeMismatch && !isRecoverable(it) } ->
                TypeCheckResult.Recovered(checked)
            errors.isEmpty() -> TypeCheckResult.Success(checked)
            else             -> TypeCheckResult.Recovered(checked)
        }
    }

    private fun isRecoverable(e: TypeCheckError): Boolean = when (e) {
        is TypeCheckError.CannotInferType -> false
        else -> true
    }

    // ── Built-in type seeding ─────────────────────────────────────────────

    private fun initBuiltinTypes() {
        // Update BuiltinSym.resolvedType for well-known built-ins in the builtin scope
        fun setBuiltin(name: String, type: Type) {
            resolvedModule.moduleScope.lookup(name)?.let {
                if (it is Symbol.BuiltinSym) it.resolvedType = type
            }
        }
        // print/println accept any value → modelled as (unknown) → ()
        setBuiltin("print",   Type.TFun(listOf(Type.TUnknown), Type.TUnit))
        setBuiltin("println", Type.TFun(listOf(Type.TUnknown), Type.TUnit))
        // true / false
        setBuiltin("true",  Type.TBool)
        setBuiltin("false", Type.TBool)
        // nil stays TNullable(TUnknown) — context-dependent
        setBuiltin("nil", Type.TNullable(Type.TUnknown))
        // Ok/Err are generic constructors — TUnknown works here
        setBuiltin("Ok",  Type.TFun(listOf(Type.TUnknown), Type.TResult(Type.TUnknown, Type.TUnknown)))
        setBuiltin("Err", Type.TFun(listOf(Type.TUnknown), Type.TResult(Type.TUnknown, Type.TUnknown)))
    }

    /** Walk the module scope and seed the env with already-typed symbols. */
    private fun seedEnvFromScope(scope: Scope, env: TypeEnv) {
        for (sym in scope.localSymbols) {
            if (sym.resolvedType != Type.TUnknown) env.define(sym.name, sym.resolvedType)
        }
    }

    // ── Pass 1: type registry ─────────────────────────────────────────────

    private fun buildTypeRegistry() {
        resolvedModule.file.declarations.forEach { registerDecl(it) }
    }

    private fun registerDecl(decl: Decl) {
        when (decl) {
            is ClassDecl    -> registerClassLike(decl.name, decl.constructorParams, decl.members, decl.typeParams)
            is StructDecl   -> registerClassLike(decl.name, decl.constructorParams, decl.members, decl.typeParams)
            is RecordDecl   -> registerClassLike(decl.name, decl.constructorParams, decl.members, decl.typeParams)
            is SealedClassDecl -> {
                val variantNames = decl.variants.map { it.name }
                sealedVariants[decl.name] = variantNames
                // Each variant is also a named type and a constructor
                for (variant in decl.variants) {
                    val paramTypes = variant.params.map { resolveTypeNode(it.type) }
                    val constructorType = if (paramTypes.isEmpty())
                        Type.TNamed(decl.name, decl.typeParams.map { Type.TVar(it.name) })
                    else
                        Type.TFun(paramTypes, Type.TNamed(decl.name, decl.typeParams.map { Type.TVar(it.name) }))
                    memberTypeMap["${decl.name}.${variant.name}"] = constructorType
                }
                // The sealed type itself
                resolvedModule.moduleScope.lookup(decl.name)?.let { sym ->
                    sym.resolvedType = Type.TNamed(decl.name, decl.typeParams.map { Type.TVar(it.name) })
                }
            }
            is EnumDecl -> {
                enumCases[decl.name] = decl.cases.map { it.name }
                val enumType = Type.TNamed(decl.name)
                resolvedModule.moduleScope.lookup(decl.name)?.let { sym ->
                    sym.resolvedType = enumType
                }
                for (case in decl.cases) {
                    if (case.associatedParams.isEmpty()) {
                        memberTypeMap["${decl.name}.${case.name}"] = enumType
                    } else {
                        val paramTypes = case.associatedParams.map { resolveTypeNode(it.type) }
                        memberTypeMap["${decl.name}.${case.name}"] = Type.TFun(paramTypes, enumType)
                    }
                }
            }
            is TypeAliasDecl -> {
                val aliased = resolveTypeNode(decl.aliasedType)
                resolvedModule.moduleScope.lookup(decl.name)?.let { sym ->
                    sym.resolvedType = aliased
                }
            }
            is FunctionDecl -> {
                // Pre-compute function type so callers can synthesize it
                withTypeParams(decl.typeParams.map { it.name }.toSet()) {
                    val paramTypes = decl.params.map { resolveTypeNode(it.type) }
                    val retType = decl.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
                    val fnType = Type.TFun(paramTypes, retType)
                    resolvedModule.moduleScope.lookup(decl.name)?.let { sym ->
                        sym.resolvedType = fnType
                    }
                }
            }
            is ExtendDecl -> {
                // Register extension members on the target type
                val targetName = decl.target.name.text
                for (member in decl.members) {
                    if (member is FunctionDecl) {
                        val paramTypes = member.params.map { resolveTypeNode(it.type) }
                        val retType = member.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
                        memberTypeMap["$targetName.${member.name}"] = Type.TFun(paramTypes, retType)
                    }
                    if (member is ComputedPropertyDecl) {
                        memberTypeMap["$targetName.${member.name}"] = resolveTypeNode(member.returnType)
                    }
                }
            }
            is InterfaceDecl -> {
                for (member in decl.members) {
                    val (memberName, pTypes, rType) = when (member) {
                        is FunctionDecl -> Triple(
                            member.name,
                            member.params.map { resolveTypeNode(it.type) },
                            member.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit,
                        )
                        is FunctionSignatureDecl -> Triple(
                            member.name,
                            member.params.map { resolveTypeNode(it.type) },
                            member.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit,
                        )
                        else -> continue
                    }
                    memberTypeMap["${decl.name}.$memberName"] = Type.TFun(pTypes, rType)
                }
            }
            is ConditionalCompilationBlock -> {
                decl.thenDecls.forEach { registerDecl(it) }
                decl.elseDecls.forEach { registerDecl(it) }
            }
            else -> {}
        }
    }

    private fun registerClassLike(
        name: String,
        constructorParams: List<ConstructorParam>,
        members: List<Decl>,
        typeParams: List<TypeParam>,
    ) {
        withTypeParams(typeParams.map { it.name }.toSet()) {
            val typeArgs = typeParams.map { Type.TVar(it.name) }
            val selfType = Type.TNamed(name, typeArgs)

            // Constructor type
            if (constructorParams.isNotEmpty()) {
                val pTypes = constructorParams.map { resolveTypeNode(it.type) }
                resolvedModule.moduleScope.lookup(name)?.let { sym ->
                    sym.resolvedType = Type.TFun(pTypes, selfType)
                }
            } else {
                resolvedModule.moduleScope.lookup(name)?.let { sym ->
                    sym.resolvedType = selfType
                }
            }

            // Constructor params as members
            for (cp in constructorParams) {
                memberTypeMap["$name.${cp.name}"] = resolveTypeNode(cp.type)
            }

            // Body members
            for (member in members) {
                when (member) {
                    is FieldDecl -> memberTypeMap["$name.${member.name}"] = resolveTypeNode(member.typeAnnotation)
                    is FunctionDecl -> withTypeParams(member.typeParams.map { it.name }.toSet()) {
                        val pTypes = member.params.map { resolveTypeNode(it.type) }
                        val rType = member.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
                        memberTypeMap["$name.${member.name}"] = Type.TFun(pTypes, rType)
                    }
                    is ComputedPropertyDecl ->
                        memberTypeMap["$name.${member.name}"] = resolveTypeNode(member.returnType)
                    is VarDecl ->
                        if (member.typeAnnotation != null)
                            memberTypeMap["$name.${member.name}"] = resolveTypeNode(member.typeAnnotation)
                    is LetDecl ->
                        if (member.typeAnnotation != null)
                            memberTypeMap["$name.${member.name}"] = resolveTypeNode(member.typeAnnotation)
                    else -> {}
                }
            }
        }
    }

    // ── TypeNode → Type ───────────────────────────────────────────────────

    fun resolveTypeNode(node: TypeNode): Type = when (node) {
        is NamedTypeNode -> {
            val name = node.name.text
            val args = node.typeArgs.map { resolveTypeNode(it) }
            when {
                name == "int"     -> Type.TInt
                name == "int64"   -> Type.TInt64
                name == "float"   -> Type.TFloat
                name == "float32" -> Type.TFloat32
                name == "float64" -> Type.TFloat64
                name == "bool"    -> Type.TBool
                name == "str"     -> Type.TStr
                name == "byte"    -> Type.TByte
                name == "char"    -> Type.TChar
                name == "()"      -> Type.TUnit
                name == "Result"  -> when (args.size) {
                    0    -> Type.TResult(Type.TUnknown, Type.TNamed("Error"))
                    1    -> Type.TResult(args[0], Type.TNamed("Error"))
                    else -> Type.TResult(args[0], args[1])
                }
                name == "GpuBuffer" -> Type.TGpuBuffer(args.firstOrNull() ?: Type.TUnknown)
                name == "Sequence"  -> Type.TSequence(args.firstOrNull() ?: Type.TUnknown)
                name == "Iterator"  -> Type.TNamed("Iterator", args)
                name == "Iterable"  -> Type.TNamed("Iterable", args)
                // Generic type parameter in scope
                name in currentTypeParams -> Type.TVar(name)
                else -> {
                    // Expand type alias if found
                    val sym = resolvedModule.moduleScope.lookup(name)
                    if (sym is Symbol.TypeAliasSym && sym.resolvedType != Type.TUnknown) {
                        sym.resolvedType
                    } else {
                        Type.TNamed(name, args)
                    }
                }
            }
        }
        is NullableTypeNode -> {
            var t = resolveTypeNode(node.inner)
            repeat(node.depth) { t = Type.TNullable(t) }
            t
        }
        is ArrayTypeNode   -> Type.TArray(resolveTypeNode(node.element))
        is MatrixTypeNode  -> Type.TMatrix(resolveTypeNode(node.element))
        is MapTypeNode     -> Type.TMap(resolveTypeNode(node.key), resolveTypeNode(node.value))
        is FnTypeNode      -> Type.TFun(node.paramTypes.map { resolveTypeNode(it) }, resolveTypeNode(node.returnType))
        is TupleTypeNode   -> Type.TTuple(node.fields.map { TupleField(it.name, resolveTypeNode(it.type)) })
        is PtrTypeNode     -> Type.TNamed("ptr", listOf(resolveTypeNode(node.inner)))
    }

    // ── TypeEnv: tracks narrowed types in branches ────────────────────────

    inner class TypeEnv(val parent: TypeEnv? = null) {
        private val bindings = mutableMapOf<String, Type>()

        fun define(name: String, type: Type) { bindings[name] = type }
        fun lookup(name: String): Type? = bindings[name] ?: parent?.lookup(name)
        fun child(): TypeEnv = TypeEnv(this)

        /** Return a child env with [name] bound to [type] (for narrowing). */
        fun withNarrowed(name: String, type: Type): TypeEnv = child().also { it.define(name, type) }
    }

    // ── Resolve identifier type ───────────────────────────────────────────

    /**
     * Look up the type of a name.
     * Priority: narrowed env binding > resolved symbol type > TUnknown.
     */
    private fun lookupType(name: String, expr: Expr, env: TypeEnv): Type {
        // 1. Narrowed binding (e.g. inside if-let branch)
        env.lookup(name)?.let { return it }
        // 2. Symbol from resolver
        val sym = resolvedModule.resolvedRefs[expr.span.start.offset]
        return sym?.resolvedType ?: Type.TUnknown
    }

    // ── Declaration checking ──────────────────────────────────────────────

    private fun checkDecl(decl: Decl, env: TypeEnv) {
        when (decl) {
            is FunctionDecl        -> checkFunctionDecl(decl, env)
            is ClassDecl           -> checkClassLikeDecl(decl.name, decl.constructorParams, decl.members, env, decl.typeParams)
            is StructDecl          -> checkClassLikeDecl(decl.name, decl.constructorParams, decl.members, env, decl.typeParams)
            is RecordDecl          -> checkClassLikeDecl(decl.name, decl.constructorParams, decl.members, env, decl.typeParams)
            is InterfaceDecl       -> decl.members.forEach { checkDecl(it, env) }
            is ExtendDecl          -> decl.members.forEach { checkDecl(it, env) }
            is SealedClassDecl     -> {} // structure already registered in pass 1
            is EnumDecl            -> {} // structure already registered in pass 1
            is TypeAliasDecl       -> {} // resolved in pass 1
            is LetDecl             -> checkLetDecl(decl, env)
            is VarDecl             -> checkVarDecl(decl, env)
            is FieldDecl           -> checkFieldDecl(decl, env)
            is FunctionSignatureDecl -> {} // interface method signature only
            is ComputedPropertyDecl  -> checkComputedProperty(decl, env)
            is ConditionalCompilationBlock -> {
                decl.thenDecls.forEach { checkDecl(it, env) }
                decl.elseDecls.forEach { checkDecl(it, env) }
            }
            is ModuleDecl, is ImportDecl, is SubscriptDecl,
            is AssocTypeDecl, is InitBlock -> {}
        }
    }

    private fun checkFunctionDecl(decl: FunctionDecl, env: TypeEnv) {
        withTypeParams(decl.typeParams.map { it.name }.toSet()) {
            val fnEnv = env.child()
            for (param in decl.params) {
                val pType = resolveTypeNode(param.type)
                fnEnv.define(param.name, pType)
            }
            val retType = decl.returnType?.let { resolveTypeNode(it) }
                ?: decl.throwsType?.let { t ->
                    Type.TResult(Type.TUnknown, resolveTypeNode(t))
                }
                ?: Type.TUnit

            val prevReturn = currentReturnType
            val prevAsync  = currentFunctionIsAsync
            val prevGpu    = currentFunctionIsGpu
            val prevSeq    = currentFunctionIsSequence
            val prevSeqElem = currentSequenceElementType
            currentReturnType = retType
            currentFunctionIsAsync = decl.isAsync
            currentFunctionIsGpu = decl.annotations.any { it.name == "gpu" }
            currentFunctionIsSequence = retType is Type.TSequence
            currentSequenceElementType = if (retType is Type.TSequence) retType.element else Type.TUnknown
            checkBody(decl.body, fnEnv, retType)
            currentReturnType = prevReturn
            currentFunctionIsAsync = prevAsync
            currentFunctionIsGpu = prevGpu
            currentFunctionIsSequence = prevSeq
            currentSequenceElementType = prevSeqElem
        }
    }

    private fun checkClassLikeDecl(
        typeName: String,
        constructorParams: List<ConstructorParam>,
        members: List<Decl>,
        env: TypeEnv,
        typeParams: List<TypeParam>,
    ) {
        val classEnv = env.child()
        for (cp in constructorParams) classEnv.define(cp.name, resolveTypeNode(cp.type))
        members.forEach { checkDecl(it, classEnv) }
    }

    private fun checkLetDecl(decl: LetDecl, env: TypeEnv) {
        val annotatedType = decl.typeAnnotation?.let { resolveTypeNode(it) }
        val initType = decl.initializer?.let { synthesize(it, env) }
        val resolved = resolveBinding(annotatedType, initType, decl.span) ?: Type.TUnknown
        // Update the symbol's resolved type
        resolvedModule.moduleScope.lookup(decl.name)?.let { it.resolvedType = resolved }
        env.define(decl.name, resolved)
    }

    private fun checkVarDecl(decl: VarDecl, env: TypeEnv) {
        val annotatedType = decl.typeAnnotation?.let { resolveTypeNode(it) }
        val initType = decl.initializer?.let { synthesize(it, env) }
        val resolved = resolveBinding(annotatedType, initType, decl.span) ?: Type.TUnknown
        resolvedModule.moduleScope.lookup(decl.name)?.let { it.resolvedType = resolved }
        env.define(decl.name, resolved)
    }

    private fun checkFieldDecl(decl: FieldDecl, env: TypeEnv) {
        val fieldType = resolveTypeNode(decl.typeAnnotation)
        if (decl.initializer != null) {
            val initType = synthesize(decl.initializer, env)
            if (!isAssignable(initType, fieldType)) {
                errors += TypeCheckError.TypeMismatch(fieldType, initType, decl.initializer.span)
            }
        }
    }

    private fun checkComputedProperty(decl: ComputedPropertyDecl, env: TypeEnv) {
        val retType = resolveTypeNode(decl.returnType)
        val prevReturn = currentReturnType
        currentReturnType = retType
        checkBody(decl.getter, env, retType)
        decl.setter?.let { checkBody(it.body, env, Type.TUnit) }
        currentReturnType = prevReturn
    }

    // ── Helper: resolve binding from annotation + initializer ─────────────

    private fun resolveBinding(annotated: Type?, initType: Type?, span: SourceSpan): Type? {
        return when {
            annotated != null && initType != null -> {
                if (!isAssignable(initType, annotated)) {
                    errors += TypeCheckError.TypeMismatch(annotated, initType, span)
                }
                annotated
            }
            annotated != null -> annotated
            initType != null  -> initType
            else -> {
                errors += TypeCheckError.CannotInferType(span)
                null
            }
        }
    }

    // ── Statement checking ────────────────────────────────────────────────

    private fun checkBody(stmts: List<Stmt>, env: TypeEnv, returnType: Type?) {
        stmts.forEach { checkStmt(it, env, returnType) }
    }

    private fun checkStmt(stmt: Stmt, env: TypeEnv, returnType: Type?) {
        when (stmt) {
            is LetStmt     -> checkLetStmt(stmt, env, returnType)
            is VarStmt     -> checkVarStmt(stmt, env, returnType)
            is ReturnStmt  -> checkReturnStmt(stmt, env, returnType)
            is AssignStmt  -> checkAssignStmt(stmt, env)
            is ExprStmt    -> { synthesize(stmt.expr, env) }
            is IfStmt      -> checkIfStmt(stmt, env, returnType)
            is GuardLetStmt -> checkGuardLetStmt(stmt, env, returnType)
            is ForStmt     -> checkForStmt(stmt, env, returnType)
            is WhileStmt   -> checkWhileStmt(stmt, env, returnType)
            is MatchStmt   -> checkMatchExpr(stmt.expr, env, null)
            is TryCatchStmt -> checkTryCatch(stmt, env, returnType)
            is ThrowStmt   -> { synthesize(stmt.expr, env) }
            is DeferStmt   -> checkDeferBody(stmt.body, env, returnType)
            is BreakStmt, is ContinueStmt -> { /* no type checks needed */ }
            is YieldStmt -> {
                if (!currentFunctionIsSequence) {
                    errors += TypeCheckError.YieldOutsideSequence(stmt.span)
                } else {
                    val exprType = synthesize(stmt.expr, env)
                    if (!isAssignable(exprType, currentSequenceElementType)) {
                        errors += TypeCheckError.TypeMismatch(currentSequenceElementType, exprType, stmt.expr.span)
                    }
                }
            }
            is UnsafeBlock -> checkBody(stmt.stmts, env, returnType)
            is AsmStmt, is BytesStmt -> { /* inline asm blocks have no Nordvest type constraints */ }
            is CBlockStmt, is CppBlockStmt -> { /* no type constraints; pass through */ }
            is GoStmt      -> checkGoStmt(stmt, env, returnType)
            is SpawnStmt   -> { synthesize(stmt.expr, env) }
            is SelectStmt  -> stmt.arms.forEach { arm ->
                when (arm) {
                    is ReceiveSelectArm -> checkBody(arm.body, env, returnType)
                    is AfterSelectArm   -> checkBody(arm.body, env, returnType)
                    is DefaultSelectArm -> checkBody(arm.body, env, returnType)
                }
            }
        }
    }

    private fun checkLetStmt(stmt: LetStmt, env: TypeEnv, returnType: Type?) {
        val annotated = stmt.typeAnnotation?.let { resolveTypeNode(it) }
        val initType  = stmt.initializer?.let { synthesize(it, env) }
        val resolved  = resolveBinding(annotated, initType, stmt.span) ?: Type.TUnknown
        bindingNames(stmt.binding).forEach { name ->
            // Update symbol if found in resolvedRefs
            resolvedModule.resolvedRefs[stmt.span.start.offset]?.let { sym ->
                sym.resolvedType = resolved
            }
            env.define(name, resolved)
        }
    }

    private fun checkVarStmt(stmt: VarStmt, env: TypeEnv, returnType: Type?) {
        val annotated = stmt.typeAnnotation?.let { resolveTypeNode(it) }
        val initType  = stmt.initializer?.let { synthesize(it, env) }
        val resolved  = resolveBinding(annotated, initType, stmt.span) ?: Type.TUnknown
        bindingNames(stmt.binding).forEach { name ->
            resolvedModule.resolvedRefs[stmt.span.start.offset]?.let { sym ->
                sym.resolvedType = resolved
            }
            env.define(name, resolved)
        }
    }

    private fun checkReturnStmt(stmt: ReturnStmt, env: TypeEnv, returnType: Type?) {
        val exprType = stmt.value?.let { synthesize(it, env) } ?: Type.TUnit
        val expected = returnType ?: currentReturnType
        if (expected != null && !isAssignable(exprType, expected)) {
            errors += TypeCheckError.ReturnTypeMismatch(expected, exprType, stmt.span)
        }
    }

    private fun checkAssignStmt(stmt: AssignStmt, env: TypeEnv) {
        val targetType = synthesize(stmt.target, env)
        val valueType  = synthesize(stmt.value, env)
        if (!isAssignable(valueType, targetType)) {
            errors += TypeCheckError.TypeMismatch(targetType, valueType, stmt.value.span)
        }
        // Mutability check: if target is an IdentExpr and resolves to a LetSym, it's immutable
        if (stmt.target is IdentExpr) {
            val sym = resolvedModule.resolvedRefs[stmt.target.span.start.offset]
            if (sym is Symbol.LetSym && !sym.isMutable) {
                errors += TypeCheckError.AssignToImmutable(stmt.target.name, stmt.target.span)
            }
        }
    }

    private fun checkIfStmt(stmt: IfStmt, env: TypeEnv, returnType: Type?) {
        if (stmt.letBinding != null) {
            // if let name = expr { ... }
            val condType = synthesize(stmt.condition, env)
            val innerType = when {
                condType is Type.TNullable -> condType.inner
                condType == Type.TUnknown  -> Type.TUnknown
                else -> {
                    errors += TypeCheckError.NotNullable(condType, stmt.condition.span)
                    Type.TError
                }
            }
            val narrowedEnv = env.withNarrowed(stmt.letBinding.name, innerType)
            checkBody(stmt.thenBody, narrowedEnv, returnType)
        } else {
            val condType = synthesize(stmt.condition, env)
            if (condType != Type.TBool && condType != Type.TUnknown && condType != Type.TError) {
                errors += TypeCheckError.ConditionNotBool(condType, stmt.condition.span)
            }
            checkBody(stmt.thenBody, env.child(), returnType)
        }
        for (clause in stmt.elseIfClauses) {
            if (clause.letBinding != null) {
                val condType = synthesize(clause.condition, env)
                val innerType = if (condType is Type.TNullable) condType.inner else Type.TUnknown
                val narrowedEnv = env.withNarrowed(clause.letBinding.name, innerType)
                checkBody(clause.body, narrowedEnv, returnType)
            } else {
                val condType = synthesize(clause.condition, env)
                if (condType != Type.TBool && condType != Type.TUnknown && condType != Type.TError) {
                    errors += TypeCheckError.ConditionNotBool(condType, clause.condition.span)
                }
                checkBody(clause.body, env.child(), returnType)
            }
        }
        stmt.elseBody?.let { checkBody(it, env.child(), returnType) }
    }

    private fun checkGuardLetStmt(stmt: GuardLetStmt, env: TypeEnv, returnType: Type?) {
        val exprType = synthesize(stmt.value, env)
        val innerType = when {
            exprType is Type.TNullable -> exprType.inner
            exprType == Type.TUnknown  -> Type.TUnknown
            else -> {
                errors += TypeCheckError.NotNullable(exprType, stmt.value.span)
                Type.TError
            }
        }
        // else body (when nil)
        checkBody(stmt.elseBody, env, returnType)
        // After guard let, the binding is available in the outer scope as narrowed type
        env.define(stmt.name, innerType)
    }

    private fun checkForStmt(stmt: ForStmt, env: TypeEnv, returnType: Type?) {
        val iterableType = synthesize(stmt.iterable, env)
        val elementType = elementTypeOf(iterableType)
        val loopEnv = env.child()
        bindingNames(stmt.binding).forEach { name -> loopEnv.define(name, elementType) }
        checkBody(stmt.body, loopEnv, returnType)
    }

    private fun checkWhileStmt(stmt: WhileStmt, env: TypeEnv, returnType: Type?) {
        val condType = synthesize(stmt.condition, env)
        if (condType != Type.TBool && condType != Type.TUnknown && condType != Type.TError) {
            errors += TypeCheckError.ConditionNotBool(condType, stmt.condition.span)
        }
        checkBody(stmt.body, env.child(), returnType)
    }

    private fun checkTryCatch(stmt: TryCatchStmt, env: TypeEnv, returnType: Type?) {
        checkBody(stmt.tryBody, env.child(), returnType)
        for (clause in stmt.catchClauses) {
            val catchEnv = env.child()
            val errType = clause.type?.let { resolveTypeNode(it) } ?: Type.TNamed("Error")
            catchEnv.define(clause.binding, errType)
            checkBody(clause.body, catchEnv, returnType)
        }
        stmt.finallyBody?.let { checkBody(it, env.child(), returnType) }
    }

    private fun checkDeferBody(body: DeferBody, env: TypeEnv, returnType: Type?) {
        when (body) {
            is SingleStmtDefer -> checkStmt(body.stmt, env, returnType)
            is BlockDefer      -> checkBody(body.stmts, env, returnType)
        }
    }

    private fun checkGoStmt(stmt: GoStmt, env: TypeEnv, returnType: Type?) {
        when (stmt.body) {
            is GoBlockBody -> checkBody(stmt.body.stmts, env, returnType)
            is GoExprBody  -> synthesize(stmt.body.expr, env)
        }
    }

    // ── Expression synthesis ──────────────────────────────────────────────

    /**
     * Synthesize (infer) the type of [expr] bottom-up.
     * Records the result in [typeMap] keyed by the expression's span offset.
     */
    fun synthesize(expr: Expr, env: TypeEnv): Type {
        val type = synthesizeRaw(expr, env)
        typeMap[expr.span.start.offset] = type
        return type
    }

    private fun synthesizeRaw(expr: Expr, env: TypeEnv): Type = when (expr) {
        // ── Literals ─────────────────────────────────────────────────────
        is IntLitExpr   -> Type.TInt
        is FloatLitExpr -> Type.TFloat
        is BoolLitExpr  -> Type.TBool
        is NilExpr      -> Type.TNullable(Type.TUnknown)
        is CharLitExpr  -> Type.TChar
        is RawStringExpr -> Type.TStr
        is ConstPiExpr  -> Type.TFloat
        is ConstInfExpr -> Type.TFloat
        is ConstEExpr   -> Type.TFloat

        // ── Interpolated string ──────────────────────────────────────────
        is InterpolatedStringExpr -> {
            for (part in expr.parts) {
                if (part is StringInterpolationPart) synthesize(part.expr, env)
            }
            Type.TStr
        }

        // ── Name references ──────────────────────────────────────────────
        is IdentExpr  -> lookupType(expr.name, expr, env)
        is WildcardExpr -> Type.TUnknown
        is ParenExpr  -> synthesize(expr.inner, env)

        // ── Unary ────────────────────────────────────────────────────────
        is UnaryExpr -> synthesizeUnary(expr, env)

        // ── Binary ───────────────────────────────────────────────────────
        is BinaryExpr -> synthesizeBinary(expr, env)

        // ── Postfix ──────────────────────────────────────────────────────
        is MemberAccessExpr -> synthesizeMemberAccess(expr, env)
        is SafeNavExpr      -> synthesizeSafeNav(expr, env)
        is CallExpr         -> synthesizeCall(expr, env)
        is IndexExpr        -> synthesizeIndex(expr, env)
        is ForceUnwrapExpr  -> synthesizeForceUnwrap(expr, env)
        is ResultPropagateExpr -> synthesizeResultPropagate(expr, env)
        is TypeTestExpr     -> { synthesize(expr.operand, env); Type.TBool }
        is SafeCastExpr     -> Type.TNullable(resolveTypeNode(expr.type))
        is ForceCastExpr    -> resolveTypeNode(expr.type)

        // ── Inline if ────────────────────────────────────────────────────
        is InlineIfExpr -> synthesizeInlineIf(expr, env)

        // ── Collections ──────────────────────────────────────────────────
        is ArrayLiteralExpr -> synthesizeArrayLiteral(expr, env)
        is MapLiteralExpr   -> synthesizeMapLiteral(expr, env)
        is EmptyMapExpr     -> Type.TMap(Type.TUnknown, Type.TUnknown)
        is TupleLiteralExpr -> synthesizeTuple(expr, env)
        is RangeExpr        -> {
            val startType = synthesize(expr.start, env)
            synthesize(expr.end, env)
            Type.TNamed("Range", listOf(startType))
        }

        // ── Comprehension ────────────────────────────────────────────────
        is ListComprehensionExpr -> {
            val compEnv = env.child()
            for (gen in expr.generators) {
                val itType = synthesize(gen.iterable, compEnv)
                val elemType = elementTypeOf(itType)
                bindingNames(gen.binding).forEach { compEnv.define(it, elemType) }
            }
            expr.guard?.let { synthesize(it, compEnv) }
            val bodyType = synthesize(expr.body, compEnv)
            Type.TArray(bodyType)
        }

        // ── Quantifiers ──────────────────────────────────────────────────
        is QuantifierExpr -> synthesizeQuantifier(expr, env)

        // ── Match ────────────────────────────────────────────────────────
        is MatchExpr -> checkMatchExpr(expr, env, null)

        // ── Lambda ───────────────────────────────────────────────────────
        is LambdaExpr -> synthesizeLambda(expr, env)

        // ── Concurrency ──────────────────────────────────────────────────
        is AwaitExpr -> {
            if (!currentFunctionIsAsync) errors += TypeCheckError.AwaitOutsideAsync(expr.span)
            val futureType = synthesize(expr.operand, env)
            when (futureType) {
                is Type.TFuture -> futureType.inner
                Type.TUnknown   -> Type.TUnknown
                Type.TError     -> Type.TError
                else -> {
                    errors += TypeCheckError.TypeMismatch(Type.TFuture(Type.TUnknown), futureType, expr.span)
                    Type.TError
                }
            }
        }
        is SpawnExpr -> {
            val inner = synthesize(expr.expr, env)
            Type.TFuture(inner)
        }
    }

    // ── Unary operators ───────────────────────────────────────────────────

    private fun synthesizeUnary(expr: UnaryExpr, env: TypeEnv): Type {
        val t = synthesize(expr.operand, env)
        return when (expr.op) {
            UnaryOp.NEGATE  -> {
                if (!t.isNumeric && t != Type.TUnknown && t != Type.TError)
                    errors += TypeCheckError.UnaryTypeMismatch("-", t, expr.span)
                t
            }
            UnaryOp.NOT     -> {
                if (t != Type.TBool && t != Type.TUnknown && t != Type.TError)
                    errors += TypeCheckError.UnaryTypeMismatch("¬", t, expr.span)
                Type.TBool
            }
            UnaryOp.BIT_NOT -> {
                if (!t.isIntLike && t != Type.TUnknown && t != Type.TError)
                    errors += TypeCheckError.UnaryTypeMismatch("~", t, expr.span)
                t
            }
        }
    }

    // ── Binary operators ──────────────────────────────────────────────────

    private fun synthesizeBinary(expr: BinaryExpr, env: TypeEnv): Type {
        val lType = synthesize(expr.left, env)
        val rType = synthesize(expr.right, env)

        // Operator overloading: check if left type defines the operator method
        if (lType is Type.TNamed) {
            val opName = expr.op.symbol
            val overloadKey = "${lType.qualifiedName}.$opName"
            val overloadType = memberTypeMap[overloadKey]
            if (overloadType is Type.TFun && overloadType.params.size == 1) {
                return overloadType.returnType
            }
        }

        // Skip operator errors for GpuBuffer types
        if (lType is Type.TGpuBuffer || rType is Type.TGpuBuffer) {
            return Type.TUnknown
        }

        return when (expr.op) {
            BinaryOp.PLUS, BinaryOp.MINUS, BinaryOp.STAR, BinaryOp.SLASH -> {
                val promo = numericPromotion(lType, rType)
                if (promo == null && lType != Type.TUnknown && rType != Type.TUnknown &&
                    lType != Type.TError && rType != Type.TError) {
                    errors += TypeCheckError.OperatorTypeMismatch(expr.op.symbol, lType, rType, expr.span)
                    Type.TError
                } else promo ?: lType
            }
            BinaryOp.INT_DIV, BinaryOp.MOD -> {
                if (!lType.isIntLike && lType != Type.TUnknown && lType != Type.TError)
                    errors += TypeCheckError.OperatorTypeMismatch("÷", lType, rType, expr.span)
                Type.TInt
            }
            BinaryOp.POWER -> {
                val promo = numericPromotion(lType, rType)
                promo ?: if (lType.isNumeric || lType == Type.TUnknown) lType else Type.TFloat
            }
            BinaryOp.EQ, BinaryOp.NEQ -> {
                // Can compare any two types that are compatible
                Type.TBool
            }
            BinaryOp.LT, BinaryOp.GT, BinaryOp.LEQ, BinaryOp.GEQ -> {
                if (!lType.isNumeric && lType != Type.TStr && lType != Type.TChar &&
                    lType != Type.TUnknown && lType != Type.TError) {
                    errors += TypeCheckError.OperatorTypeMismatch(expr.op.symbol, lType, rType, expr.span)
                }
                Type.TBool
            }
            BinaryOp.AND, BinaryOp.OR -> {
                if (lType != Type.TBool && lType != Type.TUnknown && lType != Type.TError)
                    errors += TypeCheckError.OperatorTypeMismatch(expr.op.symbol, lType, rType, expr.span)
                if (rType != Type.TBool && rType != Type.TUnknown && rType != Type.TError)
                    errors += TypeCheckError.OperatorTypeMismatch(expr.op.symbol, lType, rType, expr.span)
                Type.TBool
            }
            BinaryOp.BIT_AND, BinaryOp.BIT_OR, BinaryOp.BIT_XOR,
            BinaryOp.LSHIFT, BinaryOp.RSHIFT -> {
                if (!lType.isIntLike && lType != Type.TUnknown && lType != Type.TError)
                    errors += TypeCheckError.OperatorTypeMismatch(expr.op.symbol, lType, rType, expr.span)
                lType
            }
            BinaryOp.PIPELINE -> {
                // lType |> fn-with-_ → return type of the fn; simplified: TUnknown
                rType
            }
            BinaryOp.NULL_COALESCE -> {
                // T? ?? T → T
                val inner = if (lType is Type.TNullable) lType.inner else lType
                inner
            }
        }
    }

    // ── Member access ─────────────────────────────────────────────────────

    private fun synthesizeMemberAccess(expr: MemberAccessExpr, env: TypeEnv): Type {
        val receiverType = synthesize(expr.receiver, env)
        return memberType(receiverType, expr.member, expr.span)
    }

    private fun synthesizeSafeNav(expr: SafeNavExpr, env: TypeEnv): Type {
        val receiverType = synthesize(expr.receiver, env)
        val inner = when {
            receiverType is Type.TNullable -> receiverType.inner
            receiverType == Type.TUnknown  -> Type.TUnknown
            else -> {
                errors += TypeCheckError.NotNullable(receiverType, expr.receiver.span)
                return Type.TError
            }
        }
        val memberT = memberType(inner, expr.member, expr.span)
        return Type.TNullable(memberT)
    }

    /** Look up a member type from the registry; returns TUnknown if not found. */
    private fun memberType(receiverType: Type, member: String, span: SourceSpan): Type {
        val typeName = when (receiverType) {
            is Type.TSequence -> return when (member) {
                "map"    -> Type.TFun(listOf(Type.TFun(listOf(receiverType.element), Type.TUnknown)), Type.TSequence(Type.TUnknown))
                "filter" -> Type.TFun(listOf(Type.TFun(listOf(receiverType.element), Type.TBool)), Type.TSequence(receiverType.element))
                "take"   -> Type.TFun(listOf(Type.TInt), Type.TSequence(receiverType.element))
                "drop"   -> Type.TFun(listOf(Type.TInt), Type.TSequence(receiverType.element))
                "toList" -> Type.TFun(emptyList(), Type.TArray(receiverType.element))
                "count", "size" -> Type.TInt
                else -> Type.TUnknown
            }
            is Type.TNamed -> receiverType.qualifiedName
            is Type.TArray -> when (member) {
                "length", "size", "count" -> return Type.TInt
                "isEmpty"                 -> return Type.TBool
                else -> return memberTypeMap["Array.$member"] ?: Type.TUnknown
            }
            is Type.TStr  -> return when (member) {
                // Properties
                "length", "size", "count" -> Type.TInt
                "isEmpty"                 -> Type.TBool
                // Method function types (returned as TFun so CallExpr can call them)
                "trim", "lower", "upper", "trimStart", "trimEnd" ->
                    Type.TFun(emptyList(), Type.TStr)
                "chars"  -> Type.TFun(emptyList(), Type.TArray(Type.TChar))
                "bytes"  -> Type.TFun(emptyList(), Type.TArray(Type.TByte))
                "split"  -> Type.TFun(listOf(Type.TStr), Type.TArray(Type.TStr))
                "contains", "startsWith", "endsWith" ->
                    Type.TFun(listOf(Type.TStr), Type.TBool)
                "indexOf" -> Type.TFun(listOf(Type.TStr), Type.TInt)
                "replace" -> Type.TFun(listOf(Type.TStr, Type.TStr), Type.TStr)
                "substring" -> Type.TFun(listOf(Type.TInt, Type.TInt), Type.TStr)
                else -> memberTypeMap["str.$member"] ?: Type.TUnknown
            }
            is Type.TMap  -> return when (member) {
                "keys"   -> Type.TArray(receiverType.key)
                "values" -> Type.TArray(receiverType.value)
                "count", "size", "length" -> Type.TInt
                "isEmpty" -> Type.TBool
                else -> Type.TUnknown
            }
            else -> return Type.TUnknown
        }
        return memberTypeMap["$typeName.$member"] ?: Type.TUnknown
    }

    // ── Function calls ────────────────────────────────────────────────────

    private fun synthesizeCall(expr: CallExpr, env: TypeEnv): Type {
        val calleeType = synthesize(expr.callee, env)
        val allArgs = expr.args + (if (expr.trailingLambda != null)
            listOf(CallArg(null, expr.trailingLambda, expr.trailingLambda.span)) else emptyList())

        return when {
            calleeType == Type.TUnknown || calleeType == Type.TError -> {
                allArgs.forEach { synthesize(it.expr, env) }
                Type.TUnknown
            }
            calleeType is Type.TFun -> {
                checkCallArgs(calleeType, allArgs, env, expr.span)
                calleeType.returnType
            }
            // Safe method call: a?.method(args) where callee synthesizes to TNullable(TFun)
            calleeType is Type.TNullable && calleeType.inner is Type.TFun -> {
                val fnType = calleeType.inner
                checkCallArgs(fnType, allArgs, env, expr.span)
                Type.TNullable(fnType.returnType)
            }
            else -> {
                // Could be a constructor call on a named type
                allArgs.forEach { synthesize(it.expr, env) }
                when (calleeType) {
                    is Type.TNamed -> calleeType   // treat as constructor returning the type
                    else -> {
                        errors += TypeCheckError.NotCallable(calleeType, expr.span)
                        Type.TError
                    }
                }
            }
        }
    }

    private fun checkCallArgs(fnType: Type.TFun, allArgs: List<CallArg>, env: TypeEnv, callSpan: SourceSpan) {
        val paramTypes = fnType.params
        // Bypass arity check for single TUnknown param (variadic built-ins like print)
        if (paramTypes.size != allArgs.size &&
            !(paramTypes.size == 1 && paramTypes[0] == Type.TUnknown)) {
            errors += TypeCheckError.ArityMismatch(paramTypes.size, allArgs.size, callSpan)
        }
        allArgs.forEachIndexed { i, arg ->
            val argType = synthesize(arg.expr, env)
            val expectedParam = paramTypes.getOrNull(i) ?: paramTypes.lastOrNull() ?: Type.TUnknown
            if (!isAssignable(argType, expectedParam)) {
                errors += TypeCheckError.TypeMismatch(expectedParam, argType, arg.expr.span)
            }
        }
    }

    // ── Indexing ──────────────────────────────────────────────────────────

    private fun synthesizeIndex(expr: IndexExpr, env: TypeEnv): Type {
        val receiverType = synthesize(expr.receiver, env)
        expr.indices.forEach { idx -> if (idx.expr != null) synthesize(idx.expr, env) }
        return when (receiverType) {
            is Type.TArray  -> receiverType.element
            is Type.TMatrix -> receiverType.element
            is Type.TMap    -> Type.TNullable(receiverType.value)
            is Type.TStr    -> Type.TChar
            else            -> Type.TUnknown
        }
    }

    // ── Force unwrap / result propagate ───────────────────────────────────

    private fun synthesizeForceUnwrap(expr: ForceUnwrapExpr, env: TypeEnv): Type {
        val t = synthesize(expr.operand, env)
        return when (t) {
            is Type.TNullable -> t.inner
            Type.TUnknown     -> Type.TUnknown
            else -> {
                errors += TypeCheckError.ForceUnwrapNonNullable(t, expr.span)
                Type.TError
            }
        }
    }

    private fun synthesizeResultPropagate(expr: ResultPropagateExpr, env: TypeEnv): Type {
        val t = synthesize(expr.operand, env)
        return when {
            t is Type.TResult   -> t.okType
            t is Type.TNullable -> t.inner
            t == Type.TUnknown  -> Type.TUnknown
            else -> {
                errors += TypeCheckError.ResultPropagateNonResult(t, expr.span)
                Type.TError
            }
        }
    }

    // ── Inline if ─────────────────────────────────────────────────────────

    private fun synthesizeInlineIf(expr: InlineIfExpr, env: TypeEnv): Type {
        val condType = synthesize(expr.condition, env)
        if (condType != Type.TBool && condType != Type.TUnknown && condType != Type.TError)
            errors += TypeCheckError.ConditionNotBool(condType, expr.condition.span)
        val thenType = synthesize(expr.thenExpr, env)
        for (clause in expr.elseIfClauses) {
            synthesize(clause.condition, env)
            synthesize(clause.thenExpr, env)
        }
        val elseType = synthesize(expr.elseExpr, env)
        return joinTypes(thenType, elseType)
    }

    // ── Collections ───────────────────────────────────────────────────────

    private fun synthesizeArrayLiteral(expr: ArrayLiteralExpr, env: TypeEnv): Type {
        if (expr.elements.isEmpty()) return Type.TArray(Type.TUnknown)
        val elemTypes = expr.elements.map { synthesize(it, env) }
        val unified = elemTypes.reduce { acc, t -> joinTypes(acc, t) }
        return Type.TArray(unified)
    }

    private fun synthesizeMapLiteral(expr: MapLiteralExpr, env: TypeEnv): Type {
        if (expr.entries.isEmpty()) return Type.TMap(Type.TUnknown, Type.TUnknown)
        val keyType = synthesize(expr.entries.first().key, env)
        val valType = synthesize(expr.entries.first().value, env)
        for (entry in expr.entries.drop(1)) {
            synthesize(entry.key, env)
            synthesize(entry.value, env)
        }
        return Type.TMap(keyType, valType)
    }

    private fun synthesizeTuple(expr: TupleLiteralExpr, env: TypeEnv): Type {
        val fieldTypes = expr.elements.map { TupleField(null, synthesize(it, env)) }
        // Empty tuple () is the unit value
        return if (fieldTypes.isEmpty()) Type.TUnit else Type.TTuple(fieldTypes)
    }

    // ── Quantifiers ───────────────────────────────────────────────────────

    private fun synthesizeQuantifier(expr: QuantifierExpr, env: TypeEnv): Type {
        val qEnv = env.child()
        if (expr.iterable != null) {
            val iterType = synthesize(expr.iterable, qEnv)
            val elemType = elementTypeOf(iterType)
            expr.binding?.let { bindingNames(it).forEach { n -> qEnv.define(n, elemType) } }
        }
        return when (expr.op) {
            QuantifierOp.FORALL, QuantifierOp.EXISTS -> {
                when (val body = expr.body) {
                    is InlineQuantifierBody -> synthesize(body.expr, qEnv)
                    is BlockQuantifierBody  -> checkBody(body.stmts, qEnv, null)
                    is BareIterableBody     -> synthesize(body.iterable, qEnv)
                }
                Type.TBool
            }
            QuantifierOp.SUM, QuantifierOp.PRODUCT -> {
                when (val body = expr.body) {
                    is InlineQuantifierBody -> synthesize(body.expr, qEnv)
                    is BlockQuantifierBody  -> { checkBody(body.stmts, qEnv, null); Type.TFloat }
                    is BareIterableBody     -> synthesize(body.iterable, qEnv)
                }
            }
        }
    }

    // ── Lambda ────────────────────────────────────────────────────────────

    private fun synthesizeLambda(expr: LambdaExpr, env: TypeEnv): Type {
        val lambdaEnv = env.child()
        val paramTypes = expr.params.map { param ->
            val t = param.type?.let { resolveTypeNode(it) } ?: Type.TUnknown
            lambdaEnv.define(param.name, t)
            t
        }
        val retType = when (val body = expr.body) {
            is ExprLambdaBody  -> synthesize(body.expr, lambdaEnv)
            is BlockLambdaBody -> { checkBody(body.stmts, lambdaEnv, null); Type.TUnknown }
        }
        return Type.TFun(paramTypes, retType)
    }

    // ── Match expression / exhaustiveness ─────────────────────────────────

    /**
     * Type-checks a [MatchExpr] and verifies exhaustiveness.
     * Returns the unified type of all arm bodies (or TUnit if used as statement).
     */
    private fun checkMatchExpr(expr: MatchExpr, env: TypeEnv, expectedType: Type?): Type {
        val subjectType = synthesize(expr.subject, env)

        val armTypes = mutableListOf<Type>()
        for (arm in expr.arms) {
            val armEnv = env.child()
            bindPatternVars(arm.pattern, subjectType, armEnv)
            arm.guard?.let { synthesize(it, armEnv) }
            val bodyType = when (val body = arm.body) {
                is ExprMatchArmBody  -> synthesize(body.expr, armEnv)
                is BlockMatchArmBody -> { checkBody(body.stmts, armEnv, currentReturnType); Type.TUnit }
            }
            armTypes += bodyType
        }

        checkExhaustiveness(subjectType, expr.arms, expr.span)

        return armTypes.reduceOrNull { acc, t -> joinTypes(acc, t) } ?: Type.TUnit
    }

    /** Introduce bindings from a pattern into [env]. */
    private fun bindPatternVars(pattern: Pattern, subjectType: Type, env: TypeEnv) {
        when (pattern) {
            is BindingPattern -> env.define(pattern.name, subjectType)
            is TuplePattern   -> {
                val inner = if (subjectType is Type.TTuple) subjectType.fields else emptyList()
                pattern.elements.forEachIndexed { i, p ->
                    bindPatternVars(p, inner.getOrNull(i)?.type ?: Type.TUnknown, env)
                }
            }
            is TypePattern -> {
                // For sealed class patterns: bind positional fields
                val variantName = pattern.typeName.text
                when (val args = pattern.args) {
                    is PositionalTypePatternArgs -> {
                        // Try to look up variant param types from memberTypeMap
                        args.patterns.forEachIndexed { i, p ->
                            val variantType = memberTypeMap.entries
                                .firstOrNull { it.key.endsWith(".$variantName") }
                                ?.value
                            val elemType = when (variantType) {
                                is Type.TFun -> variantType.params.getOrNull(i) ?: Type.TUnknown
                                else         -> Type.TUnknown
                            }
                            bindPatternVars(p, elemType, env)
                        }
                    }
                    is NamedTypePatternArgs -> {
                        args.fields.forEach { field ->
                            val fieldType = memberTypeMap["$variantName.${field.name}"] ?: Type.TUnknown
                            bindPatternVars(field.pattern, fieldType, env)
                        }
                    }
                    is NoTypePatternArgs -> {}
                }
            }
            is OrPattern -> pattern.alternatives.forEach { bindPatternVars(it, subjectType, env) }
            else -> {}
        }
    }

    /**
     * Exhaustiveness checker.
     *
     * Supports:
     *  - Bool: requires `true` and `false` arms (or wildcard).
     *  - Nullable T?: requires nil arm and a non-nil arm (or wildcard).
     *  - Sealed classes: requires an arm for every variant (or wildcard).
     *  - Enums: requires an arm for every case (or wildcard).
     *  - Everything else: warns if no wildcard present (conservative).
     */
    private fun checkExhaustiveness(subjectType: Type, arms: List<MatchArm>, span: SourceSpan) {
        if (hasWildcard(arms)) return   // wildcard covers everything

        when {
            subjectType == Type.TBool -> {
                val hasTrue  = arms.any { arm -> patternCoversLiteral(arm.pattern, true) }
                val hasFalse = arms.any { arm -> patternCoversLiteral(arm.pattern, false) }
                val missing = buildList {
                    if (!hasTrue)  add("true")
                    if (!hasFalse) add("false")
                }
                if (missing.isNotEmpty())
                    errors += TypeCheckError.NonExhaustiveMatch(missing, span)
            }
            subjectType is Type.TNullable -> {
                val hasNil    = arms.any { arm -> arm.pattern is NilPattern || arm.pattern is LiteralPattern && arm.pattern.expr is NilExpr }
                val hasNonNil = arms.any { arm -> arm.pattern !is NilPattern && !(arm.pattern is LiteralPattern && arm.pattern.expr is NilExpr) }
                if (!hasNil || !hasNonNil) {
                    val missing = buildList {
                        if (!hasNil)    add("nil")
                        if (!hasNonNil) add("non-nil value")
                    }
                    errors += TypeCheckError.NonExhaustiveMatch(missing, span)
                }
            }
            subjectType is Type.TNamed -> {
                val name = subjectType.qualifiedName
                when {
                    sealedVariants.containsKey(name) -> {
                        val required = sealedVariants[name]!!
                        val covered  = coveredVariants(arms)
                        val missing  = required - covered.toSet()
                        if (missing.isNotEmpty())
                            errors += TypeCheckError.NonExhaustiveMatch(missing.toList(), span)
                    }
                    enumCases.containsKey(name) -> {
                        val required = enumCases[name]!!
                        val covered  = coveredEnumCases(arms, name)
                        val missing  = required - covered.toSet()
                        if (missing.isNotEmpty())
                            errors += TypeCheckError.NonExhaustiveMatch(missing.toList(), span)
                    }
                    else -> {} // Unknown named type: no check (e.g. int ranges)
                }
            }
            else -> {} // Other types: no exhaustiveness check
        }
    }

    private fun hasWildcard(arms: List<MatchArm>): Boolean =
        arms.any { arm -> arm.guard == null && isWildcardPattern(arm.pattern) }

    private fun isWildcardPattern(p: Pattern): Boolean = when (p) {
        is WildcardPattern -> true
        is BindingPattern  -> true   // bare name binding acts as wildcard
        is OrPattern       -> p.alternatives.all { isWildcardPattern(it) }
        else               -> false
    }

    private fun patternCoversLiteral(p: Pattern, boolValue: Boolean): Boolean = when (p) {
        is LiteralPattern -> (p.expr as? BoolLitExpr)?.value == boolValue
        is OrPattern      -> p.alternatives.any { patternCoversLiteral(it, boolValue) }
        else              -> false
    }

    private fun coveredVariants(arms: List<MatchArm>): List<String> =
        arms.mapNotNull { arm ->
            when (val p = arm.pattern) {
                is TypePattern -> p.typeName.parts.last()
                is OrPattern   -> null  // handled below
                else           -> null
            }
        } + arms.flatMap { arm ->
            if (arm.pattern is OrPattern)
                arm.pattern.alternatives.mapNotNull { p ->
                    if (p is TypePattern) p.typeName.parts.last() else null
                }
            else emptyList()
        }

    private fun coveredEnumCases(arms: List<MatchArm>, enumName: String): List<String> =
        arms.mapNotNull { arm ->
            when (val p = arm.pattern) {
                is TypePattern -> {
                    val n = p.typeName.text
                    if (n.startsWith("$enumName.")) n.removePrefix("$enumName.") else n
                }
                is LiteralPattern -> {
                    val ident = (p.expr as? IdentExpr)?.name
                    ident
                }
                else -> null
            }
        }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Given an iterable/array/range type, return the element type. */
    private fun elementTypeOf(type: Type): Type = when (type) {
        is Type.TArray     -> type.element
        is Type.TMatrix    -> type.element
        is Type.TStr       -> Type.TChar
        is Type.TGpuBuffer -> type.element
        is Type.TSequence  -> type.element
        is Type.TNamed  -> when {
            type.qualifiedName == "Range" -> type.typeArgs.firstOrNull() ?: Type.TInt
            else -> type.typeArgs.firstOrNull() ?: Type.TUnknown
        }
        else -> Type.TUnknown
    }

    /** Compute the "join" (least upper bound) of two types. */
    private fun joinTypes(a: Type, b: Type): Type = when {
        a == b -> a
        a == Type.TError || b == Type.TError -> Type.TError
        a == Type.TUnknown -> b
        b == Type.TUnknown -> a
        a == Type.TNever   -> b
        b == Type.TNever   -> a
        a.isNumeric && b.isNumeric -> numericPromotion(a, b) ?: Type.TUnknown
        // T and T? → T?
        b is Type.TNullable && isAssignable(a, b.inner) -> b
        a is Type.TNullable && isAssignable(b, a.inner) -> a
        else -> Type.TUnknown  // could not join; let downstream handle
    }

    /** Extract all names from a [Binding]. */
    private fun bindingNames(binding: Binding): List<String> = when (binding) {
        is IdentBinding -> listOf(binding.name)
        is TupleBinding -> binding.names
    }

    /** Look up the check [expr] against [expected]; emits an error if mismatch. */
    fun checkExpr(expr: Expr, expected: Type, env: TypeEnv): Type {
        val actual = synthesize(expr, env)
        if (!isAssignable(actual, expected)) {
            errors += TypeCheckError.TypeMismatch(expected, actual, expr.span)
        }
        return actual
    }
}

// ── BinaryOp.symbol helper ────────────────────────────────────────────────

private val BinaryOp.symbol: String get() = when (this) {
    BinaryOp.PLUS       -> "+"
    BinaryOp.MINUS      -> "-"
    BinaryOp.STAR       -> "*"
    BinaryOp.SLASH      -> "/"
    BinaryOp.INT_DIV    -> "÷"
    BinaryOp.MOD        -> "%"
    BinaryOp.POWER      -> "^"
    BinaryOp.BIT_AND    -> "&"
    BinaryOp.BIT_OR     -> "|"
    BinaryOp.BIT_XOR    -> "⊕"
    BinaryOp.LSHIFT     -> "<<"
    BinaryOp.RSHIFT     -> ">>"
    BinaryOp.AND        -> "&&"
    BinaryOp.OR         -> "||"
    BinaryOp.EQ         -> "=="
    BinaryOp.NEQ        -> "≠"
    BinaryOp.LT         -> "<"
    BinaryOp.GT         -> ">"
    BinaryOp.LEQ        -> "≤"
    BinaryOp.GEQ        -> "≥"
    BinaryOp.PIPELINE   -> "|>"
    BinaryOp.NULL_COALESCE -> "??"
}
