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
 * Tests for RandRuntime: std.rand operations (nv_rand_*).
 */
class RandRuntimeTest {

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
        val tmp = Files.createTempDirectory("nv_rand_").toFile()
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

    @Test fun `stdlib rand module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/rand.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_rand_seed"),  "missing nv_rand_seed @extern")
        assertTrue(c.contains("nv_rand_float"), "missing nv_rand_float @extern")
        assertTrue(c.contains("nv_rand_int"),   "missing nv_rand_int @extern")
        assertTrue(c.contains("nv_rand_bool"),  "missing nv_rand_bool @extern")
    }

    @Test fun `rand runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define void @nv_rand_seed"),    "nv_rand_seed missing")
        assertTrue(ir.contains("define i64 @nv_rand_next"),     "nv_rand_next missing")
        assertTrue(ir.contains("define double @nv_rand_float"), "nv_rand_float missing")
        assertTrue(ir.contains("define i64 @nv_rand_int"),      "nv_rand_int missing")
        assertTrue(ir.contains("define i1 @nv_rand_bool"),      "nv_rand_bool missing")
        assertTrue(ir.contains("@nv_rand_state"),               "nv_rand_state global missing")
    }

    @Test fun `rand int produces values in range`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_rand_seed") pub fn randSeed(s: int)
            @extern(fn: "nv_rand_int")  pub fn randInt(lo: int, hi: int) → int
            fn main()
                randSeed(42)
                var ok = true
                var i = 0
                while i < 20
                    let r = randInt(0, 10)
                    if r < 0
                        ok = false
                    if r >= 10
                        ok = false
                    i = i + 1
                if ok
                    println("all in range")
                else
                    println("out of range")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("all in range", out)
    }

    @Test fun `rand float produces values in 0 1`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_rand_seed")  pub fn randSeed(s: int)
            @extern(fn: "nv_rand_float") pub fn randFloat() → float
            fn main()
                randSeed(99)
                var ok = true
                var i = 0
                while i < 10
                    let r = randFloat()
                    if r < 0.0
                        ok = false
                    if r >= 1.0
                        ok = false
                    i = i + 1
                if ok
                    println("all in [0,1)")
                else
                    println("out of range")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("all in [0,1)", out)
    }
}
