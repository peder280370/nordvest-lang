package nv.tools

import nv.compiler.Compiler
import nv.compiler.CompileResult
import nv.compiler.format.Formatter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "run"     -> cmdRun(args.drop(1))
        "build"   -> cmdBuild(args.drop(1))
        "fmt"     -> cmdFmt(args.drop(1))
        "test"    -> cmdTest(args.drop(1))
        "doc"     -> cmdDoc(args.drop(1))
        "pkg"     -> cmdPkg(args.drop(1))
        "lsp"     -> cmdLsp(args.drop(1))
        "clean"   -> cmdClean(args.drop(1))
        "version", "--version", "-v" -> println("nv ${Compiler.VERSION}")
        "help", "--help", "-h" -> printUsage()
        else -> {
            System.err.println("nv: unknown command '${args[0]}'")
            printUsage()
            System.exit(1)
        }
    }
}

private fun printUsage() {
    println("""
        nv — the Nordvest language toolchain  (${Compiler.VERSION})

        Usage:
          nv run   <file.nv>              compile and run a Nordvest program
          nv build <file.nv> [--release]  compile to a native binary
          nv fmt   <file.nv> [--check] [--stdout] [--ascii]  format source
          nv test  [path]                 run .nv files, emit TAP output
          nv doc   [path] [--html]        generate documentation
          nv pkg   <cmd>                  package management (init, add, install, list, new)
          nv lsp                          start Language Server Protocol server (stdio)
          nv clean                        remove the .nv-cache directory
          nv version                      print version information
          nv help                         show this message
    """.trimIndent())
}

// ── nv run ────────────────────────────────────────────────────────────────────

private fun cmdRun(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("nv run: expected <file.nv>")
        System.exit(1)
    }
    val sourceFile = File(args[0])
    if (!sourceFile.exists()) {
        System.err.println("nv run: file not found: ${args[0]}")
        System.exit(1)
    }

    val llvmIr = compileToIr(sourceFile) ?: System.exit(1).let { return }

    val tmpDir = Files.createTempDirectory("nv_run_").toFile()
    try {
        val irFile  = File(tmpDir, "out.ll")
        val binFile = File(tmpDir, "out")
        irFile.writeText(llvmIr)

        compileIr(irFile, binFile, optimize = false) ?: System.exit(1).let { return }

        val runArgs = listOf(binFile.absolutePath) + args.drop(1)
        val proc = ProcessBuilder(runArgs)
            .inheritIO()
            .start()
        val exitCode = proc.waitFor()
        System.exit(exitCode)
    } finally {
        tmpDir.deleteRecursively()
    }
}

// ── nv build ──────────────────────────────────────────────────────────────────

private fun cmdBuild(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("nv build: expected <file.nv>")
        System.exit(1)
    }
    val sourceFile = File(args[0])
    if (!sourceFile.exists()) {
        System.err.println("nv build: file not found: ${args[0]}")
        System.exit(1)
    }

    val outputIdx = args.indexOf("-o")
    val outputPath = if (outputIdx >= 0 && outputIdx + 1 < args.size) {
        args[outputIdx + 1]
    } else {
        sourceFile.nameWithoutExtension
    }
    val release = "--release" in args
    if (release) println("nv: building in release mode (-O2)")

    val llvmIr = compileToIr(sourceFile) ?: System.exit(1).let { return }

    val tmpDir = Files.createTempDirectory("nv_build_").toFile()
    try {
        val irFile  = File(tmpDir, "out.ll")
        val binFile = File(outputPath)
        irFile.writeText(llvmIr)

        if ("--emit-llvm" in args) {
            val llFile = File("${outputPath}.ll")
            llFile.writeText(llvmIr)
            println("nv: LLVM IR written to ${llFile.path}")
            return
        }

        compileIr(irFile, binFile, optimize = release) ?: System.exit(1).let { return }
        println("nv: binary written to $outputPath")
    } finally {
        tmpDir.deleteRecursively()
    }
}

