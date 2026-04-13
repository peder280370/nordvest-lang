package nv.compiler.codegen.runtime

/** std.iter runtime: range and range-with-step array generators. */
internal object IterRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.iter ─────────────────────────────────────────────────────────────────

; nv_iter_range(start, end) → i8*
define i8* @nv_iter_range(i64 %start, i64 %end) {
entry:
  %diff = sub i64 %end, %start
  %pos  = icmp sgt i64 %diff, 0
  %cnt  = select i1 %pos, i64 %diff, i64 0
  %sz0  = mul i64 %cnt, 8
  %sz   = add i64 %sz0, 8
  %arr  = call i8* @malloc(i64 %sz)
  %cntp = bitcast i8* %arr to i64*
  store i64 %cnt, i64* %cntp, align 8
  br label %fill
fill:
  %fi    = phi i64 [ 0, %entry ], [ %fiinc, %fill_body ]
  %fdone = icmp eq i64 %fi, %cnt
  br i1 %fdone, label %done, label %fill_body
fill_body:
  %val  = add i64 %start, %fi
  %eoff = add i64 %fi, 1
  %ep   = getelementptr i64, i64* %cntp, i64 %eoff
  store i64 %val, i64* %ep, align 8
  %fiinc = add i64 %fi, 1
  br label %fill
done:
  ret i8* %arr
}

; nv_iter_range_step(start, end, step) → i8*
define i8* @nv_iter_range_step(i64 %start, i64 %end, i64 %step) {
entry:
  %sp    = icmp sgt i64 %step, 0
  %sn    = icmp slt i64 %step, 0
  %dp    = sub i64 %end, %start
  %dpos  = icmp sgt i64 %dp, 0
  %ssp   = select i1 %sp, i64 %step, i64 1
  %spm1  = sub i64 %ssp, 1
  %dpr   = add i64 %dp, %spm1
  %cntpv = sdiv i64 %dpr, %ssp
  %cnt_p = select i1 %dpos, i64 %cntpv, i64 0
  %dn    = sub i64 %start, %end
  %dneg  = icmp sgt i64 %dn, 0
  %abss  = sub i64 0, %step
  %ssn   = select i1 %sn, i64 %abss, i64 1
  %ssnm1 = sub i64 %ssn, 1
  %dnr   = add i64 %dn, %ssnm1
  %cntnv = sdiv i64 %dnr, %ssn
  %cnt_n = select i1 %dneg, i64 %cntnv, i64 0
  %cnt_s = select i1 %sn, i64 %cnt_n, i64 0
  %cnt   = select i1 %sp, i64 %cnt_p, i64 %cnt_s
  %sz0   = mul i64 %cnt, 8
  %sz    = add i64 %sz0, 8
  %arr   = call i8* @malloc(i64 %sz)
  %cntp  = bitcast i8* %arr to i64*
  store i64 %cnt, i64* %cntp, align 8
  br label %fill
fill:
  %fi   = phi i64 [ 0, %entry ], [ %fiinc, %fill_body ]
  %val  = phi i64 [ %start, %entry ], [ %vnew, %fill_body ]
  %fdone = icmp eq i64 %fi, %cnt
  br i1 %fdone, label %done, label %fill_body
fill_body:
  %eoff = add i64 %fi, 1
  %ep   = getelementptr i64, i64* %cntp, i64 %eoff
  store i64 %val, i64* %ep, align 8
  %vnew  = add i64 %val, %step
  %fiinc = add i64 %fi, 1
  br label %fill
done:
  ret i8* %arr
}

; nv_iter_repeat_int(value, n) → i8*  (array of n copies of value)
define i8* @nv_iter_repeat_int(i64 %value, i64 %n) {
entry:
  %pos  = icmp sgt i64 %n, 0
  %cnt  = select i1 %pos, i64 %n, i64 0
  %sz0  = mul i64 %cnt, 8
  %sz   = add i64 %sz0, 8
  %arr  = call i8* @malloc(i64 %sz)
  %cntp = bitcast i8* %arr to i64*
  store i64 %cnt, i64* %cntp, align 8
  %i    = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %rint.lp
rint.lp:
  %ii   = load i64, i64* %i, align 8
  %done = icmp eq i64 %ii, %cnt
  br i1 %done, label %rint.done, label %rint.body
rint.body:
  %eoff = add i64 %ii, 1
  %ep   = getelementptr i64, i64* %cntp, i64 %eoff
  store i64 %value, i64* %ep, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %rint.lp
rint.done:
  ret i8* %arr
}

