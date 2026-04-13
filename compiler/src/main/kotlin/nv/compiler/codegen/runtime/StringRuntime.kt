package nv.compiler.codegen.runtime

/** std.string runtime: slice, search, case conversion, trim, repeat, replace, parse. */
internal object StringRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.string ───────────────────────────────────────────────────────────────

; nv_str_slice(s, from, to) → i8*  (clamps indices to [0, len])
define i8* @nv_str_slice(i8* %s, i64 %from, i64 %to) {
entry:
  %len = call i64 @strlen(i8* %s)
  %ci = alloca i64, align 8
  %f1 = icmp slt i64 %from, 0
  %from_c = select i1 %f1, i64 0, i64 %from
  %f2 = icmp sgt i64 %from_c, %len
  %from_f = select i1 %f2, i64 %len, i64 %from_c
  %t1 = icmp slt i64 %to, %from_f
  %to_c = select i1 %t1, i64 %from_f, i64 %to
  %t2 = icmp sgt i64 %to_c, %len
  %to_f = select i1 %t2, i64 %len, i64 %to_c
  %sl = sub i64 %to_f, %from_f
  %bl = add i64 %sl, 1
  %buf = call i8* @malloc(i64 %bl)
  store i64 0, i64* %ci, align 8
  br label %sls.lp
sls.lp:
  %ci0 = load i64, i64* %ci, align 8
  %sls.done = icmp sge i64 %ci0, %sl
  br i1 %sls.done, label %sls.end, label %sls.body
sls.body:
  %sof = add i64 %from_f, %ci0
  %sp = getelementptr i8, i8* %s, i64 %sof
  %ch = load i8, i8* %sp, align 1
  %dp = getelementptr i8, i8* %buf, i64 %ci0
  store i8 %ch, i8* %dp, align 1
  %ci1 = add i64 %ci0, 1
  store i64 %ci1, i64* %ci, align 8
  br label %sls.lp
sls.end:
  %tp = getelementptr i8, i8* %buf, i64 %sl
  store i8 0, i8* %tp, align 1
  ret i8* %buf
}

; nv_str_index_of(s, needle) → i64  (-1 if not found)
define i64 @nv_str_index_of(i8* %s, i8* %needle) {
entry:
  %found = call i8* @strstr(i8* %s, i8* %needle)
  %is_null = icmp eq i8* %found, null
  br i1 %is_null, label %sio.null, label %sio.found
sio.null:
  ret i64 -1
sio.found:
  %si = ptrtoint i8* %s to i64
  %fi = ptrtoint i8* %found to i64
  %off = sub i64 %fi, %si
  ret i64 %off
}

; nv_str_contains(s, needle) → i1
define i1 @nv_str_contains(i8* %s, i8* %needle) {
entry:
  %found = call i8* @strstr(i8* %s, i8* %needle)
  %r = icmp ne i8* %found, null
  ret i1 %r
}

; nv_str_starts_with(s, prefix) → i1
define i1 @nv_str_starts_with(i8* %s, i8* %prefix) {
entry:
  %plen = call i64 @strlen(i8* %prefix)
  %ci = alloca i64, align 8
  store i64 0, i64* %ci, align 8
  br label %sw.lp
sw.lp:
  %ci0 = load i64, i64* %ci, align 8
  %done = icmp sge i64 %ci0, %plen
  br i1 %done, label %sw.yes, label %sw.chk
sw.chk:
  %sp = getelementptr i8, i8* %s, i64 %ci0
  %cs = load i8, i8* %sp, align 1
  %pp = getelementptr i8, i8* %prefix, i64 %ci0
  %cp = load i8, i8* %pp, align 1
  %eq = icmp eq i8 %cs, %cp
  br i1 %eq, label %sw.next, label %sw.no
sw.next:
  %ci1 = add i64 %ci0, 1
  store i64 %ci1, i64* %ci, align 8
  br label %sw.lp
sw.yes:
  ret i1 1
sw.no:
  ret i1 0
}

