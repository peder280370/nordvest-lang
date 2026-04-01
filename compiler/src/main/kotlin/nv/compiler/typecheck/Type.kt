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
    object TUnit    : Type()   // ()

    // ── Compound ───────────────────────────────────────────────────────────
    data class TNullable(val inner: Type) : Type()
    data class TNamed(val qualifiedName: String, val typeArgs: List<Type> = emptyList()) : Type()
    data class TFun(val params: List<Type>, val returnType: Type) : Type()
    data class TVar(val name: String) : Type()        // generic type parameter, e.g. "T"
    data class TArray(val element: Type) : Type()
    data class TMatrix(val element: Type) : Type()
    data class TMap(val key: Type, val value: Type) : Type()
    data class TTuple(val fields: List<TupleField>) : Type()
    data class TResult(val okType: Type, val errType: Type) : Type()
    data class TFuture(val inner: Type) : Type()
    data class TChannel(val element: Type) : Type()

    // ── Sentinels ─────────────────────────────────────────────────────────
    object TUnknown : Type()   // not yet inferred; type checker fills this in
    object TError   : Type()   // unresolvable; suppresses cascading errors
    object TNever   : Type()   // bottom type; type of non-returning expressions
}

data class TupleField(val name: String?, val type: Type)

// ── Display ───────────────────────────────────────────────────────────────

fun Type.display(): String = when (this) {
    Type.TInt     -> "int"
    Type.TInt64   -> "int64"
    Type.TFloat   -> "float"
    Type.TFloat32 -> "float32"
    Type.TFloat64 -> "float64"
    Type.TBool    -> "bool"
    Type.TStr     -> "str"
    Type.TByte    -> "byte"
    Type.TChar    -> "char"
    Type.TUnit    -> "()"
    Type.TUnknown -> "<unknown>"
    Type.TError   -> "<error>"
    Type.TNever   -> "never"
    is Type.TNullable -> "${inner.display()}?"
    is Type.TNamed    -> if (typeArgs.isEmpty()) qualifiedName
                         else "$qualifiedName<${typeArgs.joinToString(", ") { it.display() }}>"
    is Type.TFun      -> "(${params.joinToString(", ") { it.display() }}) → ${returnType.display()}"
    is Type.TVar      -> name
    is Type.TArray    -> "[${element.display()}]"
    is Type.TMatrix   -> "[[${element.display()}]]"
    is Type.TMap      -> "[${key.display()}: ${value.display()}]"
    is Type.TTuple    -> "(${fields.joinToString(", ") { f -> if (f.name != null) "${f.name}: ${f.type.display()}" else f.type.display() }})"
    is Type.TResult   -> "Result<${okType.display()}, ${errType.display()}>"
    is Type.TFuture   -> "Future<${inner.display()}>"
    is Type.TChannel  -> "Channel<${element.display()}>"
}

// ── Predicates ────────────────────────────────────────────────────────────

val Type.isNullable: Boolean get() = this is Type.TNullable
val Type.isError: Boolean    get() = this == Type.TError
val Type.isUnknown: Boolean  get() = this == Type.TUnknown

fun Type.unwrapNullable(): Type = when (this) {
    is Type.TNullable -> inner
    else              -> this
}

/** True for integer-class types. */
val Type.isIntLike: Boolean get() = this == Type.TInt || this == Type.TInt64 || this == Type.TByte

/** True for floating-point-class types. */
val Type.isFloatLike: Boolean get() = this == Type.TFloat || this == Type.TFloat32 || this == Type.TFloat64

/** True for any numeric type. */
val Type.isNumeric: Boolean get() = isIntLike || isFloatLike

/**
 * Numeric promotion: int + float → float; int64 + int → int64; etc.
 * Returns null if the two types cannot be numerically promoted.
 */
fun numericPromotion(a: Type, b: Type): Type? {
    if (!a.isNumeric || !b.isNumeric) return null
    if (a == b) return a
    // float always wins over int
    if (a.isFloatLike) return a
    if (b.isFloatLike) return b
    // both int-like; pick wider
    if (a == Type.TInt64 || b == Type.TInt64) return Type.TInt64
    return Type.TInt
}

/**
 * Check whether [from] is assignable to [to].
 * - Identical types are always compatible.
 * - A non-nullable T is assignable to T?.
 * - TError / TUnknown suppress cascading errors.
 * - TVar("T") is compatible with anything (generic placeholder).
 */
fun isAssignable(from: Type, to: Type): Boolean {
    if (from == Type.TError || to == Type.TError) return true
    if (from == Type.TUnknown || to == Type.TUnknown) return true
    if (from is Type.TVar || to is Type.TVar) return true
    if (from == Type.TNever) return true     // never is assignable to everything
    if (from == to) return true
    // T assignable to T?
    if (to is Type.TNullable && isAssignable(from, to.inner)) return true
    // T? assignable to T? if inner assignable
    if (from is Type.TNullable && to is Type.TNullable) return isAssignable(from.inner, to.inner)
    // Numeric promotion
    if (from.isNumeric && to.isNumeric) return numericPromotion(from, to) == to
    // Recursive structural checks
    if (from is Type.TResult && to is Type.TResult)
        return isAssignable(from.okType, to.okType) && isAssignable(from.errType, to.errType)
    if (from is Type.TArray && to is Type.TArray)
        return isAssignable(from.element, to.element)
    if (from is Type.TMatrix && to is Type.TMatrix)
        return isAssignable(from.element, to.element)
    if (from is Type.TMap && to is Type.TMap)
        return isAssignable(from.key, to.key) && isAssignable(from.value, to.value)
    if (from is Type.TTuple && to is Type.TTuple)
        return from.fields.size == to.fields.size &&
               from.fields.zip(to.fields).all { (f, t) -> isAssignable(f.type, t.type) }
    if (from is Type.TNamed && to is Type.TNamed && from.qualifiedName == to.qualifiedName)
        return from.typeArgs.size == to.typeArgs.size &&
               from.typeArgs.zip(to.typeArgs).all { (f, t) -> isAssignable(f, t) }
    if (from is Type.TFuture && to is Type.TFuture) return isAssignable(from.inner, to.inner)
    if (from is Type.TChannel && to is Type.TChannel) return isAssignable(from.element, to.element)
    return false
}
