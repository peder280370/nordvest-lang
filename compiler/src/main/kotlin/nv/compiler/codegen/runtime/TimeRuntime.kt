package nv.compiler.codegen.runtime

/** std.time runtime: clock, monotonic timer, sleep. */
internal object TimeRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.time ─────────────────────────────────────────────────────────────────

; nv_time_now_ms() → i64  (milliseconds since epoch via CLOCK_REALTIME=0)
define i64 @nv_time_now_ms() {
entry:
  %ts = alloca [16 x i8], align 8
  %tsp = bitcast [16 x i8]* %ts to i8*
  call i32 @clock_gettime(i32 0, i8* %tsp)
  %secp = bitcast [16 x i8]* %ts to i64*
  %nsecp = getelementptr i64, i64* %secp, i64 1
  %sec = load i64, i64* %secp, align 8
  %nsec = load i64, i64* %nsecp, align 8
  %sec_ms = mul i64 %sec, 1000
  %ns_ms = sdiv i64 %nsec, 1000000
  %ms = add i64 %sec_ms, %ns_ms
  ret i64 %ms
}

; nv_time_now_float() → double  (seconds since epoch as float)
define double @nv_time_now_float() {
entry:
  %ms = call i64 @nv_time_now_ms()
  %f = sitofp i64 %ms to double
  %r = fdiv double %f, 1000.0
  ret double %r
}

; nv_time_monotonic_ns() → i64  (CLOCK_REALTIME=0 used for portability)
define i64 @nv_time_monotonic_ns() {
entry:
  %ts = alloca [16 x i8], align 8
  %tsp = bitcast [16 x i8]* %ts to i8*
  call i32 @clock_gettime(i32 0, i8* %tsp)
  %secp = bitcast [16 x i8]* %ts to i64*
  %nsecp = getelementptr i64, i64* %secp, i64 1
  %sec = load i64, i64* %secp, align 8
  %nsec = load i64, i64* %nsecp, align 8
  %sec_ns = mul i64 %sec, 1000000000
  %ns = add i64 %sec_ns, %nsec
  ret i64 %ns
}

; nv_time_sleep_ms(ms)
define void @nv_time_sleep_ms(i64 %ms) {
entry:
  %ts = alloca [16 x i8], align 8
  %tsp = bitcast [16 x i8]* %ts to i8*
  %secp = bitcast [16 x i8]* %ts to i64*
  %nsecp = getelementptr i64, i64* %secp, i64 1
  %sec = sdiv i64 %ms, 1000
  %rem = srem i64 %ms, 1000
  %nsec = mul i64 %rem, 1000000
  store i64 %sec, i64* %secp, align 8
  store i64 %nsec, i64* %nsecp, align 8
  call i32 @nanosleep(i8* %tsp, i8* null)
  ret void
}
""".trimIndent())
    }
}
