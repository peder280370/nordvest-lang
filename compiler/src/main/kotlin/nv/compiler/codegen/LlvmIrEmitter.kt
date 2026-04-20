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
 *
 * The class body is split across several sibling files:
 *   LlvmIrEmitter+Declarations.kt — struct/class/function/method declarations
 *   LlvmIrEmitter+Derives.kt      — @derive / @config / @lazy / by-delegation synthesis
 *   LlvmIrEmitter+Stmts.kt        — statement codegen
 *   LlvmIrEmitter+Exprs.kt        — expression codegen
 */
class LlvmIrEmitter(
    internal val tcModule: TypeCheckedModule,
    /** Target architecture for @asm / @bytes arch-selection. Defaults to host arch. */
    val targetArch: String = detectHostArch(),
    /** When true, skip user main() and emit a TAP test-runner main() wrapping @test functions. */
    val testMode: Boolean = false,
) {

    // ── Output sections ───────────────────────────────────────────────────

    internal val globals   = StringBuilder()   // @.fmt.* / @.str.* constants
    internal val declares  = StringBuilder()   // declare (libc)
    internal val rtFns     = StringBuilder()   // nv runtime function bodies
    internal val userFns   = StringBuilder()   // user function bodies

    // ── Global counters ───────────────────────────────────────────────────

    internal var tempIdx   = 0
    internal var labelIdx  = 0
    internal var strIdx    = 0
    internal val stringPool = mutableMapOf<String, String>()  // raw content → @name

    // ── Per-function state (reset for each function) ──────────────────────

    internal var fnBody    = StringBuilder()
    internal var fnAllocas = StringBuilder()
    internal val varAllocas = mutableMapOf<String, Pair<String, String>>()  // name → (reg, llvmType)
    internal val varTypes   = mutableMapOf<String, Type>()                   // name → Nordvest Type (finer than llvmType)
    internal var fnReturnType = "void"
    internal var isTerminated = false

    // ── Loop stack for break/continue ─────────────────────────────────────
    internal data class LoopTarget(val condLabel: String, val endLabel: String, val userLabel: String?)
    internal val loopStack = ArrayDeque<LoopTarget>()

    // ── Extern function registry (populated at start of emit()) ──────────
    internal data class ExternInfo(val cSymbol: String, val retType: Type, val paramTypes: List<Type>)
    internal val externFunctions = mutableMapOf<String, ExternInfo>()

    // ── Inline runtime functions (defined via define, not just declared) ──
    // Apple Clang 12 rejects declare+define in the same module; skip declare for these.
    internal val inlineRuntimeFns = setOf(
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
        "nv_log_set_trace_enabled", "nv_log_do_trace",
        // std.test
        "nv_assert", "nv_fail",
        "nv_assert_nil", "nv_assert_not_nil",
        "nv_assert_ok", "nv_assert_err",
        "nv_test_begin", "nv_test_end",
        "nv_test_print_header", "nv_test_report", "nv_test_exit",
        "nv_test_skip"
    )

    // ── Actual LLVM signatures for pointer-typed inline runtime functions ──
    internal val inlineRuntimeFnPtrSigs: Map<String, Pair<String, List<String>>> = mapOf(
        "nv_rc_retain"  to ("void" to listOf("i8*")),
        "nv_rc_release" to ("void" to listOf("i8*")),
        "nv_weak_load"  to ("i8*"  to listOf("i8*")),
        "nv_Ok"         to ("i8*"  to listOf("i8*")),
        "nv_Err"        to ("i8*"  to listOf("i8*")),
    )

    // ── Struct layout registry ────────────────────────────────────────────
    internal val structLayouts      = mutableMapOf<String, List<Pair<String, Type>>>()
    internal val structCtorDefaults = mutableMapOf<String, List<Pair<String, Expr?>>>()
    internal val structCtorParamCount = mutableMapOf<String, Int>()

    // ── @lazy field registry: typeName → [(fieldName, fieldType, initExpr)] ──
    internal val lazyFields = mutableMapOf<String, MutableList<Triple<String, Type, Expr>>>()

    // ── by-delegation registry ────────────────────────────────────────────
    internal val classDelegations = mutableMapOf<String, MutableList<Triple<String, String, String>>>()

    // ── @config registry ─────────────────────────────────────────────────
    internal val configPrefixes      = mutableMapOf<String, String>()
    internal val configEnvOverrides  = mutableMapOf<String, List<Pair<String, String?>>>()

    // ── Class type registry: names of ClassDecl types (have RC header) ───
    internal val classTypeNames = mutableSetOf<String>()

    // ── Method return type registry ───────────────────────────────────────
    internal val methodReturnTypes = mutableMapOf<String, String>()
    internal val iteratorClassNames = mutableMapOf<String, String>()

    // ── Sealed class registry ─────────────────────────────────────────────
    internal val sealedClassDecls = mutableMapOf<String, SealedClassDecl>()
    internal val variantTags      = mutableMapOf<String, Int>()

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

        if (testMode) {
            val testFns = file.declarations
                .filterIsInstance<FunctionDecl>()
                .filter { fn -> fn.annotations.any { it.name == "test" } && fn.params.isEmpty() }
            emitTestRunnerMain(testFns)
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

    /** Emit a TAP-compatible main() that calls each @test function in order. */
    private fun emitTestRunnerMain(testFns: List<FunctionDecl>) {
        if (testFns.isEmpty()) return
        val total = testFns.size
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("define i32 @main(i32 %argc, i8** %argv) {")
        sb.appendLine("entry:")
        sb.appendLine("  call void @nv_test_print_header(i64 $total)")

        for ((idx, fn) in testFns.withIndex()) {
            val num = idx + 1
            val displayName = fn.annotations
                .find { it.name == "test" }
                ?.args?.find { it.name == "description" || it.name == null }
                ?.let { (it.value as? AnnotationStrValue)?.value }
                ?: fn.name
            val nameGlobal = internString(displayName)
            sb.appendLine("  call void @nv_test_begin()")
            sb.appendLine("  call void @nv_${fn.name}()")
            sb.appendLine("  call void @nv_test_report(i8* $nameGlobal, i64 $num)")
        }

        sb.appendLine("  %exitcode = call i32 @nv_test_exit()")
        sb.appendLine("  ret i32 %exitcode")
        sb.appendLine("}")
        userFns.append(sb)
    }

    /**
     * Intern a string constant into [globals] and return a constant GEP expression
     * suitable for use as an inline argument in [userFns] IR (not in a function body).
     */
    private fun internString(s: String): String {
        val globalName = stringPool.getOrPut(s) {
            val name = "@str.${strIdx++}"
            val escaped = llvmEscape(s)
            val len = llvmByteLen(s) + 1
            globals.appendLine("""$name = private unnamed_addr constant [$len x i8] c"$escaped\00", align 1""")
            name
        }
        val len = llvmByteLen(s) + 1
        return "getelementptr inbounds ([$len x i8], [$len x i8]* $globalName, i64 0, i64 0)"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stdlib extern registry
    // ─────────────────────────────────────────────────────────────────────

    internal val stdlibExternRegistry: List<Triple<String, String, Pair<Type, List<Type>>>> = listOf(
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
    // Preamble, libc declares, runtime functions, sealed class functions
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
        TestRuntime.emit(rtFns)
    }

    private fun emitSealedClassFunctions() {
        for ((_, decl) in sealedClassDecls) {
            for ((idx, variant) in decl.variants.withIndex()) {
                emitSealedVariantConstructor(variant, idx)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Declaration dispatch
    // ─────────────────────────────────────────────────────────────────────

    private fun emitDecl(decl: Decl) {
        when (decl) {
            is FunctionDecl -> {
                // In testMode, skip the user's main() — the test runner generates its own.
                if (testMode && decl.name == "main" && decl.params.isEmpty()) return
                if (decl.annotations.any { it.name == "gpu" }) {
                    userFns.appendLine("; @gpu kernel: ${decl.name}")
                }
                if (decl.annotations.any { it.name == "extern" }) {
                    emitExternDecl(decl)
                } else {
                    emitFunctionDecl(decl)
                }
            }
            is FunctionSignatureDecl -> {
                if (decl.annotations.any { it.name == "extern" }) {
                    emitExternSigDecl(decl)
                }
            }
            is StructDecl -> emitStructOrClassDecl(decl.name, decl.constructorParams, decl.members, decl.annotations, isClass = false)
            is ClassDecl  -> emitStructOrClassDecl(decl.name, decl.constructorParams, decl.members, decl.annotations, isClass = true)
            is RecordDecl -> emitStructOrClassDecl(decl.name, decl.constructorParams, decl.members, decl.annotations, isClass = false)
            else          -> { /* unsupported at top-level */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Type mapping
    // ─────────────────────────────────────────────────────────────────────

    internal fun llvmType(t: Type): String = when (t) {
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
        else                                          -> "i64"
    }

    internal fun typeOf(expr: Expr): Type {
        val mapped = tcModule.typeMap[expr.span.start.offset to expr.span.end.offset]
        if (mapped != null && mapped != Type.TUnknown) return mapped
        if (expr is IdentExpr) {
            val nvType = varTypes[expr.name]
            if (nvType != null) return nvType
            val lt = varAllocas[expr.name]?.second
            if (lt != null) return llvmTypeToType(lt)
        }
        if (expr is CallExpr && expr.callee is IdentExpr) {
            val ext = externFunctions[(expr.callee as IdentExpr).name]
            if (ext != null) return ext.retType
        }
        if (expr is IndexExpr) {
            val receiverType = typeOf(expr.receiver)
            if (receiverType is Type.TArray) return receiverType.element
            if (receiverType is Type.TStr)   return Type.TChar
        }
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

    internal fun llvmTypeToType(lt: String): Type = when (lt) {
        "i64"    -> Type.TInt
        "i32"    -> Type.TInt
        "i1"     -> Type.TBool
        "i8"     -> Type.TChar
        "double" -> Type.TFloat
        "float"  -> Type.TFloat32
        "void"   -> Type.TUnit
        "i8*"    -> Type.TStr
        else     -> Type.TUnknown
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fresh register / label / string helpers
    // ─────────────────────────────────────────────────────────────────────

    internal fun fresh(prefix: String = "tmp") = "%$prefix.${tempIdx++}"
    internal fun freshLabel(prefix: String)    = "$prefix.${labelIdx++}"

    internal fun stringConst(text: String): String {
        val globalName = stringPool.getOrPut(text) {
            val name = "@str.${strIdx++}"
            val escaped = llvmEscape(text)
            val len = llvmByteLen(text) + 1
            globals.appendLine("""$name = private unnamed_addr constant [$len x i8] c"$escaped\00", align 1""")
            name
        }
        val gepReg = fresh("gep")
        val len = llvmByteLen(text) + 1
        emit("  $gepReg = getelementptr [$len x i8], [$len x i8]* $globalName, i64 0, i64 0")
        return gepReg
    }

    internal fun fmtGep(globalName: String, len: Int): String {
        val reg = fresh("fgep")
        emit("  $reg = getelementptr [$len x i8], [$len x i8]* $globalName, i64 0, i64 0")
        return reg
    }

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

    private fun llvmByteLen(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    internal fun llvmPtrType(lt: String): String = "$lt*"

    // ─────────────────────────────────────────────────────────────────────
    // Per-function emit helpers
    // ─────────────────────────────────────────────────────────────────────

    internal fun emit(s: String) {
        if (isTerminated) {
            val label = freshLabel("dead")
            fnBody.appendLine("$label:")
            isTerminated = false
        }
        fnBody.appendLine(s)
    }

    internal fun emitRaw(s: String) {
        fnBody.appendLine(s)
    }

    internal fun emitAlloca(name: String, llvmTy: String): String {
        val reg = "%local.$name"
        val align = llvmTypeAlign(llvmTy)
        fnAllocas.appendLine("  $reg = alloca $llvmTy, align $align")
        return reg
    }

    internal fun llvmTypeAlign(lt: String): Int = when (lt) {
        "i1"  -> 1
        "i8"  -> 1
        "i32" -> 4
        "i64" -> 8
        "double" -> 8
        "float"  -> 4
        else     -> 8
    }

    internal fun emitStore(lt: String, valReg: String, ptrReg: String) {
        val align = llvmTypeAlign(lt)
        emit("  store $lt $valReg, ${llvmPtrType(lt)} $ptrReg, align $align")
    }

    internal fun emitLoad(lt: String, ptrReg: String, hint: String = "load"): String {
        val reg = fresh(hint)
        val align = llvmTypeAlign(lt)
        emit("  $reg = load $lt, ${llvmPtrType(lt)} $ptrReg, align $align")
        return reg
    }

    internal fun terminate(instruction: String) {
        fnBody.appendLine(instruction)
        isTerminated = true
    }

    // ─────────────────────────────────────────────────────────────────────
    // Coercion helpers
    // ─────────────────────────────────────────────────────────────────────

    internal fun coerceToI1(reg: String, ty: Type): String {
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

    internal fun coerceToI64(reg: String, ty: Type): String {
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

    internal fun coerceForArith(reg: String, ty: Type, forceFloat: Boolean = false): String {
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

    internal fun coerceToType(reg: String, fromTy: Type, toLt: String): String {
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
            else -> reg
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Value / size helpers
    // ─────────────────────────────────────────────────────────────────────

    internal fun nullConstant(lt: String): String = when (lt) {
        "i8*", "i64*", "double*", "float*", "i1*", "i32*" -> "null"
        "i1"     -> "0"
        "i64"    -> "0"
        "i32"    -> "0"
        "i8"     -> "0"
        "double" -> "0.0"
        "float"  -> "0.0"
        else     -> "null"
    }

    internal fun defaultValue(lt: String): String = when (lt) {
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

    internal fun llvmTypeSize(lt: String): Int = when (lt) {
        "i1"  -> 1
        "i8"  -> 1
        "i32" -> 4
        "i64" -> 8
        "double" -> 8
        "float"  -> 4
        else     -> 8
    }

    // ─────────────────────────────────────────────────────────────────────
    // TypeNode resolution
    // ─────────────────────────────────────────────────────────────────────

    internal fun resolveTypeNode(node: TypeNode): Type = when (node) {
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
    // RC type test (used by Declarations and Derives)
    // ─────────────────────────────────────────────────────────────────────

    internal fun isRcType(t: Type): Boolean = when (t) {
        is Type.TNamed    -> t.qualifiedName.substringAfterLast('.') in classTypeNames
        is Type.TNullable -> isRcType(t.inner)
        else              -> false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top-level helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Readable short type name for method dispatch / struct layout lookup. */
internal fun Type.simpleTypeName(): String = when (this) {
    Type.TInt, Type.TInt64     -> "int"
    Type.TFloat, Type.TFloat64 -> "float"
    Type.TFloat32              -> "float32"
    Type.TBool                 -> "bool"
    Type.TStr                  -> "str"
    is Type.TNamed             -> qualifiedName.substringAfterLast('.')
    is Type.TNullable          -> inner.simpleTypeName()
    else                       -> "unknown"
}

/** Detects the host CPU architecture for @asm / @bytes target selection. */
fun detectHostArch(): String {
    val osArch = System.getProperty("os.arch") ?: ""
    return when {
        osArch == "aarch64" || osArch.contains("arm64")   -> "arm64"
        osArch == "amd64"   || osArch.contains("x86_64") -> "x86_64"
        else -> osArch
    }
}
