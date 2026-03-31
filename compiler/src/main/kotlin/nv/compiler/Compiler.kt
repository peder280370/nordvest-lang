package nv.compiler

import nv.compiler.lexer.Lexer
import nv.compiler.lexer.LexerError
import nv.compiler.parser.ParseResult
import nv.compiler.parser.Parser
import nv.compiler.parser.toCompileError
import nv.compiler.resolve.Resolver
import nv.compiler.resolve.ResolveResult
import nv.compiler.resolve.toCompileError
import nv.compiler.typecheck.TypeCheckResult
import nv.compiler.typecheck.TypeChecker
import nv.compiler.typecheck.toCompileError

/**
 * Entry point for the Nordvest bootstrap compiler.
 *
 * Phase 1 implements:
 *   - Lexer (1.1) ✓
 *   - Parser → AST (1.2) ✓
 *   - Name resolution & scope analysis (1.3) ✓
 *   - Type checker (1.4) ✓
 *   - IR lowering HIR → MIR → LLVM IR (1.5)
 *   - Runtime support library (1.6)
 */
object Compiler {
    const val VERSION = "0.0.1-SNAPSHOT"

    fun compile(source: String, sourcePath: String): CompileResult {
        val tokens = try {
            Lexer(source).tokenize()
        } catch (e: LexerError) {
            return CompileResult.Failure(listOf(
                CompileError(e.message ?: "Lexer error", sourcePath, e.location.line, e.location.col)
            ))
        }
        return when (val parseResult = Parser(tokens, sourcePath).parse()) {
            is ParseResult.Success -> {
                val resolveResult = Resolver(sourcePath).resolve(parseResult.file)
                when (resolveResult) {
                    is ResolveResult.Success, is ResolveResult.Recovered -> {
                        val module = when (resolveResult) {
                            is ResolveResult.Success  -> resolveResult.module
                            is ResolveResult.Recovered -> resolveResult.module
                            else -> error("unreachable")
                        }
                        val tcResult = TypeChecker(module).check()
                        when (tcResult) {
                            is TypeCheckResult.Success   -> TODO("Phase 1.5: IR lowering")
                            is TypeCheckResult.Recovered -> CompileResult.Failure(
                                tcResult.module.errors.map { it.toCompileError(sourcePath) }
                            )
                            is TypeCheckResult.Failure   -> CompileResult.Failure(
                                tcResult.errors.map { it.toCompileError(sourcePath) }
                            )
                        }
                    }
                    is ResolveResult.Failure -> CompileResult.Failure(
                        resolveResult.errors.map { it.toCompileError(sourcePath) }
                    )
                }
            }
            is ParseResult.Recovered -> CompileResult.Failure(parseResult.errors.map { it.toCompileError(sourcePath) })
            is ParseResult.Failure   -> CompileResult.Failure(parseResult.errors.map { it.toCompileError(sourcePath) })
        }
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
