package nv.intellij.lexer

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import nv.intellij.lang.NordvestIcons
import javax.swing.Icon

/**
 * Registers Nordvest colour attributes in **Preferences › Editor › Color Scheme › Nordvest**.
 *
 * The demo text below exercises every token category so the user can preview
 * how their chosen theme colours apply to real Nordvest code.
 */
class NordvestColorSettingsPage : ColorSettingsPage {

    private val descriptors = arrayOf(
        AttributesDescriptor("Keyword",              NordvestSyntaxHighlighter.KEYWORD),
        AttributesDescriptor("Annotation",           NordvestSyntaxHighlighter.ANNOTATION),
        AttributesDescriptor("Built-in type",        NordvestSyntaxHighlighter.BUILTIN_TYPE),
        AttributesDescriptor("Identifier",           NordvestSyntaxHighlighter.IDENT),
        AttributesDescriptor("Number",               NordvestSyntaxHighlighter.NUMBER),
        AttributesDescriptor("String",               NordvestSyntaxHighlighter.STRING),
        AttributesDescriptor("Comment",              NordvestSyntaxHighlighter.COMMENT),
        AttributesDescriptor("Math operator (∀ → ≤…)", NordvestSyntaxHighlighter.MATH_OP),
        AttributesDescriptor("Operator",             NordvestSyntaxHighlighter.OPERATOR),
        AttributesDescriptor("Punctuation",          NordvestSyntaxHighlighter.PUNCTUATION),
    )

    override fun getIcon(): Icon = NordvestIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = NordvestSyntaxHighlighter()

    override fun getDemoText(): String = """
        // A tour of Nordvest syntax
        module demo

        import std.math

        @derive(All)
        struct Point(x: float, y: float)

        sealed class Shape
            Circle(radius: float)
            Rect(w: float, h: float)

        fn area(s: Shape) → float
            match s
                Circle(r): → π * r^2
                Rect(w, h): → w * h

        fn allPositive(values: [float]) → bool
            → ∀ x ∈ values: x > 0.0

        fn sumOfSquares(values: [float]) → float
            → ∑ x ∈ values: x^2

        fn parseAndDouble(s: str) → Result<int>
            let n = parseInt(s)?
            → Ok(n * 2)

        fn main()
            let origin = Point(0.0, 0.0)
            let dist   = (origin.x^2 + origin.y^2)^0.5
            println("dist = {dist}")
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "Nordvest"
}
