package nv.tests

import nv.compiler.codegen.CodegenResult
import nv.compiler.codegen.LlvmIrEmitter
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import nv.compiler.resolve.*
import nv.compiler.typecheck.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CodegenTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun emitIr(src: String): String {
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
        val tcModule = when (val r = TypeChecker(module).check()) {
            is TypeCheckResult.Success   -> r.module
            is TypeCheckResult.Recovered -> r.module
            is TypeCheckResult.Failure   -> fail("TypeCheck failed: ${r.errors.first().message}")
        }
        return when (val r = LlvmIrEmitter(tcModule).emit()) {
            is CodegenResult.Success -> r.llvmIr
            is CodegenResult.Failure -> fail("Codegen failed: ${r.errors.first().message}")
        }
    }

    /** Returns true if clang is available on PATH. */
    private fun clangAvailable(): Boolean {
        return try {
            ProcessBuilder("clang", "--version")
                .redirectErrorStream(true)
                .start()
                .waitFor(5, TimeUnit.SECONDS)
                .let { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Compile LLVM IR to a native binary using clang, run it, and return stdout.
     * Skips the test if clang is not available.
     */
    private fun compileAndRun(@TempDir tempDir: Path, ir: String): String {
        assumeTrue(clangAvailable(), "clang not available — skipping integration test")
        val irFile = tempDir.resolve("test.ll").toFile()
        val binFile = tempDir.resolve("test_bin").toFile()
        irFile.writeText(ir)

        val compile = ProcessBuilder("clang", "-o", binFile.absolutePath, irFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val compileOut = compile.inputStream.bufferedReader().readText()
        val compileExit = compile.waitFor(30, TimeUnit.SECONDS)
        assertTrue(compile.exitValue() == 0, "clang compilation failed:\n$compileOut")

        val run = ProcessBuilder(binFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val stdout = run.inputStream.bufferedReader().readText()
        run.waitFor(10, TimeUnit.SECONDS)
        return stdout
    }

    // ── IR structure tests (no clang needed) ──────────────────────────────

    @Nested
    inner class IrStructure {

        @Test
        fun `emits module header`() {
            val ir = emitIr("fn main()\n    → ()")
            assertTrue(ir.contains("Nordvest bootstrap compiler"), "Should have module header")
        }

        @Test
        fun `emits libc declares`() {
            val ir = emitIr("fn main()\n    → ()")
            assertTrue(ir.contains("@printf"), "Should declare printf")
            assertTrue(ir.contains("@malloc"), "Should declare malloc")
            assertTrue(ir.contains("@exit"), "Should declare exit")
        }

        @Test
        fun `emits runtime functions`() {
            val ir = emitIr("fn main()\n    → ()")
            assertTrue(ir.contains("define void @nv_print("), "Should define nv_print")
            assertTrue(ir.contains("define void @nv_println("), "Should define nv_println")
            assertTrue(ir.contains("define void @nv_panic("), "Should define nv_panic")
            assertTrue(ir.contains("define i8* @nv_str_concat("), "Should define nv_str_concat")
            assertTrue(ir.contains("define i1 @nv_str_eq("), "Should define nv_str_eq")
        }

        @Test
        fun `emits main function with correct signature`() {
            val ir = emitIr("fn main()\n    → ()")
            assertTrue(ir.contains("define i32 @main(i32 %argc, i8** %argv)"), "main should have C signature")
            assertTrue(ir.contains("ret i32 0"), "main should return 0")
        }

        @Test
        fun `emits hello world println call`() {
            val ir = emitIr("""
fn main()
    println("Hello, World!")
""".trimIndent())
            assertTrue(ir.contains("@nv_println"), "Should call nv_println")
            assertTrue(ir.contains("Hello, World!"), "Should have the string constant")
        }

        @Test
        fun `emits integer variable and arithmetic`() {
            val ir = emitIr("""
fn main()
    let x = 42
    let y = x + 8
    println(y)
""".trimIndent())
            assertTrue(ir.contains("alloca i64"), "Should alloca i64 for int variable")
            assertTrue(ir.contains("add i64"), "Should emit add instruction")
            assertTrue(ir.contains("@nv_println_int"), "Should call println_int")
        }

        @Test
        fun `emits float arithmetic`() {
            val ir = emitIr("""
fn main()
    let x = 3.14
    let y = x * 2.0
    println(y)
""".trimIndent())
            assertTrue(ir.contains("alloca double"), "Should alloca double for float variable")
            assertTrue(ir.contains("fmul double"), "Should emit fmul instruction")
            assertTrue(ir.contains("@nv_println_float"), "Should call println_float")
        }

        @Test
        fun `emits boolean variable`() {
            val ir = emitIr("""
fn main()
    let b = true
    println(b)
""".trimIndent())
            assertTrue(ir.contains("alloca i1"), "Should alloca i1 for bool variable")
            assertTrue(ir.contains("@nv_println_bool"), "Should call println_bool")
        }

        @Test
        fun `emits if-else`() {
            val ir = emitIr("""
fn main()
    let x = 10
    if x > 5
        println("big")
    else
        println("small")
""".trimIndent())
            assertTrue(ir.contains("icmp sgt"), "Should emit sgt comparison")
            assertTrue(ir.contains("if.then"), "Should have then branch label")
            assertTrue(ir.contains("if.merge"), "Should have merge label")
        }

        @Test
        fun `emits while loop`() {
            val ir = emitIr("""
fn main()
    var i = 0
    while i < 10
        i += 1
""".trimIndent())
            assertTrue(ir.contains("while.cond"), "Should have while.cond label")
            assertTrue(ir.contains("while.body"), "Should have while.body label")
            assertTrue(ir.contains("while.end"), "Should have while.end label")
            assertTrue(ir.contains("icmp slt"), "Should emit slt comparison")
        }

        @Test
        fun `emits for range loop`() {
            val ir = emitIr("""
fn main()
    for i in [0, 10[
        println(i)
""".trimIndent())
            // Note: [0, 10[ is currently parsed as ArrayLiteralExpr by the Phase 1.3 parser;
            // full range-in-for-loop support is tracked for Phase 1.6. The emitter still
            // generates a valid (if vacuous) loop structure.
            assertTrue(ir.contains("for.cond"), "Should have for.cond label")
            assertTrue(ir.contains("for.body"), "Should have for.body label")
        }

        @Test
        fun `emits user-defined function`() {
            val ir = emitIr("""
fn square(x: int) → int
    → x * x

fn main()
    let r = square(5)
    println(r)
""".trimIndent())
            assertTrue(ir.contains("define i64 @nv_square("), "Should emit user function")
            assertTrue(ir.contains("mul i64"), "Should emit mul in square")
            assertTrue(ir.contains("call i64 @nv_square("), "Should call square")
        }

        @Test
        fun `emits string interpolation`() {
            val ir = emitIr("""
fn main()
    let name = "World"
    println("Hello, {name}!")
""".trimIndent())
            assertTrue(ir.contains("@nv_str_concat"), "Should concat parts of interpolated string")
        }

        @Test
        fun `emits power operator`() {
            val ir = emitIr("""
fn main()
    let x = 2.0 ^ 10.0
    println(x)
""".trimIndent())
            assertTrue(ir.contains("call double @pow("), "Should call pow for ^ operator")
        }

        @Test
        fun `emits integer division`() {
            val ir = emitIr("""
fn main()
    let x = 10 ÷ 3
    println(x)
""".trimIndent())
            assertTrue(ir.contains("sdiv i64"), "Should emit sdiv for ÷ operator")
        }

        @Test
        fun `emits match statement with literals`() {
            val ir = emitIr("""
fn main()
    let x = 2
    match x
        1: println("one")
        2: println("two")
        _: println("other")
""".trimIndent())
            assertTrue(ir.contains("match.arm"), "Should have match.arm labels")
            assertTrue(ir.contains("icmp eq"), "Should emit equality checks")
        }

        @Test
        fun `emits inline if expression`() {
            val ir = emitIr("""
fn main()
    let x = 5
    let msg = if x > 0 then "positive" else "non-positive"
    println(msg)
""".trimIndent())
            assertTrue(ir.contains("iif.then"), "Should have inline-if then label")
            assertTrue(ir.contains("phi i8*"), "Should use phi for inline-if result")
        }

        @Test
        fun `emits short-circuit and`() {
            val ir = emitIr("""
fn main()
    let a = true
    let b = false
    let c = a && b
    println(c)
""".trimIndent())
            assertTrue(ir.contains("and.rhs"), "Should have and.rhs label for short-circuit")
        }

        @Test
        fun `emits string equality`() {
            val ir = emitIr("""
fn main()
    let s = "hello"
    if s == "hello"
        println("yes")
""".trimIndent())
            assertTrue(ir.contains("@nv_str_eq"), "Should call nv_str_eq for string equality")
        }

        @Test
        fun `emits return statement`() {
            val ir = emitIr("""
fn abs(x: int) → int
    if x < 0
        → -x
    → x

fn main()
    println(abs(-5))
""".trimIndent())
            assertTrue(ir.contains("ret i64"), "Should emit ret i64 in abs")
        }

        @Test
        fun `format strings are present`() {
            val ir = emitIr("fn main()\n    → ()")
            assertTrue(ir.contains("@.fmt.s"), "Should have %s format string")
            assertTrue(ir.contains("@.fmt.d"), "Should have %ld format string")
            assertTrue(ir.contains("@.str.true"), "Should have true constant")
            assertTrue(ir.contains("@.str.false"), "Should have false constant")
        }
    }

    // ── Integration tests (require clang) ─────────────────────────────────

    @Nested
    inner class Integration {

        @Test
        fun `hello world compiles and runs`(@TempDir tempDir: Path) {
            val ir = emitIr("""
fn main()
    println("Hello, World!")
""".trimIndent())
            val output = compileAndRun(tempDir, ir)
            assertEquals("Hello, World!\n", output)
        }

        @Test
        fun `integer arithmetic produces correct output`(@TempDir tempDir: Path) {
            val ir = emitIr("""
fn main()
    let x = 3
    let y = 4
    let z = x + y
    println(z)
""".trimIndent())
            val output = compileAndRun(tempDir, ir)
            assertEquals("7\n", output)
        }

        @Test
        fun `if-else selects correct branch`(@TempDir tempDir: Path) {
            val ir = emitIr("""
fn main()
    let x = 10
    if x > 5
        println("big")
    else
        println("small")
""".trimIndent())
            val output = compileAndRun(tempDir, ir)
            assertEquals("big\n", output)
        }

        @Test
        fun `while loop accumulates correctly`(@TempDir tempDir: Path) {
            // Note: range-in-for ([1, 4[) is a Phase 1.6 parser feature; use while loop instead
            val ir = emitIr("""
fn main()
    var sum = 0
    var i = 1
    while i < 4
        sum += i
        i += 1
    println(sum)
""".trimIndent())
            val output = compileAndRun(tempDir, ir)
            assertEquals("6\n", output)  // 1+2+3 = 6
        }

        @Test
        fun `user-defined function returns correct value`(@TempDir tempDir: Path) {
            val ir = emitIr("""
fn double(x: int) → int
    → x * 2

fn main()
    println(double(21))
""".trimIndent())
            val output = compileAndRun(tempDir, ir)
            assertEquals("42\n", output)
        }

        @Test
        fun `bool printing works`(@TempDir tempDir: Path) {
            val ir = emitIr("""
fn main()
    println(true)
    println(false)
""".trimIndent())
            val output = compileAndRun(tempDir, ir)
            assertEquals("true\nfalse\n", output)
        }
    }
}
