package nv.compiler.parser

sealed class ParseResult {
    data class Success(val file: SourceFile) : ParseResult()
    data class Recovered(val file: SourceFile, val errors: List<ParseError>) : ParseResult()
    data class Failure(val errors: List<ParseError>) : ParseResult()
}
