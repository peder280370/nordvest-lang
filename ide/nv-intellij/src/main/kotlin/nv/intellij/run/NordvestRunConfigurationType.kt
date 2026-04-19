package nv.intellij.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import nv.intellij.lang.NordvestIcons

/**
 * Top-level "Nordvest" run-configuration category shown in the
 * **Run/Debug Configurations** dialog.
 *
 * Contains two factories:
 * - **nv run** — compile and run a single `.nv` file
 * - **nv test** — run `nv test [filter]` on a file or directory
 */
class NordvestRunConfigurationType : ConfigurationTypeBase(
    "NordvestRun",
    "Nordvest",
    "Run or test a Nordvest program",
    NordvestIcons.FILE,
) {
    init {
        addFactory(NordvestRunConfigurationFactory(this))
        addFactory(NordvestTestConfigurationFactory(this))
    }
}
