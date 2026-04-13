package nv.compiler.codegen.runtime

/** std.hash runtime: FNV-1a, djb2, CRC-32, MurmurHash3, SHA-256, MD5, combine. */
internal object HashRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.hash ─────────────────────────────────────────────────────────────────

; nv_hash_fnv1a(s) → i64  (FNV-1a)
define i64 @nv_hash_fnv1a(i8* %s) {
entry:
  br label %loop
loop:
  %ptr  = phi i8* [ %s, %entry ], [ %np, %body ]
  %hash = phi i64 [ -3750763034362895579, %entry ], [ %nh, %body ]
  %c    = load i8, i8* %ptr, align 1
  %done = icmp eq i8 %c, 0
  br i1 %done, label %ret, label %body
body:
  %b64 = zext i8 %c to i64
  %xd  = xor i64 %hash, %b64
  %nh  = mul i64 %xd, 1099511628211
  %np  = getelementptr i8, i8* %ptr, i64 1
  br label %loop
ret:
  ret i64 %hash
}

; nv_hash_djb2(s) → i64  (djb2: hash*33 + byte)
define i64 @nv_hash_djb2(i8* %s) {
entry:
  br label %loop
loop:
  %ptr  = phi i8* [ %s, %entry ], [ %np, %body ]
  %hash = phi i64 [ 5381, %entry ], [ %nh, %body ]
  %c    = load i8, i8* %ptr, align 1
  %done = icmp eq i8 %c, 0
  br i1 %done, label %ret, label %body
body:
  %b64 = zext i8 %c to i64
  %h33 = mul i64 %hash, 33
  %nh  = add i64 %h33, %b64
  %np  = getelementptr i8, i8* %ptr, i64 1
  br label %loop
ret:
  ret i64 %hash
}

; nv_hash_crc32(s) → i64  (IEEE CRC-32, polynomial 0xEDB88320, bit-by-bit)
; -306674912 = 0xEDB88320 as signed i32
define i64 @nv_hash_crc32(i8* %s) {
entry:
  br label %loop
loop:
  %ptr  = phi i8* [ %s, %entry ], [ %np, %body ]
  %crc  = phi i32 [ -1, %entry ], [ %r7, %body ]
  %c    = load i8, i8* %ptr, align 1
  %done = icmp eq i8 %c, 0
  br i1 %done, label %ret, label %body
body:
  %b8 = zext i8 %c to i32
  %c0 = xor i32 %crc, %b8
  %l0 = and i32 %c0, 1
  %s0 = lshr i32 %c0, 1
  %e0 = icmp ne i32 %l0, 0
  %p0 = select i1 %e0, i32 -306674912, i32 0
  %r0 = xor i32 %s0, %p0
  %l1 = and i32 %r0, 1
  %s1 = lshr i32 %r0, 1
  %e1 = icmp ne i32 %l1, 0
  %p1 = select i1 %e1, i32 -306674912, i32 0
  %r1 = xor i32 %s1, %p1
  %l2 = and i32 %r1, 1
  %s2 = lshr i32 %r1, 1
  %e2 = icmp ne i32 %l2, 0
  %p2 = select i1 %e2, i32 -306674912, i32 0
  %r2 = xor i32 %s2, %p2
  %l3 = and i32 %r2, 1
  %s3 = lshr i32 %r2, 1
  %e3 = icmp ne i32 %l3, 0
  %p3 = select i1 %e3, i32 -306674912, i32 0
  %r3 = xor i32 %s3, %p3
  %l4 = and i32 %r3, 1
  %s4 = lshr i32 %r3, 1
  %e4 = icmp ne i32 %l4, 0
  %p4 = select i1 %e4, i32 -306674912, i32 0
  %r4 = xor i32 %s4, %p4
  %l5 = and i32 %r4, 1
  %s5 = lshr i32 %r4, 1
  %e5 = icmp ne i32 %l5, 0
  %p5 = select i1 %e5, i32 -306674912, i32 0
  %r5 = xor i32 %s5, %p5
  %l6 = and i32 %r5, 1
  %s6 = lshr i32 %r5, 1
  %e6 = icmp ne i32 %l6, 0
  %p6 = select i1 %e6, i32 -306674912, i32 0
  %r6 = xor i32 %s6, %p6
  %l7 = and i32 %r6, 1
  %s7 = lshr i32 %r6, 1
  %e7 = icmp ne i32 %l7, 0
  %p7 = select i1 %e7, i32 -306674912, i32 0
  %r7 = xor i32 %s7, %p7
  %np = getelementptr i8, i8* %ptr, i64 1
  br label %loop
ret:
  %final = xor i32 %crc, -1
  %r64   = zext i32 %final to i64
  ret i64 %r64
}

; nv_hash_combine(h1, h2) → i64
define i64 @nv_hash_combine(i64 %h1, i64 %h2) {
entry:
  %a   = add i64 %h2, 2654435769
  %sl6 = shl i64 %h1, 6
  %sr2 = lshr i64 %h1, 2
  %b   = add i64 %a, %sl6
  %cc  = add i64 %b, %sr2
  %res = xor i64 %h1, %cc
  ret i64 %res
}

