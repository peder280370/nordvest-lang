package nv.intellij.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the pure string-manipulation helpers in [NordvestEnterHandler].
 *
 * These helpers determine the indentation for the new line when the user presses
 * Enter — critical for indentation-sensitive Nordvest code.
 */
class EnterHandlerHelpersTest {

    // ── leadingWhitespace ─────────────────────────────────────────────────

    @Test
    fun `leadingWhitespace returns empty for no leading whitespace`() {
        assertEquals("", NordvestEnterHandler.leadingWhitespace("fn foo():"))
    }

    @Test
    fun `leadingWhitespace returns spaces`() {
        assertEquals("    ", NordvestEnterHandler.leadingWhitespace("    if x:"))
    }

    @Test
    fun `leadingWhitespace returns tabs`() {
        assertEquals("\t\t", NordvestEnterHandler.leadingWhitespace("\t\tvar x = 1"))
    }

    @Test
    fun `leadingWhitespace returns mixed spaces and tabs`() {
        assertEquals("  \t  ", NordvestEnterHandler.leadingWhitespace("  \t  code"))
    }

    @Test
    fun `leadingWhitespace returns full string when entirely whitespace`() {
        assertEquals("    ", NordvestEnterHandler.leadingWhitespace("    "))
    }

    @Test
    fun `leadingWhitespace returns empty string for empty input`() {
        assertEquals("", NordvestEnterHandler.leadingWhitespace(""))
    }

    @Test
    fun `leadingWhitespace stops at first non-whitespace`() {
        assertEquals("  ", NordvestEnterHandler.leadingWhitespace("  x  "))
    }

    // ── stripInlineComment ────────────────────────────────────────────────

    @Test
    fun `stripInlineComment leaves lines without comments unchanged`() {
        assertEquals("fn foo(x: int) → int", NordvestEnterHandler.stripInlineComment("fn foo(x: int) → int"))
    }

    @Test
    fun `stripInlineComment strips trailing line comment`() {
        assertEquals("let x = 42 ", NordvestEnterHandler.stripInlineComment("let x = 42 // set x"))
    }

    @Test
    fun `stripInlineComment strips comment at start of line`() {
        assertEquals("", NordvestEnterHandler.stripInlineComment("// whole line comment"))
    }

    @Test
    fun `stripInlineComment handles empty line`() {
        assertEquals("", NordvestEnterHandler.stripInlineComment(""))
    }

    @Test
    fun `stripInlineComment preserves double slash inside string literal`() {
        // "http://example.com" — the // is inside the string, must not be stripped
        assertEquals("""let url = "http://example.com"""",
            NordvestEnterHandler.stripInlineComment("""let url = "http://example.com""""))
    }

    @Test
    fun `stripInlineComment strips comment after string literal`() {
        assertEquals("""let s = "hello" """,
            NordvestEnterHandler.stripInlineComment("""let s = "hello" // a string"""))
    }

    @Test
    fun `stripInlineComment handles escaped quote inside string`() {
        // let s = "say \"hi\"" // comment
        // The \" inside the string should not end the string scanning early
        val line = """let s = "say \"hi\"" // comment"""
        assertEquals("""let s = "say \"hi\"" """, NordvestEnterHandler.stripInlineComment(line))
    }

    @Test
    fun `stripInlineComment handles multiple strings on one line`() {
        val line = """let a = "foo" + "bar" // concat"""
        assertEquals("""let a = "foo" + "bar" """, NordvestEnterHandler.stripInlineComment(line))
    }

    @Test
    fun `stripInlineComment single slash is not a comment`() {
        assertEquals("a / b", NordvestEnterHandler.stripInlineComment("a / b"))
    }

    // ── Combined: indentation logic ───────────────────────────────────────

    /**
     * Simulate the core logic of [NordvestEnterHandler.preprocessEnter]:
     * given a line of code, determine what indent the new line should have.
     */
    private fun computeNewIndent(line: String): String {
        val trimmed = NordvestEnterHandler.stripInlineComment(line).trimEnd()
        val base = NordvestEnterHandler.leadingWhitespace(line)
        return if (trimmed.endsWith(":")) base + "    " else base
    }

    @Test
    fun `function declaration with colon indents one level deeper`() {
        assertEquals("    ", computeNewIndent("fn foo():"))
    }

    @Test
    fun `if condition with colon indents one level deeper`() {
        assertEquals("    ", computeNewIndent("if x > 0:"))
    }

    @Test
    fun `already-indented block opener indents further`() {
        assertEquals("        ", computeNewIndent("    for item in items:"))
    }

    @Test
    fun `plain expression line preserves indent`() {
        assertEquals("    ", computeNewIndent("    let x = 42"))
    }

    @Test
    fun `line with trailing comment ending in colon does not indent`() {
        // "let x = 1 // ends with colon:" — the colon is in the comment, not the code
        assertEquals("", computeNewIndent("let x = 1 // ends with colon:"))
    }

    @Test
    fun `line with string containing colon does not double-indent`() {
        // The string ends with ":", but the line itself doesn't end with ":"
        assertEquals("", computeNewIndent("""let s = "value:""""))
    }

    @Test
    fun `match expression opener indents`() {
        assertEquals("            ", computeNewIndent("        match value:"))
    }

    @Test
    fun `empty line has empty indent`() {
        assertEquals("", computeNewIndent(""))
    }

    @Test
    fun `line with only whitespace preserves that whitespace`() {
        assertEquals("    ", computeNewIndent("    "))
    }
}
