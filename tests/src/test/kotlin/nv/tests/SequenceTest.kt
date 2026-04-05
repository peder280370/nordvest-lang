package nv.tests

import nv.compiler.Compiler
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Phase 2.6: Lazy sequences / Iterator protocol feature tests. */
class SequenceTest {

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

    private fun hasError(src: String, check: (TypeCheckError) -> Boolean): Boolean {
        return typeErrors(src).any(check)
    }

    // ── Type: Sequence<T> ─────────────────────────────────────────────────

    @Nested inner class SequenceTypeTests {

        @Test fun `Sequence type resolves correctly in type annotation`() {
            noErrors("""
                fn makeSeq() → Sequence<int>
                    yield 1
                    yield 2
            """.trimIndent())
        }

        @Test fun `Sequence type as parameter type-checks`() {
            noErrors("""
                fn consume(seq: Sequence<str>)
                    for item in seq
                        let s: str = item
            """.trimIndent())
        }

        @Test fun `Sequence of float parameter type-checks`() {
            noErrors("""
                fn sum_seq(seq: Sequence<float>) → float
                    var total = 0.0
                    for x in seq
                        total = total + x
                    → total
            """.trimIndent())
        }
    }

    // ── yield statement ───────────────────────────────────────────────────

    @Nested inner class YieldTests {

        @Test fun `yield in Sequence-returning function is valid`() {
            noErrors("""
                fn range(n: int) → Sequence<int>
                    var i = 0
                    while i < n
                        yield i
                        i = i + 1
            """.trimIndent())
        }

        @Test fun `yield in non-sequence function produces YieldOutsideSequence error`() {
            assertTrue(
                hasError("""
                    fn notASeq() → int
                        yield 42
                        → 0
                """.trimIndent()) { it is TypeCheckError.YieldOutsideSequence },
                "Expected YieldOutsideSequence error"
            )
        }

        @Test fun `yield in unit-returning function produces YieldOutsideSequence error`() {
            assertTrue(
                hasError("""
                    fn noReturn()
                        yield 1
                """.trimIndent()) { it is TypeCheckError.YieldOutsideSequence },
                "Expected YieldOutsideSequence error for unit function"
            )
        }

        @Test fun `multiple yields in Sequence-returning function are valid`() {
            noErrors("""
                fn items() → Sequence<str>
                    yield "hello"
                    yield "world"
            """.trimIndent())
        }
    }

    // ── for-loop over Sequence<T> ─────────────────────────────────────────

    @Nested inner class ForLoopTests {

        @Test fun `for-loop over Sequence infers element type T`() {
            noErrors("""
                fn process(seq: Sequence<int>)
                    for x in seq
                        let doubled: int = x + x
            """.trimIndent())
        }

        @Test fun `for-loop over Sequence of str infers str element type`() {
            noErrors("""
                fn printAll(seq: Sequence<str>)
                    for s in seq
                        let upper: str = s
            """.trimIndent())
        }

        @Test fun `for-loop over Sequence of float infers float element type`() {
            noErrors("""
                fn sumAll(seq: Sequence<float>) → float
                    var total = 0.0
                    for x in seq
                        total = total + x
                    → total
            """.trimIndent())
        }
    }

    // ── Sequence member methods ───────────────────────────────────────────

    @Nested inner class MemberTests {

        @Test fun `Sequence toList returns array type`() {
            noErrors("""
                fn toArr(seq: Sequence<int>) → [int]
                    → seq.toList()
            """.trimIndent())
        }

        @Test fun `Sequence take returns Sequence`() {
            noErrors("""
                fn firstFive(seq: Sequence<int>) → Sequence<int>
                    → seq.take(5)
            """.trimIndent())
        }

        @Test fun `Sequence drop returns Sequence`() {
            noErrors("""
                fn skipFirst(seq: Sequence<int>) → Sequence<int>
                    → seq.drop(3)
            """.trimIndent())
        }
    }

    // ── Codegen: Sequence ─────────────────────────────────────────────────

    @Nested inner class CodegenTests {

        @Test fun `Sequence-returning function compiles without error`() {
            val result = Compiler.compile("""
                fn range(n: int) → Sequence<int>
                    var i = 0
                    while i < n
                        yield i
                        i = i + 1
            """.trimIndent(), "<test>")
            assertTrue(result is nv.compiler.CompileResult.IrSuccess,
                "Expected IrSuccess, got: $result")
        }

        @Test fun `Sequence parameter function compiles without error`() {
            val result = Compiler.compile("""
                fn consume(seq: Sequence<int>) → int
                    → 0
            """.trimIndent(), "<test>")
            assertTrue(result is nv.compiler.CompileResult.IrSuccess,
                "Expected IrSuccess, got: $result")
        }
    }
}
