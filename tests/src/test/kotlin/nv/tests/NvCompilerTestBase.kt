package nv.tests

import nv.compiler.Compiler
import nv.compiler.CompileResult
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Shared test helpers for compiler integration tests that compile Nordvest source to LLVM IR
 * and optionally run the output through clang.
 */
open class NvCompilerTestBase {

    fun parse(src: String): SourceFile {
        val tokens = Lexer(src.trimIndent()).tokenize()
        return when (val result = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> result.file
            is ParseResult.Recovered -> result.file
            is ParseResult.Failure   -> fail("Parse failed: ${result.errors.first().message}")
        }
    }

    fun compileOk(src: String): String {
        val result = Compiler.compile(src.trimIndent(), "<test>")
        val ir = when (result) {
            is CompileResult.IrSuccess -> result.llvmIr
            else -> null
        }
        assertNotNull(ir, "Expected IR success but got: ${(result as? CompileResult.Failure)?.errors?.map { it.message }}")
        return ir!!
    }

    fun compileErrors(src: String): List<String> {
        val result = Compiler.compile(src.trimIndent(), "<test>")
        return when (result) {
            is CompileResult.Failure -> result.errors.map { it.message }
            else -> emptyList()
        }
    }

    fun clangAvailable(): Boolean = try {
        val p = ProcessBuilder("clang", "--version").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }

    /**
     * Compile [ir] with clang, run the resulting binary, and return its stdout (trimmed).
     * [env] is merged into the process environment; [tmpPrefix] names the temp directory.
     * Fails the test if clang exits non-zero.
     */
    fun runProgram(
        ir: String,
        env: Map<String, String> = emptyMap(),
        tmpPrefix: String = "nv_test_"
    ): String {
        val tmp = Files.createTempDirectory(tmpPrefix).toFile()
        return try {
            val ll  = File(tmp, "out.ll"); ll.writeText(ir)
            val bin = File(tmp, "out")
            val cc  = ProcessBuilder("clang", "-o", bin.absolutePath, ll.absolutePath)
                .redirectErrorStream(true).start()
            val ccOut = cc.inputStream.bufferedReader().readText()
            assertTrue(cc.waitFor(60, TimeUnit.SECONDS) && cc.exitValue() == 0, "clang failed:\n$ccOut")
            val pb = ProcessBuilder(bin.absolutePath).redirectErrorStream(true)
            pb.environment().putAll(env)
            val run = pb.start()
            run.waitFor(10, TimeUnit.SECONDS)
            run.inputStream.bufferedReader().readText().trimEnd()
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Resolve the repo root regardless of whether tests run from the repo root or the `tests/` subproject. */
    fun projectDir(): File =
        File(System.getProperty("projectDir", System.getProperty("user.dir", ".")))
            .let { if (it.name == "tests") it.parentFile else it }

    /** Convenience: skip the test if clang is not available, then run the program. */
    fun runProgramOrSkip(
        ir: String,
        env: Map<String, String> = emptyMap(),
        tmpPrefix: String = "nv_test_"
    ): String {
        assumeTrue(clangAvailable(), "clang not available")
        return runProgram(ir, env, tmpPrefix)
    }
}
