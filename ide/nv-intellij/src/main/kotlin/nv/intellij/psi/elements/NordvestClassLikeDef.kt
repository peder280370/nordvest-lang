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
 * PSI element for a Nordvest class-like declaration.
 *
 * Covers `class`, `struct`, `record`, `interface`, `sealed class`, and `enum`
 * declarations. The [kind] property returns a human-readable string for the
 * specific form, used by the structure view to select the right icon.
 */
class NordvestClassLikeDef(node: ASTNode) : NordvestAstNode(node), NordvestNamedElement, NavigationItem {

    /**
     * Returns the declaration kind: "class", "struct", "record", "interface",
     * "sealed class", or "enum".
     */
    val kind: String
        get() {
            val CLASS_KWS = setOf("class", "struct", "record", "interface", "enum", "sealed")
            var child = firstChild
            val keywords = mutableListOf<String>()
            while (child != null) {
                val t = child.node.elementType
                if (t == NordvestTokenTypes.KEYWORD && child.text in CLASS_KWS) {
                    keywords.add(child.text)
                } else if (t == NordvestTokenTypes.IDENT) {
                    break
                }
                child = child.nextSibling
            }
            return when {
                "sealed"    in keywords -> "sealed class"
                "struct"    in keywords -> "struct"
                "record"    in keywords -> "record"
                "interface" in keywords -> "interface"
                "enum"      in keywords -> "enum"
                else                    -> "class"
            }
        }

    override val declaredName: String? get() = getName()

    override fun getNameIdentifier(): PsiElement? {
        val CLASS_KWS = setOf("class", "struct", "record", "interface", "enum")
        var child = firstChild
        var seenKw = false
        while (child != null) {
            val t = child.node.elementType
            if (!seenKw && t == NordvestTokenTypes.KEYWORD && child.text in CLASS_KWS) {
                seenKw = true
            } else if (seenKw && t == NordvestTokenTypes.IDENT) {
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
        override fun getIcon(unused: Boolean) = when (kind) {
            "interface"    -> AllIcons.Nodes.Interface
            "enum"         -> AllIcons.Nodes.Enum
            "struct"       -> AllIcons.Nodes.AbstractClass
            else           -> AllIcons.Nodes.Class
        }
        override fun getLocationString(): String? = containingFile?.name
    }
}