; nv_str_ends_with(s, suffix) → i1
define i1 @nv_str_ends_with(i8* %s, i8* %suffix) {
entry:
  %slen = call i64 @strlen(i8* %s)
  %suflen = call i64 @strlen(i8* %suffix)
  %short = icmp slt i64 %slen, %suflen
  br i1 %short, label %ew.no, label %ew.chk
ew.chk:
  %off = sub i64 %slen, %suflen
  %suf_start = getelementptr i8, i8* %s, i64 %off
  %r = call i32 @strcmp(i8* %suf_start, i8* %suffix)
  %eq = icmp eq i32 %r, 0
  ret i1 %eq
ew.no:
  ret i1 0
}

; nv_str_to_upper(s) → i8*  (heap-allocated)
define i8* @nv_str_to_upper(i8* %s) {
entry:
  %len = call i64 @strlen(i8* %s)
  %bl = add i64 %len, 1
  %buf = call i8* @malloc(i64 %bl)
  %ci = alloca i64, align 8
  store i64 0, i64* %ci, align 8
  br label %stu.lp
stu.lp:
  %ci0 = load i64, i64* %ci, align 8
  %done = icmp sge i64 %ci0, %len
  br i1 %done, label %stu.end, label %stu.body
stu.body:
  %sp = getelementptr i8, i8* %s, i64 %ci0
  %ch = load i8, i8* %sp, align 1
  %ch32 = sext i8 %ch to i32
  %up = call i32 @toupper(i32 %ch32)
  %up8 = trunc i32 %up to i8
  %dp = getelementptr i8, i8* %buf, i64 %ci0
  store i8 %up8, i8* %dp, align 1
  %ci1 = add i64 %ci0, 1
  store i64 %ci1, i64* %ci, align 8
  br label %stu.lp
stu.end:
  %tp = getelementptr i8, i8* %buf, i64 %len
  store i8 0, i8* %tp, align 1
  ret i8* %buf
}

; nv_str_to_lower(s) → i8*  (heap-allocated)
define i8* @nv_str_to_lower(i8* %s) {
entry:
  %len = call i64 @strlen(i8* %s)
  %bl = add i64 %len, 1
  %buf = call i8* @malloc(i64 %bl)
  %ci = alloca i64, align 8
  store i64 0, i64* %ci, align 8
  br label %stl.lp
stl.lp:
  %ci0 = load i64, i64* %ci, align 8
  %done = icmp sge i64 %ci0, %len
  br i1 %done, label %stl.end, label %stl.body
stl.body:
  %sp = getelementptr i8, i8* %s, i64 %ci0
  %ch = load i8, i8* %sp, align 1
  %ch32 = sext i8 %ch to i32
  %lo = call i32 @tolower(i32 %ch32)
  %lo8 = trunc i32 %lo to i8
  %dp = getelementptr i8, i8* %buf, i64 %ci0
  store i8 %lo8, i8* %dp, align 1
  %ci1 = add i64 %ci0, 1
  store i64 %ci1, i64* %ci, align 8
  br label %stl.lp
stl.end:
  %tp = getelementptr i8, i8* %buf, i64 %len
  store i8 0, i8* %tp, align 1
  ret i8* %buf
}

