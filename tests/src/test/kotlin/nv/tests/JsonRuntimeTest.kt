package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for JsonRuntime: std.json parse, query, value extraction, and construction.
 */
class JsonRuntimeTest : NvCompilerTestBase() {

    @Test fun `stdlib json module has @extern bindings`() {
        val f = File(projectDir(), "stdlib/std/json.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_json_parse"),        "missing nv_json_parse @extern")
        assertTrue(c.contains("nv_json_is_null"),      "missing nv_json_is_null @extern")
        assertTrue(c.contains("nv_json_get_field"),    "missing nv_json_get_field @extern")
        assertTrue(c.contains("nv_json_get_index"),    "missing nv_json_get_index @extern")
        assertTrue(c.contains("nv_json_array_len"),    "missing nv_json_array_len @extern")
        assertTrue(c.contains("nv_json_str_value"),    "missing nv_json_str_value @extern")
        assertTrue(c.contains("nv_json_int_value"),    "missing nv_json_int_value @extern")
        assertTrue(c.contains("nv_json_float_value"),  "missing nv_json_float_value @extern")
        assertTrue(c.contains("nv_json_bool_value"),   "missing nv_json_bool_value @extern")
        assertTrue(c.contains("nv_json_stringify"),    "missing nv_json_stringify @extern")
        assertTrue(c.contains("nv_json_make_string"),  "missing nv_json_make_string @extern")
        assertTrue(c.contains("nv_json_make_int"),     "missing nv_json_make_int @extern")
        assertTrue(c.contains("nv_json_make_float"),   "missing nv_json_make_float @extern")
        assertTrue(c.contains("nv_json_make_bool"),    "missing nv_json_make_bool @extern")
        assertTrue(c.contains("nv_json_make_null"),    "missing nv_json_make_null @extern")
    }

    @Test fun `json runtime functions are defined in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i8* @nv_json_parse"),       "nv_json_parse missing")
        assertTrue(ir.contains("define i1 @nv_json_is_null"),      "nv_json_is_null missing")
        assertTrue(ir.contains("define i8* @nv_json_get_field"),   "nv_json_get_field missing")
        assertTrue(ir.contains("define i8* @nv_json_get_index"),   "nv_json_get_index missing")
        assertTrue(ir.contains("define i64 @nv_json_array_len"),   "nv_json_array_len missing")
        assertTrue(ir.contains("define i8* @nv_json_str_value"),   "nv_json_str_value missing")
        assertTrue(ir.contains("define i64 @nv_json_int_value"),   "nv_json_int_value missing")
        assertTrue(ir.contains("define double @nv_json_float_value"), "nv_json_float_value missing")
        assertTrue(ir.contains("define i1 @nv_json_bool_value"),   "nv_json_bool_value missing")
        assertTrue(ir.contains("define i8* @nv_json_stringify"),   "nv_json_stringify missing")
        assertTrue(ir.contains("define i8* @nv_json_make_string"), "nv_json_make_string missing")
        assertTrue(ir.contains("define i8* @nv_json_make_int"),    "nv_json_make_int missing")
        assertTrue(ir.contains("define i8* @nv_json_make_float"),  "nv_json_make_float missing")
        assertTrue(ir.contains("define i8* @nv_json_make_bool"),   "nv_json_make_bool missing")
        assertTrue(ir.contains("define i8* @nv_json_make_null"),   "nv_json_make_null missing")
        // helpers
        assertTrue(ir.contains("define i8* @nv_json_skip_ws"),      "nv_json_skip_ws missing")
        assertTrue(ir.contains("define i8* @nv_json_skip_string"),  "nv_json_skip_string missing")
        assertTrue(ir.contains("define i8* @nv_json_skip_value"),   "nv_json_skip_value missing")
        assertTrue(ir.contains("define i8* @nv_json_substr"),       "nv_json_substr missing")
    }

    @Test fun `is_null returns true for JSON null`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_is_null") pub fn isNull(v: str) → bool
            fn main()
                if isNull("null")
                    println("yes")
                else
                    println("no")
        """.trimIndent())
        assertEquals("yes", runProgram(ir))
    }

    @Test fun `is_null returns false for non-null JSON`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_is_null") pub fn isNull(v: str) → bool
            fn main()
                if isNull("42")
                    println("yes")
                else
                    println("no")
        """.trimIndent())
        assertEquals("no", runProgram(ir))
    }

    @Test fun `get_field returns value for existing key`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_get_field") pub fn getField(obj: str, key: str) → str
            fn main()
                let v = getField("\{\"name\":\"Alice\"}", "name")
                println(v)
        """.trimIndent())
        assertEquals("\"Alice\"", runProgram(ir))
    }

    @Test fun `get_field returns nil for missing key`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_get_field") pub fn getField(obj: str, key: str) → str?
            fn main()
                let v = getField("\{\"x\":1}", "name")
                println(v ?? "nil")
        """.trimIndent())
        assertEquals("nil", runProgram(ir))
    }

    @Test fun `get_field returns integer value`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_get_field") pub fn getField(obj: str, key: str) → str
            fn main()
                let v = getField("\{\"count\":42}", "count")
                println(v)
        """.trimIndent())
        assertEquals("42", runProgram(ir))
    }

    @Test fun `get_field handles whitespace around colon`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_get_field") pub fn getField(obj: str, key: str) → str
            fn main()
                let v = getField("\{\"key\" : \"value\"}", "key")
                println(v)
        """.trimIndent())
        assertEquals("\"value\"", runProgram(ir))
    }

    @Test fun `get_index returns element at index 0`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_get_index") pub fn getIndex(arr: str, idx: int) → str
            fn main()
                let v = getIndex("[10,20,30]", 0)
                println(v)
        """.trimIndent())
        assertEquals("10", runProgram(ir))
    }

    @Test fun `get_index returns element at index 2`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_get_index") pub fn getIndex(arr: str, idx: int) → str
            fn main()
                let v = getIndex("[10,20,30]", 2)
                println(v)
        """.trimIndent())
        assertEquals("30", runProgram(ir))
    }

    @Test fun `get_index returns nil for out-of-bounds`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_get_index") pub fn getIndex(arr: str, idx: int) → str?
            fn main()
                let v = getIndex("[1,2]", 5)
                println(v ?? "nil")
        """.trimIndent())
        assertEquals("nil", runProgram(ir))
    }

    @Test fun `array_len counts elements`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_array_len") pub fn arrayLen(arr: str) → int
            fn main()
                println(arrayLen("[1,2,3,4,5]"))
        """.trimIndent())
        assertEquals("5", runProgram(ir))
    }

    @Test fun `array_len returns 0 for empty array`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_array_len") pub fn arrayLen(arr: str) → int
            fn main()
                println(arrayLen("[]"))
        """.trimIndent())
        assertEquals("0", runProgram(ir))
    }

    @Test fun `str_value strips quotes from JSON string`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_str_value") pub fn strValue(v: str) → str
            fn main()
                println(strValue("\"hello\""))
        """.trimIndent())
        // \"hello\" in Nordvest string resolves to "hello" (with double-quote chars)
        // strValue strips the surrounding quotes, leaving: hello
        assertEquals("hello", runProgram(ir))
    }

    @Test fun `int_value parses JSON integer`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_int_value") pub fn intValue(v: str) → int
            fn main()
                println(intValue("42"))
        """.trimIndent())
        assertEquals("42", runProgram(ir))
    }

    @Test fun `bool_value returns true for JSON true`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_bool_value") pub fn boolValue(v: str) → bool
            fn main()
                if boolValue("true")
                    println("yes")
                else
                    println("no")
        """.trimIndent())
        assertEquals("yes", runProgram(ir))
    }

    @Test fun `bool_value returns false for JSON false`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_bool_value") pub fn boolValue(v: str) → bool
            fn main()
                if boolValue("false")
                    println("yes")
                else
                    println("no")
        """.trimIndent())
        assertEquals("no", runProgram(ir))
    }

    @Test fun `make_string wraps value in JSON quotes`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_make_string") pub fn makeString(s: str) → str
            fn main()
                println(makeString("hello"))
        """.trimIndent())
        assertEquals("\"hello\"", runProgram(ir))
    }

    @Test fun `make_string escapes internal quotes`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_make_string") pub fn makeString(s: str) → str
            fn main()
                println(makeString("say \"hi\""))
        """.trimIndent())
        // Input "say \"hi\"" resolves to: say "hi"
        // makeString wraps and escapes: "say \"hi\""
        assertEquals("\"say \\\"hi\\\"\"", runProgram(ir))
    }

    @Test fun `make_int formats integer as JSON`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_make_int") pub fn makeInt(n: int) → str
            fn main()
                println(makeInt(42))
        """.trimIndent())
        assertEquals("42", runProgram(ir))
    }

    @Test fun `make_bool returns JSON true`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_make_bool") pub fn makeBool(b: bool) → str
            fn main()
                println(makeBool(true))
        """.trimIndent())
        assertEquals("true", runProgram(ir))
    }

    @Test fun `make_bool returns JSON false`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_make_bool") pub fn makeBool(b: bool) → str
            fn main()
                println(makeBool(false))
        """.trimIndent())
        assertEquals("false", runProgram(ir))
    }

    @Test fun `make_null returns JSON null literal`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_make_null") pub fn makeNull() → str
            fn main()
                println(makeNull())
        """.trimIndent())
        assertEquals("null", runProgram(ir))
    }

    @Test fun `stringify returns JSON input unchanged`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_json_stringify") pub fn stringify(v: str) → str
            fn main()
                println(stringify("\{\"key\":1}"))
        """.trimIndent())
        assertEquals("{\"key\":1}", runProgram(ir))
    }
}
