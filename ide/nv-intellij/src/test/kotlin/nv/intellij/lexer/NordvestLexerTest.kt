package nv.intellij.lexer

import com.intellij.psi.tree.IElementType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NordvestLexerTest {

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun tokenize(input: String): List<Pair<String, IElementType?>> {
        val lexer = NordvestLexer()
        lexer.start(input)
        val result = mutableListOf<Pair<String, IElementType?>>()
        while (lexer.tokenType != null) {
            result.add(input.substring(lexer.tokenStart, lexer.tokenEnd) to lexer.tokenType)
            lexer.advance()
        }
        return result
    }

    /** Tokenize and drop whitespace tokens for cleaner assertions. */
    private fun tokens(input: String) =
        tokenize(input).filter { it.second != NordvestTokenTypes.WHITESPACE }

    private fun tokenTypes(input: String) = tokens(input).map { it.second }
    private fun tokenTexts(input: String) = tokens(input).map { it.first }

    // ── Empty / whitespace ────────────────────────────────────────────────

    @Test
    fun `empty input produces no tokens`() {
        assertEquals(emptyList(), tokenize(""))
    }

    @Test
    fun `whitespace-only input produces only whitespace tokens`() {
        val all = tokenize("   \t\n  ")
        assertTrue(all.all { it.second == NordvestTokenTypes.WHITESPACE })
    }

    // ── Keywords ──────────────────────────────────────────────────────────

    @Test
    fun `all sampled keywords are KEYWORD`() {
        val keywords = listOf(
            "fn", "let", "var", "return",
            "if", "else", "then",
            "for", "while", "break", "continue",
            "match",
            "class", "struct", "record", "interface", "sealed", "enum", "extend",
            "type", "init", "self",
            "true", "false", "nil",
            "in", "is", "as",
            "defer", "async", "await", "yield",
            "weak", "unowned", "unsafe",
            "guard", "override",
        )
        for (kw in keywords) {
            val toks = tokens(kw)
            assertEquals(1, toks.size, "Expected 1 token for keyword '$kw'")
            assertEquals(NordvestTokenTypes.KEYWORD, toks[0].second, "Expected KEYWORD for '$kw'")
        }
    }

    @Test
    fun `keyword prefix followed by letter is IDENT not KEYWORD`() {
        // "fnfoo" should be a single IDENT, not a KEYWORD + IDENT
        val toks = tokens("fnfoo")
        assertEquals(1, toks.size)
        assertEquals(NordvestTokenTypes.IDENT, toks[0].second)
    }

    // ── Identifiers ───────────────────────────────────────────────────────

    @Test
    fun `plain identifiers are IDENT`() {
        for (ident in listOf("myVar", "foo", "_bar", "_", "CamelCase", "x1", "snake_case")) {
            val toks = tokens(ident)
            assertEquals(1, toks.size, "Expected 1 token for '$ident'")
            assertEquals(NordvestTokenTypes.IDENT, toks[0].second, "Expected IDENT for '$ident'")
        }
    }

    @Test
    fun `Greek letter identifiers are IDENT`() {
        // α, β, γ are valid identifier chars in Nordvest
        for (ident in listOf("α", "beta2", "αβγ")) {
            val toks = tokens(ident)
            assertEquals(1, toks.size, "Expected 1 token for '$ident'")
            assertEquals(NordvestTokenTypes.IDENT, toks[0].second, "Expected IDENT for '$ident'")
        }
    }

    // ── Built-in types ────────────────────────────────────────────────────

    @Test
    fun `builtin type names are BUILTIN_TYPE`() {
        for (t in listOf("int", "float", "str", "bool", "void", "Result", "Option", "Some", "None", "Ok", "Err", "Duration")) {
            val toks = tokens(t)
            assertEquals(1, toks.size, "Expected 1 token for '$t'")
            assertEquals(NordvestTokenTypes.BUILTIN_TYPE, toks[0].second, "Expected BUILTIN_TYPE for '$t'")
        }
    }

    // ── Number literals ───────────────────────────────────────────────────

    @Test
    fun `integer literals are NUMBER`() {
        for (n in listOf("0", "42", "1000000")) {
            assertEquals(NordvestTokenTypes.NUMBER, tokens(n).single().second, "for '$n'")
        }
    }

    @Test
    fun `float literals are NUMBER`() {
        for (n in listOf("3.14", "0.0", "1.5")) {
            assertEquals(NordvestTokenTypes.NUMBER, tokens(n).single().second, "for '$n'")
        }
    }

    @Test
    fun `scientific notation floats are single NUMBER token`() {
        for (n in listOf("1.5e10", "2.0E-3", "9e+2")) {
            val toks = tokens(n)
            assertEquals(1, toks.size, "Expected 1 token for '$n', got: $toks")
            assertEquals(NordvestTokenTypes.NUMBER, toks[0].second)
        }
    }

    @Test
    fun `hex literals are NUMBER`() {
        assertEquals(NordvestTokenTypes.NUMBER, tokens("0xFF").single().second)
        assertEquals(NordvestTokenTypes.NUMBER, tokens("0xDEAD").single().second)
        assertEquals("0xFF", tokenTexts("0xFF").single())
    }

    @Test
    fun `binary literals are NUMBER`() {
        assertEquals(NordvestTokenTypes.NUMBER, tokens("0b1010").single().second)
        assertEquals("0b1010", tokenTexts("0b1010").single())
    }

    @Test
    fun `octal literals are NUMBER`() {
        assertEquals(NordvestTokenTypes.NUMBER, tokens("0o77").single().second)
        assertEquals("0o77", tokenTexts("0o77").single())
    }

    @Test
    fun `negative literal after whitespace is single NUMBER token`() {
        val toks = tokens(" -3")
        assertEquals(1, toks.size)
        assertEquals(NordvestTokenTypes.NUMBER, toks[0].second)
        assertEquals("-3", toks[0].first)
    }

    @Test
    fun `minus between identifier and number is OPERATOR not start of negative literal`() {
        // "x - 3" → IDENT(-), OPERATOR(-), NUMBER(3)
        val toks = tokens("x - 3")
        assertEquals(3, toks.size)
        assertEquals(NordvestTokenTypes.IDENT,    toks[0].second)
        assertEquals(NordvestTokenTypes.OPERATOR, toks[1].second)
        assertEquals("-",                          toks[1].first)
        assertEquals(NordvestTokenTypes.NUMBER,   toks[2].second)
        assertEquals("3",                          toks[2].first)
    }

    @Test
    fun `minus after number is OPERATOR not start of negative literal`() {
        val toks = tokens("1 - 2")
        assertEquals(3, toks.size)
        assertEquals(NordvestTokenTypes.NUMBER,   toks[0].second)
        assertEquals(NordvestTokenTypes.OPERATOR, toks[1].second)
        assertEquals(NordvestTokenTypes.NUMBER,   toks[2].second)
    }

    // ── String literals ───────────────────────────────────────────────────

    @Test
    fun `plain string literal is single STRING token`() {
        val toks = tokens(""""hello world"""")
        assertEquals(1, toks.size)
        assertEquals(NordvestTokenTypes.STRING, toks[0].second)
        assertEquals(""""hello world"""", toks[0].first)
    }

    @Test
    fun `string with escape sequences is single STRING token`() {
        val toks = tokens(""""line1\nline2"""")
        assertEquals(1, toks.size)
        assertEquals(NordvestTokenTypes.STRING, toks[0].second)
    }

    @Test
    fun `string with interpolation is single STRING token`() {
        // String interpolation "hello {name}!" is scanned as one token in Tier 1
        val toks = tokens(""""hello {name}!"""")
        assertEquals(1, toks.size)
        assertEquals(NordvestTokenTypes.STRING, toks[0].second)
    }

    @Test
    fun `raw string literal is STRING`() {
        val toks = tokens("""r"no \n escapes here"""")
        assertEquals(1, toks.size)
        assertEquals(NordvestTokenTypes.STRING, toks[0].second)
    }

    @Test
    fun `empty string is STRING`() {
        val toks = tokens("""""""")
        assertEquals(1, toks.size)
        assertEquals(NordvestTokenTypes.STRING, toks[0].second)
    }

    // ── Comments ──────────────────────────────────────────────────────────

    @Test
    fun `line comment to end of line is COMMENT`() {
        val toks = tokenize("// this is a comment\n")
        val comment = toks.filter { it.second == NordvestTokenTypes.COMMENT }
        assertEquals(1, comment.size)
        assertEquals("// this is a comment", comment[0].first)
    }

    @Test
    fun `block comment is COMMENT`() {
        val toks = tokenize("/* block comment */")
        val comment = toks.filter { it.second == NordvestTokenTypes.COMMENT }
        assertEquals(1, comment.size)
        assertEquals("/* block comment */", comment[0].first)
    }

    @Test
    fun `multiline block comment is single COMMENT token`() {
        val input = "/* line1\nline2\nline3 */"
        val comment = tokenize(input).filter { it.second == NordvestTokenTypes.COMMENT }
        assertEquals(1, comment.size)
    }

    @Test
    fun `code before and after line comment is tokenized correctly`() {
        // tokens() filters only whitespace; comment is kept as its own token
        val toks = tokens("let x = 1 // comment")
        assertEquals(listOf("let", "x", "=", "1", "// comment"), toks.map { it.first })
        assertEquals(
            listOf(NordvestTokenTypes.KEYWORD, NordvestTokenTypes.IDENT,
                NordvestTokenTypes.OPERATOR, NordvestTokenTypes.NUMBER,
                NordvestTokenTypes.COMMENT),
            toks.map { it.second }
        )
    }

    // ── Annotations ───────────────────────────────────────────────────────

    @Test
    fun `annotations are ANNOTATION`() {
        for (ann in listOf("@derive", "@builder", "@test", "@config", "@lazy")) {
            val toks = tokens(ann)
            assertEquals(1, toks.size, "Expected 1 token for '$ann'")
            assertEquals(NordvestTokenTypes.ANNOTATION, toks[0].second)
        }
    }

    @Test
    fun `annotation with argument @derive is ANNOTATION`() {
        // Only the @word part is the annotation token; (All) is separate tokens
        val toks = tokens("@derive(All)")
        assertEquals(NordvestTokenTypes.ANNOTATION, toks[0].second)
        assertEquals("@derive", toks[0].first)
    }

    // ── Math operators ────────────────────────────────────────────────────

    @Test
    fun `unicode math operators are MATH_OP`() {
        val mathOps = listOf("∀", "∃", "∑", "∏", "∈", "∧", "∨", "¬", "→", "≤", "≥", "≠", "÷", "⊕", "π", "∞")
        for (op in mathOps) {
            val toks = tokens(op)
            assertEquals(1, toks.size, "Expected 1 token for '$op'")
            assertEquals(NordvestTokenTypes.MATH_OP, toks[0].second, "Expected MATH_OP for '$op'")
        }
    }

    @Test
    fun `ASCII arrow operator dash-gt is MATH_OP`() {
        assertEquals(NordvestTokenTypes.MATH_OP, tokens("->").single().second)
        assertEquals("->", tokenTexts("->").single())
    }

    @Test
    fun `equality operator == is MATH_OP`() {
        assertEquals(NordvestTokenTypes.MATH_OP, tokens("==").single().second)
    }

    @Test
    fun `ASCII comparison operators are MATH_OP`() {
        assertEquals(NordvestTokenTypes.MATH_OP, tokens("<=").single().second)
        assertEquals(NordvestTokenTypes.MATH_OP, tokens(">=").single().second)
        assertEquals(NordvestTokenTypes.MATH_OP, tokens("!=").single().second)
    }

    @Test
    fun `div-assign ÷= is MATH_OP`() {
        assertEquals(NordvestTokenTypes.MATH_OP, tokens("÷=").single().second)
        assertEquals("÷=", tokenTexts("÷=").single())
    }

    @Test
    fun `xor-assign ⊕= is MATH_OP`() {
        assertEquals(NordvestTokenTypes.MATH_OP, tokens("⊕=").single().second)
    }

    // ── Regular operators ─────────────────────────────────────────────────

    @Test
    fun `assignment and arithmetic operators are OPERATOR`() {
        val ops = listOf("=", "+", "-", "*", "/", "%", "^", "~")
        for (op in ops) {
            assertEquals(NordvestTokenTypes.OPERATOR, tokens(op).single().second, "for '$op'")
        }
    }

    @Test
    fun `compound assignment operators are OPERATOR`() {
        for (op in listOf("+=", "-=", "*=", "%=", "&=", "|=")) {
            val toks = tokens(op)
            assertEquals(1, toks.size, "Expected 1 token for '$op'")
            assertEquals(NordvestTokenTypes.OPERATOR, toks[0].second, "for '$op'")
        }
    }

    @Test
    fun `shift operators are OPERATOR`() {
        assertEquals(NordvestTokenTypes.OPERATOR, tokens("<<").single().second)
        assertEquals(NordvestTokenTypes.OPERATOR, tokens(">>").single().second)
    }

    @Test
    fun `pipeline operator pipe-gt is OPERATOR`() {
        assertEquals(NordvestTokenTypes.OPERATOR, tokens("|>").single().second)
    }

    @Test
    fun `safe navigation ?dot is OPERATOR`() {
        // "?." — two tokens? No: the lexer emits ?. as a single OPERATOR
        val toks = tokens("?.")
        assertEquals(1, toks.size)
        assertEquals(NordvestTokenTypes.OPERATOR, toks[0].second)
        assertEquals("?.", toks[0].first)
    }

    @Test
    fun `null coalescing ?? is OPERATOR`() {
        assertEquals(NordvestTokenTypes.OPERATOR, tokens("??").single().second)
    }

    // ── Brackets ──────────────────────────────────────────────────────────

    @Test
    fun `brackets have dedicated token types`() {
        assertEquals(NordvestTokenTypes.LPAREN,   tokens("(").single().second)
        assertEquals(NordvestTokenTypes.RPAREN,   tokens(")").single().second)
        assertEquals(NordvestTokenTypes.LBRACKET, tokens("[").single().second)
        assertEquals(NordvestTokenTypes.RBRACKET, tokens("]").single().second)
        assertEquals(NordvestTokenTypes.LBRACE,   tokens("{").single().second)
        assertEquals(NordvestTokenTypes.RBRACE,   tokens("}").single().second)
    }

    // ── Punctuation ───────────────────────────────────────────────────────

    @Test
    fun `punctuation characters are PUNCTUATION`() {
        for (p in listOf(".", ",", ":", ";")) {
            assertEquals(NordvestTokenTypes.PUNCTUATION, tokens(p).single().second, "for '$p'")
        }
    }

    // ── BAD_CHAR ──────────────────────────────────────────────────────────

    @Test
    fun `unrecognised characters are BAD_CHAR`() {
        assertEquals(NordvestTokenTypes.BAD_CHAR, tokens("§").single().second)
        assertEquals(NordvestTokenTypes.BAD_CHAR, tokens("£").single().second)
    }

    // ── Integration: realistic snippets ──────────────────────────────────

    @Test
    fun `function declaration tokenizes correctly`() {
        val toks = tokens("fn square(x: int) → int")
        val types = toks.map { it.second }
        assertEquals(NordvestTokenTypes.KEYWORD,      types[0]) // fn
        assertEquals(NordvestTokenTypes.IDENT,        types[1]) // square
        assertEquals(NordvestTokenTypes.LPAREN,       types[2]) // (
        assertEquals(NordvestTokenTypes.IDENT,        types[3]) // x
        assertEquals(NordvestTokenTypes.PUNCTUATION,  types[4]) // :
        assertEquals(NordvestTokenTypes.BUILTIN_TYPE, types[5]) // int
        assertEquals(NordvestTokenTypes.RPAREN,       types[6]) // )
        assertEquals(NordvestTokenTypes.MATH_OP,      types[7]) // →
        assertEquals(NordvestTokenTypes.BUILTIN_TYPE, types[8]) // int
    }

    @Test
    fun `let binding with type annotation tokenizes correctly`() {
        val toks = tokens("let x: float = 3.14")
        assertEquals(NordvestTokenTypes.KEYWORD,      toks[0].second) // let
        assertEquals(NordvestTokenTypes.IDENT,        toks[1].second) // x
        assertEquals(NordvestTokenTypes.PUNCTUATION,  toks[2].second) // :
        assertEquals(NordvestTokenTypes.BUILTIN_TYPE, toks[3].second) // float
        assertEquals(NordvestTokenTypes.OPERATOR,     toks[4].second) // =
        assertEquals(NordvestTokenTypes.NUMBER,       toks[5].second) // 3.14
    }

    @Test
    fun `math quantifier expression tokenizes correctly`() {
        val toks = tokens("∀ x ∈ values")
        assertEquals(NordvestTokenTypes.MATH_OP, toks[0].second) // ∀
        assertEquals(NordvestTokenTypes.IDENT,   toks[1].second) // x
        assertEquals(NordvestTokenTypes.MATH_OP, toks[2].second) // ∈
        assertEquals(NordvestTokenTypes.IDENT,   toks[3].second) // values
    }

    @Test
    fun `annotation followed by declaration tokenizes correctly`() {
        val toks = tokens("@derive(All) class Point")
        assertEquals(NordvestTokenTypes.ANNOTATION, toks[0].second) // @derive
        assertEquals(NordvestTokenTypes.LPAREN,     toks[1].second) // (
        assertEquals(NordvestTokenTypes.IDENT,      toks[2].second) // All — not in BUILTIN_TYPE_SET
        assertEquals(NordvestTokenTypes.RPAREN,     toks[3].second) // )
        assertEquals(NordvestTokenTypes.KEYWORD,    toks[4].second) // class
        assertEquals(NordvestTokenTypes.IDENT,      toks[5].second) // Point
    }

    @Test
    fun `full coverage count — tokenizing a real snippet produces no BAD_CHAR`() {
        val code = """
            module myapp
            import std.math

            pub fn add(a: int, b: int) → int
                → a + b

            let PI: float = 3.14159
            // comment
        """.trimIndent()
        val bad = tokenize(code).filter { it.second == NordvestTokenTypes.BAD_CHAR }
        assertEquals(emptyList(), bad, "Unexpected BAD_CHAR tokens: $bad")
    }
}
