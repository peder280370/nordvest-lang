package nv.compiler.codegen.runtime

/** std.fmt runtime: integer/float formatting, truncate, file size, duration, thousands separator. */
internal object FmtRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.fmt ──────────────────────────────────────────────────────────────────

; nv_fmt_int(n, width, padChar, radix) → i8*
define i8* @nv_fmt_int(i64 %n, i64 %width, i8* %padChar, i64 %radix) {
entry:
  %buf  = alloca [128 x i8], align 1
  %bufp = getelementptr [128 x i8], [128 x i8]* %buf, i64 0, i64 0
  %is10 = icmp eq i64 %radix, 10
  %is16 = icmp eq i64 %radix, 16
  %is8  = icmp eq i64 %radix, 8
  %neg  = icmp slt i64 %n, 0
  %doneg = and i1 %is10, %neg
  %neg_n = sub i64 0, %n
  %absn  = select i1 %doneg, i64 %neg_n, i64 %n
  %fmtu = getelementptr [5 x i8], [5 x i8]* @.fmt.u64, i64 0, i64 0
  %fmtx = getelementptr [5 x i8], [5 x i8]* @.fmt.llx, i64 0, i64 0
  %fmto = getelementptr [5 x i8], [5 x i8]* @.fmt.llo, i64 0, i64 0
  %fs1  = select i1 %is8,  i8* %fmto, i8* %fmtu
  %fs2  = select i1 %is16, i8* %fmtx, i8* %fs1
  %fmt  = select i1 %is10, i8* %fmtu, i8* %fs2
  %fval = select i1 %is10, i64 %absn, i64 %n
  %soff = select i1 %doneg, i64 1, i64 0
  %wrp  = getelementptr i8, i8* %bufp, i64 %soff
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %wrp, i64 127, i8* %fmt, i64 %fval)
  br i1 %doneg, label %write_sign, label %done_fmt
write_sign:
  store i8 45, i8* %bufp, align 1
  br label %done_fmt
done_fmt:
  %numlen = call i64 @strlen(i8* %bufp)
  %need_pad = icmp sgt i64 %width, %numlen
  br i1 %need_pad, label %do_pad, label %no_pad
no_pad:
  %np_len = add i64 %numlen, 1
  %np_res = call i8* @malloc(i64 %np_len)
  call i8* @strcpy(i8* %np_res, i8* %bufp)
  ret i8* %np_res
do_pad:
  %padlen = sub i64 %width, %numlen
  %reslen = add i64 %width, 1
  %res = call i8* @malloc(i64 %reslen)
  %pc  = load i8, i8* %padChar, align 1
  br label %padloop
padloop:
  %pi = phi i64 [ 0, %do_pad ], [ %piinc, %pad_body ]
  %pldone = icmp eq i64 %pi, %padlen
  br i1 %pldone, label %padcopy, label %pad_body
pad_body:
  %pp = getelementptr i8, i8* %res, i64 %pi
  store i8 %pc, i8* %pp, align 1
  %piinc = add i64 %pi, 1
  br label %padloop
padcopy:
  %dst = getelementptr i8, i8* %res, i64 %padlen
  call i8* @strcpy(i8* %dst, i8* %bufp)
  ret i8* %res
}

; nv_fmt_float(x, precision, notation) → i8*
define i8* @nv_fmt_float(double %x, i64 %precision, i8* %notation) {
entry:
  %buf  = call i8* @malloc(i64 128)
  %nc   = load i8, i8* %notation, align 1
  %nc32 = sext i8 %nc to i32
  %is_e = icmp eq i32 %nc32, 101
  %is_f = icmp eq i32 %nc32, 102
  %fme  = getelementptr [5 x i8], [5 x i8]* @.fmt.fmtfloat_e, i64 0, i64 0
  %fmf  = getelementptr [5 x i8], [5 x i8]* @.fmt.fmtfloat_f, i64 0, i64 0
  %fmg  = getelementptr [5 x i8], [5 x i8]* @.fmt.fmtfloat_g, i64 0, i64 0
  %fm1  = select i1 %is_f, i8* %fmf, i8* %fmg
  %fmt  = select i1 %is_e, i8* %fme, i8* %fm1
  %prec32 = trunc i64 %precision to i32
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 128, i8* %fmt, i32 %prec32, double %x)
  ret i8* %buf
}

