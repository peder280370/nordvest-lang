package nv.intellij.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.icons.AllIcons
import nv.intellij.lang.NordvestFile
import nv.intellij.psi.elements.NordvestClassLikeDef
import nv.intellij.psi.elements.NordvestFunctionDef

/**
 * Wraps a Nordvest PSI element for display in IntelliJ's Structure View panel.
 *
 * - File root → lists all top-level function and class-like declarations.
 * - Function/class nodes → leaves (class members not parsed in Tier 2).
 */
class NordvestStructureViewElement(private val element: PsiElement) : StructureViewTreeElement {

    override fun getValue(): Any = element

    override fun getPresentation(): ItemPresentation = when (element) {
        is NordvestFile -> object : ItemPresentation {
            override fun getPresentableText(): String? = element.name
            override fun getIcon(unused: Boolean) = element.fileType.icon
            override fun getLocationString(): String? = null
        }
        is NordvestFunctionDef -> element.presentation ?: fallbackPresentation()
        is NordvestClassLikeDef -> element.presentation ?: fallbackPresentation()
        else -> fallbackPresentation()
    }

    override fun getChildren(): Array<TreeElement> {
        if (element !is NordvestFile) return emptyArray()
        return element.children
            .filter { it is NordvestFunctionDef || it is NordvestClassLikeDef }
            .map { NordvestStructureViewElement(it) }
            .toTypedArray()
    }

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigatablePsiElement)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean =
        element is NavigatablePsiElement && (element as NavigatablePsiElement).canNavigate()

    override fun canNavigateToSource(): Boolean = canNavigate()

    private fun fallbackPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = element.text.take(60)
        override fun getIcon(unused: Boolean) = AllIcons.Nodes.Gvariable
        override fun getLocationString(): String? = null
    }
}
