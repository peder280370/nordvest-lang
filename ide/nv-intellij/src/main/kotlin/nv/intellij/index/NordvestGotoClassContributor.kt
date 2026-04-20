package nv.intellij.index

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import nv.intellij.lang.NordvestFile
import nv.intellij.psi.elements.NordvestClassLikeDef

/**
 * Provides Nordvest class-like declarations (class, struct, record, interface,
 * sealed class, enum) to IntelliJ's **Go to Class** popup (⌘O / Ctrl+N).
 *
 * Only indexes symbols whose kind is one of the class-like variants;
 * functions are excluded from the class popup but appear in Go to Symbol.
 */
class NordvestGotoClassContributor : GotoClassContributor {

    private val CLASS_KINDS = setOf("class", "struct", "record", "interface", "enum", "sealed")

    override fun getQualifiedName(item: NavigationItem): String? =
        (item as? NordvestClassLikeDef)?.name

    override fun getQualifiedNameSeparator(): String = "."

    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> {
        val fbi = FileBasedIndex.getInstance()
        val scope = if (includeNonProjectItems)
            GlobalSearchScope.allScope(project)
        else
            GlobalSearchScope.projectScope(project)

        return fbi.getAllKeys(NordvestSymbolIndex.NAME, project)
            .filter { name ->
                fbi.getValues(NordvestSymbolIndex.NAME, name, scope)
                    .any { kind -> kind in CLASS_KINDS }
            }
            .toTypedArray()
    }

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
                if (child is NordvestClassLikeDef && child.name == name) result.add(child)
            }
        }
        return result.toTypedArray()
    }
}