; nv_hash_murmur3(s, seed) → i64  (MurmurHash3-inspired 64-bit)
; c1 = 0x87c37b91114253d5 (signed: -8663945395140668459)
; c2 = 0x4cf5ad432745937f (signed:  5545529020109919103)
define i64 @nv_hash_murmur3(i8* %s, i64 %seed) {
entry:
  %len = call i64 @strlen(i8* %s)
  %nblocks = udiv i64 %len, 8
  br label %blkloop
blkloop:
  %bi = phi i64 [ 0, %entry ], [ %binc, %blkbody ]
  %h  = phi i64 [ %seed, %entry ], [ %hnext, %blkbody ]
  %blkdone = icmp eq i64 %bi, %nblocks
  br i1 %blkdone, label %tail, label %blkbody
blkbody:
  %off  = mul i64 %bi, 8
  %bptr = getelementptr i8, i8* %s, i64 %off
  %kp   = bitcast i8* %bptr to i64*
  %kraw = load i64, i64* %kp, align 1
  %k1   = mul i64 %kraw, -8663945395140668459
  %rl31l = shl i64 %k1, 31
  %rl31r = lshr i64 %k1, 33
  %k2   = or i64 %rl31l, %rl31r
  %k3   = mul i64 %k2, 5545529020109919103
  %hxk  = xor i64 %h, %k3
  %rl27l = shl i64 %hxk, 27
  %rl27r = lshr i64 %hxk, 37
  %hrot = or i64 %rl27l, %rl27r
  %h5   = mul i64 %hrot, 5
  %hnext = add i64 %h5, 1390208809
  %binc = add i64 %bi, 1
  br label %blkloop
tail:
  ; accumulate remaining bytes into k, little-endian
  %tail_off = mul i64 %nblocks, 8
  %tail_ptr = getelementptr i8, i8* %s, i64 %tail_off
  %tail_len = urem i64 %len, 8
  br label %tailloop
tailloop:
  %ti  = phi i64 [ 0, %tail ], [ %tinc, %tailbody ]
  %tk  = phi i64 [ 0, %tail ], [ %tknext, %tailbody ]
  %tdone = icmp eq i64 %ti, %tail_len
  br i1 %tdone, label %finalize, label %tailbody
tailbody:
  %tbptr = getelementptr i8, i8* %tail_ptr, i64 %ti
  %tb    = load i8, i8* %tbptr, align 1
  %tb64  = zext i8 %tb to i64
  %shift = mul i64 %ti, 8
  %tb_sh = shl i64 %tb64, %shift
  %tknext = or i64 %tk, %tb_sh
  %tinc  = add i64 %ti, 1
  br label %tailloop
finalize:
  ; Mix tail k into h (if any tail bytes)
  %has_tail = icmp ne i64 %tail_len, 0
  %tk_c1    = mul i64 %tk, -8663945395140668459
  %rl31l2   = shl i64 %tk_c1, 31
  %rl31r2   = lshr i64 %tk_c1, 33
  %tk_rot   = or i64 %rl31l2, %rl31r2
  %tk_c2    = mul i64 %tk_rot, 5545529020109919103
  %h_with_tail = xor i64 %h, %tk_c2
  %h_fin = select i1 %has_tail, i64 %h_with_tail, i64 %h
  ; fmix64
  %hf1  = xor i64 %h_fin, %len
  %hf2  = lshr i64 %hf1, 33
  %hf3  = xor i64 %hf1, %hf2
  %hf4  = mul i64 %hf3, -49064778989728563
  %hf5  = lshr i64 %hf4, 33
  %hf6  = xor i64 %hf4, %hf5
  %hf7  = mul i64 %hf6, -4265267296055464877
  %hf8  = lshr i64 %hf7, 33
  %hf9  = xor i64 %hf7, %hf8
  ret i64 %hf9
}

