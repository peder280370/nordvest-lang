package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for RegexRuntime: std.regex compile, match, find, replace, split.
 */
class RegexRuntimeTest : NvCompilerTestBase() {

    @Test fun `stdlib regex module has @extern bindings`() {
        val f = File(projectDir(), "stdlib/std/regex.nv")
        assertTrue(f.exists(), "stdlib/std/regex.nv missing")
        val c = f.readText()
        assertTrue(c.contains("nv_regex_compile"),     "missing nv_regex_compile")
        assertTrue(c.contains("nv_regex_matches"),     "missing nv_regex_matches")
        assertTrue(c.contains("nv_regex_contains"),    "missing nv_regex_contains")
        assertTrue(c.contains("nv_regex_find"),        "missing nv_regex_find")
        assertTrue(c.contains("nv_regex_find_all"),    "missing nv_regex_find_all")
        assertTrue(c.contains("nv_regex_replace"),     "missing nv_regex_replace")
        assertTrue(c.contains("nv_regex_replace_all"), "missing nv_regex_replace_all")
        assertTrue(c.contains("nv_regex_split"),       "missing nv_regex_split")
        assertTrue(c.contains("nv_match_value"),       "missing nv_match_value")
        assertTrue(c.contains("nv_match_start"),       "missing nv_match_start")
        assertTrue(c.contains("nv_match_end"),         "missing nv_match_end")
        assertTrue(c.contains("nv_match_group_idx"),   "missing nv_match_group_idx")
        assertTrue(c.contains("nv_match_group_name"),  "missing nv_match_group_name")
    }

