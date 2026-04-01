package nv.compiler.codegen

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
    private var fnReturnType = "void"
    private var isTerminated = false

    // ── Loop stack for break/continue ─────────────────────────────────────

    private data class LoopTarget(val condLabel: String, val endLabel: String, val userLabel: String?)
    private val loopStack = ArrayDeque<LoopTarget>()

    // ─────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────

    fun emit(): CodegenResult {
        emitPreamble()
        emitDeclares()
        emitRuntimeFunctions()

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
""".trimIndent())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Nordvest runtime function bodies (LLVM IR, no extra deps beyond libc)
    // ─────────────────────────────────────────────────────────────────────

    private fun emitRuntimeFunctions() {
        rtFns.appendLine("""
define void @nv_print(i8* %s) {
entry:
  %fmts = getelementptr [3 x i8], [3 x i8]* @.fmt.s, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmts, i8* %s)
  ret void
}

define void @nv_println(i8* %s) {
entry:
  %fmtsn = getelementptr [4 x i8], [4 x i8]* @.fmt.sn, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtsn, i8* %s)
  ret void
}

define void @nv_print_int(i64 %n) {
entry:
  %fmtd = getelementptr [4 x i8], [4 x i8]* @.fmt.d, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtd, i64 %n)
  ret void
}

define void @nv_println_int(i64 %n) {
entry:
  %fmtdn = getelementptr [5 x i8], [5 x i8]* @.fmt.dn, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtdn, i64 %n)
  ret void
}

define void @nv_print_float(double %f) {
entry:
  %fmtg = getelementptr [3 x i8], [3 x i8]* @.fmt.g, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtg, double %f)
  ret void
}

define void @nv_println_float(double %f) {
entry:
  %fmtgn = getelementptr [4 x i8], [4 x i8]* @.fmt.gn, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtgn, double %f)
  ret void
}

define void @nv_print_bool(i1 %b) {
entry:
  %b64 = zext i1 %b to i64
  %cond = icmp eq i64 %b64, 0
  br i1 %cond, label %print.false, label %print.true
print.true:
  %fmts.t = getelementptr [3 x i8], [3 x i8]* @.fmt.s, i64 0, i64 0
  %strt = getelementptr [5 x i8], [5 x i8]* @.str.true, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmts.t, i8* %strt)
  br label %print.done
print.false:
  %fmts.f = getelementptr [3 x i8], [3 x i8]* @.fmt.s, i64 0, i64 0
  %strf = getelementptr [6 x i8], [6 x i8]* @.str.false, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmts.f, i8* %strf)
  br label %print.done
print.done:
  ret void
}

define void @nv_println_bool(i1 %b) {
entry:
  %b64 = zext i1 %b to i64
  %cond = icmp eq i64 %b64, 0
  br i1 %cond, label %println.false, label %println.true
println.true:
  %fmtsn.t = getelementptr [4 x i8], [4 x i8]* @.fmt.sn, i64 0, i64 0
  %strt = getelementptr [5 x i8], [5 x i8]* @.str.true, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtsn.t, i8* %strt)
  br label %println.done
println.false:
  %fmtsn.f = getelementptr [4 x i8], [4 x i8]* @.fmt.sn, i64 0, i64 0
  %strf = getelementptr [6 x i8], [6 x i8]* @.str.false, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtsn.f, i8* %strf)
  br label %println.done
println.done:
  ret void
}

define i8* @nv_int_to_str(i64 %n) {
entry:
  %buf = call i8* @malloc(i64 32)
  %fmtd = getelementptr [4 x i8], [4 x i8]* @.fmt.d, i64 0, i64 0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 32, i8* %fmtd, i64 %n)
  ret i8* %buf
}

define i8* @nv_float_to_str(double %f) {
entry:
  %buf = call i8* @malloc(i64 64)
  %fmtg = getelementptr [3 x i8], [3 x i8]* @.fmt.g, i64 0, i64 0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fmtg, double %f)
  ret i8* %buf
}

define i8* @nv_bool_to_str(i1 %b) {
entry:
  %b64 = zext i1 %b to i64
  %cond = icmp eq i64 %b64, 0
  br i1 %cond, label %bts.false, label %bts.true
bts.true:
  %strt = getelementptr [5 x i8], [5 x i8]* @.str.true, i64 0, i64 0
  ret i8* %strt
bts.false:
  %strf = getelementptr [6 x i8], [6 x i8]* @.str.false, i64 0, i64 0
  ret i8* %strf
}

define i8* @nv_str_concat(i8* %a, i8* %b) {
entry:
  %la   = call i64 @strlen(i8* %a)
  %lb   = call i64 @strlen(i8* %b)
  %tot  = add i64 %la, %lb
  %tot1 = add i64 %tot, 1
  %buf  = call i8* @malloc(i64 %tot1)
  call i8* @strcpy(i8* %buf, i8* %a)
  call i8* @strcat(i8* %buf, i8* %b)
  ret i8* %buf
}

define i1 @nv_str_eq(i8* %a, i8* %b) {
entry:
  %r   = call i32 @strcmp(i8* %a, i8* %b)
  %cmp = icmp eq i32 %r, 0
  ret i1 %cmp
}

define i64 @nv_str_len(i8* %s) {
entry:
  %r = call i64 @strlen(i8* %s)
  ret i64 %r
}