; ── SHA-256 helper: process one 512-bit block ────────────────────────────────
; nv_sha256_block(h: i32*, blk: i8*)  — updates h[0..7] in place
define void @nv_sha256_block(i32* %h, i8* %blk) {
entry:
  %W = alloca [64 x i32], align 4
  br label %winit
winit:
  %wi   = phi i32 [ 0, %entry ], [ %winc, %winit_body ]
  %wdone = icmp eq i32 %wi, 16
  br i1 %wdone, label %wexp, label %winit_body
winit_body:
  %wi64   = zext i32 %wi to i64
  %byteoff = mul i64 %wi64, 4
  %p0     = getelementptr i8, i8* %blk, i64 %byteoff
  %off1   = add i64 %byteoff, 1
  %off2   = add i64 %byteoff, 2
  %off3   = add i64 %byteoff, 3
  %p1     = getelementptr i8, i8* %blk, i64 %off1
  %p2     = getelementptr i8, i8* %blk, i64 %off2
  %p3     = getelementptr i8, i8* %blk, i64 %off3
  %b0r    = load i8, i8* %p0, align 1
  %b1r    = load i8, i8* %p1, align 1
  %b2r    = load i8, i8* %p2, align 1
  %b3r    = load i8, i8* %p3, align 1
  %b0     = zext i8 %b0r to i32
  %b1     = zext i8 %b1r to i32
  %b2     = zext i8 %b2r to i32
  %b3     = zext i8 %b3r to i32
  %b0s    = shl i32 %b0, 24
  %b1s    = shl i32 %b1, 16
  %b2s    = shl i32 %b2, 8
  %w01    = or i32 %b0s, %b1s
  %w012   = or i32 %w01, %b2s
  %word   = or i32 %w012, %b3
  %Wptr   = getelementptr [64 x i32], [64 x i32]* %W, i64 0, i64 %wi64
  store i32 %word, i32* %Wptr, align 4
  %winc   = add i32 %wi, 1
  br label %winit
wexp:
  %wxi  = phi i32 [ 16, %winit ], [ %wxinc, %wexp_body ]
  %wxdone = icmp eq i32 %wxi, 64
  br i1 %wxdone, label %cinit, label %wexp_body
wexp_body:
  %wxi64  = zext i32 %wxi to i64
  %im15   = sub i32 %wxi, 15
  %im15_64 = zext i32 %im15 to i64
  %Wim15p = getelementptr [64 x i32], [64 x i32]* %W, i64 0, i64 %im15_64
  %wim15  = load i32, i32* %Wim15p, align 4
  %r7lo   = lshr i32 %wim15, 7
  %r7hi   = shl  i32 %wim15, 25
  %r7     = or   i32 %r7lo, %r7hi
  %r18lo  = lshr i32 %wim15, 18
  %r18hi  = shl  i32 %wim15, 14
  %r18    = or   i32 %r18lo, %r18hi
  %sr3    = lshr i32 %wim15, 3
  %s0a    = xor  i32 %r7,  %r18
  %s0     = xor  i32 %s0a, %sr3
  %im2    = sub i32 %wxi, 2
  %im2_64 = zext i32 %im2 to i64
  %Wim2p  = getelementptr [64 x i32], [64 x i32]* %W, i64 0, i64 %im2_64
  %wim2   = load i32, i32* %Wim2p, align 4
  %r17lo  = lshr i32 %wim2, 17
  %r17hi  = shl  i32 %wim2, 15
  %r17    = or   i32 %r17lo, %r17hi
  %r19lo  = lshr i32 %wim2, 19
  %r19hi  = shl  i32 %wim2, 13
  %r19    = or   i32 %r19lo, %r19hi
  %sr10   = lshr i32 %wim2, 10
  %s1a    = xor  i32 %r17, %r19
  %s1     = xor  i32 %s1a, %sr10
  %im16   = sub i32 %wxi, 16
  %im16_64 = zext i32 %im16 to i64
  %Wim16p = getelementptr [64 x i32], [64 x i32]* %W, i64 0, i64 %im16_64
  %wim16  = load i32, i32* %Wim16p, align 4
  %im7    = sub i32 %wxi, 7
  %im7_64 = zext i32 %im7 to i64
  %Wim7p  = getelementptr [64 x i32], [64 x i32]* %W, i64 0, i64 %im7_64
  %wim7   = load i32, i32* %Wim7p, align 4
  %wa     = add i32 %wim16, %s0
  %wb     = add i32 %wa,    %wim7
  %wc     = add i32 %wb,    %s1
  %Wip    = getelementptr [64 x i32], [64 x i32]* %W, i64 0, i64 %wxi64
  store i32 %wc, i32* %Wip, align 4
  %wxinc  = add i32 %wxi, 1
  br label %wexp
cinit:
  %h0 = load i32, i32* %h, align 4
  %h1p = getelementptr i32, i32* %h, i64 1
  %h1 = load i32, i32* %h1p, align 4
  %h2p = getelementptr i32, i32* %h, i64 2
  %h2 = load i32, i32* %h2p, align 4
  %h3p = getelementptr i32, i32* %h, i64 3
  %h3 = load i32, i32* %h3p, align 4
  %h4p = getelementptr i32, i32* %h, i64 4
  %h4 = load i32, i32* %h4p, align 4
  %h5p = getelementptr i32, i32* %h, i64 5
  %h5 = load i32, i32* %h5p, align 4
  %h6p = getelementptr i32, i32* %h, i64 6
  %h6 = load i32, i32* %h6p, align 4
  %h7p = getelementptr i32, i32* %h, i64 7
  %h7 = load i32, i32* %h7p, align 4
  br label %compress
compress:
  %ci   = phi i32 [ 0, %cinit ], [ %cinc, %cbody ]
  %ca   = phi i32 [ %h0, %cinit ], [ %ca_new, %cbody ]
  %cb   = phi i32 [ %h1, %cinit ], [ %ca,     %cbody ]
  %cc   = phi i32 [ %h2, %cinit ], [ %cb,     %cbody ]
  %cd   = phi i32 [ %h3, %cinit ], [ %cc,     %cbody ]
  %ce   = phi i32 [ %h4, %cinit ], [ %ce_new, %cbody ]
  %cf   = phi i32 [ %h5, %cinit ], [ %ce,     %cbody ]
  %cg   = phi i32 [ %h6, %cinit ], [ %cf,     %cbody ]
  %chv  = phi i32 [ %h7, %cinit ], [ %cg,     %cbody ]
  %cdone = icmp eq i32 %ci, 64
  br i1 %cdone, label %cdone_bb, label %cbody
cbody:
  %ci64  = zext i32 %ci to i64
  %Kgep  = getelementptr [64 x i32], [64 x i32]* @sha256_k, i64 0, i64 %ci64
  %K_i   = load i32, i32* %Kgep, align 4
  %Wgep  = getelementptr [64 x i32], [64 x i32]* %W, i64 0, i64 %ci64
  %W_i   = load i32, i32* %Wgep, align 4
  %ep1_r6l  = lshr i32 %ce, 6
  %ep1_r6h  = shl  i32 %ce, 26
  %ep1_r6   = or   i32 %ep1_r6l,  %ep1_r6h
  %ep1_r11l = lshr i32 %ce, 11
  %ep1_r11h = shl  i32 %ce, 21
  %ep1_r11  = or   i32 %ep1_r11l, %ep1_r11h
  %ep1_r25l = lshr i32 %ce, 25
  %ep1_r25h = shl  i32 %ce, 7
  %ep1_r25  = or   i32 %ep1_r25l, %ep1_r25h
  %ep1a  = xor i32 %ep1_r6,  %ep1_r11
  %ep1   = xor i32 %ep1a,    %ep1_r25
  %ch_ef = and  i32 %ce, %cf
  %ne    = xor  i32 %ce, -1
  %ch_ng = and  i32 %ne, %cg
  %ch    = xor  i32 %ch_ef, %ch_ng
  %t1a   = add  i32 %chv, %ep1
  %t1b   = add  i32 %t1a, %ch
  %t1c   = add  i32 %t1b, %K_i
  %t1    = add  i32 %t1c, %W_i
  %ep0_r2l  = lshr i32 %ca, 2
  %ep0_r2h  = shl  i32 %ca, 30
  %ep0_r2   = or   i32 %ep0_r2l,  %ep0_r2h
  %ep0_r13l = lshr i32 %ca, 13
  %ep0_r13h = shl  i32 %ca, 19
  %ep0_r13  = or   i32 %ep0_r13l, %ep0_r13h
  %ep0_r22l = lshr i32 %ca, 22
  %ep0_r22h = shl  i32 %ca, 10
  %ep0_r22  = or   i32 %ep0_r22l, %ep0_r22h
  %ep0a  = xor i32 %ep0_r2,  %ep0_r13
  %ep0   = xor i32 %ep0a,    %ep0_r22
  %maj_ab = and i32 %ca, %cb
  %maj_ac = and i32 %ca, %cc
  %maj_bc = and i32 %cb, %cc
  %maj_a  = xor i32 %maj_ab, %maj_ac
  %maj    = xor i32 %maj_a,  %maj_bc
  %t2     = add i32 %ep0, %maj
  %ca_new = add i32 %t1, %t2
  %ce_new = add i32 %cd, %t1
  %cinc   = add i32 %ci, 1
  br label %compress
cdone_bb:
  %na0 = add i32 %h0, %ca
  %na1 = add i32 %h1, %cb
  %na2 = add i32 %h2, %cc
  %na3 = add i32 %h3, %cd
  %na4 = add i32 %h4, %ce
  %na5 = add i32 %h5, %cf
  %na6 = add i32 %h6, %cg
  %na7 = add i32 %h7, %chv
  store i32 %na0, i32* %h,   align 4
  store i32 %na1, i32* %h1p, align 4
  store i32 %na2, i32* %h2p, align 4
  store i32 %na3, i32* %h3p, align 4
  store i32 %na4, i32* %h4p, align 4
  store i32 %na5, i32* %h5p, align 4
  store i32 %na6, i32* %h6p, align 4
  store i32 %na7, i32* %h7p, align 4
  ret void
}

