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
 * Tests for IoRuntime: std.io file operations (nv_file_*).
 */
class IoRuntimeTest {

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
        val tmp = Files.createTempDirectory("nv_io_").toFile()
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

    @Test fun `stdlib io module has IOError and File operations`() {
        val f = File(projectDir(), "stdlib/std/io.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("sealed class IOError"),   "IOError sealed class missing")
        assertTrue(c.contains("nv_file_open_read"),      "nv_file_open_read @extern missing")
        assertTrue(c.contains("nv_file_open_write"),     "nv_file_open_write @extern missing")
        assertTrue(c.contains("nv_file_close"),          "nv_file_close @extern missing")
        assertTrue(c.contains("nv_file_read_line"),      "nv_file_read_line @extern missing")
        assertTrue(c.contains("nv_file_read_all"),       "nv_file_read_all @extern missing")
        assertTrue(c.contains("nv_file_write"),          "nv_file_write @extern missing")
        assertTrue(c.contains("nv_file_exists"),         "nv_file_exists @extern missing")
    }

    @Test fun `IO runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i8* @nv_file_open_read"),   "nv_file_open_read missing")
        assertTrue(ir.contains("define void @nv_file_close"),      "nv_file_close missing")
        assertTrue(ir.contains("define void @nv_file_write"),      "nv_file_write missing")
        assertTrue(ir.contains("define i8* @nv_file_read_line"),   "nv_file_read_line missing")
        assertTrue(ir.contains("define i8* @nv_file_read_all"),    "nv_file_read_all missing")
        assertTrue(ir.contains("define i1 @nv_file_exists"),       "nv_file_exists missing")
        assertTrue(ir.contains("@fopen"), "fopen not declared")
        assertTrue(ir.contains("@fclose"), "fclose not declared")
        assertTrue(ir.contains("@fgets"), "fgets not declared")
        assertTrue(ir.contains("@fread"), "fread not declared")
        assertTrue(ir.contains("@fwrite"), "fwrite not declared")
        assertTrue(ir.contains("@access"), "access not declared")
    }

    @Test fun `program using file operations compiles`() {
        val ir = compileOk("""
            module test
            @extern(fn: "nv_file_open_write")  pub fn fileOpenWrite(path: str) → str?
            @extern(fn: "nv_file_close")       pub fn fileClose(file: str)
            @extern(fn: "nv_file_writeln")     pub fn fileWriteln(file: str, s: str)
            @extern(fn: "nv_file_exists")      pub fn fileExists(path: str) → bool
            fn main()
                if fileExists("/tmp")
                    println("tmp exists")
        """.trimIndent())
        assertTrue(ir.contains("@nv_file_exists"))
    }

    @Test fun `file write and read run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_file_open_write")  pub fn fileWrite(path: str) → str?
            @extern(fn: "nv_file_open_read")   pub fn fileRead(path: str) → str?
            @extern(fn: "nv_file_close")       pub fn fileClose(file: str)
            @extern(fn: "nv_file_write")       pub fn fileWriteStr(file: str, s: str)
            @extern(fn: "nv_file_read_all")    pub fn fileReadAll(file: str) → str
            @extern(fn: "nv_file_exists")      pub fn fileExists(path: str) → bool
            fn main()
                let path = "/tmp/nv_io_test.txt"
                let wf = fileWrite(path)
                if let f = wf
                    fileWriteStr(f, "hello from nordvest")
                    fileClose(f)
                if fileExists(path)
                    let rf = fileRead(path)
                    if let f2 = rf
                        let content = fileReadAll(f2)
                        fileClose(f2)
                        println(content)
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("hello from nordvest", out)
    }
}
