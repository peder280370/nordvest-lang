package nv.tests

import nv.compiler.lexer.Lexer
import nv.compiler.parser.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParserTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun parseOk(src: String): SourceFile {
        val tokens = Lexer(src).tokenize()
        return when (val result = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> result.file
            is ParseResult.Recovered -> result.file
            is ParseResult.Failure   -> fail("Parse failed: ${result.errors.first().message}")
        }
    }

    private fun parseErrors(src: String): List<ParseError> {
        val tokens = Lexer(src).tokenize()
        return when (val result = Parser(tokens, "<test>").parse()) {
            is ParseResult.Success   -> emptyList()
            is ParseResult.Recovered -> result.errors
            is ParseResult.Failure   -> result.errors
        }
    }

    /** Wraps source in a function body and returns the first statement. */
    private fun parseStmt(src: String): Stmt {
        val wrapped = "fn _t()\n    $src\n"
        val file = parseOk(wrapped)
        val fn = file.declarations.first() as FunctionDecl
        return fn.body.first()
    }

    /** Wraps a return expression and extracts it. */
    private fun parseExpr(src: String): Expr {
        val wrapped = "fn _t()\n    → $src\n"
        val file = parseOk(wrapped)
        val fn = file.declarations.first() as FunctionDecl
        val ret = fn.body.first() as ReturnStmt
        return ret.value!!
    }

    private fun parseType(src: String): TypeNode {
        val wrapped = "fn _t(x: $src)\n    → x\n"
        val file = parseOk(wrapped)
        val fn = file.declarations.first() as FunctionDecl
        return fn.params.first().type
    }

    // ── Modules & Imports ─────────────────────────────────────────────────

    @Nested
    inner class Modules {
        @Test fun `module declaration`() {
            val file = parseOk("module myapp.geometry\n")
            assertNotNull(file.module)
            val mod = file.module!!
            assertEquals("myapp.geometry", mod.name.text)
        }

        @Test fun `no module declaration`() {
            val file = parseOk("fn foo()\n    → 1\n")
            assertNull(file.module)
        }

        @Test fun `simple import`() {
            val file = parseOk("import std.math\n")
            assertEquals(1, file.imports.size)
            assertEquals("std.math", file.imports[0].name.text)
            assertFalse(file.imports[0].isPub)
        }

        @Test fun `import with alias`() {
            val file = parseOk("import std.collections as col\n")
            val imp = file.imports[0]
            assertEquals("std.collections", imp.name.text)
            assertEquals("col", imp.alias)
        }

        @Test fun `multiple imports`() {
            val file = parseOk("import std.io\nimport std.math\n")
            assertEquals(2, file.imports.size)
        }
    }

    // ── Functions ─────────────────────────────────────────────────────────

    @Nested
    inner class Functions {
        @Test fun `simple function with return`() {
            val file = parseOk("fn greet()\n    → \"hello\"\n")
            val fn = file.declarations[0] as FunctionDecl
            assertEquals("greet", fn.name)
            assertEquals(Visibility.FILE_PRIVATE, fn.visibility)
            assertTrue(fn.params.isEmpty())
            assertNull(fn.returnType)
        }

        @Test fun `function with typed params and return`() {
            val file = parseOk("fn add(x: int, y: int) → int\n    → x + y\n")
            val fn = file.declarations[0] as FunctionDecl
            assertEquals("add", fn.name)
            assertEquals(2, fn.params.size)
            assertEquals("x", fn.params[0].name)
            assertEquals("int", (fn.params[0].type as NamedTypeNode).name.text)
            assertNotNull(fn.returnType)
        }

        @Test fun `public function`() {
            val file = parseOk("pub fn foo()\n    → nil\n")
            val fn = file.declarations[0] as FunctionDecl
            assertEquals(Visibility.PUBLIC, fn.visibility)
        }

        @Test fun `pub(pkg) function`() {
            val file = parseOk("pub(pkg) fn bar()\n    → nil\n")
            val fn = file.declarations[0] as FunctionDecl
            assertEquals(Visibility.PACKAGE, fn.visibility)
        }

        @Test fun `function with throws`() {
            val file = parseOk("fn open(path: str) throws IOError → str\n    → path\n")
            val fn = file.declarations[0] as FunctionDecl
            assertNotNull(fn.throwsType)
        }

        @Test fun `function with default param`() {
            val file = parseOk("fn greet(name: str = \"World\")\n    → name\n")
            val fn = file.declarations[0] as FunctionDecl
            assertNotNull(fn.params[0].default)
        }

        @Test fun `function with generic type params`() {
            val file = parseOk("fn identity<T>(x: T) → T\n    → x\n")
            val fn = file.declarations[0] as FunctionDecl
            assertEquals(1, fn.typeParams.size)
            assertEquals("T", fn.typeParams[0].name)
        }

        @Test fun `function with bounded type param`() {
            val file = parseOk("fn print<T: Show>(x: T)\n    → nil\n")
            val fn = file.declarations[0] as FunctionDecl
            assertNotNull(fn.typeParams[0].bound)
        }

        @Test fun `function with multiple statements`() {
            val file = parseOk("fn compute() → int\n    let x = 1\n    let y = 2\n    → x + y\n")
            val fn = file.declarations[0] as FunctionDecl
            assertEquals(3, fn.body.size)
        }

        @Test fun `computed property (no parens)`() {
            val file = parseOk("fn area → float\n    → 3.14\n")
            val prop = file.declarations[0] as ComputedPropertyDecl
            assertEquals("area", prop.name)
        }
    }

    // ── Classes ────────────────────────────────────────────────────────────

    @Nested
    inner class Classes {
        @Test fun `simple class`() {
            val file = parseOk("class Animal\n    fn speak()\n        → \"...\"\n")
            val cls = file.declarations[0] as ClassDecl
            assertEquals("Animal", cls.name)
        }

        @Test fun `class with primary constructor`() {
            val file = parseOk("class Point(pub x: float, pub y: float)\n")
            val cls = file.declarations[0] as ClassDecl
            assertEquals(2, cls.constructorParams.size)
            assertEquals("x", cls.constructorParams[0].name)
        }

        @Test fun `class with supertype`() {
            val file = parseOk("class Dog : Animal\n    fn speak()\n        → \"woof\"\n")
            val cls = file.declarations[0] as ClassDecl
            assertEquals(1, cls.superTypes.size)
        }

        @Test fun `class with type params`() {
            val file = parseOk("class Box<T>(pub value: T)\n")
            val cls = file.declarations[0] as ClassDecl
            assertEquals(1, cls.typeParams.size)
            assertEquals("T", cls.typeParams[0].name)
        }

        @Test fun `struct declaration`() {
            val file = parseOk("struct Vec2(x: float, y: float)\n")
            val s = file.declarations[0] as StructDecl
            assertEquals("Vec2", s.name)
            assertEquals(2, s.constructorParams.size)
        }

        @Test fun `record declaration`() {
            val file = parseOk("record Color(r: int, g: int, b: int)\n")
            val r = file.declarations[0] as RecordDecl
            assertEquals("Color", r.name)
        }

        @Test fun `class with where clause`() {
            val file = parseOk("class Sorted<T> where T: Compare\n    let items: [T] = []\n")
            val cls = file.declarations[0] as ClassDecl
            assertEquals(1, cls.whereClause.size)
        }
    }

    // ── Interfaces ────────────────────────────────────────────────────────

    @Nested
    inner class Interfaces {
        @Test fun `simple interface`() {
            val file = parseOk("interface Printable\n    fn print()\n")
            val iface = file.declarations[0] as InterfaceDecl
            assertEquals("Printable", iface.name)
            assertEquals(1, iface.members.size)
            assertTrue(iface.members[0] is FunctionSignatureDecl)
        }

        @Test fun `interface with default method`() {
            val file = parseOk(
                "interface Describable\n    fn description() → str\n        → \"<unknown>\"\n"
            )
            val iface = file.declarations[0] as InterfaceDecl
            assertTrue(iface.members[0] is FunctionDecl)
        }

        @Test fun `interface with associated type`() {
            val file = parseOk("interface Container\n    type Item\n    fn get(i: int) → Item\n")
            val iface = file.declarations[0] as InterfaceDecl
            assertTrue(iface.members[0] is AssocTypeDecl)
        }
    }

    // ── Sealed classes & Enums ────────────────────────────────────────────

    @Nested
    inner class SealedAndEnum {
        @Test fun `sealed class with variants`() {
            val file = parseOk("sealed class Shape\n    Circle(radius: float)\n    Rect(w: float, h: float)\n")
            val s = file.declarations[0] as SealedClassDecl
            assertEquals("Shape", s.name)
            assertEquals(2, s.variants.size)
            assertEquals("Circle", s.variants[0].name)
        }

        @Test fun `enum declaration`() {
            val file = parseOk("enum Color\n    Red\n    Green\n    Blue\n")
            val e = file.declarations[0] as EnumDecl
            assertEquals("Color", e.name)
            assertEquals(3, e.cases.size)
        }

        @Test fun `enum with raw type`() {
            val file = parseOk("enum Status : int\n    Ok = 0\n    Err = 1\n")
            val e = file.declarations[0] as EnumDecl
            assertNotNull(e.rawType)
            assertNotNull(e.cases[0].rawValue)
        }
    }

    // ── Annotations ───────────────────────────────────────────────────────

    @Nested
    inner class Annotations {
        @Test fun `derive annotation`() {
            val file = parseOk("@derive(Show, Eq)\nstruct Pt(x: int, y: int)\n")
            val s = file.declarations[0] as StructDecl
            assertEquals(1, s.annotations.size)
            assertEquals("derive", s.annotations[0].name)
        }

        @Test fun `annotation without args`() {
            val file = parseOk("@lazy\nclass Foo\n    let x: int = 0\n")
            val cls = file.declarations[0] as ClassDecl
            assertEquals(1, cls.annotations.size)
            assertEquals("lazy", cls.annotations[0].name)
        }

        @Test fun `annotation with string arg`() {
            val file = parseOk("@config(\"server\")\nstruct Cfg(port: int = 8080)\n")
            val s = file.declarations[0] as StructDecl
            assertEquals(1, s.annotations.size)
            val arg = s.annotations[0].args[0]
            assertTrue(arg.value is AnnotationStrValue)
            assertEquals("server", (arg.value as AnnotationStrValue).value)
        }
    }

    // ── Type expressions ──────────────────────────────────────────────────

    @Nested
    inner class TypeExpressions {
        @Test fun `simple named type`() {
            val t = parseType("int")
            assertTrue(t is NamedTypeNode)
            assertEquals("int", (t as NamedTypeNode).name.text)
        }

        @Test fun `nullable type`() {
            val t = parseType("str?")
            assertTrue(t is NullableTypeNode)
            assertEquals(1, (t as NullableTypeNode).depth)
        }

        @Test fun `array type`() {
            val t = parseType("[int]")
            assertTrue(t is ArrayTypeNode)
        }

        @Test fun `map type`() {
            val t = parseType("[str: int]")
            assertTrue(t is MapTypeNode)
        }

        @Test fun `generic type`() {
            val t = parseType("List<int>")
            assertTrue(t is NamedTypeNode)
            assertEquals(1, (t as NamedTypeNode).typeArgs.size)
        }

        @Test fun `function type`() {
            val t = parseType("fn(int, str) → bool")
            assertTrue(t is FnTypeNode)
        }

        @Test fun `ptr type`() {
            val t = parseType("ptr<int>")
            assertTrue(t is PtrTypeNode)
        }

        @Test fun `qualified named type`() {
            val t = parseType("std.io.File")
            assertEquals("std.io.File", (t as NamedTypeNode).name.text)
        }
    }

    // ── Literals ──────────────────────────────────────────────────────────

    @Nested
    inner class Literals {
        @Test fun `integer literal`() {
            val e = parseExpr("42")
            assertTrue(e is IntLitExpr)
            assertEquals("42", (e as IntLitExpr).text)
        }

        @Test fun `hex literal`() {
            val e = parseExpr("0xFF")
            assertTrue(e is IntLitExpr)
        }

        @Test fun `float literal`() {
            val e = parseExpr("3.14")
            assertTrue(e is FloatLitExpr)
        }

        @Test fun `boolean true`() {
            val e = parseExpr("true")
            assertEquals(true, (e as BoolLitExpr).value)
        }

        @Test fun `boolean false`() {
            val e = parseExpr("false")
            assertEquals(false, (e as BoolLitExpr).value)
        }

        @Test fun `nil literal`() {
            val e = parseExpr("nil")
            assertTrue(e is NilExpr)
        }

        @Test fun `character literal`() {
            val e = parseExpr("'a'")
            assertTrue(e is CharLitExpr)
        }

        @Test fun `raw string literal`() {
            val e = parseExpr("r\"no escapes\"")
            assertTrue(e is RawStringExpr)
        }

        @Test fun `pi constant`() {
            val e = parseExpr("π")
            assertTrue(e is ConstPiExpr)
        }

        @Test fun `infinity constant`() {
            val e = parseExpr("∞")
            assertTrue(e is ConstInfExpr)
        }

        @Test fun `euler constant e`() {
            val e = parseExpr("e")
            assertTrue(e is ConstEExpr)
        }
    }

    // ── String interpolation ──────────────────────────────────────────────

    @Nested
    inner class StringInterpolation {
        @Test fun `plain string`() {
            val e = parseExpr("\"hello\"")
            assertTrue(e is InterpolatedStringExpr)
            val parts = (e as InterpolatedStringExpr).parts
            assertEquals(1, parts.size)
            assertTrue(parts[0] is StringTextPart)
            assertEquals("hello", (parts[0] as StringTextPart).text)
        }

        @Test fun `string with interpolation`() {
            val e = parseExpr("\"hello {name}\"")
            assertTrue(e is InterpolatedStringExpr)
            val parts = (e as InterpolatedStringExpr).parts
            assertEquals(2, parts.size)
            assertTrue(parts[0] is StringTextPart)
            assertTrue(parts[1] is StringInterpolationPart)
        }

        @Test fun `empty string`() {
            val e = parseExpr("\"\"")
            assertTrue(e is InterpolatedStringExpr)
        }
    }

    // ── Binary expressions ────────────────────────────────────────────────

    @Nested
    inner class BinaryExpressions {
        @Test fun `addition`() {
            val e = parseExpr("1 + 2") as BinaryExpr
            assertEquals(BinaryOp.PLUS, e.op)
        }

        @Test fun `subtraction`() {
            val e = parseExpr("x - y") as BinaryExpr
            assertEquals(BinaryOp.MINUS, e.op)
        }

        @Test fun `multiplication`() {
            val e = parseExpr("a * b") as BinaryExpr
            assertEquals(BinaryOp.STAR, e.op)
        }

        @Test fun `division`() {
            val e = parseExpr("x / 2") as BinaryExpr
            assertEquals(BinaryOp.SLASH, e.op)
        }

        @Test fun `integer division`() {
            val e = parseExpr("n ÷ 2") as BinaryExpr
            assertEquals(BinaryOp.INT_DIV, e.op)
        }

        @Test fun `exponentiation`() {
            val e = parseExpr("x ^ 2") as BinaryExpr
            assertEquals(BinaryOp.POWER, e.op)
        }

        @Test fun `exponentiation is right-associative`() {
            val e = parseExpr("a ^ b ^ c") as BinaryExpr
            assertEquals(BinaryOp.POWER, e.op)
            // right child should also be POWER: a ^ (b ^ c)
            assertTrue(e.right is BinaryExpr)
            assertEquals(BinaryOp.POWER, (e.right as BinaryExpr).op)
        }

        @Test fun `modulo`() {
            val e = parseExpr("x % 3") as BinaryExpr
            assertEquals(BinaryOp.MOD, e.op)
        }

        @Test fun `logical and`() {
            val e = parseExpr("a && b") as BinaryExpr
            assertEquals(BinaryOp.AND, e.op)
        }

        @Test fun `logical or`() {
            val e = parseExpr("a || b") as BinaryExpr
            assertEquals(BinaryOp.OR, e.op)
        }

        @Test fun `logical and unicode`() {
            val e = parseExpr("a ∧ b") as BinaryExpr
            assertEquals(BinaryOp.AND, e.op)
        }

        @Test fun `logical or unicode`() {
            val e = parseExpr("a ∨ b") as BinaryExpr
            assertEquals(BinaryOp.OR, e.op)
        }

        @Test fun `equality`() {
            val e = parseExpr("x == y") as BinaryExpr
            assertEquals(BinaryOp.EQ, e.op)
        }

        @Test fun `inequality`() {
            val e = parseExpr("x != y") as BinaryExpr
            assertEquals(BinaryOp.NEQ, e.op)
        }

        @Test fun `less than`() {
            val e = parseExpr("x < y") as BinaryExpr
            assertEquals(BinaryOp.LT, e.op)
        }

        @Test fun `greater or equal`() {
            val e = parseExpr("x >= y") as BinaryExpr
            assertEquals(BinaryOp.GEQ, e.op)
        }

        @Test fun `null coalesce`() {
            val e = parseExpr("x ?? y") as BinaryExpr
            assertEquals(BinaryOp.NULL_COALESCE, e.op)
        }

        @Test fun `pipeline`() {
            val e = parseExpr("x |> f") as BinaryExpr
            assertEquals(BinaryOp.PIPELINE, e.op)
        }

        @Test fun `bitwise and`() {
            val e = parseExpr("x & y") as BinaryExpr
            assertEquals(BinaryOp.BIT_AND, e.op)
        }

        @Test fun `bitwise or`() {
            val e = parseExpr("x | y") as BinaryExpr
            assertEquals(BinaryOp.BIT_OR, e.op)
        }

        @Test fun `left shift`() {
            val e = parseExpr("x << 2") as BinaryExpr
            assertEquals(BinaryOp.LSHIFT, e.op)
        }

        @Test fun `precedence mul over add`() {
            val e = parseExpr("1 + 2 * 3") as BinaryExpr
            assertEquals(BinaryOp.PLUS, e.op)
            assertTrue(e.right is BinaryExpr)
            assertEquals(BinaryOp.STAR, (e.right as BinaryExpr).op)
        }

        @Test fun `precedence add left-associative`() {
            val e = parseExpr("a + b + c") as BinaryExpr
            assertEquals(BinaryOp.PLUS, e.op)
            assertTrue(e.left is BinaryExpr)
        }
    }

    // ── Unary expressions ─────────────────────────────────────────────────

    @Nested
    inner class UnaryExpressions {
        @Test fun `negation`() {
            val e = parseExpr("-x") as UnaryExpr
            assertEquals(UnaryOp.NEGATE, e.op)
        }

        @Test fun `logical not`() {
            val e = parseExpr("!flag") as UnaryExpr
            assertEquals(UnaryOp.NOT, e.op)
        }

        @Test fun `bitwise not`() {
            val e = parseExpr("~mask") as UnaryExpr
            assertEquals(UnaryOp.BIT_NOT, e.op)
        }
    }

    // ── Postfix expressions ───────────────────────────────────────────────

    @Nested
    inner class PostfixExpressions {
        @Test fun `member access`() {
            val e = parseExpr("obj.field") as MemberAccessExpr
            assertEquals("field", e.member)
        }

        @Test fun `chained member access`() {
            val e = parseExpr("a.b.c") as MemberAccessExpr
            assertEquals("c", e.member)
            assertTrue(e.receiver is MemberAccessExpr)
        }

        @Test fun `safe navigation`() {
            val e = parseExpr("obj?.field") as SafeNavExpr
            assertEquals("field", e.member)
        }

        @Test fun `function call no args`() {
            val e = parseExpr("foo()") as CallExpr
            assertTrue(e.args.isEmpty())
        }

        @Test fun `function call with args`() {
            val e = parseExpr("add(1, 2)") as CallExpr
            assertEquals(2, e.args.size)
        }

        @Test fun `named argument`() {
            val e = parseExpr("greet(name: \"Alice\")") as CallExpr
            assertEquals("name", e.args[0].name)
        }

        @Test fun `index expression`() {
            val e = parseExpr("arr[0]") as IndexExpr
            assertFalse(e.indices[0].isStar)
        }

        @Test fun `result propagation`() {
            val e = parseExpr("parse(s)?") as ResultPropagateExpr
            assertTrue(e.operand is CallExpr)
        }

        @Test fun `force unwrap`() {
            val e = parseExpr("optional!") as ForceUnwrapExpr
        }

        @Test fun `type test (is)`() {
            val e = parseExpr("shape is Circle") as TypeTestExpr
            assertEquals("Circle", (e.type as NamedTypeNode).name.text)
        }

        @Test fun `safe cast (as?)`() {
            val e = parseExpr("x as? Int") as SafeCastExpr
        }

        @Test fun `forced cast (as!)`() {
            val e = parseExpr("x as! Int") as ForceCastExpr
        }
    }

    // ── Lambda expressions ────────────────────────────────────────────────

    @Nested
    inner class LambdaExpressions {
        @Test fun `single-param lambda`() {
            val e = parseExpr("x → x * 2") as LambdaExpr
            assertEquals(1, e.params.size)
            assertEquals("x", e.params[0].name)
            assertTrue(e.body is ExprLambdaBody)
        }

        @Test fun `zero-param lambda`() {
            val e = parseExpr("() → 42") as LambdaExpr
            assertTrue(e.params.isEmpty())
        }

        @Test fun `multi-param lambda`() {
            val e = parseExpr("(x, y) → x + y") as LambdaExpr
            assertEquals(2, e.params.size)
        }

        @Test fun `typed param lambda`() {
            val e = parseExpr("(x: int) → x") as LambdaExpr
            assertNotNull(e.params[0].type)
        }

        @Test fun `lambda used in call`() {
            val e = parseExpr("[1, 2, 3].map(x → x * 2)") as CallExpr
            val arg = e.args[0].expr as LambdaExpr
            assertEquals(1, arg.params.size)
        }

        @Test fun `trailing lambda`() {
            val e = parseExpr("list.filter { x → x > 0 }") as CallExpr
            assertNotNull(e.trailingLambda)
        }
    }

    // ── Inline if expressions ─────────────────────────────────────────────

    @Nested
    inner class InlineIf {
        @Test fun `simple inline if`() {
            val e = parseExpr("if x > 0 then x else -x") as InlineIfExpr
            assertTrue(e.condition is BinaryExpr)
        }

        @Test fun `inline if with else-if`() {
            val e = parseExpr("if x > 0 then 1 else if x < 0 then -1 else 0") as InlineIfExpr
            assertEquals(1, e.elseIfClauses.size)
        }
    }

    // ── Collections & ranges ──────────────────────────────────────────────

    @Nested
    inner class CollectionLiterals {
        @Test fun `array literal`() {
            val e = parseExpr("[1, 2, 3]") as ArrayLiteralExpr
            assertEquals(3, e.elements.size)
        }

        @Test fun `empty array`() {
            val e = parseExpr("[]") as ArrayLiteralExpr
            assertTrue(e.elements.isEmpty())
        }

        @Test fun `map literal`() {
            val e = parseExpr("[\"a\": 1, \"b\": 2]") as MapLiteralExpr
            assertEquals(2, e.entries.size)
        }

        @Test fun `empty map`() {
            val e = parseExpr("[:]")
            assertTrue(e is EmptyMapExpr)
        }

        @Test fun `tuple literal`() {
            val e = parseExpr("(1, 2, 3)") as TupleLiteralExpr
            assertEquals(3, e.elements.size)
        }
    }

    // ── Match expressions ─────────────────────────────────────────────────

    @Nested
    inner class MatchExpressions {
        @Test fun `basic match`() {
            val e = parseExpr(
                "match x\n    0: → \"zero\"\n    _: → \"other\""
            ) as MatchExpr
            assertEquals(2, e.arms.size)
        }

        @Test fun `match with literal pattern`() {
            val e = parseExpr("match n\n    42: → true\n    _: → false") as MatchExpr
            assertTrue(e.arms[0].pattern is LiteralPattern)
        }

        @Test fun `match with wildcard pattern`() {
            val e = parseExpr("match n\n    _: → 0") as MatchExpr
            assertTrue(e.arms[0].pattern is WildcardPattern)
        }

        @Test fun `match with type pattern`() {
            val e = parseExpr("match shape\n    Circle(r): → r\n    _: → 0") as MatchExpr
            assertTrue(e.arms[0].pattern is TypePattern)
        }

        @Test fun `match with guard`() {
            val e = parseExpr("match x\n    n if n > 0: → n\n    _: → 0") as MatchExpr
            assertNotNull(e.arms[0].guard)
        }

        @Test fun `match with or pattern`() {
            val e = parseExpr("match c\n    1 | 2 | 3: → true\n    _: → false") as MatchExpr
            assertTrue(e.arms[0].pattern is OrPattern)
        }

        @Test fun `match with block arm`() {
            val e = parseExpr(
                "match x\n    0:\n        let y = 1\n        → y\n    _: → 0"
            ) as MatchExpr
            assertTrue(e.arms[0].body is BlockMatchArmBody)
        }
    }

    // ── Statements ────────────────────────────────────────────────────────

    @Nested
    inner class Statements {
        @Test fun `let statement`() {
            val s = parseStmt("let x = 42\n") as LetStmt
            assertEquals("x", (s.binding as IdentBinding).name)
            assertNotNull(s.initializer)
        }

        @Test fun `let with type annotation`() {
            val s = parseStmt("let x: int = 42\n") as LetStmt
            assertNotNull(s.typeAnnotation)
        }

        @Test fun `var statement`() {
            val s = parseStmt("var count = 0\n") as VarStmt
            assertEquals("count", (s.binding as IdentBinding).name)
        }

        @Test fun `return with value`() {
            val s = parseStmt("→ 42\n") as ReturnStmt
            assertNotNull(s.value)
        }

        @Test fun `return keyword`() {
            val s = parseStmt("return 42\n") as ReturnStmt
            assertNotNull(s.value)
        }

        @Test fun `if statement`() {
            val s = parseStmt("if x > 0\n    → x\n") as IfStmt
            assertTrue(s.thenBody.isNotEmpty())
            assertNull(s.elseBody)
        }

        @Test fun `if else statement`() {
            val s = parseStmt("if x > 0\n    → x\nelse\n    → -x\n") as IfStmt
            assertNotNull(s.elseBody)
        }

        @Test fun `for loop`() {
            val s = parseStmt("for i in items\n    → i\n") as ForStmt
            assertEquals("i", (s.binding as IdentBinding).name)
        }

        @Test fun `for loop with unicode in`() {
            val s = parseStmt("for x ∈ collection\n    → x\n") as ForStmt
            assertEquals("x", (s.binding as IdentBinding).name)
        }

        @Test fun `while loop`() {
            val s = parseStmt("while running\n    → nil\n") as WhileStmt
        }

        @Test fun `labeled for loop`() {
            val s = parseStmt("@outer for i in items\n    break @outer\n") as ForStmt
            assertEquals("outer", s.label)
        }

        @Test fun `break statement`() {
            val s = parseStmt("break\n") as BreakStmt
            assertNull(s.label)
        }

        @Test fun `continue with label`() {
            val s = parseStmt("continue @loop\n") as ContinueStmt
            assertEquals("loop", s.label)
        }

        @Test fun `defer statement`() {
            val s = parseStmt("defer close()\n") as DeferStmt
            assertTrue(s.body is SingleStmtDefer)
        }

        @Test fun `try-catch`() {
            val s = parseStmt("try\n    → risky()\ncatch e: IOError\n    → nil\n") as TryCatchStmt
            assertEquals(1, s.catchClauses.size)
        }

        @Test fun `throw statement`() {
            val s = parseStmt("throw IOError(\"msg\")\n") as ThrowStmt
        }

        @Test fun `yield statement`() {
            val s = parseStmt("yield 42\n") as YieldStmt
        }

        @Test fun `unsafe block`() {
            val s = parseStmt("unsafe {\n    → ptr\n}\n") as UnsafeBlock
        }

        @Test fun `assign statement`() {
            val s = parseStmt("x = 42\n") as AssignStmt
            assertEquals(AssignOp.ASSIGN, s.op)
        }

        @Test fun `compound assign`() {
            val s = parseStmt("x += 1\n") as AssignStmt
            assertEquals(AssignOp.PLUS_ASSIGN, s.op)
        }

        @Test fun `expression statement`() {
            val s = parseStmt("foo()\n") as ExprStmt
            assertTrue(s.expr is CallExpr)
        }
    }

    // ── Quantifiers ───────────────────────────────────────────────────────

    @Nested
    inner class Quantifiers {
        @Test fun `forall`() {
            val e = parseExpr("∀ x ∈ values: x > 0") as QuantifierExpr
            assertEquals(QuantifierOp.FORALL, e.op)
        }

        @Test fun `exists`() {
            val e = parseExpr("∃ x ∈ list: x == 0") as QuantifierExpr
            assertEquals(QuantifierOp.EXISTS, e.op)
        }

        @Test fun `sum`() {
            val e = parseExpr("∑ x ∈ values: x") as QuantifierExpr
            assertEquals(QuantifierOp.SUM, e.op)
        }
    }

    // ── List comprehensions ───────────────────────────────────────────────

    @Nested
    inner class ListComprehensions {
        @Test fun `basic comprehension`() {
            val e = parseExpr("[x * 2 for x in nums]") as ListComprehensionExpr
            assertEquals(1, e.generators.size)
        }

        @Test fun `comprehension with guard`() {
            val e = parseExpr("[x for x in nums if x > 0]") as ListComprehensionExpr
            assertNotNull(e.guard)
        }
    }

    // ── Extensions & Type aliases ─────────────────────────────────────────

    @Nested
    inner class ExtensionsAndAliases {
        @Test fun `extension declaration`() {
            val file = parseOk("extend Int\n    fn double() → Int\n        → self * 2\n")
            val ext = file.declarations[0] as ExtendDecl
            assertEquals("Int", ext.target.name.text)
        }

        @Test fun `type alias`() {
            val file = parseOk("type Seconds = float\n")
            val alias = file.declarations[0] as TypeAliasDecl
            assertEquals("Seconds", alias.name)
        }

        @Test fun `type alias with type param`() {
            val file = parseOk("type Pair<A, B> = (A, B)\n")
            val alias = file.declarations[0] as TypeAliasDecl
            assertEquals(2, alias.typeParams.size)
        }
    }

    // ── Conditional compilation ───────────────────────────────────────────

    @Nested
    inner class ConditionalCompilation {
        @Test fun `platform conditional`() {
            val file = parseOk("@if(platform == \"linux\")\n    let os = \"linux\"\n")
            val cc = file.declarations[0] as ConditionalCompilationBlock
            assertTrue(cc.predicate is CCPlatform)
            assertEquals(1, cc.thenDecls.size)
        }

        @Test fun `conditional with else`() {
            val file = parseOk(
                "@if(debug)\n    let log = true\n@else\n    let log = false\n"
            )
            val cc = file.declarations[0] as ConditionalCompilationBlock
            assertTrue(cc.predicate is CCDebug)
            assertEquals(1, cc.elseDecls.size)
        }
    }

    // ── Error recovery ────────────────────────────────────────────────────

    @Nested
    inner class ErrorRecovery {
        @Test fun `parse reports error for unexpected token`() {
            val errs = parseErrors("fn foo(\n    → 1\n")
            assertTrue(errs.isNotEmpty())
        }

        @Test fun `continues after error`() {
            // Second function should still be parsed even if first has an error
            val tokens = Lexer("fn broken(\nfn ok()\n    → 1\n").tokenize()
            val result = Parser(tokens, "<test>").parse()
            assertTrue(result is ParseResult.Recovered || result is ParseResult.Failure)
        }
    }
}
