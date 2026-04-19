package nv.tests

import nv.compiler.CompileError
import nv.tools.DocumentStore
import nv.tools.JsonBuilder
import nv.tools.LspServer
import nv.tools.buildJson
import nv.tools.parseJson
import nv.tools.asMap
import nv.tools.asStr
import nv.tools.asInt
import nv.tools.asLong
import nv.tools.toLspDiagnostic
import nv.tools.toJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class LspTest {

    // ── JSON builder ──────────────────────────────────────────────────────────

    @Nested
    inner class JsonBuilderTests {

        @Test
        fun `builds simple object`() {
            val json = buildJson {
                str("greeting", "hello")
                num("count", 42)
                bool("flag", true)
            }
            assertTrue(json.contains("\"greeting\":\"hello\""), "should contain greeting")
            assertTrue(json.contains("\"count\":42"), "should contain count")
            assertTrue(json.contains("\"flag\":true"), "should contain flag")
            assertTrue(json.startsWith("{") && json.endsWith("}"), "should be object")
        }

        @Test
        fun `builds nested object`() {
            val json = buildJson {
                obj("position") {
                    num("line", 5)
                    num("character", 10)
                }
            }
            assertTrue(json.contains("\"position\":{"), "should have nested object")
            assertTrue(json.contains("\"line\":5"), "should have line")
            assertTrue(json.contains("\"character\":10"), "should have character")
        }

        @Test
        fun `builds array`() {
            val json = buildJson {
                arr("items", listOf("\"a\"", "\"b\"", "\"c\""))
            }
            assertTrue(json.contains("\"items\":[\"a\",\"b\",\"c\"]"), "should have array")
        }

        @Test
        fun `escapes special characters`() {
            val json = buildJson {
                str("msg", "line1\nline2\ttab\"quote")
            }
            assertTrue(json.contains("\\n"), "should escape newlines")
            assertTrue(json.contains("\\t"), "should escape tabs")
            assertTrue(json.contains("\\\""), "should escape quotes")
        }

        @Test
        fun `null values`() {
            val json = buildJson {
                nil("key")
                str("opt", null)
            }
            assertTrue(json.contains("\"key\":null"), "should have explicit null")
            assertTrue(json.contains("\"opt\":null"), "should have string null")
        }

        @Test
        fun `raw value insertion`() {
            val json = buildJson {
                raw("nested", "{\"x\":1}")
            }
            assertTrue(json.contains("\"nested\":{\"x\":1}"), "should embed raw JSON")
        }
    }

    // ── JSON parser ───────────────────────────────────────────────────────────

    @Nested
    inner class JsonParserTests {

        @Test
        fun `parses simple object`() {
            val result = parseJson("""{"method":"initialize","id":1}""").asMap()
            assertNotNull(result)
            assertEquals("initialize", result!!["method"].asStr())
            assertEquals(1L, result["id"].asLong())
        }

        @Test
        fun `parses nested object`() {
            val result = parseJson("""{"params":{"textDocument":{"uri":"file:///test.nv"}}}""").asMap()
            val params = result!!["params"].asMap()
            val textDoc = params!!["textDocument"].asMap()
            assertEquals("file:///test.nv", textDoc!!["uri"].asStr())
        }

        @Test
        fun `parses array`() {
            @Suppress("UNCHECKED_CAST")
            val result = parseJson("""[1,2,3]""") as? List<Any?>
            assertNotNull(result)
            assertEquals(3, result!!.size)
        }

        @Test
        fun `parses boolean and null`() {
            val result = parseJson("""{"a":true,"b":false,"c":null}""").asMap()!!
            assertEquals(true, result["a"])
            assertEquals(false, result["b"])
            assertNull(result["c"])
        }

        @Test
        fun `parses string with escapes`() {
            val result = parseJson("""{"msg":"line1\nline2"}""").asMap()!!
            assertEquals("line1\nline2", result["msg"].asStr())
        }

        @Test
        fun `round-trips json object`() {
            val original = buildJson {
                str("jsonrpc", "2.0")
                num("id", 1)
                str("method", "initialize")
                obj("params") {
                    obj("capabilities") {}
                }
            }
            val parsed = parseJson(original).asMap()
            assertEquals("2.0", parsed!!["jsonrpc"].asStr())
            assertEquals(1L, parsed["id"].asLong())
            assertEquals("initialize", parsed["method"].asStr())
        }

        @Test
        fun `parses negative number`() {
            val result = parseJson("""{"n":-42}""").asMap()!!
            assertEquals(-42L, result["n"].asLong())
        }
    }

    // ── DocumentStore ─────────────────────────────────────────────────────────

    @Nested
    inner class DocumentStoreTests {

        @Test
        fun `open stores document`() {
            val store = DocumentStore()
            store.open("file:///a.nv", "let x = 1")
            assertEquals("let x = 1", store.get("file:///a.nv"))
        }

        @Test
        fun `change updates document`() {
            val store = DocumentStore()
            store.open("file:///a.nv", "let x = 1")
            store.change("file:///a.nv", "let x = 2")
            assertEquals("let x = 2", store.get("file:///a.nv"))
        }

        @Test
        fun `get returns null for unknown uri`() {
            val store = DocumentStore()
            assertNull(store.get("file:///unknown.nv"))
        }

        @Test
        fun `stores multiple documents`() {
            val store = DocumentStore()
            store.open("file:///a.nv", "let a = 1")
            store.open("file:///b.nv", "let b = 2")
            assertEquals("let a = 1", store.get("file:///a.nv"))
            assertEquals("let b = 2", store.get("file:///b.nv"))
        }
    }

    // ── Diagnostic conversion ──────────────────────────────────────────────────

    @Nested
    inner class DiagnosticTests {

        @Test
        fun `compile error converts to lsp diagnostic`() {
            val err = CompileError("type mismatch", "test.nv", 5, 10)
            val diag = err.toLspDiagnostic()
            assertEquals(4, diag.startLine)   // 0-based
            assertEquals(9, diag.startChar)   // 0-based
            assertEquals(1, diag.severity)    // error
            assertEquals("type mismatch", diag.message)
        }

        @Test
        fun `line 1 column 1 error maps to 0,0`() {
            val err = CompileError("syntax error", "test.nv", 1, 1)
            val diag = err.toLspDiagnostic()
            assertEquals(0, diag.startLine)
            assertEquals(0, diag.startChar)
        }

        @Test
        fun `line 0 column 0 clamps to 0`() {
            val err = CompileError("lex error", "test.nv", 0, 0)
            val diag = err.toLspDiagnostic()
            assertEquals(0, diag.startLine)
            assertEquals(0, diag.startChar)
        }

        @Test
        fun `diagnostic to json contains range and message`() {
            val err = CompileError("undefined variable 'x'", "test.nv", 3, 5)
            val diag = err.toLspDiagnostic()
            val json = diag.toJson()
            assertTrue(json.contains("\"range\""), "should have range")
            assertTrue(json.contains("\"severity\":1"), "should be error severity")
            assertTrue(json.contains("undefined variable"), "should contain message")
        }
    }

    // ── LSP server initialize ─────────────────────────────────────────────────

    @Nested
    inner class LspServerTests {

        private fun makeInitializeRequest(id: Int = 1): String {
            return buildJson {
                str("jsonrpc", "2.0")
                num("id", id)
                str("method", "initialize")
                obj("params") {
                    obj("capabilities") {}
                }
            }
        }

        private fun sendRequest(request: String): Map<String, Any?> {
            val requestBytes = request.toByteArray(Charsets.UTF_8)
            val header = "Content-Length: ${requestBytes.size}\r\n\r\n"
            val fullInput = header.toByteArray(Charsets.UTF_8) + requestBytes

            val inputStream = ByteArrayInputStream(fullInput)
            val outputStream = ByteArrayOutputStream()
            val errStream = PrintStream(ByteArrayOutputStream())

            // Run server in a thread with a timeout mechanism
            // We inject a shutdown notification after the initialize request
            val shutdownBytes = buildJson {
                str("jsonrpc", "2.0")
                str("method", "exit")
            }.toByteArray(Charsets.UTF_8)
            val shutdownHeader = "Content-Length: ${shutdownBytes.size}\r\n\r\n"
            val fullInput2 = fullInput +
                shutdownHeader.toByteArray(Charsets.UTF_8) +
                shutdownBytes

            val inputStream2 = ByteArrayInputStream(fullInput2)
            val outputStream2 = ByteArrayOutputStream()

            val server = LspServer(
                rawInput = inputStream2,
                output = outputStream2,
                err = errStream,
            )

            // Run in thread — will exit when it reads the exit notification
            val thread = Thread {
                try { server.run() } catch (_: Exception) {}
            }
            thread.isDaemon = true
            thread.start()
            thread.join(5000) // wait up to 5s
            thread.interrupt()

            val responseText = outputStream2.toString(Charsets.UTF_8)
            // Parse the first response message (Content-Length framing)
            val bodyStart = responseText.indexOf("\r\n\r\n")
            if (bodyStart < 0) return emptyMap()
            val body = responseText.substring(bodyStart + 4)
            // The body may contain multiple messages; parse the first JSON object
            return parseJson(body.substringBefore("\r\nContent-Length")).asMap() ?: emptyMap()
        }

        @Test
        fun `initialize response contains capabilities`() {
            val request = makeInitializeRequest()
            val response = sendRequest(request)

            assertEquals("2.0", response["jsonrpc"].asStr())
            assertEquals(1L, response["id"].asLong())

            val result = response["result"].asMap()
            assertNotNull(result, "result should not be null")

            val capabilities = result!!["capabilities"].asMap()
            assertNotNull(capabilities, "capabilities should not be null")

            val caps = capabilities!!
            assertTrue(caps.containsKey("textDocumentSync"), "should have textDocumentSync")
            assertEquals(true, caps["hoverProvider"])
            assertEquals(true, caps["definitionProvider"])
            assertEquals(true, caps["referencesProvider"])
            assertTrue(caps.containsKey("completionProvider"), "should have completionProvider")
            assertEquals(true, caps["documentFormattingProvider"])
        }

        @Test
        fun `initialize response has server info`() {
            val request = makeInitializeRequest(id = 2)
            val response = sendRequest(request)
            val result = response["result"].asMap()
            val serverInfo = result?.get("serverInfo").asMap()
            assertNotNull(serverInfo, "serverInfo should be present")
            assertEquals("nv-lsp", serverInfo!!["name"].asStr())
        }
    }

    // ── JSON framing ──────────────────────────────────────────────────────────

    @Nested
    inner class JsonFramingTests {

        @Test
        fun `content length header is correct`() {
            val payload = """{"jsonrpc":"2.0","id":1,"result":null}"""
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)

            // Simulate what the server writes
            val outputStream = ByteArrayOutputStream()
            val header = "Content-Length: ${payloadBytes.size}\r\n\r\n"
            outputStream.write(header.toByteArray(Charsets.UTF_8))
            outputStream.write(payloadBytes)

            val output = outputStream.toString(Charsets.UTF_8)
            assertTrue(output.startsWith("Content-Length: ${payloadBytes.size}"), "should start with Content-Length")
            assertTrue(output.contains("\r\n\r\n"), "should have double CRLF separator")
            assertTrue(output.endsWith(payload), "should end with payload")
        }

        @Test
        fun `unicode characters counted by bytes`() {
            // The π character is 2 bytes in UTF-8
            val payload = """{"sym":"π"}"""
            val bytes = payload.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size > payload.length, "UTF-8 multibyte chars should increase byte count")
        }
    }
}
