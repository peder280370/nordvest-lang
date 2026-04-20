package nv.compiler.codegen.runtime

/**
 * std.test runtime: TAP-compatible unit test framework.
 *
 * Global state:
 *   @nv_test_failed    — i1, set to 1 when an assertion fails in the current test
 *   @nv_test_fail_msg  — i8*, message from the failing assertion (or null)
 *   @nv_test_pass_count — i64, cumulative pass count across all tests
 *   @nv_test_fail_count — i64, cumulative fail count across all tests
 *
 * Flow (per test):
 *   nv_test_begin()            — reset per-test fail state
 *   <call user test fn>
 *   nv_test_report(name, num)  — print "ok N - name" / "not ok N - name\n  # msg", update counts
 *
 * Top-level:
 *   nv_test_print_header(total)  — print "TAP version 13\n1..N\n"
 *   nv_test_exit() → i32         — print summary line, return 0 (all pass) or 1 (any fail)
 *
 * String lengths (bytes including null terminator):
 *   "TAP version 13\n"                → 16 bytes  [16 x i8]
 *   "1..%lld\n"                       →  9 bytes  [9 x i8]
 *   "ok %lld - %s\n"                  → 14 bytes  [14 x i8]
 *   "not ok %lld - %s\n"              → 18 bytes  [18 x i8]
 *   "  # %s\n"                        →  8 bytes  [8 x i8]
 *   "\n# %lld passed, %lld failed\n"  → 29 bytes  [29 x i8]
 *   "expected nil but got non-nil"    → 29 bytes  [29 x i8]
 *   "expected non-nil value"          → 23 bytes  [23 x i8]
 *   "expected Ok result but got Err"  → 31 bytes  [31 x i8]
 *   "expected Err result but got Ok"  → 31 bytes  [31 x i8]
 */
internal object TestRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.test ─────────────────────────────────────────────────────────────────

@nv_test_failed     = global i1  0,    align 1
@nv_test_fail_msg   = global i8* null, align 8
@nv_test_pass_count = global i64 0,    align 8
@nv_test_fail_count = global i64 0,    align 8

@.test.tap_hdr    = private unnamed_addr constant [16 x i8] c"TAP version 13\0A\00", align 1
@.test.plan_fmt   = private unnamed_addr constant [9 x i8]  c"1..%lld\0A\00", align 1
@.test.ok_fmt     = private unnamed_addr constant [14 x i8] c"ok %lld - %s\0A\00", align 1
@.test.notok_fmt  = private unnamed_addr constant [18 x i8] c"not ok %lld - %s\0A\00", align 1
@.test.diag_fmt   = private unnamed_addr constant [8 x i8]  c"  # %s\0A\00", align 1
@.test.summary    = private unnamed_addr constant [29 x i8] c"\0A# %lld passed, %lld failed\0A\00", align 1
@.test.nil_msg    = private unnamed_addr constant [29 x i8] c"expected nil but got non-nil\00", align 1
@.test.notnil_msg = private unnamed_addr constant [23 x i8] c"expected non-nil value\00", align 1
@.test.ok_msg     = private unnamed_addr constant [31 x i8] c"expected Ok result but got Err\00", align 1
@.test.err_msg    = private unnamed_addr constant [31 x i8] c"expected Err result but got Ok\00", align 1

; nv_test_begin() — reset per-test fail state
define void @nv_test_begin() {
entry:
  store i1 0, i1* @nv_test_failed, align 1
  store i8* null, i8** @nv_test_fail_msg, align 8
  ret void
}

; nv_test_end() → i1 — returns true if the current test failed
define i1 @nv_test_end() {
entry:
  %f = load i1, i1* @nv_test_failed, align 1
  ret i1 %f
}

; nv_test_print_header(total: i64) — print "TAP version 13\n1..N\n"
define void @nv_test_print_header(i64 %total) {
entry:
  %hdr = getelementptr [16 x i8], [16 x i8]* @.test.tap_hdr, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %hdr)
  %pf = getelementptr [9 x i8], [9 x i8]* @.test.plan_fmt, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %pf, i64 %total)
  ret void
}

; nv_test_report(name: i8*, num: i64) — print ok/not ok line, update global counts
define void @nv_test_report(i8* %name, i64 %num) {
entry:
  %f = load i1, i1* @nv_test_failed, align 1
  br i1 %f, label %failed, label %passed

passed:
  %pc = load i64, i64* @nv_test_pass_count, align 8
  %pc1 = add i64 %pc, 1
  store i64 %pc1, i64* @nv_test_pass_count, align 8
  %okfmt = getelementptr [14 x i8], [14 x i8]* @.test.ok_fmt, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %okfmt, i64 %num, i8* %name)
  br label %done

