package nv.compiler.parser

import nv.compiler.lexer.SourceSpan
import nv.compiler.lexer.TokenKind

sealed class ParseError(
    message: String,
    val span: SourceSpan,
) : Exception(message) {

    class UnexpectedToken(
        val got: TokenKind,
        val gotText: String,
        val expected: String,
        span: SourceSpan,
    ) : ParseError("Unexpected token $got ('$gotText') at ${span.start.line}:${span.start.col}, expected $expected", span)

    class UnexpectedEof(
        val expected: String,
        span: SourceSpan,
    ) : ParseError("Unexpected end of file, expected $expected", span)

    class InvalidIndent(
        val context: String,
        span: SourceSpan,
    ) : ParseError("Expected INDENT after $context at ${span.start.line}:${span.start.col}", span)
}

fun ParseError.toCompileError(sourcePath: String): nv.compiler.CompileError =
    nv.compiler.CompileError(message ?: "Parse error", sourcePath, span.start.line, span.start.col)
