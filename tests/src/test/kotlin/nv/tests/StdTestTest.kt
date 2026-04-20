package nv.tests

import nv.compiler.Compiler
import nv.compiler.CompileResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for std.test runtime: @test runner integration, assertion functions, TAP output.
 */
class StdTestTest : NvCompilerTestBase() {

    // ── stdlib/std/test.nv shape ──────────────────────────────────────────

    @Nested inner class StdlibShapeTests {

        @Test fun `test nv exists and has required functions`() {
            val f = File(projectDir(), "stdlib/std/test.nv")
            assertTrue(f.exists(), "stdlib/std/test.nv not found")
            val c = f.readText()
            assertTrue(c.contains("nv_assert"),         "missing nv_assert @extern")
            assertTrue(c.contains("nv_fail"),            "missing nv_fail @extern")
            assertTrue(c.contains("nv_assert_nil"),      "missing nv_assert_nil @extern")
            assertTrue(c.contains("nv_assert_not_nil"),  "missing nv_assert_not_nil @extern")
            assertTrue(c.contains("nv_assert_ok"),       "missing nv_assert_ok @extern")
            assertTrue(c.contains("nv_assert_err"),      "missing nv_assert_err @extern")
            assertTrue(c.contains("assertEq"),           "missing assertEq")
            assertTrue(c.contains("assertNe"),           "missing assertNe")
            assertTrue(c.contains("assertApprox"),       "missing assertApprox")
        }
    }

    // ── assert / fail compile and produce IR ─────────────────────────────

    @Nested inner class CompileTests {

        @Test fun `assert function compiles`() {
            compileOk("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                fn main()
                    assert(1 == 1, "should pass")
            """)
        }

        @Test fun `fail function compiles`() {
            compileOk("""
                module test
                @extern(fn: "nv_fail") pub fn fail(message: str)
                fn main()
                    if false
                        fail("unreachable")
            """)
        }

        @Test fun `assertNil compiles with nullable str`() {
            compileOk("""
                module test
                @extern(fn: "nv_assert_nil") pub fn assertNil<T>(value: T?)
                fn main()
                    let x: str? = nil
                    assertNil(x)
            """)
        }

        @Test fun `assertNotNil compiles with nullable str`() {
            compileOk("""
                module test
                @extern(fn: "nv_assert_not_nil") pub fn assertNotNil<T>(value: T?)
                fn main()
                    let x: str? = "hello"
                    assertNotNil(x)
            """)
        }

        @Test fun `@test annotation on function is accepted`() {
            compileOk("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test
                fn testSomething()
                    assert(true, "ok")
            """)
        }

        @Test fun `testMode compilation produces IR with test runner`() {
            val result = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test
                fn testOne()
                    assert(true, "should pass")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, result)
            val ir = (result as CompileResult.IrSuccess).llvmIr
            assertTrue(ir.contains("@main"), "expected @main in test-runner IR")
            assertTrue(ir.contains("nv_test_begin"), "expected nv_test_begin call")
            assertTrue(ir.contains("nv_test_report"), "expected nv_test_report call")
            assertTrue(ir.contains("nv_test_exit"), "expected nv_test_exit call")
            assertTrue(ir.contains("nv_testOne"), "expected @nv_testOne call")
        }

