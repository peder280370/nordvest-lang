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
 * Tests for FsRuntime: std.fs filesystem operations (nv_fs_*).
 */
class FsRuntimeTest {

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
        val tmp = Files.createTempDirectory("nv_fs_").toFile()
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

    @Test fun `stdlib fs module has real implementations`() {
        val f = File(projectDir(), "stdlib/std/fs.nv")
        assertTrue(f.exists())
        val c = f.readText()
        assertTrue(c.contains("nv_fs_exists"),    "missing nv_fs_exists @extern")
        assertTrue(c.contains("nv_fs_is_file"),   "missing nv_fs_is_file @extern")
        assertTrue(c.contains("nv_fs_is_dir"),    "missing nv_fs_is_dir @extern")
        assertTrue(c.contains("nv_fs_mkdir"),     "missing nv_fs_mkdir @extern")
        assertTrue(c.contains("nv_fs_rm"),        "missing nv_fs_rm @extern")
        assertTrue(c.contains("nv_fs_rename"),    "missing nv_fs_rename @extern")
        assertTrue(c.contains("nv_fs_read_text"), "missing nv_fs_read_text @extern")
        assertTrue(c.contains("nv_fs_write_text"),"missing nv_fs_write_text @extern")
        assertTrue(c.contains("nv_fs_join_path"), "missing nv_fs_join_path @extern")
    }

    @Test fun `fs runtime functions are emitted in IR`() {
        val ir = compileOk("module test\nfn main()\n    println(\"ok\")")
        assertTrue(ir.contains("define i1 @nv_fs_exists"),      "nv_fs_exists missing")
        assertTrue(ir.contains("define i1 @nv_fs_is_dir"),      "nv_fs_is_dir missing")
        assertTrue(ir.contains("define i1 @nv_fs_is_file"),     "nv_fs_is_file missing")
        assertTrue(ir.contains("define i64 @nv_fs_mkdir"),      "nv_fs_mkdir missing")
        assertTrue(ir.contains("define i64 @nv_fs_rm"),         "nv_fs_rm missing")
        assertTrue(ir.contains("define i8* @nv_fs_read_text"),  "nv_fs_read_text missing")
        assertTrue(ir.contains("define void @nv_fs_write_text"),"nv_fs_write_text missing")
        assertTrue(ir.contains("define i8* @nv_fs_join_path"),  "nv_fs_join_path missing")
        assertTrue(ir.contains("define i8* @nv_fs_parent_dir"), "nv_fs_parent_dir missing")
        assertTrue(ir.contains("define i8* @nv_fs_file_name"),  "nv_fs_file_name missing")
        assertTrue(ir.contains("define i8* @nv_fs_file_ext"),   "nv_fs_file_ext missing")
        assertTrue(ir.contains("@opendir"),  "opendir not declared")
        assertTrue(ir.contains("@closedir"), "closedir not declared")
        assertTrue(ir.contains("@unlink"),   "unlink not declared")
        assertTrue(ir.contains("@mkdir"),    "mkdir not declared")
        assertTrue(ir.contains("@rename"),   "rename not declared")
        assertTrue(ir.contains("@getcwd"),   "getcwd not declared")
        assertTrue(ir.contains("@strrchr"),  "strrchr not declared")
    }

    @Test fun `fs exists and isDir run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fs_exists") pub fn fsExists(path: str) → bool
            @extern(fn: "nv_fs_is_dir") pub fn fsIsDir(path: str) → bool
            @extern(fn: "nv_fs_is_file") pub fn fsIsFile(path: str) → bool
            fn main()
                if fsExists("/tmp")
                    println("tmp exists")
                if fsIsDir("/tmp")
                    println("tmp is dir")
                if fsIsFile("/tmp")
                    println("tmp is file")
                else
                    println("tmp not file")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("tmp exists\ntmp is dir\ntmp not file", out)
    }

    @Test fun `fs write read rename rm run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fs_write_text") pub fn fsWrite(path: str, text: str)
            @extern(fn: "nv_fs_read_text")  pub fn fsRead(path: str) → str?
            @extern(fn: "nv_fs_rename")     pub fn fsRename(src: str, dst: str) → int
            @extern(fn: "nv_fs_rm")         pub fn fsRm(path: str) → int
            @extern(fn: "nv_fs_exists")     pub fn fsExists(path: str) → bool
            fn main()
                let p1 = "/tmp/nv_fs_a.txt"
                let p2 = "/tmp/nv_fs_b.txt"
                fsWrite(p1, "hello 54")
                let c = fsRead(p1)
                if let s = c
                    println(s)
                fsRename(p1, p2)
                if fsExists(p2)
                    println("renamed ok")
                fsRm(p2)
                if fsExists(p2)
                    println("still exists")
                else
                    println("deleted ok")
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("hello 54\nrenamed ok\ndeleted ok", out)
    }

    @Test fun `fs joinPath parentDir fileName fileExt run correctly`() {
        assumeTrue(clangAvailable())
        val ir = compileOk("""
            module test
            @extern(fn: "nv_fs_join_path")  pub fn fsJoin(base: str, part: str) → str
            @extern(fn: "nv_fs_parent_dir") pub fn fsParent(path: str) → str
            @extern(fn: "nv_fs_file_name")  pub fn fsName(path: str) → str
            @extern(fn: "nv_fs_file_ext")   pub fn fsExt(path: str) → str
            fn main()
                println(fsJoin("/tmp", "test.txt"))
                println(fsParent("/tmp/test.txt"))
                println(fsName("/tmp/test.txt"))
                println(fsExt("/tmp/test.txt"))
        """.trimIndent())
        val out = runProgram(ir)
        assertEquals("/tmp/test.txt\n/tmp\ntest.txt\ntxt", out)
    }
}
