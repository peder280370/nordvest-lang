package nv.compiler.parser

import nv.compiler.lexer.SourceSpan
import nv.compiler.lexer.Token
import nv.compiler.lexer.TokenKind
import nv.compiler.lexer.TokenKind.*

class Parser(private val tokens: List<Token>, private val sourcePath: String) {

    private var pos: Int = 0
    private val errors: MutableList<ParseError> = mutableListOf()

    companion object {
        private val DECL_STARTERS = setOf(
            FN, CLASS, STRUCT, RECORD, INTERFACE, SEALED, ENUM, EXTEND,
            TYPE, LET, VAR, PUB, AT, WEAK, MODULE, IMPORT,
        )
    }

    // ── Cursor primitives ─────────────────────────────────────────────────

    private fun peek(offset: Int = 0): Token {
        val idx = pos + offset
        return if (idx < tokens.size) tokens[idx] else tokens.last() // last is EOF
    }

    private fun at(kind: TokenKind, offset: Int = 0): Boolean = peek(offset).kind == kind

    private fun advance(): Token {
        val t = peek()
        if (pos < tokens.size - 1) pos++
        return t
    }

    private fun expect(kind: TokenKind): Token {
        val t = peek()
        return if (t.kind == kind) {
            advance()
        } else {
            val expected = kind.name.lowercase().replace('_', ' ')
            errors += if (t.kind == EOF)
                ParseError.UnexpectedEof(expected, t.span)
            else
                ParseError.UnexpectedToken(t.kind, t.text, expected, t.span)
            Token(kind, "", SourceSpan.SYNTHETIC)
        }
    }

    private fun match(vararg kinds: TokenKind): Token? {
        return if (peek().kind in kinds) advance() else null
    }

    private fun expectNewline() {
        // Allow optional trailing semicolons or actual NEWLINEs
        if (at(SEMICOLON)) { advance() }
        else if (at(NEWLINE)) { advance() }
        else if (at(DEDENT) || at(EOF)) { /* acceptable */ }
        else {
            errors += ParseError.UnexpectedToken(peek().kind, peek().text, "newline", peek().span)
        }
    }

    /**
     * Like [expectNewline] but skips the check when [expr] is a [BuilderCallExpr].
     * A builder block already consumed the line terminator (the NEWLINE before its INDENT),
     * so no additional newline token remains after the DEDENT.
     */
    private fun expectNewlineAfter(expr: Expr?) {
        if (expr is BuilderCallExpr) return
        expectNewline()
    }

    private fun expectIndent(context: String = "block") {
        if (!at(INDENT)) {
            errors += ParseError.InvalidIndent(context, peek().span)
        } else {
            advance()
        }
    }

    private fun expectDedent() { match(DEDENT) }

    private fun spanFrom(start: SourceSpan): SourceSpan {
        val end = if (pos > 0) tokens[pos - 1].span else peek().span
        return SourceSpan(start.start, end.end)
    }

    private fun currentSpan(): SourceSpan = peek().span

    // ── Error recovery ────────────────────────────────────────────────────

    private fun syncToStmtBoundary() {
        while (!at(NEWLINE) && !at(DEDENT) && !at(EOF)) advance()
        if (at(NEWLINE)) advance()
    }

    private fun syncToDeclBoundary() {
        while (!at(EOF)) {
            val k = peek().kind
            // DEDENT is structural — stop without consuming it so the enclosing scope can terminate.
            if (k == DEDENT) break
            if (k == INDENT || k == NEWLINE) { advance(); continue }
            if (k in DECL_STARTERS) break
            advance()
        }
    }

    // ── Lambda lookahead helpers ──────────────────────────────────────────

    private fun findMatchingParen(fromPos: Int): Int {
        var depth = 0
        var i = fromPos
        while (i < tokens.size) {
            when (tokens[i].kind) {
                LPAREN -> depth++
                RPAREN -> {
                    depth--
                    if (depth == 0) return i
                }
                EOF -> return -1
                else -> {}
            }
            i++
        }
        return -1
    }

    private fun isLambdaStart(): Boolean {
        // Single param: IDENT ARROW
        if (at(IDENT) && at(ARROW, 1)) return true
        // Multi-param: ( ... ) ARROW
        if (at(LPAREN)) {
            val closeIdx = findMatchingParen(pos)
            if (closeIdx != -1 && closeIdx + 1 < tokens.size && tokens[closeIdx + 1].kind == ARROW) return true
        }
        return false
    }

    // ── Lookahead for if-expr vs if-stmt ─────────────────────────────────

    private fun hasInlineIfThen(): Boolean {
        var i = pos
        var depth = 0
        while (i < tokens.size) {
            when (tokens[i].kind) {
                THEN -> if (depth == 0) return true
                NEWLINE, INDENT, EOF -> return false
                LPAREN, LBRACKET, LBRACE -> depth++
                RPAREN, RBRACKET, RBRACE -> depth--
                else -> {}
            }
            i++
        }
        return false
    }

    // ── Entry point ───────────────────────────────────────────────────────

    fun parse(): ParseResult {
        return try {
            val file = parseFile()
            if (errors.isEmpty()) ParseResult.Success(file)
            else ParseResult.Recovered(file, errors.toList())
        } catch (e: FatalParseError) {
            ParseResult.Failure(errors.toList())
        }
    }

    private class FatalParseError : Exception()

