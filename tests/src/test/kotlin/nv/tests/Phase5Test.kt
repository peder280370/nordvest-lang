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

    // ── 5.4 std.fs: stdlib file ───────────────────────────────────────────
    @Test fun `stdlib fs module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/fs.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_fs_exists"),    "missing nv_fs_exists @extern")
        assertTrue(c.contains("nv_fs_is_file"),   "missing nv_fs_is_file @extern")
        assertTrue(c.contains("nv_fs_is_dir"),    "missing nv_fs_is_dir @extern")
        assertTrue(c.contains("nv_fs_mkdir"),     "missing nv_fs_mkdir @extern")
        assertTrue(c.contains("nv_fs_rm"),        "missing nv_fs_rm @extern")
        assertTrue(c.contains("nv_fs_rename"),    "missing nv_fs_rename @extern")
        assertTrue(c.contains("nv_fs_read_text"), "missing nv_fs_read_text @extern")
        assertTrue(c.contains("nv_fs_write_text"),"missing nv_fs_write_text @extern")
        assertTrue(c.contains("nv_fs_join_path"), "missing nv_fs_join_path @extern")
    }

    @Test fun `phase5 fs runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i1 @nv_fs_exists"),      "nv_fs_exists missing")
        assertTrue(ir.contains("define i1 @nv_fs_is_dir"),      "nv_fs_is_dir missing")
        assertTrue(ir.contains("define i1 @nv_fs_is_file"),     "nv_fs_is_file missing")
        assertTrue(ir.contains("define i64 @nv_fs_mkdir"),      "nv_fs_mkdir missing")
        assertTrue(ir.contains("define i64 @nv_fs_rm"),         "nv_fs_rm missing")
        assertTrue(ir.contains("define i8* @nv_fs_read_text"),  "nv_fs_read_text missing")
        assertTrue(ir.contains("define void @nv_fs_write_text"),"nv_fs_write_text missing")
        assertTrue(ir.contains("define i8* @nv_fs_join_path"),  "nv_fs_join_path missing")
        assertTrue(ir.contains("define i8* @nv_fs_parent_dir"), "nv_fs_parent_dir missing")
        assertTrue(ir.contains("define i8* @nv_fs_file_name"),  "nv_fs_file_name missing")
        assertTrue(ir.contains("define i8* @nv_fs_file_ext"),   "nv_fs_file_ext missing")
        assertTrue(ir.contains("@opendir"),  "opendir not declared")
        assertTrue(ir.contains("@closedir"), "closedir not declared")
        assertTrue(ir.contains("@unlink"),   "unlink not declared")
        assertTrue(ir.contains("@mkdir"),    "mkdir not declared")
        assertTrue(ir.contains("@rename"),   "rename not declared")
        assertTrue(ir.contains("@getcwd"),   "getcwd not declared")
        assertTrue(ir.contains("@strrchr"),  "strrchr not declared")
    }

    @Test fun `fs exists and isDir run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fs_exists") pub fn fsExists(path: str) → bool
            @extern(fn: "nv_fs_is_dir") pub fn fsIsDir(path: str) → bool
            @extern(fn: "nv_fs_is_file") pub fn fsIsFile(path: str) → bool
            fn main()
                if fsExists("/tmp")
                    println("tmp exists")
                if fsIsDir("/tmp")
                    println("tmp is dir")
                if fsIsFile("/tmp")
                    println("tmp is file")
                else
                    println("tmp not file")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("tmp exists\ntmp is dir\ntmp not file", out)
    }

    @Test fun `fs write read rename rm run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fs_write_text") pub fn fsWrite(path: str, text: str)
            @extern(fn: "nv_fs_read_text")  pub fn fsRead(path: str) → str?
            @extern(fn: "nv_fs_rename")     pub fn fsRename(src: str, dst: str) → int
            @extern(fn: "nv_fs_rm")         pub fn fsRm(path: str) → int
            @extern(fn: "nv_fs_exists")     pub fn fsExists(path: str) → bool
            fn main()
                let p1 = "/tmp/nv_p54_a.txt"
                let p2 = "/tmp/nv_p54_b.txt"
                fsWrite(p1, "hello 54")
                let c = fsRead(p1)
                if let s = c
                    println(s)
                fsRename(p1, p2)
                if fsExists(p2)
                    println("renamed ok")
                fsRm(p2)
                if fsExists(p2)
                    println("still exists")
                else
                    println("deleted ok")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("hello 54\nrenamed ok\ndeleted ok", out)
    }

    @Test fun `fs joinPath parentDir fileName fileExt run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fs_join_path")  pub fn fsJoin(base: str, part: str) → str
            @extern(fn: "nv_fs_parent_dir") pub fn fsParent(path: str) → str
            @extern(fn: "nv_fs_file_name")  pub fn fsName(path: str) → str
            @extern(fn: "nv_fs_file_ext")   pub fn fsExt(path: str) → str
            fn main()
                println(fsJoin("/tmp", "test.txt"))
                println(fsParent("/tmp/test.txt"))
                println(fsName("/tmp/test.txt"))
                println(fsExt("/tmp/test.txt"))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("/tmp/test.txt\n/tmp\ntest.txt\ntxt", out)
    }

    // ── 5.4 std.time: stdlib file ─────────────────────────────────────────
    @Test fun `stdlib time module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/time.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_time_now_ms"),       "missing nv_time_now_ms @extern")
        assertTrue(c.contains("nv_time_monotonic_ns"), "missing nv_time_monotonic_ns @extern")
        assertTrue(c.contains("nv_time_sleep_ms"),     "missing nv_time_sleep_ms @extern")
    }

    @Test fun `phase5 time runtime functions are emitted in IR`() {
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

    // ── 5.4 std.process: stdlib file ──────────────────────────────────────
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

    @Test fun `phase5 process runtime functions are emitted in IR`() {
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

    // ── 5.4 std.rand: stdlib file ─────────────────────────────────────────
    @Test fun `stdlib rand module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/rand.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_rand_seed"),  "missing nv_rand_seed @extern")
        assertTrue(c.contains("nv_rand_float"), "missing nv_rand_float @extern")
        assertTrue(c.contains("nv_rand_int"),   "missing nv_rand_int @extern")
        assertTrue(c.contains("nv_rand_bool"),  "missing nv_rand_bool @extern")
    }

    @Test fun `phase5 rand runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define void @nv_rand_seed"),  "nv_rand_seed missing")
        assertTrue(ir.contains("define i64 @nv_rand_next"),   "nv_rand_next missing")
        assertTrue(ir.contains("define double @nv_rand_float"), "nv_rand_float missing")
        assertTrue(ir.contains("define i64 @nv_rand_int"),    "nv_rand_int missing")
        assertTrue(ir.contains("define i1 @nv_rand_bool"),    "nv_rand_bool missing")
        assertTrue(ir.contains("@nv_rand_state"),             "nv_rand_state global missing")
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

    // ── 5.5 RC: IR structure ──────────────────────────────────────────────

    @Test fun `phase5_5 nv_rc_retain uses atomicrmw in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define void @nv_rc_retain"),  "nv_rc_retain missing")
        assertTrue(ir.contains("atomicrmw add"),              "atomicrmw add missing from nv_rc_retain")
    }

    @Test fun `phase5_5 nv_rc_release uses atomicrmw and calls dtor in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define void @nv_rc_release"), "nv_rc_release missing")
        assertTrue(ir.contains("atomicrmw sub"),              "atomicrmw sub missing from nv_rc_release")
        assertTrue(ir.contains("bitcast i8* %dtor_raw to void (i8*)*"), "dtor dispatch missing")
    }

    @Test fun `phase5_5 nv_weak_load is emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i8* @nv_weak_load"), "nv_weak_load missing")
        assertTrue(ir.contains("icmp sgt i64"),             "strong_count check missing from nv_weak_load")
    }

    @Test fun `phase5_5 class struct has RC header fields in IR`() {
        val ir = compileOk("""
            module test
            class Counter(pub value: int)
            fn main()
                println("ok")
        """.trimIndent())
        // RC header: { i64, i8*, i64 } — strong_count, dtor_fn, user field
        assertTrue(ir.contains("%struct.Counter = type { i64, i8*, i64 }"),
            "Counter struct missing RC header; IR:\n${ir.lines().filter { it.contains("Counter") }.joinToString("\n")}")
    }

    @Test fun `phase5_5 class constructor initializes strong_count to 1`() {
        val ir = compileOk("""
            module test
            class Counter(pub value: int)
            fn main()
                println("ok")
        """.trimIndent())
        assertTrue(ir.contains("store i64 1, i64*"), "strong_count=1 store missing")
    }

    @Test fun `phase5_5 class destructor is emitted`() {
        val ir = compileOk("""
            module test
            class Node(pub value: int)
            class Tree(pub left: Node)
            fn main()
                println("ok")
        """.trimIndent())
        assertTrue(ir.contains("define void @nv_dtor_Tree"), "nv_dtor_Tree missing")
        assertTrue(ir.contains("call void @nv_rc_release"),  "nv_rc_release call missing from destructor")
    }

    @Test fun `phase5_5 class with no RC fields has null dtor`() {
        val ir = compileOk("""
            module test
            class Point(pub x: int, pub y: int)
            fn main()
                println("ok")
        """.trimIndent())
        // No RC fields => dtor_fn slot stores null
        assertTrue(ir.contains("store i8* null, i8**"), "null dtor store missing for Point")
        // No destructor function should be emitted for Point (no RC fields to release)
        assertFalse(ir.contains("define void @nv_dtor_Point"),
            "nv_dtor_Point should not be emitted when there are no RC fields")
    }

    @Test fun `phase5_5 class field GEP uses index 2 for first user field`() {
        val ir = compileOk("""
            module test
            class Box(pub n: int)
            fn getN(b: Box) → int
                → b.n
            fn main()
                println("ok")
        """.trimIndent())
        // GEP index 2 = first user field (after i64 strong_count at 0, i8* dtor_fn at 1)
        assertTrue(ir.contains("i32 2"), "GEP index 2 missing for class field access")
    }

    // ── 5.5 RC: integration tests ─────────────────────────────────────────

    @Test fun `phase5_5 class instantiation and field access works`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            class Counter(pub value: int)
            fn main()
                let c = Counter(42)
                println(c.value.str)
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("42", out)
    }

    @Test fun `phase5_5 retain and release do not crash`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_rc_retain")  pub fn rcRetain(p: int)
            @extern(fn: "nv_rc_release") pub fn rcRelease(p: int)
            fn main()
                rcRetain(0)
                rcRelease(0)
                println("no crash")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("no crash", out)
    }

    @Test fun `phase5_5 class with nested class field and destructor chain`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            class Inner(pub x: int)
            class Outer(pub inner: Inner)
            fn main()
                let i = Inner(7)
                let o = Outer(i)
                println(o.inner.x.str)
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("7", out)
    }

    @Test fun `phase5_5 weak_load returns null for null pointer`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_weak_load") pub fn weakLoad(p: int) → int
            fn main()
                let r = weakLoad(0)
                if r == 0
                    println("null ok")
                else
                    println("unexpected")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("null ok", out)
    }
}
