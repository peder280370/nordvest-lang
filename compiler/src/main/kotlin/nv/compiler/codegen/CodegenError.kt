package nv.compiler.codegen

import nv.compiler.lexer.SourceSpan

sealed class CodegenError {
    abstract val message: String
    abstract val span: SourceSpan?

    data class UnsupportedFeature(
        override val message: String,
        override val span: SourceSpan? = null,
    ) : CodegenError()

    data class InternalError(
        override val message: String,
        override val span: SourceSpan? = null,
    ) : CodegenError()
}
