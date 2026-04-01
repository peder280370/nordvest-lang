package nv.compiler.resolve

import nv.compiler.parser.AfterSelectArm
import nv.compiler.parser.AwaitExpr
import nv.compiler.parser.ArrayLiteralExpr
import nv.compiler.parser.ArrayTypeNode
import nv.compiler.parser.AssignStmt
import nv.compiler.parser.AssocTypeDecl
import nv.compiler.parser.BinaryExpr
import nv.compiler.parser.BindingPattern
import nv.compiler.parser.BlockDefer
import nv.compiler.parser.BlockLambdaBody
import nv.compiler.parser.BlockMatchArmBody
import nv.compiler.parser.BlockQuantifierBody
import nv.compiler.parser.BoolLitExpr
import nv.compiler.parser.BreakStmt
import nv.compiler.parser.BareIterableBody
import nv.compiler.parser.CallExpr
import nv.compiler.parser.CharLitExpr
import nv.compiler.parser.ClassDecl
import nv.compiler.parser.ComputedPropertyDecl
import nv.compiler.parser.ConditionalCompilationBlock
import nv.compiler.parser.ConstEExpr
import nv.compiler.parser.ConstInfExpr
import nv.compiler.parser.ConstPiExpr
import nv.compiler.parser.ContinueStmt
import nv.compiler.parser.Decl
import nv.compiler.parser.DefaultSelectArm
import nv.compiler.parser.DeferStmt
import nv.compiler.parser.EmptyMapExpr
import nv.compiler.parser.EnumDecl
import nv.compiler.parser.ExprLambdaBody
import nv.compiler.parser.ExprMatchArmBody
import nv.compiler.parser.ExprStmt
import nv.compiler.parser.ExtendDecl
import nv.compiler.parser.FieldDecl
import nv.compiler.parser.FloatLitExpr
import nv.compiler.parser.FnTypeNode
import nv.compiler.parser.ForceCastExpr
import nv.compiler.parser.ForceUnwrapExpr
import nv.compiler.parser.ForStmt
import nv.compiler.parser.FunctionDecl
import nv.compiler.parser.FunctionSignatureDecl
import nv.compiler.parser.GoBlockBody
import nv.compiler.parser.GoExprBody
import nv.compiler.parser.GoStmt
import nv.compiler.parser.GuardLetStmt
import nv.compiler.parser.IdentBinding
import nv.compiler.parser.IdentExpr
import nv.compiler.parser.IfStmt
import nv.compiler.parser.ImportDecl
import nv.compiler.parser.IndexExpr
import nv.compiler.parser.InitBlock
import nv.compiler.parser.InlineIfExpr
import nv.compiler.parser.InlineQuantifierBody
import nv.compiler.parser.InterfaceDecl
import nv.compiler.parser.IntLitExpr
import nv.compiler.parser.LambdaExpr
import nv.compiler.parser.LetDecl
import nv.compiler.parser.LetStmt
import nv.compiler.parser.ListComprehensionExpr
import nv.compiler.parser.LiteralPattern
import nv.compiler.parser.MapLiteralExpr
import nv.compiler.parser.MapTypeNode
import nv.compiler.parser.MatchExpr
import nv.compiler.parser.MatchStmt
import nv.compiler.parser.MatrixTypeNode
import nv.compiler.parser.MemberAccessExpr
import nv.compiler.parser.ModuleDecl
import nv.compiler.parser.NamedTypeNode
import nv.compiler.parser.NilExpr
import nv.compiler.parser.NilPattern
import nv.compiler.parser.NoTypePatternArgs
import nv.compiler.parser.NamedTypePatternArgs
import nv.compiler.parser.NullableTypeNode
import nv.compiler.parser.OrPattern
import nv.compiler.parser.Param
import nv.compiler.parser.ParenExpr
import nv.compiler.parser.Pattern
import nv.compiler.parser.PositionalTypePatternArgs
import nv.compiler.parser.PtrTypeNode
import nv.compiler.parser.QualifiedName
import nv.compiler.parser.QuantifierExpr
import nv.compiler.parser.RangeExpr
import nv.compiler.parser.RangePattern
import nv.compiler.parser.RawStringExpr
import nv.compiler.parser.ReceiveSelectArm
import nv.compiler.parser.RecordDecl
import nv.compiler.parser.ResultPropagateExpr
import nv.compiler.parser.ReturnStmt
import nv.compiler.parser.SafeCastExpr
import nv.compiler.parser.SafeNavExpr
import nv.compiler.parser.SealedClassDecl
import nv.compiler.parser.SealedVariant
import nv.compiler.parser.SelectStmt
import nv.compiler.parser.SingleStmtDefer
import nv.compiler.parser.SourceFile
import nv.compiler.parser.SpawnExpr
import nv.compiler.parser.SpawnStmt
import nv.compiler.parser.Stmt
import nv.compiler.parser.StringInterpolationPart
import nv.compiler.parser.StructDecl
import nv.compiler.parser.SubscriptDecl
import nv.compiler.parser.ThrowStmt
import nv.compiler.parser.TryCatchStmt
import nv.compiler.parser.TupleBinding
import nv.compiler.parser.TupleLiteralExpr
import nv.compiler.parser.TuplePattern
import nv.compiler.parser.TupleTypeNode
import nv.compiler.parser.TypeAliasDecl
import nv.compiler.parser.TypeNode
import nv.compiler.parser.TypePattern
import nv.compiler.parser.TypeTestExpr
import nv.compiler.parser.UnaryExpr
import nv.compiler.parser.UnsafeBlock
import nv.compiler.parser.InterpolatedStringExpr
import nv.compiler.parser.VarDecl
import nv.compiler.parser.VarStmt
import nv.compiler.parser.Visibility
import nv.compiler.parser.WhileStmt
import nv.compiler.parser.WildcardExpr
import nv.compiler.parser.WildcardPattern
import nv.compiler.parser.YieldStmt
import nv.compiler.lexer.SourceSpan
import nv.compiler.typecheck.Type

