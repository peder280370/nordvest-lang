package nv.compiler.lexer

/** Byte offset + 1-based line/column position in source text. */
data class SourceLocation(
    val offset: Int,
    val line: Int,
    val col: Int,
)

/** Half-open byte range [start, end) in source text. */
data class SourceSpan(
    val start: SourceLocation,
    val end: SourceLocation,
) {
    companion object {
        val SYNTHETIC = SourceSpan(
            SourceLocation(0, 0, 0),
            SourceLocation(0, 0, 0),
        )
    }
}
