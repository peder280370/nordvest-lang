package nv.intellij.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/** Binds the ".nv" extension to [NordvestLanguage] and provides the file icon. */
object NordvestFileType : LanguageFileType(NordvestLanguage) {
    override fun getName()             = "Nordvest"
    override fun getDescription()      = "Nordvest source file"
    override fun getDefaultExtension() = "nv"
    override fun getIcon(): Icon       = NordvestIcons.FILE
}
