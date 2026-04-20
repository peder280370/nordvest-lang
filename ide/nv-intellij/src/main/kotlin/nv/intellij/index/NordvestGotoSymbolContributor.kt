package nv.intellij.index

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import nv.intellij.lang.NordvestFile
import nv.intellij.psi.elements.NordvestClassLikeDef
import nv.intellij.psi.elements.NordvestFunctionDef

/**
 * Provides all Nordvest top-level symbols (functions **and** class-like declarations)
 * to IntelliJ's **Go to Symbol** popup (⌘⌥O / Ctrl+Alt+Shift+N).
 *
 * Backed by [NordvestSymbolIndex] so lookups are index-time fast without
 * requiring the PSI tree to be built for every file.
 */
class NordvestGotoSymbolContributor : ChooseByNameContributor {

    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> =
        FileBasedIndex.getInstance()
            .getAllKeys(NordvestSymbolIndex.NAME, project)
            .toTypedArray()

    override fun getItemsByName(
        name: String,
        pattern: String,
        project: Project,
        includeNonProjectItems: Boolean,
    ): Array<NavigationItem> {
        val scope = if (includeNonProjectItems)
            GlobalSearchScope.allScope(project)
        else
            GlobalSearchScope.projectScope(project)

        val fbi = FileBasedIndex.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val result = mutableListOf<NavigationItem>()

        fbi.getContainingFiles(NordvestSymbolIndex.NAME, name, scope).forEach { vFile ->
            val psiFile = psiManager.findFile(vFile) as? NordvestFile ?: return@forEach
            psiFile.children.forEach { child ->
                when (child) {
                    is NordvestFunctionDef  -> if (child.name == name) result.add(child)
                    is NordvestClassLikeDef -> if (child.name == name) result.add(child)
                }
            }
        }
        return result.toTypedArray()
    }
}
