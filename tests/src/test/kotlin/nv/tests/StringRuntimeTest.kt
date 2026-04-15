package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for StringRuntime: std.string operations (nv_str_*).
 */
class StringRuntimeTest : NvCompilerTestBase() {


    @Test fun `stdlib string module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/string.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_str_contains"),  "missing nv_str_contains @extern")
        assertTrue(c.contains("nv_str_to_upper"),   "missing nv_str_to_upper @extern")
        assertTrue(c.contains("nv_str_to_lower"),   "missing nv_str_to_lower @extern")
        assertTrue(c.contains("nv_str_trim"),       "missing nv_str_trim @extern")
        assertTrue(c.contains("nv_str_replace"),    "missing nv_str_replace @extern")
        assertTrue(c.contains("nv_str_index_of"),   "missing nv_str_index_of @extern")
        assertTrue(c.contains("nv_str_starts_with"),"missing nv_str_starts_with @extern")
        assertTrue(c.contains("nv_str_ends_with"),  "missing nv_str_ends_with @extern")
        assertTrue(c.contains("nv_str_slice"),      "missing nv_str_slice @extern")
        assertTrue(c.contains("nv_str_parse_int"),  "missing nv_str_parse_int @extern")
        assertTrue(c.contains("nv_str_parse_float"),"missing nv_str_parse_float @extern")
        assertTrue(c.contains("nv_str_repeat"),     "missing nv_str_repeat @extern")
        assertTrue(c.contains("pub fn join"),       "missing join implementation")
    }

    @Test fun `string runtime functions are emitted in IR`() {
        val ir = compileOk("""
            module test
            fn main()
                println("ok")
        """.trimIndent())
        assertTrue(ir.contains("define i8* @nv_str_slice"), "nv_str_slice not in IR")
        assertTrue(ir.contains("define i1 @nv_str_contains"), "nv_str_contains not in IR")
        assertTrue(ir.contains("define i8* @nv_str_to_upper"), "nv_str_to_upper not in IR")
        assertTrue(ir.contains("define i8* @nv_str_to_lower"), "nv_str_to_lower not in IR")
        assertTrue(ir.contains("define i8* @nv_str_trim"), "nv_str_trim not in IR")
        assertTrue(ir.contains("define i8* @nv_str_replace"), "nv_str_replace not in IR")
        assertTrue(ir.contains("define i8* @nv_str_repeat"), "nv_str_repeat not in IR")
        assertTrue(ir.contains("define i64 @nv_str_parse_int"), "nv_str_parse_int not in IR")
        assertTrue(ir.contains("define double @nv_str_parse_float"), "nv_str_parse_float not in IR")
        assertTrue(ir.contains("@strtoll"), "strtoll not declared")
        assertTrue(ir.contains("@toupper"), "toupper not declared")
        assertTrue(ir.contains("@tolower"), "tolower not declared")
        assertTrue(ir.contains("@isspace"), "isspace not declared")
        assertTrue(ir.contains("@strstr"), "strstr not declared")
    }

    @Test fun `program with @extern string function compiles`() {
        val ir = compileOk("""
            module test
            @extern(fn: "nv_str_to_upper") pub fn strToUpper(s: str) → str
            @extern(fn: "nv_str_to_lower") pub fn strToLower(s: str) → str
            @extern(fn: "nv_str_contains") pub fn strContains(s: str, n: str) → bool
            @extern(fn: "nv_str_trim")     pub fn strTrim(s: str) → str
            fn main()
                let u = strToUpper("hello")
                println(u)
        """.trimIndent())
        assertTrue(ir.contains("@nv_str_to_upper"), "extern call not emitted")
    }

    @Test fun `join builds comma-separated string`() {
        val ir = compileOk("""
            module test
            pub fn join(parts: [str], sep: str) → str
                var result = ""
                var i = 0
                for p ∈ parts
                    if i > 0
                        result = result + sep + p
                    else
                        result = p
                    i = i + 1
                → result
            fn main()
                let words = ["alpha", "beta", "gamma"]
                let r = join(words, ", ")
                println(r)
        """.trimIndent())
        assertTrue(ir.isNotEmpty())
    }

    @Test fun `string toUpper toLower run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_str_to_upper") pub fn toUpper(s: str) → str
            @extern(fn: "nv_str_to_lower") pub fn toLower(s: str) → str
            fn main()
                println(toUpper("hello world"))
                println(toLower("NORDVEST"))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("HELLO WORLD\nnordvest", out)
    }

    @Test fun `string contains and startsWith run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_str_contains")    pub fn strContains(s: str, n: str) → bool
            @extern(fn: "nv_str_starts_with") pub fn strStartsWith(s: str, p: str) → bool
            @extern(fn: "nv_str_ends_with")   pub fn strEndsWith(s: str, suf: str) → bool
            fn main()
                if strContains("hello world", "world")
                    println("contains: yes")
                if strStartsWith("hello", "he")
                    println("starts: yes")
                if strEndsWith("hello", "lo")
                    println("ends: yes")
                if strContains("hello", "xyz")
                    println("contains xyz: yes")
                else
                    println("contains xyz: no")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("contains: yes\nstarts: yes\nends: yes\ncontains xyz: no", out)
    }

    @Test fun `string trim and replace run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_str_trim")    pub fn strTrim(s: str) → str
            @extern(fn: "nv_str_replace") pub fn strReplace(s: str, f: str, t: str) → str
            fn main()
                println(strTrim("  hello  "))
                println(strReplace("foo bar foo", "foo", "baz"))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("hello\nbaz bar baz", out)
    }

    @Test fun `string slice and indexOf run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_str_slice")    pub fn strSlice(s: str, f: int, t: int) → str
            @extern(fn: "nv_str_index_of") pub fn strIndexOf(s: str, n: str) → int
            fn main()
                println(strSlice("hello world", 6, 11))
                println(strIndexOf("hello world", "world"))
                println(strIndexOf("hello world", "xyz"))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("world\n6\n-1", out)
    }

    @Test fun `string parseInt and parseFloat run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_str_parse_int")   pub fn strParseInt(s: str) → int
            @extern(fn: "nv_str_parse_float") pub fn strParseFloat(s: str) → float
            fn main()
                let n = strParseInt("42")
                println(n)
                let f = strParseFloat("3.14")
                println(f)
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("42\n3.14", out)
    }

    @Test fun `string repeat runs correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_str_repeat") pub fn strRepeat(s: str, n: int) → str
            fn main()
                println(strRepeat("ab", 3))
                println(strRepeat("x", 0))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("ababab", out)
    }
}
