package nv.tests

import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Phase 2.1: concurrency feature tests — async/await, spawn, go, select, channels. */
class ConcurrencyTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun parseOk(src: String): SourceFile {
        val tokens = Lexer(src).tokenize()
        return when (val result = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> result.file
            is ParseResult.Recovered -> result.file
            is ParseResult.Failure   -> fail("Parse failed: ${result.errors.first().message}")
        }
    }

    private fun parseDecl(src: String): Decl {
        val file = parseOk(src)
        return file.declarations.first()
    }

    private fun parseStmt(src: String): Stmt {
        val wrapped = "fn _t()\n    $src\n"
        val file = parseOk(wrapped)
        val fn = file.declarations.first() as FunctionDecl
        return fn.body.first()
    }

    private fun parseExpr(src: String): Expr {
        val wrapped = "fn _t()\n    → $src\n"
        val file = parseOk(wrapped)
        val fn = file.declarations.first() as FunctionDecl
        val ret = fn.body.first() as ReturnStmt
        return ret.value!!
    }

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

    // ── Parser: async fn ───────────────────────────────────────────────────

    @Nested
    inner class AsyncFnParsing {

        @Test fun `async fn declaration is parsed`() {
            val decl = parseDecl("async fn fetchData(url: str) → str\n    → url\n")
            assertTrue(decl is FunctionDecl, "Expected FunctionDecl")
            val fn = decl as FunctionDecl
            assertEquals("fetchData", fn.name)
            assertTrue(fn.isAsync, "Expected isAsync = true")
        }

        @Test fun `regular fn has isAsync = false`() {
            val decl = parseDecl("fn regularFn() → int\n    → 42\n")
            assertTrue(decl is FunctionDecl)
            assertFalse((decl as FunctionDecl).isAsync)
        }

        @Test fun `async fn with no return type`() {
            val decl = parseDecl("async fn doWork()\n    → 0\n")
            assertTrue(decl is FunctionDecl)
            assertTrue((decl as FunctionDecl).isAsync)
        }

        @Test fun `pub async fn declaration`() {
            val decl = parseDecl("pub async fn getData() → int\n    → 1\n")
            assertTrue(decl is FunctionDecl)
            val fn = decl as FunctionDecl
            assertTrue(fn.isAsync)
            assertEquals(Visibility.PUBLIC, fn.visibility)
        }
    }

    // ── Parser: await expression ───────────────────────────────────────────

    @Nested
    inner class AwaitParsing {

        @Test fun `await expr in return`() {
            val expr = parseExpr("await fetchData()")
            assertTrue(expr is AwaitExpr, "Expected AwaitExpr but got ${expr::class.simpleName}")
            val ae = expr as AwaitExpr
            assertTrue(ae.operand is CallExpr)
        }

        @Test fun `await expr wrapping identifier`() {
            val expr = parseExpr("await future")
            assertTrue(expr is AwaitExpr)
            val ae = expr as AwaitExpr
            assertTrue(ae.operand is IdentExpr)
            assertEquals("future", (ae.operand as IdentExpr).name)
        }
    }

    // ── Parser: spawn expression ───────────────────────────────────────────

    @Nested
    inner class SpawnParsing {

        @Test fun `spawn expr in let binding`() {
            val stmt = parseStmt("let f = spawn compute(42)\n")
            assertTrue(stmt is LetStmt)
            val ls = stmt as LetStmt
            assertTrue(ls.initializer is SpawnExpr, "Expected SpawnExpr but got ${ls.initializer?.let { it::class.simpleName }}")
        }

        @Test fun `spawn stmt at statement level`() {
            val stmt = parseStmt("spawn compute()\n")
            // Statement-level spawn is still a SpawnStmt
            assertTrue(stmt is SpawnStmt || stmt is ExprStmt,
                "Expected SpawnStmt or ExprStmt but got ${stmt::class.simpleName}")
        }
    }

    // ── Parser: go statement ───────────────────────────────────────────────

    @Nested
    inner class GoParsing {

        @Test fun `go expr statement`() {
            val stmt = parseStmt("go worker()\n")
            assertTrue(stmt is GoStmt, "Expected GoStmt but got ${stmt::class.simpleName}")
        }

        @Test fun `go block statement`() {
            // go block syntax uses braces: go { ... }
            val wrapped = "fn _t()\n    go {\n        println(\"hello\")\n    }\n"
            val file = parseOk(wrapped)
            val fn = file.declarations.first() as FunctionDecl
            val stmt = fn.body.first()
            assertTrue(stmt is GoStmt, "Expected GoStmt but got ${stmt::class.simpleName}")
            val gs = stmt as GoStmt
            assertTrue(gs.body is GoBlockBody, "Expected GoBlockBody but got ${gs.body::class.simpleName}")
        }
    }

    // ── Parser: select statement ───────────────────────────────────────────

    @Nested
    inner class SelectParsing {

        @Test fun `select with default arm`() {
            val wrapped = "fn _t()\n    select\n        default:\n            println(\"hi\")\n"
            val file = parseOk(wrapped)
            val fn = file.declarations.first() as FunctionDecl
            val stmt = fn.body.first()
            assertTrue(stmt is SelectStmt)
            val ss = stmt as SelectStmt
            assertTrue(ss.arms.any { it is DefaultSelectArm })
        }
    }

    // ── AstPrinter: async fn marker ────────────────────────────────────────

    @Nested
    inner class AstPrinterAsync {

        @Test fun `async fn prints with async marker`() {
            val decl = parseDecl("async fn doThing() → int\n    → 0\n")
            val printed = AstPrinter.print(decl)
            assertTrue(printed.contains("(async)"), "Expected '(async)' in printed AST: $printed")
        }

        @Test fun `non-async fn has no async marker`() {
            val decl = parseDecl("fn doThing() → int\n    → 0\n")
            val printed = AstPrinter.print(decl)
            assertFalse(printed.contains("(async)"), "Did not expect '(async)' in: $printed")
        }

        @Test fun `await expr prints as Await node`() {
            val expr = parseExpr("await myFuture")
            val printed = AstPrinter.printInline(expr)
            assertTrue(printed.startsWith("(Await"), "Expected '(Await ...' but got: $printed")
        }

        @Test fun `spawn expr prints as SpawnExpr node`() {
            val stmt = parseStmt("let f = spawn compute()\n")
            val ls = stmt as LetStmt
            val printed = AstPrinter.printInline(ls.initializer!!)
            assertTrue(printed.startsWith("(SpawnExpr"), "Expected '(SpawnExpr ...' but got: $printed")
        }
    }

    // ── Type checker: await/async rules ────────────────────────────────────

    @Nested
    inner class TypeCheckerAsync {

        @Test fun `await inside async fn does not produce AwaitOutsideAsync error`() {
            // We only check that AwaitOutsideAsync is NOT raised; TypeMismatch from
            // awaiting a non-Future return type is a stricter check not enforced in 2.1 bootstrap.
            val errs = typeErrors("""
async fn doWork() → int
    let f = spawn doWork()
    → await f
""".trimIndent())
            assertFalse(errs.any { it is TypeCheckError.AwaitOutsideAsync },
                "Expected no AwaitOutsideAsync error inside async fn")
        }

        @Test fun `await outside async fn produces error`() {
            val errs = typeErrors("""
fn notAsync() → int
    → await someCall()
""".trimIndent())
            assertTrue(errs.any { it is TypeCheckError.AwaitOutsideAsync },
                "Expected AwaitOutsideAsync error but got: ${errs.map { it::class.simpleName }}")
        }

        @Test fun `spawn expr produces Future type`() {
            val m = typeCheck("""
fn compute() → int
    → 42

fn main()
    let f = spawn compute()
""".trimIndent())
            // The type of the SpawnExpr should be Future<int> or Future<unknown>
            val futureTypes = m.typeMap.values.filterIsInstance<Type.TFuture>()
            assertTrue(futureTypes.isNotEmpty(), "Expected at least one TFuture in type map, got: ${m.typeMap.values.map { it::class.simpleName }}")
        }

        @Test fun `async fn with explicit return type is clean`() {
            noErrors("""
async fn doNothing() → int
    → 0
""".trimIndent())
        }
    }

    // ── Type system: TFuture and TChannel ─────────────────────────────────

    @Nested
    inner class TypeSystem {

        @Test fun `TFuture display`() {
            assertEquals("Future<int>", Type.TFuture(Type.TInt).display())
        }

        @Test fun `TChannel display`() {
            assertEquals("Channel<str>", Type.TChannel(Type.TStr).display())
        }

        @Test fun `TFuture is assignable to itself`() {
            assertTrue(isAssignable(Type.TFuture(Type.TInt), Type.TFuture(Type.TInt)))
        }

        @Test fun `TChannel is assignable to itself`() {
            assertTrue(isAssignable(Type.TChannel(Type.TStr), Type.TChannel(Type.TStr)))
        }

        @Test fun `TFuture with TUnknown is assignable`() {
            // TUnknown suppresses errors
            assertTrue(isAssignable(Type.TFuture(Type.TUnknown), Type.TFuture(Type.TInt)))
        }
    }
}
