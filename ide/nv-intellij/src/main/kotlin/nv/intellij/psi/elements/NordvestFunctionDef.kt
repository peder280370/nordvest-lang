package nv.intellij.psi.elements

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import nv.intellij.lexer.NordvestTokenTypes
import nv.intellij.psi.NordvestAstNode
import nv.intellij.psi.NordvestNamedElement

/**
 * PSI element for a Nordvest function declaration (`fn name(...)`).
 *
 * Covers both free functions and operator overloads. The name identifier
 * is the first IDENT token after the `fn` keyword; for operator functions
 * like `fn [](...)` there is no IDENT, and [getName] returns null.
 */
class NordvestFunctionDef(node: ASTNode) : NordvestAstNode(node), NordvestNamedElement, NavigationItem {

    override val declaredName: String? get() = name

    override fun getNameIdentifier(): PsiElement? {
        var child = firstChild
        var seenFn = false
        while (child != null) {
            val t = child.node.elementType
            if (!seenFn && t == NordvestTokenTypes.KEYWORD && child.text == "fn") {
                seenFn = true
            } else if (seenFn && t == NordvestTokenTypes.IDENT) {
                return child
            }
            child = child.nextSibling
        }
        return null
    }

    override fun getName(): String? = getNameIdentifier()?.text

    override fun setName(name: String): PsiElement = this

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = getName() ?: "<anonymous>"
        override fun getIcon(unused: Boolean) = AllIcons.Nodes.Method
        override fun getLocationString(): String? = containingFile?.name
    }
}
