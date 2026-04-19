package nv.intellij.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * **"Insert Math Symbol"** action (default shortcut: **Alt+\**).
 *
 * Opens a searchable dialog showing all symbols from [MathSymbolTable].
 * Type to filter by name or description; click (or double-click) a symbol
 * button to insert it at the caret.  Also accessible from EditorPopupMenu and
 * the Find Action palette (search "Insert Math Symbol").
 */
class MathSymbolInsertAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file   = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = editor != null && file != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor  = e.getData(CommonDataKeys.EDITOR) ?: return
        val file    = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val dialog = SymbolPickerDialog(
            project    = project,
            parentComp = editor.component,
            onInsert   = { symbol ->
                WriteCommandAction.runWriteCommandAction(project, "Insert Math Symbol", null, {
                    val doc  = editor.document
                    val caret = editor.caretModel.offset
                    doc.insertString(caret, symbol)
                    editor.caretModel.moveToOffset(caret + symbol.length)
                }, file)
            },
        )
        dialog.show()
    }
}

// ── Internal dialog ────────────────────────────────────────────────────────

private class SymbolPickerDialog(
    project: com.intellij.openapi.project.Project,
    parentComp: Component,
    private val onInsert: (String) -> Unit,
) : DialogWrapper(project, parentComp, false, IdeModalityType.MODELESS) {

    private val searchField = JBTextField()
    private val gridPanel   = JPanel()
    private var allEntries  = MathSymbolTable.ALL

    init {
        title = "Nordvest — Insert Math Symbol"
        isModal = false   // modeless: user can keep typing in the editor after inserting
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(8, 8))
        root.border = BorderFactory.createEmptyBorder(8, 8, 4, 8)
        root.preferredSize = Dimension(540, 420)

        // ── Search bar ───────────────────────────────────────────────────
        val searchPanel = JPanel(BorderLayout(4, 0))
        searchPanel.add(JLabel("Search:"), BorderLayout.WEST)
        searchPanel.add(searchField, BorderLayout.CENTER)
        root.add(searchPanel, BorderLayout.NORTH)

        // ── Symbol grid ──────────────────────────────────────────────────
        gridPanel.layout = GridLayout(0, 6, 4, 4)
        rebuildGrid(MathSymbolTable.ALL)
        val scroll = JBScrollPane(gridPanel)
        scroll.border = BorderFactory.createEmptyBorder()
        root.add(scroll, BorderLayout.CENTER)

        // ── Live filter on typing ────────────────────────────────────────
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = filter()
            override fun removeUpdate(e: DocumentEvent?) = filter()
            override fun changedUpdate(e: DocumentEvent?) = filter()
        })

        return root
    }

    private fun filter() {
        val q = searchField.text.trim()
        rebuildGrid(if (q.isEmpty()) MathSymbolTable.ALL else MathSymbolTable.matching(q))
    }

    private fun rebuildGrid(entries: List<MathSymbolTable.Entry>) {
        gridPanel.removeAll()
        for (entry in entries) {
            gridPanel.add(symbolButton(entry))
        }
        gridPanel.revalidate()
        gridPanel.repaint()
    }

    private fun symbolButton(entry: MathSymbolTable.Entry): JComponent {
        val btn = JButton(entry.symbol)
        btn.font            = Font("Serif", Font.PLAIN, 20)
        btn.toolTipText     = "${entry.description}  \\${entry.name}  ${entry.codepoint}"
        btn.horizontalAlignment = SwingConstants.CENTER
        btn.preferredSize   = Dimension(72, 52)
        btn.addActionListener { onInsert(entry.symbol) }
        // Highlight on hover
        btn.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                btn.background = JBColor.namedColor("Button.hoverBackground", JBColor.LIGHT_GRAY)
            }
            override fun mouseExited(e: MouseEvent?) {
                btn.background = null
            }
        })
        return btn
    }

    override fun createActions() = arrayOf(okAction)

    override fun getOKAction() = object : DialogWrapper.OkAction() {
        init { putValue(NAME, "Close") }
    }
}
