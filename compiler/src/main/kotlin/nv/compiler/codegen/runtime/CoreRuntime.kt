package nv.compiler.codegen.runtime

/**
 * Core runtime: print, panic, string primitives, reference counting, Result<T>.
 * All functions are emitted as LLVM IR into the output module.
 */
internal object CoreRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
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

define void @nv_eprintln(i8* %s) {
entry:
  %fmtsn = getelementptr [4 x i8], [4 x i8]* @.fmt.sn, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmtsn, i8* %s)
  ret void
}

define i8* @nv_read_line() {
entry:
  %buf = call i8* @malloc(i64 4096)
  %i = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %rl.loop
rl.loop:
  %ch = call i32 @getchar()
  %at_newline = icmp eq i32 %ch, 10
  %at_eof     = icmp eq i32 %ch, -1
  %done = or i1 %at_newline, %at_eof
  br i1 %done, label %rl.done, label %rl.store
rl.store:
  %idx = load i64, i64* %i, align 8
  %too_big = icmp sge i64 %idx, 4095
  br i1 %too_big, label %rl.done, label %rl.write
rl.write:
  %ch8 = trunc i32 %ch to i8
  %ptr = getelementptr i8, i8* %buf, i64 %idx
  store i8 %ch8, i8* %ptr, align 1
  %idx1 = add i64 %idx, 1
  store i64 %idx1, i64* %i, align 8
  br label %rl.loop
rl.done:
  %len = load i64, i64* %i, align 8
  %is_empty = icmp eq i64 %len, 0
  %ret_null = and i1 %at_eof, %is_empty
  br i1 %ret_null, label %rl.null, label %rl.ok
rl.null:
  call void @free(i8* %buf)
  ret i8* null
rl.ok:
  %term = getelementptr i8, i8* %buf, i64 %len
  store i8 0, i8* %term, align 1
  ret i8* %buf
}

define i8* @nv_read_all() {
entry:
  %buf = call i8* @malloc(i64 65536)
  %len = alloca i64, align 8
  store i64 0, i64* %len, align 8
  br label %ra.loop
ra.loop:
  %ch = call i32 @getchar()
  %at_eof = icmp eq i32 %ch, -1
  br i1 %at_eof, label %ra.done, label %ra.check
ra.check:
  %idx = load i64, i64* %len, align 8
  %too_big = icmp sge i64 %idx, 65535
  br i1 %too_big, label %ra.done, label %ra.write
ra.write:
  %ptr = getelementptr i8, i8* %buf, i64 %idx
  %ch8 = trunc i32 %ch to i8
  store i8 %ch8, i8* %ptr, align 1
  %idx1 = add i64 %idx, 1
  store i64 %idx1, i64* %len, align 8
  br label %ra.loop
ra.done:
  %l = load i64, i64* %len, align 8
  %term = getelementptr i8, i8* %buf, i64 %l
  store i8 0, i8* %term, align 1
  ret i8* %buf
}

; ── Reference counting ────────────────────────────────────────────────────────
; Class object layout: { i64 strong_count, i8* dtor_fn, ...user fields }
; nv_rc_retain — atomically increment strong_count (no-op on null)
define void @nv_rc_retain(i8* %ptr) {
entry:
  %isnull = icmp eq i8* %ptr, null
  br i1 %isnull, label %done, label %do_retain
do_retain:
  %hdr = bitcast i8* %ptr to i64*
  %_old = atomicrmw add i64* %hdr, i64 1 seq_cst
  br label %done
done:
  ret void
}

; nv_rc_release — atomically decrement strong_count; destroy when it reaches 0
define void @nv_rc_release(i8* %ptr) {
entry:
  %isnull = icmp eq i8* %ptr, null
  br i1 %isnull, label %done, label %do_release
do_release:
  %hdr = bitcast i8* %ptr to i64*
  %old = atomicrmw sub i64* %hdr, i64 1 seq_cst
  %is_last = icmp eq i64 %old, 1
  br i1 %is_last, label %destroy, label %done
destroy:
  ; load dtor_fn pointer stored at byte offset 8
  %dtor_loc = getelementptr i8, i8* %ptr, i64 8
  %dtor_pp  = bitcast i8* %dtor_loc to i8**
  %dtor_raw = load i8*, i8** %dtor_pp, align 8
  %null_dtor = icmp eq i8* %dtor_raw, null
  br i1 %null_dtor, label %just_free, label %call_dtor
call_dtor:
  %dtor_fn = bitcast i8* %dtor_raw to void (i8*)*
  call void %dtor_fn(i8* %ptr)
  br label %done
just_free:
  call void @free(i8* %ptr)
  br label %done
done:
  ret void
}

; nv_weak_load — return ptr if strong_count > 0, else null (safe weak-ref load)
define i8* @nv_weak_load(i8* %ptr) {
entry:
  %isnull = icmp eq i8* %ptr, null
  br i1 %isnull, label %ret_null, label %check
check:
  %hdr = bitcast i8* %ptr to i64*
  %sc  = load i64, i64* %hdr, align 8
  %alive = icmp sgt i64 %sc, 0
  br i1 %alive, label %ret_ptr, label %ret_null
ret_ptr:
  ret i8* %ptr
ret_null:
  ret i8* null
}

; Result<T, E> constructors: {i64 tag=0/1, i64 value}
; nv_Ok wraps an i8* value pointer (or any pointer-sized value)
define i8* @nv_Ok(i8* %val) {
entry:
  %p = call i8* @malloc(i64 16)
  %tp = bitcast i8* %p to i64*
  store i64 0, i64* %tp, align 8
  %vp = getelementptr i64, i64* %tp, i64 1
  %ival = ptrtoint i8* %val to i64
  store i64 %ival, i64* %vp, align 8
  ret i8* %p
}

define i8* @nv_Err(i8* %msg) {
entry:
  %p = call i8* @malloc(i64 16)
  %tp = bitcast i8* %p to i64*
  store i64 1, i64* %tp, align 8
  %vp = getelementptr i64, i64* %tp, i64 1
  %ival = ptrtoint i8* %msg to i64
  store i64 %ival, i64* %vp, align 8
  ret i8* %p
}
""".trimIndent())
    }
}
