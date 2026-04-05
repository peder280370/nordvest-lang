package nv.tests

import nv.compiler.Compiler
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Phase 2.5: Operator overloading feature tests. */
class OperatorOverloadTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun parseOk(src: String): SourceFile {
        val tokens = Lexer(src).tokenize()
        return when (val result = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> result.file
            is ParseResult.Recovered -> result.file
            is ParseResult.Failure   -> fail("Parse failed: ${result.errors.first().message}")
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

    // ── Parser: operator overloading ───────────────────────────────────────

    @Nested inner class ParserTests {

        @Test fun `fn + inside class body parses as FunctionDecl with name "+"`() {
            val file = parseOk("""
                class Vec2(pub x: float, pub y: float)
                    fn +(other: Vec2) → Vec2
                        → Vec2(x + other.x, y + other.y)
            """.trimIndent())
            val cls = file.declarations.first() as ClassDecl
            val plusFn = cls.members.filterIsInstance<FunctionDecl>().firstOrNull { it.name == "+" }
            assertNotNull(plusFn, "Expected FunctionDecl with name '+' in class members")
        }

        @Test fun `fn - inside class body parses correctly`() {
            val file = parseOk("""
                class Vec2(pub x: float, pub y: float)
                    fn -(other: Vec2) → Vec2
                        → Vec2(x - other.x, y - other.y)
            """.trimIndent())
            val cls = file.declarations.first() as ClassDecl
            val minusFn = cls.members.filterIsInstance<FunctionDecl>().firstOrNull { it.name == "-" }
            assertNotNull(minusFn, "Expected FunctionDecl with name '-' in class members")
        }

        @Test fun `fn * inside class body parses correctly`() {
            val file = parseOk("""
                class Vec2(pub x: float, pub y: float)
                    fn *(scalar: float) → Vec2
                        → Vec2(x * scalar, y * scalar)
            """.trimIndent())
            val cls = file.declarations.first() as ClassDecl
            val timesFn = cls.members.filterIsInstance<FunctionDecl>().firstOrNull { it.name == "*" }
            assertNotNull(timesFn, "Expected FunctionDecl with name '*' in class members")
        }

        @Test fun `fn == inside class body parses correctly`() {
            val file = parseOk("""
                class Point(pub x: float, pub y: float)
                    fn ==(other: Point) → bool
                        → x == other.x
            """.trimIndent())
            val cls = file.declarations.first() as ClassDecl
            val eqFn = cls.members.filterIsInstance<FunctionDecl>().firstOrNull { it.name == "==" }
            assertNotNull(eqFn, "Expected FunctionDecl with name '==' in class members")
        }

        @Test fun `fn lt inside class body parses correctly`() {
            val file = parseOk("""
                class Ordered(pub val: int)
                    fn <(other: Ordered) → bool
                        → val < other.val
            """.trimIndent())
            val cls = file.declarations.first() as ClassDecl
            val ltFn = cls.members.filterIsInstance<FunctionDecl>().firstOrNull { it.name == "<" }
            assertNotNull(ltFn, "Expected FunctionDecl with name '<' in class members")
        }

        @Test fun `multiple operator overloads in one class parse correctly`() {
            val file = parseOk("""
                class Vec(pub x: float)
                    fn +(other: Vec) → Vec
                        → Vec(x + other.x)
                    fn -(other: Vec) → Vec
                        → Vec(x - other.x)
                    fn *(s: float) → Vec
                        → Vec(x * s)
            """.trimIndent())
            val cls = file.declarations.first() as ClassDecl
            val ops = cls.members.filterIsInstance<FunctionDecl>().map { it.name }
            assertTrue("+" in ops, "Expected '+' operator")
            assertTrue("-" in ops, "Expected '-' operator")
            assertTrue("*" in ops, "Expected '*' operator")
        }
    }

    // ── Type checker: operator overloading ────────────────────────────────

    @Nested inner class TypeCheckerTests {

        @Test fun `binary + on type with fn + overload resolves to return type`() {
            noErrors("""
                class Vec2(pub x: float, pub y: float)
                    fn +(other: Vec2) → Vec2
                        → Vec2(x + other.x, y + other.y)

                fn test(a: Vec2, b: Vec2) → Vec2
                    → a + b
            """.trimIndent())
        }

        @Test fun `binary - on type with fn - overload resolves correctly`() {
            noErrors("""
                class Vec2(pub x: float, pub y: float)
                    fn -(other: Vec2) → Vec2
                        → Vec2(x - other.x, y - other.y)

                fn test(a: Vec2, b: Vec2) → Vec2
                    → a - b
            """.trimIndent())
        }

        @Test fun `binary + on type without overload still emits operator error`() {
            // A class with no + operator - using + on it should produce an OperatorTypeMismatch
            val errs = typeErrors("""
                class Blob(pub size: int)

                fn test(a: Blob, b: Blob) → Blob
                    → a + b
            """.trimIndent())
            assertTrue(errs.any { it is TypeCheckError.OperatorTypeMismatch },
                "Expected OperatorTypeMismatch when no + overload defined, but got: ${errs.map { it.message }}")
        }

        @Test fun `int + int still works with no overload lookup`() {
            noErrors("""
                fn add(a: int, b: int) → int
                    → a + b
            """.trimIndent())
        }
    }
}