    private fun parseFile(): SourceFile {
        val start = peek().span
        // Skip leading newlines
        while (at(NEWLINE)) advance()
        val module = if (at(MODULE)) parseModuleDecl() else null
        while (at(NEWLINE)) advance()
        val imports = mutableListOf<ImportDecl>()
        while (at(IMPORT)) {
            imports += parseImportDecl()
            while (at(NEWLINE)) advance()
        }
        val decls = mutableListOf<Decl>()
        while (!at(EOF)) {
            while (at(NEWLINE) || at(DEDENT)) advance()
            if (at(EOF)) break
            val decl = tryParseTopLevelDecl(docComment = null)
            if (decl != null) decls += decl
            else {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "declaration", peek().span)
                syncToDeclBoundary()
            }
        }
        return SourceFile(spanFrom(start), sourcePath, module, imports, decls)
    }

    private fun parseModuleDecl(): ModuleDecl {
        val start = peek().span
        expect(MODULE)
        val name = parseQualifiedName()
        expectNewline()
        return ModuleDecl(spanFrom(start), name)
    }

    private fun parseImportDecl(): ImportDecl {
        val start = peek().span
        expect(IMPORT)
        val isPub = match(PUB) != null
        val name = parseQualifiedName()
        val alias = if (match(AS) != null) expect(IDENT).text else null
        expectNewline()
        return ImportDecl(spanFrom(start), isPub, name, alias)
    }

    // ── Top-level declarations ─────────────────────────────────────────────

    private fun tryParseTopLevelDecl(docComment: String?): Decl? {
        // Collect doc comment
        var doc = docComment
        if (at(DOC_COMMENT)) {
            doc = advance().text
        }

        // Collect annotations
        val annotations = mutableListOf<Annotation>()
        while (at(AT)) {
            // @if / @else must be detected before parseAnnotation() consumes their args
            if (at(IF, 1)) {
                val atSpan = peek().span
                advance() // consume @
                advance() // consume if
                return parseConditionalCompilationBlock(atSpan)
            }
            val ann = parseAnnotation()
            annotations += ann
            while (at(NEWLINE)) advance()
        }

        return tryParseDecl(doc, annotations)
    }

    private fun tryParseDecl(docComment: String?, annotations: List<Annotation>): Decl? {
        val vis = parseVisibility()

        return when {
            at(FN) -> parseFunctionOrPropertyOrSubscriptDecl(docComment, annotations, vis, isAsync = false)
            at(ASYNC) && at(FN, 1) -> parseFunctionOrPropertyOrSubscriptDecl(docComment, annotations, vis, isAsync = true)
            at(CLASS) -> parseClassDecl(docComment, annotations, vis)
            at(STRUCT) -> parseStructDecl(docComment, annotations, vis)
            at(RECORD) -> parseRecordDecl(docComment, annotations, vis)
            at(INTERFACE) -> parseInterfaceDecl(docComment, annotations, vis)
            at(SEALED) -> parseSealedClassDecl(docComment, annotations, vis)
            at(ENUM) -> parseEnumDecl(docComment, annotations, vis)
            at(EXTEND) -> parseExtendDecl(annotations)
            at(TYPE) -> parseTypeAliasDecl(annotations, vis)
            at(LET) || (at(WEAK) && at(LET, 1)) -> parseLetDecl(annotations, vis)
            at(VAR) || (at(WEAK) && at(VAR, 1)) -> parseVarDecl(annotations, vis)
            at(AT) -> {
                val ann = parseAnnotation()
                val moreAnnotations = annotations + ann
                while (at(NEWLINE)) advance()
                tryParseDecl(docComment, moreAnnotations)
            }
            else -> null
        }
    }

    private fun parseVisibility(): Visibility {
        return when {
            at(PUB) && at(LPAREN, 1) -> {
                // pub(pkg)
                advance() // pub
                advance() // (
                expect(PKG)
                expect(RPAREN)
                Visibility.PACKAGE
            }
            at(PUB) -> {
                advance()
                Visibility.PUBLIC
            }
            else -> Visibility.FILE_PRIVATE
        }
    }

    // ── Annotation parsing ─────────────────────────────────────────────────

    private fun parseAnnotation(): Annotation {
        val start = peek().span
        expect(AT)
        val name = when {
            at(IDENT) -> advance().text
            at(IF) -> { advance(); "if" }
            at(ELSE) -> { advance(); "else" }
            else -> expect(IDENT).text
        }
        val args = mutableListOf<AnnotationArg>()
        if (match(LPAREN) != null) {
            if (!at(RPAREN)) {
                args += parseAnnotationArg()
                while (match(COMMA) != null) {
                    if (at(RPAREN)) break
                    args += parseAnnotationArg()
                }
            }
            expect(RPAREN)
        }
        return Annotation(spanFrom(start), name, args)
    }

    private fun parseAnnotationArg(): AnnotationArg {
        val start = peek().span
        // Named: ident: value — also allow keywords like 'fn', 'lib', 'cxx', etc. as arg names
        val isKeywordArgName = at(COLON, 1) && peek().kind != IDENT
        if ((at(IDENT) || isKeywordArgName) && at(COLON, 1)) {
            val name = advance().text
            advance() // colon
            val value = parseAnnotationArgValue()
            return AnnotationArg(name, value, spanFrom(start))
        }
        val value = parseAnnotationArgValue()
        return AnnotationArg(null, value, spanFrom(start))
    }

    private fun parseAnnotationArgValue(): AnnotationArgValue {
        val start = peek().span
        return when {
            at(STR_START) -> {
                val str = parseInterpolatedString()
                val text = (str as? InterpolatedStringExpr)?.parts
                    ?.filterIsInstance<StringTextPart>()
                    ?.joinToString("") { it.text } ?: ""
                AnnotationStrValue(spanFrom(start), text)
            }
            at(TRUE) -> { advance(); AnnotationBoolValue(spanFrom(start), true) }
            at(FALSE) -> { advance(); AnnotationBoolValue(spanFrom(start), false) }
            at(INT_LIT) -> { val t = advance(); AnnotationIntValue(spanFrom(start), t.text) }
            at(IDENT) -> {
                val parts = mutableListOf(advance().text)
                while (at(DOT) && at(IDENT, 1)) {
                    advance() // dot
                    parts += advance().text
                }
                val qn = QualifiedName(spanFrom(start), parts)
                AnnotationIdentValue(spanFrom(start), qn)
            }
            else -> {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "annotation argument value", peek().span)
                AnnotationStrValue(spanFrom(start), "")
            }
        }
    }

    // ── Operator name helper ───────────────────────────────────────────────

    /**
     * If the current token is a binary operator symbol (+, -, *, /, %, ^, ==, !=, <, >, <=, >=, &, |),
     * consume it and return the symbol string.  Otherwise return null.
     * Used to allow operator overloading declarations: fn +(other: T) → T
     */
    private fun tryParseOperatorName(): String? {
        val sym = when {
            at(PLUS)  -> "+"
            at(MINUS) -> "-"
            at(STAR)  -> "*"
            at(SLASH) -> "/"
            at(MOD)   -> "%"
            at(POWER) -> "^"
            at(EQ)    -> "=="
            at(NEQ)   -> "!="
            at(LT)    -> "<"
            at(GT)    -> ">"
            at(LEQ)   -> "<="
            at(GEQ)   -> ">="
            at(AMP)     -> "&"
            at(PIPE)    -> "|"
            at(XOR_OP)  -> "⊕"
            else        -> null
        } ?: return null
        advance()
        return sym
    }

    // ── Function / property / subscript dispatch ───────────────────────────

    private fun parseFunctionOrPropertyOrSubscriptDecl(
        docComment: String?,
        annotations: List<Annotation>,
        vis: Visibility,
        isAsync: Boolean = false,
    ): Decl {
        val start = peek().span
        if (isAsync) expect(ASYNC)
        expect(FN)

        // Subscript: fn [ ... ]
        if (at(LBRACKET)) {
            return parseSubscriptBody(start, annotations, vis)
        }

        // Operator overloading: fn +(other: T) → T  inside class/struct body
        val name = tryParseOperatorName() ?: expect(IDENT).text
        val typeParams = if (at(LT)) parseTypeParams() else emptyList()

        return when {
            // Computed property: fn name → Type
            at(ARROW) -> parseComputedPropertyBody(start, docComment, annotations, vis, name)
            // Function: fn name(...)
            at(LPAREN) -> parseFunctionBody(start, docComment, annotations, vis, isAsync, name, typeParams)
            // No parens and no arrow - treat as function signature with empty params (interface member)
            else -> parseFunctionBody(start, docComment, annotations, vis, isAsync, name, typeParams)
        }
    }

    private fun parseFunctionBody(
        start: SourceSpan,
        docComment: String?,
        annotations: List<Annotation>,
        vis: Visibility,
        isAsync: Boolean,
        name: String,
        typeParams: List<TypeParam>,
    ): Decl {
        val params = if (at(LPAREN)) {
            advance() // (
            val ps = if (!at(RPAREN)) parseParamList() else emptyList()
            expect(RPAREN)
            ps
        } else emptyList()

        // Accept both orderings: "→ T throws E" and "throws E → T"
        var returnType: TypeNode? = null
        var throwsType: TypeNode? = null
        if (at(THROWS)) { advance(); throwsType = parseType() }
        if (at(ARROW)) { advance(); returnType = parseType() }
        if (at(THROWS) && throwsType == null) { advance(); throwsType = parseType() }

        // Consume the trailing newline (present on both signatures and functions with bodies)
        if (at(NEWLINE) || at(SEMICOLON)) expectNewline()
        else if (!at(EOF) && !at(INDENT)) {
            // unexpected token after fn params - treat as signature
            return FunctionSignatureDecl(spanFrom(start), annotations, vis, isAsync, name, typeParams, params, returnType, throwsType)
        }

        // If INDENT follows, there is a body; otherwise it's a bare signature (e.g. interface member)
        if (!at(INDENT)) {
            return FunctionSignatureDecl(spanFrom(start), annotations, vis, isAsync, name, typeParams, params, returnType, throwsType)
        }
        expectIndent("fn $name")
        val body = parseStmtList()
        expectDedent()

        return FunctionDecl(spanFrom(start), docComment, annotations, vis, isAsync, name, typeParams, params, returnType, throwsType, body)
    }

    private fun parseComputedPropertyBody(
        start: SourceSpan,
        docComment: String?,
        annotations: List<Annotation>,
        vis: Visibility,
        name: String,
    ): Decl {
        expect(ARROW)
        val returnType = parseType()
        expectNewline()
        expectIndent("computed property $name")

        val getter: List<Stmt>
        val setter: SetterBlock?

        if (at(GET)) {
            // Explicit get/set blocks
            advance() // get
            expectNewline()
            expectIndent("get")
            getter = parseStmtList()
            expectDedent()
            setter = if (at(SET)) {
                val setStart = peek().span
                advance() // set
                expect(LPAREN)
                val paramName = expect(IDENT).text
                expect(RPAREN)
                expectNewline()
                expectIndent("set")
                val setBody = parseStmtList()
                expectDedent()
                SetterBlock(spanFrom(setStart), paramName, setBody)
            } else null
        } else {
            // Implicit getter (body is the getter)
            getter = parseStmtList()
            setter = null
        }

        expectDedent()
        return ComputedPropertyDecl(spanFrom(start), annotations, vis, name, returnType, getter, setter)
    }

    private fun parseSubscriptBody(
        start: SourceSpan,
        annotations: List<Annotation>,
        vis: Visibility,
    ): Decl {
        expect(LBRACKET)
        val params = parseParamList()
        expect(RBRACKET)

        // Check if setter: fn [...] = (value: Type)
        val isSetter = at(ASSIGN)
        var setterValueParam: Param? = null
        if (isSetter) {
            advance() // =
            expect(LPAREN)
            val pStart = peek().span
            val pName = expect(IDENT).text
            expect(COLON)
            val pType = parseType()
            expect(RPAREN)
            setterValueParam = Param(pName, pType, null, false, spanFrom(pStart))
        }

        val returnType = if (!isSetter && at(ARROW)) {
            advance()
            parseType()
        } else null

        expectNewline()
        expectIndent("subscript")
        val body = parseStmtList()
        expectDedent()

        return SubscriptDecl(spanFrom(start), annotations, vis, params, returnType, isSetter, setterValueParam, body)
    }

    // ── Class member parsing ───────────────────────────────────────────────

    private fun parseClassMembers(): List<Decl> {
        val members = mutableListOf<Decl>()
        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            val member = tryParseClassMember()
            if (member != null) {
                members += member
            } else {
                if (at(NEWLINE)) { advance(); continue }
                if (at(DEDENT) || at(EOF)) break
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "class member", peek().span)
                syncToDeclBoundary()
            }
        }
        return members
    }

    private fun tryParseClassMember(): Decl? {
        var doc: String? = null
        if (at(DOC_COMMENT)) doc = advance().text

        val annotations = mutableListOf<Annotation>()
        while (at(AT)) {
            annotations += parseAnnotation()
            while (at(NEWLINE)) advance()
        }

        val vis = parseVisibility()

        return when {
            at(FN) -> parseFunctionOrPropertyOrSubscriptDecl(doc, annotations, vis, isAsync = false)
            at(ASYNC) && at(FN, 1) -> parseFunctionOrPropertyOrSubscriptDecl(doc, annotations, vis, isAsync = true)
            at(INIT) -> parseInitBlock()
            at(TYPE) -> parseAssocTypeDecl()
            at(LET) || (at(WEAK) && at(LET, 1)) || (at(UNOWNED) && at(LET, 1)) -> parseFieldDecl(annotations, vis, isMutable = false)
            at(VAR) || (at(WEAK) && at(VAR, 1)) || (at(UNOWNED) && at(VAR, 1)) -> parseFieldDecl(annotations, vis, isMutable = true)
            at(NEWLINE) -> { advance(); null }
            else -> null
        }
    }

    private fun parseInitBlock(): Decl {
        val start = peek().span
        expect(INIT)
        expectNewline()
        expectIndent("init")
        val body = parseStmtList()
        expectDedent()
        return InitBlock(spanFrom(start), body)
    }

    private fun parseAssocTypeDecl(): Decl {
        val start = peek().span
        expect(TYPE)
        val name = expect(IDENT).text
        val bound = if (at(COLON)) {
            advance()
            parseType()
        } else null
        expectNewline()
        return AssocTypeDecl(spanFrom(start), name, bound)
    }

    private fun parseFieldDecl(annotations: List<Annotation>, vis: Visibility, isMutable: Boolean): Decl {
        val start = peek().span
        val isWeak = match(WEAK) != null
        val isUnowned = if (!isWeak) match(UNOWNED) != null else false
        advance() // let or var
        val name = expect(IDENT).text
        expect(COLON)
        val type = parseType()
        val init = if (match(ASSIGN) != null) parseExpr() else null
        expectNewline()
        return FieldDecl(spanFrom(start), annotations, vis, isWeak, isUnowned, isMutable, name, type, init)
    }

    // ── Class/Struct/Record/Interface/Sealed/Enum declarations ────────────

    private fun parseClassDecl(docComment: String?, annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        expect(CLASS)
        val name = expect(IDENT).text
        val typeParams = if (at(LT)) parseTypeParams() else emptyList()
        val ctorParams = if (at(LPAREN)) {
            advance()
            val ps = if (!at(RPAREN)) parseConstructorParamList() else emptyList()
            expect(RPAREN)
            ps
        } else emptyList()
        val superTypes = mutableListOf<TypeNode>()
        val delegations = mutableListOf<Pair<TypeNode, Expr>>()
        if (at(COLON)) {
            advance()
            val firstType = parseNamedType()
            superTypes += firstType
            if (at(BY)) {
                advance()
                delegations += firstType to parseExpr()
            }
            while (match(COMMA) != null) {
                val t = parseNamedType()
                superTypes += t
                if (at(BY)) {
                    advance()
                    delegations += t to parseExpr()
                }
            }
        }
        val whereClause = if (at(WHERE)) parseWhereClause() else emptyList()
        expectNewline()
        val members = if (at(INDENT)) {
            advance()
            val ms = parseClassMembers()
            expectDedent()
            ms
        } else emptyList()
        return ClassDecl(spanFrom(start), docComment, annotations, vis, name, typeParams, ctorParams, superTypes, whereClause, members, delegations)
    }

    private fun parseStructDecl(docComment: String?, annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        expect(STRUCT)
        val name = expect(IDENT).text
        val typeParams = if (at(LT)) parseTypeParams() else emptyList()
        val ctorParams = if (at(LPAREN)) {
            advance()
            val ps = if (!at(RPAREN)) parseConstructorParamList() else emptyList()
            expect(RPAREN)
            ps
        } else emptyList()
        val superTypes = if (at(COLON)) {
            advance()
            parseTypeList()
        } else emptyList()
        val whereClause = if (at(WHERE)) parseWhereClause() else emptyList()
        expectNewline()
        val members = if (at(INDENT)) {
            advance()
            val ms = parseClassMembers()
            expectDedent()
            ms
        } else emptyList()
        return StructDecl(spanFrom(start), docComment, annotations, vis, name, typeParams, ctorParams, superTypes, whereClause, members)
    }

    private fun parseRecordDecl(docComment: String?, annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        expect(RECORD)
        val name = expect(IDENT).text
        val typeParams = if (at(LT)) parseTypeParams() else emptyList()
        val ctorParams = if (at(LPAREN)) {
            advance()
            val ps = if (!at(RPAREN)) parseConstructorParamList() else emptyList()
            expect(RPAREN)
            ps
        } else emptyList()
        val superTypes = if (at(COLON)) {
            advance()
            parseTypeList()
        } else emptyList()
        val whereClause = if (at(WHERE)) parseWhereClause() else emptyList()
        expectNewline()
        val members = if (at(INDENT)) {
            advance()
            val ms = parseClassMembers()
            expectDedent()
            ms
        } else emptyList()
        return RecordDecl(spanFrom(start), docComment, annotations, vis, name, typeParams, ctorParams, superTypes, whereClause, members)
    }

    private fun parseInterfaceDecl(docComment: String?, annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        expect(INTERFACE)
        val name = expect(IDENT).text
        val typeParams = if (at(LT)) parseTypeParams() else emptyList()
        val superTypes = if (at(COLON)) {
            advance()
            parseTypeList()
        } else emptyList()
        val whereClause = if (at(WHERE)) parseWhereClause() else emptyList()
        expectNewline()
        expectIndent("interface $name")
        val members = parseClassMembers()
        expectDedent()
        return InterfaceDecl(spanFrom(start), docComment, annotations, vis, name, typeParams, superTypes, whereClause, members)
    }

    private fun parseSealedClassDecl(docComment: String?, annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        expect(SEALED)
        expect(CLASS)
        val name = expect(IDENT).text
        val typeParams = if (at(LT)) parseTypeParams() else emptyList()
        val superTypes = if (at(COLON)) {
            advance()
            parseTypeList()
        } else emptyList()
        expectNewline()
        expectIndent("sealed class $name")
        val variants = mutableListOf<SealedVariant>()
        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            variants += parseSealedVariant()
        }
        expectDedent()
        return SealedClassDecl(spanFrom(start), docComment, annotations, vis, name, typeParams, superTypes, variants)
    }

    private fun parseSealedVariant(): SealedVariant {
        val start = peek().span
        val name = expect(IDENT).text
        val params = if (at(LPAREN)) {
            advance()
            val ps = if (!at(RPAREN)) parseConstructorParamList() else emptyList()
            expect(RPAREN)
            ps
        } else emptyList()
        expectNewline()
        return SealedVariant(spanFrom(start), name, params)
    }

    private fun parseEnumDecl(docComment: String?, annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        expect(ENUM)
        val name = expect(IDENT).text
        val rawType = if (at(COLON)) {
            advance()
            parseType()
        } else null
        expectNewline()
        expectIndent("enum $name")
        val cases = mutableListOf<EnumCase>()
        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            cases += parseEnumCase()
        }
        expectDedent()
        return EnumDecl(spanFrom(start), docComment, annotations, vis, name, rawType, cases)
    }

    private fun parseEnumCase(): EnumCase {
        val start = peek().span
        val name = expect(IDENT).text
        val rawValue = if (match(ASSIGN) != null) parseExpr() else null
        val assocParams = if (at(LPAREN)) {
            advance()
            val ps = if (!at(RPAREN)) parseConstructorParamList() else emptyList()
            expect(RPAREN)
            ps
        } else emptyList()
        expectNewline()
        return EnumCase(spanFrom(start), name, rawValue, assocParams)
    }

    private fun parseExtendDecl(annotations: List<Annotation>): Decl {
        val start = peek().span
        expect(EXTEND)
        val target = parseNamedType()
        val conformances = if (at(COLON)) {
            advance()
            parseTypeList()
        } else emptyList()
        val whereClause = if (at(WHERE)) parseWhereClause() else emptyList()
        expectNewline()
        expectIndent("extend ${target.name.text}")
        val members = parseClassMembers()
        expectDedent()
        return ExtendDecl(spanFrom(start), target, conformances, whereClause, members)
    }

    private fun parseTypeAliasDecl(annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        expect(TYPE)
        val name = expect(IDENT).text
        val typeParams = if (at(LT)) parseTypeParams() else emptyList()
        expect(ASSIGN)
        val aliasedType = parseType()
        expectNewline()
        return TypeAliasDecl(spanFrom(start), annotations, vis, name, typeParams, aliasedType)
    }

    private fun parseLetDecl(annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        val isWeak = match(WEAK) != null
        expect(LET)
        val name = expect(IDENT).text
        val typeAnnotation = if (at(COLON)) {
            advance()
            parseType()
        } else null
        val initializer = if (match(ASSIGN) != null) parseExpr() else null
        expectNewline()
        return LetDecl(spanFrom(start), annotations, vis, isWeak, name, typeAnnotation, initializer)
    }

    private fun parseVarDecl(annotations: List<Annotation>, vis: Visibility): Decl {
        val start = peek().span
        val isWeak = match(WEAK) != null
        expect(VAR)
        val name = expect(IDENT).text
        val typeAnnotation = if (at(COLON)) {
            advance()
            parseType()
        } else null
        val initializer = if (match(ASSIGN) != null) parseExpr() else null
        expectNewline()
        return VarDecl(spanFrom(start), annotations, vis, isWeak, name, typeAnnotation, initializer)
    }

    private fun parseConditionalCompilationBlock(atSpan: SourceSpan): Decl {
        val start = atSpan
        // @if(pred)
        expect(LPAREN)
        val pred = parseCCPred()
        expect(RPAREN)
        expectNewline()
        expectIndent("@if")
        val thenDecls = mutableListOf<Decl>()
        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            val d = tryParseTopLevelDecl(null)
            if (d != null) thenDecls += d else {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "declaration", peek().span)
                syncToDeclBoundary()
            }
        }
        expectDedent()
        val elseDecls = mutableListOf<Decl>()
        if (at(AT)) {
            val savedPos = pos
            advance() // @
            if (at(ELSE)) {
                advance() // else
                expectNewline()
                expectIndent("@else")
                while (!at(DEDENT) && !at(EOF)) {
                    while (at(NEWLINE)) advance()
                    if (at(DEDENT) || at(EOF)) break
                    val d = tryParseTopLevelDecl(null)
                    if (d != null) elseDecls += d else {
                        errors += ParseError.UnexpectedToken(peek().kind, peek().text, "declaration", peek().span)
                        syncToDeclBoundary()
                    }
                }
                expectDedent()
            } else {
                pos = savedPos
            }
        }
        return ConditionalCompilationBlock(spanFrom(start), pred, thenDecls, elseDecls)
    }

    private fun parseCCPred(): CCPredicate {
        val start = peek().span
        return when {
            at(IDENT) && peek().text == "platform" -> {
                advance()
                expect(EQ)
                val strVal = parseStringLiteralText()
                CCPlatform(spanFrom(start), strVal)
            }
            at(IDENT) && peek().text == "arch" -> {
                advance()
                expect(EQ)
                val strVal = parseStringLiteralText()
                CCArch(spanFrom(start), strVal)
            }
            at(IDENT) && peek().text == "debug" -> {
                advance()
                CCDebug(spanFrom(start))
            }
            at(IDENT) && peek().text == "release" -> {
                advance()
                CCRelease(spanFrom(start))
            }
            at(IDENT) && peek().text == "feature" -> {
                advance()
                expect(LPAREN)
                val feat = parseStringLiteralText()
                expect(RPAREN)
                CCFeature(spanFrom(start), feat)
            }
            else -> {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "conditional compilation predicate", peek().span)
                CCDebug(spanFrom(start))
            }
        }
    }

    private fun parseStringLiteralText(): String {
        if (!at(STR_START)) {
            errors += ParseError.UnexpectedToken(peek().kind, peek().text, "string literal", peek().span)
            return ""
        }
        advance() // STR_START
        val sb = StringBuilder()
        while (at(STR_TEXT)) sb.append(advance().text)
        expect(STR_END)
        return sb.toString()
    }

    // ── Type parameters / where clause ────────────────────────────────────

    private fun parseTypeParams(): List<TypeParam> {
        expect(LT)
        val params = mutableListOf<TypeParam>()
        params += parseTypeParam()
        while (match(COMMA) != null) {
            if (at(GT)) break
            params += parseTypeParam()
        }
        expect(GT)
        return params
    }

    private fun parseTypeParam(): TypeParam {
        val start = peek().span
        val name = expect(IDENT).text
        val bound = if (at(COLON)) {
            advance()
            parseType()
        } else null
        return TypeParam(name, bound, spanFrom(start))
    }

    private fun parseWhereClause(): List<WhereConstraint> {
        expect(WHERE)
        val constraints = mutableListOf<WhereConstraint>()
        constraints += parseWhereConstraint()
        while (match(COMMA) != null) {
            constraints += parseWhereConstraint()
        }
        return constraints
    }

    private fun parseWhereConstraint(): WhereConstraint {
        val start = peek().span
        val name = parseQualifiedName()
        expect(COLON)
        val bound = parseType()
        return WhereConstraint(name, bound, spanFrom(start))
    }

    // ── Parameter lists ───────────────────────────────────────────────────

    private fun parseParamList(): List<Param> {
        val params = mutableListOf<Param>()
        params += parseParam()
        while (match(COMMA) != null) {
            if (at(RPAREN) || at(RBRACKET)) break
            params += parseParam()
        }
        return params
    }

    private fun parseParam(): Param {
        val start = peek().span
        val name = expect(IDENT).text
        expect(COLON)
        val type = parseType()
        val isVariadic = match(DOT) != null && match(DOT) != null && match(DOT) != null
        val default = if (!isVariadic && match(ASSIGN) != null) parseExpr() else null
        return Param(name, type, default, isVariadic, spanFrom(start))
    }

    private fun parseConstructorParamList(): List<ConstructorParam> {
        val params = mutableListOf<ConstructorParam>()
        params += parseConstructorParam()
        while (match(COMMA) != null) {
            if (at(RPAREN)) break
            params += parseConstructorParam()
        }
        return params
    }

    private fun parseConstructorParam(): ConstructorParam {
        val start = peek().span
        // Parse optional field-level annotations (e.g. @env("DATABASE_URL"))
        // Newlines inside parens are suppressed by the lexer, so @env on its own line works.
        val annos = mutableListOf<Annotation>()
        while (at(AT)) annos += parseAnnotation()
        val vis = parseVisibility()
        // Support @newtype-style type-only params: `UserId(int)` instead of `UserId(value: int)`.
        // Heuristic: IDENT followed by COLON → named param; anything else → type-only, name = "value".
        return if (at(IDENT) && at(COLON, 1)) {
            val name = advance().text   // consume IDENT
            advance()                   // consume COLON
            val type = parseType()
            val default = if (match(ASSIGN) != null) parseExpr() else null
            ConstructorParam(vis, name, type, default, spanFrom(start), annos)
        } else {
            val type = parseType()
            val default = if (match(ASSIGN) != null) parseExpr() else null
            ConstructorParam(vis, "value", type, default, spanFrom(start), annos)
        }
    }

    // ── Types ──────────────────────────────────────────────────────────────

    private fun parseType(): TypeNode {
        return parseNullableType()
    }

    private fun parseNullableType(): TypeNode {
        val start = peek().span
        var inner = parseBaseType()
        var depth = 0
        while (at(QUEST)) {
            advance()
            depth++
        }
        return if (depth > 0) NullableTypeNode(spanFrom(start), inner, depth) else inner
    }

    private fun parseBaseType(): TypeNode {
        val start = peek().span
        return when {
            at(FN) -> parseFnType()
            at(LPAREN) -> parseTupleOrFunctionType()
            at(LBRACKET) -> parseArrayOrMapType()
            at(IDENT) && peek().text == "ptr" -> parsePtrType()
            at(IDENT) || at(SELF) || at(SUPER) -> parseNamedType()
            else -> {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "type", peek().span)
                NamedTypeNode(spanFrom(start), QualifiedName(spanFrom(start), listOf("_error_")), emptyList())
            }
        }
    }

    private fun parseFnType(): TypeNode {
        val start = peek().span
        expect(FN)
        expect(LPAREN)
        val paramTypes = mutableListOf<TypeNode>()
        if (!at(RPAREN)) {
            paramTypes += parseType()
            while (match(COMMA) != null) {
                if (at(RPAREN)) break
                paramTypes += parseType()
            }
        }
        expect(RPAREN)
        expect(ARROW)
        val returnType = parseType()
        return FnTypeNode(spanFrom(start), paramTypes, returnType)
    }

    // Parses either a tuple type `(T, U)` or a function type `(T) → U`.
    // The decision is made after the closing `)`: if `→` follows, it's a function type.
    private fun parseTupleOrFunctionType(): TypeNode {
        val start = peek().span
        expect(LPAREN)
        val fields = mutableListOf<TupleTypeField>()
        if (!at(RPAREN)) {
            fields += parseTupleTypeField()
            while (match(COMMA) != null) {
                if (at(RPAREN)) break
                fields += parseTupleTypeField()
            }
        }
        expect(RPAREN)
        return if (at(ARROW)) {
            advance()
            val returnType = parseType()
            FnTypeNode(spanFrom(start), fields.map { it.type }, returnType)
        } else {
            TupleTypeNode(spanFrom(start), fields)
        }
    }

    private fun parseTupleType(): TypeNode {
        val start = peek().span
        expect(LPAREN)
        // Named tuple: (name: Type, ...)
        // Positional tuple: (Type, Type, ...)
        // Need lookahead: if IDENT COLON then it's named
        val fields = mutableListOf<TupleTypeField>()
        if (!at(RPAREN)) {
            val firstField = parseTupleTypeField()
            fields += firstField
            while (match(COMMA) != null) {
                if (at(RPAREN)) break
                fields += parseTupleTypeField()
            }
        }
        expect(RPAREN)
        return TupleTypeNode(spanFrom(start), fields)
    }

    private fun parseTupleTypeField(): TupleTypeField {
        // Named: ident : type
        if (at(IDENT) && at(COLON, 1)) {
            val name = advance().text
            advance() // colon
            val type = parseType()
            return TupleTypeField(name, type)
        }
        return TupleTypeField(null, parseType())
    }

    private fun parseArrayOrMapType(): TypeNode {
        val start = peek().span
        // [[T]] - matrix
        if (at(LBRACKET) && at(LBRACKET, 1)) {
            advance() // [
            advance() // [
            val elem = parseType()
            expect(RBRACKET)
            expect(RBRACKET)
            return MatrixTypeNode(spanFrom(start), elem)
        }
        expect(LBRACKET)
        // [K: V] map or [T] array
        // peek ahead: if next is type then COLON, it's a map
        // Actually we parse as: first type, then check for colon
        val firstType = parseType()
        return if (at(COLON)) {
            advance() // :
            val valueType = parseType()
            expect(RBRACKET)
            MapTypeNode(spanFrom(start), firstType, valueType)
        } else {
            expect(RBRACKET)
            ArrayTypeNode(spanFrom(start), firstType)
        }
    }

    private fun parsePtrType(): TypeNode {
        val start = peek().span
        advance() // ptr
        expect(LT)
        val inner = parseType()
        expect(GT)
        return PtrTypeNode(spanFrom(start), inner)
    }

    private fun parseNamedType(): NamedTypeNode {
        val start = peek().span
        val name = parseQualifiedName()
        val typeArgs = if (at(LT)) {
            advance()
            val args = mutableListOf<TypeNode>()
            args += parseType()
            while (match(COMMA) != null) {
                if (at(GT)) break
                args += parseType()
            }
            expect(GT)
            args
        } else emptyList()
        return NamedTypeNode(spanFrom(start), name, typeArgs)
    }

    private fun parseTypeList(): List<TypeNode> {
        val types = mutableListOf<TypeNode>()
        types += parseNamedType()
        while (match(COMMA) != null) {
            types += parseNamedType()
        }
        return types
    }

    // ── Qualified names ────────────────────────────────────────────────────

    private fun parseQualifiedName(): QualifiedName {
        val start = peek().span
        val parts = mutableListOf<String>()
        parts += expect(IDENT).text
        while (at(DOT) && at(IDENT, 1)) {
            advance() // dot
            parts += advance().text
        }
        return QualifiedName(spanFrom(start), parts)
    }

    // ── Statements ─────────────────────────────────────────────────────────

    private fun parseStmtList(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            val stmt = tryParseStmt() ?: break
            stmts += stmt
        }
        return stmts
    }

    private fun tryParseStmt(): Stmt? {
        return when {
            at(LET) || (at(WEAK) && at(LET, 1)) -> parseLetStmt()
            at(VAR) || (at(WEAK) && at(VAR, 1)) -> parseVarStmt()
            at(ARROW) || at(RETURN) -> parseReturnStmt()
            at(IF) -> parseIfOrGuardStmt()
            at(GUARD) -> parseGuardLetStmt()
            at(FOR) || (at(AT) && at(IDENT, 1) && at(FOR, 2)) -> parseForStmt()
            at(WHILE) || (at(AT) && at(IDENT, 1) && at(WHILE, 2)) -> parseWhileStmt()
            at(MATCH) -> parseMatchStmt()
            at(DEFER) -> parseDeferStmt()
            at(TRY) -> parseTryCatchStmt()
            at(THROW) -> parseThrowStmt()
            at(GO) -> parseGoStmt()
            at(SPAWN) -> parseSpawnStmt()
            at(SELECT) -> parseSelectStmt()
            at(BREAK) -> parseBreakStmt()
            at(CONTINUE) -> parseContinueStmt()
            at(UNSAFE) -> parseUnsafeBlock()
            at(YIELD) -> parseYieldStmt()
            at(AT) && at(IDENT, 1) && peek(1).text == "asm"   -> parseAsmStmt()
            at(AT) && at(IDENT, 1) && peek(1).text == "bytes" -> parseBytesStmt()
            at(AT) && at(IDENT, 1) && peek(1).text == "c"   && (at(NEWLINE, 2) || at(INDENT, 2)) -> parseCBlockStmt()
            at(AT) && at(IDENT, 1) && peek(1).text == "cpp" && (at(NEWLINE, 2) || at(INDENT, 2)) -> parseCppBlockStmt()
            at(NEWLINE) -> { advance(); null }
            at(DEDENT) || at(EOF) -> null
            else -> parseAssignOrExprStmt()
        }
    }

    private fun parseLetStmt(): Stmt {
        val start = peek().span
        val isWeak = match(WEAK) != null
        expect(LET)
        val binding = parseBinding()
        val typeAnnotation = if (at(COLON)) {
            advance()
            parseType()
        } else null
        val initializer = if (match(ASSIGN) != null) parseExpr() else null
        expectNewlineAfter(initializer)
        return LetStmt(spanFrom(start), isWeak, binding, typeAnnotation, initializer)
    }

    private fun parseVarStmt(): Stmt {
        val start = peek().span
        val isWeak = match(WEAK) != null
        expect(VAR)
        val binding = parseBinding()
        val typeAnnotation = if (at(COLON)) {
            advance()
            parseType()
        } else null
        val initializer = if (match(ASSIGN) != null) parseExpr() else null
        expectNewlineAfter(initializer)
        return VarStmt(spanFrom(start), isWeak, binding, typeAnnotation, initializer)
    }

    private fun parseReturnStmt(): Stmt {
        val start = peek().span
        if (at(ARROW)) {
            advance()
            val expr = parseExpr()
            expectNewline()
            return ReturnStmt(spanFrom(start), expr)
        }
        expect(RETURN)
        val expr = if (!at(NEWLINE) && !at(EOF) && !at(DEDENT)) parseExpr() else null
        expectNewline()
        return ReturnStmt(spanFrom(start), expr)
    }

    private fun parseIfOrGuardStmt(): Stmt {
        val start = peek().span
        expect(IF)
        // if let x = expr
        val letBinding = if (at(LET)) {
            advance() // let
            val name = expect(IDENT).text
            val typeAnn = if (at(COLON)) {
                advance()
                parseType()
            } else null
            expect(ASSIGN)
            IfLetBinding(name, typeAnn, spanFrom(start))
        } else null

        val condition = parseExpr()

        // Inline if? (has 'then')
        if (at(THEN)) {
            return parseInlineIfAsStmt(start, letBinding, condition)
        }

        expectNewline()
        expectIndent("if")
        val thenBody = parseStmtList()
        expectDedent()

        val elseIfClauses = mutableListOf<ElseIfClause>()
        var elseBody: List<Stmt>? = null

        while (at(ELSE)) {
            val elseStart = peek().span
            advance() // else
            if (at(IF)) {
                advance() // if
                val elseLetBinding = if (at(LET)) {
                    advance()
                    val nm = expect(IDENT).text
                    val ta = if (at(COLON)) { advance(); parseType() } else null
                    expect(ASSIGN)
                    IfLetBinding(nm, ta, spanFrom(elseStart))
                } else null
                val elseIfCond = parseExpr()
                expectNewline()
                expectIndent("else if")
                val elseIfBody = parseStmtList()
                expectDedent()
                elseIfClauses += ElseIfClause(elseLetBinding, elseIfCond, elseIfBody, spanFrom(elseStart))
            } else {
                expectNewline()
                expectIndent("else")
                elseBody = parseStmtList()
                expectDedent()
                break
            }
        }

        return IfStmt(spanFrom(start), letBinding, condition, thenBody, elseIfClauses, elseBody)
    }

    private fun parseInlineIfAsStmt(start: SourceSpan, letBinding: IfLetBinding?, condition: Expr): Stmt {
        // This is an inline if expr used as a statement
        advance() // then
        val thenExpr = parseExpr()
        val elseIfClauses = mutableListOf<InlineElseIfClause>()
        while (at(ELSE) && at(IF, 1)) {
            val cs = peek().span
            advance(); advance()
            val c = parseExpr()
            expect(THEN)
            val e = parseExpr()
            elseIfClauses += InlineElseIfClause(c, e, spanFrom(cs))
        }
        expect(ELSE)
        val elseExpr = parseExpr()
        expectNewline()
        val ifExpr = InlineIfExpr(spanFrom(start), condition, thenExpr, elseIfClauses, elseExpr)
        return ExprStmt(spanFrom(start), ifExpr)
    }

    private fun parseGuardLetStmt(): Stmt {
        val start = peek().span
        expect(GUARD)
        expect(LET)
        val name = expect(IDENT).text
        val typeAnn = if (at(COLON)) {
            advance()
            parseType()
        } else null
        expect(ASSIGN)
        val value = parseExpr()
        expect(ELSE)
        expectNewline()
        expectIndent("guard let else")
        val elseBody = parseStmtList()
        expectDedent()
        return GuardLetStmt(spanFrom(start), name, typeAnn, value, elseBody)
    }

    private fun parseForStmt(): Stmt {
        val start = peek().span
        val label = if (at(AT)) {
            advance()
            val lbl = expect(IDENT).text
            lbl
        } else null
        expect(FOR)
        val binding = parseBinding()
        if (match(IN) == null) {
            match(ELEM_OF)
        }
        val iterable = parseExpr()
        expectNewline()
        expectIndent("for")
        val body = parseStmtList()
        expectDedent()
        return ForStmt(spanFrom(start), label, binding, iterable, body)
    }

    private fun parseWhileStmt(): Stmt {
        val start = peek().span
        val label = if (at(AT)) {
            advance()
            val lbl = expect(IDENT).text
            lbl
        } else null
        expect(WHILE)
        val condition = parseExpr()
        expectNewline()
        expectIndent("while")
        val body = parseStmtList()
        expectDedent()
        return WhileStmt(spanFrom(start), label, condition, body)
    }

    private fun parseMatchStmt(): MatchStmt {
        val start = peek().span
        val matchExpr = parseMatchExpr()
        return MatchStmt(spanFrom(start), matchExpr)
    }

    private fun parseDeferStmt(): Stmt {
        val start = peek().span
        expect(DEFER)
        return if (at(NEWLINE)) {
            expectNewline()
            expectIndent("defer")
            val stmts = parseStmtList()
            expectDedent()
            DeferStmt(spanFrom(start), BlockDefer(spanFrom(start), stmts))
        } else {
            val stmt = tryParseStmt() ?: run {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "statement", peek().span)
                ExprStmt(peek().span, NilExpr(peek().span))
            }
            DeferStmt(spanFrom(start), SingleStmtDefer(spanFrom(start), stmt))
        }
    }

    private fun parseTryCatchStmt(): Stmt {
        val start = peek().span
        expect(TRY)
        expectNewline()
        expectIndent("try")
        val tryBody = parseStmtList()
        expectDedent()
        val catchClauses = mutableListOf<CatchClause>()
        while (at(CATCH)) {
            val cs = peek().span
            advance() // catch
            val binding = expect(IDENT).text
            val type = if (at(COLON)) {
                advance()
                parseType()
            } else null
            expectNewline()
            expectIndent("catch")
            val catchBody = parseStmtList()
            expectDedent()
            catchClauses += CatchClause(spanFrom(cs), binding, type, catchBody)
        }
        val finallyBody = if (at(FINALLY)) {
            advance()
            expectNewline()
            expectIndent("finally")
            val fb = parseStmtList()
            expectDedent()
            fb
        } else null
        return TryCatchStmt(spanFrom(start), tryBody, catchClauses, finallyBody)
    }

    private fun parseThrowStmt(): Stmt {
        val start = peek().span
        expect(THROW)
        val expr = parseExpr()
        expectNewline()
        return ThrowStmt(spanFrom(start), expr)
    }

    private fun parseGoStmt(): Stmt {
        val start = peek().span
        expect(GO)
        val captureList = if (at(LBRACKET)) {
            advance()
            val items = parseCaptureList()
            expect(RBRACKET)
            items
        } else emptyList()

        return if (at(LBRACE)) {
            advance() // {
            expectNewline()
            expectIndent("go block")
            val stmts = parseStmtList()
            expectDedent()
            expect(RBRACE)
            GoStmt(spanFrom(start), captureList, GoBlockBody(spanFrom(start), stmts))
        } else {
            val expr = parseExpr()
            expectNewline()
            GoStmt(spanFrom(start), captureList, GoExprBody(spanFrom(start), expr))
        }
    }

    private fun parseCaptureList(): List<CaptureItem> {
        val items = mutableListOf<CaptureItem>()
        items += parseCaptureItem()
        while (match(COMMA) != null) {
            if (at(RBRACKET)) break
            items += parseCaptureItem()
        }
        return items
    }

    private fun parseCaptureItem(): CaptureItem {
        val start = peek().span
        val isCopy = at(IDENT) && peek().text == "copy" && at(IDENT, 1)
        if (isCopy) advance() // copy
        val name = expect(IDENT).text
        return CaptureItem(isCopy, name, spanFrom(start))
    }

    private fun parseSpawnStmt(): Stmt {
        val start = peek().span
        expect(SPAWN)
        val expr = parseExpr()
        expectNewline()
        return SpawnStmt(spanFrom(start), expr)
    }

    private fun parseSelectStmt(): Stmt {
        val start = peek().span
        expect(SELECT)
        expectNewline()
        expectIndent("select")
        val arms = mutableListOf<SelectArm>()
        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            arms += parseSelectArm()
        }
        expectDedent()
        return SelectStmt(spanFrom(start), arms)
    }

    private fun parseSelectArm(): SelectArm {
        val start = peek().span
        return when {
            at(AFTER) -> {
                advance() // after
                val duration = parseDurationLit()
                expect(COLON)
                expectNewline()
                expectIndent("after arm")
                val body = parseStmtList()
                expectDedent()
                AfterSelectArm(spanFrom(start), duration, body)
            }
            at(DEFAULT) -> {
                advance() // default
                expect(COLON)
                expectNewline()
                expectIndent("default arm")
                val body = parseStmtList()
                expectDedent()
                DefaultSelectArm(spanFrom(start), body)
            }
            at(IDENT) || at(UNDERSCORE) -> {
                val binding = if (at(UNDERSCORE)) { advance(); null } else advance().text
                expect(FROM)
                val channel = parseExpr()
                expect(COLON)
                expectNewline()
                expectIndent("select arm")
                val body = parseStmtList()
                expectDedent()
                ReceiveSelectArm(spanFrom(start), binding, channel, body)
            }
            else -> {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "select arm", peek().span)
                syncToStmtBoundary()
                DefaultSelectArm(spanFrom(start), emptyList())
            }
        }
    }

    private fun parseDurationLit(): DurationLit {
        val start = peek().span
        val amount = expect(INT_LIT).text.toLongOrNull() ?: 0L
        val unit = when {
            at(IDENT) && peek().text == "ms" -> { advance(); DurationUnit.MS }
            at(IDENT) && peek().text == "s" -> { advance(); DurationUnit.S }
            at(IDENT) && peek().text == "m" -> { advance(); DurationUnit.M }
            at(IDENT) && peek().text == "h" -> { advance(); DurationUnit.H }
            else -> {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "duration unit (ms/s/m/h)", peek().span)
                DurationUnit.S
            }
        }
        return DurationLit(amount, unit, spanFrom(start))
    }

    private fun parseBreakStmt(): Stmt {
        val start = peek().span
        expect(BREAK)
        val label = if (at(AT)) {
            advance()
            expect(IDENT).text
        } else null
        expectNewline()
        return BreakStmt(spanFrom(start), label)
    }

    private fun parseContinueStmt(): Stmt {
        val start = peek().span
        expect(CONTINUE)
        val label = if (at(AT)) {
            advance()
            expect(IDENT).text
        } else null
        expectNewline()
        return ContinueStmt(spanFrom(start), label)
    }

    private fun parseUnsafeBlock(): Stmt {
        val start = peek().span
        expect(UNSAFE)
        expect(LBRACE)
        expectNewline()
        expectIndent("unsafe")
        val stmts = parseStmtList()
        expectDedent()
        expect(RBRACE)
        return UnsafeBlock(spanFrom(start), stmts)
    }

    private fun parseYieldStmt(): Stmt {
        val start = peek().span
        expect(YIELD)
        val expr = parseExpr()
        expectNewline()
        return YieldStmt(spanFrom(start), expr)
    }

    // ── Inline assembly / raw bytes ────────────────────────────────────────

    /**
     * Parses:
     *   @asm[arch]
     *     "instruction1"
     *     "instruction2"
     *     clobbers: ["reg1", "reg2"]    // optional
     *
     *   @asm[arch, feature1, feature2]
     *     "instruction"
     */
    private fun parseAsmStmt(): Stmt {
        val start = peek().span
        expect(AT)
        expect(IDENT)  // "asm"
        expect(LBRACKET)
        val arch = expect(IDENT).text
        val features = mutableListOf<String>()
        while (match(COMMA) != null) {
            if (at(RBRACKET)) break
            features += expect(IDENT).text
        }
        expect(RBRACKET)
        expectNewline()
        expectIndent("asm block")

        val instructions = mutableListOf<String>()
        val clobbers = mutableListOf<String>()

        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            when {
                // clobbers: ["reg1", "reg2"]
                at(IDENT) && peek().text == "clobbers" -> {
                    advance() // clobbers
                    expect(COLON)
                    expect(LBRACKET)
                    while (!at(RBRACKET) && !at(EOF)) {
                        if (at(STR_START)) clobbers += parseStringLiteralText()
                        else if (!at(COMMA) && !at(RBRACKET)) break
                        if (match(COMMA) == null) break
                    }
                    expect(RBRACKET)
                    expectNewline()
                }
                at(STR_START) -> {
                    instructions += parseStringLiteralText()
                    expectNewline()
                }
                at(RAW_STRING_LIT) -> {
                    instructions += advance().text
                    expectNewline()
                }
                else -> {
                    // unknown token in asm block — skip to next newline
                    while (!at(NEWLINE) && !at(DEDENT) && !at(EOF)) advance()
                    if (at(NEWLINE)) advance()
                }
            }
        }

        expectDedent()
        return AsmStmt(spanFrom(start), arch, features, instructions, clobbers)
    }

    /**
     * Parses:
     *   @bytes[arch]
     *     0x90, 0x90, 0x90
     *
     * Bytes may be on one or multiple lines, comma-separated or space-separated.
     */
    private fun parseBytesStmt(): Stmt {
        val start = peek().span
        expect(AT)
        expect(IDENT)  // "bytes"
        expect(LBRACKET)
        val arch = expect(IDENT).text
        expect(RBRACKET)
        expectNewline()
        expectIndent("bytes block")

        val bytes = mutableListOf<Int>()
        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            while (!at(NEWLINE) && !at(DEDENT) && !at(EOF)) {
                when {
                    at(INT_LIT) -> {
                        val tok = advance()
                        val v = tok.text.removePrefix("0x").removePrefix("0X")
                            .let { if (tok.text.startsWith("0x") || tok.text.startsWith("0X")) it.toInt(16) else it.toInt() }
                        bytes += v.coerceIn(0, 255)
                    }
                    at(COMMA) -> advance()
                    else -> advance()
                }
            }
            if (at(NEWLINE)) advance()
        }

        expectDedent()
        return BytesStmt(spanFrom(start), arch, bytes)
    }

    /** Parse an @c block: @c followed by indented raw C source lines. */
    private fun parseCBlockStmt(): Stmt {
        val start = peek().span
        expect(AT)
        expect(IDENT)  // "c"
        if (!at(INDENT)) {
            if (at(NEWLINE)) advance()
        }
        val lines = mutableListOf<String>()
        if (at(INDENT)) {
            advance() // consume INDENT
            while (!at(DEDENT) && !at(EOF)) {
                while (at(NEWLINE)) advance()
                if (at(DEDENT) || at(EOF)) break
                // Collect a line as tokens until newline
                val lineBuf = StringBuilder()
                while (!at(NEWLINE) && !at(DEDENT) && !at(EOF)) {
                    lineBuf.append(peek().text)
                    advance()
                }
                lines += lineBuf.toString()
                if (at(NEWLINE)) advance()
            }
            if (at(DEDENT)) advance()
        }
        return CBlockStmt(spanFrom(start), lines)
    }

    /** Parse a @cpp block: @cpp followed by indented raw C++ source lines. */
    private fun parseCppBlockStmt(): Stmt {
        val start = peek().span
        expect(AT)
        expect(IDENT)  // "cpp"
        if (!at(INDENT)) {
            if (at(NEWLINE)) advance()
        }
        val lines = mutableListOf<String>()
        if (at(INDENT)) {
            advance() // consume INDENT
            while (!at(DEDENT) && !at(EOF)) {
                while (at(NEWLINE)) advance()
                if (at(DEDENT) || at(EOF)) break
                val lineBuf = StringBuilder()
                while (!at(NEWLINE) && !at(DEDENT) && !at(EOF)) {
                    lineBuf.append(peek().text)
                    advance()
                }
                lines += lineBuf.toString()
                if (at(NEWLINE)) advance()
            }
            if (at(DEDENT)) advance()
        }
        return CppBlockStmt(spanFrom(start), lines)
    }

    private fun parseAssignOrExprStmt(): Stmt {
        val start = peek().span
        val expr = parseExpr()
        val assignOp = tryParseAssignOp()
        return if (assignOp != null) {
            val value = parseExpr()
            expectNewlineAfter(value)
            AssignStmt(spanFrom(start), expr, assignOp, value)
        } else {
            expectNewlineAfter(expr)
            ExprStmt(spanFrom(start), expr)
        }
    }

    private fun tryParseAssignOp(): AssignOp? {
        return when (peek().kind) {
            ASSIGN -> { advance(); AssignOp.ASSIGN }
            PLUS_ASSIGN -> { advance(); AssignOp.PLUS_ASSIGN }
            MINUS_ASSIGN -> { advance(); AssignOp.MINUS_ASSIGN }
            STAR_ASSIGN -> { advance(); AssignOp.STAR_ASSIGN }
            SLASH_ASSIGN -> { advance(); AssignOp.SLASH_ASSIGN }
            INT_DIV_ASSIGN -> { advance(); AssignOp.INT_DIV_ASSIGN }
            MOD_ASSIGN -> { advance(); AssignOp.MOD_ASSIGN }
            AMP_ASSIGN -> { advance(); AssignOp.AMP_ASSIGN }
            PIPE_ASSIGN -> { advance(); AssignOp.PIPE_ASSIGN }
            XOR_ASSIGN -> { advance(); AssignOp.XOR_ASSIGN }
            LSHIFT_ASSIGN -> { advance(); AssignOp.LSHIFT_ASSIGN }
            RSHIFT_ASSIGN -> { advance(); AssignOp.RSHIFT_ASSIGN }
            else -> null
        }
    }

    // ── Binding ────────────────────────────────────────────────────────────

    private fun parseBinding(): Binding {
        val start = peek().span
        if (at(LPAREN)) {
            advance()
            val names = mutableListOf<String>()
            names += expect(IDENT).text
            while (match(COMMA) != null) {
                if (at(RPAREN)) break
                names += expect(IDENT).text
            }
            expect(RPAREN)
            return TupleBinding(spanFrom(start), names)
        }
        if (at(UNDERSCORE)) {
            advance()
            return IdentBinding(spanFrom(start), "_")
        }
        val name = expect(IDENT).text
        return IdentBinding(spanFrom(start), name)
    }

    // ── Expressions ────────────────────────────────────────────────────────

    fun parseExpr(): Expr = parseLambdaExpr()

    private fun parseLambdaExpr(): Expr {
        if (isLambdaStart()) {
            return parseLambda(captureList = emptyList())
        }
        // Check for capture list: [copy? name, ...] params -> body
        if (at(LBRACKET)) {
            val savedPos = pos
            val savedErrorCount = errors.size
            try {
                val captureList = tryParseCaptureList()
                if (captureList != null && isLambdaStart()) {
                    return parseLambda(captureList)
                }
            } catch (_: Exception) {}
            // Speculative parse failed — restore position and roll back any errors added
            pos = savedPos
            while (errors.size > savedErrorCount) errors.removeAt(errors.size - 1)
        }
        return parsePipelineExpr()
    }

    private fun tryParseCaptureList(): List<CaptureItem>? {
        if (!at(LBRACKET)) return null
        advance()
        val items = mutableListOf<CaptureItem>()
        val start = peek().span
        if (!at(RBRACKET)) {
            items += parseCaptureItem()
            while (match(COMMA) != null) {
                if (at(RBRACKET)) break
                items += parseCaptureItem()
            }
        }
        if (!at(RBRACKET)) return null
        advance()
        return items
    }

    private fun parseLambda(captureList: List<CaptureItem>): LambdaExpr {
        val start = if (pos > 0) tokens[pos - 1].span else peek().span
        val lambdaStart = peek().span
        val params: List<LambdaParam>
        if (at(IDENT) && at(ARROW, 1)) {
            // Single param
            val pStart = peek().span
            val name = advance().text
            params = listOf(LambdaParam(name, null, pStart))
        } else {
            // Multi-param: ( params ) ->
            expect(LPAREN)
            val pList = mutableListOf<LambdaParam>()
            if (!at(RPAREN)) {
                pList += parseLambdaParam()
                while (match(COMMA) != null) {
                    if (at(RPAREN)) break
                    pList += parseLambdaParam()
                }
            }
            expect(RPAREN)
            params = pList
        }
        expect(ARROW)
        val body = if (at(NEWLINE)) {
            // Block lambda - can't happen in this grammar at top level but handle defensively
            ExprLambdaBody(peek().span, parsePipelineExpr())
        } else {
            val expr = parseExpr()
            ExprLambdaBody(spanFrom(lambdaStart), expr)
        }
        return LambdaExpr(spanFrom(lambdaStart), captureList, params, body)
    }

    private fun parseLambdaParam(): LambdaParam {
        val start = peek().span
        val name = expect(IDENT).text
        val type = if (at(COLON)) {
            advance()
            parseType()
        } else null
        return LambdaParam(name, type, spanFrom(start))
    }

    private fun parsePipelineExpr(): Expr {
        val start = peek().span
        var left = parseOrExpr()
        while (at(PIPELINE)) {
            advance()
            val right = parseOrExpr()
            left = BinaryExpr(spanFrom(start), BinaryOp.PIPELINE, left, right)
        }
        return left
    }

    private fun parseOrExpr(): Expr {
        val start = peek().span
        var left = parseAndExpr()
        while (at(OR)) {
            advance()
            val right = parseAndExpr()
            left = BinaryExpr(spanFrom(start), BinaryOp.OR, left, right)
        }
        return left
    }

    private fun parseAndExpr(): Expr {
        val start = peek().span
        var left = parseNotExpr()
        while (at(AND)) {
            advance()
            val right = parseNotExpr()
            left = BinaryExpr(spanFrom(start), BinaryOp.AND, left, right)
        }
        return left
    }

    private fun parseNotExpr(): Expr {
        val start = peek().span
        if (at(NOT) || at(BANG)) {
            advance()
            val operand = parseNotExpr()
            return UnaryExpr(spanFrom(start), UnaryOp.NOT, operand)
        }
        return parseNullCoalesceExpr()
    }

    private fun parseNullCoalesceExpr(): Expr {
        val start = peek().span
        var left = parseCompareExpr()
        while (at(NULL_COALESCE)) {
            advance()
            val right = parseCompareExpr()
            left = BinaryExpr(spanFrom(start), BinaryOp.NULL_COALESCE, left, right)
        }
        return left
    }

    private fun parseCompareExpr(): Expr {
        val start = peek().span
        val left = parseBitOrExpr()
        val op = when {
            at(EQ) -> BinaryOp.EQ
            at(NEQ) -> BinaryOp.NEQ
            at(LT) -> BinaryOp.LT
            at(GT) -> BinaryOp.GT
            at(LEQ) -> BinaryOp.LEQ
            at(GEQ) -> BinaryOp.GEQ
            else -> return left
        }
        advance()
        val right = parseBitOrExpr()
        return BinaryExpr(spanFrom(start), op, left, right)
    }

    private fun parseBitOrExpr(): Expr {
        val start = peek().span
        var left = parseBitXorExpr()
        while (at(PIPE)) {
            advance()
            val right = parseBitXorExpr()
            left = BinaryExpr(spanFrom(start), BinaryOp.BIT_OR, left, right)
        }
        return left
    }

    private fun parseBitXorExpr(): Expr {
        val start = peek().span
        var left = parseBitAndExpr()
        while (at(XOR_OP) || at(XOR)) {
            advance()
            val right = parseBitAndExpr()
            left = BinaryExpr(spanFrom(start), BinaryOp.BIT_XOR, left, right)
        }
        return left
    }

    private fun parseBitAndExpr(): Expr {
        val start = peek().span
        var left = parseShiftExpr()
        while (at(AMP)) {
            advance()
            val right = parseShiftExpr()
            left = BinaryExpr(spanFrom(start), BinaryOp.BIT_AND, left, right)
        }
        return left
    }

    private fun parseShiftExpr(): Expr {
        val start = peek().span
        var left = parseBitNotExpr()
        while (at(LSHIFT) || at(RSHIFT)) {
            val op = if (at(LSHIFT)) BinaryOp.LSHIFT else BinaryOp.RSHIFT
            advance()
            val right = parseBitNotExpr()
            left = BinaryExpr(spanFrom(start), op, left, right)
        }
        return left
    }

    private fun parseBitNotExpr(): Expr {
        val start = peek().span
        if (at(TILDE)) {
            advance()
            val operand = parseBitNotExpr()
            return UnaryExpr(spanFrom(start), UnaryOp.BIT_NOT, operand)
        }
        return parseAddExpr()
    }

    private fun parseAddExpr(): Expr {
        val start = peek().span
        var left = parseMulExpr()
        while (at(PLUS) || at(MINUS)) {
            val op = if (at(PLUS)) BinaryOp.PLUS else BinaryOp.MINUS
            advance()
            val right = parseMulExpr()
            left = BinaryExpr(spanFrom(start), op, left, right)
        }
        return left
    }

    private fun parseMulExpr(): Expr {
        val start = peek().span
        var left = parsePowerExpr()
        while (at(STAR) || at(SLASH) || at(INT_DIV) || at(MOD)) {
            val op = when {
                at(STAR) -> BinaryOp.STAR
                at(SLASH) -> BinaryOp.SLASH
                at(INT_DIV) -> BinaryOp.INT_DIV
                else -> BinaryOp.MOD
            }
            advance()
            val right = parsePowerExpr()
            left = BinaryExpr(spanFrom(start), op, left, right)
        }
        return left
    }

    private fun parsePowerExpr(): Expr {
        val start = peek().span
        val base = parseUnaryExpr()
        if (at(POWER)) {
            advance()
            val exp = parsePowerExpr() // right-associative
            return BinaryExpr(spanFrom(start), BinaryOp.POWER, base, exp)
        }
        return base
    }

    private fun parseUnaryExpr(): Expr {
        val start = peek().span
        if (at(MINUS)) {
            advance()
            val operand = parseUnaryExpr()
            return UnaryExpr(spanFrom(start), UnaryOp.NEGATE, operand)
        }
        return parsePostfixExpr()
    }

    private fun parsePostfixExpr(): Expr {
        val start = peek().span
        var expr = parsePrimary()
        while (true) {
            expr = when {
                at(QUEST) -> {
                    advance()
                    ResultPropagateExpr(spanFrom(start), expr)
                }
                at(BANG) -> {
                    advance()
                    ForceUnwrapExpr(spanFrom(start), expr)
                }
                at(DOT) && at(IDENT, 1) -> {
                    advance() // .
                    val member = advance().text
                    // @builder DSL: TypeName.build followed by an indented assignment block
                    if (member == "build" && expr is IdentExpr && at(NEWLINE) && at(INDENT, 1)) {
                        advance() // NEWLINE
                        advance() // INDENT
                        val assignments = mutableListOf<Pair<String, Expr>>()
                        while (!at(DEDENT) && !at(EOF)) {
                            while (at(NEWLINE)) advance()
                            if (at(DEDENT) || at(EOF)) break
                            val fieldName = expect(IDENT).text
                            expect(ASSIGN)
                            val value = parseExpr()
                            if (at(NEWLINE)) advance()
                            assignments += fieldName to value
                        }
                        if (at(DEDENT)) advance()
                        BuilderCallExpr(spanFrom(start), expr.name, assignments)
                    } else {
                        MemberAccessExpr(spanFrom(start), expr, member)
                    }
                }
                at(DOT_QUEST) -> {
                    advance() // ?.
                    val member = expect(IDENT).text
                    SafeNavExpr(spanFrom(start), expr, member)
                }
                at(LBRACKET) && !isRangeClosingBracket() -> {
                    advance()
                    val indices = parseIndexArgs()
                    expect(RBRACKET)
                    IndexExpr(spanFrom(start), expr, indices)
                }
                at(LPAREN) -> {
                    advance()
                    val args = if (!at(RPAREN)) parseCallArgs() else emptyList()
                    expect(RPAREN)
                    val trailingLambda = if (at(LBRACE)) parseTrailingLambda() else null
                    CallExpr(spanFrom(start), expr, args, trailingLambda)
                }
                at(LBRACE) -> {
                    // Trailing lambda without parens
                    val lambda = parseTrailingLambda()
                    CallExpr(spanFrom(start), expr, emptyList(), lambda)
                }
                at(IS) -> {
                    advance()
                    val type = parseType()
                    TypeTestExpr(spanFrom(start), expr, type)
                }
                at(AS) && at(QUEST, 1) -> {
                    advance(); advance() // as?
                    val type = parseType()
                    SafeCastExpr(spanFrom(start), expr, type)
                }
                at(AS) && at(BANG, 1) -> {
                    advance(); advance() // as!
                    val type = parseType()
                    ForceCastExpr(spanFrom(start), expr, type)
                }
                else -> break
            }
        }
        return expr
    }

    private fun parseIndexArgs(): List<IndexArg> {
        val args = mutableListOf<IndexArg>()
        args += parseIndexArg()
        while (match(COMMA) != null) {
            if (at(RBRACKET)) break
            args += parseIndexArg()
        }
        return args
    }

    private fun parseIndexArg(): IndexArg {
        val start = peek().span
        if (at(STAR)) {
            advance()
            return IndexArg(true, null, spanFrom(start))
        }
        val expr = parseExpr()
        return IndexArg(false, expr, spanFrom(start))
    }

    private fun parseCallArgs(): List<CallArg> {
        val args = mutableListOf<CallArg>()
        args += parseCallArg()
        while (match(COMMA) != null) {
            if (at(RPAREN)) break
            args += parseCallArg()
        }
        return args
    }

    private fun parseCallArg(): CallArg {
        val start = peek().span
        // Named: ident: expr
        if (at(IDENT) && at(COLON, 1)) {
            val name = advance().text
            advance() // colon
            val expr = parseExpr()
            return CallArg(name, expr, spanFrom(start))
        }
        val expr = parseExpr()
        return CallArg(null, expr, spanFrom(start))
    }

    private fun parseTrailingLambda(): LambdaExpr {
        val start = peek().span
        expect(LBRACE)
        val captureList = emptyList<CaptureItem>()
        // Optional params ->
        val params = mutableListOf<LambdaParam>()
        val savedPos = pos
        if (!at(RBRACE) && !at(NEWLINE)) {
            // Try to parse params ->
            val tempParams = tryParseLambdaParamsForTrailing()
            if (tempParams != null) {
                params += tempParams
            } else {
                pos = savedPos
            }
        }
        val body = if (at(NEWLINE)) {
            expectNewline()
            expectIndent("trailing lambda")
            val stmts = parseStmtList()
            expectDedent()
            BlockLambdaBody(spanFrom(start), stmts)
        } else {
            val expr = parseExpr()
            ExprLambdaBody(spanFrom(start), expr)
        }
        expect(RBRACE)
        return LambdaExpr(spanFrom(start), captureList, params, body)
    }

    private fun tryParseLambdaParamsForTrailing(): List<LambdaParam>? {
        val savedPos = pos
        val params = mutableListOf<LambdaParam>()
        // Try: IDENT -> or ( params ) ->
        if (at(IDENT) && at(ARROW, 1)) {
            val p = advance().text
            params += LambdaParam(p, null, peek().span)
            advance() // arrow
            return params
        }
        if (at(LPAREN)) {
            val closeIdx = findMatchingParen(pos)
            if (closeIdx != -1 && closeIdx + 1 < tokens.size && tokens[closeIdx + 1].kind == ARROW) {
                advance() // (
                if (!at(RPAREN)) {
                    params += parseLambdaParam()
                    while (match(COMMA) != null) {
                        if (at(RPAREN)) break
                        params += parseLambdaParam()
                    }
                }
                expect(RPAREN)
                advance() // arrow
                return params
            }
        }
        pos = savedPos
        return null
    }

    // ── Primary expressions ────────────────────────────────────────────────

    private fun parsePrimary(): Expr {
        val start = peek().span
        return when {
            // Literals
            at(INT_LIT) -> IntLitExpr(spanFrom(start), advance().text)
            at(FLOAT_LIT) -> FloatLitExpr(spanFrom(start), advance().text)
            at(TRUE) -> { advance(); BoolLitExpr(spanFrom(start), true) }
            at(FALSE) -> { advance(); BoolLitExpr(spanFrom(start), false) }
            at(NIL) -> { advance(); NilExpr(spanFrom(start)) }
            at(CHAR_LIT) -> CharLitExpr(spanFrom(start), advance().text)
            at(RAW_STRING_LIT) -> RawStringExpr(spanFrom(start), advance().text)
            at(CONST_PI) -> { advance(); ConstPiExpr(spanFrom(start)) }
            at(CONST_INF) -> { advance(); ConstInfExpr(spanFrom(start)) }
            at(STR_START) -> parseInterpolatedString()

            // Quantifiers
            at(FORALL) -> parseQuantifierExpr(QuantifierOp.FORALL)
            at(EXISTS) -> parseQuantifierExpr(QuantifierOp.EXISTS)
            at(SUM) -> parseQuantifierExpr(QuantifierOp.SUM)
            at(PRODUCT) -> parseQuantifierExpr(QuantifierOp.PRODUCT)

            // Match expression
            at(MATCH) -> parseMatchExpr()

            // If expression (inline)
            at(IF) && hasInlineIfThen() -> parseInlineIfExpr()

            // List comprehension: [expr for ...]
            at(LBRACKET) && isListComprehension() -> parseListComprehension()

            // Range: ] starts a range
            at(RBRACKET) -> parseOpenRange(start)

            // Array/map literals or range (if [ ... , ... ] with range context)
            at(LBRACKET) -> parseArrayOrMapLiteral()

            // Tuple literal: (e1, e2, ...)
            at(LPAREN) && isTupleLiteral() -> parseTupleLiteral()

            // Parenthesized expression
            at(LPAREN) -> {
                advance()
                val inner = parseExpr()
                expect(RPAREN)
                ParenExpr(spanFrom(start), inner)
            }

            // Wildcard / positional hole
            at(UNDERSCORE) -> { advance(); WildcardExpr(spanFrom(start)) }

            // Identifier (possibly 'e' = Euler's number)
            at(IDENT) -> {
                val text = peek().text
                advance()
                if (text == "e") ConstEExpr(spanFrom(start))
                else IdentExpr(spanFrom(start), text)
            }

            at(SELF) -> { val t = advance(); IdentExpr(spanFrom(start), t.text) }
            at(SUPER) -> { val t = advance(); IdentExpr(spanFrom(start), t.text) }

            // Async / concurrency expressions
            at(AWAIT) -> { advance(); AwaitExpr(spanFrom(start), parseUnaryExpr()) }
            at(SPAWN) -> { advance(); SpawnExpr(spanFrom(start), parseUnaryExpr()) }

            else -> {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "expression", peek().span)
                syncToStmtBoundary()
                NilExpr(spanFrom(start))
            }
        }
    }

    private fun isListComprehension(): Boolean {
        // [ expr for ...
        var i = pos + 1
        var depth = 1
        while (i < tokens.size) {
            when (tokens[i].kind) {
                LBRACKET -> depth++
                RBRACKET -> {
                    depth--
                    if (depth == 0) return false
                }
                FOR -> if (depth == 1) return true
                EOF -> return false
                else -> {}
            }
            i++
        }
        return false
    }

    /**
     * Returns true if the LBRACKET at [pos] is a range-closing bracket (as in `[a, b[`)
     * rather than an opening bracket for an index access.
     *
     * A range-closing `[` has no matching `]` before the next DEDENT or EOF.
     * An index-access `[` always has a matching `]`.
     */
    private fun isRangeClosingBracket(): Boolean {
        var i = pos + 1
        var depth = 1
        while (i < tokens.size) {
            when (tokens[i].kind) {
                TokenKind.LBRACKET -> depth++
                TokenKind.RBRACKET -> {
                    depth--
                    if (depth == 0) return false  // found matching ] — index access
                }
                TokenKind.DEDENT, TokenKind.EOF -> return true  // no ] before end — range closer
                else -> {}
            }
            i++
        }
        return true
    }

    private fun isTupleLiteral(): Boolean {
        val closeIdx = findMatchingParen(pos)
        if (closeIdx == -1) return false
        // Empty parens () = unit value — also a tuple literal
        if (closeIdx == pos + 1) return true
        // Check if there's a comma inside at depth 1
        var depth = 0
        var i = pos
        while (i <= closeIdx) {
            when (tokens[i].kind) {
                LPAREN -> depth++
                RPAREN -> depth--
                COMMA -> if (depth == 1) return true
                else -> {}
            }
            i++
        }
        return false
    }

    private fun parseInterpolatedString(): Expr {
        val start = peek().span
        expect(STR_START)
        val parts = mutableListOf<StringPart>()
        while (!at(STR_END) && !at(EOF)) {
            when {
                at(STR_TEXT) -> {
                    val t = advance()
                    parts += StringTextPart(t.span, t.text)
                }
                at(INTERP_START) -> {
                    val is_span = peek().span
                    advance() // {
                    val expr = parseExpr()
                    val formatSpec = if (at(COLON)) {
                        advance()
                        if (at(STR_TEXT)) advance().text else null
                    } else null
                    expect(INTERP_END)
                    parts += StringInterpolationPart(spanFrom(is_span), expr, formatSpec)
                }
                else -> {
                    errors += ParseError.UnexpectedToken(peek().kind, peek().text, "string part", peek().span)
                    advance()
                }
            }
        }
        expect(STR_END)
        return InterpolatedStringExpr(spanFrom(start), parts)
    }

    private fun parseQuantifierExpr(op: QuantifierOp): Expr {
        val start = peek().span
        advance() // ∀/∃/∑/∏

        // Sum/product of iterable: ∑ expr or ∏ expr (no binding/in)
        if ((op == QuantifierOp.SUM || op == QuantifierOp.PRODUCT) && !at(IDENT) && !at(LPAREN)) {
            val body = parseExpr()
            return QuantifierExpr(spanFrom(start), op, null, null, BareIterableBody(spanFrom(start), body))
        }

        val binding = parseBinding()
        if (match(ELEM_OF) == null) match(IN)

        val iterable = parseExpr()

        return if (at(COLON)) {
            advance()
            val bodyExpr = parseExpr()
            QuantifierExpr(spanFrom(start), op, binding, iterable, InlineQuantifierBody(spanFrom(start), bodyExpr))
        } else if (at(ARROW)) {
            advance()
            expectNewline()
            expectIndent("quantifier block")
            val stmts = parseStmtList()
            expectDedent()
            QuantifierExpr(spanFrom(start), op, binding, iterable, BlockQuantifierBody(spanFrom(start), stmts))
        } else {
            QuantifierExpr(spanFrom(start), op, binding, iterable, BareIterableBody(spanFrom(start), iterable))
        }
    }

    private fun parseMatchExpr(): MatchExpr {
        val start = peek().span
        expect(MATCH)
        val subject = parseExpr()
        expectNewline()
        expectIndent("match")
        val arms = mutableListOf<MatchArm>()
        while (!at(DEDENT) && !at(EOF)) {
            while (at(NEWLINE)) advance()
            if (at(DEDENT) || at(EOF)) break
            arms += parseMatchArm()
        }
        expectDedent()
        return MatchExpr(spanFrom(start), subject, arms)
    }

    private fun parseMatchArm(): MatchArm {
        val start = peek().span
        val pattern = parsePattern()
        val guard = if (at(IF)) {
            advance()
            parseExpr()
        } else null

        // Block arm: pattern: NEWLINE INDENT stmts DEDENT
        if (at(COLON) && (at(NEWLINE, 1) || at(INDENT, 1))) {
            advance() // consume COLON
            expectNewline()
            expectIndent("match arm")
            val stmts = parseStmtList()
            expectDedent()
            return MatchArm(spanFrom(start), pattern, guard, BlockMatchArmBody(spanFrom(start), stmts))
        }

        // Single-line arm: pattern: → expr  or  pattern, → expr
        if (at(COMMA) || at(COLON)) {
            advance()
            expect(ARROW)
            val expr = parseExpr()
            expectNewline()
            return MatchArm(spanFrom(start), pattern, guard, ExprMatchArmBody(spanFrom(start), expr))
        }

        // Try arrow directly (some forms)
        if (at(ARROW)) {
            advance()
            val expr = parseExpr()
            expectNewline()
            return MatchArm(spanFrom(start), pattern, guard, ExprMatchArmBody(spanFrom(start), expr))
        }

        errors += ParseError.UnexpectedToken(peek().kind, peek().text, "match arm body (: or ,)", peek().span)
        syncToStmtBoundary()
        return MatchArm(spanFrom(start), pattern, guard, ExprMatchArmBody(spanFrom(start), NilExpr(spanFrom(start))))
    }

    private fun parseInlineIfExpr(): Expr {
        val start = peek().span
        expect(IF)
        val condition = parseExpr()
        expect(THEN)
        val thenExpr = parseExpr()
        val elseIfClauses = mutableListOf<InlineElseIfClause>()
        while (at(ELSE) && at(IF, 1)) {
            val cs = peek().span
            advance() // else
            advance() // if
            val c = parseExpr()
            expect(THEN)
            val e = parseExpr()
            elseIfClauses += InlineElseIfClause(c, e, spanFrom(cs))
        }
        expect(ELSE)
        val elseExpr = parseExpr()
        return InlineIfExpr(spanFrom(start), condition, thenExpr, elseIfClauses, elseExpr)
    }

    private fun parseOpenRange(start: SourceSpan): Expr {
        // ] expr , expr ] or ] expr , expr [
        expect(RBRACKET)
        val s = parseExpr()
        expect(COMMA)
        val e = parseExpr()
        val kind = if (at(RBRACKET)) {
            advance()
            RangeKind.HALF_OPEN_RIGHT
        } else {
            expect(LBRACKET)
            RangeKind.OPEN
        }
        return RangeExpr(spanFrom(start), kind, s, e)
    }

    private fun parseArrayOrMapLiteral(): Expr {
        val start = peek().span
        expect(LBRACKET)

        // Empty map: [:]
        if (at(COLON) && at(RBRACKET, 1)) {
            advance() // :
            advance() // ]
            return EmptyMapExpr(spanFrom(start))
        }

        // Empty array: []
        if (at(RBRACKET)) {
            advance()
            return ArrayLiteralExpr(spanFrom(start), emptyList())
        }

        // Parse first expression
        val first = parseExpr()

        // Map literal: [expr: expr, ...]
        if (at(COLON)) {
            advance()
            val value = parseExpr()
            val entries = mutableListOf(MapEntry(first, value, spanFrom(start)))
            while (match(COMMA) != null) {
                if (at(RBRACKET)) break
                val k = parseExpr()
                expect(COLON)
                val v = parseExpr()
                entries += MapEntry(k, v, spanFrom(start))
            }
            expect(RBRACKET)
            return MapLiteralExpr(spanFrom(start), entries)
        }

        // Array literal: [expr, expr, ...]
        // Also handles [a, b[ (HALF_OPEN_RIGHT range) and [a, b] (CLOSED range)
        // by checking the closing token after the second element.
        val elements = mutableListOf(first)
        while (match(COMMA) != null) {
            if (at(RBRACKET) || at(LBRACKET)) break
            elements += parseExpr()
        }
        // If exactly two elements and the closing token is [ → half-open-right range
        if (elements.size == 2 && at(LBRACKET)) {
            advance() // consume closing [
            return RangeExpr(spanFrom(start), RangeKind.HALF_OPEN_RIGHT, elements[0], elements[1])
        }
        // If exactly two elements and closing ] → closed range (only in for/match-like context
        // where it can't be a 2-element array — detect by lookahead context elsewhere).
        // For now: treat [a, b] as an array (closed range is ]a, b] or in match patterns).
        expect(RBRACKET)
        return ArrayLiteralExpr(spanFrom(start), elements)
    }

    private fun parseTupleLiteral(): Expr {
        val start = peek().span
        expect(LPAREN)
        val elements = mutableListOf<Expr>()
        if (!at(RPAREN)) {
            elements += parseExpr()
            while (match(COMMA) != null) {
                if (at(RPAREN)) break
                elements += parseExpr()
            }
        }
        expect(RPAREN)
        return TupleLiteralExpr(spanFrom(start), elements)
    }

    private fun parseListComprehension(): Expr {
        val start = peek().span
        expect(LBRACKET)
        val body = parseExpr()
        val generators = mutableListOf<ComprehensionGenerator>()
        while (at(FOR)) {
            val gs = peek().span
            advance() // for
            val binding = parseBinding()
            if (match(IN) == null) match(ELEM_OF)
            val iterable = parseExpr()
            generators += ComprehensionGenerator(binding, iterable, spanFrom(gs))
        }
        val guard = if (at(IF)) {
            advance()
            parseExpr()
        } else null
        expect(RBRACKET)
        return ListComprehensionExpr(spanFrom(start), body, generators, guard)
    }

    // ── Patterns ────────────────────────────────────────────────────────────

    private fun parsePattern(): Pattern {
        return parseOrPattern()
    }

    private fun parseOrPattern(): Pattern {
        val start = peek().span
        val first = parseSinglePattern()
        if (!at(PIPE)) return first
        val alternatives = mutableListOf(first)
        while (at(PIPE)) {
            advance()
            alternatives += parseSinglePattern()
        }
        return OrPattern(spanFrom(start), alternatives)
    }

    private fun parseSinglePattern(): Pattern {
        val start = peek().span
        return when {
            at(NIL) -> { advance(); NilPattern(spanFrom(start)) }
            at(UNDERSCORE) -> { advance(); WildcardPattern(spanFrom(start)) }
            at(LPAREN) -> parseTuplePattern()
            at(LBRACKET) -> parseRangePatternClosed(start)
            at(RBRACKET) -> parseRangePatternOpen(start)
            at(MINUS) || at(INT_LIT) || at(FLOAT_LIT) || at(TRUE) || at(FALSE) || at(STR_START) || at(CHAR_LIT) -> {
                LiteralPattern(spanFrom(start), parsePrimary())
            }
            at(IDENT) -> parseIdentOrTypePattern(start)
            else -> {
                errors += ParseError.UnexpectedToken(peek().kind, peek().text, "pattern", peek().span)
                WildcardPattern(spanFrom(start))
            }
        }
    }

    private fun parseIdentOrTypePattern(start: SourceSpan): Pattern {
        val name = parseQualifiedName()
        return when {
            at(LPAREN) -> {
                advance()
                val patArgs = if (!at(RPAREN)) {
                    val pats = mutableListOf<Pattern>()
                    pats += parsePattern()
                    while (match(COMMA) != null) {
                        if (at(RPAREN)) break
                        pats += parsePattern()
                    }
                    PositionalTypePatternArgs(spanFrom(start), pats)
                } else NoTypePatternArgs(spanFrom(start))
                expect(RPAREN)
                TypePattern(spanFrom(start), name, patArgs)
            }
            at(LBRACE) -> {
                advance()
                val fields = mutableListOf<PatternField>()
                if (!at(RBRACE)) {
                    fields += parsePatternField()
                    while (match(COMMA) != null) {
                        if (at(RBRACE)) break
                        fields += parsePatternField()
                    }
                }
                expect(RBRACE)
                TypePattern(spanFrom(start), name, NamedTypePatternArgs(spanFrom(start), fields))
            }
            name.parts.size == 1 && name.parts[0][0].isLowerCase() -> {
                // lowercase ident = binding pattern
                BindingPattern(spanFrom(start), name.parts[0])
            }
            else -> {
                // Could be type pattern with no args or binding
                TypePattern(spanFrom(start), name, NoTypePatternArgs(spanFrom(start)))
            }
        }
    }

    private fun parsePatternField(): PatternField {
        val start = peek().span
        val name = expect(IDENT).text
        expect(COLON)
        val pattern = parsePattern()
        return PatternField(name, pattern, spanFrom(start))
    }

    private fun parseTuplePattern(): Pattern {
        val start = peek().span
        expect(LPAREN)
        val elements = mutableListOf<Pattern>()
        elements += parsePattern()
        while (match(COMMA) != null) {
            if (at(RPAREN)) break
            elements += parsePattern()
        }
        expect(RPAREN)
        return TuplePattern(spanFrom(start), elements)
    }

    private fun parseRangePatternClosed(start: SourceSpan): Pattern {
        // [ expr , expr ] or [ expr , expr [
        expect(LBRACKET)
        val s = parseExpr()
        expect(COMMA)
        val e = parseExpr()
        val kind = if (at(RBRACKET)) {
            advance()
            RangeKind.CLOSED
        } else {
            expect(LBRACKET)
            RangeKind.HALF_OPEN_LEFT
        }
        val range = RangeExpr(spanFrom(start), kind, s, e)
        return RangePattern(spanFrom(start), range)
    }

    private fun parseRangePatternOpen(start: SourceSpan): Pattern {
        // ] expr , expr [ or ] expr , expr ]
        expect(RBRACKET)
        val s = parseExpr()
        expect(COMMA)
        val e = parseExpr()
        val kind = if (at(LBRACKET)) {
            advance()
            RangeKind.OPEN
        } else {
            expect(RBRACKET)
            RangeKind.HALF_OPEN_RIGHT
        }
        val range = RangeExpr(spanFrom(start), kind, s, e)
        return RangePattern(spanFrom(start), range)
    }
}
