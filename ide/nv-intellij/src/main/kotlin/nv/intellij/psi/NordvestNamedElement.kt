package nv.intellij.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner

/**
 * Marker interface for named Nordvest PSI declarations (functions, classes, etc.).
 *
 * Extends [PsiNameIdentifierOwner] (which itself extends [com.intellij.psi.PsiNamedElement])
 * so that implementations provide both [getName] and [getNameIdentifier].
 */
interface NordvestNamedElement : PsiNameIdentifierOwner {
    val declaredName: String?
}
