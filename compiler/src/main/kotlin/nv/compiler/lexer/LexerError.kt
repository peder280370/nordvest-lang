package nv.compiler.lexer

/** Base class for all errors produced by the [Lexer]. */
sealed class LexerError(
    message: String,
    val location: SourceLocation,
) : Exception(message) {

    /** A character that does not begin any valid token. */
    class UnexpectedChar(
        val char: Char,
        location: SourceLocation,
    ) : LexerError(
        "Unexpected character '${char.toPrintable()}' (U+${char.code.toString(16).uppercase().padStart(4, '0')}) at ${location.line}:${location.col}",
        location,
    )

    /**
     * A DEDENT did not match any level on the indent stack.
     * (e.g., the file indents by 4 then 6, then dedents to 5 — which is not a known level.)
     */
    class IndentMismatch(
        val got: Int,
        val expected: List<Int>,
        location: SourceLocation,
    ) : LexerError(
        "Indentation mismatch at ${location.line}:${location.col}: got $got spaces, " +
            "which does not match any enclosing level ${expected}",
        location,
    )

    /** Tabs and spaces mixed on the same indentation line. */
    class MixedIndentation(
        location: SourceLocation,
    ) : LexerError(
        "Mixed tabs and spaces in indentation at ${location.line}:${location.col}",
        location,
    )

    /** A string literal was opened but never closed before EOF or unescaped newline. */
    class UnterminatedString(
        location: SourceLocation,
    ) : LexerError(
        "Unterminated string literal starting at ${location.line}:${location.col}",
        location,
    )

    /** A block comment `/* ... */` was opened but never closed before EOF. */
    class UnterminatedBlockComment(
        location: SourceLocation,
    ) : LexerError(
        "Unterminated block comment starting at ${location.line}:${location.col}",
        location,
    )

    /** An unrecognised escape sequence inside a string or char literal. */
    class InvalidEscape(
        val char: Char,
        location: SourceLocation,
    ) : LexerError(
        "Invalid escape sequence '\\${char.toPrintable()}' at ${location.line}:${location.col}",
        location,
    )

    /** A character literal contained zero or more than one character (after escape). */
    class InvalidCharLiteral(
        location: SourceLocation,
    ) : LexerError(
        "Invalid character literal at ${location.line}:${location.col}",
        location,
    )
}

private fun Char.toPrintable(): String = if (this.isISOControl()) "\\u${code.toString(16).uppercase().padStart(4, '0')}" else toString()
