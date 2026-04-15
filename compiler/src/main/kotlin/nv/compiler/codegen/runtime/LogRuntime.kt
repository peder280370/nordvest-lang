package nv.compiler.codegen.runtime

/**
 * std.log runtime: level-filtered logging to stdout.
 * Levels: 0=DEBUG, 1=INFO (default), 2=WARN, 3=ERROR; FATAL always prints then exits.
 */
internal object LogRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.log ──────────────────────────────────────────────────────────────────

@nv_log_level         = global i64 1, align 8
@nv_log_trace_enabled = global i1 0, align 1
@.log.fmt        = private unnamed_addr constant [6 x i8]  c"%s%s\0A\00",      align 1
@.log.cause_fmt  = private unnamed_addr constant [10 x i8] c"%s%s: %s\0A\00",  align 1
@.log.trace_hdr  = private unnamed_addr constant [10 x i8] c"  Trace:\0A\00",  align 1
@.log.trace_item = private unnamed_addr constant [8 x i8]  c"    %s\0A\00",    align 1
@.log.debug_pfx  = private unnamed_addr constant [9 x i8]  c"[DEBUG] \00", align 1
@.log.info_pfx   = private unnamed_addr constant [8 x i8]  c"[INFO] \00",  align 1
@.log.warn_pfx   = private unnamed_addr constant [8 x i8]  c"[WARN] \00",  align 1
@.log.error_pfx  = private unnamed_addr constant [9 x i8]  c"[ERROR] \00", align 1
@.log.fatal_pfx  = private unnamed_addr constant [9 x i8]  c"[FATAL] \00", align 1
@.log.debug_s    = private unnamed_addr constant [6 x i8]  c"debug\00",    align 1
@.log.info_s     = private unnamed_addr constant [5 x i8]  c"info\00",     align 1
@.log.warn_s     = private unnamed_addr constant [5 x i8]  c"warn\00",     align 1
@.log.error_s    = private unnamed_addr constant [6 x i8]  c"error\00",    align 1

; nv_log_debug(msg: i8*)  (prints if @nv_log_level <= 0)
define void @nv_log_debug(i8* %msg) {
entry:
  %lv = load i64, i64* @nv_log_level, align 8
  %ok = icmp sle i64 %lv, 0
  br i1 %ok, label %print, label %skip
print:
  %fmt = getelementptr [6 x i8], [6 x i8]* @.log.fmt, i64 0, i64 0
  %pfx = getelementptr [9 x i8], [9 x i8]* @.log.debug_pfx, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %pfx, i8* %msg)
  br label %skip
skip:
  ret void
}

; nv_log_info(msg: i8*)  (prints if @nv_log_level <= 1)
define void @nv_log_info(i8* %msg) {
entry:
  %lv = load i64, i64* @nv_log_level, align 8
  %ok = icmp sle i64 %lv, 1
  br i1 %ok, label %print, label %skip
print:
  %fmt = getelementptr [6 x i8], [6 x i8]* @.log.fmt, i64 0, i64 0
  %pfx = getelementptr [8 x i8], [8 x i8]* @.log.info_pfx, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %pfx, i8* %msg)
  br label %skip
skip:
  ret void
}

; nv_log_warn(msg: i8*)  (prints if @nv_log_level <= 2)
define void @nv_log_warn(i8* %msg) {
entry:
  %lv = load i64, i64* @nv_log_level, align 8
  %ok = icmp sle i64 %lv, 2
  br i1 %ok, label %print, label %skip
print:
  %fmt = getelementptr [6 x i8], [6 x i8]* @.log.fmt, i64 0, i64 0
  %pfx = getelementptr [8 x i8], [8 x i8]* @.log.warn_pfx, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %pfx, i8* %msg)
  br label %skip
skip:
  ret void
}

; nv_log_error(msg: i8*)  (prints if @nv_log_level <= 3)
define void @nv_log_error(i8* %msg) {
entry:
  %lv = load i64, i64* @nv_log_level, align 8
  %ok = icmp sle i64 %lv, 3
  br i1 %ok, label %print, label %skip
print:
  %fmt = getelementptr [6 x i8], [6 x i8]* @.log.fmt, i64 0, i64 0
  %pfx = getelementptr [9 x i8], [9 x i8]* @.log.error_pfx, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %pfx, i8* %msg)
  br label %skip
skip:
  ret void
}

; nv_log_fatal(msg: i8*)  (always prints then exits with code 1)
define void @nv_log_fatal(i8* %msg) {
entry:
  %fmt = getelementptr [6 x i8], [6 x i8]* @.log.fmt, i64 0, i64 0
  %pfx = getelementptr [9 x i8], [9 x i8]* @.log.fatal_pfx, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %pfx, i8* %msg)
  call void @exit(i32 1)
  unreachable
}

