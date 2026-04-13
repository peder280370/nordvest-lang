package nv.compiler.codegen.runtime

/**
 * std.collections runtime: array push/contains/indexOf, map new/get/set/has.
 * Array layout: {i64 count, element_data...}  (data starts at offset 8)
 * Map layout:   {i64 count, [key_ptr, val_ptr] pairs...} (pairs at offset 8, 16 bytes each)
 */
internal object CollectionsRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.collections ──────────────────────────────────────────────────────────

; nv_arr_push_i64(arr, val) → i8*  (returns new heap array with val appended)
define i8* @nv_arr_push_i64(i8* %arr, i64 %val) {
entry:
  %cnt_ptr = bitcast i8* %arr to i64*
  %count = load i64, i64* %cnt_ptr, align 8
  %new_count = add i64 %count, 1
  %data_size = mul i64 %new_count, 8
  %total = add i64 8, %data_size
  %new_arr = call i8* @malloc(i64 %total)
  %np = bitcast i8* %new_arr to i64*
  store i64 %new_count, i64* %np, align 8
  %i = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %psi.lp
psi.lp:
  %ii = load i64, i64* %i, align 8
  %done = icmp sge i64 %ii, %count
  br i1 %done, label %psi.add, label %psi.copy
psi.copy:
  %src_d = mul i64 %ii, 8
  %src_off = add i64 8, %src_d
  %srcp = getelementptr i8, i8* %arr, i64 %src_off
  %srcp64 = bitcast i8* %srcp to i64*
  %elem = load i64, i64* %srcp64, align 8
  %dstp = getelementptr i8, i8* %new_arr, i64 %src_off
  %dstp64 = bitcast i8* %dstp to i64*
  store i64 %elem, i64* %dstp64, align 8
  %ii1 = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %psi.lp
psi.add:
  %elem_d = mul i64 %count, 8
  %elem_off = add i64 8, %elem_d
  %elemp = getelementptr i8, i8* %new_arr, i64 %elem_off
  %elemp64 = bitcast i8* %elemp to i64*
  store i64 %val, i64* %elemp64, align 8
  ret i8* %new_arr
}

; nv_arr_push_str(arr, val) → i8*  (str elements are pointer-sized = 8 bytes)
define i8* @nv_arr_push_str(i8* %arr, i8* %val) {
entry:
  %cnt_ptr = bitcast i8* %arr to i64*
  %count = load i64, i64* %cnt_ptr, align 8
  %new_count = add i64 %count, 1
  %data_size = mul i64 %new_count, 8
  %total = add i64 8, %data_size
  %new_arr = call i8* @malloc(i64 %total)
  %np = bitcast i8* %new_arr to i64*
  store i64 %new_count, i64* %np, align 8
  %i = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %pss.lp
pss.lp:
  %ii = load i64, i64* %i, align 8
  %done = icmp sge i64 %ii, %count
  br i1 %done, label %pss.add, label %pss.copy
pss.copy:
  %src_d = mul i64 %ii, 8
  %src_off = add i64 8, %src_d
  %srcp = getelementptr i8, i8* %arr, i64 %src_off
  %srcp_pp = bitcast i8* %srcp to i8**
  %elem = load i8*, i8** %srcp_pp, align 8
  %dstp = getelementptr i8, i8* %new_arr, i64 %src_off
  %dstp_pp = bitcast i8* %dstp to i8**
  store i8* %elem, i8** %dstp_pp, align 8
  %ii1 = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %pss.lp
pss.add:
  %elem_d = mul i64 %count, 8
  %elem_off = add i64 8, %elem_d
  %elemp = getelementptr i8, i8* %new_arr, i64 %elem_off
  %elemp_pp = bitcast i8* %elemp to i8**
  store i8* %val, i8** %elemp_pp, align 8
  ret i8* %new_arr
}