; nv_fmt_truncate(s, width, suffix) → i8*
define i8* @nv_fmt_truncate(i8* %s, i64 %width, i8* %suffix) {
entry:
  %slen = call i64 @strlen(i8* %s)
  %need = icmp sgt i64 %slen, %width
  br i1 %need, label %do_trunc, label %no_trunc
no_trunc:
  %r1   = add i64 %slen, 1
  %res0 = call i8* @malloc(i64 %r1)
  call i8* @strcpy(i8* %res0, i8* %s)
  ret i8* %res0
do_trunc:
  %suflen = call i64 @strlen(i8* %suffix)
  %keep0  = sub i64 %width, %suflen
  %kneg   = icmp slt i64 %keep0, 0
  %keep   = select i1 %kneg, i64 0, i64 %keep0
  %actsuf = select i1 %kneg, i64 %width, i64 %suflen
  %reslen = add i64 %width, 1
  %res    = call i8* @malloc(i64 %reslen)
  call i8* @memcpy(i8* noalias %res, i8* noalias %s, i64 %keep)
  %dst    = getelementptr i8, i8* %res, i64 %keep
  call i8* @memcpy(i8* noalias %dst, i8* noalias %suffix, i64 %actsuf)
  %nul    = getelementptr i8, i8* %res, i64 %width
  store i8 0, i8* %nul, align 1
  ret i8* %res
}

; nv_fmt_file_size(bytes) → i8*
define i8* @nv_fmt_file_size(i64 %bytes) {
entry:
  %buf = call i8* @malloc(i64 64)
  %fmb = getelementptr [7 x i8], [7 x i8]* @.fmt.fs_b, i64 0, i64 0
  %fmf = getelementptr [8 x i8], [8 x i8]* @.fmt.fs_flt, i64 0, i64 0
  %kb  = getelementptr [3 x i8], [3 x i8]* @.str.kb, i64 0, i64 0
  %mb  = getelementptr [3 x i8], [3 x i8]* @.str.mb, i64 0, i64 0
  %gb  = getelementptr [3 x i8], [3 x i8]* @.str.gb, i64 0, i64 0
  %tb  = getelementptr [3 x i8], [3 x i8]* @.str.tb, i64 0, i64 0
  %pb  = getelementptr [3 x i8], [3 x i8]* @.str.pb, i64 0, i64 0
  %is_b  = icmp slt i64 %bytes, 1024
  %is_kb = icmp slt i64 %bytes, 1048576
  %is_mb = icmp slt i64 %bytes, 1073741824
  %is_gb = icmp slt i64 %bytes, 1099511627776
  %is_tb = icmp slt i64 %bytes, 1125899906842624
  br i1 %is_b, label %fmt_b, label %try_kb
fmt_b:
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fmb, i64 %bytes)
  ret i8* %buf
try_kb:
  br i1 %is_kb, label %fmt_kb, label %try_mb
fmt_kb:
  %fkb = sitofp i64 %bytes to double
  %fkb2 = fdiv double %fkb, 1024.0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fmf, double %fkb2, i8* %kb)
  ret i8* %buf
try_mb:
  br i1 %is_mb, label %fmt_mb, label %try_gb
fmt_mb:
  %fmb2 = sitofp i64 %bytes to double
  %fmb3 = fdiv double %fmb2, 1048576.0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fmf, double %fmb3, i8* %mb)
  ret i8* %buf
try_gb:
  br i1 %is_gb, label %fmt_gb, label %try_tb
fmt_gb:
  %fgb = sitofp i64 %bytes to double
  %fgb2 = fdiv double %fgb, 1073741824.0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fmf, double %fgb2, i8* %gb)
  ret i8* %buf
try_tb:
  br i1 %is_tb, label %fmt_tb, label %fmt_pb
fmt_tb:
  %ftb = sitofp i64 %bytes to double
  %ftb2 = fdiv double %ftb, 1099511627776.0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fmf, double %ftb2, i8* %tb)
  ret i8* %buf
fmt_pb:
  %fpb = sitofp i64 %bytes to double
  %fpb2 = fdiv double %fpb, 1125899906842624.0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fmf, double %fpb2, i8* %pb)
  ret i8* %buf
}