; nv_log_set_level(level: i8*)  (accepts "debug"=0 "info"=1 "warn"=2 "error"=3; unknown → no-op)
define void @nv_log_set_level(i8* %level) {
entry:
  %ds = getelementptr [6 x i8], [6 x i8]* @.log.debug_s, i64 0, i64 0
  %r0 = call i32 @strcmp(i8* %level, i8* %ds)
  %is_debug = icmp eq i32 %r0, 0
  br i1 %is_debug, label %set0, label %chk1
set0:
  store i64 0, i64* @nv_log_level, align 8
  ret void
chk1:
  %is = getelementptr [5 x i8], [5 x i8]* @.log.info_s, i64 0, i64 0
  %r1 = call i32 @strcmp(i8* %level, i8* %is)
  %is_info = icmp eq i32 %r1, 0
  br i1 %is_info, label %set1, label %chk2
set1:
  store i64 1, i64* @nv_log_level, align 8
  ret void
chk2:
  %ws = getelementptr [5 x i8], [5 x i8]* @.log.warn_s, i64 0, i64 0
  %r2 = call i32 @strcmp(i8* %level, i8* %ws)
  %is_warn = icmp eq i32 %r2, 0
  br i1 %is_warn, label %set2, label %chk3
set2:
  store i64 2, i64* @nv_log_level, align 8
  ret void
chk3:
  %es = getelementptr [6 x i8], [6 x i8]* @.log.error_s, i64 0, i64 0
  %r3 = call i32 @strcmp(i8* %level, i8* %es)
  %is_error = icmp eq i32 %r3, 0
  br i1 %is_error, label %set3, label %done
set3:
  store i64 3, i64* @nv_log_level, align 8
  ret void
done:
  ret void
}

; nv_log_flush()  (fflush(NULL) — flushes all open stdio streams)
define void @nv_log_flush() {
entry:
  call i32 @fflush(i8* null)
  ret void
}

; nv_log_set_trace_enabled(enabled: i1)
define void @nv_log_set_trace_enabled(i1 %enabled) {
entry:
  store i1 %enabled, i1* @nv_log_trace_enabled, align 1
  ret void
}

; nv_log_do_trace()  — prints a backtrace (callers must check flag themselves)
define void @nv_log_do_trace() {
entry:
  %buf = alloca [32 x i8*], align 8
  %buf_ptr = getelementptr [32 x i8*], [32 x i8*]* %buf, i64 0, i64 0
  %n = call i32 @backtrace(i8** %buf_ptr, i32 32)
  %syms = call i8** @backtrace_symbols(i8** %buf_ptr, i32 %n)
  %hdr = getelementptr [10 x i8], [10 x i8]* @.log.trace_hdr, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %hdr)
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %i_next, %loop_body ]
  %cond = icmp slt i32 %i, %n
  br i1 %cond, label %loop_body, label %loop_end
loop_body:
  %i64 = sext i32 %i to i64
  %sym_ptr = getelementptr i8*, i8** %syms, i64 %i64
  %sym = load i8*, i8** %sym_ptr, align 8
  %fmt = getelementptr [8 x i8], [8 x i8]* @.log.trace_item, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %sym)
  %i_next = add i32 %i, 1
  br label %loop
loop_end:
  %syms_cast = bitcast i8** %syms to i8*
  call void @free(i8* %syms_cast)
  ret void
}

; nv_log_warnWith(msg: i8*, cause: i8*)  (prints if @nv_log_level <= 2; trace if enabled)
define void @nv_log_warnWith(i8* %msg, i8* %cause) {
entry:
  %lv = load i64, i64* @nv_log_level, align 8
  %ok = icmp sle i64 %lv, 2
  br i1 %ok, label %print, label %skip
print:
  %fmt = getelementptr [10 x i8], [10 x i8]* @.log.cause_fmt, i64 0, i64 0
  %pfx = getelementptr [8 x i8], [8 x i8]* @.log.warn_pfx, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %pfx, i8* %msg, i8* %cause)
  %te = load i1, i1* @nv_log_trace_enabled, align 1
  br i1 %te, label %trace, label %skip
trace:
  call void @nv_log_do_trace()
  br label %skip
skip:
  ret void
}

; nv_log_errorWith(msg: i8*, cause: i8*)  (always prints at current level; trace if enabled)
define void @nv_log_errorWith(i8* %msg, i8* %cause) {
entry:
  %lv = load i64, i64* @nv_log_level, align 8
  %ok = icmp sle i64 %lv, 3
  br i1 %ok, label %print, label %skip
print:
  %fmt = getelementptr [10 x i8], [10 x i8]* @.log.cause_fmt, i64 0, i64 0
  %pfx = getelementptr [9 x i8], [9 x i8]* @.log.error_pfx, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %pfx, i8* %msg, i8* %cause)
  %te = load i1, i1* @nv_log_trace_enabled, align 1
  br i1 %te, label %trace, label %skip
trace:
  call void @nv_log_do_trace()
  br label %skip
skip:
  ret void
}

; nv_log_fatalWith(msg: i8*, cause: i8*)  (always prints + always traces, then exits)
define void @nv_log_fatalWith(i8* %msg, i8* %cause) {
entry:
  %fmt = getelementptr [10 x i8], [10 x i8]* @.log.cause_fmt, i64 0, i64 0
  %pfx = getelementptr [9 x i8], [9 x i8]* @.log.fatal_pfx, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %fmt, i8* %pfx, i8* %msg, i8* %cause)
  call void @nv_log_do_trace()
  call void @exit(i32 1)
  unreachable
}
""".trimIndent())
    }
}