// ── nv fmt ────────────────────────────────────────────────────────────────────

private fun cmdFmt(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("nv fmt: expected <file.nv>")
        System.exit(1)
    }

    val ascii  = "--ascii"  in args
    val check  = "--check"  in args
    val stdout = "--stdout" in args
    val files  = args.filter { !it.startsWith("--") }

    var anyChanged = false

    for (path in files) {
        val file = File(path)
        if (!file.exists()) {
            System.err.println("nv fmt: file not found: $path")
            System.exit(1)
        }
        val source = file.readText()
        val tokens = try {
            nv.compiler.lexer.Lexer(source).tokenize()
        } catch (e: Exception) {
            System.err.println("nv fmt: lex error in $path: ${e.message}")
            System.exit(1)
            return
        }
        val parseResult = nv.compiler.parser.Parser(tokens, path).parse()
        val parsedFile = when (parseResult) {
            is nv.compiler.parser.ParseResult.Success   -> parseResult.file
            is nv.compiler.parser.ParseResult.Recovered -> parseResult.file
            is nv.compiler.parser.ParseResult.Failure   -> {
                System.err.println("nv fmt: parse error in $path: ${parseResult.errors.firstOrNull()?.message}")
                System.exit(1)
                return
            }
        }

        val formatted = Formatter(asciiMode = ascii).format(parsedFile)

        when {
            stdout -> print(formatted)
            check  -> {
                if (source != formatted) {
                    System.err.println("nv fmt: $path would be reformatted")
                    anyChanged = true
                }
            }
            else -> {
                if (source != formatted) {
                    file.writeText(formatted)
                    println("nv fmt: formatted $path")
                } else {
                    println("nv fmt: $path already canonical")
                }
            }
        }
    }

    if (check && anyChanged) System.exit(1)
}

// ── nv test ───────────────────────────────────────────────────────────────────

private fun cmdTest(args: List<String>) {
    val searchPath = if (args.isNotEmpty()) File(args[0]) else File(".")
    if (!searchPath.exists()) {
        System.err.println("nv test: path not found: ${searchPath.path}")
        System.exit(1)
    }

    val nvFiles = searchPath.walkTopDown()
        .filter { it.isFile && it.extension == "nv" }
        .toList()
        .sortedBy { it.path }

    if (nvFiles.isEmpty()) {
        println("nv test: no .nv files found in ${searchPath.path}")
        return
    }

    var passed = 0
    var failed = 0
    println("TAP version 13")
    println("1..${nvFiles.size}")

    nvFiles.forEachIndexed { index, file ->
        val testNum = index + 1
        val source = try { file.readText() } catch (e: Exception) {
            println("not ok $testNum - ${file.path} # read error: ${e.message}")
            failed++
            return@forEachIndexed
        }
        when (val result = Compiler.compile(source, file.path)) {
            is CompileResult.IrSuccess -> { println("ok $testNum - ${file.path}"); passed++ }
            is CompileResult.Failure -> {
                val firstErr = result.errors.firstOrNull()?.message ?: "unknown error"
                println("not ok $testNum - ${file.path}")
                println("  # $firstErr")
                failed++
            }
            else -> { println("ok $testNum - ${file.path}"); passed++ }
        }
    }

    println()
    println("# $passed passed, $failed failed")
    if (failed > 0) System.exit(1)
}

// ── nv doc ────────────────────────────────────────────────────────────────────

