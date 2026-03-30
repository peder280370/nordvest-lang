package nv.tools

import nv.compiler.Compiler

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

        Phase 1 commands (coming soon):
          nv test              run tests
          nv doc               generate documentation
          nv pkg <cmd>         package management
    """.trimIndent())
}

private fun cmdRun(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("nv run: expected <file.nv>")
        System.exit(1)
    }
    TODO("Phase 1: compile ${args[0]} to temp binary and exec")
}

private fun cmdBuild(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("nv build: expected <file.nv>")
        System.exit(1)
    }
    TODO("Phase 1: compile ${args[0]} to named binary")
}

private fun cmdFmt(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("nv fmt: expected <file.nv>")
        System.exit(1)
    }
    TODO("Phase 3: format ${args[0]}")
}
