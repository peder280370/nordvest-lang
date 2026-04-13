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
 * Tests for ProcessRuntime: std.process operations (nv_process_*).
 */
class ProcessRuntimeTest {

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
        val tmp = Files.createTempDirectory("nv_proc_").toFile()
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

    @Test fun `stdlib process module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/process.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_process_getenv"),  "missing nv_process_getenv @extern")
        assertTrue(c.contains("nv_process_setenv"),  "missing nv_process_setenv @extern")
        assertTrue(c.contains("nv_process_exit"),    "missing nv_process_exit @extern")
        assertTrue(c.contains("nv_process_pid"),     "missing nv_process_pid @extern")
        assertTrue(c.contains("nv_process_capture"), "missing nv_process_capture @extern")
    }

    @Test fun `process runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i8* @nv_process_getenv"),  "nv_process_getenv missing")
        assertTrue(ir.contains("define void @nv_process_setenv"), "nv_process_setenv missing")
        assertTrue(ir.contains("define void @nv_process_exit"),   "nv_process_exit missing")
        assertTrue(ir.contains("define i64 @nv_process_pid"),     "nv_process_pid missing")
        assertTrue(ir.contains("define i8* @nv_process_capture"), "nv_process_capture missing")
        assertTrue(ir.contains("@getenv"),  "getenv not declared")
        assertTrue(ir.contains("@setenv"),  "setenv not declared")
        assertTrue(ir.contains("@getpid"),  "getpid not declared")
        assertTrue(ir.contains("@popen"),   "popen not declared")
        assertTrue(ir.contains("@pclose"),  "pclose not declared")
    }

    @Test fun `process getenv setenv run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_process_getenv") pub fn procGetenv(name: str) → str?
            @extern(fn: "nv_process_setenv") pub fn procSetenv(name: str, value: str)
            fn main()
                procSetenv("NV_TEST_VAR", "hello")
                let v = procGetenv("NV_TEST_VAR")
                if let s = v
                    println(s)
                else
                    println("not found")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("hello", out)
    }

    @Test fun `process pid returns positive`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_process_pid") pub fn procPid() → int
            fn main()
                let p = procPid()
                if p > 0
                    println("positive pid")
                else
                    println("bad pid")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("positive pid", out)
    }
}