; nv_hash_sha256(s) → i8*  (64-char hex string, caller must free)
define i8* @nv_hash_sha256(i8* %s) {
entry:
  ; Compute padded message length
  %slen  = call i64 @strlen(i8* %s)
  %lp72  = add i64 %slen, 72
  %nblks = udiv i64 %lp72, 64
  %ml    = mul i64 %nblks, 64
  ; Allocate and zero-fill padded message
  %msg   = call i8* @calloc(i64 %ml, i64 1)
  call i8* @memcpy(i8* noalias %msg, i8* noalias %s, i64 %slen)
  ; Padding: set 0x80 at msg[slen]
  %padp  = getelementptr i8, i8* %msg, i64 %slen
  store i8 -128, i8* %padp, align 1
  ; Append big-endian 64-bit bit-length at msg[ml-8..ml-1]
  %bitlen = mul i64 %slen, 8
  %ml_m8  = sub i64 %ml, 8
  %blen7  = lshr i64 %bitlen, 56
  %blen6  = lshr i64 %bitlen, 48
  %blen5  = lshr i64 %bitlen, 40
  %blen4  = lshr i64 %bitlen, 32
  %blen3  = lshr i64 %bitlen, 24
  %blen2  = lshr i64 %bitlen, 16
  %blen1  = lshr i64 %bitlen, 8
  %by7    = trunc i64 %blen7 to i8
  %by6    = trunc i64 %blen6 to i8
  %by5    = trunc i64 %blen5 to i8
  %by4    = trunc i64 %blen4 to i8
  %by3    = trunc i64 %blen3 to i8
  %by2    = trunc i64 %blen2 to i8
  %by1    = trunc i64 %blen1 to i8
  %by0    = trunc i64 %bitlen to i8
  %pp7 = getelementptr i8, i8* %msg, i64 %ml_m8
  %ml_m7 = add i64 %ml_m8, 1
  %ml_m6 = add i64 %ml_m8, 2
  %ml_m5 = add i64 %ml_m8, 3
  %ml_m4 = add i64 %ml_m8, 4
  %ml_m3 = add i64 %ml_m8, 5
  %ml_m2 = add i64 %ml_m8, 6
  %ml_m1 = add i64 %ml_m8, 7
  %pp6 = getelementptr i8, i8* %msg, i64 %ml_m7
  %pp5 = getelementptr i8, i8* %msg, i64 %ml_m6
  %pp4 = getelementptr i8, i8* %msg, i64 %ml_m5
  %pp3 = getelementptr i8, i8* %msg, i64 %ml_m4
  %pp2 = getelementptr i8, i8* %msg, i64 %ml_m3
  %pp1 = getelementptr i8, i8* %msg, i64 %ml_m2
  %pp0 = getelementptr i8, i8* %msg, i64 %ml_m1
  store i8 %by7, i8* %pp7, align 1
  store i8 %by6, i8* %pp6, align 1
  store i8 %by5, i8* %pp5, align 1
  store i8 %by4, i8* %pp4, align 1
  store i8 %by3, i8* %pp3, align 1
  store i8 %by2, i8* %pp2, align 1
  store i8 %by1, i8* %pp1, align 1
  store i8 %by0, i8* %pp0, align 1
  ; Initialize SHA-256 hash state h[0..7] from @sha256_hinit
  %hstate = alloca [8 x i32], align 4
  %hstate_i8 = bitcast [8 x i32]* %hstate to i8*
  %hinit_p = getelementptr [8 x i32], [8 x i32]* @sha256_hinit, i64 0, i64 0
  %hinit_i8 = bitcast i32* %hinit_p to i8*
  call i8* @memcpy(i8* noalias %hstate_i8, i8* noalias %hinit_i8, i64 32)
  %hptr = getelementptr [8 x i32], [8 x i32]* %hstate, i64 0, i64 0
  ; Process each 64-byte block
  br label %blkloop
blkloop:
  %blk_i = phi i64 [ 0, %entry ], [ %blk_inc, %blk_body ]
  %blk_done = icmp eq i64 %blk_i, %nblks
  br i1 %blk_done, label %output, label %blk_body
blk_body:
  %blk_off = mul i64 %blk_i, 64
  %blk_ptr = getelementptr i8, i8* %msg, i64 %blk_off
  call void @nv_sha256_block(i32* %hptr, i8* %blk_ptr)
  %blk_inc = add i64 %blk_i, 1
  br label %blkloop
output:
  call void @free(i8* %msg)
  %out    = call i8* @malloc(i64 65)
  %fmtp   = getelementptr [5 x i8], [5 x i8]* @.fmt.hex8, i64 0, i64 0
  %h0v    = load i32, i32* %hptr, align 4
  %hp1    = getelementptr i32, i32* %hptr, i64 1
  %h1v    = load i32, i32* %hp1, align 4
  %hp2    = getelementptr i32, i32* %hptr, i64 2
  %h2v    = load i32, i32* %hp2, align 4
  %hp3    = getelementptr i32, i32* %hptr, i64 3
  %h3v    = load i32, i32* %hp3, align 4
  %hp4    = getelementptr i32, i32* %hptr, i64 4
  %h4v    = load i32, i32* %hp4, align 4
  %hp5    = getelementptr i32, i32* %hptr, i64 5
  %h5v    = load i32, i32* %hp5, align 4
  %hp6    = getelementptr i32, i32* %hptr, i64 6
  %h6v    = load i32, i32* %hp6, align 4
  %hp7    = getelementptr i32, i32* %hptr, i64 7
  %h7v    = load i32, i32* %hp7, align 4
  %out0   = getelementptr i8, i8* %out, i64 0
  %out8   = getelementptr i8, i8* %out, i64 8
  %out16  = getelementptr i8, i8* %out, i64 16
  %out24  = getelementptr i8, i8* %out, i64 24
  %out32  = getelementptr i8, i8* %out, i64 32
  %out40  = getelementptr i8, i8* %out, i64 40
  %out48  = getelementptr i8, i8* %out, i64 48
  %out56  = getelementptr i8, i8* %out, i64 56
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %out0,  i64 9, i8* %fmtp, i32 %h0v)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %out8,  i64 9, i8* %fmtp, i32 %h1v)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %out16, i64 9, i8* %fmtp, i32 %h2v)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %out24, i64 9, i8* %fmtp, i32 %h3v)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %out32, i64 9, i8* %fmtp, i32 %h4v)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %out40, i64 9, i8* %fmtp, i32 %h5v)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %out48, i64 9, i8* %fmtp, i32 %h6v)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %out56, i64 9, i8* %fmtp, i32 %h7v)
  %outnul = getelementptr i8, i8* %out, i64 64
  store i8 0, i8* %outnul, align 1
  ret i8* %out
}

