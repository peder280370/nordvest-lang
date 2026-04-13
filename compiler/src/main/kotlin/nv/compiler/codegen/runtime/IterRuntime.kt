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
""".trimIndent())
    }
}