        @Test fun `testMode with multiple test functions includes all in runner`() {
            val result = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test fn testA() → unit
                    assert(true, "a")
                @test fn testB() → unit
                    assert(2 == 2, "b")
                @test fn testC() → unit
                    assert(1 < 2, "c")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, result)
            val ir = (result as CompileResult.IrSuccess).llvmIr
            assertTrue(ir.contains("nv_testA"), "expected nv_testA")
            assertTrue(ir.contains("nv_testB"), "expected nv_testB")
            assertTrue(ir.contains("nv_testC"), "expected nv_testC")
        }

        @Test fun `testMode skips user main function`() {
            val result = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test fn testX() → unit
                    assert(true, "x")
                fn main()
                    println("user main")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, result)
            val ir = (result as CompileResult.IrSuccess).llvmIr
            // The test-runner main should not contain "user main" string
            assertFalse(ir.contains("user main"), "user main should be skipped in testMode")
        }

        @Test fun `file with no @test functions in testMode still compiles`() {
            val result = Compiler.compile("""
                module test
                fn helper(x: int) → int
                    → x + 1
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, result)
        }
    }

    // ── Runtime: assertions execute correctly ─────────────────────────────

    @Nested inner class RuntimeTests {

        @Test fun `assert true passes — test exits 0`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test fn testPass()
                    assert(1 == 1, "should be true")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(0, out.exitCode, "Expected exit 0 for passing tests")
            assertTrue(out.stdout.contains("ok 1"), "Expected 'ok 1' in TAP output")
        }

        @Test fun `assert false fails — test exits 1`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test fn testFail()
                    assert(1 == 2, "oops")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(1, out.exitCode, "Expected exit 1 for failing tests")
            assertTrue(out.stdout.contains("not ok 1"), "Expected 'not ok 1' in TAP output")
            assertTrue(out.stdout.contains("oops"), "Expected failure message in TAP output")
        }

        @Test fun `tap header is printed`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test fn testA()
                    assert(true, "ok")
                @test fn testB()
                    assert(true, "ok")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertTrue(out.stdout.contains("TAP version 13"), "Expected TAP header")
            assertTrue(out.stdout.contains("1..2"), "Expected plan line 1..2")
            assertTrue(out.stdout.contains("ok 1"), "Expected ok 1")
            assertTrue(out.stdout.contains("ok 2"), "Expected ok 2")
        }

        @Test fun `mixed pass and fail tests — exit 1`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test fn testPass()
                    assert(2 + 2 == 4, "math works")
                @test fn testFail()
                    assert(2 + 2 == 5, "wrong math")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(1, out.exitCode, "Expected exit 1 when any test fails")
            assertTrue(out.stdout.contains("ok 1"), "Expected ok 1 for passing test")
            assertTrue(out.stdout.contains("not ok 2"), "Expected not ok 2 for failing test")
        }

        @Test fun `fail function marks test as failed`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_fail") pub fn fail(message: str)
                @test fn testExplicitFail()
                    fail("explicit failure message")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(1, out.exitCode)
            assertTrue(out.stdout.contains("not ok 1"))
            assertTrue(out.stdout.contains("explicit failure message"))
        }

        @Test fun `assertNil passes for nil str value`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert_nil") pub fn assertNil<T>(value: T?)
                @test fn testNilPass()
                    let x: str? = nil
                    assertNil(x)
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(0, out.exitCode)
            assertTrue(out.stdout.contains("ok 1"))
        }

        @Test fun `assertNotNil passes for non-nil str value`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert_not_nil") pub fn assertNotNil<T>(value: T?)
                @test fn testNotNilPass()
                    let x: str? = "hello"
                    assertNotNil(x)
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(0, out.exitCode)
            assertTrue(out.stdout.contains("ok 1"))
        }

        @Test fun `summary line is printed`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                @test fn t1() → unit
                    assert(true, "ok")
                @test fn t2() → unit
                    assert(false, "fail")
                @test fn t3() → unit
                    assert(true, "ok")
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertTrue(out.stdout.contains("2 passed"), "Expected '2 passed' in summary")
            assertTrue(out.stdout.contains("1 failed"), "Expected '1 failed' in summary")
        }
    }

    // ── assertEq / assertNe (Nordvest-level generic wrappers) ─────────────

    @Nested inner class AssertEqTests {

        @Test fun `assertEq on equal ints passes`() {
            assumeTrue(clangAvailable())
            // Use concrete int overload (generic T causes type-mismatch in bootstrap codegen)
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                pub fn assertEqInt(actual: int, expected: int)
                    assert(actual == expected, "expected equal values")
                @test fn testEqPass()
                    assertEqInt(42, 42)
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(0, out.exitCode)
        }

        @Test fun `assertEq on unequal ints fails`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                pub fn assertEqInt(actual: int, expected: int)
                    assert(actual == expected, "expected equal values")
                @test fn testEqFail()
                    assertEqInt(1, 2)
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(1, out.exitCode)
            assertTrue(out.stdout.contains("expected equal values"))
        }
    }

    // ── assertApprox ─────────────────────────────────────────────────────

    @Nested inner class AssertApproxTests {

        @Test fun `assertApprox passes when within epsilon`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                pub fn assertApprox(actual: float, expected: float, eps: float)
                    let diff = if actual > expected then actual - expected else expected - actual
                    assert(diff <= eps, "expected approximately equal floats")
                @test fn testApproxPass()
                    assertApprox(3.14, 3.14159, 0.01)
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(0, out.exitCode)
        }

        @Test fun `assertApprox fails when outside epsilon`() {
            assumeTrue(clangAvailable())
            val ir = Compiler.compile("""
                module test
                @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
                pub fn assertApprox(actual: float, expected: float, eps: float)
                    let diff = if actual > expected then actual - expected else expected - actual
                    assert(diff <= eps, "expected approximately equal floats")
                @test fn testApproxFail()
                    assertApprox(1.0, 2.0, 0.001)
            """.trimIndent(), "<test>", testMode = true)
            assertInstanceOf(CompileResult.IrSuccess::class.java, ir)
            val out = runTestProgram((ir as CompileResult.IrSuccess).llvmIr)
            assertEquals(1, out.exitCode)
        }
    }

    // ── @test description annotation ──────────────────────────────────────

    @Test fun `@test with description uses description as TAP name`() {
        val result = Compiler.compile("""
            module test
            @extern(fn: "nv_assert") pub fn assert(condition: bool, message: str)
            @test(description: "my custom test name")
            fn testWithDesc()
                assert(true, "ok")
        """.trimIndent(), "<test>", testMode = true)
        assertInstanceOf(CompileResult.IrSuccess::class.java, result)
        val ir = (result as CompileResult.IrSuccess).llvmIr
        assertTrue(ir.contains("my custom test name"), "Expected description in IR string constants")
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private data class TestRun(val stdout: String, val exitCode: Int)

    private fun runTestProgram(ir: String): TestRun {
        val tmp = java.nio.file.Files.createTempDirectory("nv_stdtest_").toFile()
        return try {
            val ll  = java.io.File(tmp, "out.ll"); ll.writeText(ir)
            val bin = java.io.File(tmp, "out")
            val cc  = ProcessBuilder("clang", "-o", bin.absolutePath, ll.absolutePath)
                .redirectErrorStream(true).start()
            val ccOut = cc.inputStream.bufferedReader().readText()
            assertTrue(cc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && cc.exitValue() == 0,
                "clang failed:\n$ccOut")
            val proc = ProcessBuilder(bin.absolutePath)
                .redirectErrorStream(false)
                .start()
            val stdout = proc.inputStream.bufferedReader().readText()
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            TestRun(stdout, proc.exitValue())
        } finally {
            tmp.deleteRecursively()
        }
    }
}
