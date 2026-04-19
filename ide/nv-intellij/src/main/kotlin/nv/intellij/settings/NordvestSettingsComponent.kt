package nv.intellij.settings

import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel

/** Swing component for the Nordvest settings panel (used by [NordvestSettingsConfigurable]). */
class NordvestSettingsComponent {

    val nvBinaryPathField = TextFieldWithBrowseButton()
    val lspArgumentsField = JBTextField()
    val formatOnSaveBox   = JBCheckBox("Format on save (run `nv fmt` automatically)")

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("nv binary path:"),  nvBinaryPathField, true)
        .addTooltip("Leave blank to auto-detect from PATH (recommended)")
        .addLabeledComponent(JBLabel("LSP arguments:"),   lspArgumentsField, true)
        .addTooltip("Arguments passed after the binary, e.g. \"lsp\" (default)")
        .addComponent(formatOnSaveBox)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    var nvBinaryPath: String
        get() = nvBinaryPathField.text.trim()
        set(v) { nvBinaryPathField.text = v }

    var lspArguments: String
        get() = lspArgumentsField.text.trim()
        set(v) { lspArgumentsField.text = v }

    var formatOnSave: Boolean
        get() = formatOnSaveBox.isSelected
        set(v) { formatOnSaveBox.isSelected = v }
}
