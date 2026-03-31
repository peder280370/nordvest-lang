package nv.compiler.resolve

import nv.compiler.parser.SourceFile

/** Tracks an import declaration and whether it was resolved to a known module. */
data class ResolvedImport(
    val path: String,
    val alias: String?,
    val isResolved: Boolean,   // false in single-file mode (Phase 1.3)
)

/**
 * The output of name resolution for a single source file.
 *
 * @property file         The original AST (unchanged — resolution is non-destructive).
 * @property moduleScope  The populated MODULE-level scope for this file.
 * @property resolvedRefs Map from AST node identity (SourceSpan.start.offset of the reference
 *                        node) to the Symbol it resolved to. Consumed by the type checker.
 * @property imports      Import records with resolution status.
 * @property errors       All resolve errors found during this pass.
 */
data class ResolvedModule(
    val file: SourceFile,
    val moduleScope: Scope,
    val resolvedRefs: Map<Int, Symbol>,
    val imports: List<ResolvedImport>,
    val errors: List<ResolveError>,
)

sealed class ResolveResult {
    /** Zero errors; all references resolved. */
    data class Success(val module: ResolvedModule) : ResolveResult()

    /**
     * Errors present (e.g. unresolved imports in single-file mode) but the module
     * is still usable by subsequent phases.
     */
    data class Recovered(val module: ResolvedModule) : ResolveResult()

    /** Fatal errors; subsequent phases cannot proceed. */
    data class Failure(val errors: List<ResolveError>) : ResolveResult()
}
