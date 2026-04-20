package nv.intellij.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import nv.intellij.lang.NordvestFile

/**
 * Registers the Nordvest structure view for `.nv` files.
 *
 * Displays top-level functions and class-like declarations in the
 * IDE's Structure panel (View → Tool Windows → Structure).
 */
class NordvestStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val nvFile = psiFile as? NordvestFile ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                NordvestStructureViewModel(nvFile)
        }
    }
}
