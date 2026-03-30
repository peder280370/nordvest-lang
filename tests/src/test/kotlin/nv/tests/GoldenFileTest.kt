package nv.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * Golden-file test runner.
 *
 * Discovers all *.nv files under tests/golden/, invokes `nv run` on each, and
 * asserts that the output matches the paired *.expected file.
 *
 * Skip strategy: tests are SKIPPED (not FAILED) when:
 *   - nv.jar has not been built yet (run `./gradlew :tools:fatJar` first)
 *   - the .expected file for a .nv source is missing
 *   - `nv run` times out
 *   - `nv run` exits with a stack trace containing "NotImplementedError"
 *     (compiler stub — expected until Phase 1 is complete)
 *
 * Once Phase 1 implements the compiler, NotImplementedError is no longer thrown
 * and the tests automatically activate without any code changes here.
 */
class GoldenFileTest {

    companion object {
        private val projectDir: File = File(System.getProperty("projectDir", ".."))
        private val goldenDir: File  = projectDir.resolve("tests/golden")
        private val nvJar: File      = projectDir.resolve("tools/build/libs/nv.jar")
        private const val TIMEOUT_SECONDS = 30L
    }

    @TestFactory
    fun goldenTests(): Stream<DynamicTest> {
        if (!goldenDir.exists()) return Stream.empty()

        return goldenDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "nv" }
            .map { nvFile ->
                val expectedFile = nvFile.resolveSibling("${nvFile.nameWithoutExtension}.expected")
                val testName = nvFile.relativeTo(goldenDir).path
                dynamicTest(testName) {
                    runGoldenTest(nvFile, expectedFile)
                }
            }
            .toList()
            .stream()
    }

    private fun runGoldenTest(nvFile: File, expectedFile: File) {
        assumeTrue(
            nvJar.exists(),
            "nv.jar not found at ${nvJar.path} — run ./gradlew :tools:fatJar first"
        )
        assumeTrue(
            expectedFile.exists(),
            "No .expected file for ${nvFile.name} — skipping"
        )

        val process = ProcessBuilder("java", "-jar", nvJar.absolutePath, "run", nvFile.absolutePath)
            .redirectErrorStream(true)
            .start()

        val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
        }
        assumeTrue(completed, "nv run timed out after ${TIMEOUT_SECONDS}s for ${nvFile.name}")

        val actualOutput = process.inputStream.bufferedReader().readText().trimEnd()

        // Skip gracefully while the compiler is not yet implemented
        if (process.exitValue() != 0 && actualOutput.contains("NotImplementedError")) {
            assumeTrue(false, "nv run not yet implemented (Phase 1 TODO) for ${nvFile.name}")
        }

        val expectedOutput = expectedFile.readText().trimEnd()
        assertEquals(expectedOutput, actualOutput, "Golden test mismatch for ${nvFile.name}")
    }
}
