package nv.compiler.codegen

sealed class CodegenResult {
    /** The textual LLVM IR module. */
    data class Success(val llvmIr: String) : CodegenResult()
    data class Failure(val errors: List<CodegenError>) : CodegenResult()
}
