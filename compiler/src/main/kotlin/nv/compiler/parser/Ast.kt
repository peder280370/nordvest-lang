package nv.compiler.parser

import nv.compiler.lexer.SourceSpan

sealed class Node { abstract val span: SourceSpan }

// ─── Top-level ────────────────────────────────────────────────────────────

data class SourceFile(
    override val span: SourceSpan,
    val sourcePath: String,
    val module: ModuleDecl?,
    val imports: List<ImportDecl>,
    val declarations: List<Decl>,
) : Node()

// ─── Shared helper types ──────────────────────────────────────────────────

data class QualifiedName(
    override val span: SourceSpan,
    val parts: List<String>,
) : Node() {
    val text: String get() = parts.joinToString(".")
}

enum class Visibility { PUBLIC, PACKAGE, FILE_PRIVATE }

data class TypeParam(val name: String, val bound: TypeNode?, val span: SourceSpan)
data class WhereConstraint(val typeName: QualifiedName, val bound: TypeNode, val span: SourceSpan)
data class Param(val name: String, val type: TypeNode, val default: Expr?, val isVariadic: Boolean, val span: SourceSpan)
data class ConstructorParam(val visibility: Visibility, val name: String, val type: TypeNode, val default: Expr?, val span: SourceSpan)

sealed class Binding : Node()
data class IdentBinding(override val span: SourceSpan, val name: String) : Binding()
data class TupleBinding(override val span: SourceSpan, val names: List<String>) : Binding()

data class Annotation(override val span: SourceSpan, val name: String, val args: List<AnnotationArg>) : Node()
data class AnnotationArg(val name: String?, val value: AnnotationArgValue, val span: SourceSpan)
sealed class AnnotationArgValue : Node()
data class AnnotationStrValue(override val span: SourceSpan, val value: String) : AnnotationArgValue()
data class AnnotationBoolValue(override val span: SourceSpan, val value: Boolean) : AnnotationArgValue()
data class AnnotationIdentValue(override val span: SourceSpan, val name: QualifiedName) : AnnotationArgValue()
data class AnnotationIntValue(override val span: SourceSpan, val text: String) : AnnotationArgValue()

// ─── Declarations ─────────────────────────────────────────────────────────

sealed class Decl : Node()

data class ModuleDecl(override val span: SourceSpan, val name: QualifiedName) : Decl()
data class ImportDecl(override val span: SourceSpan, val isPub: Boolean, val name: QualifiedName, val alias: String?) : Decl()

data class FunctionDecl(
    override val span: SourceSpan,
    val docComment: String?,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val isAsync: Boolean,
    val name: String,
    val typeParams: List<TypeParam>,
    val params: List<Param>,
    val returnType: TypeNode?,
    val throwsType: TypeNode?,
    val body: List<Stmt>,
) : Decl()

data class FunctionSignatureDecl(
    override val span: SourceSpan,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val isAsync: Boolean,
    val name: String,
    val typeParams: List<TypeParam>,
    val params: List<Param>,
    val returnType: TypeNode?,
    val throwsType: TypeNode?,
) : Decl()

data class SetterBlock(override val span: SourceSpan, val paramName: String, val body: List<Stmt>) : Node()

data class ComputedPropertyDecl(
    override val span: SourceSpan,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val name: String,
    val returnType: TypeNode,
    val getter: List<Stmt>,
    val setter: SetterBlock?,
) : Decl()

data class SubscriptDecl(
    override val span: SourceSpan,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val params: List<Param>,
    val returnType: TypeNode?,
    val isSetter: Boolean,
    val setterValueParam: Param?,
    val body: List<Stmt>,
) : Decl()

data class ClassDecl(
    override val span: SourceSpan,
    val docComment: String?,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val name: String,
    val typeParams: List<TypeParam>,
    val constructorParams: List<ConstructorParam>,
    val superTypes: List<TypeNode>,
    val whereClause: List<WhereConstraint>,
    val members: List<Decl>,
) : Decl()

data class StructDecl(
    override val span: SourceSpan,
    val docComment: String?,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val name: String,
    val typeParams: List<TypeParam>,
    val constructorParams: List<ConstructorParam>,
    val superTypes: List<TypeNode>,
    val whereClause: List<WhereConstraint>,
    val members: List<Decl>,
) : Decl()

