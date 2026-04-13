package nv.tests

import nv.compiler.Compiler
import nv.compiler.CompileResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Tests for IterRuntime: std.iter range operations (nv_iter_*).
 */
class IterRuntimeTest {

    private fun projectDir(): File =
        File(System.getProperty("projectDir", System.getProperty("user.dir", ".")))
            .let { if (it.name == "tests") it.parentFile else it }

    private fun compileOk(src: String): String {
        val result = Compiler.compile(src, "<test>")
        val ir = when (result) {
            is CompileResult.IrSuccess -> result.llvmIr
            else -> null
        }
        assertNotNull(ir, "Expected IR success: ${(result as? CompileResult.Failure)?.errors?.map { it.message }}")
        return ir!!
    }

    private fun clangAvailable(): Boolean {
        return try {
            val p = ProcessBuilder("clang", "--version").redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) { false }
    }

    private fun runProgram(ir: String): String {
        val tmp = Files.createTempDirectory("nv_iter_").toFile()
        return try {
            val ll  = File(tmp, "out.ll");  ll.writeText(ir)
            val bin = File(tmp, "out")
            val cc  = ProcessBuilder("clang", "-o", bin.absolutePath, ll.absolutePath)
                .redirectErrorStream(true).start()
            val ccOk = cc.waitFor(60, TimeUnit.SECONDS) && cc.exitValue() == 0
            assertTrue(ccOk, "clang failed: ${cc.inputStream.bufferedReader().readText()}")
            val run = ProcessBuilder(bin.absolutePath)
                .redirectErrorStream(true).start()
            run.waitFor(10, TimeUnit.SECONDS)
            run.inputStream.bufferedReader().readText().trimEnd()
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun `stdlib iter module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/iter.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_iter_range"),      "missing nv_iter_range @extern")
        assertTrue(c.contains("nv_iter_range_step"), "missing nv_iter_range_step @extern")
    }

    @Test fun `iter range produces correct values`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_iter_range") pub fn range(start: int, end: int) → [int]
            fn main()
                let xs = range(0, 5)
                var i = 0
                while i < len(xs)
                    println(xs[i])
                    i = i + 1
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("0\n1\n2\n3\n4", out)
    }

    @Test fun `iter rangeStep produces correct values`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_iter_range_step") pub fn rangeStep(start: int, end: int, step: int) → [int]
            fn main()
                let xs = rangeStep(0, 10, 3)
                var i = 0
                while i < len(xs)
                    println(xs[i])
                    i = i + 1
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("0\n3\n6\n9", out)
    }
}