; nv_arr_contains_i64(arr, val) → i1
define i1 @nv_arr_contains_i64(i8* %arr, i64 %val) {
entry:
  %cnt_ptr = bitcast i8* %arr to i64*
  %count = load i64, i64* %cnt_ptr, align 8
  %i = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %cni.lp
cni.lp:
  %ii = load i64, i64* %i, align 8
  %done = icmp sge i64 %ii, %count
  br i1 %done, label %cni.no, label %cni.chk
cni.chk:
  %off_d = mul i64 %ii, 8
  %off = add i64 8, %off_d
  %ep = getelementptr i8, i8* %arr, i64 %off
  %ep64 = bitcast i8* %ep to i64*
  %elem = load i64, i64* %ep64, align 8
  %match = icmp eq i64 %elem, %val
  br i1 %match, label %cni.yes, label %cni.next
cni.next:
  %ii1 = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %cni.lp
cni.yes:
  ret i1 1
cni.no:
  ret i1 0
}

; nv_arr_contains_str(arr, val) → i1
define i1 @nv_arr_contains_str(i8* %arr, i8* %val) {
entry:
  %cnt_ptr = bitcast i8* %arr to i64*
  %count = load i64, i64* %cnt_ptr, align 8
  %i = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %cns.lp
cns.lp:
  %ii = load i64, i64* %i, align 8
  %done = icmp sge i64 %ii, %count
  br i1 %done, label %cns.no, label %cns.chk
cns.chk:
  %off_d = mul i64 %ii, 8
  %off = add i64 8, %off_d
  %ep = getelementptr i8, i8* %arr, i64 %off
  %ep_pp = bitcast i8* %ep to i8**
  %elem = load i8*, i8** %ep_pp, align 8
  %r = call i32 @strcmp(i8* %elem, i8* %val)
  %match = icmp eq i32 %r, 0
  br i1 %match, label %cns.yes, label %cns.next
cns.next:
  %ii1 = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %cns.lp
cns.yes:
  ret i1 1
cns.no:
  ret i1 0
}

; nv_arr_index_of_i64(arr, val) → i64  (-1 if not found)
define i64 @nv_arr_index_of_i64(i8* %arr, i64 %val) {
entry:
  %cnt_ptr = bitcast i8* %arr to i64*
  %count = load i64, i64* %cnt_ptr, align 8
  %i = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %ioi.lp
ioi.lp:
  %ii = load i64, i64* %i, align 8
  %done = icmp sge i64 %ii, %count
  br i1 %done, label %ioi.no, label %ioi.chk
ioi.chk:
  %off_d = mul i64 %ii, 8
  %off = add i64 8, %off_d
  %ep = getelementptr i8, i8* %arr, i64 %off
  %ep64 = bitcast i8* %ep to i64*
  %elem = load i64, i64* %ep64, align 8
  %match = icmp eq i64 %elem, %val
  br i1 %match, label %ioi.yes, label %ioi.next
ioi.next:
  %ii1 = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %ioi.lp
ioi.yes:
  %idx = load i64, i64* %i, align 8
  ret i64 %idx
ioi.no:
  ret i64 -1
}

; nv_arr_index_of_str(arr, val) → i64  (-1 if not found)
define i64 @nv_arr_index_of_str(i8* %arr, i8* %val) {
entry:
  %cnt_ptr = bitcast i8* %arr to i64*
  %count = load i64, i64* %cnt_ptr, align 8
  %i = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %ios.lp
ios.lp:
  %ii = load i64, i64* %i, align 8
  %done = icmp sge i64 %ii, %count
  br i1 %done, label %ios.no, label %ios.chk
ios.chk:
  %off_d = mul i64 %ii, 8
  %off = add i64 8, %off_d
  %ep = getelementptr i8, i8* %arr, i64 %off
  %ep_pp = bitcast i8* %ep to i8**
  %elem = load i8*, i8** %ep_pp, align 8
  %r = call i32 @strcmp(i8* %elem, i8* %val)
  %match = icmp eq i32 %r, 0
  br i1 %match, label %ios.yes, label %ios.next
ios.next:
  %ii1 = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %ios.lp
ios.yes:
  %idx = load i64, i64* %i, align 8
  ret i64 %idx
ios.no:
  ret i64 -1
}

; ── Map operations (str keys, str values) ────────────────────────────────────
; Map layout: {i64 count, [key_ptr i8*, val_ptr i8*] pairs...}
; Pairs start at byte offset 8; each pair is 16 bytes.