data class RecordDecl(
    override val span: SourceSpan,
    val docComment: String?,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val name: String,
    val typeParams: List<TypeParam>,
    val constructorParams: List<ConstructorParam>,
    val superTypes: List<TypeNode>,
    val whereClause: List<WhereConstraint>,
    val members: List<Decl>,
) : Decl()

data class InterfaceDecl(
    override val span: SourceSpan,
    val docComment: String?,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val name: String,
    val typeParams: List<TypeParam>,
    val superTypes: List<TypeNode>,
    val whereClause: List<WhereConstraint>,
    val members: List<Decl>,
) : Decl()

data class SealedVariant(override val span: SourceSpan, val name: String, val params: List<ConstructorParam>) : Node()

data class SealedClassDecl(
    override val span: SourceSpan,
    val docComment: String?,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val name: String,
    val typeParams: List<TypeParam>,
    val superTypes: List<TypeNode>,
    val variants: List<SealedVariant>,
) : Decl()

data class EnumCase(override val span: SourceSpan, val name: String, val rawValue: Expr?, val associatedParams: List<ConstructorParam>) : Node()

data class EnumDecl(
    override val span: SourceSpan,
    val docComment: String?,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val name: String,
    val rawType: TypeNode?,
    val cases: List<EnumCase>,
) : Decl()

data class ExtendDecl(
    override val span: SourceSpan,
    val target: NamedTypeNode,
    val conformances: List<TypeNode>,
    val whereClause: List<WhereConstraint>,
    val members: List<Decl>,
) : Decl()

data class TypeAliasDecl(
    override val span: SourceSpan,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val name: String,
    val typeParams: List<TypeParam>,
    val aliasedType: TypeNode,
) : Decl()

data class LetDecl(
    override val span: SourceSpan,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val isWeak: Boolean,
    val name: String,
    val typeAnnotation: TypeNode?,
    val initializer: Expr?,
) : Decl()

data class VarDecl(
    override val span: SourceSpan,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val isWeak: Boolean,
    val name: String,
    val typeAnnotation: TypeNode?,
    val initializer: Expr?,
) : Decl()

data class FieldDecl(
    override val span: SourceSpan,
    val annotations: List<Annotation>,
    val visibility: Visibility,
    val isWeak: Boolean,
    val isUnowned: Boolean,
    val isMutable: Boolean,
    val name: String,
    val typeAnnotation: TypeNode,
    val initializer: Expr?,
) : Decl()

data class AssocTypeDecl(override val span: SourceSpan, val name: String, val bound: TypeNode?) : Decl()
data class InitBlock(override val span: SourceSpan, val body: List<Stmt>) : Decl()

sealed class CCPredicate : Node()
data class CCPlatform(override val span: SourceSpan, val platform: String) : CCPredicate()
data class CCArch(override val span: SourceSpan, val arch: String) : CCPredicate()
data class CCDebug(override val span: SourceSpan) : CCPredicate()
data class CCRelease(override val span: SourceSpan) : CCPredicate()
data class CCFeature(override val span: SourceSpan, val feature: String) : CCPredicate()

data class ConditionalCompilationBlock(
    override val span: SourceSpan,
    val predicate: CCPredicate,
    val thenDecls: List<Decl>,
    val elseDecls: List<Decl>,
) : Decl()

// ─── Types ────────────────────────────────────────────────────────────────

sealed class TypeNode : Node()

data class NullableTypeNode(override val span: SourceSpan, val inner: TypeNode, val depth: Int) : TypeNode()

data class NamedTypeNode(
    override val span: SourceSpan,
    val name: QualifiedName,
    val typeArgs: List<TypeNode>,
) : TypeNode()

data class ArrayTypeNode(override val span: SourceSpan, val element: TypeNode) : TypeNode()
data class MatrixTypeNode(override val span: SourceSpan, val element: TypeNode) : TypeNode()
data class MapTypeNode(override val span: SourceSpan, val key: TypeNode, val value: TypeNode) : TypeNode()

data class TupleTypeField(val name: String?, val type: TypeNode)
data class TupleTypeNode(override val span: SourceSpan, val fields: List<TupleTypeField>) : TypeNode()