private fun cmdDoc(args: List<String>) {
    val html = "--html" in args
    val paths = args.filter { !it.startsWith("--") }
    val searchPath = if (paths.isNotEmpty()) File(paths[0]) else File(".")
    if (!searchPath.exists()) {
        System.err.println("nv doc: path not found: ${searchPath.path}")
        System.exit(1)
    }

    val nvFiles = searchPath.walkTopDown()
        .filter { it.isFile && it.extension == "nv" }
        .toList()
        .sortedBy { it.path }

    if (nvFiles.isEmpty()) {
        println("nv doc: no .nv files found in ${searchPath.path}")
        return
    }

    val docsDir = File("docs")
    docsDir.mkdirs()

    val modules = mutableListOf<NvModuleDoc>()

    for (file in nvFiles) {
        val source = try { file.readText() } catch (e: Exception) {
            System.err.println("nv doc: could not read ${file.path}: ${e.message}")
            continue
        }
        val tokens = try { nv.compiler.lexer.Lexer(source).tokenize() } catch (e: Exception) {
            System.err.println("nv doc: lex error in ${file.path}: ${e.message}")
            continue
        }
        val parseResult = nv.compiler.parser.Parser(tokens, file.path).parse()
        val parsedFile = when (parseResult) {
            is nv.compiler.parser.ParseResult.Success   -> parseResult.file
            is nv.compiler.parser.ParseResult.Recovered -> parseResult.file
            is nv.compiler.parser.ParseResult.Failure   -> {
                System.err.println("nv doc: parse error in ${file.path}: ${parseResult.errors.firstOrNull()?.message}")
                continue
            }
        }

        val moduleName = parsedFile.module?.name?.text ?: file.nameWithoutExtension
        val items = mutableListOf<Pair<String, String>>()

        for (decl in parsedFile.declarations) {
            when (decl) {
                is nv.compiler.parser.FunctionDecl -> decl.docComment?.let {
                    items += Pair("fn ${decl.name}", it.trimIndent().trim())
                }
                is nv.compiler.parser.ClassDecl -> decl.docComment?.let {
                    items += Pair("class ${decl.name}", it.trimIndent().trim())
                }
                is nv.compiler.parser.StructDecl -> decl.docComment?.let {
                    items += Pair("struct ${decl.name}", it.trimIndent().trim())
                }
                is nv.compiler.parser.RecordDecl -> decl.docComment?.let {
                    items += Pair("record ${decl.name}", it.trimIndent().trim())
                }
                is nv.compiler.parser.InterfaceDecl -> decl.docComment?.let {
                    items += Pair("interface ${decl.name}", it.trimIndent().trim())
                }
                else -> {}
            }
        }
        modules += NvModuleDoc(moduleName, file.path, items)
    }

    if (html) {
        generateHtmlDocs(docsDir, modules)
    } else {
        generateMarkdownDocs(docsDir, modules)
    }
}

private data class NvModuleDoc(val name: String, val sourcePath: String, val items: List<Pair<String, String>>)

private fun generateMarkdownDocs(docsDir: File, modules: List<NvModuleDoc>) {
    val outputFile = File(docsDir, "api.md")
    outputFile.bufferedWriter().use { out ->
        out.appendLine("# Nordvest API Documentation")
        out.appendLine()
        out.appendLine("Generated by `nv doc`")
        out.appendLine()
        for (mod in modules) {
            out.appendLine("## Module `${mod.name}`")
            out.appendLine()
            out.appendLine("Source: `${mod.sourcePath}`")
            out.appendLine()
            for ((sig, doc) in mod.items) {
                out.appendLine("### `$sig`")
                out.appendLine()
                out.appendLine(doc)
                out.appendLine()
            }
        }
    }
    println("nv doc: documentation written to ${outputFile.path}")
}

