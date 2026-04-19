package nv.intellij.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings editor UI for [NordvestRunConfiguration]. */
class NordvestRunConfigurationEditor(private val project: Project) :
    SettingsEditor<NordvestRunConfiguration>() {

    private val fileField = TextFieldWithBrowseButton().also { field ->
        field.addBrowseFolderListener(
            "Select Nordvest File",
            "Choose the .nv source file to run",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("nv"),
        )
    }

    private val argsField = JBTextField()

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("File (.nv):"), fileField, true)
        .addLabeledComponent(JBLabel("Extra args:"), argsField, true)
        .addTooltip("e.g. \"-o mybin\" for nv run, or a test filter for nv test")
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun createEditor(): JComponent = panel

    override fun resetEditorFrom(cfg: NordvestRunConfiguration) {
        fileField.text = cfg.filePath
        argsField.text = cfg.extraArgs
    }

    override fun applyEditorTo(cfg: NordvestRunConfiguration) {
        cfg.filePath  = fileField.text.trim()
        cfg.extraArgs = argsField.text.trim()
    }
}