; nv_map_new() → i8*
define i8* @nv_map_new() {
entry:
  %m = call i8* @malloc(i64 8)
  %mp = bitcast i8* %m to i64*
  store i64 0, i64* %mp, align 8
  ret i8* %m
}

; nv_map_len(map) → i64
define i64 @nv_map_len(i8* %map) {
entry:
  %mp = bitcast i8* %map to i64*
  %count = load i64, i64* %mp, align 8
  ret i64 %count
}

; nv_map_get_str(map, key) → i8*  (null if not found)
define i8* @nv_map_get_str(i8* %map, i8* %key) {
entry:
  %mp = bitcast i8* %map to i64*
  %count = load i64, i64* %mp, align 8
  %i = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %mgs.lp
mgs.lp:
  %ii = load i64, i64* %i, align 8
  %done = icmp sge i64 %ii, %count
  br i1 %done, label %mgs.null, label %mgs.chk
mgs.chk:
  %koff_d = mul i64 %ii, 16
  %koff = add i64 8, %koff_d
  %kp = getelementptr i8, i8* %map, i64 %koff
  %kpp = bitcast i8* %kp to i8**
  %k = load i8*, i8** %kpp, align 8
  %r = call i32 @strcmp(i8* %k, i8* %key)
  %match = icmp eq i32 %r, 0
  br i1 %match, label %mgs.found, label %mgs.next
mgs.found:
  %voff_d = mul i64 %ii, 16
  %voff = add i64 16, %voff_d
  %vp = getelementptr i8, i8* %map, i64 %voff
  %vpp = bitcast i8* %vp to i8**
  %v = load i8*, i8** %vpp, align 8
  ret i8* %v
mgs.next:
  %ii1 = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %mgs.lp
mgs.null:
  ret i8* null
}

; nv_map_has_str(map, key) → i1
define i1 @nv_map_has_str(i8* %map, i8* %key) {
entry:
  %r = call i8* @nv_map_get_str(i8* %map, i8* %key)
  %found = icmp ne i8* %r, null
  ret i1 %found
}

; nv_map_set_str(map, key, val) → i8*  (returns same or new map pointer)
define i8* @nv_map_set_str(i8* %map, i8* %key, i8* %val) {
entry:
  %mp = bitcast i8* %map to i64*
  %count = load i64, i64* %mp, align 8
  %i = alloca i64, align 8
  %a_ci = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %mss.srch
mss.srch:
  %ii = load i64, i64* %i, align 8
  %done = icmp sge i64 %ii, %count
  br i1 %done, label %mss.insert, label %mss.chk
mss.chk:
  %koff_d = mul i64 %ii, 16
  %koff = add i64 8, %koff_d
  %kp = getelementptr i8, i8* %map, i64 %koff
  %kpp = bitcast i8* %kp to i8**
  %k = load i8*, i8** %kpp, align 8
  %r = call i32 @strcmp(i8* %k, i8* %key)
  %match = icmp eq i32 %r, 0
  br i1 %match, label %mss.update, label %mss.next
mss.update:
  %voff_d = mul i64 %ii, 16
  %voff = add i64 16, %voff_d
  %vp = getelementptr i8, i8* %map, i64 %voff
  %vpp = bitcast i8* %vp to i8**
  store i8* %val, i8** %vpp, align 8
  ret i8* %map
mss.next:
  %ii1 = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %mss.srch
mss.insert:
  %new_count = add i64 %count, 1
  %nc16 = mul i64 %new_count, 16
  %new_size = add i64 8, %nc16
  %new_map = call i8* @malloc(i64 %new_size)
  %old_d = mul i64 %count, 16
  %old_size = add i64 8, %old_d
  store i64 0, i64* %a_ci, align 8
  br label %mss.cp
mss.cp:
  %cii = load i64, i64* %a_ci, align 8
  %cp_done = icmp sge i64 %cii, %old_size
  br i1 %cp_done, label %mss.store, label %mss.cp.body
mss.cp.body:
  %csp = getelementptr i8, i8* %map, i64 %cii
  %cch = load i8, i8* %csp, align 1
  %cdp = getelementptr i8, i8* %new_map, i64 %cii
  store i8 %cch, i8* %cdp, align 1
  %cii1 = add i64 %cii, 1
  store i64 %cii1, i64* %a_ci, align 8
  br label %mss.cp
mss.store:
  %nmp = bitcast i8* %new_map to i64*
  store i64 %new_count, i64* %nmp, align 8
  %kn_d = mul i64 %count, 16
  %kn_base = add i64 8, %kn_d
  %kn_p = getelementptr i8, i8* %new_map, i64 %kn_base
  %kn_pp = bitcast i8* %kn_p to i8**
  store i8* %key, i8** %kn_pp, align 8
  %vn_base = add i64 16, %kn_d
  %vn_p = getelementptr i8, i8* %new_map, i64 %vn_base
  %vn_pp = bitcast i8* %vn_p to i8**
  store i8* %val, i8** %vn_pp, align 8
  ret i8* %new_map
}

