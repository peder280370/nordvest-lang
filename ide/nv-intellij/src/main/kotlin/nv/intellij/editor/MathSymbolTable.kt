package nv.intellij.editor

/**
 * Bidirectional mapping between LaTeX-style escape names and Nordvest
 * Unicode operators / constants.
 *
 * Usage:
 *   - [lookup]   : name → symbol   (typed handler & completion)
 *   - [ALL]      : full table      (symbol picker dialog)
 */
object MathSymbolTable {

    data class Entry(
        /** The escape name without the leading backslash, e.g. "forall". */
        val name: String,
        /** The Unicode symbol string, e.g. "∀". */
        val symbol: String,
        /** Human-readable description shown in the picker and completion popup. */
        val description: String,
        /** Unicode code point string, e.g. "U+2200". */
        val codepoint: String,
    )

    /** Every symbol the plugin recognises, in display order. */
    val ALL: List<Entry> = listOf(
        // ── Quantifiers & set operators ────────────────────────────────────
        Entry("forall",  "∀", "For all (universal quantifier)",   "U+2200"),
        Entry("exists",  "∃", "There exists (existential)",       "U+2203"),
        Entry("sum",     "∑", "Summation",                        "U+2211"),
        Entry("prod",    "∏", "Product",                          "U+220F"),
        Entry("in",      "∈", "Element of",                      "U+2208"),
        Entry("notin",   "∉", "Not element of",                  "U+2209"),
        Entry("union",   "∪", "Union",                           "U+222A"),
        Entry("inter",   "∩", "Intersection",                    "U+2229"),
        Entry("subset",  "⊂", "Subset",                         "U+2282"),
        Entry("subseteq","⊆", "Subset or equal",                 "U+2286"),

        // ── Logic operators ────────────────────────────────────────────────
        Entry("and",     "∧", "Logical and",                     "U+2227"),
        Entry("or",      "∨", "Logical or",                      "U+2228"),
        Entry("not",     "¬", "Logical not",                     "U+00AC"),
        Entry("implies", "⇒", "Implies",                         "U+21D2"),
        Entry("iff",     "⟺", "If and only if",                  "U+27FA"),

        // ── Arrows ────────────────────────────────────────────────────────
        Entry("->",      "→", "Right arrow (return / lambda)",   "U+2192"),
        Entry("<-",      "←", "Left arrow",                      "U+2190"),
        Entry("=>",      "⇒", "Double right arrow",              "U+21D2"),
        Entry("<=>",     "⟺", "Double bi-directional arrow",      "U+27FA"),

        // ── Comparison / relational ────────────────────────────────────────
        Entry("leq",     "≤", "Less than or equal",              "U+2264"),
        Entry("geq",     "≥", "Greater than or equal",           "U+2265"),
        Entry("neq",     "≠", "Not equal",                       "U+2260"),
        Entry("approx",  "≈", "Approximately equal",             "U+2248"),
        Entry("equiv",   "≡", "Identical / equivalent",          "U+2261"),

        // ── Arithmetic ────────────────────────────────────────────────────
        Entry("div",     "÷", "Integer division",                "U+00F7"),
        Entry("times",   "×", "Multiplication sign",             "U+00D7"),
        Entry("xor",     "⊕", "Bitwise XOR",                    "U+2295"),
        Entry("pm",      "±", "Plus or minus",                   "U+00B1"),
        Entry("sqrt",    "√", "Square root",                     "U+221A"),

        // ── Constants ─────────────────────────────────────────────────────
        Entry("pi",      "π", "Pi (π ≈ 3.14159…)",              "U+03C0"),
        Entry("inf",     "∞", "Infinity",                        "U+221E"),
        Entry("empty",   "∅", "Empty set",                       "U+2205"),
        Entry("nabla",   "∇", "Nabla / gradient",               "U+2207"),

        // ── Greek letters — lowercase ─────────────────────────────────────
        Entry("alpha",   "α", "Alpha",    "U+03B1"),
        Entry("beta",    "β", "Beta",     "U+03B2"),
        Entry("gamma",   "γ", "Gamma",    "U+03B3"),
        Entry("delta",   "δ", "Delta",    "U+03B4"),
        Entry("epsilon", "ε", "Epsilon",  "U+03B5"),
        Entry("zeta",    "ζ", "Zeta",     "U+03B6"),
        Entry("eta",     "η", "Eta",      "U+03B7"),
        Entry("theta",   "θ", "Theta",    "U+03B8"),
        Entry("iota",    "ι", "Iota",     "U+03B9"),
        Entry("kappa",   "κ", "Kappa",    "U+03BA"),
        Entry("lambda",  "λ", "Lambda",   "U+03BB"),
        Entry("mu",      "μ", "Mu",       "U+03BC"),
        Entry("nu",      "ν", "Nu",       "U+03BD"),
        Entry("xi",      "ξ", "Xi",       "U+03BE"),
        Entry("rho",     "ρ", "Rho",      "U+03C1"),
        Entry("sigma",   "σ", "Sigma",    "U+03C3"),
        Entry("tau",     "τ", "Tau",      "U+03C4"),
        Entry("upsilon", "υ", "Upsilon",  "U+03C5"),
        Entry("phi",     "φ", "Phi",      "U+03C6"),
        Entry("chi",     "χ", "Chi",      "U+03C7"),
        Entry("psi",     "ψ", "Psi",      "U+03C8"),
        Entry("omega",   "ω", "Omega",    "U+03C9"),

        // ── Greek letters — uppercase ─────────────────────────────────────
        Entry("Gamma",   "Γ", "Gamma (upper)",   "U+0393"),
        Entry("Delta",   "Δ", "Delta (upper)",   "U+0394"),
        Entry("Theta",   "Θ", "Theta (upper)",   "U+0398"),
        Entry("Lambda",  "Λ", "Lambda (upper)",  "U+039B"),
        Entry("Xi",      "Ξ", "Xi (upper)",      "U+039E"),
        Entry("Pi",      "Π", "Pi (upper)",      "U+03A0"),
        Entry("Sigma",   "Σ", "Sigma (upper)",   "U+03A3"),
        Entry("Phi",     "Φ", "Phi (upper)",     "U+03A6"),
        Entry("Psi",     "Ψ", "Psi (upper)",     "U+03A8"),
        Entry("Omega",   "Ω", "Omega (upper)",   "U+03A9"),

        // ── Subscript / superscript digits ────────────────────────────────
        Entry("^0",      "⁰", "Superscript 0",   "U+2070"),
        Entry("^1",      "¹", "Superscript 1",   "U+00B9"),
        Entry("^2",      "²", "Superscript 2",   "U+00B2"),
        Entry("^3",      "³", "Superscript 3",   "U+00B3"),
        Entry("_0",      "₀", "Subscript 0",     "U+2080"),
        Entry("_1",      "₁", "Subscript 1",     "U+2081"),
        Entry("_2",      "₂", "Subscript 2",     "U+2082"),
        Entry("_n",      "ₙ", "Subscript n",     "U+2099"),
        Entry("_i",      "ᵢ", "Subscript i",     "U+1D62"),
    )

    /** Name → Entry lookup map (case-sensitive, no leading backslash). */
    private val byName: Map<String, Entry> = ALL.associateBy { it.name }

    /**
     * Returns the [Entry] for [name] (without leading `\`), or null if not found.
     *
     * Called by [MathSymbolTypedHandler] and [MathSymbolCompletionContributor].
     */
    fun lookup(name: String): Entry? = byName[name]

    /**
     * Returns all entries whose name starts with [prefix] (case-sensitive).
     * Used by the completion contributor for incremental filtering.
     */
    fun matching(prefix: String): List<Entry> =
        if (prefix.isEmpty()) ALL
        else ALL.filter { it.name.startsWith(prefix) || it.description.contains(prefix, ignoreCase = true) }
}
