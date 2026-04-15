package nv.tests

import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Tests for @config and @env annotations.
 *
 *  - Parser tests:     @env on constructor params is parsed and stored in ConstructorParam.annotations
 *  - Type-check tests: @config registers `TypeName.configLoad` with correct Result<T> return type
 *  - IR structure:     generated nv_TypeName_config_load function is present with correct shape
 *  - Integration:      compile + run with clang, set env vars, verify loaded values
 */
class ConfigAnnotationTest : NvCompilerTestBase() {

    // ── Parser tests ──────────────────────────────────────────────────────────

    @Test fun `@config annotation parsed on struct`() {
        val file = parse("""
            module test
            @config("server")
            struct ServerConfig(host: str = "0.0.0.0", port: int = 8080)
        """)
        val decl = file.declarations.filterIsInstance<StructDecl>().first()
        assertEquals("ServerConfig", decl.name)
        assertEquals(1, decl.annotations.size)
        assertEquals("config", decl.annotations[0].name)
        assertEquals("server", (decl.annotations[0].args[0].value as AnnotationStrValue).value)
    }

    @Test fun `@env annotation parsed on constructor param`() {
        val file = parse("""
            module test
            @config("db")
            struct DbConfig(@env("DATABASE_URL") url: str)
        """)
        val decl = file.declarations.filterIsInstance<StructDecl>().first()
        val urlParam = decl.constructorParams.first { it.name == "url" }
        assertEquals(1, urlParam.annotations.size)
        assertEquals("env", urlParam.annotations[0].name)
        assertEquals("DATABASE_URL", (urlParam.annotations[0].args[0].value as AnnotationStrValue).value)
    }

    @Test fun `@env with sensitive flag parsed`() {
        val file = parse("""
            module test
            @config("db")
            struct DbConfig(@env("DB_PASS", sensitive: true) password: str? = nil)
        """)
        val decl = file.declarations.filterIsInstance<StructDecl>().first()
        val passParam = decl.constructorParams.first { it.name == "password" }
        val envAnno = passParam.annotations.first { it.name == "env" }
        assertEquals("DB_PASS", (envAnno.args[0].value as AnnotationStrValue).value)
        val sensitiveArg = envAnno.args.firstOrNull { it.name == "sensitive" }
        assertNotNull(sensitiveArg)
        assertEquals(true, (sensitiveArg!!.value as AnnotationBoolValue).value)
    }

    @Test fun `multiple @env params in one struct`() {
        val file = parse("""
            module test
            @config("app")
            struct AppConfig(
                @env("APP_HOST") host: str = "localhost",
                @env("APP_PORT") port: int = 3000,
                debug: bool = false
            )
        """)
        val decl = file.declarations.filterIsInstance<StructDecl>().first()
        assertEquals(3, decl.constructorParams.size)
        assertEquals("env", decl.constructorParams[0].annotations.first().name)
        assertEquals("env", decl.constructorParams[1].annotations.first().name)
        assertEquals(0, decl.constructorParams[2].annotations.size)
    }

    // ── IR structure tests ────────────────────────────────────────────────────

    @Test fun `@config emits config_load function`() {
        val ir = compileOk("""
            module test
            @config("server")
            struct ServerConfig(host: str = "0.0.0.0", port: int = 8080)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("define i8* @nv_ServerConfig_config_load()"),
            "Missing config_load function in IR")
    }

    @Test fun `@config load reads auto-derived env var names`() {
        val ir = compileOk("""
            module test
            @config("server")
            struct ServerConfig(host: str = "0.0.0.0", port: int = 8080)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("SERVER_HOST"), "Expected SERVER_HOST env var name in IR")
        assertTrue(ir.contains("SERVER_PORT"), "Expected SERVER_PORT env var name in IR")
    }

    @Test fun `@env overrides env var name`() {
        val ir = compileOk("""
            module test
            @config("db")
            struct DbConfig(@env("DATABASE_URL") url: str)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("DATABASE_URL"), "Expected DATABASE_URL env var name in IR")
        assertFalse(ir.contains("DB_URL"), "Should not contain auto-derived DB_URL")
    }

    @Test fun `@config required field emits error return`() {
        val ir = compileOk("""
            module test
            @config("db")
            struct DbConfig(url: str)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("ConfigError: missing required field 'url'"),
            "Missing ConfigError message for required field")
        assertTrue(ir.contains("@nv_Err"), "Missing nv_Err call for required field")
    }

