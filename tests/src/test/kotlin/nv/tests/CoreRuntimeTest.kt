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
 * Tests for CoreRuntime: reference counting (nv_rc_retain, nv_rc_release, nv_weak_load)
 * and class RC header layout.
 */
class CoreRuntimeTest {

    private fun compileOk(src: String): String {
        val result = Compiler.compile(src, "<test>")
        val ir = when (result) {
            is CompileResult.IrSuccess -> result.llvmIr
            else -> null
        }
        assertNotNull(ir, "Expected IR success: ${(result as? CompileResult.Failure)?.errors?.map { it.message }}")
        return ir!!
    }

    private fun clangAvailable(): Boolean {
        return try {
            val p = ProcessBuilder("clang", "--version").redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) { false }
    }

    private fun runProgram(ir: String): String {
        val tmp = Files.createTempDirectory("nv_rc_").toFile()
        return try {
            val ll  = File(tmp, "out.ll");  ll.writeText(ir)
            val bin = File(tmp, "out")
            val cc  = ProcessBuilder("clang", "-o", bin.absolutePath, ll.absolutePath)
                .redirectErrorStream(true).start()
            val ccOk = cc.waitFor(60, TimeUnit.SECONDS) && cc.exitValue() == 0
            assertTrue(ccOk, "clang failed: ${cc.inputStream.bufferedReader().readText()}")
            val run = ProcessBuilder(bin.absolutePath)
                .redirectErrorStream(true).start()
            run.waitFor(10, TimeUnit.SECONDS)
            run.inputStream.bufferedReader().readText().trimEnd()
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun `nv_rc_retain uses atomicrmw in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define void @nv_rc_retain"),  "nv_rc_retain missing")
        assertTrue(ir.contains("atomicrmw add"),              "atomicrmw add missing from nv_rc_retain")
    }

    @Test fun `nv_rc_release uses atomicrmw and calls dtor in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define void @nv_rc_release"), "nv_rc_release missing")
        assertTrue(ir.contains("atomicrmw sub"),              "atomicrmw sub missing from nv_rc_release")
        assertTrue(ir.contains("bitcast i8* %dtor_raw to void (i8*)*"), "dtor dispatch missing")
    }

    @Test fun `nv_weak_load is emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i8* @nv_weak_load"), "nv_weak_load missing")
        assertTrue(ir.contains("icmp sgt i64"),             "strong_count check missing from nv_weak_load")
    }

    @Test fun `class struct has RC header fields in IR`() {
        val ir = compileOk("""
            module test
            class Counter(pub value: int)
            fn main()
                println("ok")
        """.trimIndent())
        // RC header: { i64, i8*, i64 } — strong_count, dtor_fn, user field
        assertTrue(ir.contains("%struct.Counter = type { i64, i8*, i64 }"),
            "Counter struct missing RC header; IR:\n${ir.lines().filter { it.contains("Counter") }.joinToString("\n")}")
    }

    @Test fun `class constructor initializes strong_count to 1`() {
        val ir = compileOk("""
            module test
            class Counter(pub value: int)
            fn main()
                println("ok")
        """.trimIndent())
        assertTrue(ir.contains("store i64 1, i64*"), "strong_count=1 store missing")
    }

    @Test fun `class destructor is emitted`() {
        val ir = compileOk("""
            module test
            class Node(pub value: int)
            class Tree(pub left: Node)
            fn main()
                println("ok")
        """.trimIndent())
        assertTrue(ir.contains("define void @nv_dtor_Tree"), "nv_dtor_Tree missing")
        assertTrue(ir.contains("call void @nv_rc_release"),  "nv_rc_release call missing from destructor")
    }

    @Test fun `class with no RC fields has null dtor`() {
        val ir = compileOk("""
            module test
            class Point(pub x: int, pub y: int)
            fn main()
                println("ok")
        """.trimIndent())
        // No RC fields => dtor_fn slot stores null
        assertTrue(ir.contains("store i8* null, i8**"), "null dtor store missing for Point")
        // No destructor function should be emitted for Point (no RC fields to release)
        assertFalse(ir.contains("define void @nv_dtor_Point"),
            "nv_dtor_Point should not be emitted when there are no RC fields")
    }

    @Test fun `class field GEP uses index 2 for first user field`() {
        val ir = compileOk("""
            module test
            class Box(pub n: int)
            fn getN(b: Box) → int
                → b.n
            fn main()
                println("ok")
        """.trimIndent())
        // GEP index 2 = first user field (after i64 strong_count at 0, i8* dtor_fn at 1)
        assertTrue(ir.contains("i32 2"), "GEP index 2 missing for class field access")
    }

    @Test fun `class instantiation and field access works`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            class Counter(pub value: int)
            fn main()
                let c = Counter(42)
                println(c.value.str)
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("42", out)
    }

    @Test fun `retain and release do not crash`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_rc_retain")  pub fn rcRetain(p: int)
            @extern(fn: "nv_rc_release") pub fn rcRelease(p: int)
            fn main()
                rcRetain(0)
                rcRelease(0)
                println("no crash")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("no crash", out)
    }

    @Test fun `class with nested class field and destructor chain`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            class Inner(pub x: int)
            class Outer(pub inner: Inner)
            fn main()
                let i = Inner(7)
                let o = Outer(i)
                println(o.inner.x.str)
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("7", out)
    }

    @Test fun `weak_load returns null for null pointer`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_weak_load") pub fn weakLoad(p: int) → int
            fn main()
                let r = weakLoad(0)
                if r == 0
                    println("null ok")
                else
                    println("unexpected")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("null ok", out)
    }
}
