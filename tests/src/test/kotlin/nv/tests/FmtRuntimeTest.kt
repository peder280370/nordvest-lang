package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for FmtRuntime: std.fmt formatting operations (nv_fmt_*).
 */
class FmtRuntimeTest : NvCompilerTestBase() {


    @Test fun `stdlib fmt module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/fmt.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_fmt_int"),       "missing nv_fmt_int @extern")
        assertTrue(c.contains("nv_fmt_float"),     "missing nv_fmt_float @extern")
        assertTrue(c.contains("nv_fmt_truncate"),  "missing nv_fmt_truncate @extern")
        assertTrue(c.contains("nv_fmt_file_size"), "missing nv_fmt_file_size @extern")
        assertTrue(c.contains("nv_fmt_duration"),  "missing nv_fmt_duration @extern")
        assertTrue(c.contains("nv_fmt_thousands"), "missing nv_fmt_thousands @extern")
    }

    @Test fun `fmt formatInt decimal with padding`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fmt_int") pub fn formatInt(n: int, width: int, padChar: str, radix: int) → str
            fn main()
                println(formatInt(42, 5, "0", 10))
                println(formatInt(-7, 0, " ", 10))
                println(formatInt(255, 0, "0", 16))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("00042\n-7\nff", out)
    }

    @Test fun `fmt truncate shortens long string`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fmt_truncate") pub fn truncate(s: str, width: int, suffix: str) → str
            fn main()
                println(truncate("hello world", 8, "..."))
                println(truncate("hi", 8, "..."))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("hello...\nhi", out)
    }

    @Test fun `fmt fileSize formats bytes correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fmt_file_size") pub fn fileSize(bytes: int) → str
            fn main()
                println(fileSize(512))
                println(fileSize(2048))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("512 B\n2.0 KB", out)
    }

    @Test fun `fmt duration formats milliseconds correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fmt_duration") pub fn duration(ms: int) → str
            fn main()
                println(duration(500))
                println(duration(2000))
                println(duration(125000))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("500ms\n2s\n2m 5s", out)
    }

    @Test fun `fmt thousands inserts separators`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fmt_thousands") pub fn thousands(n: int, sep: str) → str
            fn main()
                println(thousands(1234567, ","))
                println(thousands(1000, ","))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("1,234,567\n1,000", out)
    }
}
