package nv.compiler.codegen.runtime

/**
 * std.io runtime: file open/close/read/write operations.
 * File operations wrap libc FILE* as opaque i8*.
 */
internal object IoRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.io ───────────────────────────────────────────────────────────────────

; nv_file_open(path, mode) → i8*  (NULL on failure)
define i8* @nv_file_open(i8* %path, i8* %mode) {
entry:
  %f = call i8* @fopen(i8* %path, i8* %mode)
  ret i8* %f
}

; nv_file_open_read(path) → i8*
define i8* @nv_file_open_read(i8* %path) {
entry:
  %mode = getelementptr [2 x i8], [2 x i8]* @.str.mode_r, i64 0, i64 0
  %f = call i8* @fopen(i8* %path, i8* %mode)
  ret i8* %f
}

; nv_file_open_write(path) → i8*
define i8* @nv_file_open_write(i8* %path) {
entry:
  %mode = getelementptr [2 x i8], [2 x i8]* @.str.mode_w, i64 0, i64 0
  %f = call i8* @fopen(i8* %path, i8* %mode)
  ret i8* %f
}

; nv_file_open_append(path) → i8*
define i8* @nv_file_open_append(i8* %path) {
entry:
  %mode = getelementptr [2 x i8], [2 x i8]* @.str.mode_a, i64 0, i64 0
  %f = call i8* @fopen(i8* %path, i8* %mode)
  ret i8* %f
}

; nv_file_close(file)
define void @nv_file_close(i8* %file) {
entry:
  call i32 @fclose(i8* %file)
  ret void
}

; nv_file_write(file, s)
define void @nv_file_write(i8* %file, i8* %s) {
entry:
  call i32 @fputs(i8* %s, i8* %file)
  ret void
}

; nv_file_writeln(file, s)
define void @nv_file_writeln(i8* %file, i8* %s) {
entry:
  call i32 @fputs(i8* %s, i8* %file)
  call i32 @fputc(i32 10, i8* %file)
  ret void
}

; nv_file_read_line(file) → i8*  (null on EOF; strips trailing newline)
define i8* @nv_file_read_line(i8* %file) {
entry:
  %buf = call i8* @malloc(i64 4096)
  %r = call i8* @fgets(i8* %buf, i32 4096, i8* %file)
  %is_null = icmp eq i8* %r, null
  br i1 %is_null, label %frl.null, label %frl.strip
frl.strip:
  %len = call i64 @strlen(i8* %buf)
  %zero = icmp eq i64 %len, 0
  br i1 %zero, label %frl.ret, label %frl.chk_nl
frl.chk_nl:
  %last_idx = sub i64 %len, 1
  %lastp = getelementptr i8, i8* %buf, i64 %last_idx
  %last_ch = load i8, i8* %lastp, align 1
  %is_nl = icmp eq i8 %last_ch, 10
  br i1 %is_nl, label %frl.strip_nl, label %frl.ret
frl.strip_nl:
  store i8 0, i8* %lastp, align 1
  br label %frl.ret
frl.ret:
  ret i8* %buf
frl.null:
  call void @free(i8* %buf)
  ret i8* null
}

; nv_file_read_all(file) → i8*  (up to 64KB)
define i8* @nv_file_read_all(i8* %file) {
entry:
  %buf = call i8* @malloc(i64 65536)
  %len = alloca i64, align 8
  store i64 0, i64* %len, align 8
  br label %fra.loop
fra.loop:
  %l = load i64, i64* %len, align 8
  %too_big = icmp sge i64 %l, 65535
  br i1 %too_big, label %fra.done, label %fra.read
fra.read:
  %rem = sub i64 65535, %l
  %dstp = getelementptr i8, i8* %buf, i64 %l
  %n = call i64 @fread(i8* %dstp, i64 1, i64 %rem, i8* %file)
  %zero = icmp eq i64 %n, 0
  br i1 %zero, label %fra.done, label %fra.update
fra.update:
  %l2 = add i64 %l, %n
  store i64 %l2, i64* %len, align 8
  br label %fra.loop
fra.done:
  %l_f = load i64, i64* %len, align 8
  %tp = getelementptr i8, i8* %buf, i64 %l_f
  store i8 0, i8* %tp, align 1
  ret i8* %buf
}

; nv_file_exists(path) → i1
define i1 @nv_file_exists(i8* %path) {
entry:
  %r = call i32 @access(i8* %path, i32 0)
  %exists = icmp eq i32 %r, 0
  ret i1 %exists
}

; nv_file_is_null(file) → i1  (check if open succeeded)
define i1 @nv_file_is_null(i8* %file) {
entry:
  %r = icmp eq i8* %file, null
  ret i1 %r
}
""".trimIndent())
    }
}
