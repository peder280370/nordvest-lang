package nv.intellij.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.PathMacroManager
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import nv.intellij.settings.NordvestSettings
import java.io.File

/**
 * Wires the existing `nv lsp` server into IntelliJ via LSP4IJ 0.19.x.
 *
 * Registered in `plugin.xml` under `com.redhat.devtools.lsp4ij.server` and
 * mapped to `.nv` files via `fileTypeMapping`.
 *
 * LSP4IJ negotiates capabilities automatically via the LSP `initialize`
 * handshake — only what `nv lsp` advertises in its `ServerCapabilities`
 * response will be active.  Currently that covers: diagnostics, completion,
 * hover, go-to-definition, references, formatting, and rename.
 * Inlay hints and semantic tokens are not advertised by nv-lsp yet and so
 * remain inactive without any explicit disabling here.
 */
class NordvestLspServerDefinition : LanguageServerFactory {

    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        val settings = NordvestSettings.getInstance()
        val nvPath   = resolveNvBinary(settings.state.nvBinaryPath, project)
        val args     = settings.state.lspArguments
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("lsp") }

        val cmdLine = GeneralCommandLine(listOf(nvPath) + args).apply {
            project.basePath?.let { setWorkDirectory(it) }
        }

        return object : OSProcessStreamConnectionProvider(cmdLine) {}
    }

    /**
     * Keep the server running even when no `.nv` documents are open.
     *
     * By default LSP4IJ stops the server after `lastDocumentDisconnectedTimeout`
     * seconds (5 s) with no connected files.  Returning `true` here disables
     * that idle-stop mechanism so the server stays warm between file switches.
     */
    override fun createClientFeatures(): LSPClientFeatures =
        object : LSPClientFeatures() {
            override fun keepServerAlive(): Boolean = true
        }

    /**
     * Resolves the `nv` binary path.
     *
     * Priority:
     * 1. Explicit path from settings (if non-blank and the file exists).
     * 2. Common install locations searched in order (handles macOS GUI apps
     *    that don't inherit the shell PATH).
     * 3. Falls back to `"nv"` and lets the OS resolve it — shows a
     *    notification so the user knows to configure the path in Settings.
     */
    private fun resolveNvBinary(configured: String, project: Project): String {
        if (configured.isNotBlank()) {
            // Expand IntelliJ path macros (e.g. $USER_HOME$ stored by the settings
            // persistence framework) before testing whether the file exists.
            val expanded = PathMacroManager
                .getInstance(ApplicationManager.getApplication())
                .expandPath(configured)
            val f = File(expanded)
            if (f.isFile && f.canExecute()) return expanded
        }

        val home = System.getProperty("user.home")
        val searchPaths = listOf(
            "/opt/homebrew/bin/nv",   // Apple Silicon Homebrew
            "/usr/local/bin/nv",      // Intel Homebrew / manual install
            "/usr/bin/nv",
            "$home/.local/bin/nv",
            "$home/bin/nv",
        )

        val found = searchPaths.firstOrNull { File(it).let { f -> f.isFile && f.canExecute() } }
        if (found != null) return found

        // Not found anywhere — warn the user and fall back so the error is
        // thrown by the process launcher with a descriptive message.
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Nordvest")
            .createNotification(
                "Nordvest: `nv` binary not found",
                "The LSP server could not start because `nv` is not on the PATH " +
                "and was not found in common locations. " +
                "Set the binary path in Settings → Nordvest.",
                NotificationType.WARNING,
            )
            .notify(project)

        return "nv"
    }
}