private fun generateHtmlDocs(docsDir: File, modules: List<NvModuleDoc>) {
    // Write per-module HTML pages
    for (mod in modules) {
        val safeId = mod.name.replace('.', '-')
        val moduleFile = File(docsDir, "$safeId.html")
        moduleFile.writeText(buildString {
            append("""<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><title>${htmlEscape(mod.name)} — Nordvest API</title>
<link rel="stylesheet" href="style.css"></head>
<body>
<nav><a href="index.html">← Index</a></nav>
<h1><code>${htmlEscape(mod.name)}</code></h1>
<p class="source">Source: <code>${htmlEscape(mod.sourcePath)}</code></p>
""")
            for ((sig, doc) in mod.items) {
                append("<section class=\"item\">\n")
                append("<h3><code>${htmlEscape(sig)}</code></h3>\n")
                append("<p>${htmlEscape(doc)}</p>\n")
                append("</section>\n")
            }
            append("</body></html>\n")
        })
    }

    // Write index.html
    val indexFile = File(docsDir, "index.html")
    indexFile.writeText(buildString {
        append("""<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><title>Nordvest API Documentation</title>
<link rel="stylesheet" href="style.css"></head>
<body>
<h1>Nordvest API Documentation</h1>
<p>Generated by <code>nv doc --html</code></p>
<ul class="module-list">
""")
        for (mod in modules) {
            val safeId = mod.name.replace('.', '-')
            append("<li><a href=\"$safeId.html\"><code>${htmlEscape(mod.name)}</code></a></li>\n")
        }
        append("</ul>\n</body></html>\n")
    })

    // Write style.css
    val cssFile = File(docsDir, "style.css")
    cssFile.writeText("""
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
               max-width: 860px; margin: 0 auto; padding: 2rem; color: #222; }
        h1, h2, h3 { font-weight: 600; }
        h1 { font-size: 1.8rem; border-bottom: 2px solid #e0e0e0; padding-bottom: .4rem; }
        h3 { font-size: 1rem; margin-bottom: .25rem; }
        code { font-family: "JetBrains Mono", Menlo, monospace; font-size: .9em;
               background: #f5f5f5; padding: .1em .3em; border-radius: 3px; }
        nav { margin-bottom: 1.5rem; }
        nav a { color: #0066cc; text-decoration: none; }
        nav a:hover { text-decoration: underline; }
        p.source { color: #666; font-size: .85rem; }
        section.item { border-left: 3px solid #d0d0d0; padding-left: 1rem; margin: 1.5rem 0; }
        ul.module-list { list-style: none; padding: 0; }
        ul.module-list li { padding: .3rem 0; }
        ul.module-list a { color: #0066cc; text-decoration: none; font-family: monospace; }
        ul.module-list a:hover { text-decoration: underline; }
    """.trimIndent())

    println("nv doc: HTML documentation written to ${docsDir.path}/index.html (${modules.size} modules)")
}

private fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

// ── nv pkg ────────────────────────────────────────────────────────────────────

