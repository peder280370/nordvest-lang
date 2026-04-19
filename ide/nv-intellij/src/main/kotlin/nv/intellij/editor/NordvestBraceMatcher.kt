package nv.intellij.editor

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import nv.intellij.lexer.NordvestTokenTypes

/**
 * Bracket-matching for Nordvest: `()`, `[]`, `{}`.
 *
 * `[[` / `]]` (matrix indexing) are matched by the two nested `[]` pairs —
 * IntelliJ highlights both simultaneously.
 */
class NordvestBraceMatcher : PairedBraceMatcher {

    private val pairs = arrayOf(
        BracePair(NordvestTokenTypes.LPAREN,   NordvestTokenTypes.RPAREN,   false),
        BracePair(NordvestTokenTypes.LBRACKET, NordvestTokenTypes.RBRACKET, false),
        BracePair(NordvestTokenTypes.LBRACE,   NordvestTokenTypes.RBRACE,   false),
    )

    override fun getPairs() = pairs

    // First parameter is @NotNull in the interface (changed in 2025.x)
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset
}
