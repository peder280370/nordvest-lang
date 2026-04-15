package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for TimeRuntime: std.time operations (nv_time_*).
 */
class TimeRuntimeTest : NvCompilerTestBase() {

    private fun projectDir(): File =
        File(System.getProperty("projectDir", System.getProperty("user.dir", ".")))
            .let { if (it.name == "tests") it.parentFile else it }

    @Test fun `stdlib time module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/time.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_time_now_ms"),       "missing nv_time_now_ms @extern")
        assertTrue(c.contains("nv_time_monotonic_ns"), "missing nv_time_monotonic_ns @extern")
        assertTrue(c.contains("nv_time_sleep_ms"),     "missing nv_time_sleep_ms @extern")
    }

    @Test fun `time runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i64 @nv_time_now_ms"),       "nv_time_now_ms missing")
        assertTrue(ir.contains("define double @nv_time_now_float"), "nv_time_now_float missing")
        assertTrue(ir.contains("define i64 @nv_time_monotonic_ns"), "nv_time_monotonic_ns missing")
        assertTrue(ir.contains("define void @nv_time_sleep_ms"),    "nv_time_sleep_ms missing")
        assertTrue(ir.contains("@clock_gettime"), "clock_gettime not declared")
        assertTrue(ir.contains("@nanosleep"),     "nanosleep not declared")
    }

    @Test fun `time nowMs runs and returns positive value`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_time_now_ms") pub fn timeNowMs() → int
            fn main()
                let t = timeNowMs()
                if t > 0
                    println("positive")
                else
                    println("non-positive")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("positive", out)
    }
}
