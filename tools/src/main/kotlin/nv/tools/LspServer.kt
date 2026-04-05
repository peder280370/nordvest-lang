package nv.tools

import nv.compiler.Compiler
import nv.compiler.CompileError
import nv.compiler.CompileResult
import nv.compiler.lexer.Lexer
import nv.compiler.lexer.LexerError
import nv.compiler.parser.Parser
import nv.compiler.parser.ParseResult
import nv.compiler.resolve.Resolver
import nv.compiler.resolve.ResolveResult
import nv.compiler.typecheck.TypeChecker
import nv.compiler.typecheck.TypeCheckResult
import nv.compiler.typecheck.display
import java.io.BufferedReader
import java.io.OutputStream
import java.io.PrintStream

// ── Minimal JSON builder ───────────────────────────────────────────────────────

class JsonBuilder {
    private val parts = mutableListOf<String>()

    fun str(key: String, value: String?) {
        if (value == null) {
            parts += "\"${escapeJson(key)}\":null"
        } else {
            parts += "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
    }

    fun num(key: String, value: Number?) {
        parts += "\"${escapeJson(key)}\":${value ?: "null"}"
    }

    fun bool(key: String, value: Boolean?) {
        parts += "\"${escapeJson(key)}\":${value ?: "null"}"
    }

    fun nil(key: String) {
        parts += "\"${escapeJson(key)}\":null"
    }

    fun obj(key: String, block: JsonBuilder.() -> Unit) {
        val inner = JsonBuilder()
        inner.block()
        parts += "\"${escapeJson(key)}\":${inner.build()}"
    }

    fun arr(key: String, items: List<String>) {
        parts += "\"${escapeJson(key)}\":[${items.joinToString(",")}]"
    }

    fun arrObj(key: String, items: List<JsonBuilder.() -> Unit>) {
        val rendered = items.map { block ->
            val b = JsonBuilder()
            b.block()
            b.build()
        }
        parts += "\"${escapeJson(key)}\":[${rendered.joinToString(",")}]"
    }

    fun raw(key: String, value: String) {
        parts += "\"${escapeJson(key)}\":$value"
    }

    fun build(): String = "{${parts.joinToString(",")}}"
}

fun buildJson(block: JsonBuilder.() -> Unit): String {
    val b = JsonBuilder()
    b.block()
    return b.build()
}

fun jsonString(value: String): String = "\"${escapeJson(value)}\""
fun jsonNull(): String = "null"

private fun escapeJson(s: String): String = buildString {
    for (ch in s) {
        when (ch) {
            '"'  -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
        }
    }
}

// ── Minimal JSON parser ────────────────────────────────────────────────────────

fun parseJson(input: String): Any? {
    val (value, _) = JsonParser(input, 0).parseValue()
    return value
}

private class JsonParser(private val src: String, private var pos: Int) {
    fun parseValue(): Pair<Any?, Int> {
        skipWs()
        if (pos >= src.length) return null to pos
        return when (src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { pos += 4; true to pos }
            'f' -> { pos += 5; false to pos }
            'n' -> { pos += 4; null to pos }
            else -> parseNumber()
        }
    }

    private fun parseObject(): Pair<Map<String, Any?>, Int> {
        pos++ // '{'
        val map = mutableMapOf<String, Any?>()
        skipWs()
        while (pos < src.length && src[pos] != '}') {
            skipWs()
            val (rawKey, _) = parseString()
            val key = rawKey as String
            skipWs()
            if (pos < src.length && src[pos] == ':') pos++
            skipWs()
            val (value, _) = parseValue()
            map[key] = value
            skipWs()
            if (pos < src.length && src[pos] == ',') pos++
            skipWs()
        }
        if (pos < src.length) pos++ // '}'
        return map to pos
    }

    private fun parseArray(): Pair<List<Any?>, Int> {
        pos++ // '['
        val list = mutableListOf<Any?>()
        skipWs()
        while (pos < src.length && src[pos] != ']') {
            val (value, _) = parseValue()
            list += value
            skipWs()
            if (pos < src.length && src[pos] == ',') pos++
            skipWs()
        }
        if (pos < src.length) pos++ // ']'
        return list to pos
    }