class Resolver(private val sourcePath: String) {

    // ── Built-in root scope ───────────────────────────────────────────────

    private val builtinScope: Scope = buildBuiltinScope()

    private fun buildBuiltinScope(): Scope {
        val scope = Scope(ScopeKind.BUILTIN)

        // Primitive types
        listOf("int", "int64", "float", "float32", "float64", "bool", "str", "byte", "char")
            .forEach { name ->
                scope.define(Symbol.BuiltinSym(name, resolvedType = Type.TNamed(name)))
            }

        // Built-in constants
        scope.define(Symbol.BuiltinSym("true",  resolvedType = Type.TBool))
        scope.define(Symbol.BuiltinSym("false", resolvedType = Type.TBool))
        scope.define(Symbol.BuiltinSym("nil",   resolvedType = Type.TNullable(Type.TUnknown)))

        // Built-in functions
        scope.define(Symbol.BuiltinSym("print",   resolvedType = Type.TFun(listOf(Type.TStr), Type.TUnit)))
        scope.define(Symbol.BuiltinSym("println", resolvedType = Type.TFun(listOf(Type.TStr), Type.TUnit)))

        // Result constructors
        scope.define(Symbol.BuiltinSym("Ok",  resolvedType = Type.TUnknown))
        scope.define(Symbol.BuiltinSym("Err", resolvedType = Type.TUnknown))

        return scope
    }

    // ── Accumulated state ─────────────────────────────────────────────────

    private val errors: MutableList<ResolveError> = mutableListOf()
    private val resolvedRefs: MutableMap<Int, Symbol> = mutableMapOf()
    private val importRecords: MutableList<ResolvedImport> = mutableListOf()

    // ── Entry point ───────────────────────────────────────────────────────

    fun resolve(file: SourceFile): ResolveResult {
        val moduleScope = Scope(ScopeKind.MODULE, parent = builtinScope)

        // Pass 1: collect all module-level declarations to enable forward references
        for (decl in file.declarations) {
            collectDecl(decl, moduleScope)
        }

        // Pass 2: resolve all name references
        resolveFile(file, moduleScope)

        val module = ResolvedModule(
            file,
            moduleScope,
            resolvedRefs.toMap(),
            importRecords.toList(),
            errors.toList(),
        )
        return when {
            errors.isEmpty() -> ResolveResult.Success(module)
            else             -> ResolveResult.Recovered(module)
        }
    }

