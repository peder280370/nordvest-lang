package nv.intellij.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * Registers the Nordvest settings panel under **Preferences › Tools › Nordvest**.
 *
 * Fields:
 * - **nv binary path** — leave blank to use PATH
 * - **LSP arguments** — passed after the binary when starting nv-lsp
 * - **Format on save** — invoke `nv fmt` automatically on file save
 */
class NordvestSettingsConfigurable : Configurable {

    private var component: NordvestSettingsComponent? = null

    override fun getDisplayName() = "Nordvest"

    override fun createComponent(): JComponent {
        val c = NordvestSettingsComponent()
        component = c
        return c.panel
    }

    override fun isModified(): Boolean {
        val c   = component ?: return false
        val cfg = NordvestSettings.getInstance().state
        return c.nvBinaryPath != cfg.nvBinaryPath
            || c.lspArguments != cfg.lspArguments
            || c.formatOnSave != cfg.formatOnSave
    }

    override fun apply() {
        val c   = component ?: return
        val cfg = NordvestSettings.getInstance()
        cfg.state.nvBinaryPath = c.nvBinaryPath
        cfg.state.lspArguments = c.lspArguments.ifBlank { "lsp" }
        cfg.state.formatOnSave = c.formatOnSave
    }

    override fun reset() {
        val c   = component ?: return
        val cfg = NordvestSettings.getInstance().state
        c.nvBinaryPath = cfg.nvBinaryPath
        c.lspArguments = cfg.lspArguments
        c.formatOnSave = cfg.formatOnSave
    }

    override fun disposeUIResources() {
        component = null
    }
}
