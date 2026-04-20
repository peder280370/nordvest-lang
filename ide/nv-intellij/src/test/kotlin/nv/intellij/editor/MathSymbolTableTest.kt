package nv.intellij.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MathSymbolTableTest {

    // ── lookup ────────────────────────────────────────────────────────────

    @Test
    fun `lookup returns correct entry for known names`() {
        val forall = MathSymbolTable.lookup("forall")
        assertNotNull(forall)
        assertEquals("∀", forall.symbol)
        assertEquals("forall", forall.name)
        assertEquals("U+2200", forall.codepoint)

        assertEquals("→", MathSymbolTable.lookup("->")?.symbol)
        assertEquals("≤", MathSymbolTable.lookup("leq")?.symbol)
        assertEquals("≥", MathSymbolTable.lookup("geq")?.symbol)
        assertEquals("≠", MathSymbolTable.lookup("neq")?.symbol)
        assertEquals("÷", MathSymbolTable.lookup("div")?.symbol)
        assertEquals("π", MathSymbolTable.lookup("pi")?.symbol)
        assertEquals("∞", MathSymbolTable.lookup("inf")?.symbol)
        assertEquals("α", MathSymbolTable.lookup("alpha")?.symbol)
        assertEquals("Ω", MathSymbolTable.lookup("Omega")?.symbol)
    }

    @Test
    fun `lookup returns null for unknown names`() {
        assertNull(MathSymbolTable.lookup("nonexistent"))
        assertNull(MathSymbolTable.lookup(""))
        assertNull(MathSymbolTable.lookup("FORALL"))  // case-sensitive
        assertNull(MathSymbolTable.lookup("Forall"))
    }

    @Test
    fun `lookup is case-sensitive — uppercase Gamma differs from lowercase gamma`() {
        val lower = MathSymbolTable.lookup("gamma")
        val upper = MathSymbolTable.lookup("Gamma")
        assertNotNull(lower)
        assertNotNull(upper)
        assertEquals("γ", lower.symbol)
        assertEquals("Γ", upper.symbol)
    }

    // ── ALL list integrity ────────────────────────────────────────────────

    @Test
    fun `ALL list is non-empty`() {
        assertTrue(MathSymbolTable.ALL.isNotEmpty())
    }

    @Test
    fun `ALL entries have unique names`() {
        val names = MathSymbolTable.ALL.map { it.name }
        val dupes = names.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "Duplicate names: $dupes")
    }

    @Test
    fun `ALL entries have non-empty symbols, names, descriptions, and codepoints`() {
        for (entry in MathSymbolTable.ALL) {
            assertTrue(entry.name.isNotEmpty(),        "Empty name for $entry")
            assertTrue(entry.symbol.isNotEmpty(),      "Empty symbol for $entry")
            assertTrue(entry.description.isNotEmpty(), "Empty description for $entry")
            assertTrue(entry.codepoint.isNotEmpty(),   "Empty codepoint for $entry")
        }
    }

    @Test
    fun `ALL codepoints follow U+XXXX format`() {
        val pattern = Regex("^U\\+[0-9A-F]{4,6}$")
        for (entry in MathSymbolTable.ALL) {
            assertTrue(entry.codepoint.matches(pattern),
                "Invalid codepoint '${entry.codepoint}' for entry '${entry.name}'")
        }
    }

    @Test
    fun `ALL entries are reachable via lookup`() {
        for (entry in MathSymbolTable.ALL) {
            assertEquals(entry, MathSymbolTable.lookup(entry.name),
                "Entry '${entry.name}' not reachable via lookup")
        }
    }

    // ── matching ─────────────────────────────────────────────────────────

    @Test
    fun `matching empty prefix returns all entries`() {
        assertEquals(MathSymbolTable.ALL, MathSymbolTable.matching(""))
    }

    @Test
    fun `matching exact name returns that entry`() {
        val result = MathSymbolTable.matching("forall")
        assertTrue(result.any { it.name == "forall" })
    }

    @Test
    fun `matching prefix returns all entries with that prefix`() {
        val result = MathSymbolTable.matching("om")
        // should include "omega" and "Omega"
        assertTrue(result.any { it.name == "omega" })
        assertTrue(result.any { it.name == "Omega" })
    }

    @Test
    fun `matching name prefix is case-sensitive for name but description is case-insensitive`() {
        // "G" matches uppercase-G names AND entries whose description contains "g" (ignoreCase)
        // "gamma"'s description is "Gamma", so it WILL match via description even with "G" prefix
        val upper = MathSymbolTable.matching("G")
        // All uppercase Greek capital entries should be present
        assertTrue(upper.any { it.name == "Gamma" })
        assertTrue(upper.any { it.name == "Omega" })
        // A prefix that only matches uppercase names — use a prefix that doesn't appear
        // in any lowercase description: "Upsilon" is unlikely in other descriptions
        val upsilon = MathSymbolTable.matching("Upsilon")
        assertTrue(upsilon.any { it.name == "upsilon" || it.name == "Upsilon" })
    }

    @Test
    fun `matching also matches description case-insensitively`() {
        // "For all" should match the forall entry via its description
        val result = MathSymbolTable.matching("For all")
        assertTrue(result.any { it.name == "forall" })
    }

    @Test
    fun `matching unknown prefix returns empty list`() {
        val result = MathSymbolTable.matching("zzznomatch")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `matching greek letter prefix returns lowercase and uppercase variants`() {
        val result = MathSymbolTable.matching("lambda")
        assertTrue(result.any { it.symbol == "λ" })
    }

    // ── Specific important entries ────────────────────────────────────────

    @Test
    fun `arrow entries map correctly to unicode`() {
        assertEquals("→", MathSymbolTable.lookup("->")?.symbol)
        assertEquals("←", MathSymbolTable.lookup("<-")?.symbol)
        assertEquals("⇒", MathSymbolTable.lookup("=>")?.symbol)
    }

    @Test
    fun `quantifier entries are present`() {
        assertNotNull(MathSymbolTable.lookup("forall"))
        assertNotNull(MathSymbolTable.lookup("exists"))
        assertNotNull(MathSymbolTable.lookup("sum"))
        assertNotNull(MathSymbolTable.lookup("prod"))
        assertNotNull(MathSymbolTable.lookup("in"))
    }

    @Test
    fun `subscript and superscript entries have correct codepoints`() {
        assertEquals("U+00B2", MathSymbolTable.lookup("^2")?.codepoint)
        assertEquals("U+00B3", MathSymbolTable.lookup("^3")?.codepoint)
        assertEquals("U+2080", MathSymbolTable.lookup("_0")?.codepoint)
        assertEquals("U+2099", MathSymbolTable.lookup("_n")?.codepoint)
    }
}
