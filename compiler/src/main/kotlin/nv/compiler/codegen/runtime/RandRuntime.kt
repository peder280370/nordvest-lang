package nv.compiler.codegen.runtime

/** std.rand runtime: xorshift64 PRNG — seed, next, float, int, bool. */
internal object RandRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.rand (xorshift64) ────────────────────────────────────────────────────

; nv_rand_seed(seed)
define void @nv_rand_seed(i64 %seed) {
entry:
  %z = icmp eq i64 %seed, 0
  %ns = select i1 %z, i64 6364136223846793005, i64 %seed
  store i64 %ns, i64* @nv_rand_state, align 8
  ret void
}

; nv_rand_next() → i64  (xorshift64)
define i64 @nv_rand_next() {
entry:
  %s = load i64, i64* @nv_rand_state, align 8
  %s1 = shl i64 %s, 13
  %s2 = xor i64 %s, %s1
  %s3 = lshr i64 %s2, 7
  %s4 = xor i64 %s2, %s3
  %s5 = shl i64 %s4, 17
  %s6 = xor i64 %s4, %s5
  store i64 %s6, i64* @nv_rand_state, align 8
  ret i64 %s6
}

; nv_rand_init()  — seed from clock
define void @nv_rand_init() {
entry:
  %ts = alloca [16 x i8], align 8
  %tsp = bitcast [16 x i8]* %ts to i8*
  call i32 @clock_gettime(i32 0, i8* %tsp)
  %secp = bitcast [16 x i8]* %ts to i64*
  %nsecp = getelementptr i64, i64* %secp, i64 1
  %sec = load i64, i64* %secp, align 8
  %nsec = load i64, i64* %nsecp, align 8
  %seed = xor i64 %sec, %nsec
  call void @nv_rand_seed(i64 %seed)
  ret void
}

; nv_rand_float() → double  [0.0, 1.0)
define double @nv_rand_float() {
entry:
  %n = call i64 @nv_rand_next()
  %m = lshr i64 %n, 11
  %f = uitofp i64 %m to double
  %r = fdiv double %f, 9007199254740992.0
  ret double %r
}

; nv_rand_int(lo, hi) → i64  [lo, hi)
define i64 @nv_rand_int(i64 %lo, i64 %hi) {
entry:
  %n = call i64 @nv_rand_next()
  %range = sub i64 %hi, %lo
  %pos_range = icmp sgt i64 %range, 0
  br i1 %pos_range, label %ok, label %ret_lo
ok:
  %range_u = bitcast i64 %range to i64
  %mod = urem i64 %n, %range_u
  %res = add i64 %mod, %lo
  ret i64 %res
ret_lo:
  ret i64 %lo
}

; nv_rand_bool() → i1
define i1 @nv_rand_bool() {
entry:
  %n = call i64 @nv_rand_next()
  %b = and i64 %n, 1
  %r = icmp eq i64 %b, 1
  ret i1 %r
}
""".trimIndent())
    }
}
