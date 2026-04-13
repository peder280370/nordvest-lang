package nv.tests

import nv.compiler.Compiler
import nv.compiler.CompileResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Tests for @newtype and @derive annotations.
 *
 *  - IR structure tests: compile → check LLVM IR string content (no clang needed).
 *  - Integration tests:  compile + run with clang, compare stdout.
 */
class NewtypeAndDeriveTest {

    private fun compileOk(src: String): String {
        val result = Compiler.compile(src.trimIndent(), "<test>")
        val ir = when (result) {
            is CompileResult.IrSuccess -> result.llvmIr
            else -> null
        }
        assertNotNull(ir, "Expected IR success but got: ${(result as? CompileResult.Failure)?.errors?.map { it.message }}")
        return ir!!
    }

    private fun clangAvailable(): Boolean = try {
        val p = ProcessBuilder("clang", "--version").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }

    private fun runProgram(ir: String): String {
        val tmp = Files.createTempDirectory("nv_derive_").toFile()
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

    // ── @newtype IR structure ─────────────────────────────────────────────

    @Test fun `@newtype struct parses with implicit 'value' field`() {
        val ir = compileOk("""
            module test
            @newtype struct UserId(int)
            fn main()
                let id = UserId(42)
                println(id.value)
        """)
        assertTrue(ir.contains("%struct.UserId"), "UserId struct type missing")
        assertTrue(ir.contains("@nv_UserId"), "UserId constructor missing")
    }

    @Test fun `@newtype emits toString derived method`() {
        val ir = compileOk("""
            module test
            @newtype struct Seconds(float)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i8* @nv_Seconds_toString"), "@newtype toString missing")
        assertTrue(ir.contains("nv_str_concat"), "str_concat missing from toString")
    }

    @Test fun `@newtype emits op_eq derived method`() {
        val ir = compileOk("""
            module test
            @newtype struct UserId(int)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i1 @nv_UserId_op_eq"), "@newtype op_eq missing")
        assertTrue(ir.contains("define i1 @nv_UserId_op_neq"), "@newtype op_neq missing")
    }

    @Test fun `@newtype emits hash derived method`() {
        val ir = compileOk("""
            module test
            @newtype struct UserId(int)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i64 @nv_UserId_hash"), "@newtype hash missing")
        assertTrue(ir.contains("nv_hash_combine"), "hash_combine missing from hash")
    }

    @Test fun `@newtype emits compare derived method`() {
        val ir = compileOk("""
            module test
            @newtype struct UserId(int)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i64 @nv_UserId_compare"), "@newtype compare missing")
    }

    // ── @derive IR structure ──────────────────────────────────────────────

    @Test fun `@derive(Show) emits toString for struct`() {
        val ir = compileOk("""
            module test
            @derive(Show)
            struct Point(x: float, y: float)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i8* @nv_Point_toString"), "Point toString missing")
        assertTrue(ir.contains("nv_float_to_str"), "float_to_str missing from Show")
    }

    @Test fun `@derive(Eq) emits op_eq and op_neq for struct`() {
        val ir = compileOk("""
            module test
            @derive(Eq)
            struct Color(r: int, g: int, b: int)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i1 @nv_Color_op_eq"),  "Color op_eq missing")
        assertTrue(ir.contains("define i1 @nv_Color_op_neq"), "Color op_neq missing")
        assertTrue(ir.contains("icmp eq i64"), "integer comparison missing from op_eq")
    }

    @Test fun `@derive(Hash) emits hash for struct`() {
        val ir = compileOk("""
            module test
            @derive(Hash)
            struct Point(x: float, y: float)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i64 @nv_Point_hash"), "Point hash missing")
        assertTrue(ir.contains("nv_hash_combine"), "hash_combine missing from Hash")
    }

    @Test fun `@derive(Compare) emits compare for struct`() {
        val ir = compileOk("""
            module test
            @derive(Compare)
            struct Point(x: float, y: float)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i64 @nv_Point_compare"), "Point compare missing")
        assertTrue(ir.contains("fcmp olt double"), "float lt missing from Compare")
    }

    @Test fun `@derive(Copy) emits copy for struct`() {
        val ir = compileOk("""
            module test
            @derive(Copy)
            struct Point(x: float, y: float)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i8* @nv_Point_copy"), "Point copy missing")
        assertTrue(ir.contains("call i8* @nv_Point("), "copy should call constructor")
    }

    @Test fun `@derive(All) emits all five methods`() {
        val ir = compileOk("""
            module test
            @derive(All)
            struct Vec2(x: float, y: float)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("@nv_Vec2_toString"), "Vec2 toString missing")
        assertTrue(ir.contains("@nv_Vec2_op_eq"),    "Vec2 op_eq missing")
        assertTrue(ir.contains("@nv_Vec2_hash"),     "Vec2 hash missing")
        assertTrue(ir.contains("@nv_Vec2_compare"),  "Vec2 compare missing")
        assertTrue(ir.contains("@nv_Vec2_copy"),     "Vec2 copy missing")
    }

    @Test fun `@derive works on class type`() {
        val ir = compileOk("""
            module test
            @derive(Show, Eq)
            class Node(pub value: int)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i8* @nv_Node_toString"), "Node toString missing")
        assertTrue(ir.contains("define i1 @nv_Node_op_eq"),     "Node op_eq missing")
    }

    @Test fun `@derive(Show) includes field names in IR string constants`() {
        val ir = compileOk("""
            module test
            @derive(Show)
            struct Point(x: float, y: float)
            fn main()
                println("ok")
        """)
        // The Show IR must contain the literal "x: " and "y: " separator constants
        assertTrue(ir.contains("x: "), "field name 'x' missing from Show IR")
        assertTrue(ir.contains("y: "), "field name 'y' missing from Show IR")
        assertTrue(ir.contains("Point("), "type name prefix missing from Show IR")
    }

    // ── Integration tests (require clang) ─────────────────────────────────

    @Test fun `@newtype toString returns correct string`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @newtype struct Seconds(float)
            fn main()
                let s = Seconds(3.5)
                println(s.toString())
        """)
        val out = runProgram(ir)
        assertTrue(out.contains("Seconds("), "toString prefix missing: $out")
        assertTrue(out.contains("3.5"), "inner value missing: $out")
    }

    @Test fun `@newtype eq compares inner values`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @newtype struct UserId(int)
            fn main()
                let a = UserId(1)
                let b = UserId(1)
                let c = UserId(2)
                if a == b
                    println("equal")
                if a == c
                    println("wrong")
                else
                    println("not equal")
        """)
        val out = runProgram(ir)
        assertEquals("equal\nnot equal", out)
    }

    @Test fun `@derive(Show) toString formats struct correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @derive(Show)
            struct Point(x: float, y: float)
            fn main()
                let p = Point(1.0, 2.0)
                println(p.toString())
        """)
        val out = runProgram(ir)
        assertTrue(out.contains("Point("), "prefix missing: $out")
        assertTrue(out.contains("x:"),     "field x missing: $out")
        assertTrue(out.contains("y:"),     "field y missing: $out")
    }

    @Test fun `@derive(Eq) compares structs field-by-field`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @derive(Eq)
            struct Color(r: int, g: int, b: int)
            fn main()
                let red1 = Color(255, 0, 0)
                let red2 = Color(255, 0, 0)
                let blue = Color(0, 0, 255)
                if red1 == red2
                    println("same")
                if red1 == blue
                    println("wrong")
                else
                    println("different")
        """)
        val out = runProgram(ir)
        assertEquals("same\ndifferent", out)
    }

    @Test fun `@derive(Hash) returns same hash for equal structs`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @derive(Hash)
            struct Point(x: float, y: float)
            fn main()
                let p1 = Point(1.0, 2.0)
                let p2 = Point(1.0, 2.0)
                let h1 = p1.hash()
                let h2 = p2.hash()
                if h1 == h2
                    println("same hash")
                else
                    println("different hash")
        """)
        val out = runProgram(ir)
        assertEquals("same hash", out)
    }

    @Test fun `@derive(Compare) orders structs by first field`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @derive(Compare)
            struct Score(value: int)
            fn main()
                let low  = Score(10)
                let high = Score(99)
                let cmp  = low.compare(high)
                if cmp < 0
                    println("less")
                else
                    println("not less")
        """)
        val out = runProgram(ir)
        assertEquals("less", out)
    }

    @Test fun `@derive(Copy) produces an independent copy`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @derive(Copy, Show)
            struct Point(x: float, y: float)
            fn main()
                let p = Point(1.0, 2.0)
                let q = p.copy()
                println(q.toString())
        """)
        val out = runProgram(ir)
        assertTrue(out.contains("1"), "copy should contain original values: $out")
    }

    @Test fun `@derive(All) on struct with int fields`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @derive(All)
            struct Pair(a: int, b: int)
            fn main()
                let p = Pair(3, 7)
                println(p.toString())
                let q = Pair(3, 7)
                if p == q
                    println("eq")
                println(p.hash())
        """)
        val out = runProgram(ir)
        assertTrue(out.contains("Pair("), "Show failed: $out")
        assertTrue(out.contains("eq"),    "Eq failed: $out")
    }
}
