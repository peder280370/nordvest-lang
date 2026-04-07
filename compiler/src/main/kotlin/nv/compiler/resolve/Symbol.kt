package nv.compiler.resolve

import nv.compiler.lexer.SourceSpan
import nv.compiler.parser.ClassDecl
import nv.compiler.parser.EnumDecl
import nv.compiler.parser.FunctionDecl
import nv.compiler.parser.InterfaceDecl
import nv.compiler.parser.Node
import nv.compiler.parser.Param
import nv.compiler.parser.RecordDecl
import nv.compiler.parser.SealedClassDecl
import nv.compiler.parser.StructDecl
import nv.compiler.parser.TypeAliasDecl
import nv.compiler.parser.Visibility
import nv.compiler.typecheck.Type

enum class SymbolOrigin { MODULE, BUILTIN }

sealed class Symbol {
    abstract val name: String
    abstract val span: SourceSpan
    abstract val visibility: Visibility
    abstract val origin: SymbolOrigin

    /** Resolved type; TUnknown until Phase 1.4 type checker fills it in. */
    abstract var resolvedType: Type

    // ── Value symbols ─────────────────────────────────────────────────────

    data class FunctionSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: FunctionDecl? = null,
    ) : Symbol()

    data class LetSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: Node,
        val isMutable: Boolean = false,
    ) : Symbol()

    data class VarSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: Node,
    ) : Symbol()

    data class ParamSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility = Visibility.FILE_PRIVATE,
        override val origin: SymbolOrigin = SymbolOrigin.MODULE,
        override var resolvedType: Type = Type.TUnknown,
        val param: Param,
    ) : Symbol()

    // ── Type symbols ──────────────────────────────────────────────────────

    data class ClassSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: ClassDecl,
    ) : Symbol()

    data class StructSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: StructDecl,
    ) : Symbol()

    data class RecordSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: RecordDecl,
    ) : Symbol()

    data class InterfaceSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: InterfaceDecl,
    ) : Symbol()

    data class SealedClassSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: SealedClassDecl,
    ) : Symbol()

    data class EnumSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: EnumDecl,
    ) : Symbol()

    data class TypeAliasSym(
        override val name: String,
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val decl: TypeAliasDecl,
    ) : Symbol()

    // ── Module symbol ─────────────────────────────────────────────────────

    data class ModuleSym(
        override val name: String,        // local name or alias used for lookups
        val qualifiedName: String,         // full path, e.g. "std.math"
        override val span: SourceSpan,
        override val visibility: Visibility,
        override val origin: SymbolOrigin,
        override var resolvedType: Type = Type.TUnknown,
        val alias: String?,
    ) : Symbol()

    // ── Built-in symbol ───────────────────────────────────────────────────

    data class BuiltinSym(
        override val name: String,
        override val span: SourceSpan = SourceSpan.SYNTHETIC,
        override val visibility: Visibility = Visibility.PUBLIC,
        override val origin: SymbolOrigin = SymbolOrigin.BUILTIN,
        override var resolvedType: Type,
    ) : Symbol()
}