data class FnTypeNode(override val span: SourceSpan, val paramTypes: List<TypeNode>, val returnType: TypeNode) : TypeNode()
data class PtrTypeNode(override val span: SourceSpan, val inner: TypeNode) : TypeNode()

// ─── Expressions ─────────────────────────────────────────────────────────

sealed class Expr : Node()

// Literals
data class IntLitExpr(override val span: SourceSpan, val text: String) : Expr()
data class FloatLitExpr(override val span: SourceSpan, val text: String) : Expr()
data class BoolLitExpr(override val span: SourceSpan, val value: Boolean) : Expr()
data class NilExpr(override val span: SourceSpan) : Expr()
data class CharLitExpr(override val span: SourceSpan, val text: String) : Expr()
data class RawStringExpr(override val span: SourceSpan, val text: String) : Expr()
data class ConstPiExpr(override val span: SourceSpan) : Expr()
data class ConstInfExpr(override val span: SourceSpan) : Expr()
data class ConstEExpr(override val span: SourceSpan) : Expr()

// Interpolated string
sealed class StringPart : Node()
data class StringTextPart(override val span: SourceSpan, val text: String) : StringPart()
data class StringInterpolationPart(override val span: SourceSpan, val expr: Expr, val formatSpec: String?) : StringPart()
data class InterpolatedStringExpr(override val span: SourceSpan, val parts: List<StringPart>) : Expr()

// Name/reference
data class IdentExpr(override val span: SourceSpan, val name: String) : Expr()
data class WildcardExpr(override val span: SourceSpan) : Expr()
data class ParenExpr(override val span: SourceSpan, val inner: Expr) : Expr()

// Lambda
data class LambdaParam(val name: String, val type: TypeNode?, val span: SourceSpan)
data class CaptureItem(val isCopy: Boolean, val name: String, val span: SourceSpan)
sealed class LambdaBody : Node()
data class ExprLambdaBody(override val span: SourceSpan, val expr: Expr) : LambdaBody()
data class BlockLambdaBody(override val span: SourceSpan, val stmts: List<Stmt>) : LambdaBody()
data class LambdaExpr(override val span: SourceSpan, val captureList: List<CaptureItem>, val params: List<LambdaParam>, val body: LambdaBody) : Expr()

// Binary/unary operators
enum class BinaryOp {
    PLUS, MINUS, STAR, SLASH, INT_DIV, MOD, POWER,
    BIT_AND, BIT_OR, BIT_XOR, LSHIFT, RSHIFT,
    AND, OR,
    EQ, NEQ, LT, GT, LEQ, GEQ,
    PIPELINE,
    NULL_COALESCE,
}

enum class UnaryOp { NEGATE, NOT, BIT_NOT }

data class BinaryExpr(override val span: SourceSpan, val op: BinaryOp, val left: Expr, val right: Expr) : Expr()
data class UnaryExpr(override val span: SourceSpan, val op: UnaryOp, val operand: Expr) : Expr()

// Postfix
data class MemberAccessExpr(override val span: SourceSpan, val receiver: Expr, val member: String) : Expr()
data class SafeNavExpr(override val span: SourceSpan, val receiver: Expr, val member: String) : Expr()
data class CallArg(val name: String?, val expr: Expr, val span: SourceSpan)
data class CallExpr(override val span: SourceSpan, val callee: Expr, val args: List<CallArg>, val trailingLambda: LambdaExpr?) : Expr()
data class IndexArg(val isStar: Boolean, val expr: Expr?, val span: SourceSpan)
data class IndexExpr(override val span: SourceSpan, val receiver: Expr, val indices: List<IndexArg>) : Expr()
data class ResultPropagateExpr(override val span: SourceSpan, val operand: Expr) : Expr()
data class ForceUnwrapExpr(override val span: SourceSpan, val operand: Expr) : Expr()
data class TypeTestExpr(override val span: SourceSpan, val operand: Expr, val type: TypeNode) : Expr()
data class SafeCastExpr(override val span: SourceSpan, val operand: Expr, val type: TypeNode) : Expr()
data class ForceCastExpr(override val span: SourceSpan, val operand: Expr, val type: TypeNode) : Expr()

