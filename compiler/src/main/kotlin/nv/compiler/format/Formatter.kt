package nv.compiler.format

import nv.compiler.parser.*
import nv.compiler.parser.Annotation as NvAnnotation

/**
 * Canonical Nordvest source formatter.
 *
 * Takes a parsed [SourceFile] AST and pretty-prints it as canonical Nordvest source.
 *
 * Key formatting rules:
 * - 4-space indentation per level
 * - Unicode operators preferred (→, ≠, ≤, ≥, ∧, ∨, ¬, ÷) unless [asciiMode] is true
 * - One blank line between top-level declarations
 * - Two blank lines before class/struct/interface/sealed/enum/record declarations
 * - Spaces around binary operators, after commas, after colons in type annotations
 * - No trailing whitespace
 * - Trailing newline
 */
class Formatter(private val asciiMode: Boolean = false) {

    private val sb = StringBuilder()
    private var indentLevel = 0
    private val indent get() = "    ".repeat(indentLevel)

    fun format(file: SourceFile): String {
        sb.clear()
        indentLevel = 0

        // Module declaration
        file.module?.let {
            appendLine("module ${it.name.text}")
            appendLine("")
        }

        // Import declarations
        if (file.imports.isNotEmpty()) {
            for (imp in file.imports) {
                formatImport(imp)
            }
            appendLine("")
        }

        // Top-level declarations
        var first = true
        for (decl in file.declarations) {
            if (!first) {
                if (isTypeDecl(decl)) {
                    appendLine("")
                    appendLine("")
                } else {
                    appendLine("")
                }
            }
            formatDecl(decl)
            first = false
        }

        // Trailing newline
        val result = sb.toString()
        return if (result.endsWith("\n")) result else result + "\n"
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun appendLine(line: String) {
        if (line.isBlank()) {
            sb.append("\n")
        } else {
            sb.append(line.trimEnd())
            sb.append("\n")
        }
    }

    private fun appendIndented(text: String) {
        appendLine("$indent$text")
    }

    private fun isTypeDecl(decl: Decl): Boolean = when (decl) {
        is ClassDecl, is StructDecl, is RecordDecl, is InterfaceDecl,
        is SealedClassDecl, is EnumDecl -> true
        else -> false
    }

    // ── Operators ─────────────────────────────────────────────────────────

    private fun arrow() = if (asciiMode) "->" else "→"
    private fun neq()   = if (asciiMode) "!=" else "≠"
    private fun leq()   = if (asciiMode) "<=" else "≤"
    private fun geq()   = if (asciiMode) ">=" else "≥"
    private fun and()   = if (asciiMode) "&&" else "∧"
    private fun or()    = if (asciiMode) "||" else "∨"
    private fun not()   = if (asciiMode) "!" else "¬"
    private fun intDiv() = if (asciiMode) "div" else "÷"

    // ── Declarations ──────────────────────────────────────────────────────

    private fun formatImport(imp: ImportDecl) {
        val pub = if (imp.isPub) "pub " else ""
        val alias = imp.alias?.let { " as $it" } ?: ""
        appendIndented("${pub}import ${imp.name.text}$alias")
    }

    private fun formatDecl(decl: Decl) {
        when (decl) {
            is ModuleDecl      -> appendIndented("module ${decl.name.text}")
            is ImportDecl      -> formatImport(decl)
            is FunctionDecl    -> formatFunctionDecl(decl)
            is FunctionSignatureDecl -> formatFunctionSigDecl(decl)
            is ComputedPropertyDecl  -> formatComputedPropertyDecl(decl)
            is SubscriptDecl   -> formatSubscriptDecl(decl)
            is ClassDecl       -> formatClassDecl(decl)
            is StructDecl      -> formatStructDecl(decl)
            is RecordDecl      -> formatRecordDecl(decl)
            is InterfaceDecl   -> formatInterfaceDecl(decl)
            is SealedClassDecl -> formatSealedClassDecl(decl)
            is EnumDecl        -> formatEnumDecl(decl)
            is ExtendDecl      -> formatExtendDecl(decl)
            is TypeAliasDecl   -> formatTypeAliasDecl(decl)
            is LetDecl         -> formatLetDecl(decl)
            is VarDecl         -> formatVarDecl(decl)
            is FieldDecl       -> formatFieldDecl(decl)
            is AssocTypeDecl   -> {
                val bound = decl.bound?.let { ": ${formatType(it)}" } ?: ""
                appendIndented("type ${decl.name}$bound")
            }
            is InitBlock -> {
                appendIndented("init")
                indentLevel++
                for (stmt in decl.body) formatStmt(stmt)
                indentLevel--
            }
            is ConditionalCompilationBlock -> formatCCBlock(decl)
        }
    }

    private fun formatAnnotations(annotations: List<NvAnnotation>) {
        for (ann in annotations) {
            appendIndented(formatAnnotation(ann))
        }
    }

    private fun formatAnnotation(ann: NvAnnotation): String {
        if (ann.args.isEmpty()) return "@${ann.name}"
        val args = ann.args.joinToString(", ") { arg ->
            val prefix = arg.name?.let { "$it: " } ?: ""
            prefix + formatAnnotationArg(arg.value)
        }
        return "@${ann.name}($args)"
    }

    private fun formatAnnotationArg(v: AnnotationArgValue): String = when (v) {
        is AnnotationStrValue   -> "\"${v.value}\""
        is AnnotationBoolValue  -> v.value.toString()
        is AnnotationIdentValue -> v.name.text
        is AnnotationIntValue   -> v.text
    }

    private fun formatDocComment(doc: String?) {
        doc ?: return
        val lines = doc.lines()
        if (lines.size == 1) {
            appendIndented("/** ${lines[0].trim()} */")
        } else {
            appendIndented("/**")
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) appendIndented(" *")
                else appendIndented(" * $trimmed")
            }
            appendIndented(" */")
        }
    }

    private fun formatVisibility(vis: Visibility): String = when (vis) {
        Visibility.PUBLIC       -> "pub "
        Visibility.PACKAGE      -> "pub(pkg) "
        Visibility.FILE_PRIVATE -> ""
    }

    private fun formatTypeParams(tps: List<TypeParam>): String {
        if (tps.isEmpty()) return ""
        return "<${tps.joinToString(", ") { tp ->
            val bound = tp.bound?.let { ": ${formatType(it)}" } ?: ""
            "${tp.name}$bound"
        }}>"
    }

    private fun formatWhereClause(wc: List<WhereConstraint>): String {
        if (wc.isEmpty()) return ""
        return " where ${wc.joinToString(", ") { "${it.typeName.text}: ${formatType(it.bound)}" }}"
    }

    private fun formatConstructorParams(params: List<ConstructorParam>): String {
        if (params.isEmpty()) return ""
        return "(${params.joinToString(", ") { cp ->
            val vis = formatVisibility(cp.visibility)
            val default = cp.default?.let { " = ${formatExpr(it)}" } ?: ""
            "${vis}${cp.name}: ${formatType(cp.type)}${default}"
        }})"
    }

    private fun formatParams(params: List<Param>): String {
        if (params.isEmpty()) return "()"
        return "(${params.joinToString(", ") { p ->
            val vararg = if (p.isVariadic) "..." else ""
            val default = p.default?.let { " = ${formatExpr(it)}" } ?: ""
            "${p.name}: ${formatType(p.type)}${vararg}${default}"
        }})"
    }

    private fun formatSuperTypes(types: List<TypeNode>): String {
        if (types.isEmpty()) return ""
        return " : ${types.joinToString(", ") { formatType(it) }}"
    }

    // ── Function declarations ─────────────────────────────────────────────

    private fun formatFunctionDecl(decl: FunctionDecl) {
        formatDocComment(decl.docComment)
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val async = if (decl.isAsync) "async " else ""
        val tps = formatTypeParams(decl.typeParams)
        val params = formatParams(decl.params)
        val ret = decl.returnType?.let { " ${arrow()} ${formatType(it)}" } ?: ""
        val throws = decl.throwsType?.let { " throws ${formatType(it)}" } ?: ""
        appendIndented("${vis}${async}fn ${decl.name}${tps}${params}${ret}${throws}")
        indentLevel++
        for (stmt in decl.body) formatStmt(stmt)
        indentLevel--
    }

    private fun formatFunctionSigDecl(decl: FunctionSignatureDecl) {
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val async = if (decl.isAsync) "async " else ""
        val tps = formatTypeParams(decl.typeParams)
        val params = formatParams(decl.params)
        val ret = decl.returnType?.let { " ${arrow()} ${formatType(it)}" } ?: ""
        val throws = decl.throwsType?.let { " throws ${formatType(it)}" } ?: ""
        appendIndented("${vis}${async}fn ${decl.name}${tps}${params}${ret}${throws}")
    }

    private fun formatComputedPropertyDecl(decl: ComputedPropertyDecl) {
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        appendIndented("${vis}fn ${decl.name} ${arrow()} ${formatType(decl.returnType)}")
        indentLevel++
        if (decl.setter != null) {
            appendIndented("get")
            indentLevel++
            for (stmt in decl.getter) formatStmt(stmt)
            indentLevel--
            appendIndented("set(${decl.setter.paramName})")
            indentLevel++
            for (stmt in decl.setter.body) formatStmt(stmt)
            indentLevel--
        } else {
            for (stmt in decl.getter) formatStmt(stmt)
        }
        indentLevel--
    }

    private fun formatSubscriptDecl(decl: SubscriptDecl) {
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val params = formatParams(decl.params)
        val ret = decl.returnType?.let { " ${arrow()} ${formatType(it)}" } ?: ""
        val setterVal = decl.setterValueParam?.let { ", ${it.name}: ${formatType(it.type)}" } ?: ""
        if (decl.isSetter) {
            appendIndented("${vis}fn []=${params}${setterVal}${ret}")
        } else {
            appendIndented("${vis}fn []${params}${ret}")
        }
        indentLevel++
        for (stmt in decl.body) formatStmt(stmt)
        indentLevel--
    }

    // ── Type declarations ─────────────────────────────────────────────────

    private fun formatClassDecl(decl: ClassDecl) {
        formatDocComment(decl.docComment)
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val tps = formatTypeParams(decl.typeParams)
        val ctor = formatConstructorParams(decl.constructorParams)
        val supers = formatSuperTypes(decl.superTypes)
        val where = formatWhereClause(decl.whereClause)
        appendIndented("${vis}class ${decl.name}${tps}${ctor}${supers}${where}")
        indentLevel++
        var first = true
        for (m in decl.members) {
            if (!first) {
                if (isTypeDecl(m)) appendLine("")
            }
            formatDecl(m)
            first = false
        }
        indentLevel--
    }

    private fun formatStructDecl(decl: StructDecl) {
        formatDocComment(decl.docComment)
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val tps = formatTypeParams(decl.typeParams)
        val ctor = formatConstructorParams(decl.constructorParams)
        val supers = formatSuperTypes(decl.superTypes)
        val where = formatWhereClause(decl.whereClause)
        appendIndented("${vis}struct ${decl.name}${tps}${ctor}${supers}${where}")
        indentLevel++
        for (m in decl.members) formatDecl(m)
        indentLevel--
    }

    private fun formatRecordDecl(decl: RecordDecl) {
        formatDocComment(decl.docComment)
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val tps = formatTypeParams(decl.typeParams)
        val ctor = formatConstructorParams(decl.constructorParams)
        val supers = formatSuperTypes(decl.superTypes)
        val where = formatWhereClause(decl.whereClause)
        appendIndented("${vis}record ${decl.name}${tps}${ctor}${supers}${where}")
        indentLevel++
        for (m in decl.members) formatDecl(m)
        indentLevel--
    }

    private fun formatInterfaceDecl(decl: InterfaceDecl) {
        formatDocComment(decl.docComment)
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val tps = formatTypeParams(decl.typeParams)
        val supers = formatSuperTypes(decl.superTypes)
        val where = formatWhereClause(decl.whereClause)
        appendIndented("${vis}interface ${decl.name}${tps}${supers}${where}")
        indentLevel++
        for (m in decl.members) formatDecl(m)
        indentLevel--
    }

    private fun formatSealedClassDecl(decl: SealedClassDecl) {
        formatDocComment(decl.docComment)
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val tps = formatTypeParams(decl.typeParams)
        val supers = formatSuperTypes(decl.superTypes)
        appendIndented("${vis}sealed class ${decl.name}${tps}${supers}")
        indentLevel++
        for (v in decl.variants) {
            val params = if (v.params.isEmpty()) "" else formatConstructorParams(v.params)
            appendIndented("${v.name}${params}")
        }
        indentLevel--
    }

    private fun formatEnumDecl(decl: EnumDecl) {
        formatDocComment(decl.docComment)
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val rawType = decl.rawType?.let { ": ${formatType(it)}" } ?: ""
        appendIndented("${vis}enum ${decl.name}${rawType}")
        indentLevel++
        for (case in decl.cases) {
            val raw = case.rawValue?.let { " = ${formatExpr(it)}" } ?: ""
            val assoc = if (case.associatedParams.isEmpty()) "" else formatConstructorParams(case.associatedParams)
            appendIndented("${case.name}${assoc}${raw}")
        }
        indentLevel--
    }

    private fun formatExtendDecl(decl: ExtendDecl) {
        val target = formatType(decl.target)
        val confs = if (decl.conformances.isEmpty()) "" else " : ${decl.conformances.joinToString(", ") { formatType(it) }}"
        val where = formatWhereClause(decl.whereClause)
        appendIndented("extend ${target}${confs}${where}")
        indentLevel++
        var first = true
        for (m in decl.members) {
            if (!first) appendLine("")
            formatDecl(m)
            first = false
        }
        indentLevel--
    }

    private fun formatTypeAliasDecl(decl: TypeAliasDecl) {
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val tps = formatTypeParams(decl.typeParams)
        appendIndented("${vis}type ${decl.name}${tps} = ${formatType(decl.aliasedType)}")
    }

    private fun formatLetDecl(decl: LetDecl) {
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val weak = if (decl.isWeak) "weak " else ""
        val typeAnn = decl.typeAnnotation?.let { ": ${formatType(it)}" } ?: ""
        val init = decl.initializer?.let { " = ${formatExpr(it)}" } ?: ""
        appendIndented("${vis}${weak}let ${decl.name}${typeAnn}${init}")
    }

    private fun formatVarDecl(decl: VarDecl) {
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val weak = if (decl.isWeak) "weak " else ""
        val typeAnn = decl.typeAnnotation?.let { ": ${formatType(it)}" } ?: ""
        val init = decl.initializer?.let { " = ${formatExpr(it)}" } ?: ""
        appendIndented("${vis}${weak}var ${decl.name}${typeAnn}${init}")
    }

    private fun formatFieldDecl(decl: FieldDecl) {
        formatAnnotations(decl.annotations)
        val vis = formatVisibility(decl.visibility)
        val weak = if (decl.isWeak) "weak " else ""
        val unowned = if (decl.isUnowned) "unowned " else ""
        val kw = if (decl.isMutable) "var" else "let"
        val init = decl.initializer?.let { " = ${formatExpr(it)}" } ?: ""
        appendIndented("${vis}${weak}${unowned}${kw} ${decl.name}: ${formatType(decl.typeAnnotation)}${init}")
    }

    private fun formatCCBlock(decl: ConditionalCompilationBlock) {
        val pred = formatCCPredicate(decl.predicate)
        appendIndented("@if($pred)")
        indentLevel++
        for (d in decl.thenDecls) formatDecl(d)
        indentLevel--
        if (decl.elseDecls.isNotEmpty()) {
            appendIndented("@else")
            indentLevel++
            for (d in decl.elseDecls) formatDecl(d)
            indentLevel--
        }
    }

    private fun formatCCPredicate(pred: CCPredicate): String = when (pred) {
        is CCPlatform -> "platform == \"${pred.platform}\""
        is CCArch     -> "arch == \"${pred.arch}\""
        is CCDebug    -> "debug"
        is CCRelease  -> "release"
        is CCFeature  -> "feature(\"${pred.feature}\")"
    }

    // ── Types ─────────────────────────────────────────────────────────────

    private fun formatType(type: TypeNode): String = when (type) {
        is NamedTypeNode -> {
            val targs = if (type.typeArgs.isEmpty()) "" else "<${type.typeArgs.joinToString(", ") { formatType(it) }}>"
            "${type.name.text}${targs}"
        }
        is NullableTypeNode -> "${formatType(type.inner)}${"?".repeat(type.depth)}"
        is ArrayTypeNode    -> "[${formatType(type.element)}]"
        is MatrixTypeNode   -> "[[${formatType(type.element)}]]"
        is MapTypeNode      -> "[${formatType(type.key)}: ${formatType(type.value)}]"
        is TupleTypeNode    -> {
            val fields = type.fields.joinToString(", ") { f ->
                val name = f.name?.let { "$it: " } ?: ""
                "$name${formatType(f.type)}"
            }
            "($fields)"
        }
        is FnTypeNode -> {
            val params = type.paramTypes.joinToString(", ") { formatType(it) }
            "(${params}) ${arrow()} ${formatType(type.returnType)}"
        }
        is PtrTypeNode -> "ptr<${formatType(type.inner)}>"
    }

    // ── Statements ────────────────────────────────────────────────────────

    private fun formatStmt(stmt: Stmt) {
        when (stmt) {
            is LetStmt -> {
                val weak = if (stmt.isWeak) "weak " else ""
                val binding = formatBinding(stmt.binding)
                val typeAnn = stmt.typeAnnotation?.let { ": ${formatType(it)}" } ?: ""
                val init = stmt.initializer?.let { " = ${formatExpr(it)}" } ?: ""
                appendIndented("${weak}let ${binding}${typeAnn}${init}")
            }
            is VarStmt -> {
                val weak = if (stmt.isWeak) "weak " else ""
                val binding = formatBinding(stmt.binding)
                val typeAnn = stmt.typeAnnotation?.let { ": ${formatType(it)}" } ?: ""
                val init = stmt.initializer?.let { " = ${formatExpr(it)}" } ?: ""
                appendIndented("${weak}var ${binding}${typeAnn}${init}")
            }
            is ReturnStmt -> {
                val value = stmt.value?.let { " ${formatExpr(it)}" } ?: ""
                appendIndented("${arrow()}${value}")
            }
            is IfStmt      -> formatIfStmt(stmt)
            is GuardLetStmt -> {
                val typeAnn = stmt.typeAnnotation?.let { ": ${formatType(it)}" } ?: ""
                appendIndented("guard let ${stmt.name}${typeAnn} = ${formatExpr(stmt.value)} else")
                indentLevel++
                for (s in stmt.elseBody) formatStmt(s)
                indentLevel--
            }
            is ForStmt -> {
                val label = stmt.label?.let { "@$it " } ?: ""
                appendIndented("${label}for ${formatBinding(stmt.binding)} in ${formatExpr(stmt.iterable)}")
                indentLevel++
                for (s in stmt.body) formatStmt(s)
                indentLevel--
            }
            is WhileStmt -> {
                val label = stmt.label?.let { "@$it " } ?: ""
                appendIndented("${label}while ${formatExpr(stmt.condition)}")
                indentLevel++
                for (s in stmt.body) formatStmt(s)
                indentLevel--
            }
            is MatchStmt -> formatMatchExprLines(stmt.expr)
            is DeferStmt -> {
                when (val body = stmt.body) {
                    is SingleStmtDefer -> appendIndented("defer ${formatStmtInline(body.stmt)}")
                    is BlockDefer -> {
                        appendIndented("defer")
                        indentLevel++
                        for (s in body.stmts) formatStmt(s)
                        indentLevel--
                    }
                }
            }
            is TryCatchStmt -> {
                appendIndented("try")
                indentLevel++
                for (s in stmt.tryBody) formatStmt(s)
                indentLevel--
                for (clause in stmt.catchClauses) {
                    val typeAnn = clause.type?.let { ": ${formatType(it)}" } ?: ""
                    appendIndented("catch ${clause.binding}${typeAnn}")
                    indentLevel++
                    for (s in clause.body) formatStmt(s)
                    indentLevel--
                }
                stmt.finallyBody?.let { fb ->
                    appendIndented("finally")
                    indentLevel++
                    for (s in fb) formatStmt(s)
                    indentLevel--
                }
            }
            is ThrowStmt  -> appendIndented("throw ${formatExpr(stmt.expr)}")
            is GoStmt -> {
                val caps = if (stmt.captureList.isEmpty()) "" else
                    "[${stmt.captureList.joinToString(", ") { if (it.isCopy) "copy ${it.name}" else it.name }}] "
                when (val body = stmt.body) {
                    is GoBlockBody -> {
                        appendIndented("go ${caps}")
                        indentLevel++
                        for (s in body.stmts) formatStmt(s)
                        indentLevel--
                    }
                    is GoExprBody -> appendIndented("go ${caps}${formatExpr(body.expr)}")
                }
            }
            is SpawnStmt  -> appendIndented("spawn ${formatExpr(stmt.expr)}")
            is SelectStmt -> {
                appendIndented("select")
                indentLevel++
                for (arm in stmt.arms) {
                    when (arm) {
                        is ReceiveSelectArm -> {
                            val binding = arm.binding?.let { "let $it = " } ?: ""
                            appendIndented("${binding}<- ${formatExpr(arm.channel)}")
                            indentLevel++
                            for (s in arm.body) formatStmt(s)
                            indentLevel--
                        }
                        is AfterSelectArm -> {
                            val dur = formatDuration(arm.duration)
                            appendIndented("after $dur")
                            indentLevel++
                            for (s in arm.body) formatStmt(s)
                            indentLevel--
                        }
                        is DefaultSelectArm -> {
                            appendIndented("default")
                            indentLevel++
                            for (s in arm.body) formatStmt(s)
                            indentLevel--
                        }
                    }
                }
                indentLevel--
            }
            is BreakStmt    -> appendIndented("break${stmt.label?.let { " @$it" } ?: ""}")
            is ContinueStmt -> appendIndented("continue${stmt.label?.let { " @$it" } ?: ""}")
            is YieldStmt    -> appendIndented("yield ${formatExpr(stmt.expr)}")
            is UnsafeBlock  -> {
                appendIndented("unsafe")
                indentLevel++
                for (s in stmt.stmts) formatStmt(s)
                indentLevel--
            }
            is AsmStmt -> {
                val feats = if (stmt.features.isEmpty()) "" else ", ${stmt.features.joinToString(", ")}"
                appendIndented("@asm[${stmt.arch}${feats}]")
                indentLevel++
                for (instr in stmt.instructions) appendIndented(instr)
                indentLevel--
                if (stmt.clobbers.isNotEmpty()) {
                    appendIndented("clobbers: ${stmt.clobbers.joinToString(", ")}")
                }
            }
            is BytesStmt -> {
                val hex = stmt.bytes.joinToString(", ") { "0x${it.toString(16).padStart(2, '0')}" }
                appendIndented("@bytes[${stmt.arch}] $hex")
            }
            is CBlockStmt -> {
                appendIndented("@c")
                indentLevel++
                for (line in stmt.lines) appendIndented(line)
                indentLevel--
            }
            is CppBlockStmt -> {
                appendIndented("@cpp")
                indentLevel++
                for (line in stmt.lines) appendIndented(line)
                indentLevel--
            }
            is AssignStmt -> {
                val op = formatAssignOp(stmt.op)
                appendIndented("${formatExpr(stmt.target)} $op ${formatExpr(stmt.value)}")
            }
            is ExprStmt -> appendIndented(formatExpr(stmt.expr))
        }
    }

    private fun formatStmtInline(stmt: Stmt): String {
        // For simple stmts that fit on one line; used by defer
        val sb2 = StringBuilder()
        val prev = sb
        // Temporarily capture
        return when (stmt) {
            is ReturnStmt -> "${arrow()}${stmt.value?.let { " ${formatExpr(it)}" } ?: ""}"
            is ExprStmt   -> formatExpr(stmt.expr)
            is BreakStmt  -> "break${stmt.label?.let { " @$it" } ?: ""}"
            is ContinueStmt -> "continue${stmt.label?.let { " @$it" } ?: ""}"
            is ThrowStmt  -> "throw ${formatExpr(stmt.expr)}"
            else -> { formatStmt(stmt); "" }
        }
    }

    private fun formatIfStmt(stmt: IfStmt) {
        val letBind = stmt.letBinding?.let {
            val typeAnn = it.typeAnnotation?.let { t -> ": ${formatType(t)}" } ?: ""
            "let ${it.name}${typeAnn} = "
        } ?: ""
        appendIndented("if ${letBind}${formatExpr(stmt.condition)}")
        indentLevel++
        for (s in stmt.thenBody) formatStmt(s)
        indentLevel--
        for (clause in stmt.elseIfClauses) {
            val clauseBind = clause.letBinding?.let {
                val typeAnn = it.typeAnnotation?.let { t -> ": ${formatType(t)}" } ?: ""
                "let ${it.name}${typeAnn} = "
            } ?: ""
            appendIndented("else if ${clauseBind}${formatExpr(clause.condition)}")
            indentLevel++
            for (s in clause.body) formatStmt(s)
            indentLevel--
        }
        stmt.elseBody?.let { eb ->
            appendIndented("else")
            indentLevel++
            for (s in eb) formatStmt(s)
            indentLevel--
        }
    }

    private fun formatMatchExprLines(expr: MatchExpr) {
        appendIndented("match ${formatExpr(expr.subject)}")
        indentLevel++
        for (arm in expr.arms) {
            val guard = arm.guard?.let { " if ${formatExpr(it)}" } ?: ""
            val pat = formatPattern(arm.pattern)
            when (val body = arm.body) {
                is ExprMatchArmBody  -> appendIndented("${pat}:${guard} ${arrow()} ${formatExpr(body.expr)}")
                is BlockMatchArmBody -> {
                    appendIndented("${pat}:${guard}")
                    indentLevel++
                    for (s in body.stmts) formatStmt(s)
                    indentLevel--
                }
            }
        }
        indentLevel--
    }

    private fun formatDuration(d: DurationLit): String {
        val unit = when (d.unit) {
            DurationUnit.MS -> "ms"
            DurationUnit.S  -> "s"
            DurationUnit.M  -> "m"
            DurationUnit.H  -> "h"
        }
        return "${d.amount}${unit}"
    }

    private fun formatAssignOp(op: AssignOp): String = when (op) {
        AssignOp.ASSIGN         -> "="
        AssignOp.PLUS_ASSIGN    -> "+="
        AssignOp.MINUS_ASSIGN   -> "-="
        AssignOp.STAR_ASSIGN    -> "*="
        AssignOp.SLASH_ASSIGN   -> "/="
        AssignOp.INT_DIV_ASSIGN -> "${intDiv()}="
        AssignOp.MOD_ASSIGN     -> "%="
        AssignOp.AMP_ASSIGN     -> "&="
        AssignOp.PIPE_ASSIGN    -> "|="
        AssignOp.XOR_ASSIGN     -> "⊕="
        AssignOp.LSHIFT_ASSIGN  -> "<<="
        AssignOp.RSHIFT_ASSIGN  -> ">>="
    }

    // ── Expressions ───────────────────────────────────────────────────────

    private fun formatExpr(expr: Expr): String = when (expr) {
        is IntLitExpr   -> expr.text
        is FloatLitExpr -> expr.text
        is BoolLitExpr  -> expr.value.toString()
        is NilExpr      -> "nil"
        is CharLitExpr  -> expr.text
        is RawStringExpr -> expr.text
        is ConstPiExpr  -> "π"
        is ConstInfExpr -> "∞"
        is ConstEExpr   -> "e"
        is IdentExpr    -> expr.name
        is WildcardExpr -> "_"
        is ParenExpr    -> "(${formatExpr(expr.inner)})"

        is InterpolatedStringExpr -> {
            val parts = expr.parts.joinToString("") { part ->
                when (part) {
                    is StringTextPart          -> part.text
                    is StringInterpolationPart -> {
                        val fmt = part.formatSpec?.let { ":$it" } ?: ""
                        "{${formatExpr(part.expr)}${fmt}}"
                    }
                }
            }
            "\"$parts\""
        }

        is BinaryExpr -> {
            val op = formatBinaryOp(expr.op)
            "${formatExprParenIfNeeded(expr.left, expr)} $op ${formatExprParenIfNeeded(expr.right, expr)}"
        }
        is UnaryExpr -> when (expr.op) {
            UnaryOp.NEGATE  -> "-${formatExprParenIfNeeded(expr.operand, expr)}"
            UnaryOp.NOT     -> "${not()}${formatExprParenIfNeeded(expr.operand, expr)}"
            UnaryOp.BIT_NOT -> "~${formatExprParenIfNeeded(expr.operand, expr)}"
        }

        is MemberAccessExpr -> "${formatExpr(expr.receiver)}.${expr.member}"
        is SafeNavExpr      -> "${formatExpr(expr.receiver)}?.${expr.member}"
        is CallExpr -> {
            val callee = formatExpr(expr.callee)
            val args = expr.args.joinToString(", ") { arg ->
                val name = arg.name?.let { "$it: " } ?: ""
                "$name${formatExpr(arg.expr)}"
            }
            val trailing = expr.trailingLambda?.let { " ${formatLambdaInline(it)}" } ?: ""
            "${callee}(${args})${trailing}"
        }
        is IndexExpr -> {
            val indices = expr.indices.joinToString(", ") { idx ->
                when {
                    idx.isStar -> "*"
                    idx.expr != null -> formatExpr(idx.expr)
                    else -> ""
                }
            }
            "${formatExpr(expr.receiver)}[${indices}]"
        }
        is ResultPropagateExpr -> "${formatExpr(expr.operand)}?"
        is ForceUnwrapExpr     -> "${formatExpr(expr.operand)}!"
        is TypeTestExpr  -> "${formatExpr(expr.operand)} is ${formatType(expr.type)}"
        is SafeCastExpr  -> "${formatExpr(expr.operand)} as? ${formatType(expr.type)}"
        is ForceCastExpr -> "${formatExpr(expr.operand)} as! ${formatType(expr.type)}"
        is AwaitExpr     -> "await ${formatExpr(expr.operand)}"
        is SpawnExpr     -> "spawn ${formatExpr(expr.expr)}"

        is InlineIfExpr -> {
            val sb2 = StringBuilder()
            sb2.append("if ${formatExpr(expr.condition)} then ${formatExpr(expr.thenExpr)}")
            for (c in expr.elseIfClauses) {
                sb2.append(" else if ${formatExpr(c.condition)} then ${formatExpr(c.thenExpr)}")
            }
            sb2.append(" else ${formatExpr(expr.elseExpr)}")
            sb2.toString()
        }

        is ArrayLiteralExpr -> "[${expr.elements.joinToString(", ") { formatExpr(it) }}]"
        is MapLiteralExpr   -> "[${expr.entries.joinToString(", ") { "${formatExpr(it.key)}: ${formatExpr(it.value)}" }}]"
        is EmptyMapExpr     -> "[:]"
        is TupleLiteralExpr -> "(${expr.elements.joinToString(", ") { formatExpr(it) }})"

        is RangeExpr -> {
            val (open, close) = when (expr.kind) {
                RangeKind.CLOSED         -> Pair("[", "]")
                RangeKind.HALF_OPEN_LEFT -> Pair("[", "[")
                RangeKind.OPEN           -> Pair("]", "[")
                RangeKind.HALF_OPEN_RIGHT -> Pair("]", "]")
            }
            "${open}${formatExpr(expr.start)}, ${formatExpr(expr.end)}${close}"
        }

        is ListComprehensionExpr -> {
            val gens = expr.generators.joinToString(", ") { g ->
                "${formatBinding(g.binding)} in ${formatExpr(g.iterable)}"
            }
            val guard = expr.guard?.let { " if ${formatExpr(it)}" } ?: ""
            "[${formatExpr(expr.body)} for ${gens}${guard}]"
        }

        is QuantifierExpr -> {
            val opStr = when (expr.op) {
                QuantifierOp.FORALL  -> if (asciiMode) "forall" else "∀"
                QuantifierOp.EXISTS  -> if (asciiMode) "exists" else "∃"
                QuantifierOp.SUM     -> if (asciiMode) "sum" else "∑"
                QuantifierOp.PRODUCT -> if (asciiMode) "product" else "∏"
            }
            val bindPart = if (expr.binding != null && expr.iterable != null) {
                " ${formatBinding(expr.binding)} ∈ ${formatExpr(expr.iterable)}:"
            } else ""
            val body = when (val b = expr.body) {
                is InlineQuantifierBody -> " ${formatExpr(b.expr)}"
                is BareIterableBody     -> " ${formatExpr(b.iterable)}"
                is BlockQuantifierBody  -> " { ... }" // inline repr
            }
            "${opStr}${bindPart}${body}"
        }

        is MatchExpr -> {
            // Inline match not typical but handled
            "match ${formatExpr(expr.subject)} { ... }"
        }

        is LambdaExpr -> formatLambdaInline(expr)
    }

    private fun formatLambdaInline(expr: LambdaExpr): String {
        val caps = if (expr.captureList.isEmpty()) "" else
            "[${expr.captureList.joinToString(", ") { if (it.isCopy) "copy ${it.name}" else it.name }}] "
        val params = when {
            expr.params.isEmpty() -> ""
            expr.params.size == 1 && expr.params[0].type == null -> "${expr.params[0].name} "
            else -> {
                val ps = expr.params.joinToString(", ") { p ->
                    val t = p.type?.let { ": ${formatType(it)}" } ?: ""
                    "${p.name}${t}"
                }
                "(${ps}) "
            }
        }
        return when (val body = expr.body) {
            is ExprLambdaBody  -> "${caps}${params}${arrow()} ${formatExpr(body.expr)}"
            is BlockLambdaBody -> "${caps}${params}{ ... }"
        }
    }

    private fun formatExprParenIfNeeded(expr: Expr, parent: Expr): String {
        // Add parens around binary expressions when nested in certain contexts
        return if (expr is BinaryExpr && parent is BinaryExpr) {
            if (binaryOpPrecedence(expr.op) < binaryOpPrecedence(parent.op)) {
                "(${formatExpr(expr)})"
            } else {
                formatExpr(expr)
            }
        } else {
            formatExpr(expr)
        }
    }

    private fun binaryOpPrecedence(op: BinaryOp): Int = when (op) {
        BinaryOp.OR            -> 1
        BinaryOp.AND           -> 2
        BinaryOp.EQ, BinaryOp.NEQ, BinaryOp.LT, BinaryOp.GT,
        BinaryOp.LEQ, BinaryOp.GEQ -> 3
        BinaryOp.NULL_COALESCE -> 4
        BinaryOp.BIT_OR        -> 5
        BinaryOp.BIT_XOR       -> 6
        BinaryOp.BIT_AND       -> 7
        BinaryOp.LSHIFT, BinaryOp.RSHIFT -> 8
        BinaryOp.PLUS, BinaryOp.MINUS -> 9
        BinaryOp.STAR, BinaryOp.SLASH, BinaryOp.INT_DIV, BinaryOp.MOD -> 10
        BinaryOp.POWER         -> 11
        BinaryOp.PIPELINE      -> 0
    }

    private fun formatBinaryOp(op: BinaryOp): String = when (op) {
        BinaryOp.PLUS     -> "+"
        BinaryOp.MINUS    -> "-"
        BinaryOp.STAR     -> "*"
        BinaryOp.SLASH    -> "/"
        BinaryOp.INT_DIV  -> intDiv()
        BinaryOp.MOD      -> "%"
        BinaryOp.POWER    -> "^"
        BinaryOp.BIT_AND  -> "&"
        BinaryOp.BIT_OR   -> "|"
        BinaryOp.BIT_XOR  -> "⊕"
        BinaryOp.LSHIFT   -> "<<"
        BinaryOp.RSHIFT   -> ">>"
        BinaryOp.AND      -> and()
        BinaryOp.OR       -> or()
        BinaryOp.EQ       -> "=="
        BinaryOp.NEQ      -> neq()
        BinaryOp.LT       -> "<"
        BinaryOp.GT       -> ">"
        BinaryOp.LEQ      -> leq()
        BinaryOp.GEQ      -> geq()
        BinaryOp.PIPELINE -> "|>"
        BinaryOp.NULL_COALESCE -> "??"
    }

    // ── Patterns ─────────────────────────────────────────────────────────

    private fun formatPattern(pattern: Pattern): String = when (pattern) {
        is OrPattern      -> pattern.alternatives.joinToString(" | ") { formatPattern(it) }
        is LiteralPattern -> formatExpr(pattern.expr)
        is RangePattern   -> formatExpr(pattern.range)
        is NilPattern     -> "nil"
        is WildcardPattern -> "_"
        is BindingPattern -> pattern.name
        is TypePattern -> {
            val args = when (val a = pattern.args) {
                is PositionalTypePatternArgs -> if (a.patterns.isEmpty()) "" else "(${a.patterns.joinToString(", ") { formatPattern(it) }})"
                is NamedTypePatternArgs      -> if (a.fields.isEmpty()) "" else "(${a.fields.joinToString(", ") { "${it.name}: ${formatPattern(it.pattern)}" }})"
                is NoTypePatternArgs         -> ""
            }
            "${pattern.typeName.text}${args}"
        }
        is TuplePattern -> "(${pattern.elements.joinToString(", ") { formatPattern(it) }})"
    }

    // ── Bindings ─────────────────────────────────────────────────────────

    private fun formatBinding(binding: Binding): String = when (binding) {
        is IdentBinding -> binding.name
        is TupleBinding -> "(${binding.names.joinToString(", ")})"
    }
}
