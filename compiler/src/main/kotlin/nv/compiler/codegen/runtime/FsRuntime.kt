package nv.compiler.codegen.runtime

/** std.fs runtime: filesystem exists/isDir/isFile, mkdir/rm/rename, read/write text, path ops, cwd. */
internal object FsRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.fs ───────────────────────────────────────────────────────────────────

; nv_fs_exists(path) → i1
define i1 @nv_fs_exists(i8* %path) {
entry:
  %r = call i32 @access(i8* %path, i32 0)
  %e = icmp eq i32 %r, 0
  ret i1 %e
}

; nv_fs_is_dir(path) → i1
define i1 @nv_fs_is_dir(i8* %path) {
entry:
  %d = call i8* @opendir(i8* %path)
  %nn = icmp ne i8* %d, null
  br i1 %nn, label %yes, label %no
yes:
  call i32 @closedir(i8* %d)
  ret i1 1
no:
  ret i1 0
}

; nv_fs_is_file(path) → i1
define i1 @nv_fs_is_file(i8* %path) {
entry:
  %e = call i32 @access(i8* %path, i32 0)
  %ex = icmp eq i32 %e, 0
  br i1 %ex, label %chkdir, label %no
chkdir:
  %d = call i8* @opendir(i8* %path)
  %isdir = icmp ne i8* %d, null
  br i1 %isdir, label %isdir_yes, label %yes
isdir_yes:
  call i32 @closedir(i8* %d)
  ret i1 0
yes:
  ret i1 1
no:
  ret i1 0
}

; nv_fs_mkdir(path) → i64
define i64 @nv_fs_mkdir(i8* %path) {
entry:
  %r = call i32 @mkdir(i8* %path, i32 493)
  %r64 = sext i32 %r to i64
  ret i64 %r64
}

; nv_fs_rm(path) → i64
define i64 @nv_fs_rm(i8* %path) {
entry:
  %r = call i32 @unlink(i8* %path)
  %ok = icmp eq i32 %r, 0
  br i1 %ok, label %done, label %try_dir
try_dir:
  %r2 = call i32 @rmdir(i8* %path)
  %r2_64 = sext i32 %r2 to i64
  ret i64 %r2_64
done:
  ret i64 0
}

; nv_fs_rename(src, dst) → i64
define i64 @nv_fs_rename(i8* %src, i8* %dst) {
entry:
  %r = call i32 @rename(i8* %src, i8* %dst)
  %r64 = sext i32 %r to i64
  ret i64 %r64
}

; nv_fs_read_text(path) → i8*  (null on error; caller must free)
define i8* @nv_fs_read_text(i8* %path) {
entry:
  %mode = getelementptr [2 x i8], [2 x i8]* @.str.mode_r, i64 0, i64 0
  %f = call i8* @fopen(i8* %path, i8* %mode)
  %isnull = icmp eq i8* %f, null
  br i1 %isnull, label %fail, label %read
read:
  %buf = call i8* @malloc(i64 65536)
  %lenp = alloca i64, align 8
  store i64 0, i64* %lenp, align 8
  br label %frtloop
frtloop:
  %l = load i64, i64* %lenp, align 8
  %toobig = icmp sge i64 %l, 65535
  br i1 %toobig, label %frtdone, label %frtrd
frtrd:
  %rem = sub i64 65535, %l
  %dp = getelementptr i8, i8* %buf, i64 %l
  %n = call i64 @fread(i8* %dp, i64 1, i64 %rem, i8* %f)
  %z = icmp eq i64 %n, 0
  br i1 %z, label %frtdone, label %frtupd
frtupd:
  %l2 = add i64 %l, %n
  store i64 %l2, i64* %lenp, align 8
  br label %frtloop
frtdone:
  %lf = load i64, i64* %lenp, align 8
  %tp = getelementptr i8, i8* %buf, i64 %lf
  store i8 0, i8* %tp, align 1
  call i32 @fclose(i8* %f)
  ret i8* %buf
fail:
  ret i8* null
}

; nv_fs_write_text(path, text)
define void @nv_fs_write_text(i8* %path, i8* %text) {
entry:
  %mode = getelementptr [2 x i8], [2 x i8]* @.str.mode_w, i64 0, i64 0
  %f = call i8* @fopen(i8* %path, i8* %mode)
  %isnull = icmp eq i8* %f, null
  br i1 %isnull, label %done, label %write
write:
  call i32 @fputs(i8* %text, i8* %f)
  call i32 @fclose(i8* %f)
  ret void
done:
  ret void
}