; nv_str_trim(s) → i8*  (strips leading and trailing whitespace)
define i8* @nv_str_trim(i8* %s) {
entry:
  %len = call i64 @strlen(i8* %s)
  %a_start = alloca i64, align 8
  %a_end   = alloca i64, align 8
  %a_ci    = alloca i64, align 8
  store i64 0, i64* %a_start, align 8
  store i64 %len, i64* %a_end, align 8
  br label %stm.fwd
stm.fwd:
  %si = load i64, i64* %a_start, align 8
  %si_end = icmp sge i64 %si, %len
  br i1 %si_end, label %stm.bwd, label %stm.fwd.chk
stm.fwd.chk:
  %fp = getelementptr i8, i8* %s, i64 %si
  %fch = load i8, i8* %fp, align 1
  %fch32 = sext i8 %fch to i32
  %fsp = call i32 @isspace(i32 %fch32)
  %fis = icmp ne i32 %fsp, 0
  br i1 %fis, label %stm.fwd.next, label %stm.bwd
stm.fwd.next:
  %si1 = add i64 %si, 1
  store i64 %si1, i64* %a_start, align 8
  br label %stm.fwd
stm.bwd:
  %ei = load i64, i64* %a_end, align 8
  %s_start = load i64, i64* %a_start, align 8
  %ei_at_s = icmp sle i64 %ei, %s_start
  br i1 %ei_at_s, label %stm.copy, label %stm.bwd.chk
stm.bwd.chk:
  %ei1 = sub i64 %ei, 1
  %bp = getelementptr i8, i8* %s, i64 %ei1
  %bch = load i8, i8* %bp, align 1
  %bch32 = sext i8 %bch to i32
  %bsp = call i32 @isspace(i32 %bch32)
  %bis = icmp ne i32 %bsp, 0
  br i1 %bis, label %stm.bwd.next, label %stm.copy
stm.bwd.next:
  store i64 %ei1, i64* %a_end, align 8
  br label %stm.bwd
stm.copy:
  %start_v = load i64, i64* %a_start, align 8
  %end_v   = load i64, i64* %a_end, align 8
  %sl = sub i64 %end_v, %start_v
  %bl = add i64 %sl, 1
  %buf = call i8* @malloc(i64 %bl)
  %src = getelementptr i8, i8* %s, i64 %start_v
  store i64 0, i64* %a_ci, align 8
  br label %stm.cpy
stm.cpy:
  %ci0 = load i64, i64* %a_ci, align 8
  %cpy_done = icmp sge i64 %ci0, %sl
  br i1 %cpy_done, label %stm.cpy.end, label %stm.cpy.body
stm.cpy.body:
  %csrc = getelementptr i8, i8* %src, i64 %ci0
  %cch = load i8, i8* %csrc, align 1
  %cdst = getelementptr i8, i8* %buf, i64 %ci0
  store i8 %cch, i8* %cdst, align 1
  %ci1 = add i64 %ci0, 1
  store i64 %ci1, i64* %a_ci, align 8
  br label %stm.cpy
stm.cpy.end:
  %tp = getelementptr i8, i8* %buf, i64 %sl
  store i8 0, i8* %tp, align 1
  ret i8* %buf
}

; nv_str_repeat(s, n) → i8*
define i8* @nv_str_repeat(i8* %s, i64 %n) {
entry:
  %slen = call i64 @strlen(i8* %s)
  %total = mul i64 %slen, %n
  %bl = add i64 %total, 1
  %buf = call i8* @malloc(i64 %bl)
  %ni = alloca i64, align 8
  %ci = alloca i64, align 8
  store i64 0, i64* %ni, align 8
  br label %srp.outer
srp.outer:
  %ni0 = load i64, i64* %ni, align 8
  %outer_done = icmp sge i64 %ni0, %n
  br i1 %outer_done, label %srp.end, label %srp.inner_init
srp.inner_init:
  %base = mul i64 %ni0, %slen
  store i64 0, i64* %ci, align 8
  br label %srp.inner
srp.inner:
  %ci0 = load i64, i64* %ci, align 8
  %inner_done = icmp sge i64 %ci0, %slen
  br i1 %inner_done, label %srp.next, label %srp.copy
srp.copy:
  %sp = getelementptr i8, i8* %s, i64 %ci0
  %sch = load i8, i8* %sp, align 1
  %doff = add i64 %base, %ci0
  %dp = getelementptr i8, i8* %buf, i64 %doff
  store i8 %sch, i8* %dp, align 1
  %ci1 = add i64 %ci0, 1
  store i64 %ci1, i64* %ci, align 8
  br label %srp.inner
srp.next:
  %ni1 = add i64 %ni0, 1
  store i64 %ni1, i64* %ni, align 8
  br label %srp.outer
srp.end:
  %tp = getelementptr i8, i8* %buf, i64 %total
  store i8 0, i8* %tp, align 1
  ret i8* %buf
}