// Control
data class InlineElseIfClause(val condition: Expr, val thenExpr: Expr, val span: SourceSpan)
data class InlineIfExpr(
    override val span: SourceSpan,
    val condition: Expr,
    val thenExpr: Expr,
    val elseIfClauses: List<InlineElseIfClause>,
    val elseExpr: Expr,
) : Expr()

// Collections
data class ArrayLiteralExpr(override val span: SourceSpan, val elements: List<Expr>) : Expr()
data class MapEntry(val key: Expr, val value: Expr, val span: SourceSpan)
data class MapLiteralExpr(override val span: SourceSpan, val entries: List<MapEntry>) : Expr()
data class EmptyMapExpr(override val span: SourceSpan) : Expr()
data class TupleLiteralExpr(override val span: SourceSpan, val elements: List<Expr>) : Expr()

enum class RangeKind { CLOSED, HALF_OPEN_LEFT, OPEN, HALF_OPEN_RIGHT }
data class RangeExpr(override val span: SourceSpan, val kind: RangeKind, val start: Expr, val end: Expr) : Expr()

// Comprehension
data class ComprehensionGenerator(val binding: Binding, val iterable: Expr, val span: SourceSpan)
data class ListComprehensionExpr(
    override val span: SourceSpan,
    val body: Expr,
    val generators: List<ComprehensionGenerator>,
    val guard: Expr?,
) : Expr()

// Quantifiers
enum class QuantifierOp { FORALL, EXISTS, SUM, PRODUCT }
sealed class QuantifierBody : Node()
data class InlineQuantifierBody(override val span: SourceSpan, val expr: Expr) : QuantifierBody()
data class BlockQuantifierBody(override val span: SourceSpan, val stmts: List<Stmt>) : QuantifierBody()
data class BareIterableBody(override val span: SourceSpan, val iterable: Expr) : QuantifierBody()
data class QuantifierExpr(
    override val span: SourceSpan,
    val op: QuantifierOp,
    val binding: Binding?,
    val iterable: Expr?,
    val body: QuantifierBody,
) : Expr()

// Match
sealed class MatchArmBody : Node()
data class ExprMatchArmBody(override val span: SourceSpan, val expr: Expr) : MatchArmBody()
data class BlockMatchArmBody(override val span: SourceSpan, val stmts: List<Stmt>) : MatchArmBody()
data class MatchArm(override val span: SourceSpan, val pattern: Pattern, val guard: Expr?, val body: MatchArmBody) : Node()
data class MatchExpr(override val span: SourceSpan, val subject: Expr, val arms: List<MatchArm>) : Expr()

// ─── Statements ───────────────────────────────────────────────────────────

sealed class Stmt : Node()

data class LetStmt(
    override val span: SourceSpan,
    val isWeak: Boolean,
    val binding: Binding,
    val typeAnnotation: TypeNode?,
    val initializer: Expr?,
) : Stmt()

data class VarStmt(
    override val span: SourceSpan,
    val isWeak: Boolean,
    val binding: Binding,
    val typeAnnotation: TypeNode?,
    val initializer: Expr?,
) : Stmt()

data class ReturnStmt(override val span: SourceSpan, val value: Expr?) : Stmt()

data class IfLetBinding(val name: String, val typeAnnotation: TypeNode?, val span: SourceSpan)
data class ElseIfClause(val letBinding: IfLetBinding?, val condition: Expr, val body: List<Stmt>, val span: SourceSpan)

data class IfStmt(
    override val span: SourceSpan,
    val letBinding: IfLetBinding?,
    val condition: Expr,
    val thenBody: List<Stmt>,
    val elseIfClauses: List<ElseIfClause>,
    val elseBody: List<Stmt>?,
) : Stmt()

data class GuardLetStmt(
    override val span: SourceSpan,
    val name: String,
    val typeAnnotation: TypeNode?,
    val value: Expr,
    val elseBody: List<Stmt>,
) : Stmt()

data class ForStmt(
    override val span: SourceSpan,
    val label: String?,
    val binding: Binding,
    val iterable: Expr,
    val body: List<Stmt>,
) : Stmt()

data class WhileStmt(
    override val span: SourceSpan,
    val label: String?,
    val condition: Expr,
    val body: List<Stmt>,
) : Stmt()

