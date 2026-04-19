package nv.intellij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project

/** Factory that creates [NordvestRunConfiguration] instances for `nv run`. */
class NordvestRunConfigurationFactory(type: ConfigurationType) :
    ConfigurationFactory(type) {

    override fun getId()   = "NordvestRun"
    override fun getName() = "nv run"

    override fun createTemplateConfiguration(project: Project) =
        NordvestRunConfiguration(project, this, "nv run")
}

/** Factory that creates [NordvestRunConfiguration] instances for `nv test`. */
class NordvestTestConfigurationFactory(type: ConfigurationType) :
    ConfigurationFactory(type) {

    override fun getId()   = "NordvestTest"
    override fun getName() = "nv test"

    override fun createTemplateConfiguration(project: Project) =
        NordvestRunConfiguration(project, this, "nv test", isTest = true)
}
