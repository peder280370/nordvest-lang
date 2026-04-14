package nv.compiler.typecheck

import nv.compiler.CompileError
import nv.compiler.lexer.SourceSpan

sealed class TypeCheckError {
    abstract val span: SourceSpan
    abstract val message: String

    data class TypeMismatch(
        val expected: Type,
        val actual: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() =
            "type mismatch: expected '${expected.display()}', got '${actual.display()}'"
    }

    data class ReturnTypeMismatch(
        val expected: Type,
        val actual: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() =
            "return type mismatch: expected '${expected.display()}', got '${actual.display()}'"
    }

    data class NotNullable(
        val type: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() =
            "type '${type.display()}' is not nullable; cannot use '?.', '??', or '!.'" +
            " — consider using '?.' for safe access or '??' for a default value"
    }

    data class ForceUnwrapNonNullable(
        val type: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "cannot force-unwrap non-nullable type '${type.display()}'"
    }

    data class NonExhaustiveMatch(
        val missingCases: List<String>,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() =
            "non-exhaustive match; missing cases: ${missingCases.joinToString(", ")}"
    }

    data class CannotInferType(
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "cannot infer type; add a type annotation"
    }

    data class ArityMismatch(
        val expected: Int,
        val actual: Int,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "expected $expected argument(s), got $actual"
    }

    data class NotCallable(
        val type: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "'${type.display()}' is not callable"
    }

    data class OperatorTypeMismatch(
        val op: String,
        val leftType: Type,
        val rightType: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() =
            "operator '$op' cannot be applied to '${leftType.display()}' and '${rightType.display()}'"
    }

    data class UnaryTypeMismatch(
        val op: String,
        val operandType: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() =
            "operator '$op' cannot be applied to '${operandType.display()}'"
    }

    data class AssignToImmutable(
        val name: String,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "cannot assign to immutable binding '$name'"
    }

    data class ResultPropagateNonResult(
        val type: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() =
            "'?' propagation requires Result<T> or nullable type, got '${type.display()}'"
    }

    data class ConditionNotBool(
        val actual: Type,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "condition must be 'bool', got '${actual.display()}'"
    }

    data class DuplicateMatchArm(
        val pattern: String,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "duplicate match arm: '$pattern'"
    }

    data class AwaitOutsideAsync(
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "await expression is only valid inside an async fn"
    }

    data class GpuAnnotationInvalid(
        override val span: SourceSpan,
        val reason: String,
    ) : TypeCheckError() {
        override val message get() = "invalid @gpu annotation: $reason"
    }

    data class YieldOutsideSequence(
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "yield is only valid inside a Sequence<T>-returning function"
    }

    data class UnknownBuilderField(
        val typeName: String,
        val fieldName: String,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "unknown field '$fieldName' in @builder for type '$typeName'"
    }

    data class MissingBuilderField(
        val typeName: String,
        val fieldName: String,
        override val span: SourceSpan,
    ) : TypeCheckError() {
        override val message get() = "@builder for '$typeName' requires field '$fieldName' to be set (no default value)"
    }
}

fun TypeCheckError.toCompileError(sourcePath: String) =
    CompileError(message, sourcePath, span.start.line, span.start.col)
