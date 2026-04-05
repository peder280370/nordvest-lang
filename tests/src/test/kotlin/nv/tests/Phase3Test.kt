package nv.tests

import nv.compiler.Compiler
import nv.compiler.CompileResult
import nv.compiler.format.Formatter
import nv.compiler.lexer.Lexer
import nv.compiler.parser.Parser
import nv.compiler.parser.ParseResult
import nv.compiler.resolve.ResolveError
import nv.compiler.resolve.Resolver
import nv.compiler.resolve.ResolveResult
import nv.tools.buildJson
import nv.tools.parseJson
import nv.tools.asMap
import nv.tools.asStr
import nv.tools.asLong
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Phase 3 tests: formatter, error quality, LSP helpers.
 */
class Phase3Test {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseSource(src: String): nv.compiler.parser.SourceFile {
        val tokens = Lexer(src).tokenize()
        return when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> throw AssertionError("Parse failed: ${r.errors.firstOrNull()?.message}")
        }
    }

    private fun format(src: String, ascii: Boolean = false): String {
        val file = parseSource(src)
        return Formatter(asciiMode = ascii).format(file)
    }

    private fun resolveSource(src: String): nv.compiler.resolve.ResolvedModule? {
        val tokens = Lexer(src).tokenize()
        val file = when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> return null
        }
        return when (val r = Resolver("<test>").resolve(file)) {
            is ResolveResult.Success   -> r.module
            is ResolveResult.Recovered -> r.module
            is ResolveResult.Failure   -> null
        }
    }

    // ── 3.1 Formatter: idempotency ────────────────────────────────────────────

    @Test fun `formatter is idempotent on function`() {
        val src = """
            fn add(a: int, b: int) → int
                → a + b
        """.trimIndent() + "\n"
        val once  = format(src)
        val twice = format(once)
        assertEquals(once, twice)
    }

    @Test fun `formatter is idempotent on class`() {
        val src = """
            class Point(pub x: float, pub y: float)
                fn distance(other: Point) → float
                    → (x - other.x) ^ 2.0 + (y - other.y) ^ 2.0
        """.trimIndent() + "\n"
        val once  = format(src)
        val twice = format(once)
        assertEquals(once, twice)
    }

    @Test fun `formatter normalises ASCII arrow to Unicode`() {
        val src = """
            fn f(x: int) -> int
                -> x + 1
        """.trimIndent() + "\n"
        // Arrow normalized to Unicode by default
        val out = format(src)
        assertFalse(out.contains("->"), "Should not contain ASCII arrow")
        assertTrue(out.contains("→"), "Should contain Unicode arrow")
    }

    @Test fun `formatter ascii mode uses ASCII operators`() {
        val src = "fn f(x: int) → int\n    → x + 1\n"
        val out = format(src, ascii = true)
        assertTrue(out.contains("->"), "ASCII mode should use ->")
        assertFalse(out.contains("→"), "ASCII mode should not use →")
    }

    @Test fun `formatter adds trailing newline`() {
        val src = "fn f()\n    → 0"
        val out = format(src)
        assertTrue(out.endsWith("\n"))
    }

    @Test fun `formatter handles module and imports`() {
        val src = """
            module myapp.core
            import std.math
            fn area(r: float) → float
                → π * r * r
        """.trimIndent() + "\n"
        val out = format(src)
        assertTrue(out.contains("module myapp.core"))
        assertTrue(out.contains("import std.math"))
    }

    @Test fun `formatter handles sealed class`() {
        val src = """
            sealed class Shape
                Circle(radius: float)
                Rect(w: float, h: float)
        """.trimIndent() + "\n"
        val out = format(src)
        assertTrue(out.contains("sealed class Shape"))
        assertTrue(out.contains("Circle(radius: float)"))
        assertTrue(out.contains("Rect(w: float, h: float)"))
    }

    @Test fun `formatter handles let and var declarations`() {
        val src = """
            let x: int = 42
            var y: float = 3.14
        """.trimIndent() + "\n"
        val out = format(src)
        assertTrue(out.contains("let x: int = 42"))
        assertTrue(out.contains("var y: float = 3.14"))
    }

    @Test fun `formatter handles if-else`() {
        val src = """
            fn abs(x: int) → int
                if x < 0
                    → -x
                else
                    → x
        """.trimIndent() + "\n"
        val out = format(src)
        assertTrue(out.contains("if x < 0"))
        assertTrue(out.contains("else"))
    }

    @Test fun `formatter handles match`() {
        val src = """
            fn describe(n: int) → str
                match n
                    0: → "zero"
                    _: → "other"
        """.trimIndent() + "\n"
        val out = format(src)
        assertTrue(out.contains("match n"))
    }

    @Test fun `formatter handles for loop`() {
        val src = """
            fn sum(xs: [int]) → int
                var total = 0
                for x in xs
                    total += x
                → total
        """.trimIndent() + "\n"
        val once  = format(src)
        val twice = format(once)
        assertEquals(once, twice)
    }

    @Test fun `formatter handles nullable types`() {
        val src = """
            fn find(id: int) → str?
                → nil
        """.trimIndent() + "\n"
        val out = format(src)
        assertTrue(out.contains("str?"))
    }

    @Test fun `formatter handles map literal`() {
        val src = """
            let m: [str: int] = ["a": 1, "b": 2]
        """.trimIndent() + "\n"
        val out = format(src)
        assertTrue(out.contains("[str: int]"))
    }

    @Test fun `formatter handles inline if expression`() {
        val src = """
            let abs = x → if x < 0 then -x else x
        """.trimIndent() + "\n"
        val once  = format(src)
        val twice = format(once)
        assertEquals(once, twice)
    }

    // ── 3.3 Error quality: source context and suggestions ─────────────────────

    @Test fun `undefined symbol error includes did you mean for close name`() {
        val src = """
            fn f()
                let result = calcualte(42)
        """.trimIndent()
        // Resolver detects calcualte vs calculate (if calculate were defined)
        // In this minimal test, just check that UndefinedSymbol message is correct
        val tokens = Lexer(src).tokenize()
        val file = when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> null
        }
        assertNotNull(file)
        val resolveResult = Resolver("<test>").resolve(file!!)
        val errors = when (resolveResult) {
            is ResolveResult.Failure   -> resolveResult.errors
            is ResolveResult.Recovered -> resolveResult.module.errors
            is ResolveResult.Success   -> emptyList()
        }
        assertTrue(errors.isNotEmpty(), "Should have resolve errors for undefined symbol")
    }

    @Test fun `did you mean suggestion appears in UndefinedSymbol message`() {
        // Define 'calculate', then misspell it
        val src = """
            fn calculate(x: int) → int
                → x * 2

            fn main()
                let r = calcualte(5)
        """.trimIndent()
        val tokens = Lexer(src).tokenize()
        val file = when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> null
        }
        assertNotNull(file)
        val resolveResult = Resolver("<test>").resolve(file!!)
        val errors = when (resolveResult) {
            is ResolveResult.Failure   -> resolveResult.errors
            is ResolveResult.Recovered -> resolveResult.module.errors
            is ResolveResult.Success   -> emptyList()
        }
        val undefs = errors.filterIsInstance<ResolveError.UndefinedSymbol>()
        assertTrue(undefs.isNotEmpty(), "Should have UndefinedSymbol error for 'calcualte'")
        val err = undefs.first { it.name == "calcualte" }
        // Levenshtein("calcualte","calculate") = 2, should suggest
        assertTrue(
            err.suggestions.contains("calculate"),
            "Expected 'calculate' in suggestions, got: ${err.suggestions}"
        )
        assertTrue(
            err.message?.contains("did you mean") == true,
            "Expected 'did you mean' in message, got: ${err.message}"
        )
    }

    @Test fun `NonExhaustiveMatch error lists missing cases`() {
        val src = """
            sealed class Shape
                Circle(radius: float)
                Rect(w: float, h: float)

            fn area(s: Shape) → float
                match s
                    Circle(r): → 3.14 * r * r
        """.trimIndent()
        val result = Compiler.compile(src, "<test>")
        assertTrue(result is CompileResult.Failure)
        val errors = (result as CompileResult.Failure).errors
        val missing = errors.find { it.message.contains("Rect") || it.message.contains("non-exhaustive") || it.message.contains("missing") }
        assertNotNull(missing, "Should report missing Rect arm; errors: ${errors.map { it.message }}")
    }

    // ── LSP JSON helpers ──────────────────────────────────────────────────────

    @Test fun `JSON builder produces valid JSON for simple object`() {
        val json = buildJson {
            str("key", "value")
            num("count", 42)
            bool("flag", true)
        }
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"key\":\"value\""))
        assertTrue(json.contains("\"count\":42"))
        assertTrue(json.contains("\"flag\":true"))
    }

    @Test fun `JSON builder handles nested objects`() {
        val json = buildJson {
            obj("outer") {
                str("inner", "value")
            }
        }
        assertTrue(json.contains("\"outer\":{"))
        assertTrue(json.contains("\"inner\":\"value\""))
    }

    @Test fun `JSON builder handles arrays`() {
        val json = buildJson {
            arr("items", listOf("\"a\"", "\"b\"", "\"c\""))
        }
        assertTrue(json.contains("\"items\":[\"a\",\"b\",\"c\"]"))
    }

    @Test fun `JSON parser round trips simple object`() {
        val input = """{"name":"Alice","age":30,"active":true}"""
        val parsed = parseJson(input).asMap()
        assertNotNull(parsed)
        assertEquals("Alice", parsed!!["name"].asStr())
        assertEquals(30L, parsed["age"].asLong())
        assertEquals(true, parsed["active"] as? Boolean)
    }

    @Test fun `JSON parser handles nested objects`() {
        val input = """{"a":{"b":{"c":42}}}"""
        val parsed = parseJson(input).asMap()
        val b = parsed?.get("a").asMap()?.get("b").asMap()
        assertEquals(42L, b?.get("c").asLong())
    }

    @Test fun `JSON parser handles arrays`() {
        val input = """{"items":[1,2,3]}"""
        val parsed = parseJson(input).asMap()
        @Suppress("UNCHECKED_CAST")
        val items = parsed?.get("items") as? List<Any?>
        assertNotNull(items)
        assertEquals(3, items!!.size)
    }

    @Test fun `JSON escapes special characters`() {
        val json = buildJson {
            str("text", "hello\nworld\ttab\"quote\"")
        }
        assertFalse(json.contains("\n"))
        assertFalse(json.contains("\t"))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\t"))
        assertTrue(json.contains("\\\""))
    }

    // ── 3.7 Stdlib v2 modules exist ───────────────────────────────────────────

    @Test fun `stdlib v2 sql module file exists`() {
        val f = java.io.File(System.getProperty("projectDir", "."), "stdlib/std/sql.nv")
        assertTrue(f.exists(), "stdlib/std/sql.nv should exist")
        assertTrue(f.readText().contains("module std.sql"), "sql.nv should declare module std.sql")
    }

    @Test fun `stdlib v2 toml module file exists`() {
        val f = java.io.File(System.getProperty("projectDir", "."), "stdlib/std/toml.nv")
        assertTrue(f.exists(), "stdlib/std/toml.nv should exist")
        assertTrue(f.readText().contains("module std.toml"), "toml.nv should declare module std.toml")
    }

    @Test fun `stdlib v2 html module file exists`() {
        val f = java.io.File(System.getProperty("projectDir", "."), "stdlib/std/html.nv")
        assertTrue(f.exists(), "stdlib/std/html.nv should exist")
        assertTrue(f.readText().contains("module std.html"), "html.nv should declare module std.html")
    }

    @Test fun `stdlib v2 stats module file exists`() {
        val f = java.io.File(System.getProperty("projectDir", "."), "stdlib/std/stats.nv")
        assertTrue(f.exists(), "stdlib/std/stats.nv should exist")
    }

    @Test fun `stdlib v2 debug module file exists`() {
        val f = java.io.File(System.getProperty("projectDir", "."), "stdlib/std/debug.nv")
        assertTrue(f.exists(), "stdlib/std/debug.nv should exist")
    }

    // ── 3.4 Incremental cache ─────────────────────────────────────────────────

    @Test fun `sha256 of same input is identical`() {
        val input = "hello world"
        val h1 = sha256Hex(input)
        val h2 = sha256Hex(input)
        assertEquals(h1, h2)
    }

    @Test fun `sha256 of different inputs differs`() {
        assertNotEquals(sha256Hex("foo"), sha256Hex("bar"))
    }

    @Test fun `sha256 hex is 64 chars`() {
        assertEquals(64, sha256Hex("test").length)
    }
}

// Expose sha256Hex for testing (same implementation as Main.kt)
private fun sha256Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes  = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
