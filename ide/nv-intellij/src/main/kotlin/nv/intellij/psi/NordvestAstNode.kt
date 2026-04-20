package nv.intellij.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode

/** Base class for all Nordvest PSI composite nodes. */
open class NordvestAstNode(node: ASTNode) : ASTWrapperPsiElement(node)
