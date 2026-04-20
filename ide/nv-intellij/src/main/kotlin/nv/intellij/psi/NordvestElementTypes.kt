package nv.intellij.psi

import com.intellij.psi.tree.IElementType
import nv.intellij.lang.NordvestLanguage

/**
 * Composite element types for the Nordvest PSI tree (Tier 2).
 *
 * These are used by [nv.intellij.lang.NordvestPsiParser] to mark sections
 * of the token stream as named declaration nodes, and by
 * [nv.intellij.lang.NordvestParserDefinition.createElement] to wrap them
 * in typed PSI element classes.
 */
object NordvestElementTypes {
    @JvmField val FN_DEF         = IElementType("FN_DEF",         NordvestLanguage)
    @JvmField val CLASS_LIKE_DEF = IElementType("CLASS_LIKE_DEF", NordvestLanguage)
    @JvmField val IMPORT_DECL    = IElementType("IMPORT_DECL",    NordvestLanguage)
    @JvmField val MODULE_DECL    = IElementType("MODULE_DECL",    NordvestLanguage)
    @JvmField val LET_DECL       = IElementType("LET_DECL",       NordvestLanguage)
    @JvmField val VAR_DECL       = IElementType("VAR_DECL",       NordvestLanguage)
    @JvmField val EXTEND_DECL    = IElementType("EXTEND_DECL",    NordvestLanguage)
    @JvmField val TYPE_ALIAS     = IElementType("TYPE_ALIAS",     NordvestLanguage)
}
