package nv.compiler.typecheck

/**
 * Semantic type tree used by the resolver and type checker.
 *
 * Phase 1.3: resolver populates Symbol.resolvedType with TUnknown.
 * Phase 1.4: type checker replaces TUnknown with concrete types.
 */
sealed class Type {
    // ── Primitives ─────────────────────────────────────────────────────────
    object TInt     : Type()
    object TInt64   : Type()
    object TFloat   : Type()
    object TFloat32 : Type()
    object TFloat64 : Type()
    object TBool    : Type()
    object TStr     : Type()
    object TByte    : Type()
    object TChar    : Type()
    object TUnit    : Type()

    // ── Compound ───────────────────────────────────────────────────────────
    data class TNullable(val inner: Type) : Type()
    data class TNamed(val qualifiedName: String, val typeArgs: List<Type> = emptyList()) : Type()
    data class TFun(val params: List<Type>, val returnType: Type) : Type()
    data class TVar(val name: String) : Type()   // generic type parameter, e.g. "T"

    // ── Sentinels ─────────────────────────────────────────────────────────
    object TUnknown : Type()   // not yet inferred; type checker fills this in
    object TError   : Type()   // unresolvable; suppresses cascading errors
}
