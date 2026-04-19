package nv.intellij.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider

/**
 * PSI file node for Nordvest source files.
 *
 * Tier 1 uses LSP4IJ for semantic features, so the PSI tree is kept minimal —
 * the parser produces a single root node containing leaf tokens from
 * [nv.intellij.lexer.NordvestLexer]. Richer PSI is planned for Tier 2.
 */
class NordvestFile(viewProvider: FileViewProvider) :
    PsiFileBase(viewProvider, NordvestLanguage) {

    override fun getFileType() = NordvestFileType
    override fun toString()    = "Nordvest File"
}