; ── first / last ──────────────────────────────────────────────────────────────

; nv_arr_first_i64(arr) → i64  (0 if empty)
define i64 @nv_arr_first_i64(i8* %arr) {
entry:
  %cp  = bitcast i8* %arr to i64*
  %cnt = load i64, i64* %cp, align 8
  %ok  = icmp sgt i64 %cnt, 0
  br i1 %ok, label %fi.yes, label %fi.no
fi.yes:
  %ep  = getelementptr i64, i64* %cp, i64 1
  %v   = load i64, i64* %ep, align 8
  ret i64 %v
fi.no:
  ret i64 0
}

; nv_arr_last_i64(arr) → i64  (0 if empty)
define i64 @nv_arr_last_i64(i8* %arr) {
entry:
  %cp  = bitcast i8* %arr to i64*
  %cnt = load i64, i64* %cp, align 8
  %ok  = icmp sgt i64 %cnt, 0
  br i1 %ok, label %la.yes, label %la.no
la.yes:
  %idx = sub i64 %cnt, 1
  %off = add i64 %idx, 1
  %ep  = getelementptr i64, i64* %cp, i64 %off
  %v   = load i64, i64* %ep, align 8
  ret i64 %v
la.no:
  ret i64 0
}

; nv_arr_first_str(arr) → i8*  (empty string if empty)
define i8* @nv_arr_first_str(i8* %arr) {
entry:
  %cp  = bitcast i8* %arr to i64*
  %cnt = load i64, i64* %cp, align 8
  %ok  = icmp sgt i64 %cnt, 0
  br i1 %ok, label %fs.yes, label %fs.no
fs.yes:
  %ep  = getelementptr i64, i64* %cp, i64 1
  %epp = bitcast i64* %ep to i8**
  %v   = load i8*, i8** %epp, align 8
  ret i8* %v
fs.no:
  %e = call i8* @malloc(i64 1)
  store i8 0, i8* %e, align 1
  ret i8* %e
}

; nv_arr_last_str(arr) → i8*  (empty string if empty)
define i8* @nv_arr_last_str(i8* %arr) {
entry:
  %cp  = bitcast i8* %arr to i64*
  %cnt = load i64, i64* %cp, align 8
  %ok  = icmp sgt i64 %cnt, 0
  br i1 %ok, label %ls.yes, label %ls.no
ls.yes:
  %idx = sub i64 %cnt, 1
  %off = add i64 %idx, 1
  %ep  = getelementptr i64, i64* %cp, i64 %off
  %epp = bitcast i64* %ep to i8**
  %v   = load i8*, i8** %epp, align 8
  ret i8* %v
ls.no:
  %e = call i8* @malloc(i64 1)
  store i8 0, i8* %e, align 1
  ret i8* %e
}

; ── reverse ───────────────────────────────────────────────────────────────────

