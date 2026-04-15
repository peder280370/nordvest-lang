package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for LogRuntime: std.log level-filtered logging (nv_log_*).
 */
class LogRuntimeTest : NvCompilerTestBase() {

    @Test fun `stdlib log module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/log.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_log_debug"),             "missing nv_log_debug @extern")
        assertTrue(c.contains("nv_log_info"),              "missing nv_log_info @extern")
        assertTrue(c.contains("nv_log_warn"),              "missing nv_log_warn @extern")
        assertTrue(c.contains("nv_log_error"),             "missing nv_log_error @extern")
        assertTrue(c.contains("nv_log_fatal"),             "missing nv_log_fatal @extern")
        assertTrue(c.contains("nv_log_set_level"),         "missing nv_log_set_level @extern")
        assertTrue(c.contains("nv_log_flush"),             "missing nv_log_flush @extern")
        assertTrue(c.contains("nv_log_warnWith"),          "missing nv_log_warnWith @extern")
        assertTrue(c.contains("nv_log_errorWith"),         "missing nv_log_errorWith @extern")
        assertTrue(c.contains("nv_log_fatalWith"),         "missing nv_log_fatalWith @extern")
        assertTrue(c.contains("nv_log_set_trace_enabled"), "missing nv_log_set_trace_enabled @extern")
    }

    @Test fun `log info prints with prefix`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_info") pub fn info(msg: str)
            fn main()
                info("hello world")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[INFO] hello world", out)
    }

    @Test fun `log debug suppressed at default info level`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_debug") pub fn debug(msg: str)
            @extern(fn: "nv_log_info")  pub fn info(msg: str)
            fn main()
                debug("hidden")
                info("visible")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[INFO] visible", out)
    }

    @Test fun `log setLevel enables debug output`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_debug")     pub fn debug(msg: str)
            @extern(fn: "nv_log_set_level") pub fn setLevel(level: str)
            fn main()
                setLevel("debug")
                debug("now visible")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[DEBUG] now visible", out)
    }

    @Test fun `log setLevel warn suppresses info`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_info")      pub fn info(msg: str)
            @extern(fn: "nv_log_warn")      pub fn warn(msg: str)
            @extern(fn: "nv_log_set_level") pub fn setLevel(level: str)
            fn main()
                setLevel("warn")
                info("suppressed")
                warn("shown")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[WARN] shown", out)
    }

    @Test fun `log error always prints at default level`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_error") pub fn error(msg: str)
            fn main()
                error("something failed")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[ERROR] something failed", out)
    }

    @Test fun `log flush compiles and runs without error`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_info")  pub fn info(msg: str)
            @extern(fn: "nv_log_flush") pub fn flush()
            fn main()
                info("before flush")
                flush()
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[INFO] before flush", out)
    }

    @Test fun `warnWith prints msg colon cause`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_warnWith") pub fn warnWith(msg: str, cause: str)
            fn main()
                warnWith("disk usage high", "92% full")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[WARN] disk usage high: 92% full", out)
    }

    @Test fun `errorWith prints msg colon cause`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_errorWith") pub fn errorWith(msg: str, cause: str)
            fn main()
                errorWith("write failed", "disk full")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[ERROR] write failed: disk full", out)
    }

    @Test fun `warnWith suppressed when level is error`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_warnWith")    pub fn warnWith(msg: str, cause: str)
            @extern(fn: "nv_log_error")        pub fn error(msg: str)
            @extern(fn: "nv_log_set_level")    pub fn setLevel(level: str)
            fn main()
                setLevel("error")
                warnWith("suppressed", "level too high")
                error("visible")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[ERROR] visible", out)
    }

    @Test fun `setTraceEnabled does not crash`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_log_errorWith")        pub fn errorWith(msg: str, cause: str)
            @extern(fn: "nv_log_set_trace_enabled") pub fn setTraceEnabled(enabled: bool)
            fn main()
                setTraceEnabled(false)
                errorWith("no trace", "disabled")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("[ERROR] no trace: disabled", out)
    }
}
