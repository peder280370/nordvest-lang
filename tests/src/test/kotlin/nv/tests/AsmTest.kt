package nv.tests

import nv.compiler.Compiler
import nv.compiler.codegen.detectHostArch
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Phase 2.2: inline assembly (@asm) and raw bytes (@bytes) feature tests. */
class AsmTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun parseOk(src: String): SourceFile {
        val tokens = Lexer(src).tokenize()
        return when (val result = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> result.file
            is ParseResult.Recovered -> result.file
            is ParseResult.Failure   -> fail("Parse failed: ${result.errors.first().message}")
        }
    }

    private fun parseStmt(src: String): Stmt {
        val wrapped = "fn _t()\n    $src\n"
        val file = parseOk(wrapped)
        val fn = file.declarations.first() as FunctionDecl
        return fn.body.first()
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

    private fun compileIr(src: String, targetArch: String): String {
        val result = Compiler.compile(src, "<test>", targetArch)
        return when (result) {
            is nv.compiler.CompileResult.IrSuccess -> result.llvmIr
            is nv.compiler.CompileResult.Failure   -> fail("Compile failed: ${result.errors.first().message}")
            else -> fail("Unexpected result: $result")
        }
    }

    // ── Parser: @asm[arch] ─────────────────────────────────────────────────

    @Nested inner class ParserAsmTests {

        @Test fun `parse basic @asm arm64 block`() {
            val stmt = parseStmt("@asm[arm64]\n        \"nop\"\n")
            assertTrue(stmt is AsmStmt, "Expected AsmStmt, got ${stmt::class.simpleName}")
            stmt as AsmStmt
            assertEquals("arm64", stmt.arch)
            assertTrue(stmt.features.isEmpty())
            assertEquals(listOf("nop"), stmt.instructions)
            assertTrue(stmt.clobbers.isEmpty())
        }

        @Test fun `parse @asm x86_64 block`() {
            val stmt = parseStmt("@asm[x86_64]\n        \"nop\"\n")
            stmt as AsmStmt
            assertEquals("x86_64", stmt.arch)
            assertEquals(listOf("nop"), stmt.instructions)
        }

        @Test fun `parse @asm with feature flag`() {
            val stmt = parseStmt("@asm[x86_64, avx2]\n        \"vpaddq %ymm0, %ymm1, %ymm0\"\n")
            stmt as AsmStmt
            assertEquals("x86_64", stmt.arch)
            assertEquals(listOf("avx2"), stmt.features)
            assertEquals(listOf("vpaddq %ymm0, %ymm1, %ymm0"), stmt.instructions)
        }

        @Test fun `parse @asm with multiple features`() {
            val stmt = parseStmt("@asm[x86_64, avx2, bmi2]\n        \"nop\"\n")
            stmt as AsmStmt
            assertEquals(listOf("avx2", "bmi2"), stmt.features)
        }

        @Test fun `parse @asm with multiple instructions`() {
            val stmt = parseStmt("""
                @asm[arm64]
                        "mov x0, #1"
                        "mov x1, #2"
                        "add x0, x0, x1"
            """.trimIndent() + "\n")
            stmt as AsmStmt
            assertEquals(listOf("mov x0, #1", "mov x1, #2", "add x0, x0, x1"), stmt.instructions)
        }

        @Test fun `parse @asm with clobbers`() {
            val stmt = parseStmt("""
                @asm[x86_64]
                        "cpuid"
                        clobbers: ["rbx", "rcx", "rdx"]
            """.trimIndent() + "\n")
            stmt as AsmStmt
            assertEquals(listOf("cpuid"), stmt.instructions)
            assertEquals(listOf("rbx", "rcx", "rdx"), stmt.clobbers)
        }

        @Test fun `@asm in function with fallback body parses both`() {
            val src = """
                fn nop() → int
                    @asm[arm64]
                        "nop"
                    → 0
            """.trimIndent()
            val file = parseOk(src)
            val fn = file.declarations.first() as FunctionDecl
            assertEquals(2, fn.body.size)
            assertTrue(fn.body[0] is AsmStmt)
            assertTrue(fn.body[1] is ReturnStmt)
        }

        @Test fun `multiple @asm blocks for different arches`() {
            val src = """
                fn nop() → unit
                    @asm[arm64]
                        "nop"
                    @asm[x86_64]
                        "nop"
            """.trimIndent()
            val file = parseOk(src)
            val fn = file.declarations.first() as FunctionDecl
            assertEquals(2, fn.body.size)
            assertEquals("arm64",  (fn.body[0] as AsmStmt).arch)
            assertEquals("x86_64", (fn.body[1] as AsmStmt).arch)
        }
    }

    // ── Parser: @bytes[arch] ───────────────────────────────────────────────

    @Nested inner class ParserBytesTests {

        @Test fun `parse basic @bytes block`() {
            val stmt = parseStmt("@bytes[x86_64]\n        0x90\n")
            assertTrue(stmt is BytesStmt)
            stmt as BytesStmt
            assertEquals("x86_64", stmt.arch)
            assertEquals(listOf(0x90), stmt.bytes)
        }

        @Test fun `parse @bytes with multiple bytes on one line`() {
            val stmt = parseStmt("@bytes[x86_64]\n        0x90, 0x90, 0x90\n")
            stmt as BytesStmt
            assertEquals(listOf(0x90, 0x90, 0x90), stmt.bytes)
        }

        @Test fun `parse @bytes arm64`() {
            val stmt = parseStmt("@bytes[arm64]\n        0x1f, 0x20, 0x03, 0xd5\n")
            stmt as BytesStmt
            assertEquals("arm64", stmt.arch)
            assertEquals(listOf(0x1f, 0x20, 0x03, 0xd5), stmt.bytes)
        }
    }

    // ── AstPrinter ──────────────────────────────────────────────────────────

    @Nested inner class AstPrinterTests {

        @Test fun `AstPrinter prints AsmStmt`() {
            val stmt = parseStmt("@asm[arm64]\n        \"nop\"\n")
            val out = AstPrinter.print(stmt)
            assertTrue(out.contains("Asm"), "Expected 'Asm' in: $out")
            assertTrue(out.contains("arm64"), "Expected 'arm64' in: $out")
            assertTrue(out.contains("nop"), "Expected 'nop' in: $out")
        }

        @Test fun `AstPrinter prints AsmStmt with features`() {
            val stmt = parseStmt("@asm[x86_64, avx2]\n        \"nop\"\n")
            val out = AstPrinter.print(stmt)
            assertTrue(out.contains("features=[avx2]"), "Expected features=[avx2] in: $out")
        }

        @Test fun `AstPrinter prints AsmStmt with clobbers`() {
            val stmt = parseStmt("@asm[x86_64]\n        \"cpuid\"\n        clobbers: [\"rbx\"]\n")
            val out = AstPrinter.print(stmt)
            assertTrue(out.contains("clobbers=[rbx]"), "Expected clobbers=[rbx] in: $out")
        }

        @Test fun `AstPrinter prints BytesStmt`() {
            val stmt = parseStmt("@bytes[x86_64]\n        0x90\n")
            val out = AstPrinter.print(stmt)
            assertTrue(out.contains("Bytes"), "Expected 'Bytes' in: $out")
            assertTrue(out.contains("x86_64"), "Expected 'x86_64' in: $out")
            assertTrue(out.contains("0x90"), "Expected '0x90' in: $out")
        }
    }

    // ── Type checker ────────────────────────────────────────────────────────

    @Nested inner class TypeCheckerTests {

        @Test fun `asm stmt in function has no type errors`() {
            noErrors("""
                fn nop() → unit
                    @asm[arm64]
                        "nop"
            """.trimIndent())
        }

        @Test fun `asm stmt with fallback has no type errors`() {
            noErrors("""
                fn add(a: int, b: int) → int
                    @asm[arm64]
                        "add x0, x0, x1"
                    → a + b
            """.trimIndent())
        }

        @Test fun `bytes stmt in function has no type errors`() {
            noErrors("""
                fn nop_sled() → unit
                    @bytes[x86_64]
                        0x90, 0x90, 0x90
            """.trimIndent())
        }

        @Test fun `multiple arch-specific asm blocks compile cleanly`() {
            noErrors("""
                fn nop() → unit
                    @asm[arm64]
                        "nop"
                    @asm[x86_64]
                        "nop"
            """.trimIndent())
        }
    }

    // ── Codegen: arch-selection ─────────────────────────────────────────────

    @Nested inner class CodegenTests {

        @Test fun `asm block for matching arch emits inline asm`() {
            val ir = compileIr("""
                fn nop() → unit
                    @asm[arm64]
                        "nop"
            """.trimIndent(), targetArch = "arm64")
            assertTrue(ir.contains("asm sideeffect"), "Expected 'asm sideeffect' in IR:\n$ir")
            assertTrue(ir.contains("nop"), "Expected 'nop' in IR:\n$ir")
        }

        @Test fun `asm block for non-matching arch is skipped`() {
            val ir = compileIr("""
                fn nop() → unit
                    @asm[x86_64]
                        "nop"
            """.trimIndent(), targetArch = "arm64")
            assertFalse(ir.contains("asm sideeffect"), "Expected NO asm in IR for non-matching arch:\n$ir")
        }

        @Test fun `x86_64 asm block is emitted on x86_64 target`() {
            val ir = compileIr("""
                fn nop() → unit
                    @asm[x86_64]
                        "nop"
            """.trimIndent(), targetArch = "x86_64")
            assertTrue(ir.contains("asm sideeffect"), "Expected 'asm sideeffect' in IR:\n$ir")
        }

        @Test fun `multiple instructions are joined in IR`() {
            val ir = compileIr("""
                fn add_tweak() → unit
                    @asm[arm64]
                        "mov x0, #1"
                        "mov x1, #2"
            """.trimIndent(), targetArch = "arm64")
            assertTrue(ir.contains("mov x0, #1"), "Expected first instruction in IR:\n$ir")
            assertTrue(ir.contains("mov x1, #2"), "Expected second instruction in IR:\n$ir")
        }

        @Test fun `clobbers are emitted as LLVM constraint string`() {
            val ir = compileIr("""
                fn do_cpuid() → unit
                    @asm[x86_64]
                        "cpuid"
                        clobbers: ["rbx", "rcx", "rdx"]
            """.trimIndent(), targetArch = "x86_64")
            assertTrue(ir.contains("~{rbx}"), "Expected ~{rbx} in IR:\n$ir")
            assertTrue(ir.contains("~{rcx}"), "Expected ~{rcx} in IR:\n$ir")
        }

        @Test fun `bytes stmt for matching arch emits dot-byte directive`() {
            val ir = compileIr("""
                fn nop_sled() → unit
                    @bytes[x86_64]
                        0x90, 0x90, 0x90
            """.trimIndent(), targetArch = "x86_64")
            assertTrue(ir.contains(".byte"), "Expected '.byte' in IR:\n$ir")
            assertTrue(ir.contains("0x90"), "Expected '0x90' in IR:\n$ir")
        }

        @Test fun `bytes stmt for non-matching arch is skipped`() {
            val ir = compileIr("""
                fn nop_sled() → unit
                    @bytes[x86_64]
                        0x90, 0x90, 0x90
            """.trimIndent(), targetArch = "arm64")
            assertFalse(ir.contains(".byte"), "Expected NO .byte in IR for non-matching arch:\n$ir")
        }

        @Test fun `asm fallback body is emitted when arch does not match`() {
            // The pure-Nordvest return stmt should still be emitted regardless
            val ir = compileIr("""
                fn get_val() → int
                    @asm[x86_64]
                        "mov rax, 42"
                    → 42
            """.trimIndent(), targetArch = "arm64")
            // Should have a ret i64 with the constant 42 (or equivalent)
            assertTrue(ir.contains("ret"), "Expected 'ret' in fallback IR:\n$ir")
        }

        @Test fun `detectHostArch returns a non-empty string`() {
            val arch = detectHostArch()
            assertTrue(arch.isNotEmpty(), "detectHostArch() returned empty string")
        }

        @Test fun `compile with default arch succeeds`() {
            val result = Compiler.compile("""
                fn noop() → int
                    → 0
            """.trimIndent(), "<test>")
            assertTrue(result is nv.compiler.CompileResult.IrSuccess)
        }
    }
}