data class MatchStmt(override val span: SourceSpan, val expr: MatchExpr) : Stmt()

sealed class DeferBody : Node()
data class SingleStmtDefer(override val span: SourceSpan, val stmt: Stmt) : DeferBody()
data class BlockDefer(override val span: SourceSpan, val stmts: List<Stmt>) : DeferBody()
data class DeferStmt(override val span: SourceSpan, val body: DeferBody) : Stmt()

data class CatchClause(override val span: SourceSpan, val binding: String, val type: TypeNode?, val body: List<Stmt>) : Node()
data class TryCatchStmt(
    override val span: SourceSpan,
    val tryBody: List<Stmt>,
    val catchClauses: List<CatchClause>,
    val finallyBody: List<Stmt>?,
) : Stmt()

data class ThrowStmt(override val span: SourceSpan, val expr: Expr) : Stmt()

sealed class GoBody : Node()
data class GoBlockBody(override val span: SourceSpan, val stmts: List<Stmt>) : GoBody()
data class GoExprBody(override val span: SourceSpan, val expr: Expr) : GoBody()
data class GoStmt(override val span: SourceSpan, val captureList: List<CaptureItem>, val body: GoBody) : Stmt()

data class SpawnStmt(override val span: SourceSpan, val expr: Expr) : Stmt()

data class AwaitExpr(override val span: SourceSpan, val operand: Expr) : Expr()
data class SpawnExpr(override val span: SourceSpan, val expr: Expr) : Expr()

enum class DurationUnit { MS, S, M, H }
data class DurationLit(val amount: Long, val unit: DurationUnit, val span: SourceSpan)

sealed class SelectArm : Node()
data class ReceiveSelectArm(override val span: SourceSpan, val binding: String?, val channel: Expr, val body: List<Stmt>) : SelectArm()
data class AfterSelectArm(override val span: SourceSpan, val duration: DurationLit, val body: List<Stmt>) : SelectArm()
data class DefaultSelectArm(override val span: SourceSpan, val body: List<Stmt>) : SelectArm()
data class SelectStmt(override val span: SourceSpan, val arms: List<SelectArm>) : Stmt()

data class BreakStmt(override val span: SourceSpan, val label: String?) : Stmt()
data class ContinueStmt(override val span: SourceSpan, val label: String?) : Stmt()
data class YieldStmt(override val span: SourceSpan, val expr: Expr) : Stmt()
data class UnsafeBlock(override val span: SourceSpan, val stmts: List<Stmt>) : Stmt()

enum class AssignOp {
    ASSIGN,
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, INT_DIV_ASSIGN, MOD_ASSIGN,
    AMP_ASSIGN, PIPE_ASSIGN, XOR_ASSIGN, LSHIFT_ASSIGN, RSHIFT_ASSIGN,
}

data class AssignStmt(override val span: SourceSpan, val target: Expr, val op: AssignOp, val value: Expr) : Stmt()
data class ExprStmt(override val span: SourceSpan, val expr: Expr) : Stmt()

// ─── Patterns ────────────────────────────────────────────────────────────

sealed class Pattern : Node()

data class OrPattern(override val span: SourceSpan, val alternatives: List<Pattern>) : Pattern()
data class LiteralPattern(override val span: SourceSpan, val expr: Expr) : Pattern()
data class RangePattern(override val span: SourceSpan, val range: RangeExpr) : Pattern()
data class NilPattern(override val span: SourceSpan) : Pattern()
data class WildcardPattern(override val span: SourceSpan) : Pattern()
data class BindingPattern(override val span: SourceSpan, val name: String) : Pattern()

sealed class TypePatternArgs : Node()
data class PositionalTypePatternArgs(override val span: SourceSpan, val patterns: List<Pattern>) : TypePatternArgs()
data class NamedTypePatternArgs(override val span: SourceSpan, val fields: List<PatternField>) : TypePatternArgs()
data class NoTypePatternArgs(override val span: SourceSpan) : TypePatternArgs()

data class PatternField(val name: String, val pattern: Pattern, val span: SourceSpan)
data class TypePattern(override val span: SourceSpan, val typeName: QualifiedName, val args: TypePatternArgs) : Pattern()
data class TuplePattern(override val span: SourceSpan, val elements: List<Pattern>) : Pattern()
