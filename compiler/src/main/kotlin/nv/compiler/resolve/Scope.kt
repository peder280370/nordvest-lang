package nv.compiler.resolve

enum class ScopeKind {
    BUILTIN,    // root scope: built-in types and functions
    MODULE,     // top-level declarations of a source file
    CLASS,      // class / struct / record / interface / sealed body
    FUNCTION,   // function parameters + body
    BLOCK,      // if / match arm / defer / lambda body / etc.
    LOOP,       // for or while loop body (enables break/continue label resolution)
}

class Scope(
    val kind: ScopeKind,
    val parent: Scope? = null,
    val label: String? = null,          // set for LOOP scopes with @label
) {
    private val symbols: MutableMap<String, Symbol> = LinkedHashMap()

    /**
     * Declare [symbol] in this scope.
     * Returns a [ResolveError.DuplicateDefinition] if the name is already defined in
     * this exact scope, or null on success. Shadowing an outer scope is allowed.
     */
    fun define(symbol: Symbol): ResolveError.DuplicateDefinition? {
        val existing = symbols[symbol.name]
        return if (existing != null) {
            ResolveError.DuplicateDefinition(symbol.name, existing.span, symbol.span)
        } else {
            symbols[symbol.name] = symbol
            null
        }
    }

    /** Look up [name] starting in this scope and walking up the parent chain. */
    fun lookup(name: String): Symbol? = symbols[name] ?: parent?.lookup(name)

    /** Look up [name] only in this scope (no parent traversal). */
    fun lookupLocal(name: String): Symbol? = symbols[name]

    /** All symbols declared directly in this scope (not parents). */
    val localSymbols: Collection<Symbol> get() = symbols.values

    /** Nearest enclosing LOOP scope, optionally matching a specific label. */
    fun nearestLoop(label: String? = null): Scope? =
        if (kind == ScopeKind.LOOP && (label == null || this.label == label)) this
        else parent?.nearestLoop(label)
}
