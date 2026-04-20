plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "nv.intellij"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2.6.1")
        // LSP client integration — bundles diagnostics, completion, hover,
        // go-to-definition, references, formatting, and rename via nv-lsp.
        plugin("com.redhat.devtools.lsp4ij:0.19.3")
    }
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        name = "Nordvest Language"
        version = project.version.toString()
        description = """
            IntelliJ plugin for the Nordvest programming language.

            Features:
            - Syntax highlighting (keywords, math operators, strings, annotations)
            - Mathematical symbol input: type \forall&lt;Space&gt; → ∀ in-place
            - Symbol completion: Ctrl+Space after \ shows matching math symbols
            - Alt+\ opens a searchable symbol picker for all math operators
            - LSP integration via nv lsp: diagnostics, completion, hover,
              go-to-definition, references, formatting, rename
            - Run configurations: nv run / nv test with gutter ▶ icons
            - Bracket matching for (), [], {}, [[]]
            - // line commenter (Ctrl+/ / Cmd+/)
        """.trimIndent()
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}
