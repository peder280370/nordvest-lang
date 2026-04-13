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
 * Tests for HashRuntime: std.hash operations (nv_hash_*).
 */
class HashRuntimeTest {

    private fun projectDir(): File =
        File(System.getProperty("projectDir", System.getProperty("user.dir", ".")))
            .let { if (it.name == "tests") it.parentFile else it }

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
        val tmp = Files.createTempDirectory("nv_hash_").toFile()
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

    @Test fun `stdlib hash module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/hash.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_hash_fnv1a"),   "missing nv_hash_fnv1a @extern")
        assertTrue(c.contains("nv_hash_djb2"),    "missing nv_hash_djb2 @extern")
        assertTrue(c.contains("nv_hash_murmur3"), "missing nv_hash_murmur3 @extern")
        assertTrue(c.contains("nv_hash_crc32"),   "missing nv_hash_crc32 @extern")
        assertTrue(c.contains("nv_hash_combine"), "missing nv_hash_combine @extern")
        assertTrue(c.contains("nv_hash_sha256"),  "missing nv_hash_sha256 @extern")
        assertTrue(c.contains("nv_hash_md5"),     "missing nv_hash_md5 @extern")
    }

    @Test fun `hash fnv1a and djb2 return non-zero for hello`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_hash_fnv1a") pub fn fnv1a(s: str) → int
            @extern(fn: "nv_hash_djb2")  pub fn djb2(s: str)  → int
            fn main()
                let a = fnv1a("hello")
                let b = djb2("hello")
                if a != 0
                    println("fnv ok")
                if b != 0
                    println("djb ok")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("fnv ok\ndjb ok", out)
    }

    @Test fun `hash crc32 of hello matches known value`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_hash_crc32") pub fn crc32(s: str) → int
            fn main()
                let v = crc32("hello")
                println(v)
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("907060870", out)
    }

    @Test fun `hash sha256 of hello matches known vector`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_hash_sha256") pub fn sha256(s: str) → str
            fn main()
                println(sha256("hello"))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", out)
    }

    @Test fun `hash md5 of hello matches known vector`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_hash_md5") pub fn md5(s: str) → str
            fn main()
                println(md5("hello"))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("5d41402abc4b2a76b9719d911017c592", out)
    }
}
