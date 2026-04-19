package nv.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent settings for the Nordvest plugin.
 *
 * Stored in `<config>/options/NordvestSettings.xml`.
 * Accessible via [getInstance].
 */
@State(
    name = "NordvestSettings",
    storages = [Storage("NordvestSettings.xml")],
)
class NordvestSettings : PersistentStateComponent<NordvestSettings.State> {

    data class State(
        /** Path to the `nv` binary. Empty = auto-detect from PATH. */
        var nvBinaryPath: String = "",
        /** Arguments passed to `nv` when starting the LSP server. Default: `lsp`. */
        var lspArguments: String = "lsp",
        /** Invoke `nv fmt` on save. Off by default — enable in Settings. */
        var formatOnSave: Boolean = false,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): NordvestSettings =
            ApplicationManager.getApplication().getService(NordvestSettings::class.java)
    }
}
