package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for CollectionsRuntime: std.collections operations (nv_arr_*, nv_map_*).
 */
class CollectionsRuntimeTest : NvCompilerTestBase() {


    @Test fun `stdlib collections module has typed operations`() {
        val f = File(projectDir(), "stdlib/std/collections.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_arr_push_i64"),       "missing nv_arr_push_i64 @extern")
        assertTrue(c.contains("nv_arr_push_str"),        "missing nv_arr_push_str @extern")
        assertTrue(c.contains("nv_arr_contains_i64"),    "missing nv_arr_contains_i64 @extern")
        assertTrue(c.contains("nv_arr_contains_str"),    "missing nv_arr_contains_str @extern")
        assertTrue(c.contains("nv_arr_index_of_i64"),    "missing nv_arr_index_of_i64 @extern")
        assertTrue(c.contains("nv_arr_index_of_str"),    "missing nv_arr_index_of_str @extern")
        assertTrue(c.contains("nv_map_new"),             "missing nv_map_new @extern")
        assertTrue(c.contains("nv_map_get_str"),         "missing nv_map_get_str @extern")
        assertTrue(c.contains("nv_map_set_str"),         "missing nv_map_set_str @extern")
        assertTrue(c.contains("nv_map_has_str"),         "missing nv_map_has_str @extern")
    }

    @Test fun `collections runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i8* @nv_arr_push_i64"),     "nv_arr_push_i64 missing")
        assertTrue(ir.contains("define i8* @nv_arr_push_str"),     "nv_arr_push_str missing")
        assertTrue(ir.contains("define i1 @nv_arr_contains_i64"),  "nv_arr_contains_i64 missing")
        assertTrue(ir.contains("define i1 @nv_arr_contains_str"),  "nv_arr_contains_str missing")
        assertTrue(ir.contains("define i64 @nv_arr_index_of_i64"), "nv_arr_index_of_i64 missing")
        assertTrue(ir.contains("define i64 @nv_arr_index_of_str"), "nv_arr_index_of_str missing")
        assertTrue(ir.contains("define i8* @nv_map_new"),          "nv_map_new missing")
        assertTrue(ir.contains("define i8* @nv_map_get_str"),      "nv_map_get_str missing")
        assertTrue(ir.contains("define i8* @nv_map_set_str"),      "nv_map_set_str missing")
        assertTrue(ir.contains("define i1 @nv_map_has_str"),       "nv_map_has_str missing")
    }

    @Test fun `program with @extern list operations compiles`() {
        val ir = compileOk("""
            module test
            @extern(fn: "nv_arr_push_i64")      pub fn listPushInt(list: [int], item: int) → [int]
            @extern(fn: "nv_arr_contains_i64")  pub fn listContainsInt(list: [int], item: int) → bool
            fn main()
                var xs = [1, 2, 3]
                xs = listPushInt(xs, 4)
                if listContainsInt(xs, 4)
                    println("found 4")
        """.trimIndent())
        assertTrue(ir.contains("@nv_arr_push_i64"))
        assertTrue(ir.contains("@nv_arr_contains_i64"))
    }

    @Test fun `list push and contains run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_arr_push_i64")      pub fn listPushInt(list: [int], v: int) → [int]
            @extern(fn: "nv_arr_contains_i64")  pub fn listHasInt(list: [int], v: int) → bool
            @extern(fn: "nv_arr_index_of_i64")  pub fn listIdxInt(list: [int], v: int) → int
            fn main()
                var xs = [10, 20, 30]
                xs = listPushInt(xs, 40)
                if listHasInt(xs, 40)
                    println("contains 40: yes")
                else
                    println("contains 40: no")
                if listHasInt(xs, 99)
                    println("contains 99: yes")
                else
                    println("contains 99: no")
                println(listIdxInt(xs, 20))
                println(listIdxInt(xs, 99))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("contains 40: yes\ncontains 99: no\n1\n-1", out)
    }

    @Test fun `str list push and contains run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_arr_push_str")      pub fn listPushStr(list: [str], v: str) → [str]
            @extern(fn: "nv_arr_contains_str")  pub fn listHasStr(list: [str], v: str) → bool
            @extern(fn: "nv_arr_index_of_str")  pub fn listIdxStr(list: [str], v: str) → int
            fn main()
                var ws = ["alpha", "beta"]
                ws = listPushStr(ws, "gamma")
                if listHasStr(ws, "gamma")
                    println("found gamma")
                println(listIdxStr(ws, "beta"))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("found gamma\n1", out)
    }

    @Test fun `map new get set has run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_map_new")     pub fn mapNew() → [str: str]
            @extern(fn: "nv_map_set_str") pub fn mapSet(m: [str: str], k: str, v: str) → [str: str]
            @extern(fn: "nv_map_get_str") pub fn mapGet(m: [str: str], k: str) → str?
            @extern(fn: "nv_map_has_str") pub fn mapHas(m: [str: str], k: str) → bool
            @extern(fn: "nv_map_len")     pub fn mapLen(m: [str: str]) → int
            fn main()
                var m = mapNew()
                m = mapSet(m, "name", "Nordvest")
                m = mapSet(m, "type", "compiled")
                println(mapLen(m))
                if mapHas(m, "name")
                    println("has name")
                let v = mapGet(m, "name")
                if let s = v
                    println(s)
                if mapHas(m, "missing")
                    println("has missing")
                else
                    println("no missing")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("2\nhas name\nNordvest\nno missing", out)
    }

    @Test fun `listFirstInt and listLastInt return correct values`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            import std.collections
            fn main()
                println(listFirstInt([7, 8, 9]))
                println(listLastInt([7, 8, 9]))
                println(listFirstInt([42]))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("7\n9\n42", out)
    }

    @Test fun `listSortInt returns sorted array`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            import std.collections
            fn main()
                let s = listSortInt([5, 1, 4, 2, 3])
                println(s[0])
                println(s[4])
                println(len(s))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("1\n5\n5", out)
    }

    @Test fun `listReverseInt returns reversed array`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            import std.collections
            fn main()
                let r = listReverseInt([1, 2, 3, 4, 5])
                println(r[0])
                println(r[4])
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("5\n1", out)
    }

    @Test fun `listSliceInt returns correct sub-array`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            import std.collections
            fn main()
                let sl = listSliceInt([10, 20, 30, 40, 50], 1, 4)
                println(len(sl))
                println(sl[0])
                println(sl[2])
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("3\n20\n40", out)
    }

    @Test fun `stdlib functions work via import without re-declaration`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            import std.collections
            fn main()
                var xs = [1, 2, 3]
                xs = listAppendInt(xs, 4)
                println(listContainsInt(xs, 4))
                println(listIndexOfInt(xs, 2))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("true\n1", out)
    }
}
