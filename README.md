# Nordvest

A statically typed, compiled language with clean indentation-based syntax, first-class mathematical notation, and a unified toolchain.

> **Status:** Phases 0–5 complete — bootstrap compiler (Kotlin) produces working native binaries via LLVM IR, with LSP server, formatter, package manager, and a real standard library. 694 tests passing. Next: Phase 6 — IDE Plugins.
> See [`IMPL.txt`](IMPL.txt) for current progress and [`PLAN.txt`](PLAN.txt) for the full roadmap.

---

## Highlights

- **Mathematical notation as syntax** — `∀ ∃ ∑ ∏ ∈ ∧ ∨ ¬ π e ∞ →` are part of the grammar, not library symbols
- **Null safety** — nullable types `T?`, safe navigation `?.`, null-coalescing `??`, `if let` / `guard let`
- **Result propagation** — `Result<T>` with `?` operator for recoverable errors; `throws` sugar for I/O
- **Exhaustive pattern matching** — `match` with sealed classes, ranges, guards, nested patterns
- **Value / reference / immutable types** — `struct` (stack), `class` (RC heap), `record` (immutable ref)
- **Pipeline operator** — `|>` with `_` as positional hole for left-to-right function composition
- **Indentation-sensitive** — no braces; block structure is significant whitespace
- **Unified toolchain** — one `nv` binary for compile, run, test, fmt, doc, and package management

---

## Quick look

```
// Hello World
fn main()
    print("Hello, world!")
```

```
// Null safety
fn greet(user: User?) → str
    let name = user?.name ?? "Guest"
    → "Hello, {name}!"
```

```
// Pattern matching on a sealed class
sealed class Shape
    Circle(radius: float)
    Rect(w: float, h: float)

fn area(s: Shape) → float
    match s
        Circle(r):    → π * r^2
        Rect(w, h):   → w * h
```

```
// Mathematical quantifiers
fn allPositive(values: [float]) → bool
    → ∀ x ∈ values: x > 0.0

fn sumOfSquares(values: [float]) → float
    → ∑ x ∈ values: x^2
```

```
// Result propagation — ? short-circuits on Err
fn parseAndDouble(s: str) → Result<int>
    let n = parseInt(s)?
    → Ok(n * 2)
```

```
// Pipeline operator
fn process(raw: [float]) → [float]
    → raw
        |> filter(_, x → x > 0.0)
        |> map(_, x → x.round(4))
```

More examples in [`EXAMPLE.txt`](EXAMPLE.txt) and [`examples/`](examples/).

---

## `nv` toolchain

```
nv run   <file.nv>   compile and run
nv build <file.nv>   compile to native binary
nv fmt   <file.nv>   format source (canonical style)
nv test              run tests
nv doc               generate documentation
nv pkg <cmd>         package management
```

---

## Repository layout

```
compiler/   Kotlin bootstrap compiler (Gradle subproject)
tools/      nv CLI entry point        (Gradle subproject)
tests/      Test suite — golden files + parse tests
stdlib/     Nordvest standard library source (.nv)
spec/       Formal PEG grammar (nv.peg) and language spec
docs/       Reference documentation source
examples/   Curated example programs
```

---

## How the compiler works

The bootstrap compiler is written entirely in **Kotlin** and compiled to a fat JAR via Gradle. The `nv` shell script invokes that JAR. No LLVM bindings or native code are needed at compile time — the compiler emits LLVM IR as plain text, then hands off to **Clang** (which must be on `PATH`) to produce the final native binary.

### Compilation pipeline

```
Source (.nv)
    │
    ▼
┌─────────┐   Unicode-aware lexer handles INDENT/DEDENT tokens,
│  Lexer  │   mathematical symbols (∀ ∃ ∑ …), raw strings, and
└────┬────┘   all operators. Produces a flat token stream.
     │
     ▼
┌────────┐    Recursive-descent PEG-style parser. Produces a
│ Parser │    typed AST (sealed Kotlin classes in Ast.kt).
└────┬───┘    Returns ParseResult.Success / Recovered / Failure.
     │
     ▼
┌──────────┐  Walks the AST and builds a symbol table.
│ Resolver │  Resolves names to their declaration sites,
└────┬─────┘  catches use-before-declare and undefined names.
     │
     ▼
┌─────────────┐  Hindley-Milner-style bidirectional type checker.
│ TypeChecker │  Infers types for let/var bindings, validates
└──────┬──────┘  calls, checks null safety, Result<T> propagation,
       │         and match exhaustiveness. Annotates every AST node.
       │
       ▼
┌────────────────┐  Walks the type-annotated AST and emits textual
│ LlvmIrEmitter  │  LLVM IR (.ll). Uses alloca/load/store for all
└───────┬────────┘  locals. Runtime helpers (RC, strings, I/O, …)
        │           are emitted as inline IR functions in the same
        │           module, so the output only depends on libc.
        │
        ▼
   LLVM IR (.ll)
        │
        ▼  clang -o <bin> out.ll  (or  clang -O2  in --release mode)
        │
        ▼
  Native binary
```

