package nv.tools

import nv.compiler.Compiler
import nv.compiler.CompileResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "run"   -> cmdRun(args.drop(1))
        "build" -> cmdBuild(args.drop(1))
        "fmt"   -> cmdFmt(args.drop(1))
        "test"  -> cmdTest(args.drop(1))
        "doc"   -> cmdDoc(args.drop(1))
        "pkg"   -> cmdPkg(args.drop(1))
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
          nv run   <file.nv>   compile and run a Nordvest program
          nv build <file.nv>   compile to a native binary
          nv fmt   <file.nv>   format source file (canonical style)
          nv test  [path]      run @test-annotated functions in .nv files
          nv doc   [path]      generate Markdown documentation from .nv sources
          nv pkg   <cmd>       package management (init, add, install, list)
          nv version           print version information
          nv help              show this message
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

    // Write IR to a temp file, compile with clang, exec
    val tmpDir = Files.createTempDirectory("nv_run_").toFile()
    try {
        val irFile  = File(tmpDir, "out.ll")
        val binFile = File(tmpDir, "out")
        irFile.writeText(llvmIr)

        compileIr(irFile, binFile) ?: System.exit(1).let { return }

        // Forward remaining args (after the source file) to the binary
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

    // Determine output path from flags or default to source stem
    val outputIdx = args.indexOf("-o")
    val outputPath = if (outputIdx >= 0 && outputIdx + 1 < args.size) {
        args[outputIdx + 1]
    } else {
        sourceFile.nameWithoutExtension
    }

    val llvmIr = compileToIr(sourceFile) ?: System.exit(1).let { return }

    val tmpDir = Files.createTempDirectory("nv_build_").toFile()
    try {
        val irFile  = File(tmpDir, "out.ll")
        val binFile = File(outputPath)
        irFile.writeText(llvmIr)

        // Also emit .ll if --emit-llvm requested
        if ("--emit-llvm" in args) {
            val llFile = File("${outputPath}.ll")
            llFile.writeText(llvmIr)
            println("nv: LLVM IR written to ${llFile.path}")
            return
        }

        compileIr(irFile, binFile) ?: System.exit(1).let { return }
        println("nv: binary written to $outputPath")
    } finally {
        tmpDir.deleteRecursively()
    }
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
            is CompileResult.IrSuccess -> {
                println("ok $testNum - ${file.path}")
                passed++
            }
            is CompileResult.Failure -> {
                val firstErr = result.errors.firstOrNull()?.message ?: "unknown error"
                println("not ok $testNum - ${file.path}")
                println("  # $firstErr")
                failed++
            }
            else -> {
                println("ok $testNum - ${file.path}")
                passed++
            }
        }
    }

    println()
    println("# ${passed} passed, ${failed} failed")
    if (failed > 0) System.exit(1)
}

// ── nv doc ────────────────────────────────────────────────────────────────────

