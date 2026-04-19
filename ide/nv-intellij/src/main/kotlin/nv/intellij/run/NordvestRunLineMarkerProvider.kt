package nv.intellij.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import nv.intellij.lexer.NordvestTokenTypes

/**
 * Shows a ▶ gutter icon next to `fn main(` and `@test fn` declarations.
 *
 * Because Tier 1 uses a stub parser (all tokens are leaf elements), detection
 * is based on token text pattern-matching rather than semantic PSI node types.
 * Tier 2 (PSI) will replace this with proper structural navigation.
 *
 * ### Detection rules
 * - **fn main** — an IDENT token with text "main" whose line also contains "fn"
 *   at the start of the non-whitespace content.
 * - **@test fn** — an IDENT token immediately after a `@test` annotation on
 *   the same or preceding logical line.
 */
class NordvestRunLineMarkerProvider : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        if (element !is LeafPsiElement) return null
        if (element.elementType != NordvestTokenTypes.IDENT) return null

        val doc      = element.containingFile?.viewProvider?.document ?: return null
        val offset   = element.textOffset
        val lineNum  = doc.getLineNumber(offset)
        val lineStart = doc.getLineStartOffset(lineNum)
        val lineEnd   = doc.getLineEndOffset(lineNum)
        val lineText  = doc.text.substring(lineStart, lineEnd).trim()

        return when {
            element.text == "main" && lineText.startsWith("fn main") ->
                Info(AllIcons.RunConfigurations.TestState.Run, { "Run '${element.text}'" }, *runActions())

            element.text.startsWith("test") && lineText.contains(Regex("@test\\s+fn\\s")) ->
                Info(AllIcons.RunConfigurations.TestState.Run, { "Test '${element.text}'" }, *runActions())

            else -> null
        }
    }

    private fun runActions(): Array<AnAction> = emptyArray()
}
