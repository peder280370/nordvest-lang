package nv.intellij.editor

import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.psi.PsiComment
import com.intellij.psi.tree.IElementType
import nv.intellij.lexer.NordvestTokenTypes

/**
 * Ctrl+/ (macOS: Cmd+/) toggles `//` line comments.
 * Nordvest has no block-comment syntax — `//` is always a line comment.
 */
class NordvestCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix()                   = "//"
    override fun getBlockCommentPrefix()                  = null
    override fun getBlockCommentSuffix()                  = null
    override fun getCommentedBlockCommentPrefix()         = null
    override fun getCommentedBlockCommentSuffix()         = null
    override fun getLineCommentTokenType(): IElementType  = NordvestTokenTypes.COMMENT
    override fun getBlockCommentTokenType(): IElementType? = null
    override fun getDocumentationCommentTokenType(): IElementType? = null
    override fun getDocumentationCommentPrefix()         = null
    override fun getDocumentationCommentLinePrefix()     = null
    override fun getDocumentationCommentSuffix()         = null
    override fun isDocumentationComment(element: PsiComment?) = false
}
