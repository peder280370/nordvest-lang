package nv.intellij.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFileFactory
import nv.intellij.lang.NordvestFile
import nv.intellij.lang.NordvestFileType
import nv.intellij.lexer.NordvestTokenTypes
import nv.intellij.psi.elements.NordvestFunctionDef

/**
 * Inspection: ASCII operator instead of Unicode equivalent.
 *
 * Nordvest supports both ASCII and Unicode forms of several operators.
 * This inspection flags ASCII forms and offers a quick-fix to replace them
 * with their Unicode equivalents, keeping source code consistent with the
 * mathematical notation style of the language.
 *
 * Replacements offered:
 *   `>=`  →  `≥`
 *   `<=`  →  `≤`
 *   `!=`  →  `≠`
 *   `->`  →  `→`
 */
class AsciiToUnicodeInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "ASCII operator instead of Unicode equivalent"
    override fun getGroupDisplayName(): String = "Nordvest"
    override fun getShortName(): String = "NordvestAsciiOperator"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.containingFile !is NordvestFile) return
                val tokenType = element.node?.elementType ?: return
                if (tokenType != NordvestTokenTypes.OPERATOR && tokenType != NordvestTokenTypes.MATH_OP) return

                val text = element.text
                val unicode = ASCII_TO_UNICODE[text] ?: return

                holder.registerProblem(
                    element,
                    "Operator '$text' has a Unicode equivalent '$unicode'",
                    ProblemHighlightType.WEAK_WARNING,
                    ReplaceWithUnicodeQuickFix(unicode),
                )
            }
        }
    }

    // ── Quick-fix ─────────────────────────────────────────────────────────

    private class ReplaceWithUnicodeQuickFix(private val unicode: String) : LocalQuickFix {
        override fun getName(): String = "Replace with '$unicode'"
        override fun getFamilyName(): String = "Replace ASCII operator with Unicode"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            // Create a minimal temporary file containing just the unicode token,
            // then take its first leaf to use as the replacement element.
            val factory = PsiFileFactory.getInstance(project)
            val tmp = factory.createFileFromText(
                "tmp.nv",
                NordvestFileType,
                unicode,
            ) as? NordvestFile ?: return
            val replacement = tmp.firstChild ?: return
            element.replace(replacement)
        }
    }

    companion object {
        /** Maps ASCII multi-character operators to their Unicode equivalents. */
        val ASCII_TO_UNICODE: Map<String, String> = mapOf(
            ">=" to "≥",
            "<=" to "≤",
            "!=" to "≠",
            "->" to "→",
        )
    }
}
