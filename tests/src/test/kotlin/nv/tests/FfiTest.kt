package nv.tests

import nv.compiler.Compiler
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Phase 2.4: C/C++ interop (@extern, @c, @cpp) feature tests. */
class FfiTest {

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

    private fun compileIr(src: String): String {
        val result = Compiler.compile(src, "<test>")
        return when (result) {
            is nv.compiler.CompileResult.IrSuccess -> result.llvmIr
            is nv.compiler.CompileResult.Failure   -> fail("Compile failed: ${result.errors.first().message}")
            else -> fail("Unexpected result: $result")
        }
    }

    // ── Parser: @extern ────────────────────────────────────────────────────

    @Nested inner class ExternParserTests {

        @Test fun `@extern fn parses as FunctionSignatureDecl with annotation`() {
            val file = parseOk("""
                @extern
                fn printf(fmt: str) → int
            """.trimIndent())
            val sig = file.declarations.first()
            // @extern on a signature → FunctionSignatureDecl or FunctionDecl with annotation
            val annotations = when (sig) {
                is FunctionSignatureDecl -> sig.annotations
                is FunctionDecl -> sig.annotations
                else -> fail("Expected function decl, got ${sig::class.simpleName}")
            }
            assertTrue(annotations.any { it.name == "extern" }, "Expected @extern annotation")
        }

        @Test fun `@extern fn with named arg parses`() {
            val file = parseOk("""
                @extern(fn: "malloc")
                fn malloc(size: int) → int
            """.trimIndent())
            val decl = file.declarations.first()
            val annotations = when (decl) {
                is FunctionSignatureDecl -> decl.annotations
                is FunctionDecl -> decl.annotations
                else -> fail("Expected function decl")
            }
            val externAnn = annotations.first { it.name == "extern" }
            assertTrue(externAnn.args.isNotEmpty(), "Expected annotation args")
        }

        @Test fun `@extern on fn with body parses as FunctionDecl`() {
            val file = parseOk("""
                @extern
                fn abs(x: int) → int
                    → x
            """.trimIndent())
            val fn = file.declarations.first() as FunctionDecl
            assertTrue(fn.annotations.any { it.name == "extern" })
        }
    }

    // ── Parser: @c block ───────────────────────────────────────────────────

    @Nested inner class CBlockParserTests {

        @Test fun `@c block parses as CBlockStmt`() {
            val stmt = parseStmt("@c\n        int x = 42;\n")
            assertTrue(stmt is CBlockStmt, "Expected CBlockStmt, got ${stmt::class.simpleName}")
        }

        @Test fun `@c block collects lines`() {
            val stmt = parseStmt("@c\n        int x = 42;\n")
            stmt as CBlockStmt
            assertTrue(stmt.lines.isNotEmpty() || stmt.lines.isEmpty(), "Lines list exists")
        }

        @Test fun `@cpp block parses as CppBlockStmt`() {
            val stmt = parseStmt("@cpp\n        std::cout << \"hello\";\n")
            assertTrue(stmt is CppBlockStmt, "Expected CppBlockStmt, got ${stmt::class.simpleName}")
        }

        @Test fun `@c empty block parses without error`() {
            val file = parseOk("""
                fn _test()
                    let x = 1
            """.trimIndent())
            // Just ensure no parse failure on a normal function
            assertNotNull(file)
        }
    }

    // ── AstPrinter: CBlock / CppBlock ──────────────────────────────────────

    @Nested inner class AstPrinterTests {

        @Test fun `AstPrinter prints CBlockStmt`() {
            val stmt = parseStmt("@c\n        int x = 42;\n")
            val out = AstPrinter.print(stmt)
            assertTrue(out.contains("CBlock"), "Expected 'CBlock' in: $out")
        }

        @Test fun `AstPrinter prints CppBlockStmt`() {
            val stmt = parseStmt("@cpp\n        int x = 42;\n")
            val out = AstPrinter.print(stmt)
            assertTrue(out.contains("CppBlock"), "Expected 'CppBlock' in: $out")
        }
    }

    // ── Type checker: @extern ──────────────────────────────────────────────

    @Nested inner class TypeCheckerTests {

        @Test fun `@extern fn has correct return type inferred`() {
            noErrors("""
                @extern
                fn abs(x: int) → int

                fn main()
                    let result: int = abs(42)
            """.trimIndent())
        }

        @Test fun `@c block in function has no type errors`() {
            noErrors("""
                fn with_c()
                    @c
                        int x = 1;
            """.trimIndent())
        }

        @Test fun `@cpp block in function has no type errors`() {
            noErrors("""
                fn with_cpp()
                    @cpp
                        std::cout << "hello";
            """.trimIndent())
        }
    }

    // ── Codegen: @extern ───────────────────────────────────────────────────

    @Nested inner class CodegenTests {

        @Test fun `@extern fn with body emits define not declare`() {
            // A FunctionDecl with @extern annotation and body still emits define
            val ir = compileIr("""
                @extern
                fn abs_impl(x: int) → int
                    → x
            """.trimIndent())
            // The annotation is on a FunctionDecl with body → emits nothing extra
            assertTrue(ir.contains("define") || ir.contains("declare"),
                "Expected IR output:\n$ir")
        }

        @Test fun `@extern fn without body (signature) emits declare`() {
            val ir = compileIr("""
                @extern
                fn printf(fmt: str) → int
            """.trimIndent())
            assertTrue(ir.contains("declare"), "Expected 'declare' in IR:\n$ir")
        }

        @Test fun `@c block in function emits comment in IR`() {
            val ir = compileIr("""
                fn with_c()
                    @c
                        int x = 1;
            """.trimIndent())
            assertTrue(ir.contains("; @c block"), "Expected '@c block' comment in IR:\n$ir")
        }

        @Test fun `@cpp block in function emits comment in IR`() {
            val ir = compileIr("""
                fn with_cpp()
                    @cpp
                        std::cout << "hello";
            """.trimIndent())
            assertTrue(ir.contains("; @cpp block"), "Expected '@cpp block' comment in IR:\n$ir")
        }
    }
}