### LLVM IR and Clang

The `LlvmIrEmitter` produces typed-pointer IR compatible with **LLVM/Clang 12+**.  Pointer types are written as `i8*` rather than the newer opaque `ptr` form for broad compatibility across macOS (Apple Clang) and Linux toolchains.

Clang is used as the assembler/linker driver rather than `llc`+`ld` because it handles platform-specific startup code, C runtime linking, and architecture-specific flags automatically. When `nv run` is invoked, the IR is written to a temporary directory, Clang compiles it to a binary, the binary runs, and the temporary files are cleaned up.

`nv build --emit-llvm` writes the `.ll` file to disk so you can inspect it, feed it to `opt`, or cross-compile it yourself.

### Runtime support

There is no separate runtime library to install. All runtime helpers — reference-counted heap allocation (`nv_rc_retain` / `nv_rc_release`), string operations, I/O, collections, hashing, formatting, and more — are emitted as `define` blocks in the same `.ll` module as the user program. The only external dependency is **libc** (always available).

This design means every compiled Nordvest binary is self-contained apart from the C standard library.

### Incremental compilation and caching

`nv build` caches the compiled IR in a `.nv-cache/` directory keyed by a SHA-256 hash of the source file. A file is only re-compiled when its content changes. `nv clean` removes the cache.

### Key source files

| File | Role |
|---|---|
| `compiler/…/Lexer.kt` | Tokenises source; synthesises INDENT/DEDENT |
| `compiler/…/Parser.kt` | Builds the typed AST |
| `compiler/…/Resolver.kt` | Name resolution and scope analysis |
| `compiler/…/TypeChecker.kt` | Bidirectional type inference and checking |
| `compiler/…/LlvmIrEmitter.kt` | AST → LLVM IR text |
| `compiler/…/codegen/runtime/` | IR-level runtime helpers (one file per stdlib module) |
| `tools/…/Main.kt` | `nv` CLI — run, build, fmt, test, doc, pkg, lsp |

---

## Building from source

**Prerequisites:** Java 21+, Git.  Everything else is fetched by the Gradle wrapper.

```bash
# Clone
git clone https://github.com/your-org/nordvest-lang.git
cd nordvest-lang

# Build all subprojects and run tests
./gradlew build

# Build the nv fat JAR
./gradlew :tools:fatJar

# Run the CLI (prints usage)
./nv help
```

> The project uses Gradle 8.12 with the Kotlin DSL.  Java 21 is configured in
> [`gradle.properties`](gradle.properties) — override with `JAVA_HOME` if needed.

---

## Language reference

| Document | Contents |
|---|---|
| [`EXAMPLE.txt`](EXAMPLE.txt) | Annotated examples covering all language features |
| [`PLAN.txt`](PLAN.txt) | Phased implementation roadmap and formal grammar outline |
| [`spec/nv.peg`](spec/nv.peg) | PEG grammar (stub, expanded in Phase 1) |
| [`IMPL.txt`](IMPL.txt) | Implementation progress tracker |

---

## Implementation roadmap

| Phase | Goal | Status |
|---|---|---|
| **0 — Foundation** | Repo structure, Gradle, grammar skeleton, test harness | Done |
| **1 — Bootstrap core** | Lexer, parser, type checker, LLVM IR codegen, `nv run/build` | Done |
| **2 — Systems & concurrency** | async/await, channels, C/C++ interop, GPU, stdlib v1 | Done |
| **3 — Polish & ecosystem** | LSP, formatter, package registry, error messages | Done |
| **4 — Language completion** | stdlib bodies, codegen hardening, flagship examples, fuzz testing | Done |
| **5 — Stdlib & production hardening** | Real stdlib implementations, RC, string/collections/I/O/hash/fmt/iter | Done |
| **6 — IDE plugins** | VS Code extension + IntelliJ plugin backed by nv-lsp | Planned |

The bootstrap compiler is written in **Kotlin** and targets **LLVM IR**. It is the permanent reference implementation — the language does not self-host.

---

## Language design influences

Nordvest draws from several languages:

- **Python** — indentation, readability, expressiveness
- **Kotlin** — null safety, conciseness, primary constructors
- **Go** — simplicity, tuples, unified tooling
- **Swift** — value types (`struct`/`record`), `guard let`, `is` smart casts
- **Haskell / ML** — exhaustive pattern matching, `Result<T>`, type inference
- **Mathematics** — first-class `∀ ∃ ∑ ∏` quantifier syntax, interval notation, `^` for exponentiation
