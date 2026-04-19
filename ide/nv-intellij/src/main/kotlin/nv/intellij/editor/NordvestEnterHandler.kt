package nv.intellij.editor

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import nv.intellij.lang.NordvestFileType

/**
 * Indentation-aware Enter handler for Nordvest source files.
 *
 * Nordvest is indentation-sensitive (like Python), so pressing Enter must:
 * 1. **Preserve** the current line's indentation on the new line.
 * 2. **Increase** by one level (4 spaces) when the previous line ends with `:`
 *    (the universal block-opener for `fn`, `if`, `else`, `for`, `while`,
 *     `match`, `class`, `struct`, `record`, `sealed`, `interface`, etc.).
 * 3. **Do nothing special** (fall through to IntelliJ default) when not in a
 *    Nordvest file.
 *
 * Implementation note: [preprocessEnter] manipulates the indent-string that
 * IntelliJ will insert for the new line by writing it into [indentInfoRef].
 * Returning [Result.Default] lets IntelliJ still insert the newline and apply
 * normal language-level smart-indent; we only override the indentation string.
 */
class NordvestEnterHandler : EnterHandlerDelegate {

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): Result {
        if (file.fileType != NordvestFileType) return Result.Continue

        val document  = editor.document
        val offset    = caretOffset.get()
        val lineNum   = document.getLineNumber(offset)
        if (lineNum < 0) return Result.Continue

        val lineStart = document.getLineStartOffset(lineNum)
        val lineEnd   = document.getLineEndOffset(lineNum)
        val lineText  = document.charsSequence.substring(lineStart, lineEnd)

        // Strip trailing whitespace and inline comments to find the real line end.
        val trimmed = stripInlineComment(lineText).trimEnd()

        // Determine base indentation of the current line (leading spaces/tabs).
        val baseIndent = leadingWhitespace(lineText)

        // Decide whether to indent one level deeper.
        val newIndent = if (trimmed.endsWith(":")) {
            baseIndent + "    "
        } else {
            baseIndent
        }

        // Write the desired indentation for the new line back into the document.
        // We do this in postProcessEnter instead so IntelliJ has already split
        // the line — use the adjustment approach via caretAdvance + direct insert.
        //
        // Strategy: store the computed indent in a thread-local so postProcessEnter
        // can apply it. This avoids fighting IntelliJ's own indent calculation.
        pendingIndent.set(newIndent)

        return Result.Continue
    }

    override fun postProcessEnter(
        file: PsiFile,
        editor: Editor,
        dataContext: DataContext,
    ): Result {
        if (file.fileType != NordvestFileType) return Result.Continue

        val indent = pendingIndent.get() ?: return Result.Continue
        pendingIndent.remove()

        val document = editor.document
        val offset   = editor.caretModel.offset

        // Find the start of the new (current) line.
        val lineNum   = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNum)
        val lineEnd   = document.getLineEndOffset(lineNum)

        // Measure how much whitespace IntelliJ already placed on this line.
        val existingText = document.charsSequence.substring(lineStart, lineEnd)
        val existingWS   = leadingWhitespace(existingText)

        if (existingWS == indent) return Result.Continue   // already correct

        // Replace existing leading whitespace with the computed indent.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
            editor.project, "Nordvest Indent", null,
            {
                val wsEnd = lineStart + existingWS.length
                document.replaceString(lineStart, wsEnd, indent)
                editor.caretModel.moveToOffset(lineStart + indent.length)
            },
            file,
        )

        return Result.Continue
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the leading whitespace (spaces/tabs) of [text]. */
    private fun leadingWhitespace(text: String): String =
        text.takeWhile { it == ' ' || it == '\t' }

    /**
     * Strips a trailing `// ...` comment from [line], being careful not to
     * strip `//` that appears inside a string literal.
     */
    private fun stripInlineComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inString          -> inString = true
                c == '"' && inString           -> inString = false
                c == '\\' && inString          -> i++   // skip escaped char
                c == '/' && !inString && i + 1 < line.length && line[i + 1] == '/' ->
                    return line.substring(0, i)
            }
            i++
        }
        return line
    }

    companion object {
        /** Carries the desired new-line indent across [preprocessEnter] → [postProcessEnter]. */
        private val pendingIndent = ThreadLocal<String?>()
    }
}
