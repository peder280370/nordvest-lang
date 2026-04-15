package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for ProcessRuntime: std.process operations (nv_process_*).
 */
class ProcessRuntimeTest : NvCompilerTestBase() {

    private fun projectDir(): File =
        File(System.getProperty("projectDir", System.getProperty("user.dir", ".")))
            .let { if (it.name == "tests") it.parentFile else it }

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
