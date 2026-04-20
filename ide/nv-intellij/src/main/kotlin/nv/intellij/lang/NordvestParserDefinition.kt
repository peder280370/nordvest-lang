package nv.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import nv.intellij.lexer.NordvestLexer
import nv.intellij.lexer.NordvestTokenTypes
import nv.intellij.psi.NordvestAstNode
import nv.intellij.psi.NordvestElementTypes
import nv.intellij.psi.elements.NordvestClassLikeDef
import nv.intellij.psi.elements.NordvestFunctionDef

/**
 * [ParserDefinition] for Nordvest source files (Tier 2 — PSI-backed).
 *
 * The lexer is fully implemented and drives syntax highlighting.
 * The parser ([NordvestPsiParser]) produces a structured PSI tree with named
 * composite nodes for top-level declarations; declaration bodies are kept as
 * opaque token leaves for this phase.
 *
 * [createElement] maps composite element types to their typed PSI classes so
 * that the structure view, symbol index, and inspections can access declaration
 * names and kinds via strongly-typed methods.
 */
class NordvestParserDefinition : ParserDefinition {

    companion object {
        @JvmField
        val FILE = IFileElementType(NordvestLanguage)
    }

    override fun createLexer(project: Project?): Lexer = NordvestLexer()

    override fun createParser(project: Project?): PsiParser = NordvestPsiParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getWhitespaceTokens(): TokenSet = NordvestTokenTypes.WHITESPACE_SET

    override fun getCommentTokens(): TokenSet = NordvestTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = NordvestTokenTypes.STRINGS

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        NordvestElementTypes.FN_DEF         -> NordvestFunctionDef(node)
        NordvestElementTypes.CLASS_LIKE_DEF -> NordvestClassLikeDef(node)
        else                                -> NordvestAstNode(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = NordvestFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?) =
        ParserDefinition.SpaceRequirements.MAY
}
