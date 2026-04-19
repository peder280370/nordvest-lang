package nv.intellij.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Hand-written IntelliJ [LexerBase] for Nordvest (.nv) source files.
 *
 * Designed for syntax highlighting and bracket matching; it does NOT emit
 * INDENT/DEDENT tokens (the IDE handles indentation presentation itself).
 * String interpolation `"text {expr} more"` is scanned as a single STRING
 * token for simplicity — Tier 2 (PSI) will refine this with per-segment tokens.
 *
 * Unicode math operators are classified as MATH_OP so the colour settings
 * page can assign them a distinct colour.
 */
class NordvestLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var bufEnd = 0
    private var pos = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    // ── LexerBase contract ────────────────────────────────────────────────

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufEnd  = endOffset
        this.pos     = startOffset
        advance()
    }

    override fun getState()          = 0
    override fun getTokenType()      = tokenType
    override fun getTokenStart()     = tokenStart
    override fun getTokenEnd()       = tokenEnd
    override fun getBufferSequence() = buffer
    override fun getBufferEnd()      = bufEnd

    override fun advance() {
        if (pos >= bufEnd) {
            tokenType = null
            return
        }
        tokenStart = pos
        tokenType  = scan()
        tokenEnd   = pos
    }

    // ── Scanner ───────────────────────────────────────────────────────────

    private fun peek(offset: Int = 0): Char =
        if (pos + offset < bufEnd) buffer[pos + offset] else '\u0000'

    private fun eat(): Char = buffer[pos++]

    private fun scan(): IElementType {
        val c = peek()

        // Whitespace (including indentation — not emitted as INDENT/DEDENT)
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
            while (pos < bufEnd && buffer[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' })
                pos++
            return NordvestTokenTypes.WHITESPACE
        }

        // Line comment: // to end of line
        if (c == '/' && peek(1) == '/') {
            while (pos < bufEnd && buffer[pos] != '\n') pos++
            return NordvestTokenTypes.COMMENT
        }

        // Block comment: /* ... */
        if (c == '/' && peek(1) == '*') {
            pos += 2
            while (pos < bufEnd) {
                if (buffer[pos] == '*' && pos + 1 < bufEnd && buffer[pos + 1] == '/') {
                    pos += 2; break
                }
                pos++
            }
            return NordvestTokenTypes.COMMENT
        }

        // String literal: "..." or r"..." (raw)
        if (c == 'r' && peek(1) == '"') {
            pos++ // consume 'r'
            return scanString()
        }
        if (c == '"') return scanString()

        // Number literal
        if (c.isDigit() || (c == '-' && peek(1).isDigit() && tokenType !in EXPR_ENDING_TYPES)) {
            return scanNumber()
        }

        // Annotation: @name
        if (c == '@') {
            eat()
            while (pos < bufEnd && (buffer[pos].isLetterOrDigit() || buffer[pos] == '_')) pos++
            return NordvestTokenTypes.ANNOTATION
        }

        // Identifier or keyword
        if (c.isLetter() || c == '_' || c.isUnicodeMathLetter()) {
            return scanIdentOrKeyword()
        }

        // Unicode single-char math operators
        return when (c) {
            '∀' -> { pos++; NordvestTokenTypes.MATH_OP }  // FORALL
            '∃' -> { pos++; NordvestTokenTypes.MATH_OP }  // EXISTS
            '∑' -> { pos++; NordvestTokenTypes.MATH_OP }  // SUM
            '∏' -> { pos++; NordvestTokenTypes.MATH_OP }  // PRODUCT
            '∈' -> { pos++; NordvestTokenTypes.MATH_OP }  // ELEM_OF
            '∧' -> { pos++; NordvestTokenTypes.MATH_OP }  // AND
            '∨' -> { pos++; NordvestTokenTypes.MATH_OP }  // OR
            '¬' -> { pos++; NordvestTokenTypes.MATH_OP }  // NOT
            '→' -> { pos++; NordvestTokenTypes.MATH_OP }  // ARROW
            '≤' -> { pos++; NordvestTokenTypes.MATH_OP }  // LEQ
            '≥' -> { pos++; NordvestTokenTypes.MATH_OP }  // GEQ
            '≠' -> { pos++; NordvestTokenTypes.MATH_OP }  // NEQ
            '÷' -> {                                       // INT_DIV (+ optional =)
                pos++
                if (pos < bufEnd && buffer[pos] == '=') pos++
                NordvestTokenTypes.MATH_OP
            }
            '⊕' -> {                                       // XOR_OP (+ optional =)
                pos++
                if (pos < bufEnd && buffer[pos] == '=') pos++
                NordvestTokenTypes.MATH_OP
            }
            'π' -> { pos++; NordvestTokenTypes.MATH_OP }  // PI constant
            '∞' -> { pos++; NordvestTokenTypes.MATH_OP }  // INF constant

            // ── ASCII multi-char operators ─────────────────────────────────
            '-' -> {
                eat()
                when {
                    peek() == '>' -> { eat(); NordvestTokenTypes.MATH_OP }  // ->  (arrow)
                    peek() == '=' -> { eat(); NordvestTokenTypes.OPERATOR }  // -=
                    else          -> NordvestTokenTypes.OPERATOR
                }
            }
            '<' -> {
                eat()
                when {
                    peek() == '=' -> { eat(); NordvestTokenTypes.MATH_OP }   // <=
                    peek() == '<' -> {
                        eat()
                        if (peek() == '=') eat()
                        NordvestTokenTypes.OPERATOR                           // << or <<=
                    }
                    else -> NordvestTokenTypes.OPERATOR                       // <
                }
            }
            '>' -> {
                eat()
                when {
                    peek() == '=' -> { eat(); NordvestTokenTypes.MATH_OP }   // >=
                    peek() == '>' -> {
                        eat()
                        if (peek() == '=') eat()
                        NordvestTokenTypes.OPERATOR                           // >> or >>=
                    }
                    else -> NordvestTokenTypes.OPERATOR                       // >
                }
            }
            '=' -> {
                eat()
                if (peek() == '=') { eat(); NordvestTokenTypes.MATH_OP }     // ==
                else NordvestTokenTypes.OPERATOR                              // =
            }
            '!' -> {
                eat()
                if (peek() == '=') { eat(); NordvestTokenTypes.MATH_OP }     // !=
                else NordvestTokenTypes.OPERATOR                              // ! / force-unwrap
            }
            '?' -> {
                eat()
                when {
                    peek() == '.' -> { eat(); NordvestTokenTypes.OPERATOR }   // ?.
                    peek() == '?' -> { eat(); NordvestTokenTypes.OPERATOR }   // ??
                    else          -> NordvestTokenTypes.OPERATOR              // ?
                }
            }
            '|' -> {
                eat()
                when {
                    peek() == '>' -> { eat(); NordvestTokenTypes.OPERATOR }   // |>
                    peek() == '=' -> { eat(); NordvestTokenTypes.OPERATOR }   // |=
                    else          -> NordvestTokenTypes.OPERATOR              // |
                }
            }
            '&' -> {
                eat()
                if (peek() == '=') { eat(); NordvestTokenTypes.OPERATOR }    // &=
                else NordvestTokenTypes.OPERATOR                              // &
            }
            '+' -> {
                eat()
                if (peek() == '=') { eat(); NordvestTokenTypes.OPERATOR }    // +=
                else NordvestTokenTypes.OPERATOR                              // +
            }
            '*' -> {
                eat()
                if (peek() == '=') { eat(); NordvestTokenTypes.OPERATOR }    // *=
                else NordvestTokenTypes.OPERATOR                              // *
            }
            '%' -> {
                eat()
                if (peek() == '=') { eat(); NordvestTokenTypes.OPERATOR }    // %=
                else NordvestTokenTypes.OPERATOR                              // %
            }
            '^' -> { eat(); NordvestTokenTypes.OPERATOR }                     // ^ (power)
            '~' -> { eat(); NordvestTokenTypes.OPERATOR }                     // ~
            '/' -> { eat(); NordvestTokenTypes.OPERATOR }                     // / (divide)

            // ── Brackets (distinct types for PairedBraceMatcher) ──────────
            '(' -> { eat(); NordvestTokenTypes.LPAREN }
            ')' -> { eat(); NordvestTokenTypes.RPAREN }
            '[' -> { eat(); NordvestTokenTypes.LBRACKET }
            ']' -> { eat(); NordvestTokenTypes.RBRACKET }
            '{' -> { eat(); NordvestTokenTypes.LBRACE }
            '}' -> { eat(); NordvestTokenTypes.RBRACE }

            // ── Punctuation ────────────────────────────────────────────────
            '.', ',', ':', ';', '_' -> { eat(); NordvestTokenTypes.PUNCTUATION }

            else -> { eat(); NordvestTokenTypes.BAD_CHAR }
        }
    }

    /** Scan from opening `"` to closing `"`, handling `\"` escapes and `{…}` interpolation. */
    private fun scanString(): IElementType {
        if (pos >= bufEnd || buffer[pos] != '"') {
            pos++
            return NordvestTokenTypes.BAD_CHAR
        }
        eat() // opening "
        var depth = 0
        while (pos < bufEnd) {
            val ch = buffer[pos]
            when {
                ch == '\\' -> { pos += 2 }               // escape: skip next char
                ch == '{' && depth == 0 -> { depth++; pos++ }
                ch == '}' && depth > 0  -> { depth--; pos++ }
                ch == '"' && depth == 0 -> { pos++; break }
                else -> pos++
            }
        }
        return NordvestTokenTypes.STRING
    }

    private fun scanNumber(): IElementType {
        // optional leading minus already checked by caller
        if (pos < bufEnd && buffer[pos] == '-') pos++
        // hex / binary / octal prefix
        if (pos < bufEnd && buffer[pos] == '0' && pos + 1 < bufEnd) {
            when (buffer[pos + 1].lowercaseChar()) {
                'x' -> { pos += 2; while (pos < bufEnd && buffer[pos].isHexDigit()) pos++; return NordvestTokenTypes.NUMBER }
                'b' -> { pos += 2; while (pos < bufEnd && (buffer[pos] == '0' || buffer[pos] == '1')) pos++; return NordvestTokenTypes.NUMBER }
                'o' -> { pos += 2; while (pos < bufEnd && buffer[pos] in '0'..'7') pos++; return NordvestTokenTypes.NUMBER }
                else -> {}
            }
        }
        while (pos < bufEnd && buffer[pos].isDigit()) pos++
        if (pos < bufEnd && buffer[pos] == '.' && pos + 1 < bufEnd && buffer[pos + 1].isDigit()) {
            pos++
            while (pos < bufEnd && buffer[pos].isDigit()) pos++
        }
        if (pos < bufEnd && (buffer[pos] == 'e' || buffer[pos] == 'E')) {
            pos++
            if (pos < bufEnd && (buffer[pos] == '+' || buffer[pos] == '-')) pos++
            while (pos < bufEnd && buffer[pos].isDigit()) pos++
        }
        return NordvestTokenTypes.NUMBER
    }

    private fun scanIdentOrKeyword(): IElementType {
        while (pos < bufEnd && (buffer[pos].isLetterOrDigit() || buffer[pos] == '_' || buffer[pos].isUnicodeMathLetter())) {
            pos++
        }
        val text = buffer.substring(tokenStart, pos)
        return when {
            text in NordvestTokenTypes.KEYWORD_SET     -> NordvestTokenTypes.KEYWORD
            text in NordvestTokenTypes.BUILTIN_TYPE_SET -> NordvestTokenTypes.BUILTIN_TYPE
            else                                       -> NordvestTokenTypes.IDENT
        }
    }

    private fun Char.isHexDigit() = isDigit() || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Returns true for Unicode letters used in Nordvest identifiers beyond ASCII —
     * primarily Greek letters (α–ω, Α–Ω) that commonly appear in mathematical code.
     * π and ∞ are handled separately as single-char math operator tokens.
     */
    private fun Char.isUnicodeMathLetter(): Boolean {
        val cp = code
        return cp in 0x0391..0x03C9   // Greek capital + small letters
            || (isLetter() && cp > 0x7F)  // any non-ASCII letter (broad XID_Continue)
    }

    companion object {
        /**
         * Token types that can immediately precede a number literal without
         * ambiguity with a unary minus — used to decide whether `-3` starts
         * a negative literal or is a minus operator.
         */
        private val EXPR_ENDING_TYPES = setOf(
            NordvestTokenTypes.IDENT,
            NordvestTokenTypes.NUMBER,
            NordvestTokenTypes.STRING,
            NordvestTokenTypes.BUILTIN_TYPE,
        )
    }
}