; ── MD5 ───────────────────────────────────────────────────────────────────────
; nv_hash_md5(s) → i8*  (32-char hex string, caller must free)
; MD5 uses little-endian byte order throughout.
define i8* @nv_hash_md5(i8* %s) {
entry:
  %slen  = call i64 @strlen(i8* %s)
  %lp72  = add i64 %slen, 72
  %nblks = udiv i64 %lp72, 64
  %ml    = mul i64 %nblks, 64
  %msg   = call i8* @calloc(i64 %ml, i64 1)
  call i8* @memcpy(i8* noalias %msg, i8* noalias %s, i64 %slen)
  %padp  = getelementptr i8, i8* %msg, i64 %slen
  store i8 -128, i8* %padp, align 1
  ; Append little-endian 64-bit bit-length at msg[ml-8..ml-1]
  %bitlen  = mul i64 %slen, 8
  %ml_m8   = sub i64 %ml, 8
  %by0m    = trunc i64 %bitlen to i8
  %bl1     = lshr i64 %bitlen, 8
  %by1m    = trunc i64 %bl1 to i8
  %bl2     = lshr i64 %bitlen, 16
  %by2m    = trunc i64 %bl2 to i8
  %bl3     = lshr i64 %bitlen, 24
  %by3m    = trunc i64 %bl3 to i8
  %bl4     = lshr i64 %bitlen, 32
  %by4m    = trunc i64 %bl4 to i8
  %bl5     = lshr i64 %bitlen, 40
  %by5m    = trunc i64 %bl5 to i8
  %bl6     = lshr i64 %bitlen, 48
  %by6m    = trunc i64 %bl6 to i8
  %bl7     = lshr i64 %bitlen, 56
  %by7m    = trunc i64 %bl7 to i8
  %mp0 = getelementptr i8, i8* %msg, i64 %ml_m8
  %ml7 = add i64 %ml_m8, 1
  %ml6 = add i64 %ml_m8, 2
  %ml5 = add i64 %ml_m8, 3
  %ml4 = add i64 %ml_m8, 4
  %ml3 = add i64 %ml_m8, 5
  %ml2 = add i64 %ml_m8, 6
  %ml1 = add i64 %ml_m8, 7
  %mp1 = getelementptr i8, i8* %msg, i64 %ml7
  %mp2 = getelementptr i8, i8* %msg, i64 %ml6
  %mp3 = getelementptr i8, i8* %msg, i64 %ml5
  %mp4 = getelementptr i8, i8* %msg, i64 %ml4
  %mp5 = getelementptr i8, i8* %msg, i64 %ml3
  %mp6 = getelementptr i8, i8* %msg, i64 %ml2
  %mp7 = getelementptr i8, i8* %msg, i64 %ml1
  store i8 %by0m, i8* %mp0, align 1
  store i8 %by1m, i8* %mp1, align 1
  store i8 %by2m, i8* %mp2, align 1
  store i8 %by3m, i8* %mp3, align 1
  store i8 %by4m, i8* %mp4, align 1
  store i8 %by5m, i8* %mp5, align 1
  store i8 %by6m, i8* %mp6, align 1
  store i8 %by7m, i8* %mp7, align 1
  ; Initial MD5 state
  %ma0 = alloca i32, align 4
  %mb0 = alloca i32, align 4
  %mc0 = alloca i32, align 4
  %md0 = alloca i32, align 4
  store i32 1732584193,  i32* %ma0, align 4
  store i32 -271733879,  i32* %mb0, align 4
  store i32 -1732584194, i32* %mc0, align 4
  store i32 271733878,   i32* %md0, align 4
  br label %md5_blkloop
md5_blkloop:
  %mbi  = phi i64 [ 0, %entry ], [ %mbinc, %md5_add_back ]
  %mbdone = icmp eq i64 %mbi, %nblks
  br i1 %mbdone, label %md5_output, label %md5_blk_init
md5_blk_init:
  ; Load M[0..15] as little-endian i32 from current block
  %mblk_off = mul i64 %mbi, 64
  %mblk_ptr = getelementptr i8, i8* %msg, i64 %mblk_off
  ; Load 16 words (little-endian)
  %mM = alloca [16 x i32], align 4
  br label %md5_loadM
md5_loadM:
  %mli   = phi i32 [ 0, %md5_blk_init ], [ %mlinc, %md5_loadM_body ]
  %mldone = icmp eq i32 %mli, 16
  br i1 %mldone, label %md5_compress_init, label %md5_loadM_body
md5_loadM_body:
  %mli64  = zext i32 %mli to i64
  %mloff  = mul i64 %mli64, 4
  %ml_p0  = getelementptr i8, i8* %mblk_ptr, i64 %mloff
  %ml_off1 = add i64 %mloff, 1
  %ml_off2 = add i64 %mloff, 2
  %ml_off3 = add i64 %mloff, 3
  %ml_p1  = getelementptr i8, i8* %mblk_ptr, i64 %ml_off1
  %ml_p2  = getelementptr i8, i8* %mblk_ptr, i64 %ml_off2
  %ml_p3  = getelementptr i8, i8* %mblk_ptr, i64 %ml_off3
  %mlb0   = load i8, i8* %ml_p0, align 1
  %mlb1   = load i8, i8* %ml_p1, align 1
  %mlb2   = load i8, i8* %ml_p2, align 1
  %mlb3   = load i8, i8* %ml_p3, align 1
  %mlw0   = zext i8 %mlb0 to i32
  %mlw1   = zext i8 %mlb1 to i32
  %mlw2   = zext i8 %mlb2 to i32
  %mlw3   = zext i8 %mlb3 to i32
  %mlw1s  = shl i32 %mlw1, 8
  %mlw2s  = shl i32 %mlw2, 16
  %mlw3s  = shl i32 %mlw3, 24
  %mlword01 = or i32 %mlw0, %mlw1s
  %mlword012 = or i32 %mlword01, %mlw2s
  %mlword = or i32 %mlword012, %mlw3s
  %mMp    = getelementptr [16 x i32], [16 x i32]* %mM, i64 0, i64 %mli64
  store i32 %mlword, i32* %mMp, align 4
  %mlinc  = add i32 %mli, 1
  br label %md5_loadM
md5_compress_init:
  %A0 = load i32, i32* %ma0, align 4
  %B0 = load i32, i32* %mb0, align 4
  %C0 = load i32, i32* %mc0, align 4
  %D0 = load i32, i32* %md0, align 4
  br label %md5_compress
md5_compress:
  %mci  = phi i32 [ 0, %md5_compress_init ], [ %mcinc, %md5_cbody ]
  %mA   = phi i32 [ %A0, %md5_compress_init ], [ %mD,   %md5_cbody ]
  %mB   = phi i32 [ %B0, %md5_compress_init ], [ %mnew, %md5_cbody ]
  %mC   = phi i32 [ %C0, %md5_compress_init ], [ %mB,   %md5_cbody ]
  %mD   = phi i32 [ %D0, %md5_compress_init ], [ %mC,   %md5_cbody ]
  %mcdone = icmp eq i32 %mci, 64
  br i1 %mcdone, label %md5_add_back, label %md5_cbody
md5_cbody:
  %mci64 = zext i32 %mci to i64
  ; Compute F and g based on round
  ; Round 0-15: F=(B&C)|(~B&D), g=i
  ; Round 16-31: F=(D&B)|(~D&C), g=(5*i+1)%16
  ; Round 32-47: F=B^C^D,        g=(3*i+5)%16
  ; Round 48-63: F=C^(B|~D),     g=(7*i)%16
  %is_r0  = icmp slt i32 %mci, 16
  %is_r1  = icmp slt i32 %mci, 32
  %is_r2  = icmp slt i32 %mci, 48
  ; F computation
  %F_r0_bc = and i32 %mB, %mC
  %F_r0_nd = xor i32 %mB, -1
  %F_r0_ndc = and i32 %F_r0_nd, %mD
  %F_r0   = or  i32 %F_r0_bc, %F_r0_ndc
  %F_r1_db = and i32 %mD, %mB
  %F_r1_nd2 = xor i32 %mD, -1
  %F_r1_nc = and i32 %F_r1_nd2, %mC
  %F_r1   = or  i32 %F_r1_db, %F_r1_nc
  %F_r2   = xor i32 %mB, %mC
  %F_r2x  = xor i32 %F_r2, %mD
  %F_r3_nd = xor i32 %mD, -1
  %F_r3_bnd = or i32 %mB, %F_r3_nd
  %F_r3   = xor i32 %mC, %F_r3_bnd
  %F_01   = select i1 %is_r0, i32 %F_r0, i32 %F_r1
  %F_23   = select i1 %is_r2, i32 %F_r2x, i32 %F_r3
  %F_sel  = select i1 %is_r1, i32 %F_01, i32 %F_23
  ; g index computation
  ; g_r0 = i, g_r1 = (5*i+1)%16, g_r2 = (3*i+5)%16, g_r3 = (7*i)%16
  %g_r0   = urem i32 %mci, 16
  %g5i    = mul  i32 %mci, 5
  %g5i1   = add  i32 %g5i, 1
  %g_r1   = urem i32 %g5i1, 16
  %g3i    = mul  i32 %mci, 3
  %g3i5   = add  i32 %g3i, 5
  %g_r2   = urem i32 %g3i5, 16
  %g7i    = mul  i32 %mci, 7
  %g_r3   = urem i32 %g7i, 16
  %g_01   = select i1 %is_r0, i32 %g_r0, i32 %g_r1
  %g_23   = select i1 %is_r2, i32 %g_r2, i32 %g_r3
  %g_sel  = select i1 %is_r1, i32 %g_01, i32 %g_23
  ; Load M[g]
  %g64    = zext i32 %g_sel to i64
  %mMgp   = getelementptr [16 x i32], [16 x i32]* %mM, i64 0, i64 %g64
  %mMg    = load i32, i32* %mMgp, align 4
  ; Load T[i]
  %Tp     = getelementptr [64 x i32], [64 x i32]* @md5_T, i64 0, i64 %mci64
  %Ti     = load i32, i32* %Tp, align 4
  ; Load shift s[i]
  %sp     = getelementptr [64 x i8], [64 x i8]* @md5_s, i64 0, i64 %mci64
  %si8    = load i8, i8* %sp, align 1
  %si32   = zext i8 %si8 to i32
  ; temp = A + F + T[i] + M[g]
  %tmp1   = add i32 %mA, %F_sel
  %tmp2   = add i32 %tmp1, %Ti
  %tmp3   = add i32 %tmp2, %mMg
  ; ROTL(tmp3, s[i])
  %rotl_l = shl  i32 %tmp3, %si32
  %shr_n  = sub  i32 32, %si32
  %rotl_r = lshr i32 %tmp3, %shr_n
  %rotl   = or   i32 %rotl_l, %rotl_r
  ; new = B + ROTL(...)
  %mnew   = add i32 %mB, %rotl
  %mcinc  = add i32 %mci, 1
  br label %md5_compress
md5_add_back:
  %oA = load i32, i32* %ma0, align 4
  %oB = load i32, i32* %mb0, align 4
  %oC = load i32, i32* %mc0, align 4
  %oD = load i32, i32* %md0, align 4
  %nA = add i32 %oA, %mA
  %nB = add i32 %oB, %mB
  %nC = add i32 %oC, %mC
  %nD = add i32 %oD, %mD
  store i32 %nA, i32* %ma0, align 4
  store i32 %nB, i32* %mb0, align 4
  store i32 %nC, i32* %mc0, align 4
  store i32 %nD, i32* %md0, align 4
  %mbinc = add i64 %mbi, 1
  br label %md5_blkloop
md5_output:
  call void @free(i8* %msg)
  %out = call i8* @malloc(i64 33)
  %fmtU8 = getelementptr [5 x i8], [5 x i8]* @.fmt.hexU8, i64 0, i64 0
  %fA = load i32, i32* %ma0, align 4
  %fB = load i32, i32* %mb0, align 4
  %fC = load i32, i32* %mc0, align 4
  %fD = load i32, i32* %md0, align 4
  %fAb0 = and i32 %fA, 255
  %fAb1s = lshr i32 %fA, 8
  %fAb1 = and i32 %fAb1s, 255
  %fAb2s = lshr i32 %fA, 16
  %fAb2 = and i32 %fAb2s, 255
  %fAb3s = lshr i32 %fA, 24
  %fAb3 = and i32 %fAb3s, 255
  %op0  = getelementptr i8, i8* %out, i64 0
  %op2  = getelementptr i8, i8* %out, i64 2
  %op4  = getelementptr i8, i8* %out, i64 4
  %op6  = getelementptr i8, i8* %out, i64 6
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op0,  i64 3, i8* %fmtU8, i32 %fAb0)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op2,  i64 3, i8* %fmtU8, i32 %fAb1)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op4,  i64 3, i8* %fmtU8, i32 %fAb2)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op6,  i64 3, i8* %fmtU8, i32 %fAb3)
  %fBb0 = and i32 %fB, 255
  %fBb1s = lshr i32 %fB, 8
  %fBb1 = and i32 %fBb1s, 255
  %fBb2s = lshr i32 %fB, 16
  %fBb2 = and i32 %fBb2s, 255
  %fBb3s = lshr i32 %fB, 24
  %fBb3 = and i32 %fBb3s, 255
  %op8  = getelementptr i8, i8* %out, i64 8
  %op10 = getelementptr i8, i8* %out, i64 10
  %op12 = getelementptr i8, i8* %out, i64 12
  %op14 = getelementptr i8, i8* %out, i64 14
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op8,  i64 3, i8* %fmtU8, i32 %fBb0)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op10, i64 3, i8* %fmtU8, i32 %fBb1)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op12, i64 3, i8* %fmtU8, i32 %fBb2)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op14, i64 3, i8* %fmtU8, i32 %fBb3)
  %fCb0 = and i32 %fC, 255
  %fCb1s = lshr i32 %fC, 8
  %fCb1 = and i32 %fCb1s, 255
  %fCb2s = lshr i32 %fC, 16
  %fCb2 = and i32 %fCb2s, 255
  %fCb3s = lshr i32 %fC, 24
  %fCb3 = and i32 %fCb3s, 255
  %op16 = getelementptr i8, i8* %out, i64 16
  %op18 = getelementptr i8, i8* %out, i64 18
  %op20 = getelementptr i8, i8* %out, i64 20
  %op22 = getelementptr i8, i8* %out, i64 22
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op16, i64 3, i8* %fmtU8, i32 %fCb0)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op18, i64 3, i8* %fmtU8, i32 %fCb1)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op20, i64 3, i8* %fmtU8, i32 %fCb2)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op22, i64 3, i8* %fmtU8, i32 %fCb3)
  %fDb0 = and i32 %fD, 255
  %fDb1s = lshr i32 %fD, 8
  %fDb1 = and i32 %fDb1s, 255
  %fDb2s = lshr i32 %fD, 16
  %fDb2 = and i32 %fDb2s, 255
  %fDb3s = lshr i32 %fD, 24
  %fDb3 = and i32 %fDb3s, 255
  %op24 = getelementptr i8, i8* %out, i64 24
  %op26 = getelementptr i8, i8* %out, i64 26
  %op28 = getelementptr i8, i8* %out, i64 28
  %op30 = getelementptr i8, i8* %out, i64 30
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op24, i64 3, i8* %fmtU8, i32 %fDb0)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op26, i64 3, i8* %fmtU8, i32 %fDb1)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op28, i64 3, i8* %fmtU8, i32 %fDb2)
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %op30, i64 3, i8* %fmtU8, i32 %fDb3)
  %opnul = getelementptr i8, i8* %out, i64 32
  store i8 0, i8* %opnul, align 1
  ret i8* %out
}
""".trimIndent())
    }
}
