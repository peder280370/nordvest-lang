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
          nv version           print version information
          nv help              show this message

        Phase 2 commands (coming soon):
          nv test              run tests
          nv doc               generate documentation
          nv pkg <cmd>         package management
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