    private fun parseString(): Pair<String, Int> {
        pos++ // '"'
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != '"') {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                pos++
                when (src[pos]) {
                    '"'  -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n'  -> sb.append('\n')
                    'r'  -> sb.append('\r')
                    't'  -> sb.append('\t')
                    'u'  -> {
                        val hex = src.substring(pos + 1, (pos + 5).coerceAtMost(src.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        pos += 4
                    }
                    else -> sb.append(src[pos])
                }
            } else {
                sb.append(src[pos])
            }
            pos++
        }
        if (pos < src.length) pos++ // '"'
        return sb.toString() to pos
    }

    private fun parseNumber(): Pair<Any?, Int> {
        val start = pos
        if (pos < src.length && src[pos] == '-') pos++
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' || src[pos] == '+' || src[pos] == '-')) pos++
        val text = src.substring(start, pos)
        return (text.toLongOrNull() ?: text.toDoubleOrNull()) to pos
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }
}

// ── Convenience accessors on parsed JSON ─────────────────────────────────────

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
fun Any?.asStr(): String? = this as? String
fun Any?.asLong(): Long? = when (this) {
    is Long   -> this
    is Int    -> this.toLong()
    is Double -> this.toLong()
    else      -> null
}
fun Any?.asInt(): Int? = asLong()?.toInt()

// ── Document store ────────────────────────────────────────────────────────────

class DocumentStore {
    val documents: MutableMap<String, String> = mutableMapOf()

