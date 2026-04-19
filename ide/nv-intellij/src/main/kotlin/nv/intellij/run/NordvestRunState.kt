package nv.intellij.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import nv.intellij.settings.NordvestSettings

/**
 * Executes `nv run <file>` or `nv test [file]` in the IntelliJ Run tool window.
 */
class NordvestRunState(
    private val env: ExecutionEnvironment,
    private val config: NordvestRunConfiguration,
) : CommandLineState(env) {

    override fun startProcess(): ProcessHandler {
        val settings = NordvestSettings.getInstance().state
        val nvPath   = settings.nvBinaryPath.ifBlank { "nv" }

        val params = buildList {
            if (config.isTest) {
                add("test")
                if (config.filePath.isNotBlank()) add(config.filePath)
            } else {
                add("run")
                add(config.filePath)
            }
            config.extraArgs
                .split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .forEach { add(it) }
        }

        val cmdLine = GeneralCommandLine(listOf(nvPath) + params)
            .withWorkDirectory(env.project.basePath)

        return OSProcessHandler(cmdLine).also {
            it.setShouldDestroyProcessRecursively(true)
        }
    }
}