    @Test fun `@config optional field uses phi for default`() {
        val ir = compileOk("""
            module test
            @config("app")
            struct AppConfig(port: int = 8080)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("phi i64"), "Expected phi instruction for optional int field")
    }

    @Test fun `@config nullable field treated as optional`() {
        val ir = compileOk("""
            module test
            @config("app")
            struct AppConfig(token: str? = nil)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("phi i8*"), "Expected phi instruction for nullable str field")
    }

    @Test fun `configLoad callable as method on type name`() {
        val ir = compileOk("""
            module test
            @config("server")
            struct ServerConfig(host: str = "localhost")
            fn main()
                let r = ServerConfig.configLoad()
                println("ok")
        """)
        assertTrue(ir.contains("call i8* @nv_ServerConfig_config_load()"),
            "Expected call to nv_ServerConfig_config_load")
    }

    @Test fun `camelCase field produces UPPER_SNAKE env var`() {
        val ir = compileOk("""
            module test
            @config("app")
            struct AppConfig(httpPort: int = 8080)
            fn main()
                println("ok")
        """)
        assertTrue(ir.contains("APP_HTTP_PORT"), "Expected APP_HTTP_PORT for httpPort field")
    }

    // ── Integration tests ─────────────────────────────────────────────────────

    @Test fun `@config loads str field from env var with default`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @config("server")
            struct ServerConfig(host: str = "localhost")
            fn main()
                let r = ServerConfig.configLoad()
                match r
                    Ok(cfg):  → println(cfg.host)
                    Err(msg): → println("error")
        """)
        // Without env var — should use default
        assertEquals("localhost", runProgram(ir))
        // With env var set
        assertEquals("example.com", runProgram(ir, mapOf("SERVER_HOST" to "example.com")))
    }

    @Test fun `@config loads int field from env var with default`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @config("server")
            struct ServerConfig(port: int = 8080)
            fn main()
                let r = ServerConfig.configLoad()
                match r
                    Ok(cfg):  → println(cfg.port)
                    Err(msg): → println("error")
        """)
        assertEquals("8080", runProgram(ir))
        assertEquals("9000", runProgram(ir, mapOf("SERVER_PORT" to "9000")))
    }

    @Test fun `@config loads bool field from env var`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @config("app")
            struct AppConfig(debug: bool = false)
            fn main()
                let r = AppConfig.configLoad()
                match r
                    Ok(cfg):  → println(cfg.debug)
                    Err(msg): → println("error")
        """)
        assertEquals("false", runProgram(ir))
        assertEquals("true", runProgram(ir, mapOf("APP_DEBUG" to "true")))
    }

    @Test fun `@config required field returns Err when env var missing`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @config("db")
            struct DbConfig(url: str)
            fn main()
                let r = DbConfig.configLoad()
                match r
                    Ok(_):    → println("ok")
                    Err(msg): → println("missing")
        """)
        assertEquals("missing", runProgram(ir))
        assertEquals("ok", runProgram(ir, mapOf("DB_URL" to "postgres://localhost/mydb")))
    }

    @Test fun `@env explicit var name used instead of auto-derived`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @config("db")
            struct DbConfig(@env("DATABASE_URL") url: str = "sqlite://default")
            fn main()
                let r = DbConfig.configLoad()
                match r
                    Ok(cfg):  → println(cfg.url)
                    Err(msg): → println("error")
        """)
        assertEquals("sqlite://default", runProgram(ir))
        assertEquals("postgres://prod/db", runProgram(ir, mapOf("DATABASE_URL" to "postgres://prod/db")))
    }

    @Test fun `@config multiple fields all loaded correctly`() {
        assumeTrue(clangAvailable(), "clang not available")
        val ir = compileOk("""
            module test
            @config("server")
            struct ServerConfig(host: str = "localhost", port: int = 8080, tls: bool = false)
            fn main()
                let r = ServerConfig.configLoad()
                match r
                    Ok(cfg):
                        → println(cfg.host)
                    _: → println("error")
        """)
        assertEquals("myhost", runProgram(ir, mapOf("SERVER_HOST" to "myhost")))
        assertEquals("localhost", runProgram(ir))
    }
}
