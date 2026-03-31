package nv.compiler.lexer

import java.text.Normalizer

/**
 * Hand-written lexer for the Nordvest language.
 *
 * Produces a flat stream of [Token]s from a source string.  Call [tokenize] to
 * get all tokens at once, or call [nextToken] repeatedly until EOF.
 *
 * Key properties:
 *  - Source is NFC-normalised before scanning (per ADR-002).
 *  - INDENT / DEDENT tokens are injected based on column offsets (Python-style).
 *    Tabs count as 4 spaces; mixing tabs and spaces on the same line is an error.
 *  - NEWLINE tokens are suppressed inside open `(` `)` `[` `]` `{` `}` groups.
 *  - Interpolated strings `"hello {name}"` produce:
 *      STR_START  STR_TEXT  INTERP_START  <inner tokens>  INTERP_END  STR_TEXT  STR_END
 *  - `//` is ALWAYS a line comment — there is no `//` operator.
 *  - `÷` (U+00F7) is the only integer-division operator.
 *  - `^` is exponentiation (not XOR). XOR is `⊕` or keyword `xor`.
 *  - `|>` (pipeline) is lexed by maximal munch before `|` (bitwise-OR).
 */
class Lexer(source: String) {

    // ── Source ───────────────────────────────────────────────────────────────

    private val src: String = Normalizer.normalize(source, Normalizer.Form.NFC)
    private val len: Int = src.length

    // ── Position ─────────────────────────────────────────────────────────────

    private var pos: Int = 0
    private var line: Int = 1
    private var col: Int = 1

    // ── Indentation ──────────────────────────────────────────────────────────

    /** Stack of indent levels (column counts). The base level is 0. */
    private val indentStack = ArrayDeque<Int>().also { it.addLast(0) }

    /**
     * True when the previous non-whitespace token was a NEWLINE and we must
     * process the indentation of the next logical line before emitting it.
     */
    private var pendingIndent = false

    // ── Bracket depth ────────────────────────────────────────────────────────

    /**
     * Count of open `(`, `[`, `{` that have not yet been closed.
     * When > 0, NEWLINE / INDENT / DEDENT are suppressed (implicit line continuation).
     */
    private var bracketDepth: Int = 0

    // ── String-interpolation mode stack ──────────────────────────────────────

    private enum class LexMode { NORMAL, STRING, INTERP }

    /** Current mode stack; bottom is always NORMAL. */
    private val modeStack = ArrayDeque<LexMode>().also { it.addLast(LexMode.NORMAL) }

    private val currentMode get() = modeStack.last()

    /**
     * Depth of `{` inside an INTERP mode segment.
     * We push/pop this alongside the mode stack so nested `{}` work correctly.
     */
    private val interpBraceDepth = ArrayDeque<Int>()

    // ── Token queue ──────────────────────────────────────────────────────────

