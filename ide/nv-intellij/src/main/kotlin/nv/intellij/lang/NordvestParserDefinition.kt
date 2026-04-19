package nv.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import nv.intellij.lexer.NordvestLexer
import nv.intellij.lexer.NordvestTokenTypes

/**
 * Minimal [ParserDefinition] for Tier 1 (LSP-backed) support.
 *
 * The parser is intentionally a stub that consumes all tokens into a single
 * root node — semantic understanding is delegated to the nv-lsp server via
 * LSP4IJ. The lexer is fully implemented and drives syntax highlighting.
 *
 * Tier 2 (PSI) will replace the stub parser with a real recursive-descent
 * implementation that mirrors the compiler's parser.
 */
class NordvestParserDefinition : ParserDefinition {

    companion object {
        @JvmField
        val FILE = IFileElementType(NordvestLanguage)
    }

    override fun createLexer(project: Project?): Lexer = NordvestLexer()

    /** Stub parser: consumes the entire token stream into one root node. */
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val mark = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        mark.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = NordvestTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = NordvestTokenTypes.STRINGS

    /** Not used by the stub parser; Tier 2 will map element types to real PSI classes. */
    override fun createElement(node: ASTNode): PsiElement = PsiUtilCore.NULL_PSI_ELEMENT

    override fun createFile(viewProvider: FileViewProvider): PsiFile = NordvestFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?) =
        ParserDefinition.SpaceRequirements.MAY
}