private fun cmdPkg(args: List<String>) {
    val sub = args.firstOrNull()
    when (sub) {
        null, "help" -> {
            println("""
                nv pkg — package management

                Usage:
                  nv pkg init              create nv.pkg in current directory
                  nv pkg new <name>        scaffold a new project directory
                  nv pkg add <pkg[@ver]>   add a dependency to nv.pkg
                  nv pkg install           install all declared dependencies
                  nv pkg list              list declared and installed packages
            """.trimIndent())
        }
        "init" -> {
            val pkgFile = File("nv.pkg")
            if (pkgFile.exists()) {
                System.err.println("nv pkg init: nv.pkg already exists")
                System.exit(1)
            }
            val dirName = File(".").canonicalFile.name
            pkgFile.writeText("""
                name = "$dirName"
                version = "0.1.0"
                description = ""

                [dependencies]
            """.trimIndent() + "\n")
            println("nv pkg init: created nv.pkg")
        }
        "new" -> {
            val name = args.drop(1).firstOrNull()
            if (name == null) {
                System.err.println("nv pkg new: expected <name>")
                System.exit(1); return
            }
            val dir = File(name)
            if (dir.exists()) {
                System.err.println("nv pkg new: directory '$name' already exists")
                System.exit(1); return
            }
            dir.mkdirs()
            File(dir, "nv.pkg").writeText("""
                name = "$name"
                version = "0.1.0"
                description = ""

                [dependencies]
            """.trimIndent() + "\n")
            File(dir, "main.nv").writeText("""
                fn main()
                    println("Hello from $name!")
            """.trimIndent() + "\n")
            File(dir, "tests").mkdirs()
            println("nv pkg new: created project '$name/'")
        }
        "add" -> {
            val spec = args.drop(1).firstOrNull()
            if (spec == null) {
                System.err.println("nv pkg add: expected <package[@version]>")
                System.exit(1); return
            }
            val (pkgName, pkgVersion) = if ("@" in spec) {
                spec.substringBefore("@") to spec.substringAfter("@")
            } else {
                spec to "latest"
            }
            val pkgFile = File("nv.pkg")
            if (!pkgFile.exists()) {
                System.err.println("nv pkg add: no nv.pkg found; run 'nv pkg init' first")
                System.exit(1); return
            }
            val lines = pkgFile.readLines().toMutableList()
            val depIdx = lines.indexOfFirst { it.trim() == "[dependencies]" }
            if (depIdx < 0) {
                lines += "\n[dependencies]"
                lines += "$pkgName = \"$pkgVersion\""
            } else {
                // Check if already present
                val existingIdx = lines.drop(depIdx + 1).indexOfFirst { it.startsWith("$pkgName ") || it.startsWith("$pkgName=") }
                if (existingIdx >= 0) {
                    lines[depIdx + 1 + existingIdx] = "$pkgName = \"$pkgVersion\""
                    println("nv pkg add: updated $pkgName to $pkgVersion")
                } else {
                    lines.add(depIdx + 1, "$pkgName = \"$pkgVersion\"")
                    println("nv pkg add: added $pkgName@$pkgVersion")
                }
            }
            pkgFile.writeText(lines.joinToString("\n") + "\n")
        }
        "install" -> {
            val pkgFile = File("nv.pkg")
            if (!pkgFile.exists()) {
                System.err.println("nv pkg install: no nv.pkg found; run 'nv pkg init' first")
                System.exit(1); return
            }
            val deps = parsePkgDependencies(pkgFile)
            if (deps.isEmpty()) {
                println("nv pkg install: no dependencies declared")
                return
            }
            val pkgDir = File(".nv-packages")
            pkgDir.mkdirs()
            var installed = 0
            for ((name, version) in deps) {
                val resolvedVersion = if (version == "latest") "0.1.0" else version
                val depDir = File(pkgDir, "$name-$resolvedVersion")
                if (!depDir.exists()) {
                    depDir.mkdirs()
                    File(depDir, "mod.nv").writeText("""
                        module $name

                        // Package $name@$resolvedVersion — stub installed by nv pkg install
                    """.trimIndent() + "\n")
                    println("nv pkg install: installed $name@$resolvedVersion → .nv-packages/$name-$resolvedVersion/")
                    installed++
                } else {
                    println("nv pkg install: $name@$resolvedVersion already installed")
                }
            }
            println("nv pkg install: $installed package(s) installed")
        }
        "list" -> {
            val pkgFile = File("nv.pkg")
            if (!pkgFile.exists()) {
                println("nv pkg list: no nv.pkg found")
                return
            }
            val deps = parsePkgDependencies(pkgFile)
            val pkgDir = File(".nv-packages")
            if (deps.isEmpty()) {
                println("No dependencies declared in nv.pkg")
                return
            }
            println("Declared dependencies:")
            for ((name, version) in deps) {
                val resolvedVersion = if (version == "latest") "0.1.0" else version
                val installed = File(pkgDir, "$name-$resolvedVersion").exists()
                val status = if (installed) "✓ installed" else "✗ not installed (run nv pkg install)"
                println("  $name@$version  $status")
            }
        }
        else -> {
            System.err.println("nv pkg: unknown subcommand '$sub'")
            System.exit(1)
        }
    }
}