    /**
     * Tokens queued for future [nextToken] calls.
     * The lexer sometimes needs to emit multiple tokens from one logical step
     * (e.g. several DEDENTs, or STR_TEXT + INTERP_START).
     */
    private val pending = ArrayDeque<Token>()

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Collect all tokens up to and including EOF. */
    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            val tok = nextToken()
            tokens.add(tok)
            if (tok.kind == TokenKind.EOF) break
        }
        return tokens
    }

    /** Return the next [Token], advancing the internal position. */
    fun nextToken(): Token {
        if (pending.isNotEmpty()) return pending.removeFirst()

        return when (currentMode) {
            LexMode.STRING -> lexStringContent()
            LexMode.NORMAL, LexMode.INTERP -> lexNormal()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Normal-mode lexing
    // ─────────────────────────────────────────────────────────────────────────

    private fun lexNormal(): Token {
        // After a NEWLINE we must process indentation before the next token.
        if (pendingIndent) {
            pendingIndent = false
            val indentTok = processIndent()
            if (indentTok != null) {
                // There may be additional DEDENTs queued in `pending`.
                if (pending.isNotEmpty()) {
                    pending.addFirst(indentTok)
                    return pending.removeFirst()
                }
                return indentTok
            }
            // processIndent may have queued tokens (multiple DEDENTs) but returned null
            // when the first queued token should be the indent result.
            if (pending.isNotEmpty()) return pending.removeFirst()
        }

        skipInlineWhitespace()

        if (pos >= len) return emitEof()

        val c = src[pos]

        // Newlines
        if (c == '\n' || c == '\r') return lexNewline()

        // Comments
        if (c == '/' && peek(1) == '/') { skipLineComment(); return nextToken() }
        if (c == '/' && peek(1) == '*') {
            if (peek(2) == '*') return lexDocComment()
            skipBlockComment()
            return nextToken()
        }

        // Raw string literals — must be checked before identifiers because 'r' is a valid ident start
        if (c == 'r' && peek(1) == '"') return lexRawString()

        // Identifiers & keywords (including Unicode letters, π, ∞, math symbols)
        if (isIdentStart(c)) return lexIdentOrKeyword()

        // Numeric literals
        if (c.isDigit()) return lexNumber()
        // Negative-exponent floats starting with '.': not valid in Nordvest (must start with digit)

        // Character literals
        if (c == '\'') return lexCharLit()

        // String literals
        if (c == '"') return lexStringStart()

        // Operators and punctuation
        return lexSymbol()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Whitespace & comments
    // ─────────────────────────────────────────────────────────────────────────

    /** Skip spaces and tabs on the current line (NOT newlines). */
    private fun skipInlineWhitespace() {
        while (pos < len && (src[pos] == ' ' || src[pos] == '\t')) advance()
    }

    private fun skipLineComment() {
        // consume '//'
        advance(); advance()
        while (pos < len && src[pos] != '\n' && src[pos] != '\r') advance()
    }

    private fun skipBlockComment() {
        val start = location()
        advance(); advance() // consume '/*'
        while (pos < len) {
            if (src[pos] == '*' && peek(1) == '/') {
                advance(); advance()
                return
            }
            if (src[pos] == '\n' || src[pos] == '\r') advanceNewline()
            else advance()
        }
        throw LexerError.UnterminatedBlockComment(start)
    }

    private fun lexDocComment(): Token {
        val start = location()
        val sb = StringBuilder()
        advance(); advance(); advance() // consume '/**'
        while (pos < len) {
            if (src[pos] == '*' && peek(1) == '/') {
                advance(); advance()
                break
            }
            if (src[pos] == '\n' || src[pos] == '\r') {
                sb.append('\n')
                advanceNewline()
            } else {
                sb.append(src[pos])
                advance()
            }
        }
        return Token(TokenKind.DOC_COMMENT, sb.toString(), span(start))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Newlines & indentation
    // ─────────────────────────────────────────────────────────────────────────

    private fun lexNewline(): Token {
        val start = location()
        advanceNewline()
        // Skip additional blank / comment-only lines without emitting NEWLINE for them.
        while (pos < len && isBlankOrCommentLine()) {
            skipBlankOrCommentLine()
        }
        return if (bracketDepth > 0) {
            // Inside open brackets: suppress NEWLINE, schedule indent processing.
            pendingIndent = true
            nextToken()
        } else {
            pendingIndent = true
            Token(TokenKind.NEWLINE, "\n", span(start))
        }
    }

    /** Returns true if the current position starts a blank or comment-only line. */
    private fun isBlankOrCommentLine(): Boolean {
        var i = pos
        while (i < len && (src[i] == ' ' || src[i] == '\t')) i++
        if (i >= len) return true
        if (src[i] == '\n' || src[i] == '\r') return true
        if (src[i] == '/' && i + 1 < len && src[i + 1] == '/') return true
        if (src[i] == '/' && i + 1 < len && src[i + 1] == '*') return true
        return false
    }

    private fun skipBlankOrCommentLine() {
        // Skip leading whitespace
        while (pos < len && (src[pos] == ' ' || src[pos] == '\t')) advance()
        when {
            pos >= len -> return
            src[pos] == '\n' || src[pos] == '\r' -> advanceNewline()
            src[pos] == '/' && peek(1) == '/' -> {
                skipLineComment()
                if (pos < len && (src[pos] == '\n' || src[pos] == '\r')) advanceNewline()
            }
            src[pos] == '/' && peek(1) == '*' -> {
                skipBlockComment()
                // may span multiple lines; keep going
                if (pos < len && (src[pos] == '\n' || src[pos] == '\r')) advanceNewline()
            }
        }
    }

    /**
     * Process indentation at the start of a logical line.
     * Returns the INDENT token if one should be emitted, or null.
     * Additional DEDENT tokens (when dedenting multiple levels) are pushed to [pending].
     */
    private fun processIndent(): Token? {
        if (bracketDepth > 0) return null
        if (pos >= len) {
            // EOF: emit DEDENTs back to base level
            return emitDedentsToLevel(0, location())
        }

        val (spaces, hasMixed) = countIndent()
        if (hasMixed) throw LexerError.MixedIndentation(location())

        val current = indentStack.last()
        return when {
            spaces > current -> {
                indentStack.addLast(spaces)
                Token(TokenKind.INDENT, "", location().let { SourceSpan(it, it) }.let {
                    Token(TokenKind.INDENT, "", it)
                }.span)
            }
            spaces < current -> emitDedentsToLevel(spaces, location())
            else -> null
        }
    }

    /** Count leading spaces on the current line. Returns (count, hasMixed). */
    private fun countIndent(): Pair<Int, Boolean> {
        var spaces = 0
        var seenSpace = false
        var seenTab = false
        var i = pos
        while (i < len && (src[i] == ' ' || src[i] == '\t')) {
            if (src[i] == ' ') { seenSpace = true; spaces++ }
            else { seenTab = true; spaces += 4 }
            i++
        }
        return Pair(spaces, seenSpace && seenTab)
    }

    /**
     * Pop indent levels until [target] is reached; emit DEDENTs.
     * Returns the first DEDENT (or null if already at target), queuing the rest.
     */
    private fun emitDedentsToLevel(target: Int, loc: SourceLocation): Token? {
        if (indentStack.last() == target) return null
        val dedents = mutableListOf<Token>()
        while (indentStack.last() > target) {
            indentStack.removeLast()
            dedents.add(Token(TokenKind.DEDENT, "", SourceSpan(loc, loc)))
        }
        if (indentStack.last() != target) {
            throw LexerError.IndentMismatch(target, indentStack.toList(), loc)
        }
        if (dedents.isEmpty()) return null
        // Queue all but the first
        for (i in 1 until dedents.size) pending.addLast(dedents[i])
        return dedents[0]
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Identifiers & keywords
    // ─────────────────────────────────────────────────────────────────────────

    private fun lexIdentOrKeyword(): Token {
        val start = location()
        val sb = StringBuilder()

        // Handle single-char Unicode symbols that map directly to operator tokens
        val c = src[pos]
        val unicodeOp = unicodeOperatorToken(c)
        if (unicodeOp != null) {
            advance()
            // Check for compound assignments: ⊕= ÷=
            if (pos < len && src[pos] == '=') {
                val compound = when (unicodeOp) {
                    TokenKind.XOR_OP -> TokenKind.XOR_ASSIGN
                    TokenKind.INT_DIV -> TokenKind.INT_DIV_ASSIGN
                    else -> null
                }
                if (compound != null) {
                    advance()
                    return Token(compound, src.substring(start.offset, pos), span(start))
                }
            }
            return Token(unicodeOp, c.toString(), span(start))
        }

        // Regular identifier
        while (pos < len && isIdentContinue(src[pos])) {
            sb.append(src[pos])
            advance()
        }
        val text = sb.toString()

        // Raw string: r"..." — 'r' followed immediately by '"'
        // This case is handled in lexNormal() before reaching here, so 'r' alone is an ident.

        // Standalone `_` is the wildcard token, not an identifier
        if (text == "_") return Token(TokenKind.UNDERSCORE, "_", span(start))

        val keyword = KEYWORDS[text]
        return if (keyword != null) {
            Token(keyword, text, span(start))
        } else {
            Token(TokenKind.IDENT, text, span(start))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Numeric literals
    // ─────────────────────────────────────────────────────────────────────────

    private fun lexNumber(): Token {
        val start = location()
        val startPos = pos

        // Check for base prefixes
        if (src[pos] == '0' && pos + 1 < len) {
            when (src[pos + 1]) {
                'x', 'X' -> return lexHexInt(start)
                'b', 'B' -> return lexBinaryInt(start)
                'o', 'O' -> return lexOctalInt(start)
            }
        }

        // Decimal integer or float
        while (pos < len && (src[pos].isDigit() || src[pos] == '_')) advance()

        val isFloat = (pos < len && src[pos] == '.' && pos + 1 < len && src[pos + 1].isDigit()) ||
                      (pos < len && (src[pos] == 'e' || src[pos] == 'E'))

        if (isFloat) {
            if (pos < len && src[pos] == '.') {
                advance() // consume '.'
                while (pos < len && (src[pos].isDigit() || src[pos] == '_')) advance()
            }
            if (pos < len && (src[pos] == 'e' || src[pos] == 'E')) {
                advance()
                if (pos < len && (src[pos] == '+' || src[pos] == '-')) advance()
                while (pos < len && src[pos].isDigit()) advance()
            }
            return Token(TokenKind.FLOAT_LIT, src.substring(startPos, pos), span(start))
        }

        return Token(TokenKind.INT_LIT, src.substring(startPos, pos), span(start))
    }

    private fun lexHexInt(start: SourceLocation): Token {
        val startPos = pos
        advance(); advance() // '0x'
        while (pos < len && (src[pos].isHexDigit() || src[pos] == '_')) advance()
        return Token(TokenKind.INT_LIT, src.substring(startPos, pos), span(start))
    }

    private fun lexBinaryInt(start: SourceLocation): Token {
        val startPos = pos
        advance(); advance() // '0b'
        while (pos < len && (src[pos] == '0' || src[pos] == '1' || src[pos] == '_')) advance()
        return Token(TokenKind.INT_LIT, src.substring(startPos, pos), span(start))
    }

    private fun lexOctalInt(start: SourceLocation): Token {
        val startPos = pos
        advance(); advance() // '0o'
        while (pos < len && (src[pos] in '0'..'7' || src[pos] == '_')) advance()
        return Token(TokenKind.INT_LIT, src.substring(startPos, pos), span(start))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Character literals
    // ─────────────────────────────────────────────────────────────────────────

    private fun lexCharLit(): Token {
        val start = location()
        val startPos = pos
        advance() // consume opening '\''
        if (pos >= len) throw LexerError.InvalidCharLiteral(start)
        if (src[pos] == '\\') {
            advance() // consume '\'
            if (pos >= len) throw LexerError.InvalidCharLiteral(start)
            val esc = src[pos]
            advance()
            if (esc == 'u') {
                // \uXXXX — consume 4 hex digits
                repeat(4) {
                    if (pos >= len || !src[pos].isHexDigit()) throw LexerError.InvalidCharLiteral(start)
                    advance()
                }
            } else if (esc !in "ntr\\'\"0") {
                throw LexerError.InvalidEscape(esc, location())
            }
        } else {
            advance() // consume the character
        }
        if (pos >= len || src[pos] != '\'') throw LexerError.InvalidCharLiteral(start)
        advance() // consume closing '\''
        return Token(TokenKind.CHAR_LIT, src.substring(startPos, pos), span(start))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // String literals (with interpolation)
    // ─────────────────────────────────────────────────────────────────────────

    private fun lexStringStart(): Token {
        val start = location()
        advance() // consume opening '"'
        modeStack.addLast(LexMode.STRING)
        return Token(TokenKind.STR_START, "\"", span(start))
    }

    /** Called when [currentMode] is STRING. Lex until `{`, `"`, or an escape. */
    private fun lexStringContent(): Token {
        val start = location()
        if (pos >= len) {
            // Find where the string started for a better error message
            throw LexerError.UnterminatedString(start)
        }
        return when (src[pos]) {
            '"' -> {
                val s = location()
                advance() // consume closing '"'
                modeStack.removeLast()
                Token(TokenKind.STR_END, "\"", span(s))
            }
            '{' -> {
                val s = location()
                advance() // consume '{'
                modeStack.addLast(LexMode.INTERP)
                interpBraceDepth.addLast(1)
                bracketDepth++
                Token(TokenKind.INTERP_START, "{", span(s))
            }
            else -> {
                val sb = StringBuilder()
                val startPos = pos
                loop@ while (pos < len) {
                    when (src[pos]) {
                        '"', '{' -> break@loop
                        '\\' -> {
                            // Preserve escape sequence raw (the parser/evaluator resolves it)
                            sb.append(src[pos])
                            advance()
                            if (pos >= len) throw LexerError.UnterminatedString(start)
                            val esc = src[pos]
                            if (esc !in "ntr\\'\"0{") throw LexerError.InvalidEscape(esc, location())
                            sb.append(src[pos])
                            advance()
                            if (esc == 'u') {
                                repeat(4) {
                                    if (pos >= len || !src[pos].isHexDigit())
                                        throw LexerError.UnterminatedString(start)
                                    sb.append(src[pos]); advance()
                                }
                            }
                        }
                        '\n', '\r' -> throw LexerError.UnterminatedString(start)
                        else -> { sb.append(src[pos]); advance() }
                    }
                }
                Token(TokenKind.STR_TEXT, sb.toString(), span(start))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Raw string literals
    // ─────────────────────────────────────────────────────────────────────────

    private fun lexRawString(): Token {
        val start = location()
        val startPos = pos
        advance() // consume 'r'

        // Multi-line: r"""..."""
        if (pos + 2 < len && src[pos] == '"' && src[pos + 1] == '"' && src[pos + 2] == '"') {
            advance(); advance(); advance() // consume '"""'
            val sb = StringBuilder()
            while (pos < len) {
                if (pos + 2 < len && src[pos] == '"' && src[pos + 1] == '"' && src[pos + 2] == '"') {
                    advance(); advance(); advance() // consume closing '"""'
                    break
                }
                if (src[pos] == '\n' || src[pos] == '\r') {
                    sb.append('\n')
                    advanceNewline()
                } else {
                    sb.append(src[pos])
                    advance()
                }
                if (pos >= len) throw LexerError.UnterminatedString(start)
            }
            return Token(TokenKind.RAW_STRING_LIT, src.substring(startPos, pos), span(start))
        }

        // Single-line: r"..."
        advance() // consume '"'
        while (pos < len && src[pos] != '"') {
            if (src[pos] == '\n' || src[pos] == '\r') throw LexerError.UnterminatedString(start)
            advance()
        }
        if (pos >= len) throw LexerError.UnterminatedString(start)
        advance() // consume closing '"'
        return Token(TokenKind.RAW_STRING_LIT, src.substring(startPos, pos), span(start))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Symbols & operators
    // ─────────────────────────────────────────────────────────────────────────

    private fun lexSymbol(): Token {
        val start = location()
        val c = src[pos]

        // INTERP mode: track brace depth to close on matching '}'
        if (currentMode == LexMode.INTERP && c == '}') {
            val depth = interpBraceDepth.last() - 1
            return if (depth == 0) {
                interpBraceDepth.removeLast()
                modeStack.removeLast() // pop INTERP
                bracketDepth--
                advance()
                Token(TokenKind.INTERP_END, "}", span(start))
            } else {
                interpBraceDepth[interpBraceDepth.size - 1] = depth
                advance()
                bracketDepth--
                Token(TokenKind.RBRACE, "}", span(start))
            }
        }

        return when (c) {
            '(' -> { bracketDepth++; advance(); Token(TokenKind.LPAREN, "(", span(start)) }
            ')' -> { bracketDepth--; advance(); Token(TokenKind.RPAREN, ")", span(start)) }
            '[' -> { bracketDepth++; advance(); Token(TokenKind.LBRACKET, "[", span(start)) }
            ']' -> { bracketDepth--; advance(); Token(TokenKind.RBRACKET, "]", span(start)) }
            '{' -> {
                bracketDepth++
                if (currentMode == LexMode.INTERP) {
                    interpBraceDepth[interpBraceDepth.size - 1]++
                }
                advance()
                Token(TokenKind.LBRACE, "{", span(start))
            }
            '}' -> {
                bracketDepth--
                advance()
                Token(TokenKind.RBRACE, "}", span(start))
            }

            '.' -> { advance(); Token(TokenKind.DOT, ".", span(start)) }
            ',' -> { advance(); Token(TokenKind.COMMA, ",", span(start)) }
            ':' -> { advance(); Token(TokenKind.COLON, ":", span(start)) }
            ';' -> { advance(); Token(TokenKind.SEMICOLON, ";", span(start)) }
            '@' -> { advance(); Token(TokenKind.AT, "@", span(start)) }
            '_' -> {
                // '_' may be the start of an identifier or a standalone wildcard.
                // If followed by an ident-continue char it is part of an identifier —
                // but isIdentStart() catches the '_' case, so we should not reach here
                // with '_' as the first char unless it's standalone. Emit UNDERSCORE.
                advance()
                Token(TokenKind.UNDERSCORE, "_", span(start))
            }

            '+' -> when {
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.PLUS_ASSIGN, "+=", span(start)) }
                else -> { advance(); Token(TokenKind.PLUS, "+", span(start)) }
            }
            '-' -> when {
                peek(1) == '>' -> { advance(); advance(); Token(TokenKind.ARROW, "->", span(start)) }
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.MINUS_ASSIGN, "-=", span(start)) }
                else -> { advance(); Token(TokenKind.MINUS, "-", span(start)) }
            }
            '*' -> when {
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.STAR_ASSIGN, "*=", span(start)) }
                else -> { advance(); Token(TokenKind.STAR, "*", span(start)) }
            }
            '/' -> when {
                // '//' already handled above (line comment); '/*' already handled.
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.SLASH_ASSIGN, "/=", span(start)) }
                else -> { advance(); Token(TokenKind.SLASH, "/", span(start)) }
            }
            '%' -> when {
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.MOD_ASSIGN, "%=", span(start)) }
                else -> { advance(); Token(TokenKind.MOD, "%", span(start)) }
            }
            '^' -> { advance(); Token(TokenKind.POWER, "^", span(start)) }

            '&' -> when {
                peek(1) == '&' -> { advance(); advance(); Token(TokenKind.AND, "&&", span(start)) }
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.AMP_ASSIGN, "&=", span(start)) }
                else -> { advance(); Token(TokenKind.AMP, "&", span(start)) }
            }
            '|' -> when {
                peek(1) == '>' -> { advance(); advance(); Token(TokenKind.PIPELINE, "|>", span(start)) }
                peek(1) == '|' -> { advance(); advance(); Token(TokenKind.OR, "||", span(start)) }
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.PIPE_ASSIGN, "|=", span(start)) }
                else -> { advance(); Token(TokenKind.PIPE, "|", span(start)) }
            }
            '~' -> { advance(); Token(TokenKind.TILDE, "~", span(start)) }

            '<' -> when {
                peek(1) == '<' && peek(2) == '=' -> { advance(); advance(); advance(); Token(TokenKind.LSHIFT_ASSIGN, "<<=", span(start)) }
                peek(1) == '<' -> { advance(); advance(); Token(TokenKind.LSHIFT, "<<", span(start)) }
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.LEQ, "<=", span(start)) }
                else -> { advance(); Token(TokenKind.LT, "<", span(start)) }
            }
            '>' -> when {
                peek(1) == '>' && peek(2) == '=' -> { advance(); advance(); advance(); Token(TokenKind.RSHIFT_ASSIGN, ">>=", span(start)) }
                peek(1) == '>' -> { advance(); advance(); Token(TokenKind.RSHIFT, ">>", span(start)) }
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.GEQ, ">=", span(start)) }
                else -> { advance(); Token(TokenKind.GT, ">", span(start)) }
            }
            '=' -> when {
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.EQ, "==", span(start)) }
                else -> { advance(); Token(TokenKind.ASSIGN, "=", span(start)) }
            }
            '!' -> when {
                peek(1) == '=' -> { advance(); advance(); Token(TokenKind.NEQ, "!=", span(start)) }
                else -> { advance(); Token(TokenKind.BANG, "!", span(start)) }
            }
            '?' -> when {
                peek(1) == '.' -> { advance(); advance(); Token(TokenKind.DOT_QUEST, "?.", span(start)) }
                peek(1) == '?' -> { advance(); advance(); Token(TokenKind.NULL_COALESCE, "??", span(start)) }
                else -> { advance(); Token(TokenKind.QUEST, "?", span(start)) }
            }

            else -> {
                advance()
                throw LexerError.UnexpectedChar(c, start)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EOF
    // ─────────────────────────────────────────────────────────────────────────

    private fun emitEof(): Token {
        // Before EOF, emit any remaining DEDENTs.
        val loc = location()
        val dedentTok = emitDedentsToLevel(0, loc)
        if (dedentTok != null) {
            pending.addLast(Token(TokenKind.EOF, "", SourceSpan(loc, loc)))
            return dedentTok
        }
        return Token(TokenKind.EOF, "", SourceSpan(loc, loc))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Position helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun advance() {
        if (pos < len) {
            col++
            pos++
        }
    }

    /** Advance past a newline sequence (\n or \r\n or \r), updating line/col. */
    private fun advanceNewline() {
        if (pos < len && src[pos] == '\r') {
            pos++
            col = 1
            if (pos < len && src[pos] == '\n') pos++
        } else if (pos < len && src[pos] == '\n') {
            pos++
            col = 1
        }
        line++
        col = 1
    }

    private fun peek(offset: Int): Char = if (pos + offset < len) src[pos + offset] else '\u0000'

    private fun location(): SourceLocation = SourceLocation(pos, line, col)
    private fun span(start: SourceLocation): SourceSpan = SourceSpan(start, location())

    // ─────────────────────────────────────────────────────────────────────────
    // Character classification
    // ─────────────────────────────────────────────────────────────────────────

    private fun isIdentStart(c: Char): Boolean =
        Character.isUnicodeIdentifierStart(c) || c == '_' || isUnicodeMathSymbol(c)

    private fun isIdentContinue(c: Char): Boolean =
        Character.isUnicodeIdentifierPart(c) || isUnicodeMathSymbol(c)

    /**
     * Returns the [TokenKind] for a single-character Unicode math/logical symbol,
     * or null if the character should be lexed as an identifier character instead.
     */
    private fun unicodeOperatorToken(c: Char): TokenKind? = when (c) {
        '∀' -> TokenKind.FORALL
        '∃' -> TokenKind.EXISTS
        '∑' -> TokenKind.SUM
        '∏' -> TokenKind.PRODUCT
        '∈' -> TokenKind.ELEM_OF
        '∧' -> TokenKind.AND
        '∨' -> TokenKind.OR
        '¬' -> TokenKind.NOT
        '→' -> TokenKind.ARROW
        '≤' -> TokenKind.LEQ
        '≥' -> TokenKind.GEQ
        '≠' -> TokenKind.NEQ
        '÷' -> TokenKind.INT_DIV
        '⊕' -> TokenKind.XOR_OP
        'π' -> TokenKind.CONST_PI
        '∞' -> TokenKind.CONST_INF
        else -> null
    }

    /**
     * Returns true for Unicode math symbols that the lexer handles as operator tokens
     * (so they should not be treated as identifier characters despite what
     * [Character.isUnicodeIdentifierStart] may say).
     */
    private fun isUnicodeMathSymbol(c: Char): Boolean = unicodeOperatorToken(c) != null

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
