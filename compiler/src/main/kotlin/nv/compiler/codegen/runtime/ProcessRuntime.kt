package nv.compiler.codegen.runtime

/** std.process runtime: env vars, exit, pid, cwd, shell capture. */
internal object ProcessRuntime {
    fun emit(out: StringBuilder) {
        out.appendLine("""
; ── std.process ──────────────────────────────────────────────────────────────

; nv_process_getenv(name) → i8*  (null if not set)
define i8* @nv_process_getenv(i8* %name) {
entry:
  %r = call i8* @getenv(i8* %name)
  ret i8* %r
}

; nv_process_setenv(name, value)
define void @nv_process_setenv(i8* %name, i8* %value) {
entry:
  call i32 @setenv(i8* %name, i8* %value, i32 1)
  ret void
}

; nv_process_exit(code: i64)
define void @nv_process_exit(i64 %code) {
entry:
  %c = trunc i64 %code to i32
  call void @exit(i32 %c)
  unreachable
}

; nv_process_pid() → i64
define i64 @nv_process_pid() {
entry:
  %r = call i32 @getpid()
  %r64 = sext i32 %r to i64
  ret i64 %r64
}

; nv_process_cwd() → i8*
define i8* @nv_process_cwd() {
entry:
  %r = call i8* @nv_fs_cwd()
  ret i8* %r
}

; nv_process_chdir(path) → i64
define i64 @nv_process_chdir(i8* %path) {
entry:
  %r = call i32 @chdir(i8* %path)
  %r64 = sext i32 %r to i64
  ret i64 %r64
}

; nv_process_capture(cmd) → i8*  (stdout of cmd via popen; empty str on failure)
define i8* @nv_process_capture(i8* %cmd) {
entry:
  %mode = getelementptr [2 x i8], [2 x i8]* @.str.mode_r, i64 0, i64 0
  %f = call i8* @popen(i8* %cmd, i8* %mode)
  %isnull = icmp eq i8* %f, null
  br i1 %isnull, label %fail, label %read
read:
  %buf = call i8* @malloc(i64 65536)
  %lenp = alloca i64, align 8
  store i64 0, i64* %lenp, align 8
  br label %pcloop
pcloop:
  %l = load i64, i64* %lenp, align 8
  %toobig = icmp sge i64 %l, 65535
  br i1 %toobig, label %pcdone, label %pcrd
pcrd:
  %rem = sub i64 65535, %l
  %dp = getelementptr i8, i8* %buf, i64 %l
  %n = call i64 @fread(i8* %dp, i64 1, i64 %rem, i8* %f)
  %z = icmp eq i64 %n, 0
  br i1 %z, label %pcdone, label %pcupd
pcupd:
  %l2 = add i64 %l, %n
  store i64 %l2, i64* %lenp, align 8
  br label %pcloop
pcdone:
  %lf = load i64, i64* %lenp, align 8
  %tp = getelementptr i8, i8* %buf, i64 %lf
  store i8 0, i8* %tp, align 1
  call i32 @pclose(i8* %f)
  ret i8* %buf
fail:
  %ebuf = call i8* @malloc(i64 1)
  store i8 0, i8* %ebuf, align 1
  ret i8* %ebuf
}
""".trimIndent())
    }
}