    // ── Pass 1: collect ───────────────────────────────────────────────────

    private fun collectDecl(decl: Decl, scope: Scope) {
        val error = when (decl) {
            is FunctionDecl    -> scope.define(Symbol.FunctionSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is ClassDecl       -> scope.define(Symbol.ClassSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is StructDecl      -> scope.define(Symbol.StructSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is RecordDecl      -> scope.define(Symbol.RecordSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is InterfaceDecl   -> scope.define(Symbol.InterfaceSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is SealedClassDecl -> scope.define(Symbol.SealedClassSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is EnumDecl        -> scope.define(Symbol.EnumSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is TypeAliasDecl   -> scope.define(Symbol.TypeAliasSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is LetDecl         -> scope.define(Symbol.LetSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is VarDecl         -> scope.define(Symbol.VarSym(decl.name, decl.span, decl.visibility, SymbolOrigin.MODULE, decl = decl))
            is ConditionalCompilationBlock -> {
                for (d in decl.thenDecls + decl.elseDecls) collectDecl(d, scope)
                null
            }
            // These are not collected in pass 1 (no standalone name to bind at module level)
            is ExtendDecl, is FieldDecl, is AssocTypeDecl, is InitBlock,
            is FunctionSignatureDecl, is ComputedPropertyDecl, is SubscriptDecl,
            is ModuleDecl, is ImportDecl -> null
        }
        if (error != null) errors += error
    }

    // ── Pass 2: resolve ───────────────────────────────────────────────────

    private fun resolveFile(file: SourceFile, scope: Scope) {
        for (imp in file.imports) {
            val localName = imp.alias ?: imp.name.parts.last()
            val sym = Symbol.ModuleSym(
                name          = localName,
                qualifiedName = imp.name.text,
                span          = imp.span,
                visibility    = if (imp.isPub) Visibility.PUBLIC else Visibility.FILE_PRIVATE,
                origin        = SymbolOrigin.MODULE,
                alias         = imp.alias,
            )
            scope.define(sym)?.let { errors += it }
            importRecords += ResolvedImport(imp.name.text, imp.alias, isResolved = false)
            errors += ResolveError.UnresolvedImport(imp.name.text, imp.span)
        }
        for (decl in file.declarations) {
            resolveDecl(decl, scope)
        }
    }

    private fun resolveDecl(decl: Decl, scope: Scope) {
        when (decl) {
            is FunctionDecl    -> resolveFunctionDecl(decl, scope)
            is FunctionSignatureDecl -> {
                for (p in decl.params) resolveTypeNode(p.type, scope)
                decl.returnType?.let { resolveTypeNode(it, scope) }
                decl.throwsType?.let { resolveTypeNode(it, scope) }
            }
            is ComputedPropertyDecl -> {
                resolveTypeNode(decl.returnType, scope)
                resolveStmts(decl.getter, scope)
                decl.setter?.let { resolveStmts(it.body, scope) }
            }
            is SubscriptDecl -> {
                for (p in decl.params) resolveTypeNode(p.type, scope)
                decl.returnType?.let { resolveTypeNode(it, scope) }
                resolveStmts(decl.body, scope)
            }
            is ClassDecl       -> resolveClassLike(decl.name, decl.typeParams, decl.constructorParams, decl.superTypes, decl.members, scope)
            is StructDecl      -> resolveClassLike(decl.name, decl.typeParams, decl.constructorParams, decl.superTypes, decl.members, scope)
            is RecordDecl      -> resolveClassLike(decl.name, decl.typeParams, decl.constructorParams, decl.superTypes, decl.members, scope)
            is InterfaceDecl   -> {
                val classScope = Scope(ScopeKind.CLASS, parent = scope)
                for (tp in decl.typeParams) {
                    classScope.define(Symbol.BuiltinSym(tp.name, tp.span, resolvedType = Type.TVar(tp.name)))
                }
                for (st in decl.superTypes) resolveTypeNode(st, classScope)
                for (m in decl.members) collectDecl(m, classScope)
                for (m in decl.members) resolveDecl(m, classScope)
            }
            is SealedClassDecl -> resolveSealedClass(decl, scope)
            is EnumDecl        -> resolveEnumDecl(decl, scope)
            is ExtendDecl      -> resolveExtendDecl(decl, scope)
            is TypeAliasDecl   -> {
                val aliasScope = Scope(ScopeKind.BLOCK, parent = scope)
                for (tp in decl.typeParams) {
                    aliasScope.define(Symbol.BuiltinSym(tp.name, tp.span, resolvedType = Type.TVar(tp.name)))
                }
                resolveTypeNode(decl.aliasedType, aliasScope)
            }
            is LetDecl -> {
                decl.typeAnnotation?.let { resolveTypeNode(it, scope) }
                decl.initializer?.let { resolveExpr(it, scope) }
            }
            is VarDecl -> {
                decl.typeAnnotation?.let { resolveTypeNode(it, scope) }
                decl.initializer?.let { resolveExpr(it, scope) }
            }
            is FieldDecl -> {
                resolveTypeNode(decl.typeAnnotation, scope)
                decl.initializer?.let { resolveExpr(it, scope) }
            }
            is AssocTypeDecl -> decl.bound?.let { resolveTypeNode(it, scope) }
            is InitBlock -> resolveStmts(decl.body, scope)
            is ConditionalCompilationBlock -> {
                for (d in decl.thenDecls + decl.elseDecls) resolveDecl(d, scope)
            }
            is ModuleDecl  -> { /* no sub-expressions */ }
            is ImportDecl  -> { /* handled in resolveFile */ }
        }
    }

    private fun resolveFunctionDecl(decl: FunctionDecl, enclosing: Scope) {
        val fnScope = Scope(ScopeKind.FUNCTION, parent = enclosing)
        for (tp in decl.typeParams) {
            fnScope.define(Symbol.BuiltinSym(tp.name, tp.span, resolvedType = Type.TVar(tp.name)))
        }
        for (p in decl.params) {
            fnScope.define(Symbol.ParamSym(p.name, p.span, param = p))?.let { errors += it }
            resolveTypeNode(p.type, fnScope)
            p.default?.let { resolveExpr(it, fnScope) }
        }
        decl.returnType?.let { resolveTypeNode(it, fnScope) }
        decl.throwsType?.let { resolveTypeNode(it, fnScope) }
        resolveStmts(decl.body, fnScope)
    }

    private fun resolveClassLike(
        name: String,
        typeParams: List<nv.compiler.parser.TypeParam>,
        constructorParams: List<nv.compiler.parser.ConstructorParam>,
        superTypes: List<TypeNode>,
        members: List<Decl>,
        enclosing: Scope,
    ) {
        val classScope = Scope(ScopeKind.CLASS, parent = enclosing)
        for (tp in typeParams) {
            classScope.define(Symbol.BuiltinSym(tp.name, tp.span, resolvedType = Type.TVar(tp.name)))
        }
        for (cp in constructorParams) {
            val syntheticParam = Param(cp.name, cp.type, cp.default, false, cp.span)
            classScope.define(Symbol.ParamSym(cp.name, cp.span, visibility = cp.visibility, param = syntheticParam))
                ?.let { errors += it }
            resolveTypeNode(cp.type, classScope)
            cp.default?.let { resolveExpr(it, classScope) }
        }
        for (st in superTypes) resolveTypeNode(st, classScope)
        for (m in members) collectDecl(m, classScope)
        for (m in members) resolveDecl(m, classScope)
    }

    private fun resolveSealedClass(decl: SealedClassDecl, enclosing: Scope) {
        val classScope = Scope(ScopeKind.CLASS, parent = enclosing)
        for (tp in decl.typeParams) {
            classScope.define(Symbol.BuiltinSym(tp.name, tp.span, resolvedType = Type.TVar(tp.name)))
        }
        for (st in decl.superTypes) resolveTypeNode(st, classScope)
        for (variant in decl.variants) {
            // Register variant name as a callable constructor in the class scope
            classScope.define(Symbol.LetSym(variant.name, variant.span, Visibility.PUBLIC, SymbolOrigin.MODULE, decl = decl))
            for (cp in variant.params) {
                resolveTypeNode(cp.type, classScope)
                cp.default?.let { resolveExpr(it, classScope) }
            }
        }
    }

    private fun resolveEnumDecl(decl: EnumDecl, enclosing: Scope) {
        val enumScope = Scope(ScopeKind.CLASS, parent = enclosing)
        decl.rawType?.let { resolveTypeNode(it, enumScope) }
        for (case in decl.cases) {
            enumScope.define(Symbol.LetSym(case.name, case.span, Visibility.PUBLIC, SymbolOrigin.MODULE, decl = decl))
            case.rawValue?.let { resolveExpr(it, enumScope) }
            for (cp in case.associatedParams) {
                resolveTypeNode(cp.type, enumScope)
                cp.default?.let { resolveExpr(it, enumScope) }
            }
        }
    }

    private fun resolveExtendDecl(decl: ExtendDecl, enclosing: Scope) {
        // Resolve the target type name
        val targetName = decl.target.name.parts.first()
        val sym = enclosing.lookup(targetName)
        if (sym == null) {
            errors += ResolveError.UndefinedSymbol(targetName, decl.target.span)
        } else {
            resolvedRefs[decl.target.span.start.offset] = sym
        }
        for (typeArg in decl.target.typeArgs) resolveTypeNode(typeArg, enclosing)
        for (conf in decl.conformances) resolveTypeNode(conf, enclosing)

        val extScope = Scope(ScopeKind.CLASS, parent = enclosing)
        for (m in decl.members) collectDecl(m, extScope)
        for (m in decl.members) resolveDecl(m, extScope)
    }

    // ── Statements ────────────────────────────────────────────────────────

    private fun resolveStmts(stmts: List<Stmt>, scope: Scope) {
        for (stmt in stmts) resolveStmt(stmt, scope)
    }

    private fun resolveStmt(stmt: Stmt, scope: Scope) {
        when (stmt) {
            is LetStmt -> {
                stmt.typeAnnotation?.let { resolveTypeNode(it, scope) }
                stmt.initializer?.let { resolveExpr(it, scope) }
                // Define AFTER initializer to prevent `let x = x` referencing itself
                defineBinding(stmt.binding, stmt, scope)
            }
            is VarStmt -> {
                stmt.typeAnnotation?.let { resolveTypeNode(it, scope) }
                stmt.initializer?.let { resolveExpr(it, scope) }
                when (val b = stmt.binding) {
                    is IdentBinding -> scope.define(Symbol.VarSym(b.name, stmt.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = stmt))
                        ?.let { errors += it }
                    is TupleBinding -> for (n in b.names) {
                        scope.define(Symbol.VarSym(n, stmt.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = stmt))
                            ?.let { errors += it }
                    }
                }
            }
            is ReturnStmt -> stmt.value?.let { resolveExpr(it, scope) }
            is IfStmt -> resolveIfStmt(stmt, scope)
            is GuardLetStmt -> {
                stmt.typeAnnotation?.let { resolveTypeNode(it, scope) }
                resolveExpr(stmt.value, scope)
                // else-body runs before the binding is visible
                resolveStmts(stmt.elseBody, Scope(ScopeKind.BLOCK, parent = scope))
                // After guard let, binding is visible in the enclosing scope
                scope.define(Symbol.LetSym(stmt.name, stmt.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = stmt))
                    ?.let { errors += it }
            }
            is ForStmt -> {
                resolveExpr(stmt.iterable, scope)
                val loopScope = Scope(ScopeKind.LOOP, parent = scope, label = stmt.label)
                defineBinding(stmt.binding, stmt, loopScope)
                resolveStmts(stmt.body, loopScope)
            }
            is WhileStmt -> {
                resolveExpr(stmt.condition, scope)
                val loopScope = Scope(ScopeKind.LOOP, parent = scope, label = stmt.label)
                resolveStmts(stmt.body, loopScope)
            }
            is MatchStmt -> resolveExpr(stmt.expr, scope)
            is DeferStmt -> {
                val deferScope = Scope(ScopeKind.BLOCK, parent = scope)
                when (val b = stmt.body) {
                    is SingleStmtDefer -> resolveStmt(b.stmt, deferScope)
                    is BlockDefer      -> resolveStmts(b.stmts, deferScope)
                }
            }
            is TryCatchStmt -> {
                resolveStmts(stmt.tryBody, Scope(ScopeKind.BLOCK, parent = scope))
                for (clause in stmt.catchClauses) {
                    val catchScope = Scope(ScopeKind.BLOCK, parent = scope)
                    clause.type?.let { resolveTypeNode(it, catchScope) }
                    catchScope.define(Symbol.LetSym(clause.binding, clause.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = stmt))
                        ?.let { errors += it }
                    resolveStmts(clause.body, catchScope)
                }
                stmt.finallyBody?.let { resolveStmts(it, Scope(ScopeKind.BLOCK, parent = scope)) }
            }
            is ThrowStmt  -> resolveExpr(stmt.expr, scope)
            is GoStmt -> {
                val goScope = Scope(ScopeKind.BLOCK, parent = scope)
                when (val b = stmt.body) {
                    is GoBlockBody -> resolveStmts(b.stmts, goScope)
                    is GoExprBody  -> resolveExpr(b.expr, goScope)
                }
            }
            is SpawnStmt  -> resolveExpr(stmt.expr, scope)
            is SelectStmt -> {
                for (arm in stmt.arms) {
                    when (arm) {
                        is ReceiveSelectArm -> {
                            resolveExpr(arm.channel, scope)
                            val armScope = Scope(ScopeKind.BLOCK, parent = scope)
                            arm.binding?.let { name ->
                                armScope.define(Symbol.LetSym(name, arm.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = stmt))
                                    ?.let { errors += it }
                            }
                            resolveStmts(arm.body, armScope)
                        }
                        is AfterSelectArm   -> resolveStmts(arm.body, Scope(ScopeKind.BLOCK, parent = scope))
                        is DefaultSelectArm -> resolveStmts(arm.body, Scope(ScopeKind.BLOCK, parent = scope))
                    }
                }
            }
            is AssignStmt -> {
                resolveExpr(stmt.target, scope)
                resolveExpr(stmt.value, scope)
            }
            is ExprStmt   -> resolveExpr(stmt.expr, scope)
            is BreakStmt, is ContinueStmt -> { /* no sub-expressions to resolve */ }
            is YieldStmt  -> resolveExpr(stmt.expr, scope)
            is UnsafeBlock -> resolveStmts(stmt.stmts, Scope(ScopeKind.BLOCK, parent = scope))
        }
    }

    private fun resolveIfStmt(stmt: IfStmt, scope: Scope) {
        val thenScope = Scope(ScopeKind.BLOCK, parent = scope)
        if (stmt.letBinding != null) {
            stmt.letBinding.typeAnnotation?.let { resolveTypeNode(it, thenScope) }
            resolveExpr(stmt.condition, scope)
            thenScope.define(Symbol.LetSym(stmt.letBinding.name, stmt.letBinding.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = stmt))
                ?.let { errors += it }
        } else {
            resolveExpr(stmt.condition, scope)
        }
        resolveStmts(stmt.thenBody, thenScope)
        for (clause in stmt.elseIfClauses) {
            val clauseScope = Scope(ScopeKind.BLOCK, parent = scope)
            if (clause.letBinding != null) {
                clause.letBinding.typeAnnotation?.let { resolveTypeNode(it, clauseScope) }
                resolveExpr(clause.condition, scope)
                clauseScope.define(Symbol.LetSym(clause.letBinding.name, clause.letBinding.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = stmt))
                    ?.let { errors += it }
            } else {
                resolveExpr(clause.condition, scope)
            }
            resolveStmts(clause.body, clauseScope)
        }
        stmt.elseBody?.let { resolveStmts(it, Scope(ScopeKind.BLOCK, parent = scope)) }
    }

    private fun defineBinding(binding: nv.compiler.parser.Binding, decl: nv.compiler.parser.Node, scope: Scope) {
        when (binding) {
            is IdentBinding -> scope.define(Symbol.LetSym(binding.name, binding.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = decl))
                ?.let { errors += it }
            is TupleBinding -> for (n in binding.names) {
                scope.define(Symbol.LetSym(n, binding.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE, decl = decl))
                    ?.let { errors += it }
            }
        }
    }

    // ── Expressions ───────────────────────────────────────────────────────

    private fun resolveExpr(expr: nv.compiler.parser.Expr, scope: Scope) {
        when (expr) {
            is IdentExpr -> {
                val sym = scope.lookup(expr.name)
                if (sym == null) {
                    errors += ResolveError.UndefinedSymbol(expr.name, expr.span)
                } else {
                    resolvedRefs[expr.span.start.offset] = sym
                }
            }
            is ParenExpr -> resolveExpr(expr.inner, scope)
            is CallExpr -> {
                resolveExpr(expr.callee, scope)
                for (arg in expr.args) resolveExpr(arg.expr, scope)
                expr.trailingLambda?.let { resolveLambda(it, scope) }
            }
            is MemberAccessExpr -> resolveExpr(expr.receiver, scope)
            is SafeNavExpr      -> resolveExpr(expr.receiver, scope)
            is BinaryExpr       -> { resolveExpr(expr.left, scope); resolveExpr(expr.right, scope) }
            is UnaryExpr        -> resolveExpr(expr.operand, scope)
            is IndexExpr        -> {
                resolveExpr(expr.receiver, scope)
                for (idx in expr.indices) idx.expr?.let { resolveExpr(it, scope) }
            }
            is LambdaExpr -> resolveLambda(expr, scope)
            is MatchExpr  -> resolveMatchExpr(expr, scope)
            is InlineIfExpr -> {
                resolveExpr(expr.condition, scope)
                resolveExpr(expr.thenExpr, scope)
                for (c in expr.elseIfClauses) {
                    resolveExpr(c.condition, scope)
                    resolveExpr(c.thenExpr, scope)
                }
                resolveExpr(expr.elseExpr, scope)
            }
            is InterpolatedStringExpr -> {
                for (part in expr.parts) {
                    if (part is StringInterpolationPart) resolveExpr(part.expr, scope)
                }
            }
            is ArrayLiteralExpr  -> expr.elements.forEach { resolveExpr(it, scope) }
            is MapLiteralExpr    -> expr.entries.forEach { resolveExpr(it.key, scope); resolveExpr(it.value, scope) }
            is TupleLiteralExpr  -> expr.elements.forEach { resolveExpr(it, scope) }
            is RangeExpr         -> { resolveExpr(expr.start, scope); resolveExpr(expr.end, scope) }
            is ListComprehensionExpr -> resolveListComp(expr, scope)
            is QuantifierExpr    -> resolveQuantifier(expr, scope)
            is ResultPropagateExpr -> resolveExpr(expr.operand, scope)
            is ForceUnwrapExpr   -> resolveExpr(expr.operand, scope)
            is TypeTestExpr  -> { resolveExpr(expr.operand, scope); resolveTypeNode(expr.type, scope) }
            is SafeCastExpr  -> { resolveExpr(expr.operand, scope); resolveTypeNode(expr.type, scope) }
            is ForceCastExpr -> { resolveExpr(expr.operand, scope); resolveTypeNode(expr.type, scope) }
            is AwaitExpr -> resolveExpr(expr.operand, scope)
            is SpawnExpr -> { val se: SpawnExpr = expr; resolveExpr(se.expr, scope) }
            // Leaf nodes — no sub-expressions to resolve
            is IntLitExpr, is FloatLitExpr, is BoolLitExpr, is NilExpr,
            is CharLitExpr, is RawStringExpr, is ConstPiExpr, is ConstInfExpr,
            is ConstEExpr, is WildcardExpr, is EmptyMapExpr -> { }
        }
    }

    private fun resolveLambda(expr: LambdaExpr, scope: Scope) {
        val lambdaScope = Scope(ScopeKind.FUNCTION, parent = scope)
        for (p in expr.params) {
            val syntheticParam = Param(p.name, p.type ?: syntheticUnknownType(p.span), null, false, p.span)
            lambdaScope.define(Symbol.ParamSym(p.name, p.span, param = syntheticParam))
                ?.let { errors += it }
            p.type?.let { resolveTypeNode(it, lambdaScope) }
        }
        when (val b = expr.body) {
            is ExprLambdaBody  -> resolveExpr(b.expr, lambdaScope)
            is BlockLambdaBody -> resolveStmts(b.stmts, lambdaScope)
        }
    }

    private fun resolveMatchExpr(expr: MatchExpr, scope: Scope) {
        resolveExpr(expr.subject, scope)
        for (arm in expr.arms) {
            val armScope = Scope(ScopeKind.BLOCK, parent = scope)
            resolvePattern(arm.pattern, armScope)
            arm.guard?.let { resolveExpr(it, armScope) }
            when (val b = arm.body) {
                is ExprMatchArmBody  -> resolveExpr(b.expr, armScope)
                is BlockMatchArmBody -> resolveStmts(b.stmts, armScope)
            }
        }
    }

    private fun resolveListComp(expr: ListComprehensionExpr, scope: Scope) {
        val compScope = Scope(ScopeKind.BLOCK, parent = scope)
        for (gen in expr.generators) {
            resolveExpr(gen.iterable, compScope)
            defineBinding(gen.binding, expr, compScope)
        }
        expr.guard?.let { resolveExpr(it, compScope) }
        resolveExpr(expr.body, compScope)
    }

    private fun resolveQuantifier(expr: QuantifierExpr, scope: Scope) {
        expr.iterable?.let { resolveExpr(it, scope) }
        val qScope = Scope(ScopeKind.BLOCK, parent = scope)
        expr.binding?.let { defineBinding(it, expr, qScope) }
        when (val body = expr.body) {
            is InlineQuantifierBody -> resolveExpr(body.expr, qScope)
            is BlockQuantifierBody  -> resolveStmts(body.stmts, qScope)
            is BareIterableBody     -> resolveExpr(body.iterable, qScope)
        }
    }

    // ── Patterns ─────────────────────────────────────────────────────────

    private fun resolvePattern(pattern: Pattern, scope: Scope) {
        when (pattern) {
            is BindingPattern -> {
                scope.define(Symbol.LetSym(pattern.name, pattern.span, Visibility.FILE_PRIVATE, SymbolOrigin.MODULE,
                    decl = nv.compiler.parser.LetStmt(pattern.span, false, IdentBinding(pattern.span, pattern.name), null, null)))
                    ?.let { errors += it }
            }
            is TypePattern -> {
                val name = pattern.typeName.parts.first()
                val sym = scope.lookup(name)
                if (sym == null) {
                    errors += ResolveError.UndefinedSymbol(name, pattern.typeName.span)
                } else {
                    resolvedRefs[pattern.typeName.span.start.offset] = sym
                }
                when (val args = pattern.args) {
                    is PositionalTypePatternArgs -> args.patterns.forEach { resolvePattern(it, scope) }
                    is NamedTypePatternArgs      -> args.fields.forEach { resolvePattern(it.pattern, scope) }
                    is NoTypePatternArgs         -> { }
                }
            }
            is OrPattern      -> pattern.alternatives.forEach { resolvePattern(it, scope) }
            is LiteralPattern -> resolveExpr(pattern.expr, scope)
            is RangePattern   -> { resolveExpr(pattern.range.start, scope); resolveExpr(pattern.range.end, scope) }
            is TuplePattern   -> pattern.elements.forEach { resolvePattern(it, scope) }
            is WildcardPattern, is NilPattern -> { }
        }
    }

    // ── Type nodes ────────────────────────────────────────────────────────

    private fun resolveTypeNode(type: TypeNode, scope: Scope) {
        when (type) {
            is NamedTypeNode -> {
                val name = type.name.parts.first()
                val sym = scope.lookup(name)
                if (sym == null) {
                    errors += ResolveError.UndefinedSymbol(name, type.span)
                } else {
                    resolvedRefs[type.span.start.offset] = sym
                }
                type.typeArgs.forEach { resolveTypeNode(it, scope) }
            }
            is NullableTypeNode -> resolveTypeNode(type.inner, scope)
            is ArrayTypeNode    -> resolveTypeNode(type.element, scope)
            is MatrixTypeNode   -> resolveTypeNode(type.element, scope)
            is MapTypeNode      -> { resolveTypeNode(type.key, scope); resolveTypeNode(type.value, scope) }
            is TupleTypeNode    -> type.fields.forEach { resolveTypeNode(it.type, scope) }
            is FnTypeNode       -> { type.paramTypes.forEach { resolveTypeNode(it, scope) }; resolveTypeNode(type.returnType, scope) }
            is PtrTypeNode      -> resolveTypeNode(type.inner, scope)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun syntheticUnknownType(span: SourceSpan): TypeNode =
        NamedTypeNode(span, QualifiedName(span, listOf("_")), emptyList())
}