    fun open(uri: String, text: String) { documents[uri] = text }
    fun change(uri: String, text: String) { documents[uri] = text }
    fun get(uri: String): String? = documents[uri]
}

// ── LSP diagnostics helper ────────────────────────────────────────────────────

data class LspDiagnostic(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val severity: Int,   // 1=Error, 2=Warning, 3=Info, 4=Hint
    val message: String,
    val source: String = "nv",
)

fun CompileError.toLspDiagnostic(): LspDiagnostic {
    val ln = (line - 1).coerceAtLeast(0)
    val ch = (column - 1).coerceAtLeast(0)
    return LspDiagnostic(ln, ch, ln, ch + 1, 1, message)
}

fun LspDiagnostic.toJson(): String = buildJson {
    obj("range") {
        obj("start") { num("line", startLine); num("character", startChar) }
        obj("end")   { num("line", endLine);   num("character", endChar)   }
    }
    num("severity", severity)
    str("message", message)
    str("source", source)
}

// ── Completion keywords & math symbols ───────────────────────────────────────

private val KEYWORD_COMPLETIONS = listOf(
    "let", "var", "fn", "class", "struct", "record", "interface", "sealed", "enum",
    "if", "else", "for", "while", "match", "return", "import", "module", "pub",
    "async", "await", "go", "spawn", "defer", "guard", "unsafe", "yield",
    "true", "false", "nil", "extend", "where", "throws", "is", "as", "in",
    "break", "continue", "weak", "unowned", "override",
)

private val MATH_COMPLETIONS = listOf(
    "π", "e", "∞", "→", "∀", "∃", "∑", "∏", "∈", "∧", "∨", "¬",
)

// ── LSP server ────────────────────────────────────────────────────────────────

/** Thrown internally when the LSP client requests exit. */
internal class LspExitException(val code: Int) : Exception()

class LspServer(
    private val input: BufferedReader = System.`in`.bufferedReader(),
    private val output: OutputStream = System.out,
    private val err: PrintStream = System.err,
) {
    private val store = DocumentStore()
    private var shutdownRequested = false
    private var initialized = false

    /**
     * Run the server loop. Blocks until the client sends `exit`.
     * When running as the real CLI entry point, callers should call [System.exit] with the returned code.
     * @return exit code (0 = clean shutdown, 1 = abrupt exit without prior shutdown)
     */
    fun run(): Int {
        try {
            while (true) {
                val message = readMessage() ?: break
                try {
                    val parsed = parseJson(message).asMap() ?: continue
                    val response = handleMessage(parsed)
                    if (response != null) writeMessage(response)
                } catch (e: LspExitException) {
                    return e.code
                } catch (e: Exception) {
                    err.println("[nv lsp] error handling message: ${e.message}")
                }
            }
        } catch (e: LspExitException) {
            return e.code
        }
        return 0
    }

    // ── I/O ──────────────────────────────────────────────────────────────────

    private fun readMessage(): String? {
        var contentLength = -1
        // Read headers
        while (true) {
            val line = input.readLine() ?: return null
            if (line.isBlank()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength <= 0) return null
        val buf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(buf, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return String(buf)
    }

    private fun writeMessage(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        synchronized(output) {
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    private fun handleMessage(msg: Map<String, Any?>): String? {
        val method = msg["method"].asStr() ?: return null
        val id     = msg["id"]
        val params = msg["params"].asMap() ?: emptyMap()

        // Notifications have no id and need no response
        val isNotification = id == null

        val result: Any? = try {
            when (method) {
                "initialize"                    -> handleInitialize(params)
                "initialized"                   -> { initialized = true; null }
                "shutdown"                      -> { shutdownRequested = true; null }
                "exit"                          -> throw LspExitException(if (shutdownRequested) 0 else 1)
                "textDocument/didOpen"          -> { handleDidOpen(params); null }
                "textDocument/didChange"        -> { handleDidChange(params); null }
                "textDocument/didClose"         -> null
                "textDocument/hover"            -> handleHover(params)
                "textDocument/definition"       -> handleDefinition(params)
                "textDocument/references"       -> handleReferences(params)
                "textDocument/completion"       -> handleCompletion(params)
                "textDocument/formatting"       -> handleFormatting(params)
                "$/cancelRequest"               -> null
                else                            -> if (isNotification) null else errorResponse(-32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            err.println("[nv lsp] exception in $method: ${e.message}")
            if (isNotification) null else errorResponse(-32603, "Internal error: ${e.message}")
        }

        if (isNotification) return null
        return buildResponseJson(id, result)
    }

    private fun errorResponse(code: Int, message: String): String =
        buildJson {
            obj("error") {
                num("code", code)
                str("message", message)
            }
        }

    private fun buildResponseJson(id: Any?, result: Any?): String {
        return buildJson {
            str("jsonrpc", "2.0")
            when (id) {
                is String -> str("id", id)
                is Long   -> num("id", id)
                is Int    -> num("id", id)
                is Double -> num("id", id.toLong())
                null      -> nil("id")
                else      -> str("id", id.toString())
            }
            when (result) {
                null      -> nil("result")
                is String -> if (result.startsWith("{") || result.startsWith("[") || result == "null") {
                    raw("result", result)
                } else {
                    raw("result", jsonString(result))
                }
                else      -> raw("result", result.toString())
            }
        }
    }

    // ── initialize ───────────────────────────────────────────────────────────

    private fun handleInitialize(params: Map<String, Any?>): String = buildJson {
        obj("capabilities") {
            obj("textDocumentSync") {
                num("openClose", 1)
                num("change", 1)   // 1 = full text sync
            }
            bool("hoverProvider", true)
            bool("definitionProvider", true)
            bool("referencesProvider", true)
            obj("completionProvider") {
                arr("triggerCharacters", listOf(jsonString("."), jsonString("?")))
                bool("resolveProvider", false)
            }
            bool("documentFormattingProvider", true)
        }
        obj("serverInfo") {
            str("name", "nv-lsp")
            str("version", nv.compiler.Compiler.VERSION)
        }
    }

    // ── textDocument/didOpen ─────────────────────────────────────────────────

    private fun handleDidOpen(params: Map<String, Any?>) {
        val textDoc = params["textDocument"].asMap() ?: return
        val uri  = textDoc["uri"].asStr() ?: return
        val text = textDoc["text"].asStr() ?: ""
        store.open(uri, text)
        publishDiagnostics(uri, text)
    }

    // ── textDocument/didChange ────────────────────────────────────────────────

    private fun handleDidChange(params: Map<String, Any?>) {
        val uri     = params["textDocument"].asMap()?.get("uri").asStr() ?: return
        @Suppress("UNCHECKED_CAST")
        val changes = params["contentChanges"] as? List<Any?> ?: return
        // Full-text sync: take last change's text
        val lastChange = changes.lastOrNull().asMap() ?: return
        val text = lastChange["text"].asStr() ?: return
        store.change(uri, text)
        publishDiagnostics(uri, text)
    }

    // ── diagnostics ──────────────────────────────────────────────────────────

    private fun publishDiagnostics(uri: String, text: String) {
        val errors = collectErrors(text, uriToPath(uri))
        val diagnosticsJson = errors.map { it.toLspDiagnostic().toJson() }
        val notification = buildJson {
            str("jsonrpc", "2.0")
            str("method", "textDocument/publishDiagnostics")
            obj("params") {
                str("uri", uri)
                arr("diagnostics", diagnosticsJson)
            }
        }
        writeMessage(notification)
    }

    private fun collectErrors(source: String, path: String): List<CompileError> {
        return when (val result = Compiler.compile(source, path)) {
            is CompileResult.Failure   -> result.errors
            is CompileResult.IrSuccess -> emptyList()
            is CompileResult.Success   -> emptyList()
        }
    }

    // ── textDocument/hover ────────────────────────────────────────────────────

    private fun handleHover(params: Map<String, Any?>): String {
        val uri    = params["textDocument"].asMap()?.get("uri").asStr() ?: return jsonNull()
        val pos    = params["position"].asMap() ?: return jsonNull()
        val line   = pos["line"].asInt() ?: return jsonNull()
        val char   = pos["character"].asInt() ?: return jsonNull()
        val source = store.get(uri) ?: return jsonNull()
        val path   = uriToPath(uri)

        val typeInfo = findTypeAtPosition(source, path, line, char)
        if (typeInfo == null) return jsonNull()

        return buildJson {
            obj("contents") {
                str("kind", "markdown")
                str("value", "```\n$typeInfo\n```")
            }
        }
    }

    private fun findTypeAtPosition(source: String, path: String, line: Int, char: Int): String? {
        val offset = lineCharToOffset(source, line, char)
        val module = typeCheckSource(source, path) ?: return null
        // Look up type at or near offset in the typeMap
        val entry = module.typeMap.entries
            .filter { (k, _) -> k <= offset }
            .maxByOrNull { (k, _) -> k }
        return entry?.value?.display()
    }

    // ── textDocument/definition ───────────────────────────────────────────────

    private fun handleDefinition(params: Map<String, Any?>): String {
        val uri    = params["textDocument"].asMap()?.get("uri").asStr() ?: return jsonNull()
        val pos    = params["position"].asMap() ?: return jsonNull()
        val line   = pos["line"].asInt() ?: return jsonNull()
        val char   = pos["character"].asInt() ?: return jsonNull()
        val source = store.get(uri) ?: return jsonNull()
        val path   = uriToPath(uri)

        val defLoc = findDefinition(source, path, line, char, uri)
        return defLoc ?: jsonNull()
    }

    private fun findDefinition(source: String, path: String, line: Int, char: Int, uri: String): String? {
        val offset = lineCharToOffset(source, line, char)
        val module = resolveSource(source, path) ?: return null
        val sym = module.resolvedRefs.entries
            .filter { (k, _) -> k <= offset }
            .maxByOrNull { (k, _) -> k }
            ?.value ?: return null

        val symSpan = sym.span
        val defLine = (symSpan.start.line - 1).coerceAtLeast(0)
        val defChar = (symSpan.start.col - 1).coerceAtLeast(0)

        return buildJson {
            str("uri", uri)
            obj("range") {
                obj("start") { num("line", defLine); num("character", defChar) }
                obj("end")   { num("line", defLine); num("character", defChar + sym.name.length) }
            }
        }
    }

    // ── textDocument/references ───────────────────────────────────────────────

    private fun handleReferences(params: Map<String, Any?>): String {
        // References require cross-file analysis; return empty list for now
        return "[]"
    }

    // ── textDocument/completion ───────────────────────────────────────────────

    private fun handleCompletion(params: Map<String, Any?>): String {
        val uri    = params["textDocument"].asMap()?.get("uri").asStr()
        val pos    = params["position"].asMap()
        val line   = pos?.get("line").asInt() ?: 0
        val char   = pos?.get("character").asInt() ?: 0
        val source = uri?.let { store.get(it) } ?: ""

        // Determine prefix before cursor
        val lines  = source.split('\n')
        val curLine = lines.getOrElse(line) { "" }
        val prefix  = curLine.take(char)
        val afterDot = prefix.endsWith(".") || prefix.endsWith("?.")

        val items = mutableListOf<String>()

        if (!afterDot) {
            for (kw in KEYWORD_COMPLETIONS) {
                items += buildJson {
                    str("label", kw)
                    num("kind", 14) // Keyword
                }
            }
            for (sym in MATH_COMPLETIONS) {
                items += buildJson {
                    str("label", sym)
                    num("kind", 14)
                    str("detail", "math symbol")
                }
            }
        }

        // Member completions from type checker
        if (uri != null) {
            val path = uriToPath(uri)
            val module = typeCheckSource(source, path)
            if (module != null) {
                for ((key, type) in module.memberTypeMap) {
                    val memberName = key.substringAfterLast(".")
                    items += buildJson {
                        str("label", memberName)
                        num("kind", 5) // Field
                        str("detail", type.display())
                        str("documentation", "$key: ${type.display()}")
                    }
                }
            }
        }

        return "[${items.joinToString(",")}]"
    }

    // ── textDocument/formatting ───────────────────────────────────────────────

    private fun handleFormatting(params: Map<String, Any?>): String {
        val uri    = params["textDocument"].asMap()?.get("uri").asStr() ?: return "[]"
        val source = store.get(uri) ?: return "[]"
        val path   = uriToPath(uri)

        val tokens = try { Lexer(source).tokenize() } catch (_: LexerError) { return "[]" }
        val file = when (val r = Parser(tokens, path).parse()) {
            is ParseResult.Success   -> r.file
            is ParseResult.Recovered -> r.file
            is ParseResult.Failure   -> return "[]"
        }
        val formatted = nv.compiler.format.Formatter().format(file)
        if (formatted == source) return "[]"

        // Return a single full-text replacement edit
        val lineCount = source.count { it == '\n' }
        val lastLineLen = source.substringAfterLast('\n').length

        val edit = buildJson {
            obj("range") {
                obj("start") { num("line", 0); num("character", 0) }
                obj("end")   { num("line", lineCount); num("character", lastLineLen) }
            }
            str("newText", formatted)
        }
        return "[$edit]"
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun uriToPath(uri: String): String =
        if (uri.startsWith("file://")) uri.removePrefix("file://") else uri

    private fun lineCharToOffset(source: String, line: Int, char: Int): Int {
        var offset = 0
        var currentLine = 0
        for (ch in source) {
            if (currentLine == line) break
            if (ch == '\n') currentLine++
            offset++
        }
        return offset + char
    }

    private fun typeCheckSource(source: String, path: String): nv.compiler.typecheck.TypeCheckedModule? {
        return try {
            val tokens = try { Lexer(source).tokenize() } catch (_: LexerError) { return null }
            val file = when (val r = Parser(tokens, path).parse()) {
                is ParseResult.Success   -> r.file
                is ParseResult.Recovered -> r.file
                is ParseResult.Failure   -> return null
            }
            val module = when (val r = Resolver(path).resolve(file)) {
                is ResolveResult.Success   -> r.module
                is ResolveResult.Recovered -> r.module
                is ResolveResult.Failure   -> return null
            }
            when (val r = TypeChecker(module).check()) {
                is TypeCheckResult.Success   -> r.module
                is TypeCheckResult.Recovered -> r.module
                is TypeCheckResult.Failure   -> null
            }
        } catch (_: Exception) { null }
    }

    private fun resolveSource(source: String, path: String): nv.compiler.resolve.ResolvedModule? {
        return try {
            val tokens = try { Lexer(source).tokenize() } catch (_: LexerError) { return null }
            val file = when (val r = Parser(tokens, path).parse()) {
                is ParseResult.Success   -> r.file
                is ParseResult.Recovered -> r.file
                is ParseResult.Failure   -> return null
            }
            when (val r = Resolver(path).resolve(file)) {
                is ResolveResult.Success   -> r.module
                is ResolveResult.Recovered -> r.module
                is ResolveResult.Failure   -> null
            }
        } catch (_: Exception) { null }
    }
}
