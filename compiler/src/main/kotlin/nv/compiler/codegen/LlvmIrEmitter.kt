package nv.compiler.codegen

import nv.compiler.codegen.runtime.*
import nv.compiler.parser.*
import nv.compiler.typecheck.Type
import nv.compiler.typecheck.TypeCheckedModule
import nv.compiler.typecheck.isFloatLike
import nv.compiler.typecheck.isIntLike

/**
 * Phase 1.5: LLVM IR emitter for the Nordvest bootstrap compiler.
 *
 * Generates typed-pointer LLVM IR compatible with Clang/LLVM 12+ from a [TypeCheckedModule].
 * Pointer types use `i8*` (typed pointer style) rather than opaque `ptr` for broad
 * compatibility. All locals use alloca/load/store. Runtime helpers are emitted inline so
 * the output module only requires libc.
 */
class LlvmIrEmitter(
    private val tcModule: TypeCheckedModule,
    /** Target architecture for @asm / @bytes arch-selection. Defaults to host arch. */
    val targetArch: String = detectHostArch(),
) {

    // ── Output sections ───────────────────────────────────────────────────

    private val globals   = StringBuilder()   // @.fmt.* / @.str.* constants
    private val declares  = StringBuilder()   // declare (libc)
    private val rtFns     = StringBuilder()   // nv runtime function bodies
    private val userFns   = StringBuilder()   // user function bodies

    // ── Global counters ───────────────────────────────────────────────────

    private var tempIdx   = 0
    private var labelIdx  = 0
    private var strIdx    = 0
    private val stringPool = mutableMapOf<String, String>()  // raw content → @name

    // ── Per-function state (reset for each function) ──────────────────────

    private var fnBody    = StringBuilder()
    private var fnAllocas = StringBuilder()
    private val varAllocas = mutableMapOf<String, Pair<String, String>>()  // name → (reg, llvmType)
    private val varTypes   = mutableMapOf<String, Type>()                   // name → Nordvest Type (finer than llvmType)
    private var fnReturnType = "void"
    private var isTerminated = false

    // ── Loop stack for break/continue ─────────────────────────────────────

    private data class LoopTarget(val condLabel: String, val endLabel: String, val userLabel: String?)
    private val loopStack = ArrayDeque<LoopTarget>()

    // ── Extern function registry (populated at start of emit()) ──────────
    private data class ExternInfo(val cSymbol: String, val retType: Type, val paramTypes: List<Type>)
    private val externFunctions = mutableMapOf<String, ExternInfo>()

    // ── Inline runtime functions (defined via define, not just declared) ──
    // Apple Clang 12 rejects declare+define in the same module; skip declare for these.
    private val inlineRuntimeFns = setOf(
        "nv_print", "nv_println", "nv_print_int", "nv_println_int",
        "nv_print_float", "nv_println_float", "nv_print_bool", "nv_println_bool",
        "nv_int_to_str", "nv_float_to_str", "nv_bool_to_str",
        "nv_str_concat", "nv_str_eq", "nv_str_len", "nv_panic",
        "nv_eprintln", "nv_read_line", "nv_read_all",
        "nv_rc_retain", "nv_rc_release", "nv_Ok", "nv_Err",
        // Phase 5.1 — string ops
        "nv_str_slice", "nv_str_index_of", "nv_str_contains",
        "nv_str_starts_with", "nv_str_ends_with",
        "nv_str_to_upper", "nv_str_to_lower", "nv_str_trim",
        "nv_str_replace", "nv_str_repeat",
        "nv_str_parse_int", "nv_str_parse_float",
        // Phase 5.2 — collections
        "nv_arr_push_i64", "nv_arr_push_str",
        "nv_arr_contains_i64", "nv_arr_contains_str",
        "nv_arr_index_of_i64", "nv_arr_index_of_str",
        "nv_map_new", "nv_map_len", "nv_map_get_str", "nv_map_has_str", "nv_map_set_str",
        // Phase 5.3 — file I/O
        "nv_file_open", "nv_file_open_read", "nv_file_open_write", "nv_file_open_append",
        "nv_file_close", "nv_file_write", "nv_file_writeln",
        "nv_file_read_line", "nv_file_read_all", "nv_file_exists", "nv_file_is_null",
        // Phase 5.4 — std.fs
        "nv_fs_exists", "nv_fs_is_dir", "nv_fs_is_file",
        "nv_fs_mkdir", "nv_fs_rm", "nv_fs_rename",
        "nv_fs_read_text", "nv_fs_write_text", "nv_fs_append_text",
        "nv_fs_join_path", "nv_fs_parent_dir", "nv_fs_file_name", "nv_fs_file_ext",
        "nv_fs_cwd", "nv_fs_chdir",
        // Phase 5.4 — std.time
        "nv_time_now_ms", "nv_time_now_float", "nv_time_monotonic_ns", "nv_time_sleep_ms",
        // Phase 5.4 — std.process
        "nv_process_getenv", "nv_process_setenv", "nv_process_exit",
        "nv_process_pid", "nv_process_cwd", "nv_process_chdir", "nv_process_capture",
        // Phase 5.4 — std.rand
        "nv_rand_seed", "nv_rand_init", "nv_rand_next",
        "nv_rand_float", "nv_rand_int", "nv_rand_bool",
        // Phase 5.5 — RC
        "nv_rc_retain", "nv_rc_release", "nv_weak_load",
        // Phase 5.6 — std.hash
        "nv_hash_fnv1a", "nv_hash_djb2", "nv_hash_murmur3",
        "nv_hash_crc32", "nv_hash_combine", "nv_hash_sha256", "nv_hash_md5",
        // Phase 5.6 — std.fmt
        "nv_fmt_int", "nv_fmt_float", "nv_fmt_truncate",
        "nv_fmt_file_size", "nv_fmt_duration", "nv_fmt_thousands",
        // Phase 5.6 — std.iter
        "nv_iter_range", "nv_iter_range_step",
        "nv_iter_repeat_int", "nv_iter_repeat_str",
        "nv_iter_chain_int", "nv_iter_chain_str",
        // Phase 5.6 — collections (new typed ops)
        "nv_arr_first_i64", "nv_arr_last_i64",
        "nv_arr_first_str", "nv_arr_last_str",
        "nv_arr_reverse_i64", "nv_arr_reverse_str",
        "nv_arr_slice_i64", "nv_arr_slice_str",
        "nv_arr_sort_i64",
        // std.log
        "nv_log_debug", "nv_log_info", "nv_log_warn", "nv_log_error",
        "nv_log_fatal", "nv_log_set_level", "nv_log_flush",
        "nv_log_warnWith", "nv_log_errorWith", "nv_log_fatalWith",
        "nv_log_set_trace_enabled", "nv_log_do_trace"
    )

    // ── Actual LLVM signatures for pointer-typed inline runtime functions ──
    // When user calls these via @extern with integer params (e.g. int as null pointer),
    // we emit inttoptr/ptrtoint casts to avoid LLVM type-mismatch errors.
    private val inlineRuntimeFnPtrSigs: Map<String, Pair<String, List<String>>> = mapOf(
        "nv_rc_retain"  to ("void" to listOf("i8*")),
        "nv_rc_release" to ("void" to listOf("i8*")),
        "nv_weak_load"  to ("i8*"  to listOf("i8*")),
        "nv_Ok"         to ("i8*"  to listOf("i8*")),
        "nv_Err"        to ("i8*"  to listOf("i8*")),
    )

    // ── Struct layout registry (name → ordered list of field name+type) ──
    private val structLayouts      = mutableMapOf<String, List<Pair<String, Type>>>()
    private val structCtorDefaults = mutableMapOf<String, List<Pair<String, Expr?>>>()
    // Number of fields that are constructor parameters (the rest are body/lazy fields)
    private val structCtorParamCount = mutableMapOf<String, Int>()

    // ── @lazy field registry: typeName → [(fieldName, fieldType, initExpr)] ──
    private val lazyFields = mutableMapOf<String, MutableList<Triple<String, Type, Expr>>>()

    // ── by-delegation registry: typeName → [(interfaceName, delegateFieldName, concreteTypeName)] ──
    private val classDelegations = mutableMapOf<String, MutableList<Triple<String, String, String>>>()

    // ── @config registry: typeName → prefix; typeName → [(fieldName, envVarOverride or null)] ──
    private val configPrefixes      = mutableMapOf<String, String>()
    private val configEnvOverrides  = mutableMapOf<String, List<Pair<String, String?>>>()

    // ── Class type registry: names of ClassDecl types (have RC header) ──
    private val classTypeNames = mutableSetOf<String>()

    // ── Method return type registry: "TypeName_methodName" → LLVM type string ──
    private val methodReturnTypes = mutableMapOf<String, String>()

    // ── Sealed class registry: className → decl; variant "ClassName.Variant" → tag index ──
    private val sealedClassDecls = mutableMapOf<String, SealedClassDecl>()
    private val variantTags      = mutableMapOf<String, Int>()   // "JsonValue.JsonBool" → 1

    // ─────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────

    fun emit(): CodegenResult {
        collectDeclarationInfo()
        emitPreamble()
        emitDeclares()
        emitRuntimeFunctions()
        emitSealedClassFunctions()

        val file = tcModule.resolvedModule.file
        for (decl in file.declarations) {
            emitDecl(decl)
        }

        val ir = buildString {
            appendLine("; Nordvest bootstrap compiler — Phase 1.5")
            appendLine("; Source: ${file.sourcePath}")
            appendLine()
            append(globals)
            appendLine()
            append(declares)
            appendLine()
            append(rtFns)
            appendLine()
            append(userFns)
        }
        return CodegenResult.Success(ir)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stdlib extern registry — pre-populated so that `import std.X` works
    // without requiring the user to re-declare @extern in their source.
    // User inline @extern declarations (processed below) will override these.
    // ─────────────────────────────────────────────────────────────────────

    private val stdlibExternRegistry: List<Triple<String, String, Pair<Type, List<Type>>>> = listOf(
        // std.collections — int list ops
        Triple("listAppendInt",   "nv_arr_push_i64",      Type.TArray(Type.TInt)  to listOf(Type.TArray(Type.TInt), Type.TInt)),
        Triple("listContainsInt", "nv_arr_contains_i64",  Type.TBool              to listOf(Type.TArray(Type.TInt), Type.TInt)),
        Triple("listIndexOfInt",  "nv_arr_index_of_i64",  Type.TInt               to listOf(Type.TArray(Type.TInt), Type.TInt)),
        Triple("listFirstInt",    "nv_arr_first_i64",     Type.TInt               to listOf(Type.TArray(Type.TInt))),
        Triple("listLastInt",     "nv_arr_last_i64",      Type.TInt               to listOf(Type.TArray(Type.TInt))),
        Triple("listReverseInt",  "nv_arr_reverse_i64",   Type.TArray(Type.TInt)  to listOf(Type.TArray(Type.TInt))),
        Triple("listSliceInt",    "nv_arr_slice_i64",     Type.TArray(Type.TInt)  to listOf(Type.TArray(Type.TInt), Type.TInt, Type.TInt)),
        Triple("listSortInt",     "nv_arr_sort_i64",      Type.TArray(Type.TInt)  to listOf(Type.TArray(Type.TInt))),
        // std.collections — str list ops
        Triple("listAppendStr",   "nv_arr_push_str",      Type.TArray(Type.TStr)  to listOf(Type.TArray(Type.TStr), Type.TStr)),
        Triple("listContainsStr", "nv_arr_contains_str",  Type.TBool              to listOf(Type.TArray(Type.TStr), Type.TStr)),
        Triple("listIndexOfStr",  "nv_arr_index_of_str",  Type.TInt               to listOf(Type.TArray(Type.TStr), Type.TStr)),
        Triple("listFirstStr",    "nv_arr_first_str",     Type.TStr               to listOf(Type.TArray(Type.TStr))),
        Triple("listLastStr",     "nv_arr_last_str",      Type.TStr               to listOf(Type.TArray(Type.TStr))),
        Triple("listReverseStr",  "nv_arr_reverse_str",   Type.TArray(Type.TStr)  to listOf(Type.TArray(Type.TStr))),
        Triple("listSliceStr",    "nv_arr_slice_str",     Type.TArray(Type.TStr)  to listOf(Type.TArray(Type.TStr), Type.TInt, Type.TInt)),
        // std.collections — map ops
        Triple("mapNew",  "nv_map_new",     Type.TMap(Type.TStr, Type.TStr) to emptyList()),
        Triple("mapLen",  "nv_map_len",     Type.TInt                       to listOf(Type.TMap(Type.TStr, Type.TStr))),
        Triple("mapHas",  "nv_map_has_str", Type.TBool                      to listOf(Type.TMap(Type.TStr, Type.TStr), Type.TStr)),
        Triple("mapGet",  "nv_map_get_str", Type.TNullable(Type.TStr)       to listOf(Type.TMap(Type.TStr, Type.TStr), Type.TStr)),
        Triple("mapSet",  "nv_map_set_str", Type.TMap(Type.TStr, Type.TStr) to listOf(Type.TMap(Type.TStr, Type.TStr), Type.TStr, Type.TStr)),
        // std.iter — range generators
        Triple("range",      "nv_iter_range",      Type.TArray(Type.TInt)  to listOf(Type.TInt, Type.TInt)),
        Triple("rangeStep",  "nv_iter_range_step", Type.TArray(Type.TInt)  to listOf(Type.TInt, Type.TInt, Type.TInt)),
        Triple("repeatInt",  "nv_iter_repeat_int", Type.TArray(Type.TInt)  to listOf(Type.TInt, Type.TInt)),
        Triple("repeatStr",  "nv_iter_repeat_str", Type.TArray(Type.TStr)  to listOf(Type.TStr, Type.TInt)),
        Triple("chainInt",   "nv_iter_chain_int",  Type.TArray(Type.TInt)  to listOf(Type.TArray(Type.TInt), Type.TArray(Type.TInt))),
        Triple("chainStr",   "nv_iter_chain_str",  Type.TArray(Type.TStr)  to listOf(Type.TArray(Type.TStr), Type.TArray(Type.TStr))),
    )

    // ─────────────────────────────────────────────────────────────────────
    // Declaration info collection (pass 0)
    // ─────────────────────────────────────────────────────────────────────

    private fun collectDeclarationInfo() {
        // Pre-populate with stdlib extern mappings so `import std.X` works
        // without requiring re-declaration in user code.
        for ((nvName, cSym, retAndParams) in stdlibExternRegistry) {
            val (retTy, paramTys) = retAndParams
            externFunctions[nvName] = ExternInfo(cSym, retTy, paramTys)
        }

        val file = tcModule.resolvedModule.file
        for (decl in file.declarations) {
            when (decl) {
                is FunctionSignatureDecl -> {
                    if (decl.annotations.any { it.name == "extern" }) {
                        val csym = externCSymbol(decl.annotations, decl.name)
                        val retTy = decl.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
                        val paramTys = decl.params.map { resolveTypeNode(it.type) }
                        externFunctions[decl.name] = ExternInfo(csym, retTy, paramTys)
                    }
                }
                is FunctionDecl -> {
                    if (decl.annotations.any { it.name == "extern" } && decl.body.isEmpty()) {
                        val csym = externCSymbol(decl.annotations, decl.name)
                        val retTy = decl.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
                        val paramTys = decl.params.map { resolveTypeNode(it.type) }
                        externFunctions[decl.name] = ExternInfo(csym, retTy, paramTys)
                    }
                }
                is StructDecl -> {
                    collectStructLayout(decl.name, decl.constructorParams, decl.members, isClass = false)
                    collectConfigInfo(decl.name, decl.annotations, decl.constructorParams)
                }
                is ClassDecl  -> {
                    collectStructLayout(decl.name, decl.constructorParams, decl.members, isClass = true)
                    collectConfigInfo(decl.name, decl.annotations, decl.constructorParams)
                    // Register by-delegations: interface → (delegateFieldName, concreteTypeName)
                    for ((ifaceTypeNode, delegateExpr) in decl.delegations) {
                        val ifaceName = (ifaceTypeNode as? NamedTypeNode)?.name?.text ?: continue
                        val delegateFieldName = (delegateExpr as? IdentExpr)?.name ?: continue
                        val delegateParam = decl.constructorParams.firstOrNull { it.name == delegateFieldName } ?: continue
                        val concreteType = resolveTypeNode(delegateParam.type)
                        val concreteTypeName = (concreteType as? Type.TNamed)?.qualifiedName?.substringAfterLast('.') ?: continue
                        classDelegations.getOrPut(decl.name) { mutableListOf() }
                            .add(Triple(ifaceName, delegateFieldName, concreteTypeName))
                    }
                }
                is RecordDecl -> {
                    collectStructLayout(decl.name, decl.constructorParams, decl.members, isClass = false)
                    collectConfigInfo(decl.name, decl.annotations, decl.constructorParams)
                }
                is SealedClassDecl -> {
                    sealedClassDecls[decl.name] = decl
                    for ((idx, variant) in decl.variants.withIndex()) {
                        variantTags["${decl.name}.${variant.name}"] = idx
                    }
                }
                else -> {}
            }
        }
    }

    private fun externCSymbol(annotations: List<nv.compiler.parser.Annotation>, default: String): String =
        annotations.firstOrNull { it.name == "extern" }
            ?.args?.firstOrNull { it.name == "fn" || it.name == null }
            ?.let { arg: AnnotationArg ->
                when (val v = arg.value) {
                    is AnnotationStrValue   -> v.value
                    is AnnotationIdentValue -> v.name.text
                    else -> null
                }
            } ?: default

    private fun collectStructLayout(name: String, ctorParams: List<ConstructorParam>, members: List<Decl>, isClass: Boolean = false) {
        val fields = ctorParams.map { cp -> cp.name to resolveTypeNode(cp.type) }.toMutableList()
        val ctorCount = ctorParams.size
        for (m in members) {
            when (m) {
                is FieldDecl -> {
                    val fieldType = resolveTypeNode(m.typeAnnotation)
                    if (m.annotations.any { it.name == "lazy" } && m.initializer != null) {
                        // Lazy field: prepend an i1 init-flag, then the value slot
                        fields += "_lazy_${m.name}_init" to Type.TBool
                        fields += m.name to fieldType
                        lazyFields.getOrPut(name) { mutableListOf() }
                            .add(Triple(m.name, fieldType, m.initializer))
                    } else {
                        fields += m.name to fieldType
                    }
                }
                is VarDecl   -> if (m.typeAnnotation != null) fields += m.name to resolveTypeNode(m.typeAnnotation)
                is LetDecl   -> if (m.typeAnnotation != null) fields += m.name to resolveTypeNode(m.typeAnnotation)
                else -> {}
            }
        }
        if (isClass) classTypeNames.add(name)
        structLayouts[name] = fields
        structCtorParamCount[name] = ctorCount
        structCtorDefaults[name] = ctorParams.map { it.name to it.default }
        // Emit struct type definition into globals.
        // Class types get a 16-byte RC header prepended: { i64 strong_count, i8* dtor_fn, ...fields }
        if (fields.isNotEmpty() || isClass) {
            val fieldTypes = fields.joinToString(", ") { (_, t) -> llvmType(t) }
            if (isClass) {
                val fieldPart = if (fieldTypes.isNotEmpty()) ", $fieldTypes" else ""
                globals.appendLine("%struct.$name = type { i64, i8*$fieldPart }")
            } else {
                globals.appendLine("%struct.$name = type { $fieldTypes }")
            }
        }
    }

    /** Populate @config/@env registries from a type declaration's annotations and constructor params. */
    private fun collectConfigInfo(
        typeName: String,
        annos: List<nv.compiler.parser.Annotation>,
        ctorParams: List<ConstructorParam>
    ) {
        val configAnno = annos.find { it.name == "config" } ?: return
        val prefix = (configAnno.args.firstOrNull()?.value as? AnnotationStrValue)?.value ?: typeName
        configPrefixes[typeName] = prefix
        configEnvOverrides[typeName] = ctorParams.map { param ->
            val envAnno = param.annotations.find { it.name == "env" }
            val explicitVar = (envAnno?.args?.firstOrNull { it.name == null || it.name == "name" }
                ?.value as? AnnotationStrValue)?.value
            param.name to explicitVar
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Preamble: global format strings and string constants
    // ─────────────────────────────────────────────────────────────────────

    private fun emitPreamble() {
        globals.appendLine("""
@.fmt.s     = private unnamed_addr constant [3 x i8]  c"%s\00", align 1
@.fmt.sn    = private unnamed_addr constant [4 x i8]  c"%s\0A\00", align 1
@.fmt.d     = private unnamed_addr constant [4 x i8]  c"%ld\00", align 1
@.fmt.dn    = private unnamed_addr constant [5 x i8]  c"%ld\0A\00", align 1
@.fmt.g     = private unnamed_addr constant [3 x i8]  c"%g\00", align 1
@.fmt.gn    = private unnamed_addr constant [4 x i8]  c"%g\0A\00", align 1
@.str.true  = private unnamed_addr constant [5 x i8]  c"true\00", align 1
@.str.false = private unnamed_addr constant [6 x i8]  c"false\00", align 1
@.str.nil   = private unnamed_addr constant [4 x i8]  c"nil\00", align 1
@.panic.fmt = private unnamed_addr constant [11 x i8] c"panic: %s\0A\00", align 1
@.str.mode_r = private unnamed_addr constant [2 x i8]  c"r\00", align 1
@.str.mode_w = private unnamed_addr constant [2 x i8]  c"w\00", align 1
@.str.mode_a = private unnamed_addr constant [2 x i8]  c"a\00", align 1
@.str.p5_fsjoin = private unnamed_addr constant [6 x i8] c"%s/%s\00", align 1
@nv_rand_state  = global i64 6364136223846793005, align 8
; Phase 5.6 — SHA-256 constants
@sha256_k = private constant [64 x i32] [i32 1116352408,i32 1899447441,i32 -1245643825,i32 -373957723,i32 961987163,i32 1508970993,i32 -1841331548,i32 -1424204075,i32 -670586216,i32 310598401,i32 607225278,i32 1426881987,i32 1925078388,i32 -2132889090,i32 -1680079193,i32 -1046744716,i32 -459576895,i32 -272742522,i32 264347078,i32 604807628,i32 770255983,i32 1249150122,i32 1555081692,i32 1996064986,i32 -1740746414,i32 -1473132947,i32 -1341970488,i32 -1084653625,i32 -958395405,i32 -710438585,i32 113926993,i32 338241895,i32 666307205,i32 773529912,i32 1294757372,i32 1396182291,i32 1695183700,i32 1986661051,i32 -2117940946,i32 -1838011259,i32 -1564481375,i32 -1474664885,i32 -1035236496,i32 -949202525,i32 -778901479,i32 -694614492,i32 -200395387,i32 275423344,i32 430227734,i32 506948616,i32 659060556,i32 883997877,i32 958139571,i32 1322822218,i32 1537002063,i32 1747873779,i32 1955562222,i32 2024104815,i32 -2067236844,i32 -1933114872,i32 -1866530822,i32 -1538233109,i32 -1090935817,i32 -965641998]
@sha256_hinit = private constant [8 x i32] [i32 1779033703,i32 -1150833019,i32 1013904242,i32 -1521486534,i32 1359893119,i32 -1694144372,i32 528734635,i32 1541459225]
; Phase 5.6 — MD5 constants
@md5_T = private constant [64 x i32] [i32 -680876936,i32 -389564586,i32 606105819,i32 -1044525330,i32 -176418897,i32 1200080426,i32 -1473231341,i32 -45705983,i32 1770035416,i32 -1958414417,i32 -42063,i32 -1990404162,i32 1804603682,i32 -40341101,i32 -1502002290,i32 1236535329,i32 -165796510,i32 -1069501632,i32 643717713,i32 -373897302,i32 -701558691,i32 38016083,i32 -660478335,i32 -405537848,i32 568446438,i32 -1019803690,i32 -187363961,i32 1163531501,i32 -1444681467,i32 -51403784,i32 1735328473,i32 -1926607734,i32 -378558,i32 -2022574463,i32 1839030562,i32 -35309556,i32 -1530992060,i32 1272893353,i32 -155497632,i32 -1094730640,i32 681279174,i32 -358537222,i32 -722521979,i32 76029189,i32 -640364487,i32 -421815835,i32 530742520,i32 -995338651,i32 -198630844,i32 1126891415,i32 -1416354905,i32 -57434055,i32 1700485571,i32 -1894986606,i32 -1051523,i32 -2054922799,i32 1873313359,i32 -30611744,i32 -1560198380,i32 1309151649,i32 -145523070,i32 -1120210379,i32 718787259,i32 -343485551]
@md5_s = private constant [64 x i8] [i8 7,i8 12,i8 17,i8 22,i8 7,i8 12,i8 17,i8 22,i8 7,i8 12,i8 17,i8 22,i8 7,i8 12,i8 17,i8 22,i8 5,i8 9,i8 14,i8 20,i8 5,i8 9,i8 14,i8 20,i8 5,i8 9,i8 14,i8 20,i8 5,i8 9,i8 14,i8 20,i8 4,i8 11,i8 16,i8 23,i8 4,i8 11,i8 16,i8 23,i8 4,i8 11,i8 16,i8 23,i8 4,i8 11,i8 16,i8 23,i8 6,i8 10,i8 15,i8 21,i8 6,i8 10,i8 15,i8 21,i8 6,i8 10,i8 15,i8 21,i8 6,i8 10,i8 15,i8 21]
; Phase 5.6 — fmt format strings
@.fmt.hex2 = private unnamed_addr constant [3 x i8] c"%x\00", align 1
@.fmt.hexU8 = private unnamed_addr constant [5 x i8] c"%02x\00", align 1
@.fmt.hex8 = private unnamed_addr constant [5 x i8] c"%08x\00", align 1
@.fmt.u64  = private unnamed_addr constant [5 x i8] c"%llu\00", align 1
@.fmt.fmtfloat_e = private unnamed_addr constant [5 x i8] c"%.*e\00", align 1
@.fmt.fmtfloat_f = private unnamed_addr constant [5 x i8] c"%.*f\00", align 1
@.fmt.fmtfloat_g = private unnamed_addr constant [5 x i8] c"%.*g\00", align 1
@.fmt.fs_b   = private unnamed_addr constant [7 x i8] c"%lld B\00", align 1
@.fmt.fs_int = private unnamed_addr constant [8 x i8] c"%lld %s\00", align 1
@.fmt.fs_flt = private unnamed_addr constant [8 x i8] c"%.1f %s\00", align 1
@.str.kb = private unnamed_addr constant [3 x i8] c"KB\00", align 1
@.str.mb = private unnamed_addr constant [3 x i8] c"MB\00", align 1
@.str.gb = private unnamed_addr constant [3 x i8] c"GB\00", align 1
@.str.tb = private unnamed_addr constant [3 x i8] c"TB\00", align 1
@.str.pb = private unnamed_addr constant [3 x i8] c"PB\00", align 1
@.fmt.dur_ms  = private unnamed_addr constant [7 x i8] c"%lldms\00", align 1
@.fmt.dur_s   = private unnamed_addr constant [6 x i8] c"%llds\00", align 1
@.fmt.dur_ms2 = private unnamed_addr constant [12 x i8] c"%lldm %llds\00", align 1
@.fmt.dur_hm  = private unnamed_addr constant [12 x i8] c"%lldh %lldm\00", align 1
@.fmt.u64plain = private unnamed_addr constant [5 x i8] c"%llu\00", align 1
@.fmt.llx = private unnamed_addr constant [5 x i8] c"%llx\00", align 1
@.fmt.llo = private unnamed_addr constant [5 x i8] c"%llo\00", align 1
""".trimIndent())
    }

    // ─────────────────────────────────────────────────────────────────────
    // libc declares
    // ─────────────────────────────────────────────────────────────────────

    private fun emitDeclares() {
        declares.appendLine("""
declare i32   @printf(i8*, ...)
declare i32   @snprintf(i8*, i64, i8*, ...)
declare i8*   @malloc(i64)
declare void  @free(i8*)
declare i8*   @strcpy(i8*, i8*)
declare i8*   @strcat(i8*, i8*)
declare i64   @strlen(i8*)
declare i32   @strcmp(i8*, i8*)
declare void  @exit(i32) noreturn
declare double @pow(double, double)
declare double @fmod(double, double)
declare i8*   @nv_go_spawn(i8* (i8*)*, i8*)
declare i8*   @nv_future_spawn(i8* (i8*)*, i8*)
declare i8*   @nv_future_await(i8*)
declare i8*   @nv_channel_create(i64)
declare void  @nv_channel_send(i8*, i8*)
declare i8*   @nv_channel_receive(i8*)
declare i32   @nv_channel_try_receive(i8*, i8**)
declare void  @nv_channel_close(i8*)
declare double @sin(double)
declare double @cos(double)
declare double @tan(double)
declare double @asin(double)
declare double @acos(double)
declare double @atan(double)
declare double @atan2(double, double)
declare double @exp(double)
declare double @log(double)
declare double @log2(double)
declare double @log10(double)
declare double @ceil(double)
declare double @floor(double)
declare double @round(double)
declare double @sqrt(double)
declare double @hypot(double, double)
declare double @fabs(double)
declare i32    @scanf(i8*, ...)
declare i64    @strtol(i8*, i8**, i32)
declare i64    @strtoll(i8*, i8**, i32)
declare double @strtod(i8*, i8**)
declare i32    @sscanf(i8*, i8*, ...)
declare i32    @atoi(i8*)
declare i64    @atoll(i8*)
declare double @atof(i8*)
declare i32    @getchar()
declare i32    @toupper(i32)
declare i32    @tolower(i32)
declare i32    @isspace(i32)
declare i8*    @strstr(i8*, i8*)
declare i8*    @realloc(i8*, i64)
declare i8*    @fopen(i8*, i8*)
declare i32    @fclose(i8*)
declare i8*    @fgets(i8*, i32, i8*)
declare i64    @fread(i8*, i64, i64, i8*)
declare i64    @fwrite(i8*, i64, i64, i8*)
declare i32    @fputs(i8*, i8*)
declare i32    @fputc(i32, i8*)
declare i32    @fflush(i8*)
declare i32    @access(i8*, i32)
declare i8*  @strrchr(i8*, i32)
declare i8*  @memcpy(i8* noalias, i8* noalias, i64)
declare i8*  @memset(i8*, i32, i64)
declare i8*  @calloc(i64, i64)
declare i8*  @opendir(i8*)
declare i32  @closedir(i8*)
declare i32  @unlink(i8*)
declare i32  @rmdir(i8*)
declare i32  @mkdir(i8*, i32)
declare i32  @rename(i8*, i8*)
declare i8*  @getcwd(i8*, i64)
declare i32  @chdir(i8*)
declare i32  @clock_gettime(i32, i8*)
declare i32  @nanosleep(i8*, i8*)
declare i8*  @getenv(i8*)
declare i32  @setenv(i8*, i8*, i32)
declare i32  @getpid()
declare i8*  @popen(i8*, i8*)
declare i32  @pclose(i8*)
declare i32  @backtrace(i8**, i32)
declare i8** @backtrace_symbols(i8**, i32)
""".trimIndent())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Nordvest runtime function bodies — delegated to per-module objects
    // ─────────────────────────────────────────────────────────────────────

    private fun emitRuntimeFunctions() {
        CoreRuntime.emit(rtFns)
        StringRuntime.emit(rtFns)
        CollectionsRuntime.emit(rtFns)
        IoRuntime.emit(rtFns)
        FsRuntime.emit(rtFns)
        TimeRuntime.emit(rtFns)
        ProcessRuntime.emit(rtFns)
        RandRuntime.emit(rtFns)
        HashRuntime.emit(rtFns)
        FmtRuntime.emit(rtFns)
        IterRuntime.emit(rtFns)
        LogRuntime.emit(rtFns)
    }

    /*
     * Each variant is represented as a heap-allocated {i64 tag, i64 value} struct (16 bytes).
     * The tag is the variant's 0-based index within the sealed class.
     * The value is the first constructor param coerced to i64 (0 for no-param variants).
     */
    private fun emitSealedClassFunctions() {
        for ((_, decl) in sealedClassDecls) {
            for ((idx, variant) in decl.variants.withIndex()) {
                emitSealedVariantConstructor(variant, idx)
            }
        }
    }

    private fun emitSealedVariantConstructor(variant: SealedVariant, tag: Int) {
        val mangledName = "@nv_${variant.name}"
        if (variant.params.isEmpty()) {
            rtFns.appendLine("""
define i8* $mangledName() {
entry:
  %p = call i8* @malloc(i64 16)
  %tp = bitcast i8* %p to i64*
  store i64 $tag, i64* %tp, align 8
  %vp = getelementptr i64, i64* %tp, i64 1
  store i64 0, i64* %vp, align 8
  ret i8* %p
}""".trimIndent())
        } else {
            val paramType = resolveTypeNode(variant.params[0].type)
            val paramLt = llvmType(paramType)
            val storeStmt = when (paramLt) {
                "i8*"    -> "  %ival = ptrtoint i8* %param to i64\n  store i64 %ival, i64* %vp, align 8"
                "i1"     -> "  %ival = zext i1 %param to i64\n  store i64 %ival, i64* %vp, align 8"
                "double" -> "  %ival = bitcast double %param to i64\n  store i64 %ival, i64* %vp, align 8"
                "float"  -> "  %fext = fpext float %param to double\n  %ival = bitcast double %fext to i64\n  store i64 %ival, i64* %vp, align 8"
                else     -> "  store i64 %param, i64* %vp, align 8"   // i64 and sub-types
            }
            rtFns.appendLine("""
define i8* $mangledName($paramLt %param) {
entry:
  %p = call i8* @malloc(i64 16)
  %tp = bitcast i8* %p to i64*
  store i64 $tag, i64* %tp, align 8
  %vp = getelementptr i64, i64* %tp, i64 1
$storeStmt
  ret i8* %p
}""".trimIndent())
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Type mapping
    // ─────────────────────────────────────────────────────────────────────

    private fun llvmType(t: Type): String = when (t) {
        Type.TInt, Type.TInt64                        -> "i64"
        Type.TFloat, Type.TFloat64                    -> "double"
        Type.TFloat32                                 -> "float"
        Type.TBool                                    -> "i1"
        Type.TStr                                     -> "i8*"
        Type.TByte, Type.TChar                        -> "i8"
        Type.TUnit, Type.TNever                       -> "void"
        is Type.TNullable                             -> llvmType(t.inner)
        is Type.TFuture                               -> llvmType(t.inner)
        is Type.TArray, is Type.TMap, is Type.TResult,
        is Type.TNamed, is Type.TGpuBuffer,
        is Type.TSequence                             -> "i8*"
        else                                          -> "i64"   // TUnknown, TError, TVar, TTuple, etc.
    }

    private fun typeOf(expr: Expr): Type {
        val mapped = tcModule.typeMap[expr.span.start.offset to expr.span.end.offset]
        if (mapped != null && mapped != Type.TUnknown) return mapped
        // Fallback for pattern-bound or locally-defined variables not in typeMap
        if (expr is IdentExpr) {
            val nvType = varTypes[expr.name]
            if (nvType != null) return nvType
            val lt = varAllocas[expr.name]?.second
            if (lt != null) return llvmTypeToType(lt)
        }
        // Fallback for call expressions: if the callee is a known @extern stdlib function,
        // return its declared return type (TypeChecker may have assigned TUnknown for
        // stdlib functions not re-declared inline in user code).
        if (expr is CallExpr && expr.callee is IdentExpr) {
            val ext = externFunctions[(expr.callee as IdentExpr).name]
            if (ext != null) return ext.retType
        }
        // Fallback for index expressions: derive element type from the receiver's array type.
        if (expr is IndexExpr) {
            val receiverType = typeOf(expr.receiver)
            if (receiverType is Type.TArray) return receiverType.element
            if (receiverType is Type.TStr)   return Type.TChar
        }
        // Fallback for member access: look up field type from struct layout.
        if (expr is MemberAccessExpr) {
            val receiverType = typeOf(expr.receiver)
            val typeName = when (receiverType) {
                is Type.TNamed -> receiverType.qualifiedName.substringAfterLast('.')
                else -> null
            }
            if (typeName != null) {
                val fieldType = structLayouts[typeName]?.find { it.first == expr.member }?.second
                if (fieldType != null) return fieldType
            }
        }
        return mapped ?: Type.TUnknown
    }

    /** Reverse-map an LLVM type string to a Nordvest Type (best-effort). */
    private fun llvmTypeToType(lt: String): Type = when (lt) {
        "i64"    -> Type.TInt
        "i32"    -> Type.TInt
        "i1"     -> Type.TBool
        "i8"     -> Type.TChar
        "double" -> Type.TFloat
        "float"  -> Type.TFloat32
        "void"   -> Type.TUnit
        "i8*"    -> Type.TStr   // conservative; may be a named type
        else     -> Type.TUnknown
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fresh register / label / string helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun fresh(prefix: String = "tmp") = "%$prefix.${tempIdx++}"
    private fun freshLabel(prefix: String)    = "$prefix.${labelIdx++}"

    /**
     * Interns [text] as a private global string constant and emits an inline getelementptr
     * in the current function body to get an i8* pointer to it.
     * Returns the SSA register holding the i8*.
     * If called outside a function (e.g. in global init), returns the GEP expression string.
     */
    private fun stringConst(text: String): String {
        val globalName = stringPool.getOrPut(text) {
            val name = "@str.${strIdx++}"
            val escaped = llvmEscape(text)
            val len = llvmByteLen(text) + 1   // +1 for \00
            globals.appendLine("""$name = private unnamed_addr constant [$len x i8] c"$escaped\00", align 1""")
            name
        }
        // Emit a getelementptr to get i8* from the constant
        val gepReg = fresh("gep")
        // We need the array size to form the GEP type
        val len = llvmByteLen(text) + 1
        emit("  $gepReg = getelementptr [$len x i8], [$len x i8]* $globalName, i64 0, i64 0")
        return gepReg
    }

    /**
     * Returns a GEP expression for a *pre-declared* format/runtime constant like @.fmt.s.
     * These always have fixed sizes known at compile time; we use the helper to avoid
     * emitting a fresh GEP register for them (they are used inside the runtime function bodies
     * which we emit as raw strings, not via emitExpr).
     */
    private fun fmtGep(globalName: String, len: Int): String {
        val reg = fresh("fgep")
        emit("  $reg = getelementptr [$len x i8], [$len x i8]* $globalName, i64 0, i64 0")
        return reg
    }

    /** Escape a Kotlin string to LLVM IR c"..." notation. */
    private fun llvmEscape(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\n'  -> append("\\0A")
                '\r'  -> append("\\0D")
                '\t'  -> append("\\09")
                '"'   -> append("\\22")
                '\\'  -> append("\\5C")
                '\u0000' -> append("\\00")
                else  -> {
                    val code = ch.code
                    if (code in 0x20..0x7E) append(ch)
                    else append("\\%02X".format(code))
                }
            }
        }
    }

    /** Number of bytes a string occupies in its LLVM constant (UTF-8 byte count). */
    private fun llvmByteLen(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** Returns the pointer-to-type string for a given LLVM value type. */
    private fun llvmPtrType(lt: String): String = "$lt*"

    // ─────────────────────────────────────────────────────────────────────
    // Per-function emit helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun emit(s: String) {
        if (isTerminated) {
            // Start a fresh unreachable block so the IR remains valid
            val label = freshLabel("dead")
            fnBody.appendLine("$label:")
            isTerminated = false
        }
        fnBody.appendLine(s)
    }

    private fun emitRaw(s: String) {
        fnBody.appendLine(s)
    }

    private fun emitAlloca(name: String, llvmTy: String): String {
        val reg = "%local.$name"
        val align = llvmTypeAlign(llvmTy)
        fnAllocas.appendLine("  $reg = alloca $llvmTy, align $align")
        return reg
    }

    private fun llvmTypeAlign(lt: String): Int = when (lt) {
        "i1"  -> 1
        "i8"  -> 1
        "i32" -> 4
        "i64" -> 8
        "double" -> 8
        "float"  -> 4
        else     -> 8  // pointers and i8*
    }

    /** Emit a typed store: store lt val into lt* ptr. */
    private fun emitStore(lt: String, valReg: String, ptrReg: String) {
        val align = llvmTypeAlign(lt)
        emit("  store $lt $valReg, ${llvmPtrType(lt)} $ptrReg, align $align")
    }

    /** Emit a typed load: load lt from lt* ptr. Returns SSA register name. */
    private fun emitLoad(lt: String, ptrReg: String, hint: String = "load"): String {
        val reg = fresh(hint)
        val align = llvmTypeAlign(lt)
        emit("  $reg = load $lt, ${llvmPtrType(lt)} $ptrReg, align $align")
        return reg
    }

    private fun terminate(instruction: String) {
        fnBody.appendLine(instruction)
        isTerminated = true
    }

    // ─────────────────────────────────────────────────────────────────────
    // Declaration dispatch
    // ─────────────────────────────────────────────────────────────────────

    private fun emitDecl(decl: Decl) {
        when (decl) {
            is FunctionDecl -> {
                // @gpu annotation: emit a comment marking the function as a GPU kernel stub
                if (decl.annotations.any { it.name == "gpu" }) {
                    userFns.appendLine("; @gpu kernel: ${decl.name}")
                }
                // @extern annotation: emit a declare instead of define
                if (decl.annotations.any { it.name == "extern" }) {
                    emitExternDecl(decl)
                } else {
                    emitFunctionDecl(decl)
                }
            }
            is FunctionSignatureDecl -> {
                // @extern annotation on a signature: emit declare
                if (decl.annotations.any { it.name == "extern" }) {
                    emitExternSigDecl(decl)
                }
                // otherwise interface signature — skip
            }
            is StructDecl -> emitStructOrClassDecl(decl.name, decl.constructorParams, decl.members, decl.annotations, isClass = false)
            is ClassDecl  -> emitStructOrClassDecl(decl.name, decl.constructorParams, decl.members, decl.annotations, isClass = true)
            is RecordDecl -> emitStructOrClassDecl(decl.name, decl.constructorParams, decl.members, decl.annotations, isClass = false)
            else          -> { /* unsupported at top-level */ }
        }
    }

    private fun emitExternDecl(fn: FunctionDecl) {
        // Find C symbol name from annotation args, then use fn name
        val cSymbol = fn.annotations.firstOrNull { it.name == "extern" }
            ?.args?.firstOrNull { it.name == "fn" || it.name == null }
            ?.let { arg ->
                when (val v = arg.value) {
                    is nv.compiler.parser.AnnotationStrValue -> v.value
                    is nv.compiler.parser.AnnotationIdentValue -> v.name.text
                    else -> null
                }
            } ?: fn.name
        val nvRetType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
        val retLt = llvmType(nvRetType)
        val paramTypes = fn.params.joinToString(", ") { p -> llvmType(resolveTypeNode(p.type)) }
        // Apple Clang 12 rejects declare+define for the same function in the same module.
        // Skip the declare if this C symbol is already defined inline by the runtime.
        if (cSymbol !in inlineRuntimeFns) {
            declares.appendLine("declare $retLt @$cSymbol($paramTypes)")
        }
    }

    private fun emitExternSigDecl(fn: FunctionSignatureDecl) {
        val cSymbol = fn.annotations.firstOrNull { it.name == "extern" }
            ?.args?.firstOrNull { it.name == "fn" || it.name == null }
            ?.let { arg ->
                when (val v = arg.value) {
                    is nv.compiler.parser.AnnotationStrValue -> v.value
                    is nv.compiler.parser.AnnotationIdentValue -> v.name.text
                    else -> null
                }
            } ?: fn.name
        val nvRetType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
        val retLt = llvmType(nvRetType)
        val paramTypes = fn.params.joinToString(", ") { p -> llvmType(resolveTypeNode(p.type)) }
        if (cSymbol !in inlineRuntimeFns) {
            declares.appendLine("declare $retLt @$cSymbol($paramTypes)")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Function declaration
    // ─────────────────────────────────────────────────────────────────────

    private fun emitFunctionDecl(fn: FunctionDecl) {
        // Reset per-function state
        fnBody    = StringBuilder()
        fnAllocas = StringBuilder()
        varAllocas.clear()
        varTypes.clear()
        tempIdx   = 0
        labelIdx  = 0
        isTerminated = false
        loopStack.clear()

        val isMain = fn.name == "main" && fn.params.isEmpty()

        // Determine LLVM return type
        val nvRetType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
        fnReturnType = if (isMain) "i32" else llvmType(nvRetType)

        // Build parameter list
        val paramList = if (isMain) {
            "i32 %argc, i8** %argv"
        } else {
            fn.params.joinToString(", ") { p ->
                val pt = resolveTypeNode(p.type)
                "${llvmType(pt)} %param.${p.name}"
            }
        }

        val mangledName = if (isMain) "@main" else "@nv_${fn.name}"

        // Emit function header — body built into fnBody + fnAllocas first
        // We'll join them later
        emit("") // no-op to avoid spurious dead-block on first emit

        // Store parameters into allocas
        if (!isMain) {
            for (p in fn.params) {
                val pt = resolveTypeNode(p.type)
                val lt = llvmType(pt)
                val allocaReg = emitAlloca(p.name, lt)
                varAllocas[p.name] = Pair(allocaReg, lt)
                emitStore(lt, "%param.${p.name}", allocaReg)
            }
        }

        // Emit body statements
        for (stmt in fn.body) {
            emitStmt(stmt)
        }

        // Ensure every block has a terminator
        if (!isTerminated) {
            when (fnReturnType) {
                "void" -> terminate("  ret void")
                "i32"  -> terminate("  ret i32 0")
                "i1"   -> terminate("  ret i1 0")
                "i64"  -> terminate("  ret i64 0")
                "double" -> terminate("  ret double 0.0")
                "float"  -> terminate("  ret float 0.0")
                else     -> terminate("  ret i8* null")
            }
        }

        // Assemble full function
        val noreturn = if (fn.name == "nv_panic") " noreturn" else ""
        userFns.appendLine("define $fnReturnType $mangledName($paramList)$noreturn {")
        userFns.appendLine("entry:")
        userFns.append(fnAllocas)
        userFns.append(fnBody)
        userFns.appendLine("}")
        userFns.appendLine()
    }

    private fun emitStructOrClassDecl(name: String, ctorParams: List<ConstructorParam>, members: List<Decl>, annotations: List<nv.compiler.parser.Annotation> = emptyList(), isClass: Boolean = false) {
        val fields = structLayouts[name] ?: return
        // Structs with no fields have nothing to allocate/initialize — skip.
        // Classes with no fields still need a no-arg constructor (RC header allocation).
        if (fields.isEmpty() && !isClass) return

        // Constructor function: @nv_Name(field_types...) → i8*
        // Class types prepend a 16-byte RC header: { i64 strong_count, i8* dtor_fn }
        val userFieldsSize = fields.sumOf { (_, t) -> llvmTypeSize(llvmType(t)) }
        val rcHeaderSize   = if (isClass) 16 else 0
        val allocSize      = maxOf(userFieldsSize + rcHeaderSize, 8)
        val fieldOffset    = if (isClass) 2 else 0   // GEP index of first user field

        fnBody    = StringBuilder()
        fnAllocas = StringBuilder()
        varAllocas.clear()
        varTypes.clear()
        tempIdx   = 0
        labelIdx  = 0
        isTerminated = false
        loopStack.clear()

        val ctorParamCount = structCtorParamCount[name] ?: fields.size
        val ctorFields = fields.take(ctorParamCount)
        val paramList = ctorFields.joinToString(", ") { (fname, ft) -> "${llvmType(ft)} %ctor.$fname" }

        val objReg = fresh("obj")
        emit("  $objReg = call i8* @malloc(i64 $allocSize)")
        val castReg = fresh("cast")
        val structType = "%struct.$name"
        emit("  $castReg = bitcast i8* $objReg to $structType*")

        if (isClass) {
            // Initialize strong_count = 1 (GEP index 0)
            val scPtr = fresh("sc.ptr")
            emit("  $scPtr = getelementptr $structType, $structType* $castReg, i32 0, i32 0")
            emit("  store i64 1, i64* $scPtr, align 8")
            // Store dtor_fn pointer (GEP index 1): null if no RC fields, else @nv_dtor_Name
            val dtorPtr = fresh("dtor.ptr")
            emit("  $dtorPtr = getelementptr $structType, $structType* $castReg, i32 0, i32 1")
            val hasRcFields = fields.any { (_, t) -> isRcType(t) }
            if (hasRcFields) {
                val dtorCast = fresh("dtor.cast")
                emit("  $dtorCast = bitcast void (i8*)* @nv_dtor_$name to i8*")
                emit("  store i8* $dtorCast, i8** $dtorPtr, align 8")
            } else {
                emit("  store i8* null, i8** $dtorPtr, align 8")
            }
        }

        fields.forEachIndexed { idx, (fname, ft) ->
            val fieldLt = llvmType(ft)
            val ptrReg  = fresh("fp")
            val gepIdx  = idx + fieldOffset
            emit("  $ptrReg = getelementptr $structType, $structType* $castReg, i32 0, i32 $gepIdx")
            // Ctor param fields: store from parameter; body/lazy fields: zero-initialize
            val valToStore = if (idx < ctorParamCount) "%ctor.$fname" else defaultValue(fieldLt)
            emit("  store $fieldLt $valToStore, $fieldLt* $ptrReg, align ${llvmTypeAlign(fieldLt)}")
        }
        terminate("  ret i8* $objReg")

        userFns.appendLine("define i8* @nv_$name($paramList) {")
        userFns.appendLine("entry:")
        userFns.append(fnAllocas)
        userFns.append(fnBody)
        userFns.appendLine("}")
        userFns.appendLine()

        // Emit per-class destructor only when there are RC fields to release
        if (isClass && fields.any { (_, t) -> isRcType(t) }) emitClassDestructor(name, fields)

        // Emit member functions
        for (member in members) {
            if (member is FunctionDecl && !member.annotations.any { it.name == "extern" }) {
                emitMethodDecl(name, member)
            }
        }

        // Emit @newtype / @derive auto-generated methods
        val isNewtype = annotations.any { it.name == "newtype" }
        val deriveAnno = annotations.find { it.name == "derive" }
        val derivedTraits = mutableSetOf<String>()
        if (isNewtype) derivedTraits += setOf("Show", "Eq", "Hash", "Compare")
        if (deriveAnno != null) {
            val traitNames = deriveAnno.args.mapNotNull { arg ->
                (arg.value as? AnnotationIdentValue)?.name?.text
            }
            derivedTraits += if ("All" in traitNames) setOf("Show", "Eq", "Compare", "Hash", "Copy") else traitNames
        }
        if ("Show"    in derivedTraits) emitDerivedShow(name, fields, isClass)
        if ("Eq"      in derivedTraits) emitDerivedEq(name, fields, isClass)
        if ("Hash"    in derivedTraits) emitDerivedHash(name, fields, isClass)
        if ("Compare" in derivedTraits) emitDerivedCompare(name, fields, isClass)
        if ("Copy"    in derivedTraits) emitDerivedCopy(name, fields, isClass)

        // Emit @lazy getter methods
        val lazyEntries = lazyFields[name] ?: emptyList()
        for ((fieldName, fieldType, initExpr) in lazyEntries) {
            emitLazyGetter(name, fieldName, fieldType, initExpr, isClass)
        }

        // Emit @config load function
        if (annotations.any { it.name == "config" }) {
            val ctorParamCount = structCtorParamCount[name] ?: fields.size
            emitConfigLoad(name, fields.take(ctorParamCount), isClass)
        }

        // Emit by-delegation forwarding methods
        val delegationList = classDelegations[name] ?: emptyList()
        val definedMethods = members.filterIsInstance<FunctionDecl>().map { it.name }.toSet()
        for ((ifaceName, delegateFieldName, concreteTypeName) in delegationList) {
            val ifacePrefix = "$ifaceName."
            for ((key, type) in tcModule.memberTypeMap) {
                if (!key.startsWith(ifacePrefix)) continue
                val methodName = key.removePrefix(ifacePrefix)
                if (methodName in definedMethods) continue  // overridden in class
                if (type !is Type.TFun) continue
                emitDelegationForwarder(name, methodName, type, delegateFieldName, concreteTypeName, isClass)
            }
        }
    }

    // ─── Derived-method emitters (@newtype / @derive) ─────────────────────

    /**
     * Sets up per-function IR state, calls [block] to emit IR, then flushes
     * the result to [userFns].  Mirrors [emitFunctionDecl] / [emitMethodDecl].
     */
    private fun emitSyntheticMethod(mangledName: String, paramList: String, retType: String, block: () -> Unit) {
        fnBody = StringBuilder(); fnAllocas = StringBuilder()
        varAllocas.clear(); varTypes.clear()
        tempIdx = 0; labelIdx = 0; isTerminated = false; loopStack.clear()
        fnReturnType = retType
        block()
        if (!isTerminated) {
            when (retType) {
                "void"   -> terminate("  ret void")
                "i64"    -> terminate("  ret i64 0")
                "i1"     -> terminate("  ret i1 1")   // default true (e.g. zero-field Eq)
                "double" -> terminate("  ret double 0.0")
                else     -> terminate("  ret i8* null")
            }
        }
        userFns.appendLine("define $retType $mangledName($paramList) {")
        userFns.appendLine("entry:")
        userFns.append(fnAllocas)
        userFns.append(fnBody)
        userFns.appendLine("}")
        userFns.appendLine()
    }

    /** @derive(Show) / @newtype — emit @nv_TypeName_toString returning "TypeName(f1: v1, f2: v2)". */
    private fun emitDerivedShow(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
        val structType  = "%struct.$typeName"
        val fieldOffset = if (isClass) 2 else 0
        emitSyntheticMethod("@nv_${typeName}_toString", "i8* %self", "i8*") {
            val castReg = fresh("show.cast")
            emit("  $castReg = bitcast i8* %self to $structType*")
            var acc = stringConst("$typeName(")
            for ((idx, fp) in fields.withIndex()) {
                val (fname, ft) = fp
                val gepIdx  = idx + fieldOffset
                val fieldLt = llvmType(ft)
                val sep = if (idx == 0) "$fname: " else ", $fname: "
                val sepC = stringConst(sep)
                val r1 = fresh("show.sep")
                emit("  $r1 = call i8* @nv_str_concat(i8* $acc, i8* $sepC)")
                val fpR = fresh("show.fp"); val fvR = fresh("show.fv")
                emit("  $fpR = getelementptr $structType, $structType* $castReg, i32 0, i32 $gepIdx")
                emit("  $fvR = load $fieldLt, $fieldLt* $fpR, align ${llvmTypeAlign(fieldLt)}")
                val fStr = convertToStr(fvR, ft)
                val r2 = fresh("show.acc")
                emit("  $r2 = call i8* @nv_str_concat(i8* $r1, i8* $fStr)")
                acc = r2
            }
            val closeC = stringConst(")")
            val result = fresh("show.result")
            emit("  $result = call i8* @nv_str_concat(i8* $acc, i8* $closeC)")
            terminate("  ret i8* $result")
        }
        methodReturnTypes["${typeName}_toString"] = "i8*"
    }

    /** @derive(Eq) / @newtype — emit @nv_TypeName_op_eq and @nv_TypeName_op_neq. */
    private fun emitDerivedEq(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
        val structType  = "%struct.$typeName"
        val fieldOffset = if (isClass) 2 else 0
        emitSyntheticMethod("@nv_${typeName}_op_eq", "i8* %self, i8* %other", "i1") {
            if (fields.isEmpty()) { terminate("  ret i1 1"); return@emitSyntheticMethod }
            val aC = fresh("eq.a"); val bC = fresh("eq.b")
            emit("  $aC = bitcast i8* %self to $structType*")
            emit("  $bC = bitcast i8* %other to $structType*")
            val falseLabel = freshLabel("eq.false")
            for ((idx, fp) in fields.withIndex()) {
                val (_, ft) = fp
                val gepIdx = idx + fieldOffset
                val fieldLt = llvmType(ft)
                val apR = fresh("eq.ap"); val bpR = fresh("eq.bp")
                val avR = fresh("eq.av"); val bvR = fresh("eq.bv")
                emit("  $apR = getelementptr $structType, $structType* $aC, i32 0, i32 $gepIdx")
                emit("  $bpR = getelementptr $structType, $structType* $bC, i32 0, i32 $gepIdx")
                emit("  $avR = load $fieldLt, $fieldLt* $apR, align ${llvmTypeAlign(fieldLt)}")
                emit("  $bvR = load $fieldLt, $fieldLt* $bpR, align ${llvmTypeAlign(fieldLt)}")
                val cmpR = fresh("eq.cmp")
                when (ft) {
                    Type.TStr              -> emit("  $cmpR = call i1 @nv_str_eq(i8* $avR, i8* $bvR)")
                    Type.TFloat, Type.TFloat64 -> emit("  $cmpR = fcmp oeq double $avR, $bvR")
                    Type.TFloat32          -> emit("  $cmpR = fcmp oeq float $avR, $bvR")
                    Type.TBool             -> emit("  $cmpR = icmp eq i1 $avR, $bvR")
                    else                   -> emit("  $cmpR = icmp eq $fieldLt $avR, $bvR")
                }
                val nextLabel = freshLabel("eq.next$idx")
                emit("  br i1 $cmpR, label %$nextLabel, label %$falseLabel")
                emitRaw("$nextLabel:")
                isTerminated = false
            }
            terminate("  ret i1 1")
            emitRaw("$falseLabel:")
            isTerminated = false
            terminate("  ret i1 0")
        }
        // != delegates to ==
        emitSyntheticMethod("@nv_${typeName}_op_neq", "i8* %self, i8* %other", "i1") {
            val eqR = fresh("neq.eq")
            emit("  $eqR = call i1 @nv_${typeName}_op_eq(i8* %self, i8* %other)")
            val notR = fresh("neq.not")
            emit("  $notR = xor i1 $eqR, 1")
            terminate("  ret i1 $notR")
        }
    }

    /** @derive(Hash) / @newtype — emit @nv_TypeName_hash combining field hashes via nv_hash_combine. */
    private fun emitDerivedHash(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
        val structType  = "%struct.$typeName"
        val fieldOffset = if (isClass) 2 else 0
        emitSyntheticMethod("@nv_${typeName}_hash", "i8* %self", "i64") {
            if (fields.isEmpty()) { terminate("  ret i64 0"); return@emitSyntheticMethod }
            val castR = fresh("hash.cast")
            emit("  $castR = bitcast i8* %self to $structType*")
            var seed = "0"
            for ((idx, fp) in fields.withIndex()) {
                val (_, ft) = fp
                val gepIdx = idx + fieldOffset
                val fieldLt = llvmType(ft)
                val fpR = fresh("hash.fp"); val fvR = fresh("hash.fv")
                emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
                emit("  $fvR = load $fieldLt, $fieldLt* $fpR, align ${llvmTypeAlign(fieldLt)}")
                val fhR = fresh("hash.fh")
                when (ft) {
                    Type.TStr -> emit("  $fhR = call i64 @nv_hash_fnv1a(i8* $fvR)")
                    Type.TFloat, Type.TFloat64 -> {
                        val bcR = fresh("hash.bc")
                        emit("  $bcR = bitcast double $fvR to i64")
                        emit("  $fhR = call i64 @nv_hash_combine(i64 0, i64 $bcR)")
                    }
                    Type.TFloat32 -> {
                        val extR = fresh("hash.ext"); val bcR = fresh("hash.bc")
                        emit("  $extR = fpext float $fvR to double")
                        emit("  $bcR = bitcast double $extR to i64")
                        emit("  $fhR = call i64 @nv_hash_combine(i64 0, i64 $bcR)")
                    }
                    Type.TBool -> {
                        val zR = fresh("hash.z")
                        emit("  $zR = zext i1 $fvR to i64")
                        emit("  $fhR = call i64 @nv_hash_combine(i64 0, i64 $zR)")
                    }
                    else -> emit("  $fhR = call i64 @nv_hash_combine(i64 0, i64 $fvR)")
                }
                val newSeed = fresh("hash.seed")
                emit("  $newSeed = call i64 @nv_hash_combine(i64 $seed, i64 $fhR)")
                seed = newSeed
            }
            terminate("  ret i64 $seed")
        }
        methodReturnTypes["${typeName}_hash"] = "i64"
    }

    /**
     * @derive(Compare) / @newtype — emit @nv_TypeName_compare returning i64 (-1 / 0 / 1).
     * Fields are compared lexicographically.  String fields use hash-order (bootstrap limitation).
     */
    private fun emitDerivedCompare(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
        val structType  = "%struct.$typeName"
        val fieldOffset = if (isClass) 2 else 0
        emitSyntheticMethod("@nv_${typeName}_compare", "i8* %self, i8* %other", "i64") {
            if (fields.isEmpty()) { terminate("  ret i64 0"); return@emitSyntheticMethod }
            val aC = fresh("cmp.a"); val bC = fresh("cmp.b")
            emit("  $aC = bitcast i8* %self to $structType*")
            emit("  $bC = bitcast i8* %other to $structType*")
            for ((idx, fp) in fields.withIndex()) {
                val (_, ft) = fp
                val gepIdx = idx + fieldOffset
                val fieldLt = llvmType(ft)
                val apR = fresh("cmp.ap"); val bpR = fresh("cmp.bp")
                val avR = fresh("cmp.av"); val bvR = fresh("cmp.bv")
                emit("  $apR = getelementptr $structType, $structType* $aC, i32 0, i32 $gepIdx")
                emit("  $bpR = getelementptr $structType, $structType* $bC, i32 0, i32 $gepIdx")
                emit("  $avR = load $fieldLt, $fieldLt* $apR, align ${llvmTypeAlign(fieldLt)}")
                emit("  $bvR = load $fieldLt, $fieldLt* $bpR, align ${llvmTypeAlign(fieldLt)}")
                val ltLabel = freshLabel("cmp.lt$idx"); val gtLabel = freshLabel("cmp.gt$idx")
                val eqLabel = freshLabel("cmp.eq$idx"); val chkGtLabel = "cmp.chkgt.$idx"
                when (ft) {
                    Type.TStr -> {
                        val ahR = fresh("cmp.ah"); val bhR = fresh("cmp.bh")
                        emit("  $ahR = call i64 @nv_hash_fnv1a(i8* $avR)")
                        emit("  $bhR = call i64 @nv_hash_fnv1a(i8* $bvR)")
                        val ltR = fresh("cmp.lt"); val eqR = fresh("cmp.eq")
                        emit("  $ltR = icmp slt i64 $ahR, $bhR")
                        emit("  $eqR = icmp eq i64 $ahR, $bhR")
                        emit("  br i1 $ltR, label %$ltLabel, label %$chkGtLabel")
                        emitRaw("$chkGtLabel:"); isTerminated = false
                        emit("  br i1 $eqR, label %$eqLabel, label %$gtLabel")
                    }
                    Type.TFloat, Type.TFloat64 -> {
                        val ltR = fresh("cmp.lt"); val eqR = fresh("cmp.eq")
                        emit("  $ltR = fcmp olt double $avR, $bvR")
                        emit("  $eqR = fcmp oeq double $avR, $bvR")
                        emit("  br i1 $ltR, label %$ltLabel, label %$chkGtLabel")
                        emitRaw("$chkGtLabel:"); isTerminated = false
                        emit("  br i1 $eqR, label %$eqLabel, label %$gtLabel")
                    }
                    Type.TBool -> {
                        val aE = fresh("cmp.ae"); val bE = fresh("cmp.be")
                        emit("  $aE = zext i1 $avR to i64"); emit("  $bE = zext i1 $bvR to i64")
                        val ltR = fresh("cmp.lt"); val eqR = fresh("cmp.eq")
                        emit("  $ltR = icmp slt i64 $aE, $bE"); emit("  $eqR = icmp eq i64 $aE, $bE")
                        emit("  br i1 $ltR, label %$ltLabel, label %$chkGtLabel")
                        emitRaw("$chkGtLabel:"); isTerminated = false
                        emit("  br i1 $eqR, label %$eqLabel, label %$gtLabel")
                    }
                    else -> {
                        val ltR = fresh("cmp.lt"); val eqR = fresh("cmp.eq")
                        emit("  $ltR = icmp slt i64 $avR, $bvR"); emit("  $eqR = icmp eq i64 $avR, $bvR")
                        emit("  br i1 $ltR, label %$ltLabel, label %$chkGtLabel")
                        emitRaw("$chkGtLabel:"); isTerminated = false
                        emit("  br i1 $eqR, label %$eqLabel, label %$gtLabel")
                    }
                }
                emitRaw("$ltLabel:"); isTerminated = false; terminate("  ret i64 -1")
                emitRaw("$gtLabel:"); isTerminated = false; terminate("  ret i64 1")
                emitRaw("$eqLabel:"); isTerminated = false
                // continue to next field
            }
            terminate("  ret i64 0")
        }
        methodReturnTypes["${typeName}_compare"] = "i64"
    }

    /** @derive(Copy) / @newtype — emit @nv_TypeName_copy using the constructor for a field-for-field duplicate. */
    private fun emitDerivedCopy(typeName: String, fields: List<Pair<String, Type>>, isClass: Boolean) {
        val structType  = "%struct.$typeName"
        val fieldOffset = if (isClass) 2 else 0
        emitSyntheticMethod("@nv_${typeName}_copy", "i8* %self", "i8*") {
            val castR = fresh("copy.cast")
            emit("  $castR = bitcast i8* %self to $structType*")
            val fieldRegsAndTypes = fields.mapIndexed { idx, (_, ft) ->
                val gepIdx = idx + fieldOffset; val fieldLt = llvmType(ft)
                val fpR = fresh("copy.fp"); val fvR = fresh("copy.fv")
                emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
                emit("  $fvR = load $fieldLt, $fieldLt* $fpR, align ${llvmTypeAlign(fieldLt)}")
                Pair(fvR, fieldLt)
            }
            val argList = fieldRegsAndTypes.joinToString(", ") { (r, lt) -> "$lt $r" }
            val res = fresh("copy.result")
            emit("  $res = call i8* @nv_$typeName($argList)")
            terminate("  ret i8* $res")
        }
        methodReturnTypes["${typeName}_copy"] = "i8*"
    }

    // ─── @config load emitter ─────────────────────────────────────────────

    /**
     * Emit `@nv_TypeName_config_load() → Result<T>` for a `@config("prefix")` annotated type.
     *
     * For each constructor parameter:
     *  - Reads the env var `PREFIX_FIELDNAME` (or the explicit `@env("VAR")` name).
     *  - Required fields (no default): returns `Err("ConfigError: missing…")` if absent.
     *  - Optional fields (has default or is nullable): falls back to the default value.
     * Returns `Ok(TypeName(…))` when all required fields are present.
     */
    private fun emitConfigLoad(typeName: String, ctorFields: List<Pair<String, Type>>, isClass: Boolean) {
        val prefix       = configPrefixes[typeName] ?: typeName
        val envOverrides = configEnvOverrides[typeName] ?: ctorFields.map { (n, _) -> n to null }
        val defaults     = structCtorDefaults[typeName] ?: emptyList()

        emitSyntheticMethod("@nv_${typeName}_config_load", "", "i8*") {
            val fieldRegs = mutableListOf<Pair<String, String>>()  // (reg, llvmType)

            for ((idx, fieldPair) in ctorFields.withIndex()) {
                val (fieldName, fieldType) = fieldPair
                val llvmT    = llvmType(fieldType)
                val envName  = envOverrides.getOrNull(idx)?.second
                    ?: "${toUpperSnake(prefix)}_${toUpperSnake(fieldName)}"
                val defaultExpr = defaults.firstOrNull { it.first == fieldName }?.second
                val hasDefault  = defaultExpr != null || fieldType is Type.TNullable

                // Read env var
                val envNameReg = stringConst(envName)
                val rawReg     = fresh("cfg.raw")
                emit("  $rawReg = call i8* @getenv(i8* $envNameReg)")
                val isNullReg  = fresh("cfg.null")
                emit("  $isNullReg = icmp eq i8* $rawReg, null")

                if (!hasDefault) {
                    // Required: error if absent
                    val missLabel = freshLabel("cfg.miss")
                    val okLabel   = freshLabel("cfg.ok")
                    emit("  br i1 $isNullReg, label %$missLabel, label %$okLabel")
                    emitRaw("$missLabel:"); isTerminated = false
                    val errMsgReg = stringConst("ConfigError: missing required field '$fieldName' (env: $envName)")
                    val errReg = fresh("cfg.err")
                    emit("  $errReg = call i8* @nv_Err(i8* $errMsgReg)")
                    terminate("  ret i8* $errReg")
                    emitRaw("$okLabel:"); isTerminated = false
                    fieldRegs.add(parseEnvValue(rawReg, fieldType) to llvmT)
                } else {
                    // Optional: phi between parsed value and default
                    val parseLabel = freshLabel("cfg.prs")
                    val defLabel   = freshLabel("cfg.def")
                    val doneLabel  = freshLabel("cfg.done")
                    emit("  br i1 $isNullReg, label %$defLabel, label %$parseLabel")

                    emitRaw("$parseLabel:"); isTerminated = false
                    val parsedReg = parseEnvValue(rawReg, fieldType)
                    emit("  br label %$doneLabel")

                    emitRaw("$defLabel:"); isTerminated = false
                    val defReg = when {
                        defaultExpr != null -> emitExpr(defaultExpr)
                        fieldType is Type.TNullable -> "null"
                        else -> nullConstant(llvmT)
                    }
                    emit("  br label %$doneLabel")

                    emitRaw("$doneLabel:"); isTerminated = false
                    val phiReg = fresh("cfg.v")
                    emit("  $phiReg = phi $llvmT [ $parsedReg, %$parseLabel ], [ $defReg, %$defLabel ]")
                    fieldRegs.add(phiReg to llvmT)
                }
            }

            // Construct the type and wrap in Ok
            val argList = fieldRegs.joinToString(", ") { (r, lt) -> "$lt $r" }
            val objReg  = fresh("cfg.obj")
            emit("  $objReg = call i8* @nv_$typeName($argList)")
            val okReg = fresh("cfg.ok")
            emit("  $okReg = call i8* @nv_Ok(i8* $objReg)")
            terminate("  ret i8* $okReg")
        }
        methodReturnTypes["${typeName}_config_load"] = "i8*"
    }

    /**
     * Parse a raw env-var string into the target LLVM type.
     * Called inside an `emitSyntheticMethod` block where `emit()` targets `fnBody`.
     */
    private fun parseEnvValue(rawReg: String, fieldType: Type): String = when (fieldType) {
        Type.TStr -> rawReg
        Type.TInt, Type.TInt64 -> {
            val r = fresh("cfg.int"); emit("  $r = call i64 @atoll(i8* $rawReg)"); r
        }
        Type.TFloat, Type.TFloat64 -> {
            val r = fresh("cfg.flt"); emit("  $r = call double @atof(i8* $rawReg)"); r
        }
        Type.TBool -> {
            val trueReg = stringConst("true")
            val boolReg = fresh("cfg.bool")
            emit("  $boolReg = call i1 @nv_str_eq(i8* $rawReg, i8* $trueReg)")
            boolReg
        }
        is Type.TNullable -> rawReg   // nullable str: pass raw string (may be null — handled by caller)
        else -> rawReg
    }

    /** Convert a camelCase or lowercase string to UPPER_SNAKE_CASE for env var names. */
    private fun toUpperSnake(s: String): String {
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (c.isUpperCase() && i > 0 && s[i - 1].isLowerCase()) sb.append('_')
            sb.append(c.uppercaseChar())
        }
        return sb.toString()
    }

    // ─── @lazy getter emitter ─────────────────────────────────────────────

    /**
     * Emit `@nv_TypeName_get_fieldName(i8* %self) → T` — checks the init flag,
     * computes and caches the value on first access, returns the cached value on subsequent calls.
     */
    private fun emitLazyGetter(typeName: String, fieldName: String, fieldType: Type, initExpr: Expr, isClass: Boolean) {
        val retLt      = llvmType(fieldType)
        val structType = "%struct.$typeName"
        val fieldOffset = if (isClass) 2 else 0
        val allFields  = structLayouts[typeName] ?: return
        val ctorCount  = structCtorParamCount[typeName] ?: 0

        val initFlagFieldName = "_lazy_${fieldName}_init"
        val initFlagIdx = allFields.indexOfFirst { it.first == initFlagFieldName }.let { if (it < 0) return else it } + fieldOffset
        val valueIdx    = allFields.indexOfFirst { it.first == fieldName }.let { if (it < 0) return else it } + fieldOffset

        emitSyntheticMethod("@nv_${typeName}_get_$fieldName", "i8* %self", retLt) {
            // Store self param so that member-access expressions resolve correctly
            val selfAlloca = emitAlloca("self", "i8*")
            varAllocas["self"] = Pair(selfAlloca, "i8*")
            emitStore("i8*", "%self", selfAlloca)

            // Cast self to struct pointer
            val castR = fresh("lz.cast")
            emit("  $castR = bitcast i8* %self to $structType*")

            // Pre-compute GEP pointers (usable in both branches)
            val initFlagPtrR = fresh("lz.ifp")
            emit("  $initFlagPtrR = getelementptr $structType, $structType* $castR, i32 0, i32 $initFlagIdx")
            val valPtrR = fresh("lz.vp")
            emit("  $valPtrR = getelementptr $structType, $structType* $castR, i32 0, i32 $valueIdx")

            // Load init flag
            val initFlagR = fresh("lz.flag")
            emit("  $initFlagR = load i1, i1* $initFlagPtrR, align 1")

            // Branch: hit (already computed) vs miss (need to compute)
            val hitLabel  = freshLabel("lz.hit")
            val missLabel = freshLabel("lz.miss")
            val retLabel  = freshLabel("lz.ret")
            emit("  br i1 $initFlagR, label %$hitLabel, label %$missLabel")

            // ── Hit: load cached value ────────────────────────────────────────
            emitRaw("$hitLabel:")
            isTerminated = false
            val cachedR = fresh("lz.cached")
            emit("  $cachedR = load $retLt, $retLt* $valPtrR, align ${llvmTypeAlign(retLt)}")
            emit("  br label %$retLabel")

            // ── Miss: evaluate init expression, store result, set flag ────────
            emitRaw("$missLabel:")
            isTerminated = false

            // Pre-load all ctor-param fields into varAllocas so the init expression
            // can reference them by name (e.g. `source` instead of `self.source`).
            for (i in 0 until ctorCount) {
                val (fname, ft) = allFields[i]
                val fldLt  = llvmType(ft)
                val gepIdx = i + fieldOffset
                val fpR    = fresh("lz.fp")
                emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
                val fvR = fresh("lz.fv")
                emit("  $fvR = load $fldLt, $fldLt* $fpR, align ${llvmTypeAlign(fldLt)}")
                val allocaR = emitAlloca(fname, fldLt)
                varAllocas[fname] = Pair(allocaR, fldLt)
                varTypes[fname]   = ft
                emitStore(fldLt, fvR, allocaR)
            }

            // Evaluate the initializer expression
            val computedR = emitExpr(initExpr)

            // Store computed value and set init flag
            emit("  store $retLt $computedR, $retLt* $valPtrR, align ${llvmTypeAlign(retLt)}")
            emit("  store i1 1, i1* $initFlagPtrR, align 1")
            emit("  br label %$retLabel")

            // ── Return: phi selects cached vs. newly-computed ─────────────────
            emitRaw("$retLabel:")
            isTerminated = false
            val resultR = fresh("lz.result")
            emit("  $resultR = phi $retLt [ $cachedR, %$hitLabel ], [ $computedR, %$missLabel ]")
            terminate("  ret $retLt $resultR")
        }
        methodReturnTypes["${typeName}_get_$fieldName"] = retLt
    }

    // ─── by-delegation forwarding emitter ────────────────────────────────

    /**
     * Emit a forwarding method `@nv_TypeName_methodName` that loads the delegate field
     * from `self` and calls the corresponding method on the concrete delegate type.
     */
    private fun emitDelegationForwarder(
        typeName: String,
        methodName: String,
        methodType: Type.TFun,
        delegateFieldName: String,
        concreteTypeName: String,
        isClass: Boolean,
    ) {
        val paramTypes  = methodType.params
        val retType     = methodType.returnType
        val retLt       = llvmType(retType)
        val structType  = "%struct.$typeName"
        val fieldOffset = if (isClass) 2 else 0
        val allFields   = structLayouts[typeName] ?: return
        val delegateIdx = allFields.indexOfFirst { it.first == delegateFieldName }
        if (delegateIdx < 0) return
        val gepIdx = delegateIdx + fieldOffset

        val paramList = buildString {
            append("i8* %self")
            paramTypes.forEachIndexed { i, t -> append(", ${llvmType(t)} %fwd.p$i") }
        }

        emitSyntheticMethod("@nv_${typeName}_$methodName", paramList, retLt) {
            val castR = fresh("fwd.cast")
            emit("  $castR = bitcast i8* %self to $structType*")
            val dptrR = fresh("fwd.dptr")
            emit("  $dptrR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
            val delR = fresh("fwd.del")
            emit("  $delR = load i8*, i8** $dptrR, align 8")

            val argList = buildString {
                append("i8* $delR")
                paramTypes.forEachIndexed { i, t -> append(", ${llvmType(t)} %fwd.p$i") }
            }

            if (retLt == "void") {
                emit("  call void @nv_${concreteTypeName}_$methodName($argList)")
                terminate("  ret void")
            } else {
                val res = fresh("fwd.res")
                emit("  $res = call $retLt @nv_${concreteTypeName}_$methodName($argList)")
                terminate("  ret $retLt $res")
            }
        }
        methodReturnTypes["${typeName}_$methodName"] = retLt
    }

    // ─────────────────────────────────────────────────────────────────────

    /** Emit a destructor @nv_dtor_Name that releases all RC-typed fields then frees the object. */
    private fun emitClassDestructor(name: String, fields: List<Pair<String, Type>>) {
        val structType = "%struct.$name"
        val rcFields   = fields.withIndex().filter { (_, pair) -> isRcType(pair.second) }

        userFns.appendLine("define void @nv_dtor_$name(i8* %ptr) {")
        userFns.appendLine("entry:")
        if (rcFields.isNotEmpty()) {
            userFns.appendLine("  %dtor.cast = bitcast i8* %ptr to $structType*")
            for ((idx, pair) in rcFields) {
                val (fname, _) = pair
                val gepIdx = idx + 2  // +2 to skip RC header fields
                userFns.appendLine("  %dtor.fp.$fname = getelementptr $structType, $structType* %dtor.cast, i32 0, i32 $gepIdx")
                userFns.appendLine("  %dtor.fv.$fname = load i8*, i8** %dtor.fp.$fname, align 8")
                userFns.appendLine("  call void @nv_rc_release(i8* %dtor.fv.$fname)")
            }
        }
        userFns.appendLine("  call void @free(i8* %ptr)")
        userFns.appendLine("  ret void")
        userFns.appendLine("}")
        userFns.appendLine()
    }

    /** True if type t is a reference-counted class type (or nullable wrapper of one). */
    private fun isRcType(t: Type): Boolean = when (t) {
        is Type.TNamed    -> t.qualifiedName.substringAfterLast('.') in classTypeNames
        is Type.TNullable -> isRcType(t.inner)
        else              -> false
    }

    private fun emitMethodDecl(typeName: String, fn: FunctionDecl) {
        fnBody    = StringBuilder()
        fnAllocas = StringBuilder()
        varAllocas.clear()
        varTypes.clear()
        tempIdx   = 0
        labelIdx  = 0
        isTerminated = false
        loopStack.clear()

        val nvRetType = fn.returnType?.let { resolveTypeNode(it) } ?: Type.TUnit
        fnReturnType = llvmType(nvRetType)

        // self as first param (i8*)
        val selfAlloca = emitAlloca("self", "i8*")
        varAllocas["self"] = Pair(selfAlloca, "i8*")
        varTypes["self"] = Type.TNamed(typeName)
        emitStore("i8*", "%self", selfAlloca)

        // Other params
        for (p in fn.params) {
            val pt = resolveTypeNode(p.type)
            val lt = llvmType(pt)
            val allocaReg = emitAlloca(p.name, lt)
            varAllocas[p.name] = Pair(allocaReg, lt)
            emitStore(lt, "%param.${p.name}", allocaReg)
        }

        for (stmt in fn.body) emitStmt(stmt)
        if (!isTerminated) {
            when (fnReturnType) {
                "void"   -> terminate("  ret void")
                "i32"    -> terminate("  ret i32 0")
                "i64"    -> terminate("  ret i64 0")
                "i1"     -> terminate("  ret i1 0")
                "double" -> terminate("  ret double 0.0")
                else     -> terminate("  ret i8* null")
            }
        }

        val opSuffix = operatorToSuffix(fn.name)
        val mangledName = if (opSuffix != null) "@nv_${typeName}_op_$opSuffix" else "@nv_${typeName}_${fn.name}"
        val paramList = buildString {
            append("i8* %self")
            for (p in fn.params) append(", ${llvmType(resolveTypeNode(p.type))} %param.${p.name}")
        }

        // Register return type for use by call sites
        methodReturnTypes["${typeName}_${fn.name}"] = fnReturnType

        userFns.appendLine("define $fnReturnType $mangledName($paramList) {")
        userFns.appendLine("entry:")
        userFns.append(fnAllocas)
        userFns.append(fnBody)
        userFns.appendLine("}")
        userFns.appendLine()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Statement emit
    // ─────────────────────────────────────────────────────────────────────

    private fun emitStmt(stmt: Stmt) {
        when (stmt) {
            is LetStmt        -> emitLetVarStmt(stmt.binding, stmt.typeAnnotation, stmt.initializer, mutable = false)
            is VarStmt        -> emitLetVarStmt(stmt.binding, stmt.typeAnnotation, stmt.initializer, mutable = true)
            is ReturnStmt     -> emitReturnStmt(stmt)
            is ExprStmt       -> { emitExpr(stmt.expr); Unit }
            is IfStmt         -> emitIfStmt(stmt)
            is WhileStmt      -> emitWhileStmt(stmt)
            is ForStmt        -> emitForStmt(stmt)
            is AssignStmt     -> emitAssignStmt(stmt)
            is MatchStmt      -> emitMatchExpr(stmt.expr)
            is BreakStmt      -> emitBreakStmt(stmt)
            is ContinueStmt   -> emitContinueStmt(stmt)
            is GuardLetStmt   -> emitGuardLetStmt(stmt)
            is UnsafeBlock    -> stmt.stmts.forEach { emitStmt(it) }
            is AsmStmt        -> emitAsmStmt(stmt)
            is BytesStmt      -> emitBytesStmt(stmt)
            is CBlockStmt     -> {
                emit("  ; @c block (passthrough to C compiler)")
                for (line in stmt.lines) emit("  ; $line")
            }
            is CppBlockStmt   -> {
                emit("  ; @cpp block (passthrough to C++ compiler)")
                for (line in stmt.lines) emit("  ; $line")
            }
            is DeferStmt      -> { /* Phase 1.5: defer not fully supported */ }
            is TryCatchStmt   -> emitTryCatchStmt(stmt)
            is ThrowStmt      -> { emitExpr(stmt.expr); Unit }
            is GoStmt         -> emitGoStmt(stmt)
            is SpawnStmt      -> { emitExpr(stmt.expr); Unit }
            is SelectStmt     -> emitSelectStmt(stmt)
            else              -> { /* unsupported */ }
        }
    }

    private fun emitLetVarStmt(
        binding: Binding,
        typeAnnotation: TypeNode?,
        initializer: Expr?,
        mutable: Boolean,
    ) {
        when (binding) {
            is IdentBinding -> {
                val name   = binding.name
                val initReg = initializer?.let { emitExpr(it) }
                val ty = when {
                    typeAnnotation != null -> resolveTypeNode(typeAnnotation)
                    initializer != null    -> typeOf(initializer)
                    else                   -> Type.TUnknown
                }
                val lt = if (llvmType(ty) == "void") "i64" else llvmType(ty)
                val allocaReg = emitAlloca(name, lt)
                varAllocas[name] = Pair(allocaReg, lt)
                if (ty != Type.TUnknown) varTypes[name] = ty
                if (initReg != null) {
                    emitStore(lt, initReg, allocaReg)
                }
            }
            is TupleBinding -> {
                // Simplified: treat as a single allocation; destructuring not fully supported
                val initReg = initializer?.let { emitExpr(it) }
                for (name in binding.names) {
                    val allocaReg = emitAlloca(name, "i64")
                    varAllocas[name] = Pair(allocaReg, "i64")
                }
                // Best-effort: store the whole value into the first binding
                if (initReg != null && binding.names.isNotEmpty()) {
                    val (firstReg, _) = varAllocas[binding.names.first()]!!
                    emitStore("i64", initReg, firstReg)
                }
            }
        }
    }

    private fun emitReturnStmt(stmt: ReturnStmt) {
        if (stmt.value == null) {
            if (fnReturnType == "i32") terminate("  ret i32 0")
            else terminate("  ret void")
            return
        }
        val valueType = typeOf(stmt.value)
        // If the declared return is void/unit or the value is unit (TupleLiteralExpr([])), treat as void
        val isUnitReturn = fnReturnType == "void" ||
                           valueType == Type.TUnit ||
                           (stmt.value is TupleLiteralExpr && (stmt.value as TupleLiteralExpr).elements.isEmpty())
        if (isUnitReturn) {
            // Still emit the expression for its side effects (e.g. `→ println(...)` in a match arm)
            if (stmt.value != null && stmt.value !is TupleLiteralExpr) emitExpr(stmt.value)
            if (fnReturnType == "i32") terminate("  ret i32 0")
            else terminate("  ret void")
            return
        }
        val valReg = emitExpr(stmt.value)
        val coerced = coerceToType(valReg, valueType, fnReturnType)
        terminate("  ret $fnReturnType $coerced")
    }

    private fun emitIfStmt(stmt: IfStmt) {
        val thenLabel  = freshLabel("if.then")
        val mergeLabel = freshLabel("if.merge")

        // If-let (nullable unwrap)
        if (stmt.letBinding != null) {
            val srcReg  = emitExpr(stmt.condition)
            val srcType = typeOf(stmt.condition)
            val innerLt = llvmType((srcType as? Type.TNullable)?.inner ?: srcType)

            val elseLabel = freshLabel("if.else")
            val isNull = fresh("isnull")
            emit("  $isNull = icmp eq $innerLt $srcReg, ${nullConstant(innerLt)}")
            emit("  br i1 $isNull, label %$elseLabel, label %$thenLabel")
            emitRaw("$thenLabel:")
            isTerminated = false

            // Bind the unwrapped variable
            val bindName = stmt.letBinding.name
            val allocaReg = emitAlloca(bindName, innerLt)
            varAllocas[bindName] = Pair(allocaReg, innerLt)
            emitStore(innerLt, srcReg, allocaReg)

            stmt.thenBody.forEach { emitStmt(it) }
            if (!isTerminated) emit("  br label %$mergeLabel")

            emitRaw("$elseLabel:")
            isTerminated = false
            stmt.elseBody?.forEach { emitStmt(it) }
            if (!isTerminated) emit("  br label %$mergeLabel")

            emitRaw("$mergeLabel:")
            isTerminated = false
            return
        }

        val condReg = emitExpr(stmt.condition)
        val condI1  = coerceToI1(condReg, typeOf(stmt.condition))

        val hasElse = stmt.elseBody != null || stmt.elseIfClauses.isNotEmpty()
        val elseLabel = if (hasElse) freshLabel("if.else") else mergeLabel

        emit("  br i1 $condI1, label %$thenLabel, label %$elseLabel")
        emitRaw("$thenLabel:")
        isTerminated = false
        stmt.thenBody.forEach { emitStmt(it) }
        if (!isTerminated) emit("  br label %$mergeLabel")

        // else-if chains
        var currentElse = elseLabel
        val clauseLabels = stmt.elseIfClauses.mapIndexed { i, clause ->
            val clauseThen  = freshLabel("elif.then")
            val clauseMerge = mergeLabel
            Triple(clause, clauseThen, clauseMerge)
        }

        if (stmt.elseIfClauses.isNotEmpty()) {
            emitRaw("$currentElse:")
            isTerminated = false
            for ((idx, triple) in clauseLabels.withIndex()) {
                val (clause, clauseThen, _) = triple
                val nextElse = if (idx < clauseLabels.size - 1) {
                    freshLabel("elif.else")
                } else if (stmt.elseBody != null) {
                    freshLabel("if.else.final")
                } else {
                    mergeLabel
                }
                val clauseCond = emitExpr(clause.condition)
                val clauseI1   = coerceToI1(clauseCond, typeOf(clause.condition))
                emit("  br i1 $clauseI1, label %$clauseThen, label %$nextElse")
                emitRaw("$clauseThen:")
                isTerminated = false
                clause.body.forEach { emitStmt(it) }
                if (!isTerminated) emit("  br label %$mergeLabel")
                currentElse = nextElse
                if (idx < clauseLabels.size - 1) {
                    emitRaw("$nextElse:")
                    isTerminated = false
                }
            }
            // Final else
            if (stmt.elseBody != null) {
                emitRaw("$currentElse:")
                isTerminated = false
                stmt.elseBody.forEach { emitStmt(it) }
                if (!isTerminated) emit("  br label %$mergeLabel")
            }
        } else if (stmt.elseBody != null) {
            emitRaw("$elseLabel:")
            isTerminated = false
            stmt.elseBody.forEach { emitStmt(it) }
            if (!isTerminated) emit("  br label %$mergeLabel")
        }

        emitRaw("$mergeLabel:")
        isTerminated = false
    }

    private fun emitWhileStmt(stmt: WhileStmt) {
        val condLabel = freshLabel("while.cond")
        val bodyLabel = freshLabel("while.body")
        val endLabel  = freshLabel("while.end")

        loopStack.addLast(LoopTarget(condLabel, endLabel, stmt.label))
        emit("  br label %$condLabel")
        emitRaw("$condLabel:")
        isTerminated = false

        val condReg = emitExpr(stmt.condition)
        val condI1  = coerceToI1(condReg, typeOf(stmt.condition))
        emit("  br i1 $condI1, label %$bodyLabel, label %$endLabel")

        emitRaw("$bodyLabel:")
        isTerminated = false
        stmt.body.forEach { emitStmt(it) }
        if (!isTerminated) emit("  br label %$condLabel")

        loopStack.removeLast()
        emitRaw("$endLabel:")
        isTerminated = false
    }

    private fun emitForStmt(stmt: ForStmt) {
        val condLabel = freshLabel("for.cond")
        val bodyLabel = freshLabel("for.body")
        val endLabel  = freshLabel("for.end")

        loopStack.addLast(LoopTarget(condLabel, endLabel, stmt.label))

        val iterable = stmt.iterable
        if (iterable is RangeExpr) {
            emitForRange(stmt, iterable, condLabel, bodyLabel, endLabel)
        } else {
            // Generic iterable: iterate over array using index
            emitForArray(stmt, iterable, condLabel, bodyLabel, endLabel)
        }

        loopStack.removeLast()
        emitRaw("$endLabel:")
        isTerminated = false
    }

    private fun emitForRange(
        stmt: ForStmt,
        range: RangeExpr,
        condLabel: String,
        bodyLabel: String,
        endLabel: String,
    ) {
        val startReg = emitExpr(range.start)
        val endReg   = emitExpr(range.end)
        val startI64 = coerceToI64(startReg, typeOf(range.start))
        val endI64   = coerceToI64(endReg, typeOf(range.end))

        // counter alloca
        val counterReg = emitAlloca("for.counter.${labelIdx}", "i64")
        emitStore("i64", startI64, counterReg)
        emit("  br label %$condLabel")

        emitRaw("$condLabel:")
        isTerminated = false
        val cv = emitLoad("i64", counterReg, "cv")

        // condition depends on range kind
        val cond = fresh("for.cond")
        when (range.kind) {
            RangeKind.HALF_OPEN_RIGHT -> emit("  $cond = icmp slt i64 $cv, $endI64")  // [a, b[
            RangeKind.CLOSED          -> emit("  $cond = icmp sle i64 $cv, $endI64")  // [a, b]
            RangeKind.OPEN            -> emit("  $cond = icmp slt i64 $cv, $endI64")  // ]a, b[ (start already +1 handled below)
            RangeKind.HALF_OPEN_LEFT  -> emit("  $cond = icmp sle i64 $cv, $endI64")  // ]a, b]
        }
        emit("  br i1 $cond, label %$bodyLabel, label %$endLabel")

        emitRaw("$bodyLabel:")
        isTerminated = false

        // Bind the loop variable
        val bindName = when (val b = stmt.binding) {
            is IdentBinding -> b.name
            is TupleBinding -> b.names.firstOrNull() ?: "_"
        }
        val bindAlloca = emitAlloca(bindName, "i64")
        varAllocas[bindName] = Pair(bindAlloca, "i64")
        emitStore("i64", cv, bindAlloca)

        stmt.body.forEach { emitStmt(it) }

        // Increment
        if (!isTerminated) {
            val next = fresh("next")
            emit("  $next = add i64 $cv, 1")
            emitStore("i64", next, counterReg)
            emit("  br label %$condLabel")
        }
    }

    private fun emitForArray(
        stmt: ForStmt,
        iterableExpr: Expr,
        condLabel: String,
        bodyLabel: String,
        endLabel: String,
    ) {
        val arrReg = emitExpr(iterableExpr)
        val iterableType = typeOf(iterableExpr)

        val elemType = when (iterableType) {
            is Type.TArray -> iterableType.element
            Type.TStr      -> Type.TChar
            else           -> Type.TStr  // best-effort fallback for TUnknown collections
        }
        val elemLt   = llvmType(elemType)
        val elemSize = llvmTypeSize(elemLt).coerceAtLeast(1).toLong()

        // Load count from the 8-byte header at the start of the array
        val hdrPtr   = fresh("arr.hdr")
        emit("  $hdrPtr = bitcast i8* $arrReg to i64*")
        val countReg = fresh("arr.count")
        emit("  $countReg = load i64, i64* $hdrPtr, align 8")

        val idxReg = emitAlloca("for.idx.${labelIdx}", "i64")
        emitStore("i64", "0", idxReg)
        emit("  br label %$condLabel")

        emitRaw("$condLabel:")
        isTerminated = false
        val curIdx = fresh("for.i")
        emit("  $curIdx = load i64, i64* $idxReg, align 8")
        val cond = fresh("for.cond")
        emit("  $cond = icmp slt i64 $curIdx, $countReg")
        emit("  br i1 $cond, label %$bodyLabel, label %$endLabel")

        emitRaw("$bodyLabel:")
        isTerminated = false

        // Pointer to element data (starts at offset 8)
        val dataStart = fresh("arr.data")
        emit("  $dataStart = getelementptr i8, i8* $arrReg, i64 8")
        val elemValReg = if (elemLt == "i8") {
            val ptrReg = fresh("elem.ptr")
            emit("  $ptrReg = getelementptr i8, i8* $dataStart, i64 $curIdx")
            val r = fresh("elem.val")
            emit("  $r = load i8, i8* $ptrReg, align 1")
            r
        } else {
            val castReg = fresh("elem.cast")
            emit("  $castReg = bitcast i8* $dataStart to ${llvmPtrType(elemLt)}")
            val ptrReg = fresh("elem.ptr")
            emit("  $ptrReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $castReg, i64 $curIdx")
            val r = fresh("elem.val")
            emit("  $r = load $elemLt, ${llvmPtrType(elemLt)} $ptrReg, align $elemSize")
            r
        }

        // Bind loop variable
        val bindName = when (val b = stmt.binding) {
            is IdentBinding -> b.name
            is TupleBinding -> b.names.firstOrNull() ?: "_"
        }
        if (bindName != "_") {
            val bindAlloca = emitAlloca(bindName, elemLt)
            varAllocas[bindName] = Pair(bindAlloca, elemLt)
            emitStore(elemLt, elemValReg, bindAlloca)
        }

        stmt.body.forEach { emitStmt(it) }

        // Increment and loop back
        if (!isTerminated) {
            val nextIdx = fresh("next.i")
            emit("  $nextIdx = add i64 $curIdx, 1")
            emitStore("i64", nextIdx, idxReg)
            emit("  br label %$condLabel")
        }
    }

    private fun emitAssignStmt(stmt: AssignStmt) {
        val valReg = emitExpr(stmt.value)
        when (stmt.target) {
            is IdentExpr -> {
                val name = stmt.target.name
                val entry = varAllocas[name]
                if (entry != null) {
                    val (allocaReg, lt) = entry
                    val coerced = if (stmt.op == AssignOp.ASSIGN) {
                        coerceToType(valReg, typeOf(stmt.value), lt)
                    } else {
                        // Compound assignment
                        val cur = emitLoad(lt, allocaReg, "cur")
                        val valCoerced = coerceToType(valReg, typeOf(stmt.value), lt)
                        val res = fresh("res")
                        val op = assignOpToLlvm(stmt.op, lt)
                        emit("  $res = $op $lt $cur, $valCoerced")
                        res
                    }
                    emitStore(lt, coerced, allocaReg)
                }
            }
            is IndexExpr -> {
                // Phase 1.5: simplified — not fully supported
            }
            is MemberAccessExpr -> {
                // Phase 1.5: simplified — not fully supported
            }
            else -> { /* unsupported lvalue */ }
        }
    }

    private fun assignOpToLlvm(op: AssignOp, lt: String): String = when (op) {
        AssignOp.PLUS_ASSIGN      -> if (lt == "double" || lt == "float") "fadd" else "add"
        AssignOp.MINUS_ASSIGN     -> if (lt == "double" || lt == "float") "fsub" else "sub"
        AssignOp.STAR_ASSIGN      -> if (lt == "double" || lt == "float") "fmul" else "mul"
        AssignOp.SLASH_ASSIGN     -> if (lt == "double" || lt == "float") "fdiv" else "sdiv"
        AssignOp.INT_DIV_ASSIGN   -> "sdiv"
        AssignOp.MOD_ASSIGN       -> "srem"
        AssignOp.AMP_ASSIGN       -> "and"
        AssignOp.PIPE_ASSIGN      -> "or"
        AssignOp.XOR_ASSIGN       -> "xor"
        AssignOp.LSHIFT_ASSIGN    -> "shl"
        AssignOp.RSHIFT_ASSIGN    -> "ashr"
        else                      -> "add"
    }

    private fun emitBreakStmt(stmt: BreakStmt) {
        val target = if (stmt.label != null) {
            loopStack.lastOrNull { it.userLabel == stmt.label }
        } else {
            loopStack.lastOrNull()
        }
        if (target != null) {
            terminate("  br label %${target.endLabel}")
        }
    }

    private fun emitContinueStmt(stmt: ContinueStmt) {
        val target = if (stmt.label != null) {
            loopStack.lastOrNull { it.userLabel == stmt.label }
        } else {
            loopStack.lastOrNull()
        }
        if (target != null) {
            terminate("  br label %${target.condLabel}")
        }
    }

    private fun emitGuardLetStmt(stmt: GuardLetStmt) {
        val srcReg  = emitExpr(stmt.value)
        val srcType = typeOf(stmt.value)
        val innerLt = llvmType((srcType as? Type.TNullable)?.inner ?: srcType)

        val okLabel   = freshLabel("guard.ok")
        val elseLabel = freshLabel("guard.else")

        val isNull = fresh("isnull")
        emit("  $isNull = icmp eq $innerLt $srcReg, ${nullConstant(innerLt)}")
        emit("  br i1 $isNull, label %$elseLabel, label %$okLabel")

        emitRaw("$elseLabel:")
        isTerminated = false
        stmt.elseBody.forEach { emitStmt(it) }
        if (!isTerminated) {
            // guard's else must exit the scope; emit a fallthrough terminator
            if (fnReturnType == "void" || fnReturnType == "i32") terminate("  ret $fnReturnType ${if (fnReturnType == "i32") "0" else ""}")
            else terminate("  ret $fnReturnType ${defaultValue(fnReturnType)}")
        }

        emitRaw("$okLabel:")
        isTerminated = false

        // Bind the unwrapped variable
        val allocaReg = emitAlloca(stmt.name, innerLt)
        varAllocas[stmt.name] = Pair(allocaReg, innerLt)
        emitStore(innerLt, srcReg, allocaReg)
    }

    private fun emitTryCatchStmt(stmt: TryCatchStmt) {
        // Phase 1.5: simplified — just emit the try body; catch/finally ignored
        stmt.tryBody.forEach { emitStmt(it) }
        stmt.finallyBody?.forEach { emitStmt(it) }
    }

    // ── Concurrency statement emit ─────────────────────────────────────────

    private fun emitGoStmt(stmt: GoStmt) {
        // Phase 2.1 bootstrap: emit the body expression/block as a direct call
        // Full pthread wrapping requires generating wrapper functions at codegen time,
        // which is deferred. For now we emit the call directly (fire-and-forget semantics
        // are approximated by a normal call).
        when (val body = stmt.body) {
            is GoExprBody  -> { emitExpr(body.expr); Unit }
            is GoBlockBody -> body.stmts.forEach { emitStmt(it) }
        }
    }

    private fun emitSelectStmt(stmt: SelectStmt) {
        // Phase 2.1 bootstrap: emit each arm's body sequentially as a stub.
        // A real select requires non-blocking channel polls; full implementation is
        // deferred to the runtime-complete phase.
        val mergeLabel = freshLabel("select.merge")
        for (arm in stmt.arms) {
            when (arm) {
                is ReceiveSelectArm -> arm.body.forEach { emitStmt(it) }
                is AfterSelectArm   -> arm.body.forEach { emitStmt(it) }
                is DefaultSelectArm -> arm.body.forEach { emitStmt(it) }
            }
        }
    }

    private fun emitAwaitExpr(expr: AwaitExpr): String {
        // Phase 2.1 bootstrap: async functions run synchronously and return values
        // directly — await is a no-op pass-through.
        return emitExpr(expr.operand)
    }

    private fun emitSpawnExprValue(expr: SpawnExpr): String {
        // Phase 2.1 bootstrap: evaluate the inner expression as a direct call.
        // Full pthread future wrapping is deferred.
        val inner = emitExpr(expr.expr)
        val result = fresh("spawn")
        // Wrap the raw result in a dummy heap allocation to represent a future pointer
        emit("  $result = call i8* @malloc(i64 8)")
        return result
    }

    // ── Inline assembly / raw bytes emit (Phase 2.2) ─────────────────────

    /**
     * Emits an @asm[arch] block as LLVM inline assembly.
     *
     * The asm string is formed by joining all instruction strings with "\0A\09"
     * (newline + tab, the canonical LLVM separator). Clobbers are emitted as
     * "~{reg}" constraints. If the block's arch doesn't match [targetArch] it is
     * silently skipped — the compiler will fall through to the pure-Nordvest body.
     */
    private fun emitAsmStmt(stmt: AsmStmt) {
        if (stmt.arch != targetArch || isTerminated) return

        val asmStr = stmt.instructions.joinToString("\\0A\\09") { escapeAsmStr(it) }
        val clobberConstraints = stmt.clobbers.joinToString(",") { "~{$it}" }
        emit("  call void asm sideeffect \"$asmStr\", \"$clobberConstraints\"()")
    }

    /**
     * Emits a @bytes[arch] block via LLVM inline assembly using `.byte` directives.
     * Skipped if arch doesn't match [targetArch].
     */
    private fun emitBytesStmt(stmt: BytesStmt) {
        if (stmt.arch != targetArch || isTerminated || stmt.bytes.isEmpty()) return

        val byteStr = stmt.bytes.joinToString(", ") { "0x%02x".format(it) }
        emit("  call void asm sideeffect \".byte $byteStr\", \"\"()")
    }

    /** Escape a raw asm string for embedding in an LLVM IR quoted string. */
    private fun escapeAsmStr(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\22").replace("\n", "\\0A").replace("\t", "\\09")

    // ─────────────────────────────────────────────────────────────────────
    // Expression emit — returns the LLVM register holding the value
    // ─────────────────────────────────────────────────────────────────────

    private fun emitCharLit(expr: CharLitExpr): String {
        val text = expr.text
        return when {
            text.length == 1 -> text[0].code.toString()
            text.startsWith("\\") -> when (text) {
                "\\n"  -> "10"
                "\\t"  -> "9"
                "\\r"  -> "13"
                "\\0"  -> "0"
                "\\\\" -> "92"
                "\\'"  -> "39"
                "\\\"" -> "34"
                else   -> "0"
            }
            else -> "0"
        }
    }

    private fun normalizeFloat(text: String): String {
        val stripped = text.trimEnd('f', 'F')
        return if ('.' in stripped || 'e' in stripped.lowercase()) stripped else "$stripped.0"
    }

    private fun emitIdentExpr(expr: IdentExpr): String {
        val name = expr.name
        val entry = varAllocas[name]
        if (entry != null) {
            val (allocaReg, lt) = entry
            if (lt == "void") return "0"
            return emitLoad(lt, allocaReg)
        }
        // Fallback: if we're inside a method and `self` has a known named type,
        // try to load the identifier as a constructor param field from self.
        val selfType = varTypes["self"]
        if (selfType is Type.TNamed) {
            val ownerName = selfType.qualifiedName.substringAfterLast('.')
            val allFields = structLayouts[ownerName]
            val ctorCount = structCtorParamCount[ownerName] ?: 0
            if (allFields != null) {
                val fieldIdx = allFields.indexOfFirst { it.first == name && allFields.indexOf(it) < ctorCount }
                if (fieldIdx in 0 until ctorCount) {
                    val (_, ft) = allFields[fieldIdx]
                    val lt = llvmType(ft)
                    val fieldOffset = if (ownerName in classTypeNames) 2 else 0
                    val gepIdx = fieldIdx + fieldOffset
                    val structType = "%struct.$ownerName"
                    val selfReg = emitLoad("i8*", varAllocas["self"]!!.first)
                    val castR = fresh("idf.cast")
                    emit("  $castR = bitcast i8* $selfReg to $structType*")
                    val fpR = fresh("idf.fp")
                    emit("  $fpR = getelementptr $structType, $structType* $castR, i32 0, i32 $gepIdx")
                    val fvR = fresh("idf.fv")
                    emit("  $fvR = load $lt, $lt* $fpR, align ${llvmTypeAlign(lt)}")
                    return fvR
                }
            }
        }
        // Check if it's a no-param sealed variant or similar value-typed ident
        val symType = tcModule.resolvedModule.moduleScope.lookup(name)?.resolvedType
        if (symType is Type.TNamed) {
            // No-arg constructor call
            val res = fresh("call")
            emit("  $res = call i8* @nv_$name()")
            return res
        }
        return "0"
    }

    private fun emitInterpolatedString(expr: InterpolatedStringExpr): String {
        if (expr.parts.isEmpty()) return stringConst("")

        // Single text-only part — return constant directly
        if (expr.parts.size == 1 && expr.parts[0] is StringTextPart) {
            return stringConst((expr.parts[0] as StringTextPart).text)
        }

        // Build up the string by converting each part to str and concat-ing
        var acc: String? = null
        for (part in expr.parts) {
            val partStr = when (part) {
                is StringTextPart -> {
                    if (part.text.isEmpty()) continue
                    stringConst(part.text)
                }
                is StringInterpolationPart -> {
                    val valReg = emitExpr(part.expr)
                    val ty = typeOf(part.expr)
                    convertToStr(valReg, ty)
                }
                else -> continue
            }
            acc = if (acc == null) {
                partStr
            } else {
                val res = fresh("concat")
                emit("  $res = call i8* @nv_str_concat(i8* $acc, i8* $partStr)")
                res
            }
        }
        return acc ?: stringConst("")
    }

    private fun convertToStr(reg: String, ty: Type): String = when (ty) {
        Type.TStr                   -> reg
        Type.TInt, Type.TInt64      -> {
            val r = fresh("i2s"); emit("  $r = call i8* @nv_int_to_str(i64 $reg)"); r
        }
        Type.TFloat, Type.TFloat64  -> {
            val r = fresh("f2s"); emit("  $r = call i8* @nv_float_to_str(double $reg)"); r
        }
        Type.TFloat32               -> {
            val ext = fresh("fext"); emit("  $ext = fpext float $reg to double")
            val r = fresh("f2s"); emit("  $r = call i8* @nv_float_to_str(double $ext)"); r
        }
        Type.TBool                  -> {
            val r = fresh("b2s"); emit("  $r = call i8* @nv_bool_to_str(i1 $reg)"); r
        }
        else                        -> reg  // best effort
    }

    private fun binaryOpSymbol(op: BinaryOp): String = when (op) {
        BinaryOp.PLUS -> "+"; BinaryOp.MINUS -> "-"; BinaryOp.STAR -> "*"
        BinaryOp.SLASH -> "/"; BinaryOp.EQ -> "=="; BinaryOp.NEQ -> "!="
        BinaryOp.LT -> "<"; BinaryOp.GT -> ">"; BinaryOp.LEQ -> "<="
        BinaryOp.GEQ -> ">="; BinaryOp.MOD -> "%"; BinaryOp.POWER -> "^"
        else -> op.name
    }

    private fun operatorToSuffix(name: String): String? = when (name) {
        "+"  -> "plus"; "-" -> "minus"; "*" -> "mul"; "/" -> "div"
        "==" -> "eq"; "!=" -> "neq"; "<" -> "lt"; ">" -> "gt"
        "<=" -> "le"; ">=" -> "ge"; "%" -> "mod"; "^" -> "pow"
        else -> null
    }

    private fun emitBinaryExpr(expr: BinaryExpr): String {
        // Short-circuit for && and ||
        if (expr.op == BinaryOp.AND) return emitShortCircuit(expr, isAnd = true)
        if (expr.op == BinaryOp.OR)  return emitShortCircuit(expr, isAnd = false)
        // Null coalesce
        if (expr.op == BinaryOp.NULL_COALESCE) return emitNullCoalesce(expr)
        // Pipeline
        if (expr.op == BinaryOp.PIPELINE) return emitPipeline(expr)

        // Operator overloading: check if left type defines the operator
        val leftTypeForOp = typeOf(expr.left)
        if (leftTypeForOp is Type.TNamed) {
            val opSym = binaryOpSymbol(expr.op)
            val opKey = "${leftTypeForOp.qualifiedName}.$opSym"
            val overload = tcModule.memberTypeMap[opKey]
            if (overload is Type.TFun && overload.params.size == 1) {
                val lReg = emitExpr(expr.left)
                val rReg = emitExpr(expr.right)
                val rType = typeOf(expr.right)
                val retLt = llvmType(overload.returnType)
                val typeName = leftTypeForOp.qualifiedName.substringAfterLast('.')
                val suffix = operatorToSuffix(opSym)
                val mangledFn = if (suffix != null) "@nv_${typeName}_op_$suffix"
                                else "@nv_${typeName}_op_custom"
                val res = fresh("opol")
                emit("  $res = call $retLt $mangledFn(i8* noundef $lReg, ${llvmType(rType)} noundef $rReg)")
                return res
            }
        }

        val leftReg  = emitExpr(expr.left)
        val rightReg = emitExpr(expr.right)
        val lt = typeOf(expr.left)
        val rt = typeOf(expr.right)

        // String concatenation
        if (expr.op == BinaryOp.PLUS && (lt == Type.TStr || rt == Type.TStr)) {
            val lStr = convertToStr(leftReg, lt)
            val rStr = convertToStr(rightReg, rt)
            val res = fresh("strcat")
            emit("  $res = call i8* @nv_str_concat(i8* $lStr, i8* $rStr)")
            return res
        }

        // String equality
        if (expr.op == BinaryOp.EQ && lt == Type.TStr && rt == Type.TStr) {
            val res = fresh("streq")
            emit("  $res = call i1 @nv_str_eq(i8* $leftReg, i8* $rightReg)")
            return res
        }
        if (expr.op == BinaryOp.NEQ && lt == Type.TStr && rt == Type.TStr) {
            val eq = fresh("streq")
            emit("  $eq = call i1 @nv_str_eq(i8* $leftReg, i8* $rightReg)")
            val res = fresh("strneq")
            emit("  $res = xor i1 $eq, 1")
            return res
        }

        // Float operations
        val isFloat = lt.isFloatLike || rt.isFloatLike
        val llvmLt  = if (isFloat) "double" else "i64"

        val lCoerced = coerceForArith(leftReg, lt, isFloat)
        val rCoerced = coerceForArith(rightReg, rt, isFloat)

        val res = fresh("binop")
        when (expr.op) {
            BinaryOp.PLUS    -> emit("  $res = ${if (isFloat) "fadd" else "add"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.MINUS   -> emit("  $res = ${if (isFloat) "fsub" else "sub"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.STAR    -> emit("  $res = ${if (isFloat) "fmul" else "mul"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.SLASH   -> emit("  $res = ${if (isFloat) "fdiv" else "sdiv"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.INT_DIV -> emit("  $res = sdiv i64 $lCoerced, $rCoerced")
            BinaryOp.MOD     -> emit("  $res = ${if (isFloat) "frem" else "srem"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.POWER   -> {
                val la = coerceForArith(leftReg, lt, forceFloat = true)
                val ra = coerceForArith(rightReg, rt, forceFloat = true)
                emit("  $res = call double @pow(double noundef $la, double noundef $ra)")
                return res
            }
            BinaryOp.BIT_AND -> emit("  $res = and i64 $lCoerced, $rCoerced")
            BinaryOp.BIT_OR  -> emit("  $res = or i64 $lCoerced, $rCoerced")
            BinaryOp.BIT_XOR -> emit("  $res = xor i64 $lCoerced, $rCoerced")
            BinaryOp.LSHIFT  -> emit("  $res = shl i64 $lCoerced, $rCoerced")
            BinaryOp.RSHIFT  -> emit("  $res = ashr i64 $lCoerced, $rCoerced")
            BinaryOp.EQ      -> emit("  $res = ${if (isFloat) "fcmp oeq" else "icmp eq"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.NEQ     -> emit("  $res = ${if (isFloat) "fcmp one" else "icmp ne"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.LT      -> emit("  $res = ${if (isFloat) "fcmp olt" else "icmp slt"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.GT      -> emit("  $res = ${if (isFloat) "fcmp ogt" else "icmp sgt"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.LEQ     -> emit("  $res = ${if (isFloat) "fcmp ole" else "icmp sle"} $llvmLt $lCoerced, $rCoerced")
            BinaryOp.GEQ     -> emit("  $res = ${if (isFloat) "fcmp oge" else "icmp sge"} $llvmLt $lCoerced, $rCoerced")
            else             -> emit("  $res = add i64 0, 0")   // fallback
        }
        return res
    }

    private fun emitShortCircuit(expr: BinaryExpr, isAnd: Boolean): String {
        val lhsReg  = emitExpr(expr.left)
        val lhsI1   = coerceToI1(lhsReg, typeOf(expr.left))

        val rhsLabel    = freshLabel(if (isAnd) "and.rhs" else "or.rhs")
        val shortLabel  = freshLabel(if (isAnd) "and.short" else "or.short")
        val mergeLabel  = freshLabel(if (isAnd) "and.merge" else "or.merge")

        if (isAnd) {
            emit("  br i1 $lhsI1, label %$rhsLabel, label %$shortLabel")
        } else {
            emit("  br i1 $lhsI1, label %$shortLabel, label %$rhsLabel")
        }
        val lhsBlock = if (isAnd) shortLabel else shortLabel

        emitRaw("$rhsLabel:")
        isTerminated = false
        val rhsReg = emitExpr(expr.right)
        val rhsI1  = coerceToI1(rhsReg, typeOf(expr.right))
        emit("  br label %$mergeLabel")

        emitRaw("$lhsBlock:")
        isTerminated = false
        emit("  br label %$mergeLabel")

        emitRaw("$mergeLabel:")
        isTerminated = false
        val res = fresh("scres")
        val shortVal = if (isAnd) "0" else "1"
        emit("  $res = phi i1 [ $rhsI1, %$rhsLabel ], [ $shortVal, %$lhsBlock ]")
        return res
    }

    private fun emitNullCoalesce(expr: BinaryExpr): String {
        val lhsReg  = emitExpr(expr.left)
        val lhsType = typeOf(expr.left)
        val innerLt = llvmType((lhsType as? Type.TNullable)?.inner ?: lhsType)

        val notNullLabel = freshLabel("nn.ok")
        val nullLabel    = freshLabel("nn.null")
        val mergeLabel   = freshLabel("nn.merge")

        val isNull = fresh("isnull")
        emit("  $isNull = icmp eq $innerLt $lhsReg, ${nullConstant(innerLt)}")
        emit("  br i1 $isNull, label %$nullLabel, label %$notNullLabel")

        emitRaw("$notNullLabel:")
        isTerminated = false
        emit("  br label %$mergeLabel")

        emitRaw("$nullLabel:")
        isTerminated = false
        val rhsReg = emitExpr(expr.right)
        emit("  br label %$mergeLabel")

        emitRaw("$mergeLabel:")
        isTerminated = false
        val res = fresh("coalesce")
        emit("  $res = phi $innerLt [ $lhsReg, %$notNullLabel ], [ $rhsReg, %$nullLabel ]")
        return res
    }

    private fun emitPipeline(expr: BinaryExpr): String {
        // x |> f(_) or x |> f  →  f(x)
        // We store the piped value into a temp alloca and re-load it when needed.
        val lhsReg  = emitExpr(expr.left)
        val lhsType = typeOf(expr.left)
        val lhsLt   = llvmType(lhsType).let { if (it == "void") "i64" else it }
        val pipeSlot = emitAlloca("pipe.${tempIdx}", lhsLt)
        emitStore(lhsLt, lhsReg, pipeSlot)

        return when (val rhs = expr.right) {
            is CallExpr -> {
                // If there is a wildcard arg, replace that position with the piped value;
                // otherwise prepend the piped value as the first argument.
                val hasWildcard = rhs.args.any { it.expr is WildcardExpr }
                val argRegs   = mutableListOf<String>()
                val argTypes  = mutableListOf<Type>()
                if (!hasWildcard) {
                    // prepend piped value
                    argRegs.add(lhsReg); argTypes.add(lhsType)
                }
                for (arg in rhs.args) {
                    if (arg.expr is WildcardExpr) {
                        argRegs.add(lhsReg); argTypes.add(lhsType)
                    } else {
                        argRegs.add(emitExpr(arg.expr)); argTypes.add(typeOf(arg.expr))
                    }
                }
                if (rhs.callee is IdentExpr) {
                    val fnName = rhs.callee.name
                    when (fnName) {
                        "print"   -> { for ((r, t) in argRegs.zip(argTypes)) emitPrintValue(r, t, false); "0" }
                        "println" -> { for ((r, t) in argRegs.zip(argTypes)) emitPrintValue(r, t, true);  "0" }
                        else      -> emitBuiltinOrUserCall(fnName, argRegs, argTypes)
                    }
                } else {
                    emitBuiltinOrUserCall("unknown", argRegs, argTypes)
                }
            }
            is IdentExpr -> emitBuiltinOrUserCall(rhs.name, listOf(lhsReg), listOf(lhsType))
            else -> lhsReg
        }
    }

    private fun emitUnaryExpr(expr: UnaryExpr): String {
        val operandReg = emitExpr(expr.operand)
        val ty = typeOf(expr.operand)
        val res = fresh("unary")
        when (expr.op) {
            UnaryOp.NEGATE -> {
                if (ty.isFloatLike) emit("  $res = fneg double $operandReg")
                else emit("  $res = sub i64 0, $operandReg")
            }
            UnaryOp.NOT -> {
                val i1 = coerceToI1(operandReg, ty)
                emit("  $res = xor i1 $i1, 1")
                return res
            }
            UnaryOp.BIT_NOT -> emit("  $res = xor i64 $operandReg, -1")
        }
        return res
    }

    private fun emitCallExpr(expr: CallExpr): String {
        // Builtin functions
        if (expr.callee is IdentExpr) {
            val name = expr.callee.name
            when (name) {
                "print"   -> return emitPrintCall(expr, newline = false)
                "println" -> return emitPrintCall(expr, newline = true)
                "str"     -> return emitStrConversion(expr)
                "assert"  -> return emitAssert(expr)
                "panic"   -> return emitPanicCall(expr)
                "len"     -> return emitLenCall(expr)
                else      -> { /* fall through to user function */ }
            }
        }

        // Method call: expr.method(args)
        if (expr.callee is MemberAccessExpr) {
            return emitMethodCall(expr, expr.callee)
        }

        // User-defined function call
        if (expr.callee is IdentExpr) {
            val fnName = expr.callee.name
            val argRegs   = expr.args.map { emitExpr(it.expr) }
            val argTypes  = expr.args.map { typeOf(it.expr) }
            return emitBuiltinOrUserCall(fnName, argRegs, argTypes)
        }

        // Lambda call or other — simplified
        return "0"
    }

    private fun emitCallExpr(expr: CallExpr, callee: MemberAccessExpr) = emitMethodCall(expr, callee)

    private fun emitBuiltinOrUserCall(fnName: String, argRegs: List<String>, argTypes: List<Type>): String {
        // Check if it's an @extern function → call C symbol directly
        val ext = externFunctions[fnName]
        if (ext != null) {
            // For pointer-typed inline runtime functions, emit inttoptr/ptrtoint casts
            // to avoid LLVM type-mismatch when the @extern declaration uses int params.
            val ptrSig = inlineRuntimeFnPtrSigs[ext.cSymbol]
            if (ptrSig != null) {
                val (actualRetLt, actualParamLts) = ptrSig
                val castArgList = argRegs.indices.joinToString(", ") { i ->
                    val r = argRegs[i]
                    val paramLt = actualParamLts.getOrElse(i) { llvmType(argTypes.getOrElse(i) { Type.TInt }) }
                    val srcLt = argTypes.getOrNull(i)?.let { llvmType(it) } ?: "i64"
                    if (paramLt == "i8*" && srcLt != "i8*") {
                        val castReg = fresh("itp")
                        emit("  $castReg = inttoptr $srcLt $r to i8*")
                        "i8* $castReg"
                    } else {
                        "$paramLt $r"
                    }
                }
                if (actualRetLt == "void") {
                    emit("  call void @${ext.cSymbol}($castArgList)")
                    return "0"
                }
                val callRes = fresh("extcall")
                emit("  $callRes = call $actualRetLt @${ext.cSymbol}($castArgList)")
                val declRetLt = llvmType(ext.retType)
                if (declRetLt == "i64" && actualRetLt == "i8*") {
                    val ptiReg = fresh("pti")
                    emit("  $ptiReg = ptrtoint i8* $callRes to i64")
                    return ptiReg
                }
                return callRes
            }
            val retLt = llvmType(ext.retType)
            val argList = if (ext.paramTypes.isNotEmpty()) {
                argRegs.zip(ext.paramTypes).joinToString(", ") { (r, t) -> "${llvmType(t)} noundef $r" }
            } else {
                argRegs.zip(argTypes).joinToString(", ") { (r, t) -> "${llvmType(t)} noundef $r" }
            }
            if (retLt == "void") {
                emit("  call void @${ext.cSymbol}($argList)")
                return "0"
            }
            val res = fresh("extcall")
            emit("  $res = call $retLt @${ext.cSymbol}($argList)")
            return res
        }
        // Special stdlib functions
        when (fnName) {
            "eprintln" -> {
                val strReg = if (argRegs.isNotEmpty()) {
                    val r = argRegs[0]; val t = argTypes.getOrElse(0) { Type.TStr }
                    if (t == Type.TStr) r else convertToStr(r, t)
                } else { stringConst("") }
                emit("  call void @nv_eprintln(i8* $strReg)")
                return "0"
            }
            "readLine" -> {
                val res = fresh("rl"); emit("  $res = call i8* @nv_read_line()"); return res
            }
            "readAll" -> {
                val res = fresh("ra"); emit("  $res = call i8* @nv_read_all()"); return res
            }
        }
        // Regular user-defined function
        val mangledName = "@nv_$fnName"
        val fnSymType = tcModule.resolvedModule.moduleScope.lookup(fnName)?.resolvedType
        val fnRetType = (fnSymType as? Type.TFun)?.returnType
        // Constructor calls (struct/class names in structLayouts) always return i8*
        val retLt = when {
            fnName in structLayouts -> "i8*"
            else -> fnRetType?.let { llvmType(it) } ?: "i64"
        }
        val argList = argRegs.zip(argTypes).joinToString(", ") { (r, t) ->
            val lt = llvmType(t); "$lt noundef $r"
        }
        if (retLt == "void") {
            emit("  call void $mangledName($argList)")
            return "0"
        }
        val res = fresh("call")
        emit("  $res = call $retLt $mangledName($argList)")
        return res
    }

    private fun emitMethodCall(callExpr: CallExpr, memberExpr: MemberAccessExpr): String {
        val receiverReg  = emitExpr(memberExpr.receiver)
        val receiverType = typeOf(memberExpr.receiver)
        val member       = memberExpr.member

        // .str() / .toString() — use derived/user-defined toString when available; fall back to convertToStr
        if (member == "str" || member == "toString") {
            val tn = receiverType.simpleTypeName()
            if (tn.isNotEmpty() && methodReturnTypes.containsKey("${tn}_toString")) {
                // Fall through to general method dispatch below (derived Show method)
            } else {
                return convertToStr(receiverReg, receiverType)
            }
        }

        // @config generated loader: static-style call, ignore receiver.
        // Use the receiver IdentExpr name directly because StructSym.resolvedType is TUnknown.
        if (member == "configLoad") {
            val typeName = (memberExpr.receiver as? IdentExpr)?.name
                ?: receiverType.simpleTypeName()
            val res = fresh("cfgld")
            emit("  $res = call i8* @nv_${typeName}_config_load()")
            return res
        }

        // String methods
        if (receiverType == Type.TStr) {
            when (member) {
                "length", "len", "count" -> {
                    val res = fresh("strlen")
                    emit("  $res = call i64 @nv_str_len(i8*$receiverReg)")
                    return res
                }
                else -> { /* fall through */ }
            }
        }

        // General method dispatch: call @nv_TypeName_method(receiver, args...)
        val typeName = receiverType.simpleTypeName()
        val argRegs   = callExpr.args.map { emitExpr(it.expr) }
        val argTypes  = callExpr.args.map { typeOf(it.expr) }
        val mangledFn = "@nv_${typeName}_$member"

        val receiverLt = llvmType(receiverType)
        val argList = buildString {
            append("$receiverLt noundef $receiverReg")
            for ((r, t) in argRegs.zip(argTypes)) {
                append(", ${llvmType(t)} noundef $r")
            }
        }
        // Look up the actual return type from the method registry; fall back to i64
        val methodRetLt = methodReturnTypes["${typeName}_$member"] ?: "i64"
        if (methodRetLt == "void") {
            emit("  call void $mangledFn($argList)")
            return "0"
        }
        val res = fresh("mcall")
        emit("  $res = call $methodRetLt $mangledFn($argList)")
        return res
    }

    private fun emitPrintCall(expr: CallExpr, newline: Boolean): String {
        val allArgs = buildList {
            addAll(expr.args.map { it.expr })
            if (expr.trailingLambda != null) { /* skip */ }
        }
        for (argExpr in allArgs) {
            val argReg = emitExpr(argExpr)
            val ty = typeOf(argExpr)
            emitPrintValue(argReg, ty, newline)
        }
        return "0"  // void → dummy
    }

    private fun emitPrintValue(reg: String, ty: Type, newline: Boolean) {
        val suffix = if (newline) "ln" else ""
        when (ty) {
            Type.TStr             -> emit("  call void @nv_print${suffix}(i8* $reg)")
            Type.TInt, Type.TInt64 -> emit("  call void @nv_print${suffix}_int(i64 $reg)")
            Type.TFloat, Type.TFloat64 -> emit("  call void @nv_print${suffix}_float(double $reg)")
            Type.TFloat32         -> {
                val ext = fresh("fext"); emit("  $ext = fpext float $reg to double")
                emit("  call void @nv_print${suffix}_float(double $ext)")
            }
            Type.TBool            -> emit("  call void @nv_print${suffix}_bool(i1 $reg)")
            is Type.TNullable     -> {
                // Check null, print "nil" or the inner value
                val innerLt = llvmType(ty.inner)
                val notNullLabel = freshLabel("prt.notnull")
                val nullLabel    = freshLabel("prt.null")
                val doneLabel    = freshLabel("prt.done")
                val isNull = fresh("isnull")
                emit("  $isNull = icmp eq $innerLt $reg, ${nullConstant(innerLt)}")
                emit("  br i1 $isNull, label %$nullLabel, label %$notNullLabel")
                emitRaw("$notNullLabel:")
                isTerminated = false
                emitPrintValue(reg, ty.inner, newline)
                emit("  br label %$doneLabel")
                emitRaw("$nullLabel:")
                isTerminated = false
                val nilGep = stringConst("nil")
                emit("  call void @nv_print${suffix}(i8* $nilGep)")
                emit("  br label %$doneLabel")
                emitRaw("$doneLabel:")
                isTerminated = false
            }
            else -> {
                // Convert to str and print
                val strReg = convertToStr(reg, ty)
                emit("  call void @nv_print${suffix}(i8* $strReg)")
            }
        }
    }

    private fun emitStrConversion(expr: CallExpr): String {
        val argExpr = expr.args.firstOrNull()?.expr ?: return stringConst("")
        val argReg  = emitExpr(argExpr)
        return convertToStr(argReg, typeOf(argExpr))
    }

    private fun emitAssert(expr: CallExpr): String {
        val condExpr = expr.args.firstOrNull()?.expr ?: return "0"
        val condReg  = emitExpr(condExpr)
        val condI1   = coerceToI1(condReg, typeOf(condExpr))

        val okLabel   = freshLabel("assert.ok")
        val failLabel = freshLabel("assert.fail")
        emit("  br i1 $condI1, label %$okLabel, label %$failLabel")

        emitRaw("$failLabel:")
        isTerminated = false
        val msgConst = stringConst("assertion failed")
        emit("  call void @nv_panic(i8*$msgConst)")
        terminate("  unreachable")

        emitRaw("$okLabel:")
        isTerminated = false
        return "0"
    }

    private fun emitPanicCall(expr: CallExpr): String {
        val msgExpr = expr.args.firstOrNull()?.expr
        val msgReg = if (msgExpr != null) {
            val r = emitExpr(msgExpr)
            convertToStr(r, typeOf(msgExpr))
        } else {
            stringConst("panic")
        }
        emit("  call void @nv_panic(i8*$msgReg)")
        terminate("  unreachable")
        return "0"
    }

    private fun emitLenCall(expr: CallExpr): String {
        val argExpr = expr.args.firstOrNull()?.expr ?: return "0"
        val argReg  = emitExpr(argExpr)
        val argType = typeOf(argExpr)
        if (argType == Type.TStr) {
            val res = fresh("len")
            emit("  $res = call i64 @nv_str_len(i8*$argReg)")
            return res
        }
        // For arrays: length is stored in first 8 bytes of the array header
        if (argType is Type.TArray) {
            val lenPtr = fresh("lenptr")
            emit("  $lenPtr = bitcast i8* $argReg to i64*")
            val lenReg = fresh("arrlen")
            emit("  $lenReg = load i64, i64* $lenPtr, align 8")
            return lenReg
        }
        return "0"
    }

    private fun emitMemberAccessExpr(expr: MemberAccessExpr): String {
        val receiverReg  = emitExpr(expr.receiver)
        val receiverType = typeOf(expr.receiver)

        return when {
            expr.member == "length" && receiverType == Type.TStr -> {
                val res = fresh("len")
                emit("  $res = call i64 @nv_str_len(i8*$receiverReg)")
                res
            }
            expr.member == "str" -> convertToStr(receiverReg, receiverType)
            else -> {
                // Struct/record/class field access via GEP (or lazy getter call)
                val typeName = if (receiverType is Type.TNamed) receiverType.qualifiedName.substringAfterLast('.') else ""
                val fields = if (typeName.isNotEmpty()) structLayouts[typeName] else null
                if (fields != null && receiverType is Type.TNamed) {
                    // Check if this is a @lazy field — call the getter instead of direct GEP
                    val lazyForType = lazyFields[typeName]
                    if (lazyForType != null && lazyForType.any { it.first == expr.member }) {
                        val getterRetLt = methodReturnTypes["${typeName}_get_${expr.member}"] ?: llvmType(typeOf(expr))
                        val res = fresh("lzget")
                        emit("  $res = call $getterRetLt @nv_${typeName}_get_${expr.member}(i8* $receiverReg)")
                        return res
                    }
                    val fieldIdx = fields.indexOfFirst { it.first == expr.member }
                    if (fieldIdx >= 0) {
                        val fieldType = fields[fieldIdx].second
                        val fieldLt = llvmType(fieldType)
                        // Class types have a 2-field RC header prepended; offset GEP index accordingly
                        val gepIdx = fieldIdx + (if (typeName in classTypeNames) 2 else 0)
                        val castReg = fresh("sfcast")
                        emit("  $castReg = bitcast i8* $receiverReg to %struct.$typeName*")
                        val ptrReg = fresh("sfptr")
                        emit("  $ptrReg = getelementptr %struct.$typeName, %struct.$typeName* $castReg, i32 0, i32 $gepIdx")
                        val loadReg = fresh("sfld")
                        emit("  $loadReg = load $fieldLt, $fieldLt* $ptrReg, align ${llvmTypeAlign(fieldLt)}")
                        return loadReg
                    }
                }
                "0"
            }
        }
    }

    private fun emitSafeNavExpr(expr: SafeNavExpr): String {
        val receiverReg  = emitExpr(expr.receiver)
        val receiverType = typeOf(expr.receiver)
        val innerLt = llvmType((receiverType as? Type.TNullable)?.inner ?: receiverType)

        val okLabel    = freshLabel("safenav.ok")
        val nullLabel  = freshLabel("safenav.null")
        val mergeLabel = freshLabel("safenav.merge")

        val isNull = fresh("isnull")
        emit("  $isNull = icmp eq $innerLt $receiverReg, ${nullConstant(innerLt)}")
        emit("  br i1 $isNull, label %$nullLabel, label %$okLabel")

        emitRaw("$okLabel:")
        isTerminated = false
        val memberRes = when (expr.member) {
            "length" -> {
                val r = fresh("len"); emit("  $r = call i64 @nv_str_len(i8*$receiverReg)"); r
            }
            else -> "0"
        }
        emit("  br label %$mergeLabel")

        emitRaw("$nullLabel:")
        isTerminated = false
        emit("  br label %$mergeLabel")

        emitRaw("$mergeLabel:")
        isTerminated = false
        val res = fresh("safenav")
        emit("  $res = phi $innerLt [ $memberRes, %$okLabel ], [ ${nullConstant(innerLt)}, %$nullLabel ]")
        return res
    }

    private fun emitInlineIfExpr(expr: InlineIfExpr): String {
        val condReg = emitExpr(expr.condition)
        val condI1  = coerceToI1(condReg, typeOf(expr.condition))
        val ty      = typeOf(expr)
        val lt      = if (llvmType(ty) == "void") "i64" else llvmType(ty)

        val thenLabel  = freshLabel("iif.then")
        val elseLabel  = freshLabel("iif.else")
        val mergeLabel = freshLabel("iif.merge")

        emit("  br i1 $condI1, label %$thenLabel, label %$elseLabel")

        emitRaw("$thenLabel:")
        isTerminated = false
        val thenReg = emitExpr(expr.thenExpr)
        val thenCoerced = coerceToType(thenReg, typeOf(expr.thenExpr), lt)
        emit("  br label %$mergeLabel")

        emitRaw("$elseLabel:")
        isTerminated = false
        val elseReg = emitExpr(expr.elseExpr)
        val elseCoerced = coerceToType(elseReg, typeOf(expr.elseExpr), lt)
        emit("  br label %$mergeLabel")

        emitRaw("$mergeLabel:")
        isTerminated = false
        val res = fresh("iif")
        emit("  $res = phi $lt [ $thenCoerced, %$thenLabel ], [ $elseCoerced, %$elseLabel ]")
        return res
    }

    private fun emitForceUnwrapExpr(expr: ForceUnwrapExpr): String {
        val reg  = emitExpr(expr.operand)
        val ty   = typeOf(expr.operand)
        val innerLt = llvmType((ty as? Type.TNullable)?.inner ?: ty)

        val okLabel    = freshLabel("unwrap.ok")
        val failLabel  = freshLabel("unwrap.fail")

        val isNull = fresh("isnull")
        emit("  $isNull = icmp eq $innerLt $reg, ${nullConstant(innerLt)}")
        emit("  br i1 $isNull, label %$failLabel, label %$okLabel")

        emitRaw("$failLabel:")
        isTerminated = false
        val msgConst = stringConst("force unwrap of nil value")
        emit("  call void @nv_panic(i8*$msgConst)")
        terminate("  unreachable")

        emitRaw("$okLabel:")
        isTerminated = false
        return reg
    }

    private fun emitTypeTestExpr(expr: TypeTestExpr): String {
        // Phase 1.5: simplified — always return true
        return "1"
    }

    private fun emitArrayLiteral(expr: ArrayLiteralExpr): String {
        val count = expr.elements.size.toLong()
        if (expr.elements.isEmpty()) {
            // Alloc just the header (count=0)
            val rawReg = fresh("arr.empty")
            emit("  $rawReg = call i8* @malloc(i64 8)")
            val hdrPtr = fresh("hdrptr")
            emit("  $hdrPtr = bitcast i8* $rawReg to i64*")
            emit("  store i64 0, i64* $hdrPtr, align 8")
            return rawReg
        }
        val elemType = typeOf(expr.elements[0])
        val elemLt   = llvmType(elemType)
        val elemSize = llvmTypeSize(elemLt)
        val dataSize = elemSize * expr.elements.size
        val totalSize = 8 + dataSize  // 8-byte header for count
        val rawReg = fresh("arr.raw")
        emit("  $rawReg = call i8* @malloc(i64 $totalSize)")
        // Store count in header
        val hdrPtr = fresh("hdrptr")
        emit("  $hdrPtr = bitcast i8* $rawReg to i64*")
        emit("  store i64 $count, i64* $hdrPtr, align 8")
        // Elements start at offset 8
        val dataStart = fresh("arr.data")
        emit("  $dataStart = getelementptr i8, i8* $rawReg, i64 8")
        val arrReg = fresh("arr")
        emit("  $arrReg = bitcast i8* $dataStart to ${llvmPtrType(elemLt)}")
        for ((i, elemExpr) in expr.elements.withIndex()) {
            val valReg   = emitExpr(elemExpr)
            val coerced  = coerceToType(valReg, typeOf(elemExpr), elemLt)
            val idxReg   = fresh("arrptr")
            emit("  $idxReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $arrReg, i64 $i")
            emit("  store $elemLt $coerced, ${llvmPtrType(elemLt)} $idxReg, align $elemSize")
        }
        // Return the header pointer (i8*)
        return rawReg
    }

    private fun emitTupleLiteral(expr: TupleLiteralExpr): String {
        if (expr.elements.isEmpty()) return "null"
        // Allocate storage for all elements (simplified: store as i64 array)
        val rawReg = fresh("tuple.raw")
        emit("  $rawReg = call i8* @malloc(i64 ${expr.elements.size * 8})")
        val arrReg = fresh("tuple")
        emit("  $arrReg = bitcast i8* $rawReg to i64*")
        for ((i, elemExpr) in expr.elements.withIndex()) {
            val valReg  = emitExpr(elemExpr)
            val ty      = typeOf(elemExpr)
            val asI64   = coerceToI64(valReg, ty)
            val ptrReg  = fresh("tptr")
            emit("  $ptrReg = getelementptr i64, i64* $arrReg, i64 $i")
            emit("  store i64 $asI64, i64* $ptrReg, align 8")
        }
        val retReg = fresh("tupret")
        emit("  $retReg = bitcast i64* $arrReg to i8*")
        return retReg
    }

    private fun emitMatchExprValue(expr: MatchExpr): String {
        emitMatchExpr(expr)
        return "0"
    }

    private fun emitMatchExpr(expr: MatchExpr) {
        val subjectReg  = emitExpr(expr.subject)
        val subjectType = typeOf(expr.subject)
        val mergeLabel  = freshLabel("match.merge")

        for ((idx, arm) in expr.arms.withIndex()) {
            val bodyLabel = freshLabel("match.arm")
            val nextLabel = if (idx < expr.arms.size - 1) freshLabel("match.next") else mergeLabel

            val matched = emitPatternCheck(subjectReg, subjectType, arm.pattern)
            val condI1  = coerceToI1(matched, Type.TBool)

            // Guard
            val finalCond = if (arm.guard != null) {
                val guardReg = emitExpr(arm.guard)
                val guardI1  = coerceToI1(guardReg, typeOf(arm.guard))
                val both     = fresh("guard")
                emit("  $both = and i1 $condI1, $guardI1")
                both
            } else condI1

            emit("  br i1 $finalCond, label %$bodyLabel, label %$nextLabel")
            emitRaw("$bodyLabel:")
            isTerminated = false

            when (val body = arm.body) {
                is ExprMatchArmBody  -> {
                    val valReg  = emitExpr(body.expr)
                    val valType = typeOf(body.expr)
                    val valLt   = llvmType(valType)
                    // Only emit ret if the expression produces a real value (not unit/void).
                    // Unit-typed arms (e.g. println inside a loop match) just fall through.
                    if (valType != Type.TUnit && valLt != "void" && fnReturnType != "void") {
                        val coerced = coerceToType(valReg, valType, fnReturnType)
                        terminate("  ret $fnReturnType $coerced")
                    }
                }
                is BlockMatchArmBody -> body.stmts.forEach { emitStmt(it) }
            }
            if (!isTerminated) emit("  br label %$mergeLabel")

            if (idx < expr.arms.size - 1) {
                emitRaw("$nextLabel:")
                isTerminated = false
            }
        }

        emitRaw("$mergeLabel:")
        isTerminated = false
    }

    private fun emitPatternCheck(subjectReg: String, subjectType: Type, pattern: Pattern): String {
        return when (pattern) {
            is WildcardPattern  -> "1"
            is BindingPattern   -> {
                // Bind and always match
                val lt = llvmType(subjectType)
                if (lt != "void") {
                    val allocaReg = emitAlloca(pattern.name, lt)
                    varAllocas[pattern.name] = Pair(allocaReg, lt)
                    emitStore(lt, subjectReg, allocaReg)
                }
                "1"
            }
            is NilPattern       -> {
                val lt  = llvmType(subjectType)
                val res = fresh("nilchk")
                emit("  $res = icmp eq $lt $subjectReg, ${nullConstant(lt)}")
                res
            }
            is LiteralPattern   -> {
                val patReg  = emitExpr(pattern.expr)
                val patType = typeOf(pattern.expr)
                val lt      = llvmType(patType)
                val res     = fresh("litchk")
                if (patType == Type.TStr || subjectType == Type.TStr) {
                    emit("  $res = call i1 @nv_str_eq(i8* $subjectReg, i8* $patReg)")
                } else {
                    val lhs = coerceToType(subjectReg, subjectType, lt)
                    emit("  $res = icmp eq $lt $lhs, $patReg")
                }
                res
            }
            is RangePattern     -> {
                val range = pattern.range
                val startReg = emitExpr(range.start)
                val endReg   = emitExpr(range.end)
                val lt       = "i64"
                val subI64   = coerceToI64(subjectReg, subjectType)
                val startI64 = coerceToI64(startReg, typeOf(range.start))
                val endI64   = coerceToI64(endReg, typeOf(range.end))
                val lo = fresh("lo"); val hi = fresh("hi"); val res = fresh("rngchk")
                emit("  $lo = icmp sge $lt $subI64, $startI64")
                when (range.kind) {
                    RangeKind.CLOSED, RangeKind.HALF_OPEN_LEFT ->
                        emit("  $hi = icmp sle $lt $subI64, $endI64")
                    else ->
                        emit("  $hi = icmp slt $lt $subI64, $endI64")
                }
                emit("  $res = and i1 $lo, $hi")
                res
            }
            is OrPattern        -> {
                val checks = pattern.alternatives.map { emitPatternCheck(subjectReg, subjectType, it) }
                checks.reduce { acc, c ->
                    val res = fresh("or")
                    emit("  $res = or i1 $acc, $c")
                    res
                }
            }
            is TypePattern -> emitTypePatternCheck(subjectReg, subjectType, pattern)
            else -> "1"
        }
    }

    /**
     * Emit a check for a TypePattern (sealed class variant or Result Ok/Err).
     * Sealed class variants are represented as heap-allocated {i64 tag, i64 payload} (16 bytes).
     * Returns an i1 register: 1 if the tag matches, 0 otherwise.
     * As a side effect, binds any BindingPattern variables via varAllocas.
     */
    private fun emitTypePatternCheck(subjectReg: String, subjectType: Type, pattern: TypePattern): String {
        val variantName = pattern.typeName.text

        // Determine expected tag
        val tag: Int
        val payloadParamType: Type?
        when (variantName) {
            "Ok"  -> { tag = 0; payloadParamType = null }
            "Err" -> { tag = 1; payloadParamType = Type.TStr }
            else  -> {
                val sealedDecl = sealedClassDecls.values.firstOrNull { sd ->
                    sd.variants.any { it.name == variantName }
                } ?: return "1"  // Unknown variant — always match (fallback)
                val variantIdx = sealedDecl.variants.indexOfFirst { it.name == variantName }
                tag = variantIdx
                payloadParamType = sealedDecl.variants[variantIdx].params.firstOrNull()
                    ?.let { resolveTypeNode(it.type) }
            }
        }

        // Cast subject i8* → i64* and load tag
        val castReg = fresh("vcast")
        emit("  $castReg = bitcast i8* $subjectReg to i64*")
        val tagReg = fresh("vtag")
        emit("  $tagReg = load i64, i64* $castReg, align 8")
        val matchedReg = fresh("vmatch")
        emit("  $matchedReg = icmp eq i64 $tagReg, $tag")

        // Bind positional pattern variables
        val posArgs = pattern.args as? PositionalTypePatternArgs
        if (posArgs != null && posArgs.patterns.isNotEmpty()) {
            // Load the payload i64
            val vpReg = fresh("vpay")
            emit("  $vpReg = getelementptr i64, i64* $castReg, i64 1")
            val payI64Reg = fresh("pay_i64")
            emit("  $payI64Reg = load i64, i64* $vpReg, align 8")

            for (subPat in posArgs.patterns) {
                if (subPat is BindingPattern) {
                    val paramLt = payloadParamType?.let { llvmType(it) } ?: "i8*"
                    val extractedReg = when (paramLt) {
                        "i8*" -> {
                            val r = fresh("extract"); emit("  $r = inttoptr i64 $payI64Reg to i8*"); r
                        }
                        "i1" -> {
                            val r = fresh("extract"); emit("  $r = trunc i64 $payI64Reg to i1"); r
                        }
                        "double" -> {
                            val r = fresh("extract"); emit("  $r = bitcast i64 $payI64Reg to double"); r
                        }
                        else -> payI64Reg   // i64 and other integer types
                    }
                    val allocaReg = emitAlloca(subPat.name, paramLt)
                    varAllocas[subPat.name] = Pair(allocaReg, paramLt)
                    emitStore(paramLt, extractedReg, allocaReg)
                    // Record Nordvest type so field access (cfg.host) can resolve the struct layout
                    val innerNvType: Type? = when {
                        subjectType is Type.TResult && variantName == "Ok" -> subjectType.okType
                        payloadParamType != null -> payloadParamType
                        else -> null
                    }
                    if (innerNvType != null) varTypes[subPat.name] = innerNvType
                }
            }
        }

        return matchedReg
    }

    private fun emitIndexExpr(expr: IndexExpr): String {
        val receiverReg  = emitExpr(expr.receiver)
        val receiverType = typeOf(expr.receiver)

        val idxArg = expr.indices.firstOrNull() ?: return "0"
        val idxReg = if (idxArg.isStar) "0" else (idxArg.expr?.let { emitExpr(it) } ?: "0")

        val elemLt = when (receiverType) {
            is Type.TArray -> llvmType(receiverType.element)
            is Type.TStr   -> "i8"
            else           -> "i64"
        }
        val idxI64 = if (idxArg.expr != null) coerceToI64(idxReg, typeOf(idxArg.expr!!)) else "0"
        val castReg: String
        if (receiverType is Type.TArray) {
            // Skip the 8-byte count header
            val dataPtr = fresh("arr.data")
            emit("  $dataPtr = getelementptr i8, i8* $receiverReg, i64 8")
            castReg = fresh("idxcast")
            emit("  $castReg = bitcast i8* $dataPtr to ${llvmPtrType(elemLt)}")
        } else {
            castReg = fresh("idxcast")
            emit("  $castReg = bitcast i8* $receiverReg to ${llvmPtrType(elemLt)}")
        }
        val ptrReg = fresh("idxptr")
        emit("  $ptrReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $castReg, i64 $idxI64")
        val res = fresh("idx")
        emit("  $res = load $elemLt, ${llvmPtrType(elemLt)} $ptrReg, align 1")
        return res
    }

    // ─────────────────────────────────────────────────────────────────────
    // Coercion helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun coerceToI1(reg: String, ty: Type): String {
        return when {
            ty == Type.TBool || llvmType(ty) == "i1" -> reg
            ty.isIntLike || llvmType(ty) == "i64" -> {
                val res = fresh("tobool")
                emit("  $res = icmp ne i64 $reg, 0")
                res
            }
            ty == Type.TStr -> {
                val lenReg = fresh("slen")
                emit("  $lenReg = call i64 @nv_str_len(i8*$reg)")
                val res = fresh("tobool")
                emit("  $res = icmp ne i64 $lenReg, 0")
                res
            }
            else -> reg
        }
    }

    private fun coerceToI64(reg: String, ty: Type): String {
        return when {
            ty.isIntLike || llvmType(ty) == "i64" -> reg
            ty == Type.TBool || llvmType(ty) == "i1" -> {
                val res = fresh("zext"); emit("  $res = zext i1 $reg to i64"); res
            }
            ty.isFloatLike -> {
                val res = fresh("fptosi"); emit("  $res = fptosi double $reg to i64"); res
            }
            else -> reg
        }
    }

    private fun coerceForArith(reg: String, ty: Type, forceFloat: Boolean = false): String {
        return when {
            forceFloat && ty.isIntLike -> {
                val res = fresh("sitofp"); emit("  $res = sitofp i64 $reg to double"); res
            }
            forceFloat && ty == Type.TFloat32 -> {
                val res = fresh("fpext"); emit("  $res = fpext float $reg to double"); res
            }
            !forceFloat && ty == Type.TFloat32 -> {
                val res = fresh("fpext"); emit("  $res = fpext float $reg to double"); res
            }
            !forceFloat && ty.isIntLike && llvmType(ty) != "i64" -> {
                val res = fresh("sext"); emit("  $res = sext i32 $reg to i64"); res
            }
            else -> reg
        }
    }

    private fun coerceToType(reg: String, fromTy: Type, toLt: String): String {
        val fromLt = llvmType(fromTy)
        if (fromLt == toLt) return reg
        return when {
            toLt == "i1" && fromLt == "i64" -> {
                val res = fresh("trunc"); emit("  $res = trunc i64 $reg to i1"); res
            }
            toLt == "i64" && fromLt == "i1" -> {
                val res = fresh("zext"); emit("  $res = zext i1 $reg to i64"); res
            }
            toLt == "double" && fromLt == "i64" -> {
                val res = fresh("sitofp"); emit("  $res = sitofp i64 $reg to double"); res
            }
            toLt == "i64" && fromLt == "double" -> {
                val res = fresh("fptosi"); emit("  $res = fptosi double $reg to i64"); res
            }
            toLt == "double" && fromLt == "float" -> {
                val res = fresh("fpext"); emit("  $res = fpext float $reg to double"); res
            }
            toLt == "float" && fromLt == "double" -> {
                val res = fresh("fptrunc"); emit("  $res = fptrunc double $reg to float"); res
            }
            toLt == "i32" && fromLt == "i64" -> {
                val res = fresh("trunc"); emit("  $res = trunc i64 $reg to i32"); res
            }
            toLt == "i64" && fromLt == "i32" -> {
                val res = fresh("sext"); emit("  $res = sext i32 $reg to i64"); res
            }
            else -> reg  // best-effort
        }
    }

    private fun nullConstant(lt: String): String = when (lt) {
        "i8*", "i64*", "double*", "float*", "i1*", "i32*" -> "null"
        "i1"     -> "0"
        "i64"    -> "0"
        "i32"    -> "0"
        "i8"     -> "0"
        "double" -> "0.0"
        "float"  -> "0.0"
        else     -> "null"
    }

    private fun defaultValue(lt: String): String = when (lt) {
        "i1"     -> "0"
        "i8"     -> "0"
        "i32"    -> "0"
        "i64"    -> "0"
        "double" -> "0.0"
        "float"  -> "0.0"
        "i8*", "i64*", "double*", "float*", "i1*", "i32*" -> "null"
        "void"   -> ""
        else     -> "0"
    }

    private fun llvmTypeSize(lt: String): Int = when (lt) {
        "i1"  -> 1
        "i8"  -> 1
        "i32" -> 4
        "i64" -> 8
        "double" -> 8
        "float"  -> 4
        else     -> 8  // pointers (i8*, i64*, etc.) and unknowns
    }

    // ─────────────────────────────────────────────────────────────────────
    // TypeNode resolution (simplified — maps syntax to semantic Type)
    // ─────────────────────────────────────────────────────────────────────

    private fun resolveTypeNode(node: TypeNode): Type = when (node) {
        is NamedTypeNode -> when (node.name.text) {
            "int"     -> Type.TInt
            "int64"   -> Type.TInt64
            "float"   -> Type.TFloat
            "float32" -> Type.TFloat32
            "float64" -> Type.TFloat64
            "bool"    -> Type.TBool
            "str"     -> Type.TStr
            "byte"    -> Type.TByte
            "char"    -> Type.TChar
            "unit", "()" -> Type.TUnit
            "never"   -> Type.TNever
            else      -> Type.TNamed(node.name.text, node.typeArgs.map { resolveTypeNode(it) })
        }
        is NullableTypeNode  -> Type.TNullable(resolveTypeNode(node.inner))
        is ArrayTypeNode     -> Type.TArray(resolveTypeNode(node.element))
        is MatrixTypeNode    -> Type.TMatrix(resolveTypeNode(node.element))
        is MapTypeNode       -> Type.TMap(resolveTypeNode(node.key), resolveTypeNode(node.value))
        is TupleTypeNode     -> Type.TTuple(node.fields.map { nv.compiler.typecheck.TupleField(it.name, resolveTypeNode(it.type)) })
        is FnTypeNode        -> Type.TFun(node.paramTypes.map { resolveTypeNode(it) }, resolveTypeNode(node.returnType))
        else                 -> Type.TUnknown
    }

    // ─────────────────────────────────────────────────────────────────────
    // Extension helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun Type.simpleTypeName(): String = when (this) {
        Type.TInt, Type.TInt64  -> "int"
        Type.TFloat, Type.TFloat64 -> "float"
        Type.TFloat32           -> "float32"
        Type.TBool              -> "bool"
        Type.TStr               -> "str"
        is Type.TNamed          -> qualifiedName.substringAfterLast('.')
        is Type.TNullable       -> inner.simpleTypeName()
        else                    -> "unknown"
    }

    private fun emitExpr(expr: Expr): String = when (expr) {
        is IntLitExpr            -> expr.text.trimEnd('L', 'l').toLongOrNull()?.let { it.toString() } ?: "0"
        is FloatLitExpr          -> normalizeFloat(expr.text)
        is BoolLitExpr           -> if (expr.value) "1" else "0"
        is NilExpr               -> "null"
        is ConstPiExpr           -> "3.141592653589793"
        is ConstEExpr            -> "2.718281828459045"
        is ConstInfExpr          -> "0x7FF0000000000000"
        is CharLitExpr           -> emitCharLit(expr)
        is RawStringExpr         -> stringConst(expr.text)
        is InterpolatedStringExpr -> emitInterpolatedString(expr)
        is IdentExpr             -> emitIdentExpr(expr)
        is ParenExpr             -> emitExpr(expr.inner)
        is BinaryExpr            -> emitBinaryExpr(expr)
        is UnaryExpr             -> emitUnaryExpr(expr)
        is CallExpr              -> emitCallExpr(expr)
        is MemberAccessExpr      -> emitMemberAccessExpr(expr)
        is SafeNavExpr           -> emitSafeNavExpr(expr)
        is InlineIfExpr          -> emitInlineIfExpr(expr)
        is ForceUnwrapExpr       -> emitForceUnwrapExpr(expr)
        is ResultPropagateExpr   -> emitExpr(expr.operand)
        is TypeTestExpr          -> emitTypeTestExpr(expr)
        is SafeCastExpr          -> emitExpr(expr.operand)
        is ForceCastExpr         -> emitExpr(expr.operand)
        is ArrayLiteralExpr      -> emitArrayLiteral(expr)
        is MapLiteralExpr        -> "null"
        is EmptyMapExpr          -> "null"
        is TupleLiteralExpr      -> emitTupleLiteral(expr)
        is RangeExpr             -> "null"
        is MatchExpr             -> emitMatchExprValue(expr)
        is LambdaExpr            -> "null"
        is WildcardExpr          -> "0"
        is IndexExpr             -> emitIndexExpr(expr)
        is QuantifierExpr        -> emitQuantifierExpr(expr)
        is ListComprehensionExpr -> "null"
        is AwaitExpr             -> emitAwaitExpr(expr)
        is SpawnExpr             -> emitSpawnExprValue(expr)
        is BuilderCallExpr       -> emitBuilderCallExpr(expr)
        else                     -> "0"
    }

    private fun emitQuantifierExpr(expr: QuantifierExpr): String {
        val iterableExpr = expr.iterable ?: return when (expr.op) {
            QuantifierOp.FORALL, QuantifierOp.EXISTS -> "0"
            QuantifierOp.SUM, QuantifierOp.PRODUCT   -> "0"
        }
        val bodyExpr = when (val b = expr.body) {
            is InlineQuantifierBody -> b.expr
            else -> return "0"  // block/bare bodies not yet lowered
        }
        val bindName = when (val bind = expr.binding) {
            is IdentBinding -> bind.name
            is TupleBinding -> bind.names.firstOrNull() ?: "_"
            null -> "_"
        }

        // Determine element type from the iterable
        val iterableType = typeOf(iterableExpr)
        val elemType = when (iterableType) {
            is Type.TArray -> iterableType.element
            Type.TStr      -> Type.TChar
            else           -> Type.TUnknown
        }
        val elemLt   = llvmType(elemType)
        val elemSize = llvmTypeSize(elemLt).coerceAtLeast(1).toLong()

        // Emit the iterable and load array count
        val arrReg   = emitExpr(iterableExpr)
        val hdrPtr   = fresh("q.hdr")
        emit("  $hdrPtr = bitcast i8* $arrReg to i64*")
        val countReg = fresh("q.count")
        emit("  $countReg = load i64, i64* $hdrPtr, align 8")

        // Labels
        val condLabel = freshLabel("q.cond")
        val bodyLabel = freshLabel("q.body")
        val endLabel  = freshLabel("q.end")

        // Accumulator / result alloca
        val isBoolean = expr.op == QuantifierOp.FORALL || expr.op == QuantifierOp.EXISTS
        val accLt     = if (isBoolean) "i1" else elemLt
        val accReg    = emitAlloca("q.acc.${labelIdx}", accLt)
        val initVal   = when (expr.op) {
            QuantifierOp.FORALL  -> "1"   // true until proven false
            QuantifierOp.EXISTS  -> "0"   // false until proven true
            QuantifierOp.SUM     -> if (accLt == "double") "0.0" else "0"
            QuantifierOp.PRODUCT -> if (accLt == "double") "1.0" else "1"
        }
        emitStore(accLt, initVal, accReg)

        // Index alloca
        val idxReg = emitAlloca("q.idx.${labelIdx}", "i64")
        emitStore("i64", "0", idxReg)
        emit("  br label %$condLabel")

        emitRaw("$condLabel:")
        isTerminated = false
        val curIdx = fresh("q.i")
        emit("  $curIdx = load i64, i64* $idxReg, align 8")
        val loopCond = fresh("q.cond")
        emit("  $loopCond = icmp slt i64 $curIdx, $countReg")

        // For FORALL/EXISTS we can short-circuit: check accumulator too
        if (expr.op == QuantifierOp.FORALL || expr.op == QuantifierOp.EXISTS) {
            val continueLabel = freshLabel("q.cont")
            emit("  br i1 $loopCond, label %$continueLabel, label %$endLabel")
            emitRaw("$continueLabel:")
            isTerminated = false
            // Also check accumulator to allow short-circuit
            val accVal    = emitLoad(accLt, accReg, "q.acc.cur")
            val keepGoing = fresh("q.keep")
            if (expr.op == QuantifierOp.FORALL) {
                // Keep going while acc is still true
                emit("  $keepGoing = icmp eq i1 $accVal, 1")
            } else {
                // Keep going while acc is still false
                emit("  $keepGoing = icmp eq i1 $accVal, 0")
            }
            emit("  br i1 $keepGoing, label %$bodyLabel, label %$endLabel")
        } else {
            emit("  br i1 $loopCond, label %$bodyLabel, label %$endLabel")
        }

        emitRaw("$bodyLabel:")
        isTerminated = false

        // Load element
        val dataStart = fresh("q.data")
        emit("  $dataStart = getelementptr i8, i8* $arrReg, i64 8")
        val elemValReg = if (elemLt == "i8") {
            val ptrReg = fresh("q.eptr")
            emit("  $ptrReg = getelementptr i8, i8* $dataStart, i64 $curIdx")
            val r = fresh("q.eval")
            emit("  $r = load i8, i8* $ptrReg, align 1")
            r
        } else {
            val castReg = fresh("q.ecast")
            emit("  $castReg = bitcast i8* $dataStart to ${llvmPtrType(elemLt)}")
            val ptrReg = fresh("q.eptr")
            emit("  $ptrReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $castReg, i64 $curIdx")
            val r = fresh("q.eval")
            emit("  $r = load $elemLt, ${llvmPtrType(elemLt)} $ptrReg, align $elemSize")
            r
        }

        // Bind loop variable so body expression can use it
        if (bindName != "_") {
            val bindAlloca = emitAlloca(bindName, elemLt)
            varAllocas[bindName] = Pair(bindAlloca, elemLt)
            if (elemType != Type.TUnknown) varTypes[bindName] = elemType
            emitStore(elemLt, elemValReg, bindAlloca)
        }

        // Evaluate body and update accumulator
        val bodyVal = emitExpr(bodyExpr)
        val accCur  = emitLoad(accLt, accReg, "q.acc.prev")
        val accNew  = fresh("q.acc.new")
        when (expr.op) {
            QuantifierOp.FORALL -> {
                val condI1 = coerceToI1(bodyVal, typeOf(bodyExpr))
                emit("  $accNew = and i1 $accCur, $condI1")
                emitStore(accLt, accNew, accReg)
            }
            QuantifierOp.EXISTS -> {
                val condI1 = coerceToI1(bodyVal, typeOf(bodyExpr))
                emit("  $accNew = or i1 $accCur, $condI1")
                emitStore(accLt, accNew, accReg)
            }
            QuantifierOp.SUM -> {
                val bodyCoerced = coerceToType(bodyVal, typeOf(bodyExpr), accLt)
                val addOp = if (accLt == "double" || accLt == "float") "fadd" else "add"
                emit("  $accNew = $addOp $accLt $accCur, $bodyCoerced")
                emitStore(accLt, accNew, accReg)
            }
            QuantifierOp.PRODUCT -> {
                val bodyCoerced = coerceToType(bodyVal, typeOf(bodyExpr), accLt)
                val mulOp = if (accLt == "double" || accLt == "float") "fmul" else "mul"
                emit("  $accNew = $mulOp $accLt $accCur, $bodyCoerced")
                emitStore(accLt, accNew, accReg)
            }
        }

        // Increment index and loop back
        if (!isTerminated) {
            val nextIdx = fresh("q.next")
            emit("  $nextIdx = add i64 $curIdx, 1")
            emitStore("i64", nextIdx, idxReg)
            emit("  br label %$condLabel")
        }

        emitRaw("$endLabel:")
        isTerminated = false
        return emitLoad(accLt, accReg, "q.result")
    }

    private fun emitBuilderCallExpr(expr: BuilderCallExpr): String {
        val typeName = expr.typeName
        val fields   = structLayouts[typeName] ?: return "null"
        val ctorParamCount = structCtorParamCount[typeName] ?: fields.size
        val defaults = structCtorDefaults[typeName] ?: emptyList()
        val assignMap: Map<String, Expr> = expr.assignments.toMap()
        // Only pass ctor-param fields to the constructor (not lazy/body fields)
        val argParts = fields.take(ctorParamCount).map { (fieldName, fieldType) ->
            val llvmT = llvmType(fieldType)
            val reg = when {
                assignMap.containsKey(fieldName) -> emitExpr(assignMap[fieldName]!!)
                else -> {
                    val defExpr = defaults.firstOrNull { it.first == fieldName }?.second
                    if (defExpr != null) emitExpr(defExpr) else if (llvmT == "i8*") "null" else "0"
                }
            }
            "$llvmT $reg"
        }
        val result = fresh("bld")
        emit("  $result = call i8* @nv_$typeName(${argParts.joinToString(", ")})")
        return result
    }
}

/** Detects the host CPU architecture for @asm / @bytes target selection. */
fun detectHostArch(): String {
    val osArch = System.getProperty("os.arch") ?: ""
    return when {
        osArch == "aarch64" || osArch.contains("arm64") -> "arm64"
        osArch == "amd64"   || osArch.contains("x86_64") -> "x86_64"
        else -> osArch
    }
}