; nv_str_replace(s, from, to) → i8*  (returns s copy when from is empty)
define i8* @nv_str_replace(i8* %s, i8* %from, i8* %to) {
entry:
  %slen = call i64 @strlen(i8* %s)
  %flen = call i64 @strlen(i8* %from)
  %tlen = call i64 @strlen(i8* %to)
  %a_count = alloca i64, align 8
  %a_scan  = alloca i64, align 8
  %a_rpos  = alloca i64, align 8
  %a_wpos  = alloca i64, align 8
  %a_bi    = alloca i64, align 8
  %a_ti    = alloca i64, align 8
  %a_tii   = alloca i64, align 8
  %a_buf   = alloca i8*, align 8
  %a_moff  = alloca i64, align 8
  %fem = icmp eq i64 %flen, 0
  br i1 %fem, label %rpl.copy_s, label %rpl.cnt_init
rpl.cnt_init:
  store i64 0, i64* %a_count, align 8
  store i64 0, i64* %a_scan, align 8
  br label %rpl.cnt
rpl.cnt:
  %sc = load i64, i64* %a_scan, align 8
  %scp = getelementptr i8, i8* %s, i64 %sc
  %fnd = call i8* @strstr(i8* %scp, i8* %from)
  %no_fnd = icmp eq i8* %fnd, null
  br i1 %no_fnd, label %rpl.alloc, label %rpl.cnt_inc
rpl.cnt_inc:
  %cnt = load i64, i64* %a_count, align 8
  %cnt1 = add i64 %cnt, 1
  store i64 %cnt1, i64* %a_count, align 8
  %fnd_i = ptrtoint i8* %fnd to i64
  %s_i = ptrtoint i8* %s to i64
  %fnd_off = sub i64 %fnd_i, %s_i
  %nxt = add i64 %fnd_off, %flen
  store i64 %nxt, i64* %a_scan, align 8
  br label %rpl.cnt
rpl.alloc:
  %cnt_f = load i64, i64* %a_count, align 8
  %rmv = mul i64 %cnt_f, %flen
  %add = mul i64 %cnt_f, %tlen
  %nlen = sub i64 %slen, %rmv
  %nlen2 = add i64 %nlen, %add
  %bl = add i64 %nlen2, 1
  %buf = call i8* @malloc(i64 %bl)
  store i8* %buf, i8** %a_buf, align 8
  store i64 0, i64* %a_rpos, align 8
  store i64 0, i64* %a_wpos, align 8
  br label %rpl.main
rpl.main:
  %rp = load i64, i64* %a_rpos, align 8
  %rpp = getelementptr i8, i8* %s, i64 %rp
  %match = call i8* @strstr(i8* %rpp, i8* %from)
  %no_match = icmp eq i8* %match, null
  br i1 %no_match, label %rpl.tail, label %rpl.before
rpl.before:
  %mi = ptrtoint i8* %match to i64
  %si2 = ptrtoint i8* %s to i64
  %moff = sub i64 %mi, %si2
  store i64 %moff, i64* %a_moff, align 8
  %rp2 = load i64, i64* %a_rpos, align 8
  %bef_len = sub i64 %moff, %rp2
  store i64 0, i64* %a_bi, align 8
  br label %rpl.bef.lp
rpl.bef.lp:
  %bi0 = load i64, i64* %a_bi, align 8
  %bef_done = icmp sge i64 %bi0, %bef_len
  br i1 %bef_done, label %rpl.to_init, label %rpl.bef.body
rpl.bef.body:
  %rp3 = load i64, i64* %a_rpos, align 8
  %bsrc_off = add i64 %rp3, %bi0
  %bsp = getelementptr i8, i8* %s, i64 %bsrc_off
  %bch = load i8, i8* %bsp, align 1
  %bwp = load i64, i64* %a_wpos, align 8
  %bbuf = load i8*, i8** %a_buf, align 8
  %bwdst = getelementptr i8, i8* %bbuf, i64 %bwp
  store i8 %bch, i8* %bwdst, align 1
  %bwp1 = add i64 %bwp, 1
  store i64 %bwp1, i64* %a_wpos, align 8
  %bi1 = add i64 %bi0, 1
  store i64 %bi1, i64* %a_bi, align 8
  br label %rpl.bef.lp
rpl.to_init:
  store i64 0, i64* %a_ti, align 8
  br label %rpl.to.lp
rpl.to.lp:
  %ti0 = load i64, i64* %a_ti, align 8
  %to_done = icmp sge i64 %ti0, %tlen
  br i1 %to_done, label %rpl.advance, label %rpl.to.body
rpl.to.body:
  %tsp = getelementptr i8, i8* %to, i64 %ti0
  %tch = load i8, i8* %tsp, align 1
  %twp = load i64, i64* %a_wpos, align 8
  %tbuf = load i8*, i8** %a_buf, align 8
  %twdst = getelementptr i8, i8* %tbuf, i64 %twp
  store i8 %tch, i8* %twdst, align 1
  %twp1 = add i64 %twp, 1
  store i64 %twp1, i64* %a_wpos, align 8
  %ti1 = add i64 %ti0, 1
  store i64 %ti1, i64* %a_ti, align 8
  br label %rpl.to.lp
rpl.advance:
  %moff2 = load i64, i64* %a_moff, align 8
  %new_rp = add i64 %moff2, %flen
  store i64 %new_rp, i64* %a_rpos, align 8
  br label %rpl.main
rpl.tail:
  %rp_tail = load i64, i64* %a_rpos, align 8
  %tail_len = sub i64 %slen, %rp_tail
  store i64 0, i64* %a_tii, align 8
  br label %rpl.tl.lp
rpl.tl.lp:
  %tii0 = load i64, i64* %a_tii, align 8
  %tl_done = icmp sge i64 %tii0, %tail_len
  br i1 %tl_done, label %rpl.term, label %rpl.tl.body
rpl.tl.body:
  %tlsrc = add i64 %rp_tail, %tii0
  %tlsp = getelementptr i8, i8* %s, i64 %tlsrc
  %tlch = load i8, i8* %tlsp, align 1
  %tlwp = load i64, i64* %a_wpos, align 8
  %tlbuf = load i8*, i8** %a_buf, align 8
  %tlwdst = getelementptr i8, i8* %tlbuf, i64 %tlwp
  store i8 %tlch, i8* %tlwdst, align 1
  %tlwp1 = add i64 %tlwp, 1
  store i64 %tlwp1, i64* %a_wpos, align 8
  %tii1 = add i64 %tii0, 1
  store i64 %tii1, i64* %a_tii, align 8
  br label %rpl.tl.lp
rpl.term:
  %wfinal = load i64, i64* %a_wpos, align 8
  %finbuf = load i8*, i8** %a_buf, align 8
  %fintp = getelementptr i8, i8* %finbuf, i64 %wfinal
  store i8 0, i8* %fintp, align 1
  ret i8* %finbuf
rpl.copy_s:
  %bl_cs = add i64 %slen, 1
  %buf_cs = call i8* @malloc(i64 %bl_cs)
  store i64 0, i64* %a_bi, align 8
  br label %rpl.cs.lp
rpl.cs.lp:
  %csbi = load i64, i64* %a_bi, align 8
  %cs_done = icmp sge i64 %csbi, %slen
  br i1 %cs_done, label %rpl.cs.end, label %rpl.cs.body
rpl.cs.body:
  %cssp = getelementptr i8, i8* %s, i64 %csbi
  %csch = load i8, i8* %cssp, align 1
  %csdp = getelementptr i8, i8* %buf_cs, i64 %csbi
  store i8 %csch, i8* %csdp, align 1
  %csbi1 = add i64 %csbi, 1
  store i64 %csbi1, i64* %a_bi, align 8
  br label %rpl.cs.lp
rpl.cs.end:
  %cs_tp = getelementptr i8, i8* %buf_cs, i64 %slen
  store i8 0, i8* %cs_tp, align 1
  ret i8* %buf_cs
}

; nv_str_parse_int(s) → i64  (0 on parse error)
define i64 @nv_str_parse_int(i8* %s) {
entry:
  %r = call i64 @strtoll(i8* %s, i8** null, i32 10)
  ret i64 %r
}

; nv_str_parse_float(s) → double  (0.0 on parse error)
define double @nv_str_parse_float(i8* %s) {
entry:
  %r = call double @strtod(i8* %s, i8** null)
  ret double %r
}
""".trimIndent())
    }
}
