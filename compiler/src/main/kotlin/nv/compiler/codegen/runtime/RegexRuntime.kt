package nv.compiler.codegen.runtime

/**
 * std.regex runtime: POSIX extended regular expressions via regcomp/regexec.
 *
 * Regex handle layout (heap-allocated, 1048 bytes):
 *   [offset  0, 8 B]  i64  num_capture_groups
 *   [offset  8, 8 B]  i64  num_named_groups
 *   [offset 16, 8 B]  i8*  names_buf (packed null-terminated names, may be empty "")
 *   [offset 24, 1024 B] regex_t (1024 B >> macOS/Linux platform maximum)
 *
 * Match handle layout (heap-allocated, 40 + num_groups * 8 bytes):
 *   [offset  0, 8 B]  i8*  matched text (owned heap copy)
 *   [offset  8, 8 B]  i64  start byte offset in the original source string
 *   [offset 16, 8 B]  i64  end byte offset (exclusive)
 *   [offset 24, 8 B]  i64  num_capture_groups
 *   [offset 32, 8 B]  i8*  names_buf (borrowed from Regex; never freed through Match)
 *   [offset 40 + i*8] i8*  capture group (i+1) text; null if group did not participate
 *
 * Named groups (?P<name>…) are rewritten to plain (…) before regcomp; names are
 * stored in a packed null-terminated buffer ordered by group number (1-based).
 *
 * Replacement strings in replace/replaceAll: $0 = whole match, $1..$N = groups.
 * Multi-digit indices ($10, $11 …) are supported.
 *
 * Platform note: regoff_t is ssize_t (i64) on macOS and int (i32) on Linux, so
 * regmatch_t = {i64 rm_so, i64 rm_eo} = 16 bytes on macOS. This runtime uses
 * the macOS layout (i64 fields, 16-byte stride).
 * REG_EXTENDED = 1 and REG_NOMATCH = 1 on both platforms.
 */
internal object RegexRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.regex ─────────────────────────────────────────────────────────────────

; ── nv_regex_substr(s, from, len) → i8* ──────────────────────────────────────
; Allocate a null-terminated copy of s[from .. from+len).
define i8* @nv_regex_substr(i8* %s, i64 %from, i64 %len) {
rxsub.entry:
  %sz = add i64 %len, 1
  %buf = call i8* @malloc(i64 %sz)
  %src = getelementptr i8, i8* %s, i64 %from
  call i8* @memcpy(i8* %buf, i8* %src, i64 %len)
  %nulp = getelementptr i8, i8* %buf, i64 %len
  store i8 0, i8* %nulp, align 1
  ret i8* %buf
}

