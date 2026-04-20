package nv.compiler.codegen.runtime

/**
 * std.json runtime: JSON parse, query, value extraction, and construction.
 *
 * JSON values are represented as plain null-terminated C strings (i8*) containing
 * their raw JSON text. Helper functions operate on these string pointers directly.
 *
 * Design: uses strstr-based field lookup with a colon-verification retry loop for
 * robustness. Nested object/array traversal uses a depth counter. The implementation
 * is intentionally lightweight; known limitations are documented inline.
 *
 * Private helpers (not in inlineRuntimeFns, no @extern bindings):
 *   nv_json_skip_ws     — advance past whitespace characters
 *   nv_json_skip_string — advance past a JSON string (handles \-escapes)
 *   nv_json_skip_value  — advance past any JSON value (string/object/array/scalar)
 *   nv_json_substr      — malloc + copy a substring with null terminator
 */
internal object JsonRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.json ─────────────────────────────────────────────────────────────────

@.json.null_str    = private unnamed_addr constant [5  x i8] c"null\00",         align 1
@.json.true_str    = private unnamed_addr constant [5  x i8] c"true\00",         align 1
@.json.false_str   = private unnamed_addr constant [6  x i8] c"false\00",        align 1
@.json.err_invalid = private unnamed_addr constant [13 x i8] c"invalid JSON\00", align 1
@.json.fmt_ld      = private unnamed_addr constant [5  x i8] c"%lld\00",         align 1
@.json.fmt_g       = private unnamed_addr constant [3  x i8] c"%g\00",           align 1

; ── nv_json_skip_ws(p) → i8* ─────────────────────────────────────────────────
; Returns pointer to first non-whitespace character at or after p.
define i8* @nv_json_skip_ws(i8* %p) {
entry:
  br label %loop
loop:
  %cur = phi i8* [ %p, %entry ], [ %next, %body ]
  %c   = load i8, i8* %cur, align 1
  %cu  = zext i8 %c to i32
  %r   = call i32 @isspace(i32 %cu)
  %ws  = icmp ne i32 %r, 0
  br i1 %ws, label %body, label %done
body:
  %next = getelementptr i8, i8* %cur, i64 1
  br label %loop
done:
  ret i8* %cur
}

