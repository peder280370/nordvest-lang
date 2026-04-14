package nv.tests

import nv.compiler.Compiler
import nv.compiler.CompileResult
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Tests for the @builder annotation and BuilderCallExpr.
 *
 *  - Parser tests:     verify that `TypeName.build INDENT field = val DEDENT` produces a BuilderCallExpr.
 *  - Type-check tests: verify field validation and missing-required-field errors.
 *  - IR structure:     verify the constructor call is emitted correctly.
 *  - Integration:      compile + run with clang and check stdout.
 */
class BuilderTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parse(src: String): SourceFile {
        val tokens = Lexer(src.trimIndent()).tokenize()
        return when (val result = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> result.file
            is ParseResult.Recovered -> result.file
            is ParseResult.Failure   -> fail("Parse failed: ${result.errors.first().message}")
        }
    }

    private fun compileOk(src: String): String {
        val result = Compiler.compile(src.trimIndent(), "<test>")
        val ir = when (result) {
            is CompileResult.IrSuccess -> result.llvmIr
            else -> null
        }
        assertNotNull(ir, "Expected IR success but got: ${(result as? CompileResult.Failure)?.errors?.map { it.message }}")
        return ir!!
    }

    private fun compileErrors(src: String): List<String> {
        val result = Compiler.compile(src.trimIndent(), "<test>")
        return when (result) {
            is CompileResult.Failure -> result.errors.map { it.message }
            else -> emptyList()
        }
    }

    private fun clangAvailable(): Boolean = try {
        val p = ProcessBuilder("clang", "--version").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }

    private fun runProgram(ir: String): String {
        val tmp = Files.createTempDirectory("nv_builder_").toFile()
        return try {
            val ll  = File(tmp, "out.ll");  ll.writeText(ir)
            val bin = File(tmp, "out")
            val cc  = ProcessBuilder("clang", "-o", bin.absolutePath, ll.absolutePath)
                .redirectErrorStream(true).start()
            val ccOut = cc.inputStream.bufferedReader().readText()
            val ccOk  = cc.waitFor(60, TimeUnit.SECONDS) && cc.exitValue() == 0
            assertTrue(ccOk, "clang failed:\n$ccOut")
            val run = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
            run.waitFor(10, TimeUnit.SECONDS)
            run.inputStream.bufferedReader().readText().trimEnd()
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── Parser tests ──────────────────────────────────────────────────────────

    @Test fun `@builder annotation parsed on class`() {
        val file = parse("""
            module test
            @builder
            class Request(url: str, method: str = "GET")
        """)
        val cls = file.declarations.filterIsInstance<ClassDecl>().first()
        assertTrue(cls.annotations.any { it.name == "builder" }, "Expected @builder annotation")
    }

    @Test fun `TypeName dot build with indented block produces BuilderCallExpr`() {
        val file = parse("""
            module test
            fn main()
                let req = Request.build
                    url = "https://example.com"
                    method = "POST"
        """)
        val fn = file.declarations.filterIsInstance<FunctionDecl>().first()
        val letStmt = fn.body.filterIsInstance<LetStmt>().first()
        assertTrue(letStmt.initializer is BuilderCallExpr,
            "Expected BuilderCallExpr but got ${letStmt.initializer?.javaClass?.simpleName}")
    }

    @Test fun `BuilderCallExpr captures type name and assignments`() {
        val file = parse("""
            module test
            fn main()
                let req = Request.build
                    url = "https://example.com"
                    method = "POST"
        """)
        val fn = file.declarations.filterIsInstance<FunctionDecl>().first()
        val letStmt = fn.body.filterIsInstance<LetStmt>().first()
        val builder = letStmt.initializer as BuilderCallExpr
        assertEquals("Request", builder.typeName)
        assertEquals(2, builder.assignments.size)
        assertEquals("url", builder.assignments[0].first)
        assertEquals("method", builder.assignments[1].first)
    }

    @Test fun `BuilderCallExpr with single field`() {
        val file = parse("""
            module test
            fn main()
                let p = Point.build
                    x = 1
        """)
        val fn = file.declarations.filterIsInstance<FunctionDecl>().first()
        val letStmt = fn.body.filterIsInstance<LetStmt>().first()
        val builder = letStmt.initializer as BuilderCallExpr
        assertEquals("Point", builder.typeName)
        assertEquals(1, builder.assignments.size)
        assertEquals("x", builder.assignments[0].first)
    }

    @Test fun `dot build without indent block is plain member access`() {
        val file = parse("""
            module test
            fn main()
                let x = Foo.build
        """)
        val fn = file.declarations.filterIsInstance<FunctionDecl>().first()
        val letStmt = fn.body.filterIsInstance<LetStmt>().first()
        // Without an indented block, .build is a regular MemberAccessExpr
        assertTrue(letStmt.initializer is MemberAccessExpr,
            "Expected MemberAccessExpr for plain .build without indent block")
    }

    // ── AstPrinter tests ──────────────────────────────────────────────────────

    @Test fun `AstPrinter renders BuilderCallExpr`() {
        val file = parse("""
            module test
            fn main()
                let req = Request.build
                    url = "https://example.com"
        """)
        val printed = AstPrinter.print(file)
        assertTrue(printed.contains("BuilderCall"), "AstPrinter should output BuilderCall")
        assertTrue(printed.contains("Request"), "AstPrinter should include type name")
    }

    // ── Type-check tests ──────────────────────────────────────────────────────

    @Test fun `@builder class compiles to IR`() {
        val ir = compileOk("""
            module test
            @builder
            class Request(url: str, method: str = "GET")
            fn main()
                let req = Request.build
                    url = "https://example.com"
                println("ok")
        """)
        assertTrue(ir.contains("@nv_Request"), "Constructor should be emitted")
    }

    @Test fun `@builder struct compiles to IR`() {
        val ir = compileOk("""
            module test
            @builder
            struct Point(x: int, y: int = 0)
            fn main()
                let p = Point.build
                    x = 10
                println("ok")
        """)
        assertTrue(ir.contains("@nv_Point"), "Constructor should be emitted")
    }

    @Test fun `missing required field emits error`() {
        val errs = compileErrors("""
            module test
            @builder
            class Request(url: str, method: str = "GET")
            fn main()
                let req = Request.build
                    method = "POST"
        """)
        assertTrue(errs.any { "url" in it },
            "Expected missing-field error for 'url' but got: $errs")
    }

    @Test fun `unknown field emits error`() {
        val errs = compileErrors("""
            module test
            @builder
            class Request(url: str)
            fn main()
                let req = Request.build
                    url    = "https://example.com"
                    typo   = "oops"
        """)
        assertTrue(errs.any { "typo" in it },
            "Expected error mentioning unknown field 'typo' but got: $errs")
    }

    @Test fun `all required fields provided — no error`() {
        val ir = compileOk("""
            module test
            @builder
            class Request(url: str, method: str)
            fn main()
                let req = Request.build
                    url    = "https://example.com"
                    method = "POST"
                println("ok")
        """)
        assertNotNull(ir)
    }

    // ── IR structure tests ────────────────────────────────────────────────────

    @Test fun `builder call emits constructor call in IR`() {
        val ir = compileOk("""
            module test
            @builder
            class Request(url: str, method: str = "GET")
            fn main()
                let req = Request.build
                    url = "https://example.com"
                println("ok")
        """)
        assertTrue(ir.contains("call i8* @nv_Request"), "Builder call should emit constructor call")
    }

    @Test fun `builder with only defaults fills all fields`() {
        val ir = compileOk("""
            module test
            @builder
            class Config(host: str = "localhost", port: int = 8080)
            fn main()
                let cfg = Config.build
                    host = "example.com"
                println("ok")
        """)
        assertTrue(ir.contains("call i8* @nv_Config"), "Constructor call should be present")
    }

    @Test fun `builder on struct emits struct constructor call`() {
        val ir = compileOk("""
            module test
            @builder
            struct Vec2(x: float, y: float)
            fn main()
                let v = Vec2.build
                    x = 1.0
                    y = 2.0
                println("ok")
        """)
        assertTrue(ir.contains("call i8* @nv_Vec2"), "Struct builder call should emit constructor")
    }

    // ── Integration tests (require clang) ────────────────────────────────────

    @Test fun `builder produces correct value — class`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @builder
            class Request(url: str, method: str = "GET")
            fn main()
                let req = Request.build
                    url = "https://example.com"
                println(req.url)
                println(req.method)
        """)
        val out = runProgram(ir)
        assertEquals("https://example.com\nGET", out)
    }

    @Test fun `builder produces correct value — override default`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @builder
            class Request(url: str, method: str = "GET")
            fn main()
                let req = Request.build
                    url    = "https://example.com"
                    method = "POST"
                println(req.url)
                println(req.method)
        """)
        val out = runProgram(ir)
        assertEquals("https://example.com\nPOST", out)
    }

    @Test fun `builder produces correct value — struct`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @builder
            struct Point(x: int, y: int = 0)
            fn main()
                let p = Point.build
                    x = 42
                println(p.x)
                println(p.y)
        """)
        val out = runProgram(ir)
        assertEquals("42\n0", out)
    }
}
