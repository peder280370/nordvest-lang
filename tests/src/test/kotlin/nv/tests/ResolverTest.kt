package nv.tests

import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ResolverTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun resolveModule(src: String): ResolvedModule {
        val tokens = Lexer(src).tokenize()
        val file = when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> fail("Parse failed: ${r.errors.first().message}")
        }
        return when (val r = Resolver("<test>").resolve(file)) {
            is ResolveResult.Success   -> r.module
            is ResolveResult.Recovered -> r.module
            is ResolveResult.Failure   -> fail("Resolver hard failure: ${r.errors.first().message}")
        }
    }

    private fun resolveResult(src: String): ResolveResult {
        val tokens = Lexer(src).tokenize()
        val file = when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> return ResolveResult.Failure(emptyList())
        }
        return Resolver("<test>").resolve(file)
    }

    private fun resolveErrors(src: String): List<ResolveError> {
        val tokens = Lexer(src).tokenize()
        val file = when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> return emptyList()
        }
        return when (val r = Resolver("<test>").resolve(file)) {
            is ResolveResult.Success   -> emptyList()
            is ResolveResult.Recovered -> r.module.errors
            is ResolveResult.Failure   -> r.errors
        }
    }

    private fun noUndefinedSymbols(src: String) {
        val errors = resolveErrors(src)
        val undef = errors.filterIsInstance<ResolveError.UndefinedSymbol>()
        assertTrue(undef.isEmpty(), "Expected no UndefinedSymbol errors but got: ${undef.map { it.name }}")
    }

    // ── Built-in scope ─────────────────────────────────────────────────────

    @Nested
    inner class BuiltinScope {

        @Test
        fun `int type is resolvable`() {
            noUndefinedSymbols("fn f(x: int) → int\n    → x\n")
        }

        @Test
        fun `bool type is resolvable`() {
            noUndefinedSymbols("fn f(x: bool) → bool\n    → x\n")
        }

        @Test
        fun `str type is resolvable`() {
            noUndefinedSymbols("fn f(x: str) → str\n    → x\n")
        }

        @Test
        fun `float type is resolvable`() {
            noUndefinedSymbols("fn f(x: float) → float\n    → x\n")
        }

        @Test
        fun `print function is resolvable`() {
            noUndefinedSymbols("fn main()\n    print(\"hello\")\n")
        }

        @Test
        fun `println function is resolvable`() {
            noUndefinedSymbols("fn main()\n    println(\"hello\")\n")
        }

        @Test
        fun `nil is resolvable`() {
            noUndefinedSymbols("fn f() → str?\n    → nil\n")
        }

        @Test
        fun `true and false are resolvable`() {
            noUndefinedSymbols("fn f() → bool\n    → true\n")
            noUndefinedSymbols("fn f() → bool\n    → false\n")
        }

        @Test
        fun `print resolves to BuiltinSym in resolvedRefs`() {
            val m = resolveModule("fn main()\n    print(\"hi\")\n")
            assertTrue(m.resolvedRefs.values.any { it is Symbol.BuiltinSym && it.name == "print" })
        }
    }

    // ── Forward references ────────────────────────────────────────────────

    @Nested
    inner class ForwardReferences {

        @Test
        fun `function can call a forward-declared function`() {
            noUndefinedSymbols("""
                fn a() → int
                    → b()
                fn b() → int
                    → 42
            """.trimIndent() + "\n")
        }

        @Test
        fun `class used before its declaration resolves`() {
            noUndefinedSymbols("""
                fn make() → Point
                    → Point(1.0, 2.0)
                class Point(x: float, y: float)
            """.trimIndent() + "\n")
        }

        @Test
        fun `module-level let is reachable from function defined before it`() {
            noUndefinedSymbols("""
                fn f() → int
                    → x
                let x = 42
            """.trimIndent() + "\n")
        }

        @Test
        fun `sealed class used before declaration resolves`() {
            noUndefinedSymbols("""
                fn describe(s: Shape) → str
                    → "shape"
                sealed class Shape
                    Circle(radius: float)
                    Rect(w: float, h: float)
            """.trimIndent() + "\n")
        }
    }

    // ── Nested scopes ─────────────────────────────────────────────────────

    @Nested
    inner class NestedScopes {

        @Test
        fun `inner block can access outer variable`() {
            noUndefinedSymbols("""
                fn f() → int
                    let x = 1
                    if true
                        → x
                    → 0
            """.trimIndent() + "\n")
        }

        @Test
        fun `inner let shadows outer let without error`() {
            val errors = resolveErrors("""
                fn f() → int
                    let x = 1
                    if true
                        let x = 2
                        → x
                    → x
            """.trimIndent() + "\n")
            assertTrue(errors.none { it is ResolveError.UndefinedSymbol })
            assertTrue(errors.none { it is ResolveError.DuplicateDefinition })
        }

        @Test
        fun `for-loop variable is scoped to loop body`() {
            noUndefinedSymbols("""
                fn f()
                    for i in [1, 2, 3]
                        let _ = i
            """.trimIndent() + "\n")
        }

        @Test
        fun `for-loop variable not visible after loop`() {
            val errors = resolveErrors("""
                fn f() → int
                    for i in [1, 2, 3]
                        let _ = i
                    → i
            """.trimIndent() + "\n")
            assertTrue(errors.any { it is ResolveError.UndefinedSymbol && (it as ResolveError.UndefinedSymbol).name == "i" })
        }

        @Test
        fun `lambda parameters are scoped to lambda body`() {
            noUndefinedSymbols("""
                fn f()
                    let add = (x: int, y: int) → x + y
            """.trimIndent() + "\n")
        }

        @Test
        fun `match arm binding is scoped to arm`() {
            noUndefinedSymbols("""
                fn f(v: int) → str
                    match v
                        x: → "got"
            """.trimIndent() + "\n")
        }

        @Test
        fun `guard let binding is visible after the guard`() {
            noUndefinedSymbols("""
                fn f(v: int?) → int
                    guard let x = v else
                        → 0
                    → x
            """.trimIndent() + "\n")
        }

        @Test
        fun `function parameters are accessible in the body`() {
            noUndefinedSymbols("""
                fn add(a: int, b: int) → int
                    → a + b
            """.trimIndent() + "\n")
        }
    }

    // ── Duplicate definitions ─────────────────────────────────────────────

    @Nested
    inner class DuplicateDefinitions {

        @Test
        fun `duplicate top-level function names produce error`() {
            val errors = resolveErrors("fn foo() → int\n    → 1\nfn foo() → int\n    → 2\n")
            assertTrue(errors.any { it is ResolveError.DuplicateDefinition && (it as ResolveError.DuplicateDefinition).name == "foo" })
        }

        @Test
        fun `duplicate local variable names in same scope produce error`() {
            val errors = resolveErrors("fn f()\n    let x = 1\n    let x = 2\n")
            assertTrue(errors.any { it is ResolveError.DuplicateDefinition && (it as ResolveError.DuplicateDefinition).name == "x" })
        }

        @Test
        fun `duplicate top-level type names produce error`() {
            val errors = resolveErrors("type Foo = int\ntype Foo = str\n")
            assertTrue(errors.any { it is ResolveError.DuplicateDefinition && (it as ResolveError.DuplicateDefinition).name == "Foo" })
        }

        @Test
        fun `duplicate parameter names in same function produce error`() {
            val errors = resolveErrors("fn f(x: int, x: int) → int\n    → x\n")
            assertTrue(errors.any { it is ResolveError.DuplicateDefinition && (it as ResolveError.DuplicateDefinition).name == "x" })
        }
    }

    // ── Undefined symbols ─────────────────────────────────────────────────

    @Nested
    inner class UndefinedSymbols {

        @Test
        fun `calling undefined function is an error`() {
            val errors = resolveErrors("fn f()\n    ghost()\n")
            assertTrue(errors.any { it is ResolveError.UndefinedSymbol && (it as ResolveError.UndefinedSymbol).name == "ghost" })
        }

        @Test
        fun `using undefined type annotation is an error`() {
            val errors = resolveErrors("fn f(x: Phantasm) → int\n    → 1\n")
            assertTrue(errors.any { it is ResolveError.UndefinedSymbol && (it as ResolveError.UndefinedSymbol).name == "Phantasm" })
        }

        @Test
        fun `referencing undefined variable is an error`() {
            val errors = resolveErrors("fn f() → int\n    → missing\n")
            assertTrue(errors.any { it is ResolveError.UndefinedSymbol && (it as ResolveError.UndefinedSymbol).name == "missing" })
        }

        @Test
        fun `undefined return type is an error`() {
            val errors = resolveErrors("fn f() → Ghost\n    → Ghost()\n")
            assertTrue(errors.any { it is ResolveError.UndefinedSymbol && (it as ResolveError.UndefinedSymbol).name == "Ghost" })
        }
    }

    // ── Import tracking ───────────────────────────────────────────────────

    @Nested
    inner class ImportTracking {

        @Test
        fun `import produces UnresolvedImport warning in single-file mode`() {
            val errors = resolveErrors("import std.math\nfn f()\n    print(\"x\")\n")
            assertTrue(errors.any { it is ResolveError.UnresolvedImport && (it as ResolveError.UnresolvedImport).modulePath == "std.math" })
        }

        @Test
        fun `import result is Recovered not Failure`() {
            val result = resolveResult("import std.math\nfn f()\n    print(\"x\")\n")
            assertTrue(result is ResolveResult.Recovered)
        }

        @Test
        fun `imported module alias is in scope`() {
            val m = resolveModule("import std.math as m\nfn f()\n    let _ = m.PI\n")
            val undef = m.errors.filterIsInstance<ResolveError.UndefinedSymbol>()
            assertTrue(undef.none { it.name == "m" }, "Expected 'm' to be in scope, got: ${undef.map { it.name }}")
        }

        @Test
        fun `module dependency list contains all imported paths`() {
            val m = resolveModule("import std.io\nimport std.math\nfn f() → int\n    → 1\n")
            assertEquals(setOf("std.io", "std.math"), m.imports.map { it.path }.toSet())
        }

        @Test
        fun `import without alias uses last path component as name`() {
            val m = resolveModule("import std.math\nfn f()\n    let _ = math.PI\n")
            val undef = m.errors.filterIsInstance<ResolveError.UndefinedSymbol>()
            assertTrue(undef.none { it.name == "math" }, "Expected 'math' to be in scope")
        }
    }

    // ── resolvedRefs map ──────────────────────────────────────────────────

    @Nested
    inner class ResolvedRefsMap {

        @Test
        fun `IdentExpr pointing to a FunctionSym is recorded`() {
            val m = resolveModule("fn foo() → int\n    → 1\nfn bar() → int\n    → foo()\n")
            assertTrue(m.resolvedRefs.values.any { it is Symbol.FunctionSym && it.name == "foo" })
        }

        @Test
        fun `IdentExpr pointing to a local variable is recorded`() {
            val m = resolveModule("fn f() → int\n    let x = 42\n    → x\n")
            assertTrue(m.resolvedRefs.values.any { it is Symbol.LetSym && it.name == "x" })
        }

        @Test
        fun `resolvedRefs is empty for a file with no references`() {
            val m = resolveModule("fn f()\n    → 42\n")
            // No IdentExprs that refer to user symbols (only a literal)
            assertTrue(m.resolvedRefs.values.none { it is Symbol.FunctionSym || it is Symbol.LetSym })
        }

        @Test
        fun `built-in print is recorded in resolvedRefs`() {
            val m = resolveModule("fn main()\n    print(\"hi\")\n")
            assertTrue(m.resolvedRefs.values.any { it is Symbol.BuiltinSym && it.name == "print" })
        }
    }

    // ── Module scope ──────────────────────────────────────────────────────

    @Nested
    inner class ModuleScope {

        @Test
        fun `module scope contains all top-level function declarations`() {
            val m = resolveModule("fn foo() → int\n    → 1\nfn bar() → int\n    → 2\n")
            assertNotNull(m.moduleScope.lookupLocal("foo"))
            assertNotNull(m.moduleScope.lookupLocal("bar"))
        }

        @Test
        fun `module scope contains class declarations`() {
            val m = resolveModule("class Dog(name: str)\n")
            assertNotNull(m.moduleScope.lookupLocal("Dog"))
            assertTrue(m.moduleScope.lookupLocal("Dog") is Symbol.ClassSym)
        }

        @Test
        fun `module scope contains struct declarations`() {
            val m = resolveModule("struct Vec2(x: float, y: float)\n")
            assertNotNull(m.moduleScope.lookupLocal("Vec2"))
            assertTrue(m.moduleScope.lookupLocal("Vec2") is Symbol.StructSym)
        }

        @Test
        fun `module scope contains type alias declarations`() {
            val m = resolveModule("type Seconds = float\n")
            assertNotNull(m.moduleScope.lookupLocal("Seconds"))
            assertTrue(m.moduleScope.lookupLocal("Seconds") is Symbol.TypeAliasSym)
        }
    }
}
