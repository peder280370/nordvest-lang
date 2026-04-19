package nv.intellij.editor

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import nv.intellij.lang.NordvestFileType

/**
 * **Live backslash substitution** — the primary mechanism for entering
 * mathematical symbols in Nordvest source files.
 *
 * When the user types a Space or Tab immediately after a `\<name>` sequence
 * whose name is in [MathSymbolTable], the escape sequence is replaced in-place
 * with the Unicode symbol.  The trigger character itself is preserved.
 *
 * Examples:
 *   `\forall` + Space  →  `∀ `
 *   `\->` + Space      →  `→ `
 *   `\leq` + Space     →  `≤ `
 *   `\pi` + Space      →  `π `
 *
 * The handler is a no-op in all non-Nordvest files.
 */
class MathSymbolTypedHandler : TypedHandlerDelegate() {

    override fun charTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
    ): TypedHandlerDelegate.Result {
        if (file.fileType != NordvestFileType) return TypedHandlerDelegate.Result.CONTINUE
        if (c != ' ' && c != '\t') return TypedHandlerDelegate.Result.CONTINUE

        val document  = editor.document
        val caretOff  = editor.caretModel.offset
        val triggerAt = caretOff - 1
        if (triggerAt <= 0) return TypedHandlerDelegate.Result.CONTINUE

        val text   = document.charsSequence
        // Walk back from just before the trigger to find the backslash.
        var i = triggerAt - 1
        while (i >= 0 && isEscapeNameChar(text[i])) i--
        if (i < 0 || text[i] != '\\') return TypedHandlerDelegate.Result.CONTINUE

        val bsAt     = i
        val nameText = text.substring(bsAt + 1, triggerAt)
        if (nameText.isEmpty()) return TypedHandlerDelegate.Result.CONTINUE

        val entry = MathSymbolTable.lookup(nameText) ?: return TypedHandlerDelegate.Result.CONTINUE

        // Replace \<name><trigger> with <symbol><trigger>
        WriteCommandAction.runWriteCommandAction(project, "Insert Math Symbol", null, {
            val replacement = entry.symbol + c
            document.replaceString(bsAt, caretOff, replacement)
            editor.caretModel.moveToOffset(bsAt + replacement.length)
        }, file)

        return TypedHandlerDelegate.Result.STOP
    }

    private fun isEscapeNameChar(c: Char): Boolean =
        c.isLetter() || c.isDigit() || c == '-' || c == '>' || c == '<' || c == '=' || c == '_'
}
