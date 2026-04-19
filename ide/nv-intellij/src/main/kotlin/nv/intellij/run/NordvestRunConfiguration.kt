package nv.intellij.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element

/**
 * Holds the data for a single Nordvest run/test configuration.
 *
 * @param isTest  When true, the configuration runs `nv test` instead of `nv run`.
 */
class NordvestRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
    val isTest: Boolean = false,
) : RunConfigurationBase<Element>(project, factory, name) {

    /** Absolute path to the `.nv` source file (or directory for `nv test`). */
    var filePath: String = ""

    /** Extra arguments appended to the command (e.g. `-o output` or a test filter). */
    var extraArgs: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        NordvestRunConfigurationEditor(project)

    override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState =
        NordvestRunState(env, this)

    override fun checkConfiguration() {
        if (!isTest && filePath.isBlank()) {
            throw ExecutionException("No Nordvest source file specified")
        }
    }

    // ── Persistence ────────────────────────────────────────────────────────

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("filePath", filePath)
        element.setAttribute("extraArgs", extraArgs)
        element.setAttribute("isTest",   isTest.toString())
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        filePath  = element.getAttributeValue("filePath")  ?: ""
        extraArgs = element.getAttributeValue("extraArgs") ?: ""
    }
}