    @Test fun `regex runtime functions are defined in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i8* @nv_regex_compile"),     "nv_regex_compile missing")
        assertTrue(ir.contains("define void @nv_regex_free"),       "nv_regex_free missing")
        assertTrue(ir.contains("define i1 @nv_regex_matches"),      "nv_regex_matches missing")
        assertTrue(ir.contains("define i1 @nv_regex_contains"),     "nv_regex_contains missing")
        assertTrue(ir.contains("define i8* @nv_regex_find"),        "nv_regex_find missing")
        assertTrue(ir.contains("define i8* @nv_regex_find_all"),    "nv_regex_find_all missing")
        assertTrue(ir.contains("define i8* @nv_regex_replace"),     "nv_regex_replace missing")
        assertTrue(ir.contains("define i8* @nv_regex_replace_all"), "nv_regex_replace_all missing")
        assertTrue(ir.contains("define i8* @nv_regex_split"),       "nv_regex_split missing")
        assertTrue(ir.contains("define i8* @nv_match_value"),       "nv_match_value missing")
        assertTrue(ir.contains("define i64 @nv_match_start"),       "nv_match_start missing")
        assertTrue(ir.contains("define i64 @nv_match_end"),         "nv_match_end missing")
        assertTrue(ir.contains("define i8* @nv_match_group_idx"),   "nv_match_group_idx missing")
        assertTrue(ir.contains("define i8* @nv_match_group_name"),  "nv_match_group_name missing")
        // helpers
        assertTrue(ir.contains("define i8* @nv_regex_preproc"),     "nv_regex_preproc missing")
        assertTrue(ir.contains("define i8* @nv_regex_substr"),      "nv_regex_substr missing")
        assertTrue(ir.contains("define i8* @nv_regex_build_match"), "nv_regex_build_match missing")
        assertTrue(ir.contains("define i8* @nv_regex_expand_repl"), "nv_regex_expand_repl missing")
        assertTrue(ir.contains("define void @nv_match_free"),       "nv_match_free missing")
        // POSIX declares
        assertTrue(ir.contains("declare i32  @regcomp"),  "regcomp declare missing")
        assertTrue(ir.contains("declare i32  @regexec"),  "regexec declare missing")
        assertTrue(ir.contains("declare void @regfree"),  "regfree declare missing")
        assertTrue(ir.contains("declare i64  @regerror"), "regerror declare missing")
    }

    // ── compile ──────────────────────────────────────────────────────────────

    @Test fun `compile returns Ok for valid pattern`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(pat: str) → Result<str>
            fn main()
                let r = compile("[a-z]+")
                match r
                    Ok(_):  → println("ok")
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("ok", runProgram(ir))
    }

    @Test fun `compile returns Err for invalid pattern`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(pat: str) → Result<str>
            fn main()
                let r = compile("[unclosed")
                match r
                    Ok(_):  → println("ok")
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("err", runProgram(ir))
    }

    // ── matches ──────────────────────────────────────────────────────────────

    @Test fun `matches returns true for full match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_matches") pub fn matches(rx: str, s: str) → bool
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(if matches(rx, "12345") then "yes" else "no")
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("yes", runProgram(ir))
    }

    @Test fun `matches returns false for partial match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_matches") pub fn matches(rx: str, s: str) → bool
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(if matches(rx, "abc123") then "yes" else "no")
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("no", runProgram(ir))
    }

    @Test fun `matches returns false when no match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_matches") pub fn matches(rx: str, s: str) → bool
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(if matches(rx, "abc") then "yes" else "no")
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("no", runProgram(ir))
    }

    // ── contains ─────────────────────────────────────────────────────────────

    @Test fun `contains returns true when pattern appears anywhere`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")  pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_contains") pub fn contains(rx: str, s: str) → bool
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(if contains(rx, "abc 42 def") then "yes" else "no")
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("yes", runProgram(ir))
    }

    @Test fun `contains returns false when no match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")  pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_contains") pub fn contains(rx: str, s: str) → bool
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(if contains(rx, "hello world") then "yes" else "no")
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("no", runProgram(ir))
    }

    // ── find ─────────────────────────────────────────────────────────────────

    @Test fun `find returns matched value`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")    pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_value")   pub fn matchValue(m: str) → str
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(matchValue(find(rx, "abc 42 def") ?? ""))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("42", runProgram(ir))
    }

    @Test fun `find returns nil when no match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")    pub fn find(rx: str, s: str) → str?
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(find(rx, "hello") ?? "nil")
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("nil", runProgram(ir))
    }

    @Test fun `find returns start offset`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")    pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_start")   pub fn matchStart(m: str) → int
            fn getStart(rx: str) → str
                let m = find(rx, "abc 42 def")
                → "{matchStart(m ?? "")}"
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(getStart(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("4", runProgram(ir))
    }

    @Test fun `find returns end offset`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")    pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_end")     pub fn matchEnd(m: str) → int
            fn getEnd(rx: str) → str
                let m = find(rx, "abc 42 def")
                → "{matchEnd(m ?? "")}"
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(getEnd(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("6", runProgram(ir))
    }

    // ── capture groups ───────────────────────────────────────────────────────

    @Test fun `find with capture group returns group 1`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")   pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")      pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_group_idx") pub fn group(m: str, i: int) → str?
            fn getGroup1(rx: str) → str
                → group(find(rx, "key=42") ?? "", 1) ?? "nil"
            fn main()
                let r = compile("([a-z]+)=([0-9]+)")
                match r
                    Ok(rx): → println(getGroup1(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("key", runProgram(ir))
    }

    @Test fun `find with capture group returns group 2`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")   pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")      pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_group_idx") pub fn group(m: str, i: int) → str?
            fn getGroup2(rx: str) → str
                → group(find(rx, "key=42") ?? "", 2) ?? "nil"
            fn main()
                let r = compile("([a-z]+)=([0-9]+)")
                match r
                    Ok(rx): → println(getGroup2(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("42", runProgram(ir))
    }

    @Test fun `group returns nil for out of range index`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")   pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")      pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_group_idx") pub fn group(m: str, i: int) → str?
            fn getGroup5(rx: str) → str
                → group(find(rx, "hello") ?? "", 5) ?? "nil"
            fn main()
                let r = compile("([a-z]+)")
                match r
                    Ok(rx): → println(getGroup5(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("nil", runProgram(ir))
    }

    // ── named groups ─────────────────────────────────────────────────────────

    @Test fun `named group word extracted correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")    pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")       pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_group_name") pub fn groupNamed(m: str, name: str) → str?
            fn getWord(rx: str) → str
                → groupNamed(find(rx, "key=42") ?? "", "word") ?? "nil"
            fn main()
                let r = compile("(?P<word>[a-z]+)=(?P<num>[0-9]+)")
                match r
                    Ok(rx): → println(getWord(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("key", runProgram(ir))
    }

    @Test fun `named group num extracted correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")    pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")       pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_group_name") pub fn groupNamed(m: str, name: str) → str?
            fn getNum(rx: str) → str
                → groupNamed(find(rx, "key=42") ?? "", "num") ?? "nil"
            fn main()
                let r = compile("(?P<word>[a-z]+)=(?P<num>[0-9]+)")
                match r
                    Ok(rx): → println(getNum(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("42", runProgram(ir))
    }

    @Test fun `named group returns nil for unknown name`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")    pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")       pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_group_name") pub fn groupNamed(m: str, name: str) → str?
            fn getUnknown(rx: str) → str
                → groupNamed(find(rx, "hello") ?? "", "nope") ?? "nil"
            fn main()
                let r = compile("(?P<word>[a-z]+)")
                match r
                    Ok(rx): → println(getUnknown(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("nil", runProgram(ir))
    }

    // ── replace ──────────────────────────────────────────────────────────────

    @Test fun `replace substitutes first match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_replace") pub fn replace(rx: str, s: str, repl: str) → str
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(replace(rx, "a1b2c3", "X"))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("aXb2c3", runProgram(ir))
    }

    @Test fun `replace returns copy when no match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_replace") pub fn replace(rx: str, s: str, repl: str) → str
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(replace(rx, "hello", "X"))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("hello", runProgram(ir))
    }

    @Test fun `replace with dollar-zero substitution`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_replace") pub fn replace(rx: str, s: str, repl: str) → str
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(replace(rx, "a42b", "[$0]"))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("a[42]b", runProgram(ir))
    }

    @Test fun `replace with capture group back-reference`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_replace") pub fn replace(rx: str, s: str, repl: str) → str
            fn main()
                let r = compile("([a-z]+)=([0-9]+)")
                match r
                    Ok(rx): → println(replace(rx, "key=42", "$2=$1"))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("42=key", runProgram(ir))
    }

    // ── replaceAll ───────────────────────────────────────────────────────────

    @Test fun `replaceAll substitutes every match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")     pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_replace_all") pub fn replaceAll(rx: str, s: str, repl: str) → str
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(replaceAll(rx, "a1b2c3", "X"))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("aXbXcX", runProgram(ir))
    }

    @Test fun `replaceAll with capture group back-reference`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")     pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_replace_all") pub fn replaceAll(rx: str, s: str, repl: str) → str
            fn main()
                let r = compile("([0-9]+)")
                match r
                    Ok(rx): → println(replaceAll(rx, "a1b22c333", "($1)"))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("a(1)b(22)c(333)", runProgram(ir))
    }

    @Test fun `replaceAll returns copy when no match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")     pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_replace_all") pub fn replaceAll(rx: str, s: str, repl: str) → str
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(replaceAll(rx, "hello", "X"))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("hello", runProgram(ir))
    }

    // ── split ────────────────────────────────────────────────────────────────

    @Test fun `split on delimiter count`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_split")   pub fn split(rx: str, s: str) → [str]
            fn main()
                let r = compile(",")
                match r
                    Ok(rx): → println(split(rx, "a,b,c").count)
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("3", runProgram(ir))
    }

    @Test fun `split with no match returns one element`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_split")   pub fn split(rx: str, s: str) → [str]
            fn main()
                let r = compile(",")
                match r
                    Ok(rx): → println(split(rx, "hello").count)
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("1", runProgram(ir))
    }

    @Test fun `split on whitespace count`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile") pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_split")   pub fn split(rx: str, s: str) → [str]
            fn main()
                let r = compile("[[:space:]]+")
                match r
                    Ok(rx): → println(split(rx, "hello world  foo").count)
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("3", runProgram(ir))
    }

    // ── find_all ─────────────────────────────────────────────────────────────

    @Test fun `findAll count`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")  pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find_all") pub fn findAll(rx: str, s: str) → [str]
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(findAll(rx, "a1 b22 c333").count)
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("3", runProgram(ir))
    }

    @Test fun `findAll returns empty array when no match`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")  pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find_all") pub fn findAll(rx: str, s: str) → [str]
            fn main()
                let r = compile("[0-9]+")
                match r
                    Ok(rx): → println(findAll(rx, "hello").count)
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("0", runProgram(ir))
    }

    // ── non-capturing groups ──────────────────────────────────────────────────
    // Note: POSIX ERE has no (?:...) syntax. The preprocessor converts (?:...)
    // to (...), making it a regular capture group. Group numbering follows POSIX:
    // (?:[a-z]+)=([0-9]+) → ([a-z]+)=([0-9]+), so group 1 = "key", group 2 = "42".

    @Test fun `non-capturing group result`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")   pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")      pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_group_idx") pub fn group(m: str, i: int) → str?
            fn ncResult(rx: str) → str
                → group(find(rx, "key=42") ?? "", 2) ?? "nil"
            fn main()
                let r = compile("(?:[a-z]+)=([0-9]+)")
                match r
                    Ok(rx): → println(ncResult(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("42", runProgram(ir))
    }

    @Test fun `non-capturing group second group is nil`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_regex_compile")   pub fn compile(p: str) → Result<str>
            @extern(fn: "nv_regex_find")      pub fn find(rx: str, s: str) → str?
            @extern(fn: "nv_match_group_idx") pub fn group(m: str, i: int) → str?
            fn ncResult2(rx: str) → str
                → group(find(rx, "key=42") ?? "", 3) ?? "nil"
            fn main()
                let r = compile("(?:[a-z]+)=([0-9]+)")
                match r
                    Ok(rx): → println(ncResult2(rx))
                    Err(_): → println("err")
        """.trimIndent())
        assertEquals("nil", runProgram(ir))
    }
}
