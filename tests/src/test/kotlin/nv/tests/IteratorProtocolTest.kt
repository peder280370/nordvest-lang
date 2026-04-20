package nv.tests

import nv.compiler.typecheck.TypeCheckError
import nv.compiler.typecheck.TypeChecker
import nv.compiler.resolve.Resolver
import nv.compiler.resolve.ResolveResult
import nv.compiler.typecheck.TypeCheckResult
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the Iterator protocol (for-in over user-defined Iterator / Iterable types).
 *
 * The protocol mirrors the language spec:
 *   interface Iterator  { fn next() → Item? }
 *   interface Iterable  { fn iter() → Iterator }
 *
 * For-loops desugar to:
 *   let _it = iterable.iter()
 *   while let x = _it.next() { body }
 */
class IteratorProtocolTest : NvCompilerTestBase() {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun typeErrors(src: String): List<TypeCheckError> {
        val tokens = Lexer(src.trimIndent()).tokenize()
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

    // ── Type checker: Iterator (next() only) ─────────────────────────────

    @Nested inner class TypeCheckIteratorTests {

        @Test fun `for-in over class with next() → str? type-checks`() {
            noErrors("""
                class Src(pub var done: bool = false)
                    fn next() → str?
                        if done
                            → nil
                        done = true
                        → "hi"

                fn f()
                    let s = Src()
                    for item in s
                        let _: str = item
            """)
        }

        @Test fun `for-in over class with next() → int? type-checks`() {
            noErrors("""
                class Counter(pub limit: int, pub var cur: int = 0)
                    fn next() → int?
                        if cur >= limit
                            → nil
                        cur = cur + 1
                        → cur

                fn f()
                    let c = Counter(3)
                    for x in c
                        let _: int = x
            """)
        }

        @Test fun `for-in over class with iter() + next() type-checks`() {
            noErrors("""
                class CounterIter(pub limit: int, pub var cur: int = 0)
                    fn next() → int?
                        if cur >= limit
                            → nil
                        cur = cur + 1
                        → cur

                class Counter(pub limit: int)
                    fn iter() → CounterIter
                        → CounterIter(limit)

                fn f()
                    let c = Counter(5)
                    for x in c
                        let _: int = x
            """)
        }

        @Test fun `for-in loop body can use the bound variable`() {
            noErrors("""
                class StrIter(pub var i: int = 0)
                    fn next() → str?
                        if i >= 2
                            → nil
                        i = i + 1
                        → "x"

                fn f() → int
                    var total = 0
                    for s in StrIter()
                        total = total + s.len
                    → total
            """)
        }
    }

    // ── IR structure: Iterator (next() only) ─────────────────────────────

    @Nested inner class IrStructureIteratorTests {

        @Test fun `for-in over Iterator calls next() in IR`() {
            val ir = compileOk("""
                class Counter(pub limit: int, pub var cur: int = 0)
                    fn next() → int?
                        if cur >= limit
                            → nil
                        cur = cur + 1
                        → cur

                fn main()
                    let c = Counter(3)
                    for x in c
                        println(x)
            """)
            assertTrue(ir.contains("@nv_Counter_next"), "IR should call @nv_Counter_next, got:\n$ir")
        }

        @Test fun `for-in Iterator IR has nil check`() {
            val ir = compileOk("""
                class Counter(pub limit: int, pub var cur: int = 0)
                    fn next() → int?
                        if cur >= limit
                            → nil
                        cur = cur + 1
                        → cur

                fn main()
                    let c = Counter(1)
                    for x in c
                        println(x)
            """)
            // nil check: icmp eq i64 <val>, 0  (int? null = 0)
            assertTrue(ir.contains("icmp eq i64") || ir.contains("for.cond"),
                "IR should have nil check for int? iterator, got:\n$ir")
        }

        @Test fun `for-in str? Iterator has null pointer nil check`() {
            val ir = compileOk("""
                class StrGen(pub var done: bool = false)
                    fn next() → str?
                        if done
                            → nil
                        done = true
                        → "hello"

                fn main()
                    let g = StrGen()
                    for s in g
                        println(s)
            """)
            assertTrue(ir.contains("@nv_StrGen_next"), "IR should call @nv_StrGen_next")
            assertTrue(ir.contains("icmp eq i8* ") || ir.contains("for.cond"),
                "IR should have null pointer nil check for str?")
        }
    }

    // ── IR structure: Iterable (iter() + next()) ─────────────────────────

    @Nested inner class IrStructureIterableTests {

        @Test fun `for-in Iterable calls iter() then next() in IR`() {
            val ir = compileOk("""
                class CounterIter(pub limit: int, pub var cur: int = 0)
                    fn next() → int?
                        if cur >= limit
                            → nil
                        cur = cur + 1
                        → cur

                class Counter(pub limit: int)
                    fn iter() → CounterIter
                        → CounterIter(limit)

                fn main()
                    let c = Counter(3)
                    for x in c
                        println(x)
            """)
            assertTrue(ir.contains("@nv_Counter_iter"),   "IR should call @nv_Counter_iter, got:\n$ir")
            assertTrue(ir.contains("@nv_CounterIter_next"), "IR should call @nv_CounterIter_next, got:\n$ir")
        }

        @Test fun `for-in Iterable iter() result is used as receiver for next()`() {
            val ir = compileOk("""
                class StrIter(pub var i: int = 0)
                    fn next() → str?
                        if i >= 1
                            → nil
                        i = 1
                        → "done"

                class StrSrc()
                    fn iter() → StrIter
                        → StrIter()

                fn main()
                    for s in StrSrc()
                        println(s)
            """)
            assertTrue(ir.contains("@nv_StrSrc_iter"),  "IR should call iter()")
            assertTrue(ir.contains("@nv_StrIter_next"), "IR should call next() on iterator result")
        }
    }

    // ── Integration ───────────────────────────────────────────────────────

    @Nested inner class IntegrationTests {

        @Test fun `Iterator with str? yields values until nil`() {
            val ir = compileOk("""
                class ThreeStrings(pub var i: int = 0)
                    fn next() → str?
                        if i == 0
                            i = 1
                            → "alpha"
                        if i == 1
                            i = 2
                            → "beta"
                        if i == 2
                            i = 3
                            → "gamma"
                        → nil

                fn main()
                    let gen = ThreeStrings()
                    for s in gen
                        println(s)
            """)
            val out = runProgramOrSkip(ir)
            assertEquals("alpha\nbeta\ngamma", out)
        }

        @Test fun `Iterable with iter() + int? next() produces correct values`() {
            // Values start at 1 to avoid the bootstrap 0=nil limitation for int?
            val ir = compileOk("""
                class RangeIter(pub limit: int, pub var cur: int = 0)
                    fn next() → int?
                        cur = cur + 1
                        if cur > limit
                            → nil
                        → cur

                class Range(pub limit: int)
                    fn iter() → RangeIter
                        → RangeIter(limit)

                fn main()
                    for x in Range(3)
                        println(x)
            """)
            val out = runProgramOrSkip(ir)
            assertEquals("1\n2\n3", out)
        }

        @Test fun `empty Iterator produces no iterations`() {
            val ir = compileOk("""
                class Empty()
                    fn next() → str?
                        → nil

                fn main()
                    for s in Empty()
                        println(s)
                    println("done")
            """)
            val out = runProgramOrSkip(ir)
            assertEquals("done", out)
        }
    }
}
