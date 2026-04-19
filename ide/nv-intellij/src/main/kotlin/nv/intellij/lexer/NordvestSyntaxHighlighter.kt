package nv.intellij.lexer

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/** Maps Nordvest token types to editor colour keys. */
class NordvestSyntaxHighlighter : SyntaxHighlighterBase() {

    companion object {
        // ── Colour key declarations ────────────────────────────────────────
        @JvmField val KEYWORD      = createTextAttributesKey("NV_KEYWORD",      Default.KEYWORD)
        @JvmField val ANNOTATION   = createTextAttributesKey("NV_ANNOTATION",   Default.METADATA)
        @JvmField val BUILTIN_TYPE = createTextAttributesKey("NV_BUILTIN_TYPE", Default.CLASS_NAME)
        @JvmField val IDENT        = createTextAttributesKey("NV_IDENT",        Default.IDENTIFIER)
        @JvmField val NUMBER       = createTextAttributesKey("NV_NUMBER",       Default.NUMBER)
        @JvmField val STRING       = createTextAttributesKey("NV_STRING",       Default.STRING)
        @JvmField val COMMENT      = createTextAttributesKey("NV_COMMENT",      Default.LINE_COMMENT)
        /**
         * Unicode math operators (∀ ∃ ∑ ∏ ∈ ∧ ∨ ¬ → ≤ ≥ ≠ ÷ ⊕ π ∞) and their
         * ASCII counterparts (-> <= >= != ==).  Defaults to a slightly different
         * colour from the ordinary operator so mathematical notation stands out.
         */
        @JvmField val MATH_OP      = createTextAttributesKey("NV_MATH_OP",     Default.KEYWORD)
        @JvmField val OPERATOR     = createTextAttributesKey("NV_OPERATOR",    Default.OPERATION_SIGN)
        @JvmField val PUNCTUATION  = createTextAttributesKey("NV_PUNCTUATION", Default.DOT)
        @JvmField val BAD_CHAR     = createTextAttributesKey("NV_BAD_CHAR",    HighlighterColors.BAD_CHARACTER)

        private val EMPTY = emptyArray<TextAttributesKey>()

        private val TOKEN_MAP: Map<IElementType, Array<TextAttributesKey>> = mapOf(
            NordvestTokenTypes.KEYWORD      to arrayOf(KEYWORD),
            NordvestTokenTypes.ANNOTATION   to arrayOf(ANNOTATION),
            NordvestTokenTypes.BUILTIN_TYPE to arrayOf(BUILTIN_TYPE),
            NordvestTokenTypes.IDENT        to arrayOf(IDENT),
            NordvestTokenTypes.NUMBER       to arrayOf(NUMBER),
            NordvestTokenTypes.STRING       to arrayOf(STRING),
            NordvestTokenTypes.COMMENT      to arrayOf(COMMENT),
            NordvestTokenTypes.MATH_OP      to arrayOf(MATH_OP),
            NordvestTokenTypes.OPERATOR     to arrayOf(OPERATOR),
            NordvestTokenTypes.PUNCTUATION  to arrayOf(PUNCTUATION),
            NordvestTokenTypes.LPAREN       to arrayOf(PUNCTUATION),
            NordvestTokenTypes.RPAREN       to arrayOf(PUNCTUATION),
            NordvestTokenTypes.LBRACKET     to arrayOf(PUNCTUATION),
            NordvestTokenTypes.RBRACKET     to arrayOf(PUNCTUATION),
            NordvestTokenTypes.LBRACE       to arrayOf(PUNCTUATION),
            NordvestTokenTypes.RBRACE       to arrayOf(PUNCTUATION),
            NordvestTokenTypes.BAD_CHAR     to arrayOf(BAD_CHAR),
        )
    }

    override fun getHighlightingLexer(): Lexer = NordvestLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        TOKEN_MAP[tokenType] ?: EMPTY
}
