package nv.intellij.structure

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.psi.PsiElement
import nv.intellij.lang.NordvestFile
import nv.intellij.psi.elements.NordvestClassLikeDef
import nv.intellij.psi.elements.NordvestFunctionDef

/**
 * Structure view model for Nordvest files.
 *
 * Shows top-level functions and class-like declarations in the Structure panel.
 * Alpha sorter is offered but not forced; the default is declaration order.
 */
class NordvestStructureViewModel(file: NordvestFile) :
    StructureViewModelBase(file, NordvestStructureViewElement(file)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        element.value is NordvestFunctionDef

    override fun getSuitableClasses(): Array<Class<out PsiElement>> = arrayOf(
        NordvestFunctionDef::class.java,
        NordvestClassLikeDef::class.java,
    )
}
