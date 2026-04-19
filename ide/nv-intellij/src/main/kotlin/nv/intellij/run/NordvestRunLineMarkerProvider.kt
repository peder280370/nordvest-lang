package nv.intellij.run

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
                Info(AllIcons.RunConfigurations.TestState.Run, { "Run 'main'" },
                    *launchActions(element, isTest = false))

            element.text.startsWith("test") && lineText.contains(Regex("@test\\s+fn\\s")) ->
                Info(AllIcons.RunConfigurations.TestState.Run, { "Run test '${element.text}'" },
                    *launchActions(element, isTest = true))

            else -> null
        }
    }

    /**
     * Returns a single "Run" action that creates (or reuses) a [NordvestRunConfiguration]
     * for the containing file and executes it via the default Run executor.
     */
    private fun launchActions(anchor: PsiElement, isTest: Boolean): Array<AnAction> {
        val label = if (isTest) "Run Test" else "Run 'main'"
        return arrayOf(object : AnAction(label, null, AllIcons.RunConfigurations.TestState.Run) {
            override fun actionPerformed(e: AnActionEvent) {
                val project = anchor.project
                val vFile   = anchor.containingFile?.virtualFile ?: return
                val type    = ConfigurationTypeUtil
                    .findConfigurationType(NordvestRunConfigurationType::class.java)
                val factory = type.configurationFactories
                    .first { if (isTest) it.id == "NordvestTest" else it.id == "NordvestRun" }
                val runManager = RunManager.getInstance(project)

                // Reuse an existing config for this file rather than creating duplicates.
                val settings = runManager.allConfigurationsList
                    .filterIsInstance<NordvestRunConfiguration>()
                    .find { it.filePath == vFile.path && it.isTest == isTest }
                    ?.let { runManager.findSettings(it) }
                    ?: run {
                        val cfg = factory.createTemplateConfiguration(project) as NordvestRunConfiguration
                        cfg.filePath = vFile.path
                        cfg.name = "${if (isTest) "nv test" else "nv run"} ${vFile.nameWithoutExtension}"
                        runManager.createConfiguration(cfg, factory).also { s ->
                            runManager.addConfiguration(s)
                        }
                    }

                runManager.selectedConfiguration = settings
                val executor = ExecutorRegistry.getInstance()
                    .getExecutorById(DefaultRunExecutor.EXECUTOR_ID) ?: return
                ProgramRunnerUtil.executeConfiguration(settings, executor)
            }
        })
    }
}