; nv_fmt_duration(ms) → i8*
define i8* @nv_fmt_duration(i64 %ms) {
entry:
  %buf    = call i8* @malloc(i64 64)
  %fms    = getelementptr [7 x i8], [7 x i8]* @.fmt.dur_ms, i64 0, i64 0
  %fs     = getelementptr [6 x i8], [6 x i8]* @.fmt.dur_s, i64 0, i64 0
  %fms2   = getelementptr [12 x i8], [12 x i8]* @.fmt.dur_ms2, i64 0, i64 0
  %fhm    = getelementptr [12 x i8], [12 x i8]* @.fmt.dur_hm, i64 0, i64 0
  %is_ms  = icmp slt i64 %ms, 1000
  %is_s   = icmp slt i64 %ms, 60000
  %is_min = icmp slt i64 %ms, 3600000
  br i1 %is_ms, label %emit_ms, label %try_s
emit_ms:
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fms, i64 %ms)
  ret i8* %buf
try_s:
  br i1 %is_s, label %emit_s, label %try_min
emit_s:
  %sv = sdiv i64 %ms, 1000
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fs, i64 %sv)
  ret i8* %buf
try_min:
  br i1 %is_min, label %emit_min, label %emit_h
emit_min:
  %mv    = sdiv i64 %ms, 60000
  %msrem = srem i64 %ms, 60000
  %msv   = sdiv i64 %msrem, 1000
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fms2, i64 %mv, i64 %msv)
  ret i8* %buf
emit_h:
  %hv    = sdiv i64 %ms, 3600000
  %hmrem = srem i64 %ms, 3600000
  %hmv   = sdiv i64 %hmrem, 60000
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fhm, i64 %hv, i64 %hmv)
  ret i8* %buf
}

; nv_fmt_thousands(n, sep) → i8*
define i8* @nv_fmt_thousands(i64 %n, i8* %sep) {
entry:
  %neg    = icmp slt i64 %n, 0
  %neg_n  = sub i64 0, %n
  %absn   = select i1 %neg, i64 %neg_n, i64 %n
  %negext = zext i1 %neg to i64
  %digits  = alloca [25 x i8], align 1
  %digitsp = getelementptr [25 x i8], [25 x i8]* %digits, i64 0, i64 0
  %fmtu   = getelementptr [5 x i8], [5 x i8]* @.fmt.u64, i64 0, i64 0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %digitsp, i64 25, i8* %fmtu, i64 %absn)
  %len    = call i64 @strlen(i8* %digitsp)
  %seplen = call i64 @strlen(i8* %sep)
  %len_m1 = sub i64 %len, 1
  %nsep   = udiv i64 %len_m1, 3
  %nssl   = mul i64 %nsep, %seplen
  %rl0    = add i64 %len, %nssl
  %rl1    = add i64 %rl0, %negext
  %reslen = add i64 %rl1, 1
  %res    = call i8* @malloc(i64 %reslen)
  %pos0   = select i1 %neg, i64 1, i64 0
  br i1 %neg, label %wrneg, label %cpyinit
wrneg:
  store i8 45, i8* %res, align 1
  br label %cpyinit
cpyinit:
  br label %cpyloop
cpyloop:
  %ci  = phi i64 [ 0, %cpyinit ], [ %ciinc, %cpyafter ]
  %pos = phi i64 [ %pos0, %cpyinit ], [ %posnew, %cpyafter ]
  %cdone = icmp eq i64 %ci, %len
  br i1 %cdone, label %cpydone, label %cpy_body
cpy_body:
  %dcp = getelementptr i8, i8* %digitsp, i64 %ci
  %dc  = load i8, i8* %dcp, align 1
  %rcp = getelementptr i8, i8* %res, i64 %pos
  store i8 %dc, i8* %rcp, align 1
  %pos1  = add i64 %pos, 1
  %rem0  = sub i64 %len, %ci
  %rem   = sub i64 %rem0, 1
  %rmgt0 = icmp sgt i64 %rem, 0
  %rm3   = urem i64 %rem, 3
  %rm3z  = icmp eq i64 %rm3, 0
  %dosep = and i1 %rmgt0, %rm3z
  br i1 %dosep, label %ins_sep, label %no_sep
ins_sep:
  %sepdst = getelementptr i8, i8* %res, i64 %pos1
  call i8* @strcpy(i8* %sepdst, i8* %sep)
  %pos2 = add i64 %pos1, %seplen
  br label %cpyafter
no_sep:
  br label %cpyafter
cpyafter:
  %posnew = phi i64 [ %pos2, %ins_sep ], [ %pos1, %no_sep ]
  %ciinc  = add i64 %ci, 1
  br label %cpyloop
cpydone:
  %nullp = getelementptr i8, i8* %res, i64 %pos
  store i8 0, i8* %nullp, align 1
  ret i8* %res
}
""".trimIndent())
    }
}
