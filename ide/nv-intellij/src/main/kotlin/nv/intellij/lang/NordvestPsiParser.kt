package nv.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import nv.intellij.lexer.NordvestTokenTypes
import nv.intellij.psi.NordvestElementTypes

/**
 * PSI parser for Nordvest (Tier 2).
 *
 * Builds a structured PSI tree by identifying top-level declarations and wrapping
 * them in typed composite nodes. Declaration bodies are consumed as opaque token
 * sequences — only the declaration header is structurally identified at this stage.
 *
 * Indentation sensitivity is handled by checking the character-column of each token
 * via [isAtColumn0]: since the lexer does not emit INDENT/DEDENT tokens, column
 * position is inferred from the raw file text preceding each token offset.
 *
 * Algorithm per top-level slot:
 *   1. If current token is not at column 0, advance one token (body content).
 *   2. Open a [PsiBuilder.Marker].
 *   3. Consume leading annotations (@name + same-line arguments) and visibility
 *      keywords (pub / pub(pkg)).
 *   4. Inspect the next keyword to determine the declaration type.
 *   5. If no known declaration keyword is found, roll back the marker and advance.
 *   6. Otherwise, consume the header line and all subsequent indented body lines,
 *      then close the marker with the appropriate element type.
 */
class NordvestPsiParser : PsiParser {

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val fileMarker = builder.mark()
        while (!builder.eof()) {
            parseTopLevel(builder)
        }
        fileMarker.done(root)
        return builder.treeBuilt
    }

    // ── Top-level dispatch ─────────────────────────────────────────────────

    private fun parseTopLevel(builder: PsiBuilder) {
        if (!isAtColumn0(builder)) {
            builder.advanceLexer()
            return
        }

        val mark = builder.mark()

        // Consume leading annotations (@name + optional same-line args)
        while (!builder.eof() && builder.tokenType == NordvestTokenTypes.ANNOTATION) {
            val annoLineEnd = findLineEnd(builder.originalText, builder.currentOffset)
            builder.advanceLexer() // @name
            // consume annotation arguments that are on the same line
            while (!builder.eof() && builder.currentOffset <= annoLineEnd) {
                builder.advanceLexer()
            }
        }

        // Consume visibility modifier: pub or pub(pkg)
        if (!builder.eof() && builder.tokenType == NordvestTokenTypes.KEYWORD
            && builder.tokenText == "pub"
        ) {
            builder.advanceLexer() // pub
            if (!builder.eof() && builder.tokenType == NordvestTokenTypes.LPAREN) {
                builder.advanceLexer() // (
                if (!builder.eof()) builder.advanceLexer() // pkg
                if (!builder.eof()) builder.advanceLexer() // )
            }
        }

        val declType: IElementType? = when {
            builder.tokenType != NordvestTokenTypes.KEYWORD -> null
            else -> when (builder.tokenText) {
                "fn"                                    -> NordvestElementTypes.FN_DEF
                "class", "interface", "record",
                "enum", "struct", "sealed"              -> NordvestElementTypes.CLASS_LIKE_DEF
                "module"                                -> NordvestElementTypes.MODULE_DECL
                "import"                                -> NordvestElementTypes.IMPORT_DECL
                "let"                                   -> NordvestElementTypes.LET_DECL
                "var"                                   -> NordvestElementTypes.VAR_DECL
                "extend"                                -> NordvestElementTypes.EXTEND_DECL
                "type"                                  -> NordvestElementTypes.TYPE_ALIAS
                else                                    -> null
            }
        }

        if (declType == null) {
            // No recognizable declaration — rollback and consume one token
            mark.rollbackTo()
            builder.advanceLexer()
            return
        }

        // Consume header line + indented body
        consumeDecl(builder)
        mark.done(declType)
    }

    /**
     * Consumes the declaration header (current line) and all following
     * indented lines that form the declaration body.
     */
    private fun consumeDecl(builder: PsiBuilder) {
        val text = builder.originalText
        // Consume header line: all tokens up to and including the newline
        val headerEnd = findLineEnd(text, builder.currentOffset)
        while (!builder.eof() && builder.currentOffset <= headerEnd) {
            builder.advanceLexer()
        }
        // Consume indented body: lines where the first token is NOT at column 0
        while (!builder.eof() && !isAtColumn0(builder)) {
            builder.advanceLexer()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Returns true if the current token starts at column 0 (i.e., it is either
     * the very first character of the file or is immediately preceded by a `\n`
     * with no intervening spaces/tabs).
     */
    private fun isAtColumn0(builder: PsiBuilder): Boolean {
        val off = builder.currentOffset
        if (off == 0) return true
        val text = builder.originalText
        // Walk backwards to find the last newline (or start of file)
        var i = off - 1
        while (i >= 0 && text[i] != '\n') i--
        // Distance from (newline+1) to current offset is the column number.
        // Column 0 means the token is at exactly (i+1).
        return (off - (i + 1)) == 0
    }

    /** Returns the offset of the `\n` ending the line that contains [fromOffset]. */
    private fun findLineEnd(text: CharSequence, fromOffset: Int): Int {
        val nl = (fromOffset until text.length).firstOrNull { text[it] == '\n' }
        return nl ?: text.length
    }
}
