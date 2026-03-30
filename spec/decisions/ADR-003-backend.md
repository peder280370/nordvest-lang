# ADR-003 — Code Generation Backend

**Status**: Accepted
**Date**: 2026-03-30
**Deciders**: Nordvest core team

## Context

The Nordvest bootstrap compiler (Phase 1) is written in Kotlin/JVM. It must produce native machine code for at least two targets: `x86_64-unknown-linux-gnu` and `aarch64-apple-darwin` (matching the CI matrix). Three code generation strategies were evaluated:

### Option A: Custom native code generator
Write a backend that directly emits x86_64 and arm64 machine code (or object files) from the Nordvest IR.

**Ruled out.** Producing correct, optimized machine code from scratch for two ISAs is months of engineering work with no benefit over a mature IR framework. Register allocation, instruction selection, calling conventions, and ABI compliance are all hard problems that LLVM already solves.

### Option B: LLVM Java bindings (llvm4j / LLVM Java API)
Use a JVM-accessible binding to the LLVM C API so that the Kotlin compiler can call LLVM functions in-process to construct IR and run optimization passes.

**Deferred.** The available bindings (llvm4j, panama-based LLVM bindings) are less mature than the LLVM C API itself. They add a JNI/FFM native-library dependency to the compiler JAR, complicating distribution and CI setup. They are the right long-term choice once they stabilize.

### Option C: Textual LLVM IR emission + external llc/clang
The compiler emits textual LLVM IR (`.ll` files) using string manipulation in Kotlin. The `nv` CLI then invokes `llc` (IR → object file) and `clang` (object + runtime → linked binary) as external subprocesses. Both must be on `PATH`.

**Selected for Phase 1.**

## Decision

**Phase 1 uses textual LLVM IR emission (Option C).**

The `Compiler.compile()` method in `compiler/` produces a `.ll` file containing valid LLVM IR text. The `nv run` and `nv build` commands in `tools/` invoke the following subprocess pipeline:

```
source.nv → [Kotlin compiler] → source.ll → [llc] → source.o → [clang] → binary
```

### Implementation details

- **IR target triple**: determined at compile time from the host platform (`x86_64-unknown-linux-gnu` on Linux, `aarch64-apple-darwin` on macOS). The `--target` flag overrides for cross-compilation.
- **Optimization level**: Phase 1 uses `llc -O0` (no optimization) for fast compilation. `nv build --release` will use `llc -O2` once the feature is wired in Phase 3.
- **Runtime linking**: the Nordvest C runtime library (`compiler/src/main/c/runtime/`) is compiled to a static archive (`nv_runtime.a`) and linked via `clang -lnv_runtime`.
- **Error reporting**: if `llc` or `clang` are not on `PATH`, `nv` prints a clear diagnostic:
  ```
  error: llc not found on PATH — install LLVM (https://llvm.org/releases) and ensure llc is on PATH
  ```
- **Temporary files**: `nv run` writes the `.ll` file and intermediate `.o` to `$TMPDIR/nv-<pid>/` and cleans up on exit.
- **`nv build`**: writes the `.ll` file alongside the source (or to `--output`) and does not clean up, allowing inspection.

### Long-term migration path

- **Phase 2/3**: migrate to llvm4j or the official LLVM Java bindings (once mature) for in-process IR construction. This eliminates subprocess startup latency (~50 ms per `llc` invocation) and enables incremental compilation via cached LLVM modules.
- **Phase 4 (self-hosting)**: the self-hosted Nordvest compiler will emit textual LLVM IR via string operations. No JNI is needed. The subprocess model is therefore also the natural model for the self-hosted compiler, making Option C the canonical long-term approach as well.

### IR structure

The LLVM IR emitter in `compiler/src/main/kotlin/nv/compiler/codegen/` produces IR organized as follows:

```
; source file comment
target triple = "..."
target datalayout = "..."

; type declarations (structs, RC header)

; runtime function declarations (@nv_alloc, @nv_retain, @nv_release, @nv_panic, ...)

; string constant pool

; function definitions
define <rettype> @<mangled_name>(<params>) {
  ...
}

; module initializer (static initializers, if any)
```

Nordvest name mangling: `<module>.<name>` → `nv$<module_dot_replaced_with_dollar>$<name>`. `main` → `@nv$main$main`, called from a C `main()` wrapper in the runtime.

## Consequences

### Positive

- **Simple implementation**: textual IR is just string interpolation in Kotlin. The IR is human-readable, can be inspected with `llvm-dis` / `opt`, and is straightforward to debug when codegen is wrong.
- **No native library in JAR**: the Kotlin compiler JAR has no JNI dependency. It runs on any JVM with `llc` + `clang` installed.
- **Full LLVM optimization pipeline available**: once `llc -O2` is wired up, Nordvest programs benefit from decades of LLVM optimization work with zero additional compiler effort.
- **Cross-compilation ready**: passing `--target <triple>` to both the Nordvest compiler and `llc`/`clang` enables cross-compilation without changes to the Kotlin code.
- **Self-hosting natural fit**: a Nordvest program that produces LLVM IR text requires no FFI, making this approach directly portable to Phase 4.

### Negative

- **Subprocess latency**: each `nv run` / `nv build` invocation spawns `llc` and `clang` as separate processes. LLVM startup takes ~50 ms per tool on a warm system. For a "Hello World" compile, this dominates the compile time. Acceptable for Phase 1; addressed by in-process bindings in Phase 3.
- **LLVM on PATH required**: developers must install LLVM separately. The CI matrix already installs LLVM via `apt`/`brew`. Local developer setup requires one extra step (`brew install llvm` or `apt install llvm clang`).
- **Textual IR verbosity**: textual LLVM IR for complex programs is verbose. Very large programs may see I/O overhead from writing large `.ll` files. Mitigated by the Phase 3 migration to in-process IR construction.

### Neutral

- The codegen module (`compiler/src/main/kotlin/nv/compiler/codegen/`) is cleanly separated from parsing and type checking. Switching from textual IR to llvm4j in Phase 3 requires changes only to this module.
- Both `llc` and `clang` are available via standard package managers on all supported platforms (`brew install llvm` on macOS, `apt install llvm clang` on Ubuntu, `winget install LLVM` on Windows).
