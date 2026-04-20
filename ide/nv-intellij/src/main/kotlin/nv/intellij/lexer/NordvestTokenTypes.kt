package nv.intellij.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import nv.intellij.lang.NordvestLanguage

/** IElementType constants for every Nordvest token category. */
object NordvestTokenTypes {

    // ── Structural ─────────────────────────────────────────────────────────
    @JvmField val WHITESPACE  = TokenType.WHITE_SPACE
    @JvmField val BAD_CHAR    = TokenType.BAD_CHARACTER
    @JvmField val COMMENT     = IElementType("COMMENT",     NordvestLanguage)

    // ── Keywords ───────────────────────────────────────────────────────────
    @JvmField val KEYWORD     = IElementType("KEYWORD",     NordvestLanguage)

    // ── Annotations (@derive, @builder …) ─────────────────────────────────
    @JvmField val ANNOTATION  = IElementType("ANNOTATION",  NordvestLanguage)

    // ── Built-in type names (int, float, str, bool, …) ────────────────────
    @JvmField val BUILTIN_TYPE = IElementType("BUILTIN_TYPE", NordvestLanguage)

    // ── Identifiers ────────────────────────────────────────────────────────
    @JvmField val IDENT       = IElementType("IDENT",       NordvestLanguage)

    // ── Literals ───────────────────────────────────────────────────────────
    @JvmField val NUMBER      = IElementType("NUMBER",      NordvestLanguage)
    /** String body text (quoted region including any {…} interpolation). */
    @JvmField val STRING      = IElementType("STRING",      NordvestLanguage)

    // ── Mathematical / logical operators (Unicode) ─────────────────────────
    /** ∀ ∃ ∑ ∏ ∈ ∧ ∨ ¬ → ≤ ≥ ≠ ÷ ⊕ π ∞ and ASCII equivalents. */
    @JvmField val MATH_OP     = IElementType("MATH_OP",     NordvestLanguage)

    // ── Operators and punctuation ──────────────────────────────────────────
    @JvmField val OPERATOR    = IElementType("OPERATOR",    NordvestLanguage)
    @JvmField val PUNCTUATION = IElementType("PUNCTUATION", NordvestLanguage)

    // ── Bracket tokens (distinct types required by PairedBraceMatcher) ────
    @JvmField val LPAREN      = IElementType("LPAREN",      NordvestLanguage)
    @JvmField val RPAREN      = IElementType("RPAREN",      NordvestLanguage)
    @JvmField val LBRACKET    = IElementType("LBRACKET",    NordvestLanguage)
    @JvmField val RBRACKET    = IElementType("RBRACKET",    NordvestLanguage)
    @JvmField val LBRACE      = IElementType("LBRACE",      NordvestLanguage)
    @JvmField val RBRACE      = IElementType("RBRACE",      NordvestLanguage)

    // ── Token sets used by ParserDefinition ───────────────────────────────
    @JvmField val COMMENTS       = TokenSet.create(COMMENT)
    @JvmField val STRINGS        = TokenSet.create(STRING)
    @JvmField val WHITESPACE_SET = TokenSet.create(WHITESPACE)

    // ── Keywords (full set mirroring compiler TokenKind) ──────────────────
    val KEYWORD_SET: Set<String> = setOf(
        "module", "import", "pub", "pkg",
        "fn", "let", "var", "return",
        "if", "else", "then",
        "for", "while", "break", "continue",
        "match",
        "class", "struct", "record", "interface", "sealed", "enum", "extend",
        "type", "init", "self", "super",
        "true", "false", "nil",
        "in", "is", "as",
        "throws", "throw", "try", "catch", "finally",
        "defer",
        "go", "spawn", "select", "from", "after", "default",
        "async", "await", "yield",
        "weak", "unowned", "unsafe",
        "where", "get", "set", "by", "xor",
        "guard", "override",
    )

    // ── Built-in type names ────────────────────────────────────────────────
    val BUILTIN_TYPE_SET: Set<String> = setOf(
        "int", "float", "str", "bool", "void",
        "Result", "Ok", "Err", "Option", "Some", "None",
        "Sequence", "Iterator", "Iterable",
        "Channel", "Future", "GpuBuffer",
        "Duration",
    )
}