define void @nv_panic(i8* %msg) noreturn {
entry:
  %fmtpanic = getelementptr [11 x i8], [11 x i8]* @.panic.fmt, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtpanic, i8* %msg)
  call void @exit(i32 1)
  unreachable
}
""".trimIndent())
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
        is Type.TArray, is Type.TMap, is Type.TResult,
        is Type.TNamed                                -> "i8*"
        else                                          -> "i64"   // TUnknown, TError, TVar, TTuple, etc.
    }

    private fun typeOf(expr: Expr): Type =
        tcModule.typeMap[expr.span.start.offset] ?: Type.TUnknown

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
            is FunctionDecl -> emitFunctionDecl(decl)
            else            -> { /* unsupported at top-level in Phase 1.5 */ }
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
        // Simplified: just emit a comment; proper array iteration requires runtime support
        val arrReg = emitExpr(iterableExpr)

        // For now emit a placeholder that compiles but iterates 0 times
        val idxReg = emitAlloca("arr.idx.${labelIdx}", "i64")
        emitStore("i64", "0", idxReg)
        emit("  br label %$condLabel")

        emitRaw("$condLabel:")
        isTerminated = false
        // We don't know length without runtime support; always false for now
        emit("  br label %$endLabel")

        emitRaw("$bodyLabel:")
        isTerminated = false
        stmt.body.forEach { emitStmt(it) }
        if (!isTerminated) emit("  br label %$condLabel")
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
        // Phase 2.1 bootstrap: call @nv_future_await on the future pointer.
        val futureReg = emitExpr(expr.operand)
        val result = fresh("await")
        emit("  $result = call i8* @nv_future_await(i8* $futureReg)")
        return result
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
        val entry = varAllocas[name] ?: return "0"
        val (allocaReg, lt) = entry
        if (lt == "void") return "0"
        return emitLoad(lt, allocaReg)
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

    private fun emitBinaryExpr(expr: BinaryExpr): String {
        // Short-circuit for && and ||
        if (expr.op == BinaryOp.AND) return emitShortCircuit(expr, isAnd = true)
        if (expr.op == BinaryOp.OR)  return emitShortCircuit(expr, isAnd = false)
        // Null coalesce
        if (expr.op == BinaryOp.NULL_COALESCE) return emitNullCoalesce(expr)
        // Pipeline
        if (expr.op == BinaryOp.PIPELINE) return emitPipeline(expr)

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
        val mangledName = "@nv_$fnName"
        // Determine return type from memberTypeMap or fall back to TUnknown
        val retLt = "i64"  // conservative default; proper typing done above
        val argList = argRegs.zip(argTypes).joinToString(", ") { (r, t) ->
            val lt = llvmType(t); "$lt noundef $r"
        }
        val res = fresh("call")
        emit("  $res = call $retLt $mangledName($argList)")
        return res
    }

    private fun emitMethodCall(callExpr: CallExpr, memberExpr: MemberAccessExpr): String {
        val receiverReg  = emitExpr(memberExpr.receiver)
        val receiverType = typeOf(memberExpr.receiver)
        val member       = memberExpr.member

        // .str() — toString conversion
        if (member == "str" || member == "toString") {
            return convertToStr(receiverReg, receiverType)
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
        val res = fresh("mcall")
        emit("  $res = call i64 $mangledFn($argList)")
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
                // General member access — not fully supported in Phase 1.5
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
        // Phase 1.5: allocate a raw array via malloc
        if (expr.elements.isEmpty()) return "null"
        val elemType = typeOf(expr.elements[0])
        val elemLt   = llvmType(elemType)
        val elemSize = llvmTypeSize(elemLt)
        val totalSize = elemSize * expr.elements.size
        val rawReg = fresh("arr.raw")
        emit("  $rawReg = call i8* @malloc(i64 $totalSize)")
        val arrReg = fresh("arr")
        emit("  $arrReg = bitcast i8* $rawReg to ${llvmPtrType(elemLt)}")
        for ((i, elemExpr) in expr.elements.withIndex()) {
            val valReg   = emitExpr(elemExpr)
            val coerced  = coerceToType(valReg, typeOf(elemExpr), elemLt)
            val idxReg   = fresh("arrptr")
            emit("  $idxReg = getelementptr $elemLt, ${llvmPtrType(elemLt)} $arrReg, i64 $i")
            emit("  store $elemLt $coerced, ${llvmPtrType(elemLt)} $idxReg, align $elemSize")
        }
        // Return as i8*
        val retReg = fresh("arrret")
        emit("  $retReg = bitcast ${llvmPtrType(elemLt)} $arrReg to i8*")
        return retReg
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
                is ExprMatchArmBody  -> { emitExpr(body.expr); Unit }
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
            else -> "1"
        }
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
        val castReg = fresh("idxcast")
        emit("  $castReg = bitcast i8* $receiverReg to ${llvmPtrType(elemLt)}")
        val ptrReg = fresh("idxptr")
        val idxI64 = if (idxArg.expr != null) coerceToI64(idxReg, typeOf(idxArg.expr!!)) else "0"
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
        is QuantifierExpr        -> "0"
        is ListComprehensionExpr -> "null"
        is AwaitExpr             -> emitAwaitExpr(expr)
        is SpawnExpr             -> emitSpawnExprValue(expr)
        else                     -> "0"
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
