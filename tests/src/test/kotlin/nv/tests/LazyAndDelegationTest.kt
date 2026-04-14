package nv.tests

import nv.compiler.Compiler
import nv.compiler.CompileResult
import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Tests for @lazy fields and `by` interface delegation.
 *
 *  - Parser tests:     AST structure for @lazy and `by` syntax.
 *  - Type-check tests: member types are correctly propagated.
 *  - IR structure:     verify getter methods and forwarding methods are emitted.
 *  - Integration:      compile + run with clang and check stdout.
 */
class LazyAndDelegationTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parse(src: String): SourceFile {
        val tokens = Lexer(src.trimIndent()).tokenize()
        return when (val result = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> result.file
            is ParseResult.Recovered -> result.file
            is ParseResult.Failure   -> fail("Parse failed: ${result.errors.first().message}")
        }
    }

    private fun compileOk(src: String): String {
        val result = Compiler.compile(src.trimIndent(), "<test>")
        val ir = when (result) {
            is CompileResult.IrSuccess -> result.llvmIr
            else -> null
        }
        assertNotNull(ir, "Expected IR success but got: ${(result as? CompileResult.Failure)?.errors?.map { it.message }}")
        return ir!!
    }

    private fun compileErrors(src: String): List<String> {
        val result = Compiler.compile(src.trimIndent(), "<test>")
        return when (result) {
            is CompileResult.Failure -> result.errors.map { it.message }
            else -> emptyList()
        }
    }

    private fun clangAvailable(): Boolean = try {
        val p = ProcessBuilder("clang", "--version").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }

    private fun runProgram(ir: String, tmpPrefix: String = "nv_lazy_"): String {
        val tmp = Files.createTempDirectory(tmpPrefix).toFile()
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

    // ── @lazy — Parser tests ──────────────────────────────────────────────────

    @Test fun `@lazy annotation parsed on field`() {
        val file = parse("""
            module test
            class Parser(source: str)
                @lazy let tokens: int = 0
        """)
        val cls = file.declarations.filterIsInstance<ClassDecl>().first()
        val field = cls.members.filterIsInstance<FieldDecl>().first()
        assertTrue(field.annotations.any { it.name == "lazy" }, "Expected @lazy annotation on field")
        assertFalse(field.isMutable, "Expected val (immutable) lazy field")
        assertEquals("tokens", field.name)
    }

    @Test fun `@lazy var field parsed correctly`() {
        val file = parse("""
            module test
            class Cache(key: str)
                @lazy var data: str = "initial"
        """)
        val cls = file.declarations.filterIsInstance<ClassDecl>().first()
        val field = cls.members.filterIsInstance<FieldDecl>().first()
        assertTrue(field.annotations.any { it.name == "lazy" })
        assertTrue(field.isMutable, "Expected var (mutable) lazy field")
    }

    // ── `by` delegation — Parser tests ───────────────────────────────────────

    @Test fun `by delegation parsed in class declaration`() {
        val file = parse("""
            module test
            interface Greeter
                fn greet() → str
            class FrenchGreeter : Greeter
                fn greet() → str
                    → "Bonjour!"
            class Delegator(g: FrenchGreeter) : Greeter by g
        """)
        val delegator = file.declarations.filterIsInstance<ClassDecl>()
            .first { it.name == "Delegator" }
        assertEquals(1, delegator.superTypes.size, "Delegator should have 1 super type")
        assertEquals(1, delegator.delegations.size, "Delegator should have 1 delegation")
        val (_, delegateExpr) = delegator.delegations.first()
        assertTrue(delegateExpr is IdentExpr && (delegateExpr as IdentExpr).name == "g",
            "Delegate expression should be IdentExpr('g')")
    }

    @Test fun `multiple by delegations parsed`() {
        val file = parse("""
            module test
            interface Printer
                fn print() → str
            interface Logger
                fn log() → str
            class Impl : Printer, Logger
                fn print() → str
                    → "p"
                fn log() → str
                    → "l"
            class Multi(p: Impl, l: Impl) : Printer by p, Logger by l
        """)
        val multi = file.declarations.filterIsInstance<ClassDecl>()
            .first { it.name == "Multi" }
        assertEquals(2, multi.delegations.size, "Multi should have 2 delegations")
    }

    @Test fun `class with super type but no delegation`() {
        val file = parse("""
            module test
            interface Animal
                fn speak() → str
            class Dog : Animal
                fn speak() → str
                    → "Woof"
        """)
        val dog = file.declarations.filterIsInstance<ClassDecl>().first { it.name == "Dog" }
        assertEquals(1, dog.superTypes.size)
        assertEquals(0, dog.delegations.size, "No delegation when 'by' is absent")
    }

    // ── @lazy — IR structure tests ────────────────────────────────────────────

    @Test fun `@lazy field generates init flag and value in struct type`() {
        val ir = compileOk("""
            module test
            class Parser(source: str)
                @lazy let tokens: int = 42
            fn main()
                let p = Parser("hello")
                let t = p.tokens
                println(t)
        """)
        // The struct type should have 3 fields for the class body:
        // (source: i8*), (_lazy_tokens_init: i1), (tokens: i64)
        assertTrue(ir.contains("%struct.Parser = type { i64, i8*,"),
            "struct.Parser should have RC header (i64, i8*)")
        assertTrue(ir.contains("i1"), "struct type should include i1 init flag")
    }

    @Test fun `@lazy field generates getter method in IR`() {
        val ir = compileOk("""
            module test
            class Box(value: int)
                @lazy let doubled: int = value + value
            fn main()
                let b = Box(21)
                println(b.doubled)
        """)
        assertTrue(ir.contains("@nv_Box_get_doubled"),
            "Should emit @nv_Box_get_doubled getter method")
        // The getter should check the init flag
        assertTrue(ir.contains("lz.flag") || ir.contains("lz.hit") || ir.contains("lz.miss"),
            "Getter should have lazy branching logic")
    }

    @Test fun `@lazy field access calls getter not GEP`() {
        val ir = compileOk("""
            module test
            class Box(value: int)
                @lazy let doubled: int = value + value
            fn main()
                let b = Box(21)
                let d = b.doubled
                println(d)
        """)
        // The main function should call the getter, not do a raw GEP
        assertTrue(ir.contains("call i64 @nv_Box_get_doubled"),
            "Member access on lazy field should call the getter")
    }

    @Test fun `@lazy constructor does not include lazy fields in parameters`() {
        val ir = compileOk("""
            module test
            class Box(value: int)
                @lazy let doubled: int = value + value
            fn main()
                let b = Box(21)
                println(b.doubled)
        """)
        // Constructor @nv_Box should only take 'value' as parameter
        assertTrue(ir.contains("define i8* @nv_Box(i64 %ctor.value)"),
            "Constructor should only have ctor params, not lazy fields")
    }

    // ── @lazy — integration tests ─────────────────────────────────────────────

    @Test fun `@lazy field computed on first access`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            class Box(value: int)
                @lazy let doubled: int = value + value
            fn main()
                let b = Box(21)
                println(b.doubled)
        """)
        assertEquals("42", runProgram(ir))
    }

    @Test fun `@lazy field cached - computed only once`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            class Expensive(base: int)
                @lazy let result: int = base * 100
            fn main()
                let obj = Expensive(5)
                let a = obj.result
                let b = obj.result
                println(a)
                println(b)
        """)
        val out = runProgram(ir)
        assertEquals("500\n500", out)
    }

    @Test fun `@lazy field in struct works`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            struct Point(x: int, y: int)
                @lazy let sum: int = x + y
            fn main()
                let p = Point(3, 4)
                println(p.sum)
        """)
        assertEquals("7", runProgram(ir))
    }

    @Test fun `multiple @lazy fields in same class`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            class Stats(n: int)
                @lazy let doubled: int = n + n
                @lazy let tripled: int = n + n + n
            fn main()
                let s = Stats(10)
                println(s.doubled)
                println(s.tripled)
        """)
        val out = runProgram(ir)
        assertEquals("20\n30", out)
    }

    // ── `by` delegation — IR structure tests ─────────────────────────────────

    @Test fun `by delegation generates forwarding method in IR`() {
        val ir = compileOk("""
            module test
            interface Greeter
                fn greet() → str
            class FrenchGreeter : Greeter
                fn greet() → str
                    → "Bonjour!"
            class Delegator(g: FrenchGreeter) : Greeter by g
            fn main()
                let d = Delegator(FrenchGreeter())
                println(d.greet())
        """)
        assertTrue(ir.contains("@nv_Delegator_greet"),
            "Should emit @nv_Delegator_greet forwarding method")
    }

    @Test fun `by delegation forwarder loads delegate and calls method`() {
        val ir = compileOk("""
            module test
            interface Counter
                fn count() → int
            class SimpleCounter : Counter
                fn count() → int
                    → 42
            class WrappedCounter(c: SimpleCounter) : Counter by c
            fn main()
                let w = WrappedCounter(SimpleCounter())
                println(w.count())
        """)
        // Forwarding method should load the delegate field and call SimpleCounter.count
        assertTrue(ir.contains("@nv_WrappedCounter_count"),
            "Should emit forwarding method")
        assertTrue(ir.contains("@nv_SimpleCounter_count"),
            "Forwarding method should call the concrete delegate method")
    }

    @Test fun `by delegation override takes precedence`() {
        val ir = compileOk("""
            module test
            interface Greeter
                fn greet() → str
            class Base : Greeter
                fn greet() → str
                    → "Hello from Base"
            class Overriding(b: Base) : Greeter by b
                fn greet() → str
                    → "Hello from Override"
            fn main()
                let o = Overriding(Base())
                println(o.greet())
        """)
        // Should call the overriding method, not the delegate
        // The override is present as a user-defined method, not a forwarder
        assertTrue(ir.contains("define i8* @nv_Overriding_greet"),
            "Override should be a real method, not a forwarder")
    }

    // ── `by` delegation — integration tests ──────────────────────────────────

    @Test fun `by delegation forwards method call correctly`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            interface Greeter
                fn greet() → str
            class FrenchGreeter : Greeter
                fn greet() → str
                    → "Bonjour!"
            class Delegator(g: FrenchGreeter) : Greeter by g
            fn main()
                let d = Delegator(FrenchGreeter())
                println(d.greet())
        """)
        assertEquals("Bonjour!", runProgram(ir))
    }

    @Test fun `by delegation override returns overriding result`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            interface Greeter
                fn greet() → str
            class Base : Greeter
                fn greet() → str
                    → "Hello from Base"
            class Overriding(b: Base) : Greeter by b
                fn greet() → str
                    → "Hello from Override"
            fn main()
                let o = Overriding(Base())
                println(o.greet())
        """)
        assertEquals("Hello from Override", runProgram(ir))
    }

    @Test fun `by delegation forwards multiple methods`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            interface Shape
                fn area() → int
                fn perimeter() → int
            class Square(side: int) : Shape
                fn area() → int
                    → side * side
                fn perimeter() → int
                    → side * 4
            class ShapeWrapper(s: Square) : Shape by s
            fn main()
                let w = ShapeWrapper(Square(5))
                println(w.area())
                println(w.perimeter())
        """)
        val out = runProgram(ir)
        assertEquals("25\n20", out)
    }

    @Test fun `by delegation with @lazy field combined`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            interface Valueable
                fn getValue() → int
            class Source(x: int) : Valueable
                fn getValue() → int
                    → x
            class Cached(src: Source) : Valueable by src
                @lazy let cached: int = src.getValue()
            fn main()
                let c = Cached(Source(99))
                println(c.cached)
                println(c.getValue())
        """)
        val out = runProgram(ir)
        assertEquals("99\n99", out)
    }
}
