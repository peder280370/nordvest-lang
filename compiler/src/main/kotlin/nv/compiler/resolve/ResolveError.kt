package nv.compiler.resolve

import nv.compiler.CompileError
import nv.compiler.lexer.SourceSpan

sealed class ResolveError(message: String, val span: SourceSpan) : Exception(message) {

    class UndefinedSymbol(
        val name: String,
        span: SourceSpan,
    ) : ResolveError("Undefined symbol '$name'", span)

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