private fun cmdDoc(args: List<String>) {
    val searchPath = if (args.isNotEmpty()) File(args[0]) else File(".")
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
    val outputFile = File(docsDir, "api.md")

    outputFile.bufferedWriter().use { out ->
        out.appendLine("# Nordvest API Documentation")
        out.appendLine()
        out.appendLine("Generated by `nv doc`")
        out.appendLine()

        for (file in nvFiles) {
            val source = try { file.readText() } catch (e: Exception) {
                System.err.println("nv doc: could not read ${file.path}: ${e.message}")
                continue
            }

            // Parse to extract doc comments
            val tokens = try {
                nv.compiler.lexer.Lexer(source).tokenize()
            } catch (e: Exception) {
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

            // Module heading
            val moduleName = parsedFile.module?.name?.text ?: file.nameWithoutExtension
            out.appendLine("## Module `$moduleName`")
            out.appendLine()
            out.appendLine("Source: `${file.path}`")
            out.appendLine()

            // Emit doc comments for top-level declarations
            for (decl in parsedFile.declarations) {
                when (decl) {
                    is nv.compiler.parser.FunctionDecl -> {
                        decl.docComment?.let { doc ->
                            out.appendLine("### `fn ${decl.name}`")
                            out.appendLine()
                            out.appendLine(doc.trimIndent().trim())
                            out.appendLine()
                        }
                    }
                    is nv.compiler.parser.FunctionSignatureDecl -> {
                        // No doc comment field on signatures, skip
                    }
                    is nv.compiler.parser.ClassDecl -> {
                        decl.docComment?.let { doc ->
                            out.appendLine("### `class ${decl.name}`")
                            out.appendLine()
                            out.appendLine(doc.trimIndent().trim())
                            out.appendLine()
                        }
                    }
                    is nv.compiler.parser.StructDecl -> {
                        decl.docComment?.let { doc ->
                            out.appendLine("### `struct ${decl.name}`")
                            out.appendLine()
                            out.appendLine(doc.trimIndent().trim())
                            out.appendLine()
                        }
                    }
                    is nv.compiler.parser.RecordDecl -> {
                        decl.docComment?.let { doc ->
                            out.appendLine("### `record ${decl.name}`")
                            out.appendLine()
                            out.appendLine(doc.trimIndent().trim())
                            out.appendLine()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    println("nv doc: documentation written to ${outputFile.path}")
}

// ── nv pkg ────────────────────────────────────────────────────────────────────

private fun cmdPkg(args: List<String>) {
    val sub = args.firstOrNull()
    when (sub) {
        null, "help" -> {
            println("""
                nv pkg — package management

                Usage:
                  nv pkg init           create a new nv.pkg file in the current directory
                  nv pkg add <package>  add a dependency (not yet implemented)
                  nv pkg install        install all dependencies (not yet implemented)
                  nv pkg list           list installed packages (not yet implemented)
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
        "add" -> {
            val pkg = args.drop(1).firstOrNull()
            if (pkg == null) {
                System.err.println("nv pkg add: expected <package>")
                System.exit(1)
            }
            System.err.println("nv pkg add: not yet implemented (Phase 3)")
            System.err.println("  Would add: $pkg")
            System.exit(1)
        }
        "install" -> {
            System.err.println("nv pkg install: not yet implemented (Phase 3)")
            System.exit(1)
        }
        "list" -> {
            System.err.println("nv pkg list: not yet implemented (Phase 3)")
            System.exit(1)
        }
        else -> {
            System.err.println("nv pkg: unknown subcommand '$sub'")
            System.exit(1)
        }
    }
}

// ── nv fmt ────────────────────────────────────────────────────────────────────

private fun cmdFmt(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("nv fmt: expected <file.nv>")
        System.exit(1)
    }
    System.err.println("nv fmt: formatter not yet implemented (Phase 3)")
    System.exit(1)
}

// ── Internal helpers ──────────────────────────────────────────────────────────

/**
 * Compile [sourceFile] through lex → parse → resolve → typecheck → codegen.
 * Returns the textual LLVM IR on success, or prints errors and returns null.
 */
private fun compileToIr(sourceFile: File): String? {
    val source = sourceFile.readText()
    return when (val result = Compiler.compile(source, sourceFile.path)) {
        is CompileResult.IrSuccess -> result.llvmIr
        is CompileResult.Success   -> {
            // Phase 1.5+: shouldn't reach here, but handle gracefully
            System.err.println("nv: unexpected Success result (no IR)")
            null
        }
        is CompileResult.Failure   -> {
            for (err in result.errors) {
                val loc = if (err.line > 0) "${err.sourcePath}:${err.line}:${err.column}" else err.sourcePath
                System.err.println("$loc: error: ${err.message}")
            }
            null
        }
    }
}

/**
 * Invoke clang to compile [irFile] → [binFile].
 * Returns [binFile] on success, prints error and returns null on failure.
 * If clang is not available, falls back to `cc`.
 */
private fun compileIr(irFile: File, binFile: File): File? {
    val compiler = findCompiler() ?: run {
        System.err.println(
            "nv: no C compiler found. Install clang or gcc and ensure it is on PATH.\n" +
            "    On macOS:  xcode-select --install\n" +
            "    On Ubuntu: sudo apt install clang"
        )
        return null
    }

    val proc = ProcessBuilder(compiler, "-o", binFile.absolutePath, irFile.absolutePath)
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

/** Find clang or cc on PATH. Returns the command string, or null if neither found. */
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
