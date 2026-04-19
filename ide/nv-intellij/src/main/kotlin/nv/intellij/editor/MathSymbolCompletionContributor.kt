package nv.intellij.editor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import nv.intellij.lang.NordvestFile

/**
 * **Math symbol completion** — Ctrl+Space after a `\` prefix shows matching
 * math symbol suggestions from [MathSymbolTable].
 *
 * Typing `\fo` + Ctrl+Space → suggests `∀ — \forall (For all)`.
 * Selecting an entry replaces `\<prefix>` with the Unicode symbol.
 *
 * This works alongside [MathSymbolTypedHandler]: the typed handler fires on
 * Space/Tab, while the completion contributor serves users who prefer popup
 * selection or want to browse available symbols.
 */
class MathSymbolCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(NordvestFile::class.java)),
            MathSymbolProvider(),
        )
    }

    private class MathSymbolProvider : CompletionProvider<CompletionParameters>() {

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val document  = parameters.editor.document
            val caretOff  = parameters.offset
            val text      = document.charsSequence

            // Walk back to find a leading backslash on the same line.
            var i = caretOff - 1
            while (i >= 0 && isEscapeNameChar(text[i])) i--
            if (i < 0 || text[i] != '\\') return

            val bsAt    = i
            val prefix  = text.substring(bsAt + 1, caretOff)
            val matches = MathSymbolTable.matching(prefix)
            if (matches.isEmpty()) return

            // Tell IntelliJ the prefix starts at the backslash so replacement
            // covers the entire \<name> range.
            val adjusted = result.withPrefixMatcher(prefix)

            for (entry in matches) {
                adjusted.addElement(buildLookupElement(entry, bsAt, caretOff))
            }
        }

        private fun buildLookupElement(
            entry: MathSymbolTable.Entry,
            bsAt: Int,
            caretOff: Int,
        ): LookupElement {
            return LookupElementBuilder
                .create(entry.symbol)
                .withLookupString(entry.name)
                .withLookupString(entry.symbol)
                .withPresentableText(entry.symbol)
                .withTailText("  \\${entry.name}  ${entry.description}", true)
                .withTypeText(entry.codepoint)
                .withBoldness(true)
                .withInsertHandler { ctx, _ ->
                    // Replace everything from '\' to the caret with the symbol.
                    val doc      = ctx.document
                    val finalEnd = ctx.tailOffset
                    // ctx.startOffset is where the matched prefix begins (after '\')
                    // but we want to also erase the '\'.
                    val actualStart = bsAt
                    doc.replaceString(actualStart, finalEnd, entry.symbol)
                    ctx.editor.caretModel.moveToOffset(actualStart + entry.symbol.length)
                }
        }

        private fun isEscapeNameChar(c: Char): Boolean =
            c.isLetter() || c.isDigit() || c == '-' || c == '>' || c == '<' || c == '=' || c == '_'
    }
}
