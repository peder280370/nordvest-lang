package nv.compiler.typecheck

import nv.compiler.resolve.ResolvedModule

/**
 * The output of the type checker for a single source file.
 *
 * @property resolvedModule  The name-resolved module (AST + symbol table).
 * @property typeMap         Maps each expression's span-start-offset to its inferred [Type].
 * @property memberTypeMap   Maps "TypeName.memberName" to the member's [Type].
 * @property errors          All type errors found during this pass.
 */
data class TypeCheckedModule(
    val resolvedModule: ResolvedModule,
    val typeMap: Map<Int, Type>,
    val memberTypeMap: Map<String, Type>,
    val errors: List<TypeCheckError>,
)

sealed class TypeCheckResult {
    /** Zero type errors. */
    data class Success(val module: TypeCheckedModule) : TypeCheckResult()

    /**
     * Type errors present but the module is still usable by subsequent phases
     * (e.g. unknown member access resolved to TUnknown).
     */
    data class Recovered(val module: TypeCheckedModule) : TypeCheckResult()

    /** Fatal type errors that prevent code generation from proceeding. */
    data class Failure(val errors: List<TypeCheckError>) : TypeCheckResult()
}