; nv_fs_append_text(path, text)
define void @nv_fs_append_text(i8* %path, i8* %text) {
entry:
  %mode = getelementptr [2 x i8], [2 x i8]* @.str.mode_a, i64 0, i64 0
  %f = call i8* @fopen(i8* %path, i8* %mode)
  %isnull = icmp eq i8* %f, null
  br i1 %isnull, label %done, label %write
write:
  call i32 @fputs(i8* %text, i8* %f)
  call i32 @fclose(i8* %f)
  ret void
done:
  ret void
}

; nv_fs_join_path(base, part) → i8*
define i8* @nv_fs_join_path(i8* %base, i8* %part) {
entry:
  %blen = call i64 @strlen(i8* %base)
  %plen = call i64 @strlen(i8* %part)
  %tot = add i64 %blen, %plen
  %sz = add i64 %tot, 3
  %buf = call i8* @malloc(i64 %sz)
  %fmt = getelementptr [6 x i8], [6 x i8]* @.str.p5_fsjoin, i64 0, i64 0
  call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 %sz, i8* %fmt, i8* %base, i8* %part)
  ret i8* %buf
}

; nv_fs_parent_dir(path) → i8*
define i8* @nv_fs_parent_dir(i8* %path) {
entry:
  %slash = call i8* @strrchr(i8* %path, i32 47)
  %isnull = icmp eq i8* %slash, null
  br i1 %isnull, label %ret_dot, label %compute
compute:
  %pi = ptrtoint i8* %path to i64
  %si = ptrtoint i8* %slash to i64
  %off = sub i64 %si, %pi
  %isroot = icmp eq i64 %off, 0
  br i1 %isroot, label %ret_root, label %ret_parent
ret_root:
  %rbuf = call i8* @malloc(i64 2)
  store i8 47, i8* %rbuf, align 1
  %rnp = getelementptr i8, i8* %rbuf, i64 1
  store i8 0, i8* %rnp, align 1
  ret i8* %rbuf
ret_parent:
  %sz = add i64 %off, 1
  %buf = call i8* @malloc(i64 %sz)
  call i8* @memcpy(i8* %buf, i8* %path, i64 %off)
  %np = getelementptr i8, i8* %buf, i64 %off
  store i8 0, i8* %np, align 1
  ret i8* %buf
ret_dot:
  %dbuf = call i8* @malloc(i64 2)
  store i8 46, i8* %dbuf, align 1
  %dnp = getelementptr i8, i8* %dbuf, i64 1
  store i8 0, i8* %dnp, align 1
  ret i8* %dbuf
}

; nv_fs_file_name(path) → i8*  (pointer into path, no alloc)
define i8* @nv_fs_file_name(i8* %path) {
entry:
  %slash = call i8* @strrchr(i8* %path, i32 47)
  %isnull = icmp eq i8* %slash, null
  br i1 %isnull, label %ret_path, label %ret_after
ret_path:
  ret i8* %path
ret_after:
  %next = getelementptr i8, i8* %slash, i64 1
  ret i8* %next
}

; nv_fs_file_ext(path) → i8*  (pointer into path, no alloc; empty str if no ext)
define i8* @nv_fs_file_ext(i8* %path) {
entry:
  %name = call i8* @nv_fs_file_name(i8* %path)
  %dot = call i8* @strrchr(i8* %name, i32 46)
  %isnull = icmp eq i8* %dot, null
  br i1 %isnull, label %ret_empty, label %ret_ext
ret_empty:
  %ebuf = call i8* @malloc(i64 1)
  store i8 0, i8* %ebuf, align 1
  ret i8* %ebuf
ret_ext:
  %next = getelementptr i8, i8* %dot, i64 1
  ret i8* %next
}

; nv_fs_cwd() → i8*
define i8* @nv_fs_cwd() {
entry:
  %buf = call i8* @malloc(i64 4096)
  %r = call i8* @getcwd(i8* %buf, i64 4096)
  %isnull = icmp eq i8* %r, null
  br i1 %isnull, label %fail, label %ok
ok:
  ret i8* %buf
fail:
  store i8 0, i8* %buf, align 1
  ret i8* %buf
}

; nv_fs_chdir(path) → i64
define i64 @nv_fs_chdir(i8* %path) {
entry:
  %r = call i32 @chdir(i8* %path)
  %r64 = sext i32 %r to i64
  ret i64 %r64
}
""".trimIndent())
    }
}