; nv_arr_reverse_i64(arr) → i8*
define i8* @nv_arr_reverse_i64(i8* %arr) {
entry:
  %cp   = bitcast i8* %arr to i64*
  %cnt  = load i64, i64* %cp, align 8
  %sz0  = mul i64 %cnt, 8
  %sz   = add i64 %sz0, 8
  %out  = call i8* @malloc(i64 %sz)
  %op   = bitcast i8* %out to i64*
  store i64 %cnt, i64* %op, align 8
  %i    = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %rvi.lp
rvi.lp:
  %ii   = load i64, i64* %i, align 8
  %done = icmp eq i64 %ii, %cnt
  br i1 %done, label %rvi.done, label %rvi.body
rvi.body:
  %src_idx = sub i64 %cnt, %ii
  %src_off = add i64 0, %src_idx
  %srcp = getelementptr i64, i64* %cp, i64 %src_off
  %v    = load i64, i64* %srcp, align 8
  %dst_off = add i64 %ii, 1
  %dstp = getelementptr i64, i64* %op, i64 %dst_off
  store i64 %v, i64* %dstp, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %rvi.lp
rvi.done:
  ret i8* %out
}

; nv_arr_reverse_str(arr) → i8*
define i8* @nv_arr_reverse_str(i8* %arr) {
entry:
  %cp   = bitcast i8* %arr to i64*
  %cnt  = load i64, i64* %cp, align 8
  %sz0  = mul i64 %cnt, 8
  %sz   = add i64 %sz0, 8
  %out  = call i8* @malloc(i64 %sz)
  %op   = bitcast i8* %out to i64*
  store i64 %cnt, i64* %op, align 8
  %i    = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %rvs.lp
rvs.lp:
  %ii   = load i64, i64* %i, align 8
  %done = icmp eq i64 %ii, %cnt
  br i1 %done, label %rvs.done, label %rvs.body
rvs.body:
  %src_idx = sub i64 %cnt, %ii
  %srcp = getelementptr i64, i64* %cp, i64 %src_idx
  %srcpp = bitcast i64* %srcp to i8**
  %v    = load i8*, i8** %srcpp, align 8
  %dst_off = add i64 %ii, 1
  %dstp = getelementptr i64, i64* %op, i64 %dst_off
  %dstpp = bitcast i64* %dstp to i8**
  store i8* %v, i8** %dstpp, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %rvs.lp
rvs.done:
  ret i8* %out
}

; ── slice ─────────────────────────────────────────────────────────────────────

; nv_arr_slice_i64(arr, from, to) → i8*
define i8* @nv_arr_slice_i64(i8* %arr, i64 %from, i64 %to) {
entry:
  %cp   = bitcast i8* %arr to i64*
  %cnt  = load i64, i64* %cp, align 8
  %f0   = icmp slt i64 %from, 0
  %f1   = select i1 %f0, i64 0, i64 %from
  %t0   = icmp sgt i64 %to, %cnt
  %t1   = select i1 %t0, i64 %cnt, i64 %to
  %f2   = icmp sgt i64 %f1, %t1
  %f3   = select i1 %f2, i64 %t1, i64 %f1
  %ncnt = sub i64 %t1, %f3
  %ok   = icmp sgt i64 %ncnt, 0
  %cnt2 = select i1 %ok, i64 %ncnt, i64 0
  %sz0  = mul i64 %cnt2, 8
  %sz   = add i64 %sz0, 8
  %out  = call i8* @malloc(i64 %sz)
  %op   = bitcast i8* %out to i64*
  store i64 %cnt2, i64* %op, align 8
  %i    = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %sli.lp
sli.lp:
  %ii   = load i64, i64* %i, align 8
  %done = icmp eq i64 %ii, %cnt2
  br i1 %done, label %sli.done, label %sli.body
sli.body:
  %sidx = add i64 %f3, %ii
  %soff = add i64 %sidx, 1
  %srcp = getelementptr i64, i64* %cp, i64 %soff
  %v    = load i64, i64* %srcp, align 8
  %doff = add i64 %ii, 1
  %dstp = getelementptr i64, i64* %op, i64 %doff
  store i64 %v, i64* %dstp, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %sli.lp
sli.done:
  ret i8* %out
}

