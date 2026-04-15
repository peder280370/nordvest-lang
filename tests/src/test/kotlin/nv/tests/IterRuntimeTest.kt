package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for IterRuntime: std.iter range operations (nv_iter_*).
 */
class IterRuntimeTest : NvCompilerTestBase() {


    @Test fun `stdlib iter module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/iter.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_iter_range"),      "missing nv_iter_range @extern")
        assertTrue(c.contains("nv_iter_range_step"), "missing nv_iter_range_step @extern")
    }

    @Test fun `iter range produces correct values`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_iter_range") pub fn range(start: int, end: int) → [int]
            fn main()
                let xs = range(0, 5)
                var i = 0
                while i < len(xs)
                    println(xs[i])
                    i = i + 1
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("0\n1\n2\n3\n4", out)
    }

    @Test fun `iter rangeStep produces correct values`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_iter_range_step") pub fn rangeStep(start: int, end: int, step: int) → [int]
            fn main()
                let xs = rangeStep(0, 10, 3)
                var i = 0
                while i < len(xs)
                    println(xs[i])
                    i = i + 1
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("0\n3\n6\n9", out)
    }

    @Test fun `iter repeatInt produces correct values`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            import std.iter
            fn main()
                let xs = repeatInt(42, 4)
                println(len(xs))
                println(xs[0])
                println(xs[3])
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("4\n42\n42", out)
    }

    @Test fun `iter chainInt concatenates two int arrays`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            import std.iter
            fn main()
                let c = chainInt([1, 2, 3], [10, 20])
                println(len(c))
                println(c[0])
                println(c[4])
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("5\n1\n20", out)
    }

    @Test fun `iter range via import std dot iter works`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            import std.iter
            fn main()
                let xs = range(1, 6)
                println(len(xs))
                println(xs[0])
                println(xs[4])
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("5\n1\n5", out)
    }
}
