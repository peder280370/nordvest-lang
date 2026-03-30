package nv.compiler

/**
 * Entry point for the Nordvest bootstrap compiler.
 *
 * Phase 1 will implement:
 *   - Lexer (1.1)
 *   - Parser → AST (1.2)
 *   - Name resolution & scope analysis (1.3)
 *   - Type checker (1.4)
 *   - IR lowering HIR → MIR → LLVM IR (1.5)
 *   - Runtime support library (1.6)
 */
object Compiler {
    const val VERSION = "0.0.1-SNAPSHOT"

    fun compile(source: String, sourcePath: String): CompileResult {
        TODO("Phase 1: implement lexer, parser, type checker, IR lowering")
    }
}

sealed class CompileResult {
    data class Success(val outputPath: String) : CompileResult()
    data class Failure(val errors: List<CompileError>) : CompileResult()
}

data class CompileError(
    val message: String,
    val sourcePath: String,
    val line: Int,
    val column: Int,
)
