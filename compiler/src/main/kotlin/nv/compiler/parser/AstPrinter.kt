package nv.compiler.parser

/** Prints an AST node as an S-expression string for golden testing. Source spans are omitted. */
object AstPrinter {

    fun print(node: Node): String = buildString { printNode(node, 0) }

    private fun StringBuilder.printNode(node: Node, indent: Int) {
        val pad = "  ".repeat(indent)
        when (node) {
            // ── Top-level ──────────────────────────────────────────────────
            is SourceFile -> {
                append("(SourceFile")
                node.module?.let { append("\n"); append(pad); append("  module="); printNode(it, indent + 1) }
                for (imp in node.imports) { append("\n"); append(pad); append("  "); printNode(imp, indent + 1) }
                for (decl in node.declarations) { append("\n"); append(pad); append("  "); printNode(decl, indent + 1) }
                append(")")
            }

            // ── Declarations ───────────────────────────────────────────────
            is ModuleDecl -> append("(ModuleDecl name=${q(node.name.text)})")
            is ImportDecl -> append("(ImportDecl${if (node.isPub) " pub" else ""} name=${q(node.name.text)}${node.alias?.let { " as=$it" } ?: ""})")

            is FunctionDecl -> {
                append("(FunctionDecl name=${q(node.name)} vis=${node.visibility}")
                if (node.typeParams.isNotEmpty()) append(" typeParams=[${node.typeParams.joinToString { it.name }}]")
                for (p in node.params) { append("\n${pad}  "); printParam(p, indent + 1) }
                node.returnType?.let { append("\n${pad}  returnType="); printNode(it, indent + 1) }
                node.throwsType?.let { append("\n${pad}  throws="); printNode(it, indent + 1) }
                append("\n${pad}  body="); printStmts(node.body, indent + 1)
                append(")")
            }

            is FunctionSignatureDecl -> {
                append("(FunctionSignature name=${q(node.name)} vis=${node.visibility}")
                for (p in node.params) { append("\n${pad}  "); printParam(p, indent + 1) }
                node.returnType?.let { append("\n${pad}  returnType="); printNode(it, indent + 1) }
                append(")")
            }

            is ComputedPropertyDecl -> {
                append("(ComputedProperty name=${q(node.name)} vis=${node.visibility}")
                append("\n${pad}  returnType="); printNode(node.returnType, indent + 1)
                append("\n${pad}  getter="); printStmts(node.getter, indent + 1)
                node.setter?.let { append("\n${pad}  setter=(SetterBlock param=${q(it.paramName)})") }
                append(")")
            }

            is SubscriptDecl -> {
                append("(SubscriptDecl vis=${node.visibility}${if (node.isSetter) " setter" else ""}")
                for (p in node.params) { append("\n${pad}  "); printParam(p, indent + 1) }
                node.returnType?.let { append("\n${pad}  returnType="); printNode(it, indent + 1) }
                append(")")
            }

            is ClassDecl -> printClassLike("ClassDecl", node.name, node.visibility, node.typeParams, node.constructorParams, node.superTypes, node.whereClause, node.members, indent)
            is StructDecl -> printClassLike("StructDecl", node.name, node.visibility, node.typeParams, node.constructorParams, node.superTypes, node.whereClause, node.members, indent)
            is RecordDecl -> printClassLike("RecordDecl", node.name, node.visibility, node.typeParams, node.constructorParams, node.superTypes, node.whereClause, node.members, indent)

            is InterfaceDecl -> {
                append("(InterfaceDecl name=${q(node.name)} vis=${node.visibility}")
                if (node.typeParams.isNotEmpty()) append(" typeParams=[${node.typeParams.joinToString { it.name }}]")
                if (node.superTypes.isNotEmpty()) { append("\n${pad}  supers="); printTypeList(node.superTypes, indent + 1) }
                for (m in node.members) { append("\n${pad}  "); printNode(m, indent + 1) }
                append(")")
            }

            is SealedClassDecl -> {
                append("(SealedClassDecl name=${q(node.name)} vis=${node.visibility}")
                for (v in node.variants) { append("\n${pad}  (SealedVariant name=${q(v.name)})") }
                append(")")
            }

            is EnumDecl -> {
                append("(EnumDecl name=${q(node.name)} vis=${node.visibility}")
                node.rawType?.let { append("\n${pad}  rawType="); printNode(it, indent + 1) }
                for (c in node.cases) { append("\n${pad}  (EnumCase name=${q(c.name)})") }
                append(")")
            }

            is ExtendDecl -> {
                append("(ExtendDecl target="); printNode(node.target, indent + 1)
                for (m in node.members) { append("\n${pad}  "); printNode(m, indent + 1) }
                append(")")
            }

            is TypeAliasDecl -> {
                append("(TypeAlias name=${q(node.name)} vis=${node.visibility} ="); printNode(node.aliasedType, indent + 1); append(")")
            }

            is LetDecl -> {
                append("(LetDecl name=${q(node.name)} vis=${node.visibility}${if (node.isWeak) " weak" else ""}")
                node.typeAnnotation?.let { append(" type="); printNode(it, indent + 1) }
                node.initializer?.let { append(" init="); printNode(it, indent + 1) }
                append(")")
            }

            is VarDecl -> {
                append("(VarDecl name=${q(node.name)} vis=${node.visibility}${if (node.isWeak) " weak" else ""}")
                node.typeAnnotation?.let { append(" type="); printNode(it, indent + 1) }
                node.initializer?.let { append(" init="); printNode(it, indent + 1) }
                append(")")
            }

            is FieldDecl -> {
                append("(FieldDecl name=${q(node.name)} ${if (node.isMutable) "var" else "let"}${if (node.isWeak) " weak" else ""}${if (node.isUnowned) " unowned" else ""}")
                append(" type="); printNode(node.typeAnnotation, indent + 1)
                node.initializer?.let { append(" init="); printNode(it, indent + 1) }
                append(")")
            }

            is AssocTypeDecl -> {
                append("(AssocType name=${q(node.name)}")
                node.bound?.let { append(" bound="); printNode(it, indent + 1) }
                append(")")
            }

            is InitBlock -> append("(InitBlock)")

            is ConditionalCompilationBlock -> {
                append("(CCBlock pred="); printNode(node.predicate, indent + 1); append(")")
            }

            // ── CC predicates ──────────────────────────────────────────────
            is CCPlatform -> append("(cc-platform ${q(node.platform)})")
            is CCArch -> append("(cc-arch ${q(node.arch)})")
            is CCDebug -> append("(cc-debug)")
            is CCRelease -> append("(cc-release)")
            is CCFeature -> append("(cc-feature ${q(node.feature)})")

            // ── Types ──────────────────────────────────────────────────────
            is NullableTypeNode -> {
                append("(Nullable"); repeat(node.depth) { append("?") }; append(" "); printNode(node.inner, indent); append(")")
            }
            is NamedTypeNode -> {
                if (node.typeArgs.isEmpty()) {
                    append("(NamedType ${q(node.name.text)})")
                } else {
                    append("(NamedType ${q(node.name.text)} args="); printTypeList(node.typeArgs, indent); append(")")
                }
            }
            is ArrayTypeNode -> { append("(ArrayType "); printNode(node.element, indent); append(")") }
            is MatrixTypeNode -> { append("(MatrixType "); printNode(node.element, indent); append(")") }
            is MapTypeNode -> { append("(MapType key="); printNode(node.key, indent); append(" val="); printNode(node.value, indent); append(")") }
            is TupleTypeNode -> {
                append("(TupleType fields=[${node.fields.joinToString { f -> "${f.name?.let { "$it:" } ?: ""}${printInline(f.type)}" }}])")
            }
            is FnTypeNode -> {
                append("(FnType (${node.paramTypes.joinToString { printInline(it) }}) -> ${printInline(node.returnType)})")
            }
            is PtrTypeNode -> { append("(PtrType "); printNode(node.inner, indent); append(")") }

            // ── Expressions ────────────────────────────────────────────────
            is IntLitExpr -> append("(IntLit ${q(node.text)})")
            is FloatLitExpr -> append("(FloatLit ${q(node.text)})")
            is BoolLitExpr -> append("(BoolLit ${node.value})")
            is NilExpr -> append("nil")
            is CharLitExpr -> append("(CharLit ${q(node.text)})")
            is RawStringExpr -> append("(RawString ${q(node.text)})")
            is ConstPiExpr -> append("π")
            is ConstInfExpr -> append("∞")
            is ConstEExpr -> append("e")
            is IdentExpr -> append("(Ident ${q(node.name)})")
            is WildcardExpr -> append("_")
            is ParenExpr -> { append("(Paren "); printNode(node.inner, indent); append(")") }

            is InterpolatedStringExpr -> {
                append("(StrLit parts=[")
                node.parts.forEachIndexed { i, p ->
                    if (i > 0) append(", ")
                    when (p) {
                        is StringTextPart -> append("text(${q(p.text)})")
                        is StringInterpolationPart -> append("interp(${printInline(p.expr)}${p.formatSpec?.let { ":$it" } ?: ""})")
                    }
                }
                append("])")
            }

            is LambdaExpr -> {
                append("(Lambda params=[${node.params.joinToString { "${it.name}${it.type?.let { t -> ":${printInline(t)}" } ?: ""}" }}]")
                when (val b = node.body) {
                    is ExprLambdaBody -> { append(" body="); printNode(b.expr, indent + 1) }
                    is BlockLambdaBody -> { append(" body="); printStmts(b.stmts, indent + 1) }
                }
                append(")")
            }

            is BinaryExpr -> {
                append("(${node.op} ")
                printNode(node.left, indent + 1)
                append(" ")
                printNode(node.right, indent + 1)
                append(")")
            }

            is UnaryExpr -> {
                append("(${node.op} "); printNode(node.operand, indent + 1); append(")")
            }

            is MemberAccessExpr -> {
                append("(Dot "); printNode(node.receiver, indent + 1); append(" ${q(node.member)})")
            }

            is SafeNavExpr -> {
                append("(SafeNav "); printNode(node.receiver, indent + 1); append(" ${q(node.member)})")
            }

            is CallExpr -> {
                append("(Call "); printNode(node.callee, indent + 1)
                if (node.args.isNotEmpty()) {
                    append(" args=[${node.args.joinToString { a -> "${a.name?.let { "$it=" } ?: ""}${printInline(a.expr)}" }}]")
                }
                node.trailingLambda?.let { append(" trailing="); printNode(it, indent + 1) }
                append(")")
            }

            is IndexExpr -> {
                append("(Index "); printNode(node.receiver, indent + 1)
                append(" [${node.indices.joinToString { if (it.isStar) "*" else printInline(it.expr!!) }}]")
                append(")")
            }

            is ResultPropagateExpr -> { append("(Propagate "); printNode(node.operand, indent + 1); append(")") }
            is ForceUnwrapExpr -> { append("(ForceUnwrap "); printNode(node.operand, indent + 1); append(")") }
            is TypeTestExpr -> { append("(is "); printNode(node.operand, indent + 1); append(" "); printNode(node.type, indent + 1); append(")") }
            is SafeCastExpr -> { append("(as? "); printNode(node.operand, indent + 1); append(" "); printNode(node.type, indent + 1); append(")") }
            is ForceCastExpr -> { append("(as! "); printNode(node.operand, indent + 1); append(" "); printNode(node.type, indent + 1); append(")") }

            is InlineIfExpr -> {
                append("(InlineIf cond="); printNode(node.condition, indent + 1)
                append(" then="); printNode(node.thenExpr, indent + 1)
                append(" else="); printNode(node.elseExpr, indent + 1)
                append(")")
            }

            is ArrayLiteralExpr -> {
                append("[${node.elements.joinToString { printInline(it) }}]")
            }

            is MapLiteralExpr -> {
                append("[${node.entries.joinToString { "${printInline(it.key)}: ${printInline(it.value)}" }}]")
            }

            is EmptyMapExpr -> append("[:]")
            is TupleLiteralExpr -> append("(Tuple ${node.elements.joinToString { printInline(it) }})")

            is RangeExpr -> {
                val (l, r) = when (node.kind) {
                    RangeKind.CLOSED -> "[" to "]"
                    RangeKind.HALF_OPEN_LEFT -> "[" to "["
                    RangeKind.OPEN -> "]" to "["
                    RangeKind.HALF_OPEN_RIGHT -> "]" to "]"
                }
                append("(Range$l${printInline(node.start)}, ${printInline(node.end)}$r)")
            }

            is ListComprehensionExpr -> {
                append("(Comprehension body="); printNode(node.body, indent + 1)
                for (g in node.generators) append(" for ${printBinding(g.binding)} in ${printInline(g.iterable)}")
                node.guard?.let { append(" if ${printInline(it)}") }
                append(")")
            }

            is QuantifierExpr -> {
                val sym = when (node.op) {
                    QuantifierOp.FORALL -> "∀"
                    QuantifierOp.EXISTS -> "∃"
                    QuantifierOp.SUM -> "∑"
                    QuantifierOp.PRODUCT -> "∏"
                }
                append("(Quantifier $sym")
                node.binding?.let { append(" ${printBinding(it)}") }
                node.iterable?.let { append(" in ${printInline(it)}") }
                append(" body=")
                when (val b = node.body) {
                    is InlineQuantifierBody -> printNode(b.expr, indent + 1)
                    is BlockQuantifierBody -> append("block")
                    is BareIterableBody -> printNode(b.iterable, indent + 1)
                }
                append(")")
            }

            is MatchExpr -> {
                append("(Match subject="); printNode(node.subject, indent + 1)
                for (arm in node.arms) { append("\n${pad}  "); printNode(arm, indent + 1) }
                append(")")
            }

            // ── Match arm ──────────────────────────────────────────────────
            is MatchArm -> {
                append("(Arm pattern="); printNode(node.pattern, indent + 1)
                node.guard?.let { append(" guard="); printNode(it, indent + 1) }
                append(" body=")
                when (val b = node.body) {
                    is ExprMatchArmBody -> printNode(b.expr, indent + 1)
                    is BlockMatchArmBody -> printStmts(b.stmts, indent + 1)
                }
                append(")")
            }

            // ── Statements ─────────────────────────────────────────────────
            is LetStmt -> {
                append("(LetStmt ${printBinding(node.binding)}${if (node.isWeak) " weak" else ""}")
                node.typeAnnotation?.let { append(" type="); printNode(it, indent + 1) }
                node.initializer?.let { append(" init="); printNode(it, indent + 1) }
                append(")")
            }
            is VarStmt -> {
                append("(VarStmt ${printBinding(node.binding)}${if (node.isWeak) " weak" else ""}")
                node.typeAnnotation?.let { append(" type="); printNode(it, indent + 1) }
                node.initializer?.let { append(" init="); printNode(it, indent + 1) }
                append(")")
            }
            is ReturnStmt -> {
                append("(Return")
                node.value?.let { append(" "); printNode(it, indent + 1) }
                append(")")
            }
            is IfStmt -> {
                append("(If cond="); printNode(node.condition, indent + 1)
                append(" then="); printStmts(node.thenBody, indent + 1)
                for (ei in node.elseIfClauses) append(" elif=...")
                node.elseBody?.let { append(" else="); printStmts(it, indent + 1) }
                append(")")
            }
            is GuardLetStmt -> {
                append("(GuardLet name=${q(node.name)} val="); printNode(node.value, indent + 1)
                append(" else="); printStmts(node.elseBody, indent + 1)
                append(")")
            }
            is ForStmt -> {
                append("(For ${printBinding(node.binding)} in="); printNode(node.iterable, indent + 1)
                append(" body="); printStmts(node.body, indent + 1)
                append(")")
            }
            is WhileStmt -> {
                append("(While cond="); printNode(node.condition, indent + 1)
                append(" body="); printStmts(node.body, indent + 1)
                append(")")
            }
            is MatchStmt -> printNode(node.expr, indent)
            is DeferStmt -> {
                append("(Defer ")
                when (val b = node.body) {
                    is SingleStmtDefer -> printNode(b.stmt, indent + 1)
                    is BlockDefer -> printStmts(b.stmts, indent + 1)
                }
                append(")")
            }
            is TryCatchStmt -> {
                append("(TryCatch")
                append(" try="); printStmts(node.tryBody, indent + 1)
                for (c in node.catchClauses) {
                    append(" catch(${q(c.binding)}${c.type?.let { ":${printInline(it)}" } ?: ""})")
                }
                node.finallyBody?.let { append(" finally="); printStmts(it, indent + 1) }
                append(")")
            }
            is ThrowStmt -> { append("(Throw "); printNode(node.expr, indent + 1); append(")") }
            is GoStmt -> append("(Go)")
            is SpawnStmt -> { append("(Spawn "); printNode(node.expr, indent + 1); append(")") }
            is SelectStmt -> append("(Select ${node.arms.size} arms)")
            is BreakStmt -> append("(Break${node.label?.let { " @$it" } ?: ""})")
            is ContinueStmt -> append("(Continue${node.label?.let { " @$it" } ?: ""})")
            is YieldStmt -> { append("(Yield "); printNode(node.expr, indent + 1); append(")") }
            is UnsafeBlock -> { append("(Unsafe "); printStmts(node.stmts, indent + 1); append(")") }
            is AssignStmt -> {
                append("(Assign ${node.op} "); printNode(node.target, indent + 1); append(" "); printNode(node.value, indent + 1); append(")")
            }
            is ExprStmt -> printNode(node.expr, indent)

            // ── Patterns ───────────────────────────────────────────────────
            is OrPattern -> append("(OrPat ${node.alternatives.joinToString(" | ") { printInline(it) }})")
            is LiteralPattern -> { append("(LitPat "); printNode(node.expr, indent + 1); append(")") }
            is RangePattern -> printNode(node.range, indent)
            is NilPattern -> append("nil")
            is WildcardPattern -> append("_")
            is BindingPattern -> append("(Bind ${q(node.name)})")
            is TypePattern -> {
                append("(TypePat ${node.typeName.text}")
                when (val a = node.args) {
                    is PositionalTypePatternArgs -> append("(${a.patterns.joinToString { printInline(it) }})")
                    is NamedTypePatternArgs -> append("{${a.fields.joinToString { "${it.name}: ${printInline(it.pattern)}" }}}")
                    is NoTypePatternArgs -> {}
                }
                append(")")
            }
            is TuplePattern -> append("(TuplePat ${node.elements.joinToString { printInline(it) }})")

            // ── Other nodes ────────────────────────────────────────────────
            is QualifiedName -> append(q(node.text))
            is SetterBlock -> append("(SetterBlock ${q(node.paramName)})")
            is SealedVariant -> append("(SealedVariant ${q(node.name)})")
            is EnumCase -> append("(EnumCase ${q(node.name)})")
            is CatchClause -> append("(Catch ${q(node.binding)})")
            is ReceiveSelectArm, is AfterSelectArm, is DefaultSelectArm -> append("(SelectArm)")
            is StringTextPart -> append("(TextPart ${q(node.text)})")
            is StringInterpolationPart -> { append("(InterpPart "); printNode(node.expr, indent + 1); append(")") }
            is ExprLambdaBody -> printNode(node.expr, indent)
            is BlockLambdaBody -> printStmts(node.stmts, indent)
            is ExprMatchArmBody -> printNode(node.expr, indent)
            is BlockMatchArmBody -> printStmts(node.stmts, indent)
            is InlineQuantifierBody -> printNode(node.expr, indent)
            is BlockQuantifierBody -> printStmts(node.stmts, indent)
            is BareIterableBody -> printNode(node.iterable, indent)
            is PositionalTypePatternArgs -> append("(PositionalArgs)")
            is NamedTypePatternArgs -> append("(NamedArgs)")
            is NoTypePatternArgs -> append("(NoArgs)")
            is GoBlockBody -> printStmts(node.stmts, indent)
            is GoExprBody -> printNode(node.expr, indent)
            is SingleStmtDefer -> printNode(node.stmt, indent)
            is BlockDefer -> printStmts(node.stmts, indent)
            is Annotation -> append("(@${node.name})")
            is AnnotationBoolValue -> append(node.value.toString())
            is AnnotationIdentValue -> append(node.name.text)
            is AnnotationIntValue -> append(node.text)
            is AnnotationStrValue -> append(q(node.value))
            is IdentBinding -> append(node.name)
            is TupleBinding -> append("(${node.names.joinToString()})")
        }
    }

