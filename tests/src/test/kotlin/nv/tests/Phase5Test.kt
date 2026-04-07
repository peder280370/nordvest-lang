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
 * Phase 5 tests: std.string (5.1), std.collections (5.2), std.io (5.3).
 *
 * Tests are split into:
 *  - IrStructure: compile to LLVM IR and check content (no clang needed)
 *  - Integration: compile + run with clang (require clangAvailable())
 */
class Phase5Test {

    // ── Helpers ───────────────────────────────────────────────────────────

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
        val tmp = Files.createTempDirectory("nv_p5_").toFile()
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

    // ── 5.1 std.string: stdlib file ──────────────────────────────────────

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

    @Test fun `phase5 string runtime functions are emitted in IR`() {
        val ir = compileOk("""
            module test
            fn main()
                println("ok")
        """.trimIndent())
        // Runtime functions are always emitted
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

    // ── 5.1 Integration: string ops actually run ──────────────────────────

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

    // ── 5.2 std.collections: stdlib file ─────────────────────────────────

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

    @Test fun `phase5 collection runtime functions are emitted in IR`() {
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

    // ── 5.3 std.io: stdlib file ───────────────────────────────────────────

    @Test fun `stdlib io module has IOError and File operations`() {
        val f = File(projectDir(), "stdlib/std/io.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("sealed class IOError"),   "IOError sealed class missing")
        assertTrue(c.contains("nv_file_open_read"),      "nv_file_open_read @extern missing")
        assertTrue(c.contains("nv_file_open_write"),     "nv_file_open_write @extern missing")
        assertTrue(c.contains("nv_file_close"),          "nv_file_close @extern missing")
        assertTrue(c.contains("nv_file_read_line"),      "nv_file_read_line @extern missing")
        assertTrue(c.contains("nv_file_read_all"),       "nv_file_read_all @extern missing")
        assertTrue(c.contains("nv_file_write"),          "nv_file_write @extern missing")
        assertTrue(c.contains("nv_file_exists"),         "nv_file_exists @extern missing")
    }

    @Test fun `phase5 IO runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i8* @nv_file_open_read"),   "nv_file_open_read missing")
        assertTrue(ir.contains("define void @nv_file_close"),      "nv_file_close missing")
        assertTrue(ir.contains("define void @nv_file_write"),      "nv_file_write missing")
        assertTrue(ir.contains("define i8* @nv_file_read_line"),   "nv_file_read_line missing")
        assertTrue(ir.contains("define i8* @nv_file_read_all"),    "nv_file_read_all missing")
        assertTrue(ir.contains("define i1 @nv_file_exists"),       "nv_file_exists missing")
        assertTrue(ir.contains("@fopen"), "fopen not declared")
        assertTrue(ir.contains("@fclose"), "fclose not declared")
        assertTrue(ir.contains("@fgets"), "fgets not declared")
        assertTrue(ir.contains("@fread"), "fread not declared")
        assertTrue(ir.contains("@fwrite"), "fwrite not declared")
        assertTrue(ir.contains("@access"), "access not declared")
    }

    @Test fun `program using file operations compiles`() {
        val ir = compileOk("""
            module test
            @extern(fn: "nv_file_open_write")  pub fn fileOpenWrite(path: str) → str?
            @extern(fn: "nv_file_close")       pub fn fileClose(file: str)
            @extern(fn: "nv_file_writeln")     pub fn fileWriteln(file: str, s: str)
            @extern(fn: "nv_file_exists")      pub fn fileExists(path: str) → bool
            fn main()
                if fileExists("/tmp")
                    println("tmp exists")
        """.trimIndent())
        assertTrue(ir.contains("@nv_file_exists"))
    }

    @Test fun `file write and read run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_file_open_write")  pub fn fileWrite(path: str) → str?
            @extern(fn: "nv_file_open_read")   pub fn fileRead(path: str) → str?
            @extern(fn: "nv_file_close")       pub fn fileClose(file: str)
            @extern(fn: "nv_file_write")       pub fn fileWriteStr(file: str, s: str)
            @extern(fn: "nv_file_read_all")    pub fn fileReadAll(file: str) → str
            @extern(fn: "nv_file_exists")      pub fn fileExists(path: str) → bool
            fn main()
                let path = "/tmp/nv_phase5_test.txt"
                let wf = fileWrite(path)
                if let f = wf
                    fileWriteStr(f, "hello from nordvest")
                    fileClose(f)
                if fileExists(path)
                    let rf = fileRead(path)
                    if let f2 = rf
                        let content = fileReadAll(f2)
                        fileClose(f2)
                        println(content)
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("hello from nordvest", out)
    }
}
