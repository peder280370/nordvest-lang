package nv.compiler.lexer

/**
 * A single lexical token produced by the [Lexer].
 *
 * @param kind    What kind of token this is.
 * @param text    The exact source text that produced this token.
 *                For INDENT/DEDENT/EOF the text is empty ("").
 *                For STR_START/STR_END the text is the `"` character.
 *                For INTERP_START/INTERP_END the text is `{` or `}`.
 *                For STR_TEXT the text is the literal string content (escapes already preserved
 *                as-is in the raw text; the parser/evaluator resolves them).
 * @param span    Source location of this token.
 */
data class Token(
    val kind: TokenKind,
    val text: String,
    val span: SourceSpan,
) {
    override fun toString(): String = "Token($kind, ${text.take(40).replace("\n", "\\n")}, ${span.start.line}:${span.start.col})"
}
