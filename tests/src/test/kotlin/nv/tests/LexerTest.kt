package nv.tests

import nv.compiler.lexer.Lexer
import nv.compiler.lexer.LexerError
import nv.compiler.lexer.Token
import nv.compiler.lexer.TokenKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LexerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun lex(src: String): List<Token> = Lexer(src).tokenize()

    /** Return just the kinds (drops EOF for brevity unless caller passes includeEof=true). */
    private fun kinds(src: String, includeEof: Boolean = false): List<TokenKind> {
        val tokens = lex(src)
        return if (includeEof) tokens.map { it.kind }
        else tokens.dropLast(1).map { it.kind }
    }

    /** Return (kind, text) pairs, dropping the trailing EOF. */
    private fun kindTexts(src: String): List<Pair<TokenKind, String>> =
        lex(src).dropLast(1).map { it.kind to it.text }

    // ── Keywords ─────────────────────────────────────────────────────────────

    @Nested
    inner class Keywords {
        @Test fun `module keyword`() = assertEquals(TokenKind.MODULE, kinds("module").single())
        @Test fun `import keyword`() = assertEquals(TokenKind.IMPORT, kinds("import").single())
        @Test fun `fn keyword`() = assertEquals(TokenKind.FN, kinds("fn").single())
        @Test fun `let keyword`() = assertEquals(TokenKind.LET, kinds("let").single())
        @Test fun `var keyword`() = assertEquals(TokenKind.VAR, kinds("var").single())
        @Test fun `if keyword`() = assertEquals(TokenKind.IF, kinds("if").single())
        @Test fun `else keyword`() = assertEquals(TokenKind.ELSE, kinds("else").single())
        @Test fun `then keyword`() = assertEquals(TokenKind.THEN, kinds("then").single())
        @Test fun `for keyword`() = assertEquals(TokenKind.FOR, kinds("for").single())
        @Test fun `while keyword`() = assertEquals(TokenKind.WHILE, kinds("while").single())
        @Test fun `match keyword`() = assertEquals(TokenKind.MATCH, kinds("match").single())
        @Test fun `return keyword`() = assertEquals(TokenKind.RETURN, kinds("return").single())
        @Test fun `class keyword`() = assertEquals(TokenKind.CLASS, kinds("class").single())
        @Test fun `struct keyword`() = assertEquals(TokenKind.STRUCT, kinds("struct").single())
        @Test fun `record keyword`() = assertEquals(TokenKind.RECORD, kinds("record").single())
        @Test fun `interface keyword`() = assertEquals(TokenKind.INTERFACE, kinds("interface").single())
        @Test fun `sealed keyword`() = assertEquals(TokenKind.SEALED, kinds("sealed").single())
        @Test fun `enum keyword`() = assertEquals(TokenKind.ENUM, kinds("enum").single())
        @Test fun `extend keyword`() = assertEquals(TokenKind.EXTEND, kinds("extend").single())
        @Test fun `nil keyword`() = assertEquals(TokenKind.NIL, kinds("nil").single())
        @Test fun `true keyword`() = assertEquals(TokenKind.TRUE, kinds("true").single())
        @Test fun `false keyword`() = assertEquals(TokenKind.FALSE, kinds("false").single())
        @Test fun `in keyword`() = assertEquals(TokenKind.IN, kinds("in").single())
        @Test fun `is keyword`() = assertEquals(TokenKind.IS, kinds("is").single())
        @Test fun `as keyword`() = assertEquals(TokenKind.AS, kinds("as").single())
        @Test fun `xor keyword`() = assertEquals(TokenKind.XOR, kinds("xor").single())
        @Test fun `throws keyword`() = assertEquals(TokenKind.THROWS, kinds("throws").single())
        @Test fun `throw keyword`() = assertEquals(TokenKind.THROW, kinds("throw").single())
        @Test fun `try keyword`() = assertEquals(TokenKind.TRY, kinds("try").single())
        @Test fun `catch keyword`() = assertEquals(TokenKind.CATCH, kinds("catch").single())
        @Test fun `defer keyword`() = assertEquals(TokenKind.DEFER, kinds("defer").single())
        @Test fun `async keyword`() = assertEquals(TokenKind.ASYNC, kinds("async").single())
        @Test fun `await keyword`() = assertEquals(TokenKind.AWAIT, kinds("await").single())
        @Test fun `yield keyword`() = assertEquals(TokenKind.YIELD, kinds("yield").single())
        @Test fun `weak keyword`() = assertEquals(TokenKind.WEAK, kinds("weak").single())
        @Test fun `unsafe keyword`() = assertEquals(TokenKind.UNSAFE, kinds("unsafe").single())
        @Test fun `guard keyword`() = assertEquals(TokenKind.GUARD, kinds("guard").single())
        @Test fun `where keyword`() = assertEquals(TokenKind.WHERE, kinds("where").single())
    }

    // ── Identifiers ──────────────────────────────────────────────────────────

    @Nested
    inner class Identifiers {
        @Test fun `simple identifier`() {
            val t = lex("hello").first()
            assertEquals(TokenKind.IDENT, t.kind)
            assertEquals("hello", t.text)
        }

        @Test fun `identifier with digits and underscores`() {
            val t = lex("foo_bar2").first()
            assertEquals(TokenKind.IDENT, t.kind)
            assertEquals("foo_bar2", t.text)
        }

        @Test fun `underscore-prefixed identifier`() {
            val t = lex("_private").first()
            assertEquals(TokenKind.IDENT, t.kind)
            assertEquals("_private", t.text)
        }

        @Test fun `'e' is emitted as IDENT (context-sensitive constant)`() {
            val t = lex("e").first()
            assertEquals(TokenKind.IDENT, t.kind)
            assertEquals("e", t.text)
        }

        @Test fun `keyword-prefixed identifier is an IDENT`() {
            // 'module2' starts with 'module' but is a valid identifier
            val t = lex("module2").first()
            assertEquals(TokenKind.IDENT, t.kind)
        }

        @Test fun `Unicode Greek identifier`() {
            val t = lex("αβγ").first()
            assertEquals(TokenKind.IDENT, t.kind)
            assertEquals("αβγ", t.text)
        }

        @Test fun `multiple identifiers on one line`() {
            assertEquals(
                listOf(TokenKind.IDENT, TokenKind.IDENT),
                kinds("foo bar"),
            )
        }
    }

    // ── Integer literals ─────────────────────────────────────────────────────

    @Nested
    inner class IntegerLiterals {
        @Test fun `decimal`() = assertEquals("42", lex("42").first().text)
        @Test fun `decimal with underscores`() = assertEquals("1_000_000", lex("1_000_000").first().text)
        @Test fun `zero`() { val t = lex("0").first(); assertEquals(TokenKind.INT_LIT, t.kind); assertEquals("0", t.text) }
        @Test fun `hex`() { val t = lex("0xFF_00").first(); assertEquals(TokenKind.INT_LIT, t.kind); assertEquals("0xFF_00", t.text) }
        @Test fun `binary`() { val t = lex("0b1010_1111").first(); assertEquals(TokenKind.INT_LIT, t.kind) }
        @Test fun `octal`() { val t = lex("0o755").first(); assertEquals(TokenKind.INT_LIT, t.kind) }
    }

    // ── Float literals ───────────────────────────────────────────────────────

    @Nested
    inner class FloatLiterals {
        @Test fun `basic float`() { val t = lex("3.14").first(); assertEquals(TokenKind.FLOAT_LIT, t.kind); assertEquals("3.14", t.text) }
        @Test fun `float with exponent`() { val t = lex("1.5e-3").first(); assertEquals(TokenKind.FLOAT_LIT, t.kind) }
        @Test fun `float with positive exponent`() { val t = lex("1.0e+10").first(); assertEquals(TokenKind.FLOAT_LIT, t.kind) }
        @Test fun `integer-like with exponent is float`() { val t = lex("1e5").first(); assertEquals(TokenKind.FLOAT_LIT, t.kind) }
    }

    // ── Character literals ───────────────────────────────────────────────────

    @Nested
    inner class CharLiterals {
        @Test fun `simple char`() { val t = lex("'a'").first(); assertEquals(TokenKind.CHAR_LIT, t.kind); assertEquals("'a'", t.text) }
        @Test fun `newline escape`() { val t = lex("'\\n'").first(); assertEquals(TokenKind.CHAR_LIT, t.kind) }
        @Test fun `unicode escape`() { val t = lex("'\\u0041'").first(); assertEquals(TokenKind.CHAR_LIT, t.kind) }
    }

    // ── Plain string literals ────────────────────────────────────────────────

    @Nested
    inner class StringLiterals {
        @Test fun `empty string produces STR_START STR_END`() {
            assertEquals(
                listOf(TokenKind.STR_START, TokenKind.STR_END),
                kinds("\"\""),
            )
        }

        @Test fun `plain string`() {
            val toks = kindTexts("\"hello\"")
            assertEquals(TokenKind.STR_START, toks[0].first)
            assertEquals(TokenKind.STR_TEXT,  toks[1].first); assertEquals("hello", toks[1].second)
            assertEquals(TokenKind.STR_END,   toks[2].first)
        }

        @Test fun `string with escape sequence`() {
            val toks = kindTexts("\"tab\\there\"")
            assertEquals(TokenKind.STR_TEXT, toks[1].first)
            assertEquals("tab\\there", toks[1].second)
        }
    }

    // ── String interpolation ─────────────────────────────────────────────────

    @Nested
    inner class StringInterpolation {
        @Test fun `simple interpolation`() {
            // "hello {name}!"
            val toks = kinds("\"hello {name}!\"")
            assertEquals(
                listOf(
                    TokenKind.STR_START,
                    TokenKind.STR_TEXT,    // "hello "
                    TokenKind.INTERP_START,
                    TokenKind.IDENT,       // name
                    TokenKind.INTERP_END,
                    TokenKind.STR_TEXT,    // "!"
                    TokenKind.STR_END,
                ),
                toks,
            )
        }

        @Test fun `interpolation at start`() {
            // "{x} hi" — no STR_TEXT before the first { (empty text is not emitted)
            val toks = kinds("\"{x} hi\"")
            assertEquals(TokenKind.STR_START,    toks[0])
            assertEquals(TokenKind.INTERP_START, toks[1])
            assertEquals(TokenKind.IDENT,        toks[2])
            assertEquals(TokenKind.INTERP_END,   toks[3])
            assertEquals(TokenKind.STR_TEXT,     toks[4]) // " hi"
            assertEquals(TokenKind.STR_END,      toks[5])
        }

        @Test fun `multiple interpolations`() {
            // "{a}{b}" — no empty STR_TEXT tokens between/around interpolations
            val toks = kinds("\"{a}{b}\"")
            val expected = listOf(
                TokenKind.STR_START,
                TokenKind.INTERP_START,
                TokenKind.IDENT,       // a
                TokenKind.INTERP_END,
                TokenKind.INTERP_START,
                TokenKind.IDENT,       // b
                TokenKind.INTERP_END,
                TokenKind.STR_END,
            )
            assertEquals(expected, toks)
        }
    }

    // ── Raw string literals ──────────────────────────────────────────────────

    @Nested
    inner class RawStringLiterals {
        @Test fun `single-line raw string`() {
            val t = lex("r\"no \\n escapes\"").first()
            assertEquals(TokenKind.RAW_STRING_LIT, t.kind)
        }

        @Test fun `multi-line raw string`() {
            val src = "r\"\"\"line1\nline2\"\"\""
            val t = lex(src).first()
            assertEquals(TokenKind.RAW_STRING_LIT, t.kind)
        }
    }

    // ── Unicode operators ────────────────────────────────────────────────────

    @Nested
    inner class UnicodeOperators {
        @Test fun `forall ∀`() = assertEquals(TokenKind.FORALL,    kinds("∀").single())
        @Test fun `exists ∃`() = assertEquals(TokenKind.EXISTS,    kinds("∃").single())
        @Test fun `sum ∑`() = assertEquals(TokenKind.SUM,          kinds("∑").single())
        @Test fun `product ∏`() = assertEquals(TokenKind.PRODUCT,  kinds("∏").single())
        @Test fun `elem_of ∈`() = assertEquals(TokenKind.ELEM_OF,  kinds("∈").single())
        @Test fun `and ∧`() = assertEquals(TokenKind.AND,          kinds("∧").single())
        @Test fun `or ∨`() = assertEquals(TokenKind.OR,            kinds("∨").single())
        @Test fun `not ¬`() = assertEquals(TokenKind.NOT,          kinds("¬").single())
        @Test fun `arrow →`() = assertEquals(TokenKind.ARROW,      kinds("→").single())
        @Test fun `leq ≤`() = assertEquals(TokenKind.LEQ,          kinds("≤").single())
        @Test fun `geq ≥`() = assertEquals(TokenKind.GEQ,          kinds("≥").single())
        @Test fun `neq ≠`() = assertEquals(TokenKind.NEQ,          kinds("≠").single())
        @Test fun `int_div ÷`() = assertEquals(TokenKind.INT_DIV,  kinds("÷").single())
        @Test fun `xor_op ⊕`() = assertEquals(TokenKind.XOR_OP,   kinds("⊕").single())
        @Test fun `const_pi π`() = assertEquals(TokenKind.CONST_PI, kinds("π").single())
        @Test fun `const_inf ∞`() = assertEquals(TokenKind.CONST_INF, kinds("∞").single())
    }

    // ── ASCII operator aliases ────────────────────────────────────────────────

    @Nested
    inner class AsciiOperators {
        @Test fun `and &&`() = assertEquals(TokenKind.AND, kinds("&&").single())
        @Test fun `or ||`() = assertEquals(TokenKind.OR,   kinds("||").single())
        @Test fun `not !`() = assertEquals(TokenKind.BANG, kinds("!").single())
        @Test fun `neq !=`() = assertEquals(TokenKind.NEQ, kinds("!=").single())
        @Test fun `leq ascii`() = assertEquals(TokenKind.LEQ, kinds("<=").single())
        @Test fun `geq ascii`() = assertEquals(TokenKind.GEQ, kinds(">=").single())
        @Test fun `eq ascii`() = assertEquals(TokenKind.EQ,   kinds("==").single())
        @Test fun `arrow ascii`() = assertEquals(TokenKind.ARROW, kinds("->").single())
    }

    // ── Maximal munch ────────────────────────────────────────────────────────

    @Nested
    inner class MaximalMunch {
        @Test fun `pipeline vs pipe`() {
            assertEquals(listOf(TokenKind.PIPELINE), kinds("|>"))
            assertEquals(listOf(TokenKind.PIPE),     kinds("|"))
        }

        @Test fun `?? vs ?`() {
            assertEquals(listOf(TokenKind.NULL_COALESCE), kinds("??"))
            assertEquals(listOf(TokenKind.QUEST),         kinds("?"))
        }

        @Test fun `?dot vs ?`() {
            assertEquals(listOf(TokenKind.DOT_QUEST), kinds("?."))
        }

        @Test fun `lshift assign`() {
            assertEquals(listOf(TokenKind.LSHIFT_ASSIGN), kinds("<<="))
            assertEquals(listOf(TokenKind.LSHIFT),        kinds("<<"))
            assertEquals(listOf(TokenKind.LT),            kinds("<"))
        }

        @Test fun `rshift assign`() {
            assertEquals(listOf(TokenKind.RSHIFT_ASSIGN), kinds(">>="))
            assertEquals(listOf(TokenKind.RSHIFT),        kinds(">>"))
            assertEquals(listOf(TokenKind.GT),            kinds(">"))
        }

        @Test fun `xor compound assignment`() = assertEquals(listOf(TokenKind.XOR_ASSIGN),     kinds("⊕="))
        @Test fun `int div compound assignment`() = assertEquals(listOf(TokenKind.INT_DIV_ASSIGN),  kinds("÷="))
        @Test fun `plus assign compound`() = assertEquals(listOf(TokenKind.PLUS_ASSIGN),  kinds("+="))
        @Test fun `minus assign compound`() = assertEquals(listOf(TokenKind.MINUS_ASSIGN), kinds("-="))
        @Test fun `star assign compound`() = assertEquals(listOf(TokenKind.STAR_ASSIGN),  kinds("*="))
        @Test fun `slash assign compound`() = assertEquals(listOf(TokenKind.SLASH_ASSIGN), kinds("/="))

        @Test fun `slash-slash is a comment not two slashes`() {
            // "// comment" produces only EOF (comment consumed)
            assertEquals(emptyList<TokenKind>(), kinds("// comment"))
        }
    }

    // ── INDENT / DEDENT ──────────────────────────────────────────────────────

    @Nested
    inner class IndentDedent {
        @Test fun `single indent and dedent`() {
            val src = "fn main\n    pass\n"
            // Expected: IDENT IDENT NEWLINE INDENT IDENT NEWLINE DEDENT
            val k = kinds(src)
            assert(k.contains(TokenKind.INDENT)) { "Expected INDENT in $k" }
            assert(k.contains(TokenKind.DEDENT)) { "Expected DEDENT in $k" }
        }

        @Test fun `dedent at EOF emits DEDENT`() {
            val src = "a\n    b\n"
            val k = kinds(src)
            assert(k.contains(TokenKind.DEDENT))
        }

        @Test fun `blank lines do not emit extra NEWLINEs`() {
            val src = "a\n\n\nb\n"
            val k = kinds(src)
            // Should have exactly two NEWLINEs (after 'a' and after 'b'), not four
            assertEquals(2, k.count { it == TokenKind.NEWLINE })
        }

        @Test fun `two-level nesting`() {
            val src = "a\n    b\n        c\n    d\ne\n"
            val k = kinds(src)
            assertEquals(2, k.count { it == TokenKind.INDENT })
            assertEquals(2, k.count { it == TokenKind.DEDENT })
        }

        @Test fun `indent mismatch throws`() {
            // Indent by 4, then dedent to 2 (which was never a level on the stack)
            val src = "a\n    b\n  c\n"
            assertThrows<LexerError.IndentMismatch> { lex(src) }
        }

        @Test fun `mixed indentation throws`() {
            val src = "a\n\t b\n" // tab then space
            assertThrows<LexerError.MixedIndentation> { lex(src) }
        }

        @Test fun `comment lines do not affect indentation`() {
            val src = "a\n    b\n    // comment\n    c\n"
            val k = kinds(src)
            assertEquals(1, k.count { it == TokenKind.INDENT })
            assertEquals(1, k.count { it == TokenKind.DEDENT })
        }
    }

    // ── NEWLINE suppression ──────────────────────────────────────────────────

    @Nested
    inner class NewlineSuppression {
        @Test fun `newline inside parens is suppressed`() {
            val src = "(\na\n)"
            val k = kinds(src)
            assert(TokenKind.NEWLINE !in k) { "Expected no NEWLINE inside parens, got $k" }
        }

        @Test fun `newline inside brackets is suppressed`() {
            val src = "[\na\n]"
            val k = kinds(src)
            assert(TokenKind.NEWLINE !in k) { "Expected no NEWLINE inside brackets, got $k" }
        }
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    @Nested
    inner class Comments {
        @Test fun `line comment is consumed silently`() {
            assertEquals(emptyList<TokenKind>(), kinds("// this is a comment"))
        }

        @Test fun `block comment is consumed silently`() {
            assertEquals(emptyList<TokenKind>(), kinds("/* block */"))
        }

        @Test fun `doc comment produces DOC_COMMENT token`() {
            val k = kinds("/** doc */")
            assertEquals(listOf(TokenKind.DOC_COMMENT), k)
        }

        @Test fun `tokens after line comment on same line are skipped`() {
            // "x // comment\ny" — only x and y tokens (and their NEWLINE)
            val k = kinds("x // comment\ny")
            assertEquals(listOf(TokenKind.IDENT, TokenKind.NEWLINE, TokenKind.IDENT), k)
        }
    }

    // ── Error cases ──────────────────────────────────────────────────────────

    @Nested
    inner class Errors {
        @Test fun `unterminated string throws`() {
            assertThrows<LexerError.UnterminatedString> { lex("\"hello") }
        }

        @Test fun `unterminated block comment throws`() {
            assertThrows<LexerError.UnterminatedBlockComment> { lex("/* unclosed") }
        }

        @Test fun `unexpected character throws`() {
            assertThrows<LexerError.UnexpectedChar> { lex("`") }
        }
    }

    // ── Source locations ─────────────────────────────────────────────────────

    @Nested
    inner class SourceLocations {
        @Test fun `first token starts at line 1 col 1`() {
            val t = lex("x").first()
            assertEquals(1, t.span.start.line)
            assertEquals(1, t.span.start.col)
        }

        @Test fun `second token on same line has correct column`() {
            val tokens = lex("x y")
            assertEquals(1, tokens[0].span.start.col)
            assertEquals(3, tokens[1].span.start.col)
        }

        @Test fun `token on second line has line=2`() {
            val tokens = lex("a\nb")
            // tokens: IDENT(a) NEWLINE IDENT(b) EOF
            val bTok = tokens.first { it.text == "b" }
            assertEquals(2, bTok.span.start.line)
        }
    }

    // ── Full snippet ─────────────────────────────────────────────────────────

    @Nested
    inner class FullSnippets {
        @Test fun `hello world function`() {
            val src = """
                fn main()
                    print("Hello, world!")
            """.trimIndent() + "\n"
            val k = kinds(src)
            // Should contain FN, IDENT(main), LPAREN, RPAREN, NEWLINE, INDENT, IDENT(print), ...
            assert(TokenKind.FN in k)
            assert(TokenKind.INDENT in k)
            assert(TokenKind.STR_START in k)
        }

        @Test fun `variable declaration`() {
            val src = "let x: int = 42\n"
            val k = kinds(src)
            assertEquals(
                listOf(TokenKind.LET, TokenKind.IDENT, TokenKind.COLON,
                       TokenKind.IDENT, TokenKind.ASSIGN, TokenKind.INT_LIT,
                       TokenKind.NEWLINE),
                k,
            )
        }

        @Test fun `unicode math expression`() {
            val src = "∀ x ∈ values: x > 0\n"
            val k = kinds(src)
            assert(TokenKind.FORALL in k)
            assert(TokenKind.ELEM_OF in k)
        }
    }
}
