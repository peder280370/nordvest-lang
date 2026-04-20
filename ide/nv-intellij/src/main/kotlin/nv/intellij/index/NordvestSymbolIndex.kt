package nv.intellij.index

import com.intellij.openapi.fileTypes.FileType
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import nv.intellij.lang.NordvestFileType

/**
 * File-based index for top-level Nordvest symbol names.
 *
 * Maps each top-level declaration name to its kind string:
 * `"fn"`, `"class"`, `"struct"`, `"record"`, `"interface"`, `"enum"`,
 * `"sealed"`, `"extend"`, `"let"`, `"var"`, or `"type"`.
 *
 * Used by [NordvestGotoSymbolContributor] and [NordvestGotoClassContributor]
 * to implement Go to Symbol (⌘⌥O) and Go to Class (⌘O) across the project.
 */
class NordvestSymbolIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME
    override fun getVersion(): Int = 2
    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(NordvestFileType as FileType)

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { input ->
        val result = HashMap<String, String>()
        extractSymbols(input.contentAsText).forEach { (name, kind) ->
            result[name] = kind
        }
        result
    }

    companion object {
        @JvmField
        val NAME: ID<String, String> = ID.create("nv.intellij.symbols")
        /**
         * Scans raw file text for top-level declarations and returns (name, kind) pairs.
         *
         * Top-level declarations are lines that start at column 0 (no leading whitespace)
         * and begin with a known declaration keyword (possibly after `pub` / `@annotation`).
         */
        internal fun extractSymbols(text: CharSequence): List<Pair<String, String>> {
            val results = mutableListOf<Pair<String, String>>()
            var pos = 0
            while (pos < text.length) {
                val lineStart = pos
                val lineEnd = run {
                    var i = pos
                    while (i < text.length && text[i] != '\n') i++
                    i
                }
                val line = text.substring(lineStart, lineEnd)

                // Only top-level lines (no leading whitespace, non-empty, non-comment)
                if (line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("//")) {
                    extractDeclFromLine(line)?.let { results.add(it) }
                }

                pos = lineEnd + 1  // skip the '\n'
            }
            return results
        }

        internal fun extractDeclFromLine(line: String): Pair<String, String>? {
            // Strip leading annotations (@name or @name(...))
            var rest = line.trim()
            while (rest.startsWith("@")) {
                val spaceIdx = rest.indexOfFirst { it == ' ' || it == '\t' || it == '\n' }
                rest = if (spaceIdx < 0) "" else rest.substring(spaceIdx).trim()
            }

            // Strip visibility: pub or pub(pkg)
            if (rest.startsWith("pub(pkg)")) rest = rest.removePrefix("pub(pkg)").trim()
            else if (rest.startsWith("pub ")) rest = rest.removePrefix("pub").trim()

            // Strip "sealed" prefix for sealed classes
            val sealedPrefix = rest.startsWith("sealed ")
            if (sealedPrefix) rest = rest.removePrefix("sealed").trim()

            val DECL_KEYWORDS = listOf("fn", "class", "struct", "record", "interface", "enum",
                "extend", "let", "var", "type")

            for (kw in DECL_KEYWORDS) {
                if (rest.startsWith("$kw ") || rest.startsWith("$kw(") || rest == kw) {
                    val afterKw = rest.removePrefix(kw).trimStart()
                    val name = afterKw.takeWhile { it.isLetterOrDigit() || it == '_' }
                    if (name.isEmpty()) continue
                    val kind = if (sealedPrefix && kw == "class") "sealed" else kw
                    return Pair(name, kind)
                }
            }
            return null
        }
    }
}
