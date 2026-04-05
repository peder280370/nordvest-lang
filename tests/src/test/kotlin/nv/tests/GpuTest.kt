package nv.tests

import nv.compiler.Compiler
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Phase 2.3: GPU acceleration (@gpu) feature tests. */
class GpuTest {

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

    private fun compileIr(src: String): String {
        val result = Compiler.compile(src, "<test>")
        return when (result) {
            is nv.compiler.CompileResult.IrSuccess -> result.llvmIr
            is nv.compiler.CompileResult.Failure   -> fail("Compile failed: ${result.errors.first().message}")
            else -> fail("Unexpected result: $result")
        }
    }

    // ── Parser: @gpu annotation ────────────────────────────────────────────

    @Nested inner class ParserTests {

        @Test fun `@gpu annotation parses on function`() {
            val file = parseOk("""
                @gpu
                fn matmul(a: float, b: float) → float
                    → a * b
            """.trimIndent())
            val fn = file.declarations.first() as FunctionDecl
            assertTrue(fn.annotations.any { it.name == "gpu" }, "Expected @gpu annotation")
        }

        @Test fun `@gpu annotation with arguments parses`() {
            val file = parseOk("""
                @gpu(threads: 256)
                fn kernel(x: float) → float
                    → x
            """.trimIndent())
            val fn = file.declarations.first() as FunctionDecl
            assertTrue(fn.annotations.any { it.name == "gpu" }, "Expected @gpu annotation")
        }

        @Test fun `function without @gpu has no gpu annotation`() {
            val file = parseOk("""
                fn regular(x: float) → float
                    → x
            """.trimIndent())
            val fn = file.declarations.first() as FunctionDecl
            assertFalse(fn.annotations.any { it.name == "gpu" }, "Did not expect @gpu annotation")
        }
    }

    // ── Type checker: @gpu ─────────────────────────────────────────────────

    @Nested inner class TypeCheckerTests {

        @Test fun `@gpu function type-checks correctly`() {
            noErrors("""
                @gpu
                fn matmul(a: float, b: float) → float
                    → a * b
            """.trimIndent())
        }

        @Test fun `@gpu function with unit return type-checks`() {
            noErrors("""
                @gpu
                fn kernel(x: float)
                    let y = x * 2.0
            """.trimIndent())
        }

        @Test fun `GpuBuffer type resolves correctly`() {
            noErrors("""
                fn process(buf: GpuBuffer<float>) → float
                    → 0.0
            """.trimIndent())
        }

        @Test fun `GpuBuffer element type inferred in for-loop`() {
            noErrors("""
                fn sum_gpu(buf: GpuBuffer<float>) → float
                    var total = 0.0
                    for x in buf
                        total = total + x
                    → total
            """.trimIndent())
        }
    }

    // ── Codegen: @gpu ──────────────────────────────────────────────────────

    @Nested inner class CodegenTests {

        @Test fun `@gpu function emits gpu kernel comment`() {
            val ir = compileIr("""
                @gpu
                fn matmul(a: float, b: float) → float
                    → a * b
            """.trimIndent())
            assertTrue(ir.contains("; @gpu kernel: matmul"), "Expected '; @gpu kernel: matmul' in IR:\n$ir")
        }

        @Test fun `@gpu function still defines function body`() {
            val ir = compileIr("""
                @gpu
                fn kernel(x: float) → float
                    → x
            """.trimIndent())
            assertTrue(ir.contains("define"), "Expected 'define' in IR:\n$ir")
        }

        @Test fun `non-gpu function has no gpu comment`() {
            val ir = compileIr("""
                fn regular(x: float) → float
                    → x
            """.trimIndent())
            assertFalse(ir.contains("; @gpu kernel"), "Did not expect gpu kernel comment:\n$ir")
        }

        @Test fun `GpuBuffer parameter compiles without error`() {
            val ir = compileIr("""
                fn process(buf: GpuBuffer<float>) → float
                    → 0.0
            """.trimIndent())
            assertTrue(ir.contains("define"), "Expected successful compilation:\n$ir")
        }
    }
}
