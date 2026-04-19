package nv.intellij.lang

import com.intellij.lang.Language

/** Singleton Language descriptor for Nordvest (.nv) files. */
object NordvestLanguage : Language("Nordvest") {
    // Language.findLanguageByID("Nordvest") returns this instance.
    private fun readResolve(): Any = NordvestLanguage
}