; nv_iter_repeat_str(value, n) → i8*  (array of n string pointers)
define i8* @nv_iter_repeat_str(i8* %value, i64 %n) {
entry:
  %pos  = icmp sgt i64 %n, 0
  %cnt  = select i1 %pos, i64 %n, i64 0
  %sz0  = mul i64 %cnt, 8
  %sz   = add i64 %sz0, 8
  %arr  = call i8* @malloc(i64 %sz)
  %cntp = bitcast i8* %arr to i64*
  store i64 %cnt, i64* %cntp, align 8
  %i    = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %rstr.lp
rstr.lp:
  %ii   = load i64, i64* %i, align 8
  %done = icmp eq i64 %ii, %cnt
  br i1 %done, label %rstr.done, label %rstr.body
rstr.body:
  %eoff = add i64 %ii, 1
  %ep   = getelementptr i64, i64* %cntp, i64 %eoff
  %epp  = bitcast i64* %ep to i8**
  store i8* %value, i8** %epp, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %rstr.lp
rstr.done:
  ret i8* %arr
}

; nv_iter_chain_int(a, b) → i8*  (concatenate two int arrays)
define i8* @nv_iter_chain_int(i8* %a, i8* %b) {
entry:
  %ap   = bitcast i8* %a to i64*
  %bp   = bitcast i8* %b to i64*
  %ca   = load i64, i64* %ap, align 8
  %cb   = load i64, i64* %bp, align 8
  %tot  = add i64 %ca, %cb
  %sz0  = mul i64 %tot, 8
  %sz   = add i64 %sz0, 8
  %out  = call i8* @malloc(i64 %sz)
  %op   = bitcast i8* %out to i64*
  store i64 %tot, i64* %op, align 8
  %i    = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %chai.lp
chai.lp:
  %ii   = load i64, i64* %i, align 8
  %done = icmp eq i64 %ii, %ca
  br i1 %done, label %chai.b, label %chai.acopy
chai.acopy:
  %ao   = add i64 %ii, 1
  %asp  = getelementptr i64, i64* %ap, i64 %ao
  %av   = load i64, i64* %asp, align 8
  %odp  = getelementptr i64, i64* %op, i64 %ao
  store i64 %av, i64* %odp, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %chai.lp
chai.b:
  store i64 0, i64* %i, align 8
  br label %chbi.lp
chbi.lp:
  %bi   = load i64, i64* %i, align 8
  %bdone = icmp eq i64 %bi, %cb
  br i1 %bdone, label %chai.done, label %chbi.copy
chbi.copy:
  %bo   = add i64 %bi, 1
  %bsp  = getelementptr i64, i64* %bp, i64 %bo
  %bv   = load i64, i64* %bsp, align 8
  %odo  = add i64 %bi, %ca
  %odq  = add i64 %odo, 1
  %odpp = getelementptr i64, i64* %op, i64 %odq
  store i64 %bv, i64* %odpp, align 8
  %bi1  = add i64 %bi, 1
  store i64 %bi1, i64* %i, align 8
  br label %chbi.lp
chai.done:
  ret i8* %out
}

; nv_iter_chain_str(a, b) → i8*  (concatenate two str arrays)
define i8* @nv_iter_chain_str(i8* %a, i8* %b) {
entry:
  %ap   = bitcast i8* %a to i64*
  %bp   = bitcast i8* %b to i64*
  %ca   = load i64, i64* %ap, align 8
  %cb   = load i64, i64* %bp, align 8
  %tot  = add i64 %ca, %cb
  %sz0  = mul i64 %tot, 8
  %sz   = add i64 %sz0, 8
  %out  = call i8* @malloc(i64 %sz)
  %op   = bitcast i8* %out to i64*
  store i64 %tot, i64* %op, align 8
  %i    = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %chas.lp
chas.lp:
  %ii   = load i64, i64* %i, align 8
  %done = icmp eq i64 %ii, %ca
  br i1 %done, label %chas.b, label %chas.acopy
chas.acopy:
  %ao   = add i64 %ii, 1
  %asp  = getelementptr i64, i64* %ap, i64 %ao
  %aspp = bitcast i64* %asp to i8**
  %av   = load i8*, i8** %aspp, align 8
  %odp  = getelementptr i64, i64* %op, i64 %ao
  %odpp = bitcast i64* %odp to i8**
  store i8* %av, i8** %odpp, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %chas.lp
chas.b:
  store i64 0, i64* %i, align 8
  br label %chbs.lp
chbs.lp:
  %bi   = load i64, i64* %i, align 8
  %bdone = icmp eq i64 %bi, %cb
  br i1 %bdone, label %chas.done, label %chbs.copy
chbs.copy:
  %bo   = add i64 %bi, 1
  %bsp  = getelementptr i64, i64* %bp, i64 %bo
  %bspp = bitcast i64* %bsp to i8**
  %bv   = load i8*, i8** %bspp, align 8
  %odo  = add i64 %bi, %ca
  %odq  = add i64 %odo, 1
  %odpq = getelementptr i64, i64* %op, i64 %odq
  %odpqp = bitcast i64* %odpq to i8**
  store i8* %bv, i8** %odpqp, align 8
  %bi1  = add i64 %bi, 1
  store i64 %bi1, i64* %i, align 8
  br label %chbs.lp
chas.done:
  ret i8* %out
}
""".trimIndent())
    }
}