; ── nv_json_skip_string(p) → i8* ─────────────────────────────────────────────
; p points to '"'. Returns pointer to character after the closing '"'.
; Handles backslash-escaped characters (e.g. \", \\).
define i8* @nv_json_skip_string(i8* %p) {
entry:
  %p1 = getelementptr i8, i8* %p, i64 1
  br label %loop
loop:
  %cur = phi i8* [ %p1, %entry ], [ %skip2, %esc ], [ %adv, %plain ]
  %c   = load i8, i8* %cur, align 1
  ; '"' == 34 → end of string
  %is_q = icmp eq i8 %c, 34
  br i1 %is_q, label %done, label %not_q
not_q:
  ; '\\' == 92 → escape sequence: skip backslash + next char
  %is_bs = icmp eq i8 %c, 92
  br i1 %is_bs, label %esc, label %plain
esc:
  %skip2 = getelementptr i8, i8* %cur, i64 2
  br label %loop
plain:
  %adv = getelementptr i8, i8* %cur, i64 1
  br label %loop
done:
  %result = getelementptr i8, i8* %cur, i64 1
  ret i8* %result
}

; ── nv_json_skip_value(p) → i8* ──────────────────────────────────────────────
; p points to first char of a JSON value (after whitespace skip).
; Returns pointer to character after the value.
; Handles strings, objects, arrays, and scalars (numbers, true, false, null).
define i8* @nv_json_skip_value(i8* %p) {
entry:
  %c = load i8, i8* %p, align 1
  ; '"' == 34 → string
  %is_str = icmp eq i8 %c, 34
  br i1 %is_str, label %do_str, label %chk_obj
chk_obj:
  ; '{' == 123 → object
  %is_obj = icmp eq i8 %c, 123
  br i1 %is_obj, label %do_nested, label %chk_arr
chk_arr:
  ; '[' == 91 → array
  %is_arr = icmp eq i8 %c, 91
  br i1 %is_arr, label %do_nested, label %do_scalar
do_str:
  %str_end = call i8* @nv_json_skip_string(i8* %p)
  ret i8* %str_end
do_nested:
  ; Nested object or array: track bracket depth
  ; close bracket: '{' (123) → '}' (125), '[' (91) → ']' (93)
  %is_open_obj = icmp eq i8 %c, 123
  %close_c = select i1 %is_open_obj, i8 125, i8 93
  %p1 = getelementptr i8, i8* %p, i64 1
  br label %nest_loop
nest_loop:
  %np  = phi i8* [ %p1, %do_nested ], [ %np_after, %nest_step ]
  %dep = phi i64  [ 1, %do_nested ], [ %dep_after, %nest_step ]
  %nc  = load i8, i8* %np, align 1
  ; null byte → malformed input; return current position
  %nc_null = icmp eq i8 %nc, 0
  br i1 %nc_null, label %nest_eof, label %nest_chk
nest_eof:
  ret i8* %np
nest_chk:
  ; string → skip it without counting its inner brackets
  %nc_str = icmp eq i8 %nc, 34
  br i1 %nc_str, label %nest_skip_str, label %nest_brackets
nest_skip_str:
  %np_after_str = call i8* @nv_json_skip_string(i8* %np)
  br label %nest_step
nest_brackets:
  %np1 = getelementptr i8, i8* %np, i64 1
  ; open bracket → depth++
  %nc_oo = icmp eq i8 %nc, 123
  %nc_oa = icmp eq i8 %nc, 91
  %nc_open = or i1 %nc_oo, %nc_oa
  %dep_inc = add i64 %dep, 1
  ; close bracket → depth--
  %nc_close = icmp eq i8 %nc, %close_c
  %dep_dec = sub i64 %dep, 1
  %dep_if_open  = select i1 %nc_open,  i64 %dep_inc, i64 %dep
  %dep_if_close = select i1 %nc_close, i64 %dep_dec, i64 %dep_if_open
  %dep_zero = icmp eq i64 %dep_if_close, 0
  br i1 %dep_zero, label %nest_done, label %nest_step
nest_done:
  ret i8* %np1
nest_step:
  %np_after  = phi i8* [ %np_after_str, %nest_skip_str ], [ %np1, %nest_brackets ]
  %dep_after = phi i64  [ %dep, %nest_skip_str ], [ %dep_if_close, %nest_brackets ]
  br label %nest_loop
do_scalar:
  ; Advance until delimiter: whitespace, ',', '}', ']', or '\0'
  br label %scalar_loop
scalar_loop:
  %sp = phi i8* [ %p, %do_scalar ], [ %sp_next, %scalar_body ]
  %sc = load i8, i8* %sp, align 1
  %scu = zext i8 %sc to i32
  %sc_ws = call i32 @isspace(i32 %scu)
  %is_ws   = icmp ne i32 %sc_ws, 0
  %is_com  = icmp eq i8 %sc, 44
  %is_rcb  = icmp eq i8 %sc, 125
  %is_rsb  = icmp eq i8 %sc, 93
  %is_nil  = icmp eq i8 %sc, 0
  %d1 = or i1 %is_ws,  %is_com
  %d2 = or i1 %is_rcb, %is_rsb
  %d3 = or i1 %d1, %d2
  %delim = or i1 %d3, %is_nil
  br i1 %delim, label %scalar_done, label %scalar_body
scalar_body:
  %sp_next = getelementptr i8, i8* %sp, i64 1
  br label %scalar_loop
scalar_done:
  ret i8* %sp
}

; ── nv_json_substr(start, len) → i8* ─────────────────────────────────────────
; Allocates a new string containing len bytes from start, plus a null terminator.
define i8* @nv_json_substr(i8* %start, i64 %len) {
entry:
  %buf_len = add i64 %len, 1
  %buf = call i8* @malloc(i64 %buf_len)
  call i8* @memcpy(i8* %buf, i8* %start, i64 %len)
  %end_ptr = getelementptr i8, i8* %buf, i64 %len
  store i8 0, i8* %end_ptr, align 1
  ret i8* %buf
}

; ── nv_json_parse(s) → Result<str>  (i8*) ────────────────────────────────────
; Validates that s begins with a valid JSON value start character.
; Returns nv_Ok(s) on success or nv_Err("invalid JSON") on failure.
define i8* @nv_json_parse(i8* %s) {
entry:
  %p  = call i8* @nv_json_skip_ws(i8* %s)
  %c  = load i8, i8* %p, align 1
  %c64 = zext i8 %c to i64
  ; valid starts: '{' '[' '"' '-' '0'-'9' 't' 'f' 'n'
  %is_obj = icmp eq i8 %c, 123
  %is_arr = icmp eq i8 %c, 91
  %is_str = icmp eq i8 %c, 34
  %is_neg = icmp eq i8 %c, 45
  %ge0 = icmp uge i64 %c64, 48
  %le9 = icmp ule i64 %c64, 57
  %is_num = and i1 %ge0, %le9
  %is_t = icmp eq i8 %c, 116
  %is_f = icmp eq i8 %c, 102
  %is_n = icmp eq i8 %c, 110
  %v1 = or i1 %is_obj, %is_arr
  %v2 = or i1 %is_str, %is_neg
  %v3 = or i1 %is_num, %is_t
  %v4 = or i1 %is_f,   %is_n
  %v12 = or i1 %v1, %v2
  %v34 = or i1 %v3, %v4
  %valid = or i1 %v12, %v34
  br i1 %valid, label %ok, label %err
ok:
  %ok_r = call i8* @nv_Ok(i8* %s)
  ret i8* %ok_r
err:
  %emsg = getelementptr [13 x i8], [13 x i8]* @.json.err_invalid, i64 0, i64 0
  %err_r = call i8* @nv_Err(i8* %emsg)
  ret i8* %err_r
}

; ── nv_json_is_null(val) → i1 ────────────────────────────────────────────────
; Returns true if val (after whitespace skip) starts with 'n','u','l','l'.
define i1 @nv_json_is_null(i8* %val) {
entry:
  %p  = call i8* @nv_json_skip_ws(i8* %val)
  %c0 = load i8, i8* %p, align 1
  %p1 = getelementptr i8, i8* %p, i64 1
  %c1 = load i8, i8* %p1, align 1
  %p2 = getelementptr i8, i8* %p, i64 2
  %c2 = load i8, i8* %p2, align 1
  %p3 = getelementptr i8, i8* %p, i64 3
  %c3 = load i8, i8* %p3, align 1
  %ok0 = icmp eq i8 %c0, 110
  %ok1 = icmp eq i8 %c1, 117
  %ok2 = icmp eq i8 %c2, 108
  %ok3 = icmp eq i8 %c3, 108
  %a01 = and i1 %ok0, %ok1
  %a23 = and i1 %ok2, %ok3
  %r   = and i1 %a01, %a23
  ret i1 %r
}

; ── nv_json_get_field(obj, key) → i8* or null ────────────────────────────────
; Searches obj for a JSON key-value pair. Returns a heap-allocated string
; containing the raw JSON value, or null if key is not found.
; Uses strstr with retry loop to handle false positives in string values.
; Known limitation: cannot distinguish key appearing as substring of another key.
define i8* @nv_json_get_field(i8* %obj, i8* %key) {
entry:
  ; Build search pattern: '"' key '"'
  %klen    = call i64 @strlen(i8* %key)
  %buflen  = add i64 %klen, 3
  %search  = call i8* @malloc(i64 %buflen)
  store i8 34, i8* %search, align 1
  %pk = getelementptr i8, i8* %search, i64 1
  call i8* @memcpy(i8* %pk, i8* %key, i64 %klen)
  %cidx = add i64 %klen, 1
  %pc   = getelementptr i8, i8* %search, i64 %cidx
  store i8 34, i8* %pc, align 1
  %nidx = add i64 %klen, 2
  %pn   = getelementptr i8, i8* %search, i64 %nidx
  store i8 0, i8* %pn, align 1
  ; Pattern length: '"' + key + '"' = klen + 2
  %plen = add i64 %klen, 2
  br label %search_loop
search_loop:
  %from = phi i8* [ %obj, %entry ], [ %from_next, %retry ]
  %pos  = call i8* @strstr(i8* %from, i8* %search)
  %hit  = icmp ne i8* %pos, null
  br i1 %hit, label %verify_colon, label %miss
miss:
  call void @free(i8* %search)
  ret i8* null
verify_colon:
  ; Advance past the pattern and skip whitespace
  %after_pat = getelementptr i8, i8* %pos, i64 %plen
  %ws_ptr    = call i8* @nv_json_skip_ws(i8* %after_pat)
  %cc        = load i8, i8* %ws_ptr, align 1
  %is_colon  = icmp eq i8 %cc, 58
  br i1 %is_colon, label %extract, label %retry
retry:
  %from_next = getelementptr i8, i8* %pos, i64 1
  br label %search_loop
extract:
  %after_colon = getelementptr i8, i8* %ws_ptr, i64 1
  %val_start   = call i8* @nv_json_skip_ws(i8* %after_colon)
  %val_end     = call i8* @nv_json_skip_value(i8* %val_start)
  %vs_i = ptrtoint i8* %val_start to i64
  %ve_i = ptrtoint i8* %val_end   to i64
  %vlen = sub i64 %ve_i, %vs_i
  %result = call i8* @nv_json_substr(i8* %val_start, i64 %vlen)
  call void @free(i8* %search)
  ret i8* %result
}

; ── nv_json_get_index(arr, idx) → i8* or null ────────────────────────────────
; Returns a heap-allocated string containing the raw JSON value at position idx
; in the JSON array arr, or null if idx is out of bounds.
define i8* @nv_json_get_index(i8* %arr, i64 %idx) {
entry:
  %ap   = call i8* @nv_json_skip_ws(i8* %arr)
  %ac   = load i8, i8* %ap, align 1
  %is_b = icmp eq i8 %ac, 91
  br i1 %is_b, label %start, label %bad
bad:
  ret i8* null
start:
  %p0 = getelementptr i8, i8* %ap, i64 1
  br label %loop
loop:
  %cur   = phi i8* [ %p0,       %start ], [ %next_cur, %advance ]
  %count = phi i64  [ 0,         %start ], [ %nc2, %advance ]
  %cw    = call i8* @nv_json_skip_ws(i8* %cur)
  %cc2   = load i8, i8* %cw, align 1
  %is_end = icmp eq i8 %cc2, 93
  br i1 %is_end, label %oob, label %check
check:
  %match = icmp eq i64 %count, %idx
  br i1 %match, label %found, label %skip
found:
  %vend  = call i8* @nv_json_skip_value(i8* %cw)
  %vi    = ptrtoint i8* %cw   to i64
  %vei   = ptrtoint i8* %vend to i64
  %vl    = sub i64 %vei, %vi
  %res   = call i8* @nv_json_substr(i8* %cw, i64 %vl)
  ret i8* %res
skip:
  %av   = call i8* @nv_json_skip_value(i8* %cw)
  %aw   = call i8* @nv_json_skip_ws(i8* %av)
  %sc   = load i8, i8* %aw, align 1
  %is_comma = icmp eq i8 %sc, 44
  br i1 %is_comma, label %advance, label %oob
advance:
  %nc2      = add i64 %count, 1
  %next_raw = getelementptr i8, i8* %aw, i64 1
  %next_cur = call i8* @nv_json_skip_ws(i8* %next_raw)
  br label %loop
oob:
  ret i8* null
}

; ── nv_json_array_len(arr) → i64 ─────────────────────────────────────────────
; Returns the number of elements in the JSON array arr.
define i64 @nv_json_array_len(i8* %arr) {
entry:
  %ap  = call i8* @nv_json_skip_ws(i8* %arr)
  %ac  = load i8, i8* %ap, align 1
  %isb = icmp eq i8 %ac, 91
  br i1 %isb, label %start, label %bad
bad:
  ret i64 0
start:
  %p1  = getelementptr i8, i8* %ap, i64 1
  %pw  = call i8* @nv_json_skip_ws(i8* %p1)
  %c1  = load i8, i8* %pw, align 1
  %emp = icmp eq i8 %c1, 93
  br i1 %emp, label %zero, label %count_loop
zero:
  ret i64 0
count_loop:
  %cur = phi i8* [ %pw, %start ], [ %nc_cur, %advance2 ]
  %cnt = phi i64  [ 0, %start ], [ %nc_cnt, %advance2 ]
  %av2 = call i8* @nv_json_skip_value(i8* %cur)
  %nc_cnt = add i64 %cnt, 1
  %aw2 = call i8* @nv_json_skip_ws(i8* %av2)
  %sc2 = load i8, i8* %aw2, align 1
  %isc2 = icmp eq i8 %sc2, 44
  br i1 %isc2, label %advance2, label %done2
advance2:
  %nr2 = getelementptr i8, i8* %aw2, i64 1
  %nc_cur = call i8* @nv_json_skip_ws(i8* %nr2)
  br label %count_loop
done2:
  ret i64 %nc_cnt
}

; ── nv_json_str_value(val) → i8* ─────────────────────────────────────────────
; Extracts the content of a JSON string value (strips surrounding quotes).
; Returns a heap-allocated copy of the raw unescaped content.
; If val is not a JSON string, returns val unchanged.
define i8* @nv_json_str_value(i8* %val) {
entry:
  %p  = call i8* @nv_json_skip_ws(i8* %val)
  %c  = load i8, i8* %p, align 1
  %iq = icmp eq i8 %c, 34
  br i1 %iq, label %extract, label %passthrough
passthrough:
  ret i8* %val
extract:
  %content_start = getelementptr i8, i8* %p, i64 1
  %end_ptr = call i8* @nv_json_skip_string(i8* %p)
  ; end_ptr is past the closing '"'; content ends at end_ptr - 1
  %cs_i = ptrtoint i8* %content_start to i64
  %ce_i = ptrtoint i8* %end_ptr to i64
  %len  = sub i64 %ce_i, %cs_i
  ; subtract 1 to exclude the closing '"'
  %clen = sub i64 %len, 1
  %neg  = icmp slt i64 %clen, 0
  %safe = select i1 %neg, i64 0, i64 %clen
  %res  = call i8* @nv_json_substr(i8* %content_start, i64 %safe)
  ret i8* %res
}

; ── nv_json_int_value(val) → i64 ─────────────────────────────────────────────
; Parses val as a JSON integer. Returns 0 on error.
define i64 @nv_json_int_value(i8* %val) {
entry:
  %p = call i8* @nv_json_skip_ws(i8* %val)
  %r = call i64 @atoll(i8* %p)
  ret i64 %r
}

; ── nv_json_float_value(val) → double ────────────────────────────────────────
; Parses val as a JSON number. Returns 0.0 on error.
define double @nv_json_float_value(i8* %val) {
entry:
  %p = call i8* @nv_json_skip_ws(i8* %val)
  %r = call double @atof(i8* %p)
  ret double %r
}

; ── nv_json_bool_value(val) → i1 ─────────────────────────────────────────────
; Returns true if val starts with 't' (i.e. JSON true), false otherwise.
define i1 @nv_json_bool_value(i8* %val) {
entry:
  %p = call i8* @nv_json_skip_ws(i8* %val)
  %c = load i8, i8* %p, align 1
  %r = icmp eq i8 %c, 116
  ret i1 %r
}

; ── nv_json_stringify(val) → i8* ─────────────────────────────────────────────
; Identity function: JSON values are already stored as strings.
define i8* @nv_json_stringify(i8* %val) {
entry:
  ret i8* %val
}

; ── nv_json_make_string(s) → i8* ─────────────────────────────────────────────
; Wraps s in JSON string delimiters, escaping '"' and '\' characters.
; Returns a heap-allocated JSON string literal.
define i8* @nv_json_make_string(i8* %s) {
entry:
  %slen   = call i64 @strlen(i8* %s)
  %slen2  = mul i64 %slen, 2
  %buflen = add i64 %slen2, 3
  %buf    = call i8* @malloc(i64 %buflen)
  store i8 34, i8* %buf, align 1
  %dp0 = getelementptr i8, i8* %buf, i64 1
  br label %esc_loop
esc_loop:
  %sp = phi i8* [ %s,    %entry    ], [ %sp_adv, %plain ], [ %sp_esc, %esc ]
  %dp = phi i8* [ %dp0,  %entry    ], [ %dp_adv, %plain ], [ %dp_esc, %esc ]
  %sc = load i8, i8* %sp, align 1
  %z  = icmp eq i8 %sc, 0
  br i1 %z, label %esc_done, label %esc_chk
esc_chk:
  %is_q  = icmp eq i8 %sc, 34
  %is_bs = icmp eq i8 %sc, 92
  %needs = or i1 %is_q, %is_bs
  br i1 %needs, label %esc, label %plain
esc:
  store i8 92, i8* %dp, align 1
  %dp1 = getelementptr i8, i8* %dp, i64 1
  store i8 %sc, i8* %dp1, align 1
  %dp_esc = getelementptr i8, i8* %dp1, i64 1
  %sp_esc = getelementptr i8, i8* %sp,  i64 1
  br label %esc_loop
plain:
  store i8 %sc, i8* %dp, align 1
  %dp_adv = getelementptr i8, i8* %dp, i64 1
  %sp_adv = getelementptr i8, i8* %sp, i64 1
  br label %esc_loop
esc_done:
  store i8 34, i8* %dp, align 1
  %dp_end = getelementptr i8, i8* %dp, i64 1
  store i8 0, i8* %dp_end, align 1
  ret i8* %buf
}

; ── nv_json_make_int(n) → i8* ────────────────────────────────────────────────
; Returns a heap-allocated string with the decimal representation of n.
define i8* @nv_json_make_int(i64 %n) {
entry:
  %buf = call i8* @malloc(i64 32)
  %fmt = getelementptr [5 x i8], [5 x i8]* @.json.fmt_ld, i64 0, i64 0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 32, i8* %fmt, i64 %n)
  ret i8* %buf
}

; ── nv_json_make_float(f) → i8* ──────────────────────────────────────────────
; Returns a heap-allocated string with the JSON number representation of f.
define i8* @nv_json_make_float(double %f) {
entry:
  %buf = call i8* @malloc(i64 64)
  %fmt = getelementptr [3 x i8], [3 x i8]* @.json.fmt_g, i64 0, i64 0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 64, i8* %fmt, double %f)
  ret i8* %buf
}

; ── nv_json_make_bool(b) → i8* ───────────────────────────────────────────────
; Returns a pointer to the static string "true" or "false".
define i8* @nv_json_make_bool(i1 %b) {
entry:
  %ts = getelementptr [5 x i8], [5 x i8]* @.json.true_str,  i64 0, i64 0
  %fs = getelementptr [6 x i8], [6 x i8]* @.json.false_str, i64 0, i64 0
  %r  = select i1 %b, i8* %ts, i8* %fs
  ret i8* %r
}

; ── nv_json_make_null() → i8* ────────────────────────────────────────────────
; Returns a pointer to the static string "null".
define i8* @nv_json_make_null() {
entry:
  %r = getelementptr [5 x i8], [5 x i8]* @.json.null_str, i64 0, i64 0
  ret i8* %r
}
""".trimIndent())
    }
}
