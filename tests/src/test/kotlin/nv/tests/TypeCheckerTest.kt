package nv.tests

import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TypeCheckerTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun typeCheck(src: String): TypeCheckedModule {
        val tokens = Lexer(src).tokenize()
        val file = when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> fail("Parse failed: ${r.errors.first().message}")
        }
        val module = when (val r = Resolver("<test>").resolve(file)) {
            is ResolveResult.Success   -> r.module
            is ResolveResult.Recovered -> r.module
            is ResolveResult.Failure   -> fail("Resolve failed: ${r.errors.first().message}")
        }
        return when (val r = TypeChecker(module).check()) {
            is TypeCheckResult.Success   -> r.module
            is TypeCheckResult.Recovered -> r.module
            is TypeCheckResult.Failure   -> fail("TypeChecker hard failure: ${r.errors.first().message}")
        }
    }

    private fun typeErrors(src: String): List<TypeCheckError> {
        val tokens = Lexer(src).tokenize()
        val file = when (val r = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> return emptyList()
        }
        val module = when (val r = Resolver("<test>").resolve(file)) {
            is ResolveResult.Success   -> r.module
            is ResolveResult.Recovered -> r.module
            is ResolveResult.Failure   -> return emptyList()
        }
        return when (val r = TypeChecker(module).check()) {
            is TypeCheckResult.Success   -> emptyList()
            is TypeCheckResult.Recovered -> r.module.errors
            is TypeCheckResult.Failure   -> r.errors
        }
    }

    private fun noErrors(src: String) {
        val errs = typeErrors(src)
        assertTrue(errs.isEmpty(), "Expected no type errors but got: ${errs.map { it.message }}")
    }

    private fun hasError(src: String, kind: Class<out TypeCheckError>) {
        val errs = typeErrors(src)
        assertTrue(errs.any { kind.isInstance(it) },
            "Expected ${kind.simpleName} error but got: ${errs.map { it::class.simpleName }}")
    }

    // Resolve the type of the first expression inside a helper function body
    private fun exprType(src: String): Type {
        val m = typeCheck(src)
        return m.typeMap.values.firstOrNull() ?: Type.TUnknown
    }

    // ── Literal types ─────────────────────────────────────────────────────

    @Nested inner class Literals {
        @Test fun `int literal`() {
            val m = typeCheck("fn f() → int\n    → 42")
            assertTrue(m.typeMap.values.any { it == Type.TInt })
        }

        @Test fun `float literal`() {
            val m = typeCheck("fn f() → float\n    → 3.14")
            assertTrue(m.typeMap.values.any { it == Type.TFloat })
        }

        @Test fun `bool literal true`() {
            val m = typeCheck("fn f() → bool\n    → true")
            assertTrue(m.typeMap.values.any { it == Type.TBool })
        }

        @Test fun `bool literal false`() {
            val m = typeCheck("fn f() → bool\n    → false")
            assertTrue(m.typeMap.values.any { it == Type.TBool })
        }

        @Test fun `string literal`() {
            val m = typeCheck("fn f() → str\n    → \"hello\"")
            assertTrue(m.typeMap.values.any { it == Type.TStr })
        }

        @Test fun `char literal`() {
            val m = typeCheck("fn f() → char\n    → 'x'")
            assertTrue(m.typeMap.values.any { it == Type.TChar })
        }

        @Test fun `nil literal is nullable`() {
            val m = typeCheck("fn f() → int?\n    → nil")
            assertTrue(m.typeMap.values.any { it is Type.TNullable })
        }

        @Test fun `pi constant is float`() {
            val m = typeCheck("fn f() → float\n    → π")
            assertTrue(m.typeMap.values.any { it == Type.TFloat })
        }
    }

    // ── Arithmetic operator types ─────────────────────────────────────────

    @Nested inner class Arithmetic {
        @Test fun `int plus int gives int`() = noErrors("""
fn add(a: int, b: int) → int
    → a + b
        """.trimIndent())

        @Test fun `int plus float gives float`() = noErrors("""
fn f(a: int, b: float) → float
    → a + b
        """.trimIndent())

        @Test fun `int minus int is int`() = noErrors("""
fn f(a: int, b: int) → int
    → a - b
        """.trimIndent())

        @Test fun `star gives int for int operands`() = noErrors("""
fn f(a: int, b: int) → int
    → a * b
        """.trimIndent())

        @Test fun `int div requires int operands`() = noErrors("""
fn f(a: int, b: int) → int
    → a ÷ b
        """.trimIndent())

        @Test fun `mod requires int operands`() = noErrors("""
fn f(a: int, b: int) → int
    → a % b
        """.trimIndent())

        @Test fun `comparison gives bool`() = noErrors("""
fn f(a: int, b: int) → bool
    → a < b
        """.trimIndent())

        @Test fun `equality gives bool`() = noErrors("""
fn f(a: int, b: int) → bool
    → a == b
        """.trimIndent())

        @Test fun `logical and requires bool`() = noErrors("""
fn f(a: bool, b: bool) → bool
    → a && b
        """.trimIndent())

        @Test fun `logical or requires bool`() = noErrors("""
fn f(a: bool, b: bool) → bool
    → a || b
        """.trimIndent())

        @Test fun `logical and on int gives error`() =
            hasError("fn f(a: int, b: int) → bool\n    → a && b", TypeCheckError.OperatorTypeMismatch::class.java)

        @Test fun `negate on int is ok`() = noErrors("""
fn f(a: int) → int
    → -a
        """.trimIndent())

        @Test fun `not on non-bool gives error`() =
            hasError("fn f(a: int) → bool\n    → ¬a", TypeCheckError.UnaryTypeMismatch::class.java)

        @Test fun `bitwise and requires int-like`() = noErrors("""
fn f(a: int, b: int) → int
    → a & b
        """.trimIndent())

        @Test fun `power operator`() = noErrors("""
fn f(base: int, exp: int) → int
    → base ^ exp
        """.trimIndent())
    }

    // ── Let / var inference ───────────────────────────────────────────────

    @Nested inner class Bindings {
        @Test fun `let inferred from int literal`() = noErrors("""
fn f()
    let x = 42
    println(x)
        """.trimIndent())

        @Test fun `let with annotation`() = noErrors("""
fn f()
    let x: int = 42
    println(x)
        """.trimIndent())

        @Test fun `let annotation mismatch gives error`() =
            hasError("""
fn f()
    let x: bool = 42
            """.trimIndent(), TypeCheckError.TypeMismatch::class.java)

        @Test fun `var inferred from float literal`() = noErrors("""
fn f()
    var x = 3.14
    println(x)
        """.trimIndent())

        @Test fun `let without initializer and no annotation gives error`() =
            hasError("""
fn f()
    let x
            """.trimIndent(), TypeCheckError.CannotInferType::class.java)
    }

    // ── Function types ────────────────────────────────────────────────────

    @Nested inner class Functions {
        @Test fun `function return type annotation enforced`() =
            hasError("""
fn f() → int
    → "hello"
            """.trimIndent(), TypeCheckError.ReturnTypeMismatch::class.java)

        @Test fun `function returns unit when no annotation`() = noErrors("""
fn f()
    let x = 1
        """.trimIndent())

        @Test fun `function call arg count mismatch`() =
            hasError("""
fn add(a: int, b: int) → int
    → a + b
fn g() → int
    → add(1, 2, 3)
            """.trimIndent(), TypeCheckError.ArityMismatch::class.java)

        @Test fun `function call arg type mismatch`() =
            hasError("""
fn greet(name: str)
    println(name)
fn g()
    greet(42)
            """.trimIndent(), TypeCheckError.TypeMismatch::class.java)

        @Test fun `function call correct types`() = noErrors("""
fn add(a: int, b: int) → int
    → a + b
fn g() → int
    → add(1, 2)
        """.trimIndent())

        @Test fun `higher-order function type`() = noErrors("""
fn applyTwice(f: (int) → int, x: int) → int
    → f(f(x))
        """.trimIndent())
    }

    // ── Null safety ───────────────────────────────────────────────────────

    @Nested inner class NullSafety {
        @Test fun `null coalesce T? ?? T gives T`() = noErrors("""
fn f(x: str?) → str
    → x ?? "default"
        """.trimIndent())

        @Test fun `force unwrap nullable`() = noErrors("""
fn f(x: int?) → int
    → x!
        """.trimIndent())

        @Test fun `force unwrap non-nullable gives error`() =
            hasError("""
fn f(x: int) → int
    → x!
            """.trimIndent(), TypeCheckError.ForceUnwrapNonNullable::class.java)

        @Test fun `if let narrows nullable`() = noErrors("""
fn f(x: int?)
    if let v = x
        println(v)
        """.trimIndent())

        @Test fun `guard let narrows nullable`() {
            val errs = typeErrors("""
fn f(x: str?)
    guard let s = x else
        → ()
    println(s)
            """.trimIndent())
            assertTrue(errs.isEmpty(), "Expected no errors but got: ${errs.map { it.message }}")
        }

        @Test fun `result propagation on Result type`() = noErrors("""
fn parse(s: str) → Result<int>
    → Ok(0)
fn f(s: str) → Result<int>
    let n = parse(s)?
    → Ok(n)
        """.trimIndent())

        @Test fun `result propagation on non-result gives error`() =
            hasError("""
fn f(x: int) → int
    → x?
            """.trimIndent(), TypeCheckError.ResultPropagateNonResult::class.java)
    }

    // ── Type tests and casts ─────────────────────────────────────────────

    @Nested inner class TypeTests {
        @Test fun `is expression gives bool`() = noErrors("""
fn f(x: int) → bool
    → x is int
        """.trimIndent())

        @Test fun `safe cast gives nullable`() = noErrors("""
fn f(x: int) → float?
    → x as? float
        """.trimIndent())

        @Test fun `force cast gives non-nullable`() = noErrors("""
fn f(x: int) → float
    → x as! float
        """.trimIndent())
    }

    // ── Match exhaustiveness ──────────────────────────────────────────────

    @Nested inner class MatchExhaustiveness {
        @Test fun `exhaustive bool match passes`() = noErrors("""
fn f(x: bool) → str
    match x
        true:  → "yes"
        false: → "no"
        """.trimIndent())

        @Test fun `non-exhaustive bool match missing false`() =
            hasError("""
fn f(x: bool) → str
    match x
        true: → "yes"
            """.trimIndent(), TypeCheckError.NonExhaustiveMatch::class.java)

        @Test fun `non-exhaustive bool match missing true`() =
            hasError("""
fn f(x: bool) → str
    match x
        false: → "no"
            """.trimIndent(), TypeCheckError.NonExhaustiveMatch::class.java)

        @Test fun `wildcard covers bool match`() = noErrors("""
fn f(x: bool) → str
    match x
        true: → "yes"
        _:    → "other"
        """.trimIndent())

        @Test fun `binding pattern covers match`() = noErrors("""
fn f(x: bool) → str
    match x
        true: → "yes"
        other: → "no"
        """.trimIndent())

        @Test fun `exhaustive sealed class match`() = noErrors("""
sealed class Shape
    Circle(radius: float)
    Rect(w: float, h: float)
fn area(s: Shape) → float
    match s
        Circle(r): → 3.14 * r * r
        Rect(w, h): → w * h
        """.trimIndent())

        @Test fun `non-exhaustive sealed class match`() =
            hasError("""
sealed class Shape
    Circle(radius: float)
    Rect(w: float, h: float)
fn area(s: Shape) → float
    match s
        Circle(r): → 3.14 * r * r
            """.trimIndent(), TypeCheckError.NonExhaustiveMatch::class.java)

        @Test fun `wildcard covers sealed class match`() = noErrors("""
sealed class Color
    Red
    Green
    Blue
fn f(c: Color) → str
    match c
        Red: → "red"
        _:   → "other"
        """.trimIndent())

        @Test fun `nullable exhaustiveness - requires nil arm`() =
            hasError("""
fn f(x: int?) → str
    match x
        42: → "the answer"
            """.trimIndent(), TypeCheckError.NonExhaustiveMatch::class.java)

        @Test fun `nullable match with nil arm ok`() = noErrors("""
fn f(x: int?) → str
    match x
        nil: → "nothing"
        _:   → "something"
        """.trimIndent())
    }

    // ── Conditions ───────────────────────────────────────────────────────

    @Nested inner class Conditions {
        @Test fun `if condition must be bool`() =
            hasError("""
fn f(x: int)
    if x
        println("yes")
            """.trimIndent(), TypeCheckError.ConditionNotBool::class.java)

        @Test fun `while condition must be bool`() =
            hasError("""
fn f()
    while 1
        println("loop")
            """.trimIndent(), TypeCheckError.ConditionNotBool::class.java)

        @Test fun `inline if condition must be bool`() =
            hasError("""
fn f(x: int) → str
    → if x then "yes" else "no"
            """.trimIndent(), TypeCheckError.ConditionNotBool::class.java)
    }

    // ── Collections ───────────────────────────────────────────────────────

    @Nested inner class Collections {
        @Test fun `array literal infers element type`() = noErrors("""
fn f() → [int]
    → [1, 2, 3]
        """.trimIndent())

        @Test fun `empty array`() = noErrors("""
fn f() → [int]
    let xs: [int] = []
    → xs
        """.trimIndent())

        @Test fun `map literal`() = noErrors("""
fn f() → [str: int]
    → ["a": 1, "b": 2]
        """.trimIndent())

        @Test fun `array indexing gives element type`() = noErrors("""
fn f(xs: [int]) → int
    → xs[0]
        """.trimIndent())

        @Test fun `str indexing gives char`() = noErrors("""
fn f(s: str) → char
    → s[0]
        """.trimIndent())
    }

    // ── Struct / class types ──────────────────────────────────────────────

    @Nested inner class NamedTypes {
        @Test fun `struct constructor type`() = noErrors("""
struct Point(x: float, y: float)
fn f() → Point
    → Point(1.0, 2.0)
        """.trimIndent())

        @Test fun `class constructor type`() = noErrors("""
class Person(name: str, age: int)
fn f() → Person
    → Person("Alice", 30)
        """.trimIndent())

        @Test fun `member access on struct`() = noErrors("""
struct Point(x: float, y: float)
fn f(p: Point) → float
    → p.x
        """.trimIndent())
    }

    // ── Lambda types ──────────────────────────────────────────────────────

    @Nested inner class Lambdas {
        @Test fun `lambda with annotated params`() = noErrors("""
fn f() → int
    let double = (x: int) → x * 2
    → double(5)
        """.trimIndent())

        @Test fun `lambda inferred from context`() = noErrors("""
fn apply(f: (int) → int, x: int) → int
    → f(x)
fn g() → int
    → apply(x → x + 1, 5)
        """.trimIndent())
    }

    // ── Type aliases ──────────────────────────────────────────────────────

    @Nested inner class TypeAliases {
        @Test fun `type alias resolves correctly`() = noErrors("""
type Meters = float
fn f(d: Meters) → Meters
    → d * 2.0
        """.trimIndent())
    }

    // ── List comprehension ────────────────────────────────────────────────

    @Nested inner class Comprehensions {
        @Test fun `list comprehension gives array type`() = noErrors("""
fn f(xs: [int]) → [int]
    → [x * 2 for x in xs]
        """.trimIndent())
    }

    // ── Quantifiers ───────────────────────────────────────────────────────

    @Nested inner class Quantifiers {
        @Test fun `forall gives bool`() = noErrors("""
fn f(xs: [int]) → bool
    → ∀ x ∈ xs: x > 0
        """.trimIndent())

        @Test fun `sum gives numeric type`() = noErrors("""
fn f(xs: [float]) → float
    → ∑ x ∈ xs: x
        """.trimIndent())
    }

    // ── Generic functions ─────────────────────────────────────────────────

    @Nested inner class Generics {
        @Test fun `generic identity function`() = noErrors("""
fn identity<T>(x: T) → T
    → x
        """.trimIndent())

        @Test fun `generic function called with int`() = noErrors("""
fn identity<T>(x: T) → T
    → x
fn f() → int
    → identity(42)
        """.trimIndent())
    }

    // ── Immutability ─────────────────────────────────────────────────────

    @Nested inner class Mutability {
        @Test fun `assigning to let binding gives error`() =
            hasError("""
fn f()
    let x = 42
    x = 99
            """.trimIndent(), TypeCheckError.AssignToImmutable::class.java)

        @Test fun `assigning to var binding is ok`() = noErrors("""
fn f()
    var x = 42
    x = 99
        """.trimIndent())
    }

    // ── Interpolated strings ──────────────────────────────────────────────

    @Nested inner class StringInterpolation {
        @Test fun `interpolated string is str`() = noErrors("""
fn f(name: str) → str
    → "Hello {name}"
        """.trimIndent())
    }

    // ── No spurious errors on valid programs ──────────────────────────────

    @Nested inner class ValidPrograms {
        @Test fun `hello world`() = noErrors("""
fn main()
    println("Hello, World!")
        """.trimIndent())

        @Test fun `factorial recursive`() = noErrors("""
fn factorial(n: int) → int
    if n <= 0
        → 1
    → n * factorial(n - 1)
        """.trimIndent())

        @Test fun `fibonacci`() = noErrors("""
fn fib(n: int) → int
    if n <= 1
        → n
    → fib(n - 1) + fib(n - 2)
        """.trimIndent())

        @Test fun `nullable chain`() = noErrors("""
fn firstName(fullName: str?) → str
    → fullName?.trim() ?? "Unknown"
        """.trimIndent())

        @Test fun `for loop over array`() = noErrors("""
fn sum(xs: [int]) → int
    var total = 0
    for x in xs
        total = total + x
    → total
        """.trimIndent())

        @Test fun `result type usage`() = noErrors("""
fn safeDivide(a: int, b: int) → Result<int>
    if b == 0
        → Err(0)
    → Ok(a ÷ b)
        """.trimIndent())

        @Test fun `inline if expression`() = noErrors("""
fn abs(x: int) → int
    → if x < 0 then -x else x
        """.trimIndent())
    }
}