; ── nv_regex_preproc ──────────────────────────────────────────────────────────
; Transform (?P<name>...) → (...), (?:...) → (...) (POSIX has no non-capturing groups),
; count capture groups. Returns heap-allocated transformed pattern.
; *names_out  ← packed null-terminated names buffer (malloc'd)
; *total_out  ← total capture groups
; *named_out  ← number of named groups
define i8* @nv_regex_preproc(i8* %pat, i8** %names_out, i64* %total_out, i64* %named_out) {
rxpp.entry:
  %plen = call i64 @strlen(i8* %pat)
  %bufsz = add i64 %plen, 1
  %trans = call i8* @malloc(i64 %bufsz)
  %names = call i8* @malloc(i64 %bufsz)
  %sp.a  = alloca i8*, align 8
  %dp.a  = alloca i8*, align 8
  %np.a  = alloca i8*, align 8
  %tot.a = alloca i64,  align 8
  %nam.a = alloca i64,  align 8
  store i8* %pat,   i8** %sp.a,  align 8
  store i8* %trans, i8** %dp.a,  align 8
  store i8* %names, i8** %np.a,  align 8
  store i64 0, i64* %tot.a, align 8
  store i64 0, i64* %nam.a, align 8
  br label %rxpp.loop

rxpp.loop:
  %sp0 = load i8*, i8** %sp.a, align 8
  %dp0 = load i8*, i8** %dp.a, align 8
  %c0  = load i8, i8* %sp0, align 1
  %end = icmp eq i8 %c0, 0
  br i1 %end, label %rxpp.done, label %rxpp.d1

rxpp.d1:
  %is_bs = icmp eq i8 %c0, 92
  br i1 %is_bs, label %rxpp.esc, label %rxpp.d2

rxpp.d2:
  %is_lb = icmp eq i8 %c0, 91
  br i1 %is_lb, label %rxpp.cc, label %rxpp.d3

rxpp.d3:
  %is_lp = icmp eq i8 %c0, 40
  br i1 %is_lp, label %rxpp.lp, label %rxpp.copy

rxpp.esc:
  store i8 92, i8* %dp0, align 1
  %dp.e1 = getelementptr i8, i8* %dp0, i64 1
  %sp.e1 = getelementptr i8, i8* %sp0, i64 1
  %ce1   = load i8, i8* %sp.e1, align 1
  %eend  = icmp eq i8 %ce1, 0
  br i1 %eend, label %rxpp.esc1, label %rxpp.esc2

rxpp.esc1:
  store i8* %dp.e1, i8** %dp.a, align 8
  store i8* %sp.e1, i8** %sp.a, align 8
  br label %rxpp.loop

rxpp.esc2:
  store i8 %ce1, i8* %dp.e1, align 1
  %dp.e2 = getelementptr i8, i8* %dp.e1, i64 1
  %sp.e2 = getelementptr i8, i8* %sp.e1, i64 1
  store i8* %dp.e2, i8** %dp.a, align 8
  store i8* %sp.e2, i8** %sp.a, align 8
  br label %rxpp.loop

; copy [...] verbatim, handling internal escapes, to avoid miscounting '(' inside
rxpp.cc:
  store i8 91, i8* %dp0, align 1
  %dp.cc0 = getelementptr i8, i8* %dp0, i64 1
  %sp.cc0 = getelementptr i8, i8* %sp0, i64 1
  store i8* %dp.cc0, i8** %dp.a, align 8
  store i8* %sp.cc0, i8** %sp.a, align 8
  br label %rxpp.ccl

rxpp.ccl:
  %sp.cc = load i8*, i8** %sp.a, align 8
  %dp.cc = load i8*, i8** %dp.a, align 8
  %c.cc  = load i8, i8* %sp.cc, align 1
  %cc0   = icmp eq i8 %c.cc, 0
  br i1 %cc0, label %rxpp.loop, label %rxpp.cc1

rxpp.cc1:
  %cc.bs = icmp eq i8 %c.cc, 92
  br i1 %cc.bs, label %rxpp.ccesc, label %rxpp.cc2

rxpp.cc2:
  %cc.rb = icmp eq i8 %c.cc, 93
  br i1 %cc.rb, label %rxpp.ccend, label %rxpp.ccplain

rxpp.ccplain:
  store i8 %c.cc, i8* %dp.cc, align 1
  %dp.ccp = getelementptr i8, i8* %dp.cc, i64 1
  %sp.ccp = getelementptr i8, i8* %sp.cc, i64 1
  store i8* %dp.ccp, i8** %dp.a, align 8
  store i8* %sp.ccp, i8** %sp.a, align 8
  br label %rxpp.ccl

rxpp.ccesc:
  store i8 92, i8* %dp.cc, align 1
  %dp.ce1 = getelementptr i8, i8* %dp.cc, i64 1
  %sp.ce1 = getelementptr i8, i8* %sp.cc, i64 1
  %c.ce1  = load i8, i8* %sp.ce1, align 1
  %cce0   = icmp eq i8 %c.ce1, 0
  br i1 %cce0, label %rxpp.cce0, label %rxpp.cce1

rxpp.cce0:
  store i8* %dp.ce1, i8** %dp.a, align 8
  store i8* %sp.ce1, i8** %sp.a, align 8
  br label %rxpp.ccl

rxpp.cce1:
  store i8 %c.ce1, i8* %dp.ce1, align 1
  %dp.ce2 = getelementptr i8, i8* %dp.ce1, i64 1
  %sp.ce2 = getelementptr i8, i8* %sp.ce1, i64 1
  store i8* %dp.ce2, i8** %dp.a, align 8
  store i8* %sp.ce2, i8** %sp.a, align 8
  br label %rxpp.ccl

rxpp.ccend:
  store i8 93, i8* %dp.cc, align 1
  %dp.cce = getelementptr i8, i8* %dp.cc, i64 1
  %sp.cce = getelementptr i8, i8* %sp.cc, i64 1
  store i8* %dp.cce, i8** %dp.a, align 8
  store i8* %sp.cce, i8** %sp.a, align 8
  br label %rxpp.loop

rxpp.lp:
  %sp.lp1 = getelementptr i8, i8* %sp0, i64 1
  %c.lp1  = load i8, i8* %sp.lp1, align 1
  %lp.q   = icmp eq i8 %c.lp1, 63
  br i1 %lp.q, label %rxpp.lpq, label %rxpp.lpcap

rxpp.lpcap:
  store i8 40, i8* %dp0, align 1
  %dp.lpc = getelementptr i8, i8* %dp0, i64 1
  %sp.lpc = getelementptr i8, i8* %sp0, i64 1
  store i8* %dp.lpc, i8** %dp.a, align 8
  store i8* %sp.lpc, i8** %sp.a, align 8
  %tot.c  = load i64, i64* %tot.a, align 8
  %tot.c1 = add i64 %tot.c, 1
  store i64 %tot.c1, i64* %tot.a, align 8
  br label %rxpp.loop

rxpp.lpq:
  %sp.lp2 = getelementptr i8, i8* %sp0, i64 2
  %c.lp2  = load i8, i8* %sp.lp2, align 1
  %lp.nc  = icmp eq i8 %c.lp2, 58
  br i1 %lp.nc, label %rxpp.lpnc, label %rxpp.lpq2

rxpp.lpq2:
  %lp.P = icmp eq i8 %c.lp2, 80
  br i1 %lp.P, label %rxpp.lpP, label %rxpp.lpoth

rxpp.lpnc:
  ; Convert (?:...) → (...): POSIX has no non-capturing groups; becomes a capturing group.
  store i8 40, i8* %dp0, align 1
  %dp.nc1 = getelementptr i8, i8* %dp0, i64 1
  %sp.nc3 = getelementptr i8, i8* %sp0, i64 3
  store i8* %dp.nc1, i8** %dp.a, align 8
  store i8* %sp.nc3, i8** %sp.a, align 8
  %tot.nc  = load i64, i64* %tot.a, align 8
  %tot.nc1 = add i64 %tot.nc, 1
  store i64 %tot.nc1, i64* %tot.a, align 8
  br label %rxpp.loop

rxpp.lpP:
  %sp.lp3 = getelementptr i8, i8* %sp0, i64 3
  %c.lp3  = load i8, i8* %sp.lp3, align 1
  %lp.lt  = icmp eq i8 %c.lp3, 60
  br i1 %lp.lt, label %rxpp.named, label %rxpp.lpoth

rxpp.lpoth:
  store i8 40, i8* %dp0, align 1
  %dp.lo = getelementptr i8, i8* %dp0, i64 1
  %sp.lo = getelementptr i8, i8* %sp0, i64 1
  store i8* %dp.lo, i8** %dp.a, align 8
  store i8* %sp.lo, i8** %sp.a, align 8
  br label %rxpp.loop

rxpp.named:
  store i8 40, i8* %dp0, align 1
  %dp.nm = getelementptr i8, i8* %dp0, i64 1
  store i8* %dp.nm, i8** %dp.a, align 8
  %sp.nm4 = getelementptr i8, i8* %sp0, i64 4
  store i8* %sp.nm4, i8** %sp.a, align 8
  %tot.n  = load i64, i64* %tot.a, align 8
  %tot.n1 = add i64 %tot.n, 1
  store i64 %tot.n1, i64* %tot.a, align 8
  %nam.n  = load i64, i64* %nam.a, align 8
  %nam.n1 = add i64 %nam.n, 1
  store i64 %nam.n1, i64* %nam.a, align 8
  br label %rxpp.namel

rxpp.namel:
  %sp.nl = load i8*, i8** %sp.a, align 8
  %np.nl = load i8*, i8** %np.a, align 8
  %c.nl  = load i8, i8* %sp.nl, align 1
  %nl.gt = icmp eq i8 %c.nl, 62
  br i1 %nl.gt, label %rxpp.nameend, label %rxpp.namechk

rxpp.namechk:
  %nl.z = icmp eq i8 %c.nl, 0
  br i1 %nl.z, label %rxpp.namenul, label %rxpp.namecopy

rxpp.namecopy:
  store i8 %c.nl, i8* %np.nl, align 1
  %sp.nlc = getelementptr i8, i8* %sp.nl, i64 1
  %np.nlc = getelementptr i8, i8* %np.nl, i64 1
  store i8* %sp.nlc, i8** %sp.a, align 8
  store i8* %np.nlc, i8** %np.a, align 8
  br label %rxpp.namel

rxpp.nameend:
  %sp.ne = getelementptr i8, i8* %sp.nl, i64 1
  store i8* %sp.ne, i8** %sp.a, align 8
  br label %rxpp.namenul

rxpp.namenul:
  %np.nt = load i8*, i8** %np.a, align 8
  store i8 0, i8* %np.nt, align 1
  %np.nt1 = getelementptr i8, i8* %np.nt, i64 1
  store i8* %np.nt1, i8** %np.a, align 8
  br label %rxpp.loop

rxpp.copy:
  store i8 %c0, i8* %dp0, align 1
  %dp.cp = getelementptr i8, i8* %dp0, i64 1
  %sp.cp = getelementptr i8, i8* %sp0, i64 1
  store i8* %dp.cp, i8** %dp.a, align 8
  store i8* %sp.cp, i8** %sp.a, align 8
  br label %rxpp.loop

rxpp.done:
  %dp.fin = load i8*, i8** %dp.a, align 8
  store i8 0, i8* %dp.fin, align 1
  %tot.fin = load i64, i64* %tot.a, align 8
  %nam.fin = load i64, i64* %nam.a, align 8
  store i8* %names, i8** %names_out, align 8
  store i64 %tot.fin, i64* %total_out, align 8
  store i64 %nam.fin, i64* %named_out, align 8
  ret i8* %trans
}

; ── nv_regex_compile(pattern) → i8*  (Result<i8*>) ───────────────────────────
; Returns nv_Ok(regex_handle) or nv_Err(message).
define i8* @nv_regex_compile(i8* %pattern) {
rxc.entry:
  %rxc.names = alloca i8*, align 8
  %rxc.total = alloca i64,  align 8
  %rxc.named = alloca i64,  align 8
  %rxc.trans = call i8* @nv_regex_preproc(i8* %pattern, i8** %rxc.names, i64* %rxc.total, i64* %rxc.named)
  %rxc.ng = load i64, i64* %rxc.total, align 8
  %rxc.nn = load i64, i64* %rxc.named, align 8
  %rxc.nb = load i8*, i8** %rxc.names, align 8
  %rxc.obj = call i8* @malloc(i64 1048)
  %rxc.ng_p  = bitcast i8* %rxc.obj to i64*
  store i64 %rxc.ng, i64* %rxc.ng_p, align 8
  %rxc.nn_p  = getelementptr i8, i8* %rxc.obj, i64 8
  %rxc.nn_64 = bitcast i8* %rxc.nn_p to i64*
  store i64 %rxc.nn, i64* %rxc.nn_64, align 8
  %rxc.nb_p  = getelementptr i8, i8* %rxc.obj, i64 16
  %rxc.nb_pp = bitcast i8* %rxc.nb_p to i8**
  store i8* %rxc.nb, i8** %rxc.nb_pp, align 8
  %rxc.rt = getelementptr i8, i8* %rxc.obj, i64 24
  %rxc.rc = call i32 @regcomp(i8* %rxc.rt, i8* %rxc.trans, i32 1)
  call void @free(i8* %rxc.trans)
  %rxc.ok = icmp eq i32 %rxc.rc, 0
  br i1 %rxc.ok, label %rxc.success, label %rxc.error

rxc.success:
  %rxc.res = call i8* @nv_Ok(i8* %rxc.obj)
  ret i8* %rxc.res

rxc.error:
  %rxc.errbuf = call i8* @malloc(i64 256)
  call i64 @regerror(i32 %rxc.rc, i8* %rxc.rt, i8* %rxc.errbuf, i64 256)
  call void @free(i8* %rxc.nb)
  call void @free(i8* %rxc.obj)
  %rxc.err = call i8* @nv_Err(i8* %rxc.errbuf)
  ret i8* %rxc.err
}

; ── nv_regex_new(pattern) → i8*  (raw handle, panics on error) ───────────────
; Convenience wrapper: compiles pattern and returns raw handle.
; Panics if the pattern is invalid (invalid regex is a programmer error).
define i8* @nv_regex_new(i8* %pattern) {
rxnew.entry:
  %rxnew.names = alloca i8*, align 8
  %rxnew.total = alloca i64,  align 8
  %rxnew.named = alloca i64,  align 8
  %rxnew.trans = call i8* @nv_regex_preproc(i8* %pattern, i8** %rxnew.names, i64* %rxnew.total, i64* %rxnew.named)
  %rxnew.ng = load i64, i64* %rxnew.total, align 8
  %rxnew.nn = load i64, i64* %rxnew.named, align 8
  %rxnew.nb = load i8*, i8** %rxnew.names, align 8
  %rxnew.obj = call i8* @malloc(i64 1048)
  %rxnew.ng_p  = bitcast i8* %rxnew.obj to i64*
  store i64 %rxnew.ng, i64* %rxnew.ng_p, align 8
  %rxnew.nn_p  = getelementptr i8, i8* %rxnew.obj, i64 8
  %rxnew.nn_64 = bitcast i8* %rxnew.nn_p to i64*
  store i64 %rxnew.nn, i64* %rxnew.nn_64, align 8
  %rxnew.nb_p  = getelementptr i8, i8* %rxnew.obj, i64 16
  %rxnew.nb_pp = bitcast i8* %rxnew.nb_p to i8**
  store i8* %rxnew.nb, i8** %rxnew.nb_pp, align 8
  %rxnew.rt = getelementptr i8, i8* %rxnew.obj, i64 24
  %rxnew.rc = call i32 @regcomp(i8* %rxnew.rt, i8* %rxnew.trans, i32 1)
  call void @free(i8* %rxnew.trans)
  %rxnew.ok = icmp eq i32 %rxnew.rc, 0
  br i1 %rxnew.ok, label %rxnew.success, label %rxnew.error

rxnew.success:
  ret i8* %rxnew.obj

rxnew.error:
  %rxnew.errbuf = call i8* @malloc(i64 256)
  call i64 @regerror(i32 %rxnew.rc, i8* %rxnew.rt, i8* %rxnew.errbuf, i64 256)
  call void @free(i8* %rxnew.nb)
  call void @free(i8* %rxnew.obj)
  call void @nv_panic(i8* %rxnew.errbuf)
  ret i8* null
}

; ── nv_regex_free(regex) → void ──────────────────────────────────────────────
define void @nv_regex_free(i8* %regex) {
rxfree.entry:
  %rxfree.null = icmp eq i8* %regex, null
  br i1 %rxfree.null, label %rxfree.done, label %rxfree.work

rxfree.work:
  %rxfree.nb_p  = getelementptr i8, i8* %regex, i64 16
  %rxfree.nb_pp = bitcast i8* %rxfree.nb_p to i8**
  %rxfree.nb    = load i8*, i8** %rxfree.nb_pp, align 8
  %rxfree.nb_nul = icmp eq i8* %rxfree.nb, null
  br i1 %rxfree.nb_nul, label %rxfree.skip_nb, label %rxfree.free_nb

rxfree.free_nb:
  call void @free(i8* %rxfree.nb)
  br label %rxfree.skip_nb

rxfree.skip_nb:
  %rxfree.rt = getelementptr i8, i8* %regex, i64 24
  call void @regfree(i8* %rxfree.rt)
  call void @free(i8* %regex)
  br label %rxfree.done

rxfree.done:
  ret void
}

; ── nv_match_free(match) → void ──────────────────────────────────────────────
; Free a match object and all its owned string copies.
define void @nv_match_free(i8* %match) {
rxmf.entry:
  %rxmf.null = icmp eq i8* %match, null
  br i1 %rxmf.null, label %rxmf.done, label %rxmf.work

rxmf.work:
  %rxmf.vp = bitcast i8* %match to i8**
  %rxmf.v  = load i8*, i8** %rxmf.vp, align 8
  call void @free(i8* %rxmf.v)
  %rxmf.ngoff = getelementptr i8, i8* %match, i64 24
  %rxmf.ng_p  = bitcast i8* %rxmf.ngoff to i64*
  %rxmf.ng    = load i64, i64* %rxmf.ng_p, align 8
  %rxmf.ng0   = icmp eq i64 %rxmf.ng, 0
  br i1 %rxmf.ng0, label %rxmf.freematch, label %rxmf.gloop

rxmf.gloop:
  %rxmf.gi = phi i64 [ 0, %rxmf.work ], [ %rxmf.gi1, %rxmf.gnext ]
  %rxmf.gd = icmp sge i64 %rxmf.gi, %rxmf.ng
  br i1 %rxmf.gd, label %rxmf.freematch, label %rxmf.gload

rxmf.gload:
  %rxmf.gr  = mul i64 %rxmf.gi, 8
  %rxmf.go  = add i64 40, %rxmf.gr
  %rxmf.gp  = getelementptr i8, i8* %match, i64 %rxmf.go
  %rxmf.gpp = bitcast i8* %rxmf.gp to i8**
  %rxmf.gv  = load i8*, i8** %rxmf.gpp, align 8
  %rxmf.gvn = icmp eq i8* %rxmf.gv, null
  br i1 %rxmf.gvn, label %rxmf.gnext, label %rxmf.gfree

rxmf.gfree:
  call void @free(i8* %rxmf.gv)
  br label %rxmf.gnext

rxmf.gnext:
  %rxmf.gi1 = add i64 %rxmf.gi, 1
  br label %rxmf.gloop

rxmf.freematch:
  call void @free(i8* %match)
  br label %rxmf.done

rxmf.done:
  ret void
}

; ── nv_regex_build_match(regex, src, pmatch, base_off) → i8* ─────────────────
; Build a Match handle from POSIX regmatch_t results.
; src   – string passed to regexec (starts at base_off within the original string)
; pmatch – regmatch_t[] array (16 bytes per entry on macOS: two i64s rm_so, rm_eo)
; base_off – byte offset of src within the original source string
define i8* @nv_regex_build_match(i8* %regex, i8* %src, i8* %pmatch, i64 %base_off) {
rxbm.entry:
  %rxbm.ng_p = bitcast i8* %regex to i64*
  %rxbm.ng   = load i64, i64* %rxbm.ng_p, align 8
  %rxbm.nb_off = getelementptr i8, i8* %regex, i64 16
  %rxbm.nb_pp  = bitcast i8* %rxbm.nb_off to i8**
  %rxbm.nb     = load i8*, i8** %rxbm.nb_pp, align 8
  %rxbm.so0_p  = bitcast i8* %pmatch to i64*
  %rxbm.so0_64 = load i64, i64* %rxbm.so0_p, align 8
  %rxbm.eo0_p  = getelementptr i8, i8* %pmatch, i64 8
  %rxbm.eo0_64p = bitcast i8* %rxbm.eo0_p to i64*
  %rxbm.eo0_64 = load i64, i64* %rxbm.eo0_64p, align 8
  %rxbm.mlen   = sub i64 %rxbm.eo0_64, %rxbm.so0_64
  %rxbm.value  = call i8* @nv_regex_substr(i8* %src, i64 %rxbm.so0_64, i64 %rxbm.mlen)
  %rxbm.abs_s  = add i64 %base_off, %rxbm.so0_64
  %rxbm.abs_e  = add i64 %base_off, %rxbm.eo0_64
  %rxbm.gb     = mul i64 %rxbm.ng, 8
  %rxbm.msz    = add i64 40, %rxbm.gb
  %rxbm.match  = call i8* @malloc(i64 %rxbm.msz)
  %rxbm.v_pp   = bitcast i8* %rxbm.match to i8**
  store i8* %rxbm.value, i8** %rxbm.v_pp, align 8
  %rxbm.s_off  = getelementptr i8, i8* %rxbm.match, i64 8
  %rxbm.s_p64  = bitcast i8* %rxbm.s_off to i64*
  store i64 %rxbm.abs_s, i64* %rxbm.s_p64, align 8
  %rxbm.e_off  = getelementptr i8, i8* %rxbm.match, i64 16
  %rxbm.e_p64  = bitcast i8* %rxbm.e_off to i64*
  store i64 %rxbm.abs_e, i64* %rxbm.e_p64, align 8
  %rxbm.ng_off = getelementptr i8, i8* %rxbm.match, i64 24
  %rxbm.ng_s64 = bitcast i8* %rxbm.ng_off to i64*
  store i64 %rxbm.ng, i64* %rxbm.ng_s64, align 8
  %rxbm.nb_soff = getelementptr i8, i8* %rxbm.match, i64 32
  %rxbm.nb_spp  = bitcast i8* %rxbm.nb_soff to i8**
  store i8* %rxbm.nb, i8** %rxbm.nb_spp, align 8
  %rxbm.ng0 = icmp eq i64 %rxbm.ng, 0
  br i1 %rxbm.ng0, label %rxbm.done, label %rxbm.gloop

rxbm.gloop:
  %rxbm.gi = phi i64 [ 1, %rxbm.entry ], [ %rxbm.gi1, %rxbm.gstore ]
  %rxbm.gd = icmp sgt i64 %rxbm.gi, %rxbm.ng
  br i1 %rxbm.gd, label %rxbm.done, label %rxbm.gload

rxbm.gload:
  %rxbm.pm_off = mul i64 %rxbm.gi, 16
  %rxbm.pm_p   = getelementptr i8, i8* %pmatch, i64 %rxbm.pm_off
  %rxbm.pm_s64 = bitcast i8* %rxbm.pm_p to i64*
  %rxbm.so64   = load i64, i64* %rxbm.pm_s64, align 8
  %rxbm.pm_e_r = getelementptr i8, i8* %rxbm.pm_p, i64 8
  %rxbm.pm_e64 = bitcast i8* %rxbm.pm_e_r to i64*
  %rxbm.eo64   = load i64, i64* %rxbm.pm_e64, align 8
  %rxbm.nomatch = icmp slt i64 %rxbm.so64, 0
  br i1 %rxbm.nomatch, label %rxbm.gnull, label %rxbm.gsub

rxbm.gsub:
  %rxbm.gslen = sub i64 %rxbm.eo64, %rxbm.so64
  %rxbm.gs    = call i8* @nv_regex_substr(i8* %src, i64 %rxbm.so64, i64 %rxbm.gslen)
  br label %rxbm.gstore

rxbm.gnull:
  br label %rxbm.gstore

rxbm.gstore:
  %rxbm.gsv   = phi i8* [ %rxbm.gs, %rxbm.gsub ], [ null, %rxbm.gnull ]
  %rxbm.gi_m1 = sub i64 %rxbm.gi, 1
  %rxbm.gsr   = mul i64 %rxbm.gi_m1, 8
  %rxbm.gsoff = add i64 40, %rxbm.gsr
  %rxbm.gsp   = getelementptr i8, i8* %rxbm.match, i64 %rxbm.gsoff
  %rxbm.gspp  = bitcast i8* %rxbm.gsp to i8**
  store i8* %rxbm.gsv, i8** %rxbm.gspp, align 8
  %rxbm.gi1   = add i64 %rxbm.gi, 1
  br label %rxbm.gloop

rxbm.done:
  ret i8* %rxbm.match
}

; ── nv_match_value(match) → i8* ──────────────────────────────────────────────
define i8* @nv_match_value(i8* %m) {
rxmv.entry:
  %rxmv.pp = bitcast i8* %m to i8**
  %rxmv.v  = load i8*, i8** %rxmv.pp, align 8
  ret i8* %rxmv.v
}

; ── nv_match_start(match) → i64 ──────────────────────────────────────────────
define i64 @nv_match_start(i8* %m) {
rxms.entry:
  %rxms.p = getelementptr i8, i8* %m, i64 8
  %rxms.i = bitcast i8* %rxms.p to i64*
  %rxms.v = load i64, i64* %rxms.i, align 8
  ret i64 %rxms.v
}

; ── nv_match_end(match) → i64 ────────────────────────────────────────────────
define i64 @nv_match_end(i8* %m) {
rxme.entry:
  %rxme.p = getelementptr i8, i8* %m, i64 16
  %rxme.i = bitcast i8* %rxme.p to i64*
  %rxme.v = load i64, i64* %rxme.i, align 8
  ret i64 %rxme.v
}

; ── nv_match_group_idx(match, idx) → i8* (nullable) ─────────────────────────
; idx 0 = whole match, 1..N = capture groups.
define i8* @nv_match_group_idx(i8* %m, i64 %idx) {
rxmgi.entry:
  %rxmgi.z = icmp eq i64 %idx, 0
  br i1 %rxmgi.z, label %rxmgi.whole, label %rxmgi.cap

rxmgi.whole:
  %rxmgi.vp = bitcast i8* %m to i8**
  %rxmgi.v  = load i8*, i8** %rxmgi.vp, align 8
  ret i8* %rxmgi.v

rxmgi.cap:
  %rxmgi.ngp = getelementptr i8, i8* %m, i64 24
  %rxmgi.ng64 = bitcast i8* %rxmgi.ngp to i64*
  %rxmgi.ng  = load i64, i64* %rxmgi.ng64, align 8
  %rxmgi.oob = icmp sgt i64 %idx, %rxmgi.ng
  br i1 %rxmgi.oob, label %rxmgi.null, label %rxmgi.get

rxmgi.get:
  %rxmgi.im1 = sub i64 %idx, 1
  %rxmgi.rel = mul i64 %rxmgi.im1, 8
  %rxmgi.off = add i64 40, %rxmgi.rel
  %rxmgi.gp  = getelementptr i8, i8* %m, i64 %rxmgi.off
  %rxmgi.gpp = bitcast i8* %rxmgi.gp to i8**
  %rxmgi.gv  = load i8*, i8** %rxmgi.gpp, align 8
  ret i8* %rxmgi.gv

rxmgi.null:
  ret i8* null
}

; ── nv_match_group_name(match, name) → i8* (nullable) ────────────────────────
; Linear scan of names_buf to find the group index, then delegate to group_idx.
define i8* @nv_match_group_name(i8* %m, i8* %name) {
rxmgn.entry:
  %rxmgn.nb_off = getelementptr i8, i8* %m, i64 32
  %rxmgn.nb_pp  = bitcast i8* %rxmgn.nb_off to i8**
  %rxmgn.nb     = load i8*, i8** %rxmgn.nb_pp, align 8
  %rxmgn.nb_nul = icmp eq i8* %rxmgn.nb, null
  br i1 %rxmgn.nb_nul, label %rxmgn.null, label %rxmgn.scan

rxmgn.scan:
  %rxmgn.np.a  = alloca i8*, align 8
  %rxmgn.idx.a = alloca i64,  align 8
  store i8* %rxmgn.nb, i8** %rxmgn.np.a, align 8
  store i64 1, i64* %rxmgn.idx.a, align 8
  br label %rxmgn.loop

rxmgn.loop:
  %rxmgn.np  = load i8*, i8** %rxmgn.np.a, align 8
  %rxmgn.fc  = load i8, i8* %rxmgn.np, align 1
  %rxmgn.emp = icmp eq i8 %rxmgn.fc, 0
  br i1 %rxmgn.emp, label %rxmgn.null, label %rxmgn.cmp

rxmgn.cmp:
  %rxmgn.cr = call i32 @strcmp(i8* %rxmgn.np, i8* %name)
  %rxmgn.eq = icmp eq i32 %rxmgn.cr, 0
  br i1 %rxmgn.eq, label %rxmgn.found, label %rxmgn.next

rxmgn.found:
  %rxmgn.fi = load i64, i64* %rxmgn.idx.a, align 8
  %rxmgn.fv = call i8* @nv_match_group_idx(i8* %m, i64 %rxmgn.fi)
  ret i8* %rxmgn.fv

rxmgn.next:
  %rxmgn.ci  = load i64, i64* %rxmgn.idx.a, align 8
  %rxmgn.ci1 = add i64 %rxmgn.ci, 1
  store i64 %rxmgn.ci1, i64* %rxmgn.idx.a, align 8
  %rxmgn.nl  = call i64 @strlen(i8* %rxmgn.np)
  %rxmgn.np1 = getelementptr i8, i8* %rxmgn.np, i64 %rxmgn.nl
  %rxmgn.np2 = getelementptr i8, i8* %rxmgn.np1, i64 1
  store i8* %rxmgn.np2, i8** %rxmgn.np.a, align 8
  br label %rxmgn.loop

rxmgn.null:
  ret i8* null
}

; ── nv_regex_matches(regex, s) → i1 ──────────────────────────────────────────
; True only if the pattern matches the ENTIRE string.
define i1 @nv_regex_matches(i8* %regex, i8* %s) {
rxmat.entry:
  %rxmat.pm = alloca [16 x i8], align 8
  %rxmat.pmp = bitcast [16 x i8]* %rxmat.pm to i8*
  %rxmat.rt  = getelementptr i8, i8* %regex, i64 24
  %rxmat.rc  = call i32 @regexec(i8* %rxmat.rt, i8* %s, i64 1, i8* %rxmat.pmp, i32 0)
  %rxmat.hit = icmp eq i32 %rxmat.rc, 0
  br i1 %rxmat.hit, label %rxmat.chk, label %rxmat.false

rxmat.chk:
  %rxmat.so_p  = bitcast i8* %rxmat.pmp to i64*
  %rxmat.so    = load i64, i64* %rxmat.so_p, align 8
  %rxmat.eo_rp = getelementptr i8, i8* %rxmat.pmp, i64 8
  %rxmat.eo_p  = bitcast i8* %rxmat.eo_rp to i64*
  %rxmat.eo    = load i64, i64* %rxmat.eo_p, align 8
  %rxmat.sl    = call i64 @strlen(i8* %s)
  %rxmat.so_ok = icmp eq i64 %rxmat.so, 0
  %rxmat.eo_ok = icmp eq i64 %rxmat.eo, %rxmat.sl
  %rxmat.full  = and i1 %rxmat.so_ok, %rxmat.eo_ok
  ret i1 %rxmat.full

rxmat.false:
  ret i1 false
}

; ── nv_regex_contains(regex, s) → i1 ─────────────────────────────────────────
define i1 @nv_regex_contains(i8* %regex, i8* %s) {
rxcon.entry:
  %rxcon.pm  = alloca [16 x i8], align 8
  %rxcon.pmp = bitcast [16 x i8]* %rxcon.pm to i8*
  %rxcon.rt  = getelementptr i8, i8* %regex, i64 24
  %rxcon.rc  = call i32 @regexec(i8* %rxcon.rt, i8* %s, i64 1, i8* %rxcon.pmp, i32 0)
  %rxcon.res = icmp eq i32 %rxcon.rc, 0
  ret i1 %rxcon.res
}

; ── nv_regex_find(regex, s) → i8* (Match? or null) ───────────────────────────
define i8* @nv_regex_find(i8* %regex, i8* %s) {
rxf.entry:
  %rxf.ng_p  = bitcast i8* %regex to i64*
  %rxf.ng    = load i64, i64* %rxf.ng_p, align 8
  %rxf.nm    = add i64 %rxf.ng, 1
  %rxf.pmsz  = mul i64 %rxf.nm, 16
  %rxf.pm    = call i8* @malloc(i64 %rxf.pmsz)
  %rxf.rt    = getelementptr i8, i8* %regex, i64 24
  %rxf.rc    = call i32 @regexec(i8* %rxf.rt, i8* %s, i64 %rxf.nm, i8* %rxf.pm, i32 0)
  %rxf.ok    = icmp eq i32 %rxf.rc, 0
  br i1 %rxf.ok, label %rxf.build, label %rxf.miss

rxf.build:
  %rxf.m = call i8* @nv_regex_build_match(i8* %regex, i8* %s, i8* %rxf.pm, i64 0)
  call void @free(i8* %rxf.pm)
  ret i8* %rxf.m

rxf.miss:
  call void @free(i8* %rxf.pm)
  ret i8* null
}

; ── nv_regex_find_all(regex, s) → i8* (NvArray of Match handles) ─────────────
define i8* @nv_regex_find_all(i8* %regex, i8* %s) {
rxfa.entry:
  %rxfa.ng_p = bitcast i8* %regex to i64*
  %rxfa.ng   = load i64, i64* %rxfa.ng_p, align 8
  %rxfa.nm   = add i64 %rxfa.ng, 1
  %rxfa.pmsz = mul i64 %rxfa.nm, 16
  %rxfa.pm   = call i8* @malloc(i64 %rxfa.pmsz)
  %rxfa.rt   = getelementptr i8, i8* %regex, i64 24
  %rxfa.arr0 = call i8* @malloc(i64 8)
  %rxfa.a0p  = bitcast i8* %rxfa.arr0 to i64*
  store i64 0, i64* %rxfa.a0p, align 8
  %rxfa.arr.a = alloca i8*, align 8
  %rxfa.pos.a = alloca i8*, align 8
  %rxfa.off.a = alloca i64, align 8
  store i8* %rxfa.arr0, i8** %rxfa.arr.a, align 8
  store i8* %s,         i8** %rxfa.pos.a, align 8
  store i64 0,          i64* %rxfa.off.a, align 8
  br label %rxfa.loop

rxfa.loop:
  %rxfa.pos = load i8*, i8** %rxfa.pos.a, align 8
  %rxfa.rc  = call i32 @regexec(i8* %rxfa.rt, i8* %rxfa.pos, i64 %rxfa.nm, i8* %rxfa.pm, i32 0)
  %rxfa.hit = icmp eq i32 %rxfa.rc, 0
  br i1 %rxfa.hit, label %rxfa.match, label %rxfa.done

rxfa.match:
  %rxfa.so_p  = bitcast i8* %rxfa.pm to i64*
  %rxfa.so64  = load i64, i64* %rxfa.so_p, align 8
  %rxfa.eo_rp = getelementptr i8, i8* %rxfa.pm, i64 8
  %rxfa.eo_p  = bitcast i8* %rxfa.eo_rp to i64*
  %rxfa.eo64  = load i64, i64* %rxfa.eo_p, align 8
  %rxfa.off   = load i64, i64* %rxfa.off.a, align 8
  %rxfa.m     = call i8* @nv_regex_build_match(i8* %regex, i8* %rxfa.pos, i8* %rxfa.pm, i64 %rxfa.off)
  %rxfa.acur  = load i8*, i8** %rxfa.arr.a, align 8
  %rxfa.anew  = call i8* @nv_arr_push_str(i8* %rxfa.acur, i8* %rxfa.m)
  call void @free(i8* %rxfa.acur)
  store i8* %rxfa.anew, i8** %rxfa.arr.a, align 8
  %rxfa.zlen  = icmp eq i64 %rxfa.so64, %rxfa.eo64
  br i1 %rxfa.zlen, label %rxfa.adv1, label %rxfa.adveo

rxfa.adv1:
  %rxfa.pos1  = load i8*, i8** %rxfa.pos.a, align 8
  %rxfa.pos1n = getelementptr i8, i8* %rxfa.pos1, i64 1
  store i8* %rxfa.pos1n, i8** %rxfa.pos.a, align 8
  %rxfa.off1  = load i64, i64* %rxfa.off.a, align 8
  %rxfa.off1n = add i64 %rxfa.off1, 1
  store i64 %rxfa.off1n, i64* %rxfa.off.a, align 8
  br label %rxfa.loop

rxfa.adveo:
  %rxfa.pos2  = load i8*, i8** %rxfa.pos.a, align 8
  %rxfa.pos2n = getelementptr i8, i8* %rxfa.pos2, i64 %rxfa.eo64
  store i8* %rxfa.pos2n, i8** %rxfa.pos.a, align 8
  %rxfa.off2  = load i64, i64* %rxfa.off.a, align 8
  %rxfa.off2n = add i64 %rxfa.off2, %rxfa.eo64
  store i64 %rxfa.off2n, i64* %rxfa.off.a, align 8
  br label %rxfa.loop

rxfa.done:
  call void @free(i8* %rxfa.pm)
  %rxfa.res = load i8*, i8** %rxfa.arr.a, align 8
  ret i8* %rxfa.res
}

; ── nv_regex_expand_repl(repl, match) → i8* ──────────────────────────────────
; Expand dollar-N references in repl using match group strings. Two-pass (size then build).
define i8* @nv_regex_expand_repl(i8* %repl, i8* %match) {
rxexp.entry:
  %rxexp.rp.a  = alloca i8*, align 8
  %rxexp.len.a = alloca i64, align 8
  %rxexp.idx.a = alloca i64, align 8
  %rxexp.op.a  = alloca i8*, align 8
  ; ── Pass 1: compute output length ────────────────────────────────────────
  store i8* %repl, i8** %rxexp.rp.a, align 8
  store i64 0, i64* %rxexp.len.a, align 8
  br label %rxexp.p1

rxexp.p1:
  %rxexp.rp1 = load i8*, i8** %rxexp.rp.a, align 8
  %rxexp.c1  = load i8, i8* %rxexp.rp1, align 1
  %rxexp.e1  = icmp eq i8 %rxexp.c1, 0
  br i1 %rxexp.e1, label %rxexp.alloc, label %rxexp.p1d

rxexp.p1d:
  %rxexp.p1dl = icmp eq i8 %rxexp.c1, 36
  br i1 %rxexp.p1dl, label %rxexp.p1dol, label %rxexp.p1plain

rxexp.p1plain:
  %rxexp.l1  = load i64, i64* %rxexp.len.a, align 8
  %rxexp.l1a = add i64 %rxexp.l1, 1
  store i64 %rxexp.l1a, i64* %rxexp.len.a, align 8
  %rxexp.rp1a = getelementptr i8, i8* %rxexp.rp1, i64 1
  store i8* %rxexp.rp1a, i8** %rxexp.rp.a, align 8
  br label %rxexp.p1

rxexp.p1dol:
  %rxexp.rp1d = getelementptr i8, i8* %rxexp.rp1, i64 1
  %rxexp.cd1  = load i8, i8* %rxexp.rp1d, align 1
  %rxexp.dg0  = icmp uge i8 %rxexp.cd1, 48
  %rxexp.dg9  = icmp ule i8 %rxexp.cd1, 57
  %rxexp.dig1 = and i1 %rxexp.dg0, %rxexp.dg9
  br i1 %rxexp.dig1, label %rxexp.p1idx, label %rxexp.p1dlit

rxexp.p1dlit:
  %rxexp.ll  = load i64, i64* %rxexp.len.a, align 8
  %rxexp.lla = add i64 %rxexp.ll, 1
  store i64 %rxexp.lla, i64* %rxexp.len.a, align 8
  %rxexp.rp1la = getelementptr i8, i8* %rxexp.rp1, i64 1
  store i8* %rxexp.rp1la, i8** %rxexp.rp.a, align 8
  br label %rxexp.p1

rxexp.p1idx:
  store i64 0, i64* %rxexp.idx.a, align 8
  store i8* %rxexp.rp1d, i8** %rxexp.rp.a, align 8
  br label %rxexp.p1int

rxexp.p1int:
  %rxexp.rpi1 = load i8*, i8** %rxexp.rp.a, align 8
  %rxexp.ci1  = load i8, i8* %rxexp.rpi1, align 1
  %rxexp.ig0  = icmp uge i8 %rxexp.ci1, 48
  %rxexp.ig9  = icmp ule i8 %rxexp.ci1, 57
  %rxexp.idig = and i1 %rxexp.ig0, %rxexp.ig9
  br i1 %rxexp.idig, label %rxexp.p1intb, label %rxexp.p1intd

rxexp.p1intb:
  %rxexp.ii1 = load i64, i64* %rxexp.idx.a, align 8
  %rxexp.iv1 = zext i8 %rxexp.ci1 to i64
  %rxexp.id1 = sub i64 %rxexp.iv1, 48
  %rxexp.im1 = mul i64 %rxexp.ii1, 10
  %rxexp.ia1 = add i64 %rxexp.im1, %rxexp.id1
  store i64 %rxexp.ia1, i64* %rxexp.idx.a, align 8
  %rxexp.rpi1a = getelementptr i8, i8* %rxexp.rpi1, i64 1
  store i8* %rxexp.rpi1a, i8** %rxexp.rp.a, align 8
  br label %rxexp.p1int

rxexp.p1intd:
  %rxexp.fi1 = load i64, i64* %rxexp.idx.a, align 8
  %rxexp.gs1 = call i8* @nv_match_group_idx(i8* %match, i64 %rxexp.fi1)
  %rxexp.gn1 = icmp eq i8* %rxexp.gs1, null
  br i1 %rxexp.gn1, label %rxexp.p1, label %rxexp.p1grplen

rxexp.p1grplen:
  %rxexp.gl1 = call i64 @strlen(i8* %rxexp.gs1)
  %rxexp.la1 = load i64, i64* %rxexp.len.a, align 8
  %rxexp.la2 = add i64 %rxexp.la1, %rxexp.gl1
  store i64 %rxexp.la2, i64* %rxexp.len.a, align 8
  br label %rxexp.p1

  ; ── Allocate output ─────────────────────────────────────────────────────
rxexp.alloc:
  %rxexp.olen = load i64, i64* %rxexp.len.a, align 8
  %rxexp.osz  = add i64 %rxexp.olen, 1
  %rxexp.out  = call i8* @malloc(i64 %rxexp.osz)
  store i8* %repl,        i8** %rxexp.rp.a, align 8
  store i8* %rxexp.out,   i8** %rxexp.op.a, align 8
  br label %rxexp.p2

  ; ── Pass 2: build output ─────────────────────────────────────────────────
rxexp.p2:
  %rxexp.rp2 = load i8*, i8** %rxexp.rp.a, align 8
  %rxexp.c2  = load i8, i8* %rxexp.rp2, align 1
  %rxexp.e2  = icmp eq i8 %rxexp.c2, 0
  br i1 %rxexp.e2, label %rxexp.p2done, label %rxexp.p2d

rxexp.p2d:
  %rxexp.p2dl = icmp eq i8 %rxexp.c2, 36
  br i1 %rxexp.p2dl, label %rxexp.p2dol, label %rxexp.p2plain

rxexp.p2plain:
  %rxexp.op2  = load i8*, i8** %rxexp.op.a, align 8
  store i8 %rxexp.c2, i8* %rxexp.op2, align 1
  %rxexp.op2a  = getelementptr i8, i8* %rxexp.op2, i64 1
  %rxexp.rp2a  = getelementptr i8, i8* %rxexp.rp2, i64 1
  store i8* %rxexp.op2a, i8** %rxexp.op.a, align 8
  store i8* %rxexp.rp2a, i8** %rxexp.rp.a, align 8
  br label %rxexp.p2

rxexp.p2dol:
  %rxexp.rp2d = getelementptr i8, i8* %rxexp.rp2, i64 1
  %rxexp.cd2  = load i8, i8* %rxexp.rp2d, align 1
  %rxexp.d2g0 = icmp uge i8 %rxexp.cd2, 48
  %rxexp.d2g9 = icmp ule i8 %rxexp.cd2, 57
  %rxexp.d2dg = and i1 %rxexp.d2g0, %rxexp.d2g9
  br i1 %rxexp.d2dg, label %rxexp.p2idx, label %rxexp.p2dlit

rxexp.p2dlit:
  %rxexp.op2l = load i8*, i8** %rxexp.op.a, align 8
  store i8 36, i8* %rxexp.op2l, align 1
  %rxexp.op2la = getelementptr i8, i8* %rxexp.op2l, i64 1
  %rxexp.rp2la = getelementptr i8, i8* %rxexp.rp2, i64 1
  store i8* %rxexp.op2la, i8** %rxexp.op.a, align 8
  store i8* %rxexp.rp2la, i8** %rxexp.rp.a, align 8
  br label %rxexp.p2

rxexp.p2idx:
  store i64 0, i64* %rxexp.idx.a, align 8
  store i8* %rxexp.rp2d, i8** %rxexp.rp.a, align 8
  br label %rxexp.p2int

rxexp.p2int:
  %rxexp.rpi2 = load i8*, i8** %rxexp.rp.a, align 8
  %rxexp.ci2  = load i8, i8* %rxexp.rpi2, align 1
  %rxexp.j2g0 = icmp uge i8 %rxexp.ci2, 48
  %rxexp.j2g9 = icmp ule i8 %rxexp.ci2, 57
  %rxexp.j2dg = and i1 %rxexp.j2g0, %rxexp.j2g9
  br i1 %rxexp.j2dg, label %rxexp.p2intb, label %rxexp.p2intd

rxexp.p2intb:
  %rxexp.ii2 = load i64, i64* %rxexp.idx.a, align 8
  %rxexp.iv2 = zext i8 %rxexp.ci2 to i64
  %rxexp.id2 = sub i64 %rxexp.iv2, 48
  %rxexp.im2 = mul i64 %rxexp.ii2, 10
  %rxexp.ia2 = add i64 %rxexp.im2, %rxexp.id2
  store i64 %rxexp.ia2, i64* %rxexp.idx.a, align 8
  %rxexp.rpi2a = getelementptr i8, i8* %rxexp.rpi2, i64 1
  store i8* %rxexp.rpi2a, i8** %rxexp.rp.a, align 8
  br label %rxexp.p2int

rxexp.p2intd:
  %rxexp.fi2 = load i64, i64* %rxexp.idx.a, align 8
  %rxexp.gs2 = call i8* @nv_match_group_idx(i8* %match, i64 %rxexp.fi2)
  %rxexp.gn2 = icmp eq i8* %rxexp.gs2, null
  br i1 %rxexp.gn2, label %rxexp.p2, label %rxexp.p2grpcopy

rxexp.p2grpcopy:
  %rxexp.op2g  = load i8*, i8** %rxexp.op.a, align 8
  %rxexp.gl2   = call i64 @strlen(i8* %rxexp.gs2)
  call i8* @memcpy(i8* %rxexp.op2g, i8* %rxexp.gs2, i64 %rxexp.gl2)
  %rxexp.op2ga = getelementptr i8, i8* %rxexp.op2g, i64 %rxexp.gl2
  store i8* %rxexp.op2ga, i8** %rxexp.op.a, align 8
  br label %rxexp.p2

rxexp.p2done:
  %rxexp.opf = load i8*, i8** %rxexp.op.a, align 8
  store i8 0, i8* %rxexp.opf, align 1
  ret i8* %rxexp.out
}

; ── nv_regex_replace(regex, s, repl) → i8* ───────────────────────────────────
; Replace the FIRST match. Returns a copy of s if no match.
define i8* @nv_regex_replace(i8* %regex, i8* %s, i8* %repl) {
rxrp.entry:
  %rxrp.ng_p = bitcast i8* %regex to i64*
  %rxrp.ng   = load i64, i64* %rxrp.ng_p, align 8
  %rxrp.nm   = add i64 %rxrp.ng, 1
  %rxrp.pmsz = mul i64 %rxrp.nm, 16
  %rxrp.pm   = call i8* @malloc(i64 %rxrp.pmsz)
  %rxrp.rt   = getelementptr i8, i8* %regex, i64 24
  %rxrp.rc   = call i32 @regexec(i8* %rxrp.rt, i8* %s, i64 %rxrp.nm, i8* %rxrp.pm, i32 0)
  %rxrp.ok   = icmp eq i32 %rxrp.rc, 0
  br i1 %rxrp.ok, label %rxrp.do, label %rxrp.nomatch

rxrp.nomatch:
  call void @free(i8* %rxrp.pm)
  %rxrp.sl   = call i64 @strlen(i8* %s)
  %rxrp.copy = call i8* @nv_regex_substr(i8* %s, i64 0, i64 %rxrp.sl)
  ret i8* %rxrp.copy

rxrp.do:
  %rxrp.so_p  = bitcast i8* %rxrp.pm to i64*
  %rxrp.so64  = load i64, i64* %rxrp.so_p, align 8
  %rxrp.eo_rp = getelementptr i8, i8* %rxrp.pm, i64 8
  %rxrp.eo_p  = bitcast i8* %rxrp.eo_rp to i64*
  %rxrp.eo64  = load i64, i64* %rxrp.eo_p, align 8
  %rxrp.m     = call i8* @nv_regex_build_match(i8* %regex, i8* %s, i8* %rxrp.pm, i64 0)
  call void @free(i8* %rxrp.pm)
  %rxrp.exp   = call i8* @nv_regex_expand_repl(i8* %repl, i8* %rxrp.m)
  call void @nv_match_free(i8* %rxrp.m)
  %rxrp.el    = call i64 @strlen(i8* %rxrp.exp)
  %rxrp.sl2   = call i64 @strlen(i8* %s)
  %rxrp.suf_l = sub i64 %rxrp.sl2, %rxrp.eo64
  %rxrp.olen  = add i64 %rxrp.so64, %rxrp.el
  %rxrp.olen2 = add i64 %rxrp.olen, %rxrp.suf_l
  %rxrp.osz   = add i64 %rxrp.olen2, 1
  %rxrp.out   = call i8* @malloc(i64 %rxrp.osz)
  call i8* @memcpy(i8* %rxrp.out, i8* %s, i64 %rxrp.so64)
  %rxrp.op1   = getelementptr i8, i8* %rxrp.out, i64 %rxrp.so64
  call i8* @memcpy(i8* %rxrp.op1, i8* %rxrp.exp, i64 %rxrp.el)
  %rxrp.op2   = getelementptr i8, i8* %rxrp.op1, i64 %rxrp.el
  %rxrp.suf_p = getelementptr i8, i8* %s, i64 %rxrp.eo64
  %rxrp.suf_cp = add i64 %rxrp.suf_l, 1
  call i8* @memcpy(i8* %rxrp.op2, i8* %rxrp.suf_p, i64 %rxrp.suf_cp)
  call void @free(i8* %rxrp.exp)
  ret i8* %rxrp.out
}

; ── nv_regex_replace_all(regex, s, repl) → i8* ───────────────────────────────
; Two-pass: first count output length, then build.
define i8* @nv_regex_replace_all(i8* %regex, i8* %s, i8* %repl) {
rxra.entry:
  %rxra.ng_p = bitcast i8* %regex to i64*
  %rxra.ng   = load i64, i64* %rxra.ng_p, align 8
  %rxra.nm   = add i64 %rxra.ng, 1
  %rxra.pmsz = mul i64 %rxra.nm, 16
  %rxra.pm   = call i8* @malloc(i64 %rxra.pmsz)
  %rxra.rt   = getelementptr i8, i8* %regex, i64 24
  %rxra.pos.a = alloca i8*, align 8
  %rxra.off.a = alloca i64, align 8
  %rxra.len.a = alloca i64, align 8
  %rxra.op.a  = alloca i8*, align 8
  ; ── Pass 1: compute total output length ──────────────────────────────────
  store i8* %s, i8** %rxra.pos.a, align 8
  store i64 0, i64* %rxra.off.a, align 8
  store i64 0, i64* %rxra.len.a, align 8
  br label %rxra.p1

rxra.p1:
  %rxra.pos1 = load i8*, i8** %rxra.pos.a, align 8
  %rxra.off1 = load i64, i64* %rxra.off.a, align 8
  %rxra.rc1  = call i32 @regexec(i8* %rxra.rt, i8* %rxra.pos1, i64 %rxra.nm, i8* %rxra.pm, i32 0)
  %rxra.ok1  = icmp eq i32 %rxra.rc1, 0
  br i1 %rxra.ok1, label %rxra.p1m, label %rxra.p1tail

rxra.p1m:
  %rxra.so1_p  = bitcast i8* %rxra.pm to i64*
  %rxra.so164  = load i64, i64* %rxra.so1_p, align 8
  %rxra.eo1_rp = getelementptr i8, i8* %rxra.pm, i64 8
  %rxra.eo1_p  = bitcast i8* %rxra.eo1_rp to i64*
  %rxra.eo164  = load i64, i64* %rxra.eo1_p, align 8
  %rxra.m1     = call i8* @nv_regex_build_match(i8* %regex, i8* %rxra.pos1, i8* %rxra.pm, i64 %rxra.off1)
  %rxra.exp1   = call i8* @nv_regex_expand_repl(i8* %repl, i8* %rxra.m1)
  call void @nv_match_free(i8* %rxra.m1)
  %rxra.el1    = call i64 @strlen(i8* %rxra.exp1)
  call void @free(i8* %rxra.exp1)
  %rxra.l1     = load i64, i64* %rxra.len.a, align 8
  %rxra.l1a    = add i64 %rxra.l1, %rxra.so164
  %rxra.l1b    = add i64 %rxra.l1a, %rxra.el1
  store i64 %rxra.l1b, i64* %rxra.len.a, align 8
  %rxra.zl1    = icmp eq i64 %rxra.so164, %rxra.eo164
  br i1 %rxra.zl1, label %rxra.p1adv1, label %rxra.p1adveo

rxra.p1adv1:
  ; Zero-length match: count the char we skip (if not at end)
  %rxra.pc1   = load i8, i8* %rxra.pos1, align 1
  %rxra.ate1  = icmp eq i8 %rxra.pc1, 0
  br i1 %rxra.ate1, label %rxra.p1tail, label %rxra.p1adv1b

rxra.p1adv1b:
  %rxra.l1c   = load i64, i64* %rxra.len.a, align 8
  %rxra.l1d   = add i64 %rxra.l1c, 1
  store i64 %rxra.l1d, i64* %rxra.len.a, align 8
  %rxra.pos1a = getelementptr i8, i8* %rxra.pos1, i64 1
  store i8* %rxra.pos1a, i8** %rxra.pos.a, align 8
  %rxra.off1a = add i64 %rxra.off1, 1
  store i64 %rxra.off1a, i64* %rxra.off.a, align 8
  br label %rxra.p1

rxra.p1adveo:
  %rxra.pos1b = getelementptr i8, i8* %rxra.pos1, i64 %rxra.eo164
  store i8* %rxra.pos1b, i8** %rxra.pos.a, align 8
  %rxra.off1b = add i64 %rxra.off1, %rxra.eo164
  store i64 %rxra.off1b, i64* %rxra.off.a, align 8
  br label %rxra.p1

rxra.p1tail:
  %rxra.tail1  = load i8*, i8** %rxra.pos.a, align 8
  %rxra.taill1 = call i64 @strlen(i8* %rxra.tail1)
  %rxra.lt1    = load i64, i64* %rxra.len.a, align 8
  %rxra.lt2    = add i64 %rxra.lt1, %rxra.taill1
  store i64 %rxra.lt2, i64* %rxra.len.a, align 8
  ; Allocate output
  %rxra.olen  = load i64, i64* %rxra.len.a, align 8
  %rxra.osz   = add i64 %rxra.olen, 1
  %rxra.out   = call i8* @malloc(i64 %rxra.osz)
  ; ── Pass 2: build output ─────────────────────────────────────────────────
  store i8* %s,        i8** %rxra.pos.a, align 8
  store i64 0,         i64* %rxra.off.a, align 8
  store i8* %rxra.out, i8** %rxra.op.a,  align 8
  br label %rxra.p2

rxra.p2:
  %rxra.pos2 = load i8*, i8** %rxra.pos.a, align 8
  %rxra.off2 = load i64, i64* %rxra.off.a, align 8
  %rxra.rc2  = call i32 @regexec(i8* %rxra.rt, i8* %rxra.pos2, i64 %rxra.nm, i8* %rxra.pm, i32 0)
  %rxra.ok2  = icmp eq i32 %rxra.rc2, 0
  br i1 %rxra.ok2, label %rxra.p2m, label %rxra.p2tail

rxra.p2m:
  %rxra.so2_p  = bitcast i8* %rxra.pm to i64*
  %rxra.so264  = load i64, i64* %rxra.so2_p, align 8
  %rxra.eo2_rp = getelementptr i8, i8* %rxra.pm, i64 8
  %rxra.eo2_p  = bitcast i8* %rxra.eo2_rp to i64*
  %rxra.eo264  = load i64, i64* %rxra.eo2_p, align 8
  %rxra.op2    = load i8*, i8** %rxra.op.a, align 8
  call i8* @memcpy(i8* %rxra.op2, i8* %rxra.pos2, i64 %rxra.so264)
  %rxra.op2a   = getelementptr i8, i8* %rxra.op2, i64 %rxra.so264
  %rxra.m2     = call i8* @nv_regex_build_match(i8* %regex, i8* %rxra.pos2, i8* %rxra.pm, i64 %rxra.off2)
  %rxra.exp2   = call i8* @nv_regex_expand_repl(i8* %repl, i8* %rxra.m2)
  call void @nv_match_free(i8* %rxra.m2)
  %rxra.el2    = call i64 @strlen(i8* %rxra.exp2)
  call i8* @memcpy(i8* %rxra.op2a, i8* %rxra.exp2, i64 %rxra.el2)
  call void @free(i8* %rxra.exp2)
  %rxra.op2b   = getelementptr i8, i8* %rxra.op2a, i64 %rxra.el2
  store i8* %rxra.op2b, i8** %rxra.op.a, align 8
  %rxra.zl2    = icmp eq i64 %rxra.so264, %rxra.eo264
  br i1 %rxra.zl2, label %rxra.p2adv1, label %rxra.p2adveo

rxra.p2adv1:
  %rxra.pc2   = load i8, i8* %rxra.pos2, align 1
  %rxra.ate2  = icmp eq i8 %rxra.pc2, 0
  br i1 %rxra.ate2, label %rxra.p2tail, label %rxra.p2adv1b

rxra.p2adv1b:
  %rxra.op2c  = load i8*, i8** %rxra.op.a, align 8
  store i8 %rxra.pc2, i8* %rxra.op2c, align 1
  %rxra.op2d  = getelementptr i8, i8* %rxra.op2c, i64 1
  store i8* %rxra.op2d, i8** %rxra.op.a, align 8
  %rxra.pos2a = getelementptr i8, i8* %rxra.pos2, i64 1
  store i8* %rxra.pos2a, i8** %rxra.pos.a, align 8
  %rxra.off2a = add i64 %rxra.off2, 1
  store i64 %rxra.off2a, i64* %rxra.off.a, align 8
  br label %rxra.p2

rxra.p2adveo:
  %rxra.pos2b = getelementptr i8, i8* %rxra.pos2, i64 %rxra.eo264
  store i8* %rxra.pos2b, i8** %rxra.pos.a, align 8
  %rxra.off2b = add i64 %rxra.off2, %rxra.eo264
  store i64 %rxra.off2b, i64* %rxra.off.a, align 8
  br label %rxra.p2

rxra.p2tail:
  %rxra.op2t  = load i8*, i8** %rxra.op.a, align 8
  %rxra.tail2 = load i8*, i8** %rxra.pos.a, align 8
  %rxra.tl2   = call i64 @strlen(i8* %rxra.tail2)
  %rxra.tlp1  = add i64 %rxra.tl2, 1
  call i8* @memcpy(i8* %rxra.op2t, i8* %rxra.tail2, i64 %rxra.tlp1)
  call void @free(i8* %rxra.pm)
  ret i8* %rxra.out
}

; ── nv_regex_split(regex, s) → i8* (NvArray<str>) ────────────────────────────
; Returns the parts of s between each match.
define i8* @nv_regex_split(i8* %regex, i8* %s) {
rxsp.entry:
  %rxsp.pm   = alloca [16 x i8], align 8
  %rxsp.pmp  = bitcast [16 x i8]* %rxsp.pm to i8*
  %rxsp.rt   = getelementptr i8, i8* %regex, i64 24
  %rxsp.arr0 = call i8* @malloc(i64 8)
  %rxsp.a0p  = bitcast i8* %rxsp.arr0 to i64*
  store i64 0, i64* %rxsp.a0p, align 8
  %rxsp.arr.a = alloca i8*, align 8
  %rxsp.pos.a = alloca i8*, align 8
  store i8* %rxsp.arr0, i8** %rxsp.arr.a, align 8
  store i8* %s,          i8** %rxsp.pos.a, align 8
  br label %rxsp.loop

rxsp.loop:
  %rxsp.pos = load i8*, i8** %rxsp.pos.a, align 8
  %rxsp.rc  = call i32 @regexec(i8* %rxsp.rt, i8* %rxsp.pos, i64 1, i8* %rxsp.pmp, i32 0)
  %rxsp.ok  = icmp eq i32 %rxsp.rc, 0
  br i1 %rxsp.ok, label %rxsp.match, label %rxsp.tail

rxsp.match:
  %rxsp.so_p  = bitcast i8* %rxsp.pmp to i64*
  %rxsp.so64  = load i64, i64* %rxsp.so_p, align 8
  %rxsp.eo_rp = getelementptr i8, i8* %rxsp.pmp, i64 8
  %rxsp.eo_p  = bitcast i8* %rxsp.eo_rp to i64*
  %rxsp.eo64  = load i64, i64* %rxsp.eo_p, align 8
  %rxsp.part  = call i8* @nv_regex_substr(i8* %rxsp.pos, i64 0, i64 %rxsp.so64)
  %rxsp.acur  = load i8*, i8** %rxsp.arr.a, align 8
  %rxsp.anew  = call i8* @nv_arr_push_str(i8* %rxsp.acur, i8* %rxsp.part)
  call void @free(i8* %rxsp.acur)
  store i8* %rxsp.anew, i8** %rxsp.arr.a, align 8
  ; Advance past match (handle zero-length)
  %rxsp.zl    = icmp eq i64 %rxsp.so64, %rxsp.eo64
  br i1 %rxsp.zl, label %rxsp.adv1, label %rxsp.adveo

rxsp.adv1:
  %rxsp.posc = load i8, i8* %rxsp.pos, align 1
  %rxsp.atend = icmp eq i8 %rxsp.posc, 0
  br i1 %rxsp.atend, label %rxsp.tail, label %rxsp.adv1b

rxsp.adv1b:
  %rxsp.pos1 = getelementptr i8, i8* %rxsp.pos, i64 1
  store i8* %rxsp.pos1, i8** %rxsp.pos.a, align 8
  br label %rxsp.loop

rxsp.adveo:
  %rxsp.poseo = getelementptr i8, i8* %rxsp.pos, i64 %rxsp.eo64
  store i8* %rxsp.poseo, i8** %rxsp.pos.a, align 8
  br label %rxsp.loop

rxsp.tail:
  ; Append the remaining suffix (may be empty string)
  %rxsp.tpos  = load i8*, i8** %rxsp.pos.a, align 8
  %rxsp.tlen  = call i64 @strlen(i8* %rxsp.tpos)
  %rxsp.tailv = call i8* @nv_regex_substr(i8* %rxsp.tpos, i64 0, i64 %rxsp.tlen)
  %rxsp.afin  = load i8*, i8** %rxsp.arr.a, align 8
  %rxsp.afin2 = call i8* @nv_arr_push_str(i8* %rxsp.afin, i8* %rxsp.tailv)
  call void @free(i8* %rxsp.afin)
  ret i8* %rxsp.afin2
}
""".trimIndent())
    }
}