failed:
  %fc = load i64, i64* @nv_test_fail_count, align 8
  %fc1 = add i64 %fc, 1
  store i64 %fc1, i64* @nv_test_fail_count, align 8
  %nofmt = getelementptr [18 x i8], [18 x i8]* @.test.notok_fmt, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %nofmt, i64 %num, i8* %name)
  %msg = load i8*, i8** @nv_test_fail_msg, align 8
  %hasmsg = icmp ne i8* %msg, null
  br i1 %hasmsg, label %printmsg, label %done

printmsg:
  %diagfmt = getelementptr [8 x i8], [8 x i8]* @.test.diag_fmt, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %diagfmt, i8* %msg)
  br label %done

done:
  ret void
}

; nv_test_exit() → i32 — print summary, return 0 if all passed, 1 if any failed
define i32 @nv_test_exit() {
entry:
  %passed = load i64, i64* @nv_test_pass_count, align 8
  %failed = load i64, i64* @nv_test_fail_count, align 8
  %sumfmt = getelementptr [29 x i8], [29 x i8]* @.test.summary, i64 0, i64 0
  call i32 (i8*, ...) @printf(i8* %sumfmt, i64 %passed, i64 %failed)
  %has_fail = icmp ne i64 %failed, 0
  %code = select i1 %has_fail, i32 1, i32 0
  ret i32 %code
}

; nv_assert(cond: i1, msg: i8*) — mark failure if cond is false
define void @nv_assert(i1 %cond, i8* %msg) {
entry:
  br i1 %cond, label %pass, label %fail
fail:
  store i1 1, i1* @nv_test_failed, align 1
  store i8* %msg, i8** @nv_test_fail_msg, align 8
  br label %done
pass:
  br label %done
done:
  ret void
}

; nv_fail(msg: i8*) — unconditional failure
define void @nv_fail(i8* %msg) {
entry:
  store i1 1, i1* @nv_test_failed, align 1
  store i8* %msg, i8** @nv_test_fail_msg, align 8
  ret void
}

; nv_assert_nil(ptr: i8*) — assert pointer is null
define void @nv_assert_nil(i8* %ptr) {
entry:
  %isnull = icmp eq i8* %ptr, null
  br i1 %isnull, label %pass, label %fail
fail:
  %s = getelementptr [29 x i8], [29 x i8]* @.test.nil_msg, i64 0, i64 0
  store i1 1, i1* @nv_test_failed, align 1
  store i8* %s, i8** @nv_test_fail_msg, align 8
  br label %done
pass:
  br label %done
done:
  ret void
}

; nv_assert_not_nil(ptr: i8*) — assert pointer is non-null
define void @nv_assert_not_nil(i8* %ptr) {
entry:
  %notnull = icmp ne i8* %ptr, null
  br i1 %notnull, label %pass, label %fail
fail:
  %s = getelementptr [23 x i8], [23 x i8]* @.test.notnil_msg, i64 0, i64 0
  store i1 1, i1* @nv_test_failed, align 1
  store i8* %s, i8** @nv_test_fail_msg, align 8
  br label %done
pass:
  br label %done
done:
  ret void
}

; nv_assert_ok(result: i8*) — assert Result tag == 0 (Ok)
define void @nv_assert_ok(i8* %result) {
entry:
  %tp = bitcast i8* %result to i64*
  %tag = load i64, i64* %tp, align 8
  %isok = icmp eq i64 %tag, 0
  br i1 %isok, label %pass, label %fail
fail:
  %s = getelementptr [31 x i8], [31 x i8]* @.test.ok_msg, i64 0, i64 0
  store i1 1, i1* @nv_test_failed, align 1
  store i8* %s, i8** @nv_test_fail_msg, align 8
  br label %done
pass:
  br label %done
done:
  ret void
}

; nv_assert_err(result: i8*) — assert Result tag == 1 (Err)
define void @nv_assert_err(i8* %result) {
entry:
  %tp = bitcast i8* %result to i64*
  %tag = load i64, i64* %tp, align 8
  %iserr = icmp eq i64 %tag, 1
  br i1 %iserr, label %pass, label %fail
fail:
  %s = getelementptr [31 x i8], [31 x i8]* @.test.err_msg, i64 0, i64 0
  store i1 1, i1* @nv_test_failed, align 1
  store i8* %s, i8** @nv_test_fail_msg, align 8
  br label %done
pass:
  br label %done
done:
  ret void
}

; nv_test_skip(msg: i8*) — record a skip (no-op in bootstrap; TAP SKIP in full runtime)
define void @nv_test_skip(i8* %msg) {
entry:
  ret void
}
""".trimIndent())
    }
}
