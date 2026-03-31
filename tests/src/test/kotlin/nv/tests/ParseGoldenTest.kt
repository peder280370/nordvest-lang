package nv.tests

import nv.compiler.lexer.Lexer
import nv.compiler.parser.AstPrinter
import nv.compiler.parser.ParseResult
import nv.compiler.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Paths

class ParseGoldenTest {

    @TestFactory
    fun goldenTests(): List<DynamicTest> {
        val projectDir = System.getProperty("projectDir")
            ?: System.getProperty("user.dir")
        val parseDir = File(Paths.get(projectDir, "tests", "parse").toString())
        if (!parseDir.exists()) return emptyList()

        val nvFiles = parseDir.listFiles { f -> f.extension == "nv" } ?: return emptyList()
        return nvFiles.sortedBy { it.name }.map { nvFile ->
            val expectedFile = File(nvFile.parentFile, "${nvFile.nameWithoutExtension}.ast.expected")
            DynamicTest.dynamicTest(nvFile.nameWithoutExtension) {
                val source = nvFile.readText()
                val tokens = Lexer(source).tokenize()
                val result = Parser(tokens, nvFile.name).parse()
                val file = when (result) {
                    is ParseResult.Success   -> result.file
                    is ParseResult.Recovered -> result.file
                    is ParseResult.Failure   -> throw AssertionError(
                        "Parse failed: ${result.errors.first().message}"
                    )
                }
                val actual = AstPrinter.print(file).trim()
                if (expectedFile.exists()) {
                    val expected = expectedFile.readText().trim()
                    assertEquals(expected, actual)
                } else {
                    // First run: write the expected file
                    expectedFile.writeText("$actual\n")
                }
            }
        }
    }
}
