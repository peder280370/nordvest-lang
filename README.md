# Nordvest

A statically typed, compiled language with clean indentation-based syntax, first-class mathematical notation, and a unified toolchain.

> **Status:** Design phase — bootstrap compiler under active development (Phase 0/1).
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
| **0 — Foundation** | Repo structure, Gradle, grammar skeleton, test harness | In progress |
| **1 — Bootstrap core** | Lexer, parser, type checker, LLVM IR codegen, `nv run/build` | Planned |
| **2 — Systems & concurrency** | async/await, channels, C/C++ interop, GPU, stdlib v1 | Planned |
| **3 — Polish & ecosystem** | LSP, formatter, package registry, error messages | Planned |
| **4 — Self-hosting** | Rewrite the compiler in Nordvest | Planned |

The bootstrap compiler is written in **Kotlin** and targets **LLVM IR**.
Self-hosting is the goal of Phase 4.

---

## Language design influences

Nordvest draws from several languages:

- **Python** — indentation, readability, expressiveness
- **Kotlin** — null safety, conciseness, primary constructors
- **Go** — simplicity, tuples, unified tooling
- **Swift** — value types (`struct`/`record`), `guard let`, `is` smart casts
- **Haskell / ML** — exhaustive pattern matching, `Result<T>`, type inference
- **Mathematics** — first-class `∀ ∃ ∑ ∏` quantifier syntax, interval notation, `^` for exponentiation
