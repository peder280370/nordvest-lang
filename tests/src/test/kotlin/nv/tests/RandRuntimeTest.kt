package nv.tests

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for RandRuntime: std.rand operations (nv_rand_*).
 */
class RandRuntimeTest : NvCompilerTestBase() {


    @Test fun `stdlib rand module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/rand.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_rand_seed"),  "missing nv_rand_seed @extern")
        assertTrue(c.contains("nv_rand_float"), "missing nv_rand_float @extern")
        assertTrue(c.contains("nv_rand_int"),   "missing nv_rand_int @extern")
        assertTrue(c.contains("nv_rand_bool"),  "missing nv_rand_bool @extern")
    }

    @Test fun `rand runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define void @nv_rand_seed"),    "nv_rand_seed missing")
        assertTrue(ir.contains("define i64 @nv_rand_next"),     "nv_rand_next missing")
        assertTrue(ir.contains("define double @nv_rand_float"), "nv_rand_float missing")
        assertTrue(ir.contains("define i64 @nv_rand_int"),      "nv_rand_int missing")
        assertTrue(ir.contains("define i1 @nv_rand_bool"),      "nv_rand_bool missing")
        assertTrue(ir.contains("@nv_rand_state"),               "nv_rand_state global missing")
    }

    @Test fun `rand int produces values in range`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_rand_seed") pub fn randSeed(s: int)
            @extern(fn: "nv_rand_int")  pub fn randInt(lo: int, hi: int) → int
            fn main()
                randSeed(42)
                var ok = true
                var i = 0
                while i < 20
                    let r = randInt(0, 10)
                    if r < 0
                        ok = false
                    if r >= 10
                        ok = false
                    i = i + 1
                if ok
                    println("all in range")
                else
                    println("out of range")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("all in range", out)
    }

    @Test fun `rand float produces values in 0 1`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_rand_seed")  pub fn randSeed(s: int)
            @extern(fn: "nv_rand_float") pub fn randFloat() → float
            fn main()
                randSeed(99)
                var ok = true
                var i = 0
                while i < 10
                    let r = randFloat()
                    if r < 0.0
                        ok = false
                    if r >= 1.0
                        ok = false
                    i = i + 1
                if ok
                    println("all in [0,1)")
                else
                    println("out of range")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("all in [0,1)", out)
    }
}