/** Parse `[dependencies]` section from a nv.pkg TOML-lite file. */
private fun parsePkgDependencies(pkgFile: File): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var inDeps = false
    for (line in pkgFile.readLines()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("[") -> inDeps = trimmed == "[dependencies]"
            inDeps && trimmed.contains("=") && !trimmed.startsWith("#") -> {
                val key   = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim().removeSurrounding("\"")
                if (key.isNotBlank()) result[key] = value
            }
        }
    }
    return result
}

// ── nv lsp ────────────────────────────────────────────────────────────────────

private fun cmdLsp(@Suppress("UNUSED_PARAMETER") args: List<String>) {
    val code = LspServer().run()
    if (code != 0) System.exit(code)
}

// ── nv clean ──────────────────────────────────────────────────────────────────

private fun cmdClean(@Suppress("UNUSED_PARAMETER") args: List<String>) {
    val cacheDir = File(".nv-cache")
    if (cacheDir.exists()) {
        cacheDir.deleteRecursively()
        println("nv clean: removed .nv-cache/")
    } else {
        println("nv clean: nothing to clean")
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

/**
 * Compile [sourceFile] through lex → parse → resolve → typecheck → codegen.
 * Returns textual LLVM IR on success, or prints errors (with source context) and returns null.
 * Uses an incremental file-based cache keyed on the SHA-256 of the source.
 */
private fun compileToIr(sourceFile: File): String? {
    val source = sourceFile.readText()
    val cacheKey = sha256Hex(source)
    val cacheFile = File(".nv-cache", "$cacheKey.ll")
    if (cacheFile.exists()) {
        return cacheFile.readText()
    }

    return when (val result = Compiler.compile(source, sourceFile.path)) {
        is CompileResult.IrSuccess -> {
            // Cache the result
            cacheFile.parentFile.mkdirs()
            cacheFile.writeText(result.llvmIr)
            result.llvmIr
        }
        is CompileResult.Success -> {
            System.err.println("nv: unexpected Success result (no IR)")
            null
        }
        is CompileResult.Failure -> {
            val sourceLines = source.lines()
            for (err in result.errors) {
                val loc = if (err.line > 0) "${err.sourcePath}:${err.line}:${err.column}" else err.sourcePath
                System.err.println("$loc: error: ${err.message}")
                if (err.line > 0 && err.line <= sourceLines.size) {
                    val srcLine = sourceLines[err.line - 1]
                    System.err.println("    $srcLine")
                    val col = (err.column - 1).coerceAtLeast(0)
                    System.err.println("    ${" ".repeat(col)}^")
                }
            }
            null
        }
    }
}

/**
 * Invoke clang to compile [irFile] → [binFile].
 * Passes -O2 when [optimize] is true.
 * Returns [binFile] on success, or null on failure.
 */
private fun compileIr(irFile: File, binFile: File, optimize: Boolean = false): File? {
    val compiler = findCompiler() ?: run {
        System.err.println(
            "nv: no C compiler found. Install clang or gcc and ensure it is on PATH.\n" +
            "    On macOS:  xcode-select --install\n" +
            "    On Ubuntu: sudo apt install clang"
        )
        return null
    }

    val cmd = buildList {
        add(compiler)
        if (optimize) add("-O2")
        add("-o"); add(binFile.absolutePath)
        add(irFile.absolutePath)
    }

    val proc = ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    val ok = proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0
    if (!ok) {
        System.err.println("nv: compilation failed:\n$output")
        return null
    }
    return binFile
}

/** Find clang or cc on PATH. */
private fun findCompiler(): String? {
    for (cmd in listOf("clang", "cc", "gcc")) {
        try {
            val probe = ProcessBuilder(cmd, "--version")
                .redirectErrorStream(true)
                .start()
            if (probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0) return cmd
        } catch (_: Exception) { /* not found */ }
    }
    return null
}

/** Compute SHA-256 hex digest of [input]. */
private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes  = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
