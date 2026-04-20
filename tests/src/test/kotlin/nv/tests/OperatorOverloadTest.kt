package nv.tests

import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Phase 2.5: Operator overloading feature tests. */
class OperatorOverloadTest : NvCompilerTestBase() {

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

        @Test fun `fn less-than inside class body parses correctly`() {
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

        @Test fun `fn xor (⊕) inside class body parses correctly`() {
            val file = parseOk("""
                class Bits(pub v: int)
                    fn ⊕(other: Bits) → Bits
                        → Bits(v ⊕ other.v)
            """.trimIndent())
            val cls = file.declarations.first() as ClassDecl
            val xorFn = cls.members.filterIsInstance<FunctionDecl>().firstOrNull { it.name == "⊕" }
            assertNotNull(xorFn, "Expected FunctionDecl with name '⊕' in class members")
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

        @Test fun `binary != on type with fn != overload resolves correctly`() {
            noErrors("""
                class Point(pub x: float, pub y: float)
                    fn !=(other: Point) → bool
                        → x != other.x
                fn test(a: Point, b: Point) → bool
                    → a != b
            """.trimIndent())
        }

        @Test fun `binary lte on type with fn lte overload resolves correctly`() {
            noErrors("""
                class Weight(pub kg: float)
                    fn <=(other: Weight) → bool
                        → kg <= other.kg
                fn test(a: Weight, b: Weight) → bool
                    → a <= b
            """.trimIndent())
        }

        @Test fun `binary gte on type with fn gte overload resolves correctly`() {
            noErrors("""
                class Weight(pub kg: float)
                    fn >=(other: Weight) → bool
                        → kg >= other.kg
                fn test(a: Weight, b: Weight) → bool
                    → a >= b
            """.trimIndent())
        }

        @Test fun `binary + on type without overload still emits operator error`() {
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

    // ── Codegen: IR structure ─────────────────────────────────────────────

    @Nested inner class CodegenTests {

        @Test fun `op_plus is defined and called in LLVM IR`() {
            val ir = compileOk("""
                module test
                struct Vec2(pub x: float, pub y: float)
                    fn +(other: Vec2) → Vec2
                        → Vec2(x + other.x, y + other.y)
                fn main()
                    let a = Vec2(1.0, 2.0)
                    let b = Vec2(3.0, 4.0)
                    let c = a + b
                    println(c.x)
            """)
            assertTrue(ir.contains("@nv_Vec2_op_plus"), "op_plus definition or call missing in IR:\n$ir")
        }

        @Test fun `op_eq is defined and called in LLVM IR`() {
            val ir = compileOk("""
                module test
                struct Point(pub x: float, pub y: float)
                    fn ==(other: Point) → bool
                        → x == other.x
                fn main()
                    let a = Point(1.0, 2.0)
                    let b = Point(1.0, 3.0)
                    if a == b
                        println("equal")
            """)
            assertTrue(ir.contains("@nv_Point_op_eq"), "op_eq definition or call missing in IR:\n$ir")
        }

        @Test fun `op_neq is defined and called in LLVM IR`() {
            val ir = compileOk("""
                module test
                struct Pair(pub a: int, pub b: int)
                    fn !=(other: Pair) → bool
                        → a != other.a
                fn main()
                    let p = Pair(1, 2)
                    let q = Pair(3, 4)
                    if p != q
                        println("different")
            """)
            assertTrue(ir.contains("@nv_Pair_op_neq"), "op_neq definition or call missing in IR:\n$ir")
        }

        @Test fun `op_bitand is defined and called in LLVM IR`() {
            val ir = compileOk("""
                module test
                struct Flags(pub bits: int)
                    fn &(other: Flags) → Flags
                        → Flags(bits & other.bits)
                fn main()
                    let a = Flags(0b1100)
                    let b = Flags(0b1010)
                    let c = a & b
                    println(c.bits)
            """)
            assertTrue(ir.contains("@nv_Flags_op_bitand"), "op_bitand definition or call missing in IR:\n$ir")
        }

        @Test fun `op_bitor is defined and called in LLVM IR`() {
            val ir = compileOk("""
                module test
                struct Flags(pub bits: int)
                    fn |(other: Flags) → Flags
                        → Flags(bits | other.bits)
                fn main()
                    let a = Flags(0b0100)
                    let b = Flags(0b0010)
                    let c = a | b
                    println(c.bits)
            """)
            assertTrue(ir.contains("@nv_Flags_op_bitor"), "op_bitor definition or call missing in IR:\n$ir")
        }

        @Test fun `op_lt is defined and called in LLVM IR`() {
            val ir = compileOk("""
                module test
                struct Weight(pub kg: float)
                    fn <(other: Weight) → bool
                        → kg < other.kg
                fn main()
                    let a = Weight(10.0)
                    let b = Weight(20.0)
                    if a < b
                        println("lighter")
            """)
            assertTrue(ir.contains("@nv_Weight_op_lt"), "op_lt definition or call missing in IR:\n$ir")
        }
    }

    // ── Integration: end-to-end (clang required) ──────────────────────────

    @Nested inner class IntegrationTests {

        @Test fun `struct with fn + produces correct output`() {
            val ir = compileOk("""
                module test
                struct Vec2(pub x: float, pub y: float)
                    fn +(other: Vec2) → Vec2
                        → Vec2(x + other.x, y + other.y)
                fn main()
                    let a = Vec2(1.0, 2.0)
                    let b = Vec2(3.0, 4.0)
                    let c = a + b
                    println(c.x)
            """)
            val out = runProgramOrSkip(ir)
            // %g format: 4.0 prints as "4" (no trailing zeros)
            assertEquals("4", out)
        }

        @Test fun `struct with fn == prints equal when operands match`() {
            val ir = compileOk("""
                module test
                struct Point(pub x: float, pub y: float)
                    fn ==(other: Point) → bool
                        → x == other.x
                fn main()
                    let a = Point(1.0, 2.0)
                    let b = Point(1.0, 3.0)
                    if a == b
                        println("equal")
                    else
                        println("not equal")
            """)
            val out = runProgramOrSkip(ir)
            assertEquals("equal", out)
        }

        @Test fun `struct with fn lt is used in conditional`() {
            val ir = compileOk("""
                module test
                struct Weight(pub kg: float)
                    fn <(other: Weight) → bool
                        → kg < other.kg
                fn main()
                    let light = Weight(5.0)
                    let heavy = Weight(20.0)
                    if light < heavy
                        println("lighter")
                    else
                        println("heavier")
            """)
            val out = runProgramOrSkip(ir)
            assertEquals("lighter", out)
        }
    }
}