    private fun StringBuilder.printClassLike(
        tag: String,
        name: String,
        visibility: Visibility,
        typeParams: List<TypeParam>,
        constructorParams: List<ConstructorParam>,
        superTypes: List<TypeNode>,
        whereClause: List<WhereConstraint>,
        members: List<Decl>,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        append("($tag name=${q(name)} vis=$visibility")
        if (typeParams.isNotEmpty()) append(" typeParams=[${typeParams.joinToString { it.name }}]")
        if (constructorParams.isNotEmpty()) {
            append(" ctorParams=[${constructorParams.joinToString { "${it.name}:${printInline(it.type)}" }}]")
        }
        if (superTypes.isNotEmpty()) { append("\n${pad}  supers="); printTypeList(superTypes, indent + 1) }
        for (m in members) { append("\n${pad}  "); printNode(m, indent + 1) }
        append(")")
    }

    private fun StringBuilder.printStmts(stmts: List<Stmt>, indent: Int) {
        val pad = "  ".repeat(indent)
        append("[")
        for ((i, s) in stmts.withIndex()) {
            if (i > 0) append(", ")
            printNode(s, indent)
        }
        append("]")
    }

    private fun StringBuilder.printTypeList(types: List<TypeNode>, indent: Int) {
        append("[")
        types.forEachIndexed { i, t -> if (i > 0) append(", "); printNode(t, indent) }
        append("]")
    }

    private fun StringBuilder.printParam(p: Param, indent: Int) {
        append("(Param name=${q(p.name)} type="); printNode(p.type, indent); append("${if (p.isVariadic) "..." else ""})")
    }

    private fun printBinding(b: Binding): String = when (b) {
        is IdentBinding -> b.name
        is TupleBinding -> "(${b.names.joinToString()})"
    }

    /** Print a node inline (single line, no wrapping). Used for compact representations. */
    fun printInline(node: Node): String {
        val sb = StringBuilder()
        sb.printNode(node, 0)
        return sb.toString()
    }

    private fun q(s: String): String = "\"$s\""
}
