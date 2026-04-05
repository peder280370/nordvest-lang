package nv.compiler.resolve

import nv.compiler.CompileError
import nv.compiler.lexer.SourceSpan

sealed class ResolveError(message: String, val span: SourceSpan) : Exception(message) {

    class UndefinedSymbol(
        val name: String,
        span: SourceSpan,
        val suggestions: List<String> = emptyList(),
    ) : ResolveError(
        buildMessage(name, suggestions),
        span,
    ) {
        companion object {
            private fun buildMessage(name: String, suggestions: List<String>): String {
                val base = "Undefined symbol '$name'"
                return if (suggestions.isEmpty()) base
                else "$base; did you mean: ${suggestions.joinToString(", ") { "'$it'" }}?"
            }
        }
    }

    class DuplicateDefinition(
        val name: String,
        val firstDefinedAt: SourceSpan,
        span: SourceSpan,
    ) : ResolveError(
        "'$name' already defined at ${firstDefinedAt.start.line}:${firstDefinedAt.start.col}",
        span,
    )

    class UnresolvedImport(
        val modulePath: String,
        span: SourceSpan,
    ) : ResolveError("Cannot resolve import '$modulePath'", span)
}

fun ResolveError.toCompileError(sourcePath: String): CompileError =
    CompileError(message ?: "Resolve error", sourcePath, span.start.line, span.start.col)