; nv_arr_slice_str(arr, from, to) → i8*
define i8* @nv_arr_slice_str(i8* %arr, i64 %from, i64 %to) {
entry:
  %cp   = bitcast i8* %arr to i64*
  %cnt  = load i64, i64* %cp, align 8
  %f0   = icmp slt i64 %from, 0
  %f1   = select i1 %f0, i64 0, i64 %from
  %t0   = icmp sgt i64 %to, %cnt
  %t1   = select i1 %t0, i64 %cnt, i64 %to
  %f2   = icmp sgt i64 %f1, %t1
  %f3   = select i1 %f2, i64 %t1, i64 %f1
  %ncnt = sub i64 %t1, %f3
  %ok   = icmp sgt i64 %ncnt, 0
  %cnt2 = select i1 %ok, i64 %ncnt, i64 0
  %sz0  = mul i64 %cnt2, 8
  %sz   = add i64 %sz0, 8
  %out  = call i8* @malloc(i64 %sz)
  %op   = bitcast i8* %out to i64*
  store i64 %cnt2, i64* %op, align 8
  %i    = alloca i64, align 8
  store i64 0, i64* %i, align 8
  br label %sls.lp
sls.lp:
  %ii   = load i64, i64* %i, align 8
  %done = icmp eq i64 %ii, %cnt2
  br i1 %done, label %sls.done, label %sls.body
sls.body:
  %sidx = add i64 %f3, %ii
  %soff = add i64 %sidx, 1
  %srcp = getelementptr i64, i64* %cp, i64 %soff
  %srcpp = bitcast i64* %srcp to i8**
  %v    = load i8*, i8** %srcpp, align 8
  %doff = add i64 %ii, 1
  %dstp = getelementptr i64, i64* %op, i64 %doff
  %dstpp = bitcast i64* %dstp to i8**
  store i8* %v, i8** %dstpp, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %sls.lp
sls.done:
  ret i8* %out
}

; ── sort ──────────────────────────────────────────────────────────────────────

; nv_arr_sort_i64(arr) → i8*  (insertion sort, ascending)
define i8* @nv_arr_sort_i64(i8* %arr) {
entry:
  %cp   = bitcast i8* %arr to i64*
  %cnt  = load i64, i64* %cp, align 8
  %sz0  = mul i64 %cnt, 8
  %sz   = add i64 %sz0, 8
  %out  = call i8* @malloc(i64 %sz)
  call i8* @memcpy(i8* %out, i8* %arr, i64 %sz)
  %op   = bitcast i8* %out to i64*
  %i    = alloca i64, align 8
  %j    = alloca i64, align 8
  store i64 1, i64* %i, align 8
  br label %sort.outer
sort.outer:
  %ii   = load i64, i64* %i, align 8
  %odone = icmp sge i64 %ii, %cnt
  br i1 %odone, label %sort.done, label %sort.inner_init
sort.inner_init:
  %kp   = getelementptr i64, i64* %op, i64 %ii
  %koff = add i64 %ii, 1
  %kvp  = getelementptr i64, i64* %op, i64 %koff
  %kv   = load i64, i64* %kvp, align 8
  %jj0  = sub i64 %ii, 1
  store i64 %jj0, i64* %j, align 8
  br label %sort.inner
sort.inner:
  %jj   = load i64, i64* %j, align 8
  %jneg = icmp slt i64 %jj, 0
  br i1 %jneg, label %sort.insert, label %sort.cmp
sort.cmp:
  %joff = add i64 %jj, 1
  %ajp  = getelementptr i64, i64* %op, i64 %joff
  %av   = load i64, i64* %ajp, align 8
  %gt   = icmp sgt i64 %av, %kv
  br i1 %gt, label %sort.shift, label %sort.insert
sort.shift:
  %jnext = add i64 %jj, 1
  %doff  = add i64 %jnext, 1
  %dst   = getelementptr i64, i64* %op, i64 %doff
  store i64 %av, i64* %dst, align 8
  %jj1  = sub i64 %jj, 1
  store i64 %jj1, i64* %j, align 8
  br label %sort.inner
sort.insert:
  %ins_j = load i64, i64* %j, align 8
  %ins_off = add i64 %ins_j, 2
  %insp  = getelementptr i64, i64* %op, i64 %ins_off
  store i64 %kv, i64* %insp, align 8
  %ii1  = add i64 %ii, 1
  store i64 %ii1, i64* %i, align 8
  br label %sort.outer
sort.done:
  ret i8* %out
}
""".trimIndent())
    }
}
