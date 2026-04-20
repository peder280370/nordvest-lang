package nv.intellij.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NordvestSymbolIndexTest {

    // ── extractDeclFromLine ───────────────────────────────────────────────

    @Test
    fun `fn declaration extracts name and kind`() {
        assertEquals(Pair("add", "fn"), NordvestSymbolIndex.extractDeclFromLine("fn add(a: int, b: int) → int"))
        assertEquals(Pair("main", "fn"), NordvestSymbolIndex.extractDeclFromLine("fn main()"))
        assertEquals(Pair("f", "fn"), NordvestSymbolIndex.extractDeclFromLine("fn f()"))
    }

    @Test
    fun `class declaration extracts name and kind`() {
        assertEquals(Pair("User", "class"), NordvestSymbolIndex.extractDeclFromLine("class User(name: str)"))
        assertEquals(Pair("Node", "class"), NordvestSymbolIndex.extractDeclFromLine("class Node"))
    }

    @Test
    fun `struct declaration extracts name and kind`() {
        assertEquals(Pair("Vec2", "struct"), NordvestSymbolIndex.extractDeclFromLine("struct Vec2(x: float, y: float)"))
    }

    @Test
    fun `record declaration extracts name and kind`() {
        assertEquals(Pair("Point", "record"), NordvestSymbolIndex.extractDeclFromLine("record Point(x: float, y: float)"))
    }

    @Test
    fun `interface declaration extracts name and kind`() {
        assertEquals(Pair("Printable", "interface"), NordvestSymbolIndex.extractDeclFromLine("interface Printable"))
    }

    @Test
    fun `enum declaration extracts name and kind`() {
        assertEquals(Pair("Color", "enum"), NordvestSymbolIndex.extractDeclFromLine("enum Color"))
    }

    @Test
    fun `sealed class extracts kind as 'sealed'`() {
        assertEquals(Pair("Shape", "sealed"), NordvestSymbolIndex.extractDeclFromLine("sealed class Shape"))
    }

    @Test
    fun `let declaration extracts name and kind`() {
        assertEquals(Pair("PI", "let"), NordvestSymbolIndex.extractDeclFromLine("let PI: float = 3.14"))
        assertEquals(Pair("x", "let"), NordvestSymbolIndex.extractDeclFromLine("let x = 42"))
    }

    @Test
    fun `var declaration extracts name and kind`() {
        assertEquals(Pair("count", "var"), NordvestSymbolIndex.extractDeclFromLine("var count: int = 0"))
    }

    @Test
    fun `type alias extracts name and kind`() {
        assertEquals(Pair("Callback", "type"), NordvestSymbolIndex.extractDeclFromLine("type Callback = (int) → void"))
    }

    @Test
    fun `extend declaration extracts name and kind`() {
        assertEquals(Pair("String", "extend"), NordvestSymbolIndex.extractDeclFromLine("extend String"))
    }

    @Test
    fun `pub visibility modifier is stripped`() {
        assertEquals(Pair("add", "fn"), NordvestSymbolIndex.extractDeclFromLine("pub fn add(a: int) → int"))
        assertEquals(Pair("User", "class"), NordvestSymbolIndex.extractDeclFromLine("pub class User"))
    }

    @Test
    fun `pub(pkg) visibility modifier is stripped`() {
        assertEquals(Pair("helper", "fn"), NordvestSymbolIndex.extractDeclFromLine("pub(pkg) fn helper()"))
    }

    @Test
    fun `leading annotation is stripped`() {
        assertEquals(Pair("Point", "struct"), NordvestSymbolIndex.extractDeclFromLine("@derive(All) struct Point(x: float)"))
        assertEquals(Pair("run", "fn"), NordvestSymbolIndex.extractDeclFromLine("@test fn run()"))
    }

    @Test
    fun `multiple annotations are stripped`() {
        assertEquals(Pair("Config", "struct"), NordvestSymbolIndex.extractDeclFromLine("@config @derive(Show) struct Config"))
    }

    @Test
    fun `annotation with pub and sealed class`() {
        assertEquals(Pair("Expr", "sealed"), NordvestSymbolIndex.extractDeclFromLine("@derive(All) pub sealed class Expr"))
    }

    @Test
    fun `lines without declaration keywords return null`() {
        // Note: extractDeclFromLine trims its input; indented-line filtering happens in extractSymbols
        assertNull(NordvestSymbolIndex.extractDeclFromLine("// comment"))
        assertNull(NordvestSymbolIndex.extractDeclFromLine(""))
        assertNull(NordvestSymbolIndex.extractDeclFromLine("import std.math"))
        assertNull(NordvestSymbolIndex.extractDeclFromLine("module myapp"))
        assertNull(NordvestSymbolIndex.extractDeclFromLine("→ a + b"))
        assertNull(NordvestSymbolIndex.extractDeclFromLine("print(x)"))
    }

    @Test
    fun `keyword without following name returns null`() {
        assertNull(NordvestSymbolIndex.extractDeclFromLine("fn"))
        assertNull(NordvestSymbolIndex.extractDeclFromLine("fn "))
    }

    // ── extractSymbols ────────────────────────────────────────────────────

    private fun extract(code: String) = NordvestSymbolIndex.extractSymbols(code).toMap()

    @Test
    fun `extracts single function`() {
        val syms = extract("fn add(a: int, b: int) → int\n    → a + b\n")
        assertEquals(mapOf("add" to "fn"), syms)
    }

    @Test
    fun `extracts multiple top-level declarations`() {
        val code = """
            fn main()
                print("hello")

            class User(name: str)

            let VERSION: str = "1.0"
        """.trimIndent()
        val syms = extract(code)
        assertEquals("fn",    syms["main"])
        assertEquals("class", syms["User"])
        assertEquals("let",   syms["VERSION"])
    }

    @Test
    fun `ignores indented declarations (body lines)`() {
        val code = """
            class Parser
                fn parse() → Ast
                    let result = nil
        """.trimIndent()
        val syms = extract(code)
        assertEquals(setOf("Parser"), syms.keys)  // fn parse is indented, not top-level
    }

    @Test
    fun `ignores comment lines`() {
        val code = """
            // This is a comment
            fn greet()
            // Another comment
        """.trimIndent()
        val syms = extract(code)
        assertEquals(mapOf("greet" to "fn"), syms)
    }

    @Test
    fun `extracts sealed class with correct kind`() {
        val syms = extract("sealed class Shape\n    Circle(r: float)\n    Rect(w: float, h: float)\n")
        assertEquals("sealed", syms["Shape"])
    }

    @Test
    fun `extracts pub functions correctly`() {
        val syms = extract("pub fn exported() → void\n")
        assertEquals("fn", syms["exported"])
    }

    @Test
    fun `handles empty input`() {
        assertEquals(emptyMap(), extract(""))
    }

    @Test
    fun `handles file with only comments`() {
        assertEquals(emptyMap(), extract("// just comments\n// nothing else\n"))
    }

    @Test
    fun `real-world module file extracts all top-level declarations`() {
        val code = """
            module myapp.geometry

            import std.math

            @derive(All)
            pub struct Vec2(x: float, y: float)

            pub fn dot(a: Vec2, b: Vec2) → float
                → a.x * b.x + a.y * b.y

            pub fn length(v: Vec2) → float
                → ∑ x ∈ [v.x, v.y]: x^2

            type Transform = (Vec2) → Vec2
        """.trimIndent()
        val syms = extract(code)
        assertEquals("struct", syms["Vec2"])
        assertEquals("fn",     syms["dot"])
        assertEquals("fn",     syms["length"])
        assertEquals("type",   syms["Transform"])
        // import / module lines should not produce symbols
        assertTrue("math" !in syms)
        assertTrue("myapp" !in syms)
    }
}
