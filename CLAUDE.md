# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository State

This repository is in **active development** — Phase 0 is complete and Phase 1 is in progress (through 1.4 type checker). The bootstrap compiler (Kotlin) has 396 passing tests. Next up: Phase 1.5 IR lowering.

Key documents:
- `EXAMPLE.txt` — language design and feature examples (the authoritative source for syntax and semantics)
- `PLAN.txt` — phased implementation roadmap, formal syntax specification outline, standard library design, and tooling plan
- `IMPL.txt` — current implementation progress tracker (authoritative status for each phase item)

## Language Overview

Nordvest is a statically typed, compiled, indentation-sensitive language. Key distinguishing features:

- **Mathematical notation as first-class syntax:** `∀ ∃ ∑ ∏ ∈ ∧ ∨ ¬ π e ∞ →` are part of the grammar, not library symbols
- **Range notation:** `[a, b[` (half-open), `[a, b]` (closed), `]a, b[` (open) — mirrors mathematical interval notation
- **Dual return syntax:** `→` and `->` are interchangeable; `→ expr` is a return statement
- **Null safety:** nullable types with `?`, safe navigation `?.`, null-coalescing `??`, `if let`, `guard let`
- **Result<T> with `?` propagation** for recoverable errors; checked exceptions (`throws`) for I/O
- **Sealed classes** for discriminated unions; exhaustive `match`
- **Extension functions** via `extend TypeName`; statically dispatched, no private field access
- **Primary constructors** declared on the class line; body fields are per-instance initializers (not constructor args)
- **Pipeline operator `|>`** with `_` as positional hole for the piped value
- **Bitwise operators:** `&` `|` `~` `⊕`/`xor` `<<` `>>` — `^` is exponentiation, not XOR; `÷` is integer division; `//` is **always a comment**, never an operator
- **Three-tier visibility:** `pub` (exported), `pub(pkg)` (package-internal), default (file-private)
- **Computed properties:** `fn name → T` (no parameter list) distinguishes properties from methods; settable via `get`/`set` blocks
- **Subscript operators:** `fn [](...)` and `fn []=(...)`  enable `obj[key]` / `obj[key] = val` on user types
- **Lazy sequences:** `yield` in a `Sequence<T>`-returning function produces a generator; `Iterator`/`Iterable` interfaces use associated types
- **Associated types** in interfaces: `type Item`; constraint form `where C.Item: Printable`
- **Defer:** `defer stmt` runs LIFO when the enclosing scope exits
- **Type casts:** `is` (smart cast — narrows type in then-branch), `as?` (explicit safe → T?), `as!` (forced, panics)
- **Labeled loops:** `@label for/while`; `break @label` / `continue @label`
- **Conditional compilation:** `@if(platform == "linux")` / `@else` at declaration scope; predicates `platform`, `arch`, `debug`, `release`, `feature("name")`
- **Closure captures:** by-reference (RC) by default; `[name] x → expr` snapshots by value; `[copy name]` for `go` coroutines
- **Raw strings:** `r"..."` (no escapes, no interpolation); `r"""..."""` multi-line
- **Multi-file modules:** all `.nv` files in a directory sharing a `module` declaration form one module; subdirectories are sub-modules; `mod.nv` controls re-exports with `pub import`
- **Value types:** `struct` is stack-allocated, copied on assignment, no RC overhead; `class` is reference-counted; `record` is immutable reference type
- **Weak/unowned references:** `weak var x: T?` and `unowned let x: T` break RC cycles
- **Map type:** `[K: V]` (e.g. `[str: int]`); map literal `["a": 1]`; empty map `[:]`
- **Inline if:** `if cond then a else b` — no ternary `?:` operator
- **No ternary operator:** `?` is only used for nullable types (`T?`) and Result propagation (`expr?`)
- **async/await compile-time:** `await` is only valid inside `async fn`; calling async from non-async requires `spawn` or `go`
- **throws sugar:** `fn f() throws E` desugars to `fn f() → Result<T, E>`; the two interoperate via `?` propagation
- **Unsafe blocks:** `unsafe { }` required for `ptr<T>` operations in Nordvest code; `@c`/`@cpp` blocks are implicitly unsafe
- **Derive annotations:** `@derive(traits)` auto-generates `Show`, `Eq`, `Compare`, `Hash`, `Copy`; `@derive(All)` is the shorthand for all five; `.copy(field: val)` for shallow-copy-with-override (requires `Copy`/`All`)
- **`@builder`:** generates a Builder class and `.build { }` DSL block for complex construction; required fields validated at `.build()` time
- **`@newtype`:** `@newtype struct T(U)` — zero-cost nominal wrapper; `.value` unwraps; auto-derives `Eq`/`Hash`/`Compare`/`Show` from the inner type
- **`@lazy` fields:** `@lazy val/var field = expr` — computed once on first access (`val`) or recomputed after `nil` assignment (`var`); body fields only, not constructor params
- **`by` delegation:** `class C : I by expr` delegates all methods of interface `I` to `expr`; C may override individually; statically dispatched
- **`@config`:** `@config("prefix")` on a struct/record/class generates a static `.load(from: Config) → Result<T>` at compile time; fields with defaults are optional, non-nullable fields without defaults are required; `@config("prefix", reload: true)` enables hot-reload via `cfg.onReload { }`
- **`@env`:** `@env("VAR_NAME")` on a field inside a `@config` struct binds a specific env var (wins over TOML); `@env("VAR_NAME", sensitive: true)` masks the value in `Show`, logs, and debug output; env names are auto-derived as `UPPER_SNAKE(prefix)_UPPER_SNAKE(field)` when `@env` is absent
- **`std.config`:** `ConfigLoader.default()` merges env vars > `config.local.toml` > `config.{NV_ENV}.toml` > `config.toml` > defaults; `cfg.bind<T>()` calls the generated `.load()` and optionally `validate()`; `ConfigValue` supports `Duration` coercion from strings like `"30s"`

## Implementation Plan Summary

See `PLAN.txt` for full details. The four phases:

1. **Phase 1 (bootstrap):** Kotlin implementation targeting LLVM IR — lexer with Unicode/INDENT handling, PEG parser, type checker, IR lowering. Delivers `nv run`, `nv build`.
2. **Phase 2 (full features):** Async/await, channels, `@asm`/`@gpu`/`@extern`, complete stdlib. Delivers `nv test`, `nv doc`, `nv pkg`.
3. **Phase 3 (tooling):** LSP server, canonical formatter, incremental compilation, package registry.
4. **Phase 4 (self-hosting):** Rewrite `nv` in Nordvest; staged bootstrap K0 → nvcc0 → nvcc1 → nvcc2.

## Planned `nv` CLI

```
nv run main.nv        # compile + run
nv build main.nv      # compile to native binary
nv test               # run tests
nv fmt main.nv        # format source
nv doc                # generate HTML/Markdown docs from /** */ doc comments
nv pkg add <pkg>      # add a dependency
nv new <name>         # scaffold a new project
```

## Standard Library Modules (planned)

`std.io`, `std.fs`, `std.math`, `std.string`, `std.collections`, `std.net.http`, `std.json`, `std.concurrent`, `std.test`, `std.gpu`, `std.ffi`, `std.process`, `std.time`, `std.log`, `std.crypto`, `std.encoding`, `std.net`, `std.compress`, `std.cli`, `std.regex`, `std.rand`, `std.hash`, `std.iter`, `std.net.url`, `std.fmt`, `std.uuid`, `std.bytes`, `std.config`, `std.sql`, `std.csv`, `std.xml`, `std.toml`, `std.unicode`, `std.bignum`, `std.archive`, `std.html`, `std.stats`, `std.mime`, `std.signal`, `std.template`, `std.debug`

## Key Syntax Reference

```
// Variables
let x = 42          // immutable, type inferred
var y: int = 0      // mutable

// Functions — return type optional when inferred from → expr
fn square(x: float) → float
    → x * x

// Inline if (no ternary operator)
let abs = x → if x < 0.0 then -x else x

// Lambda
[1, 2, 3].map(x → x * 2)

// Nullable + weak references
fn find(id: int) → User?
let name = user?.name ?? "Guest"
weak var delegate: Listener?   // does not keep delegate alive

// Pattern matching (exhaustive)
match value
    0:       → "zero"
    [1, 9]:  → "small"
    _:       → "other"

// Sealed class
sealed class Shape
    Circle(radius: float)
    Rect(w: float, h: float)

// is smart cast — narrows type in then-branch
if shape is Circle
    print(shape.radius)   // shape is Circle here

// Value types
struct Vec2(x: float, y: float)   // stack-allocated, copied on assignment
record Point(x: float, y: float)  // immutable reference type, auto ==/hash

// Result propagation; throws as sugar
fn compute(s: str) → Result<float>
    let n = parseInt(s)?    // propagates Err upward
    → Ok(n.toFloat())

fn readFile(path: str) throws IOError → str   // sugar for → Result<str, IOError>

// Map type and literal
let scores: [str: int] = ["Alice": 95, "Bob": 87]
let empty: [str: int] = [:]

// Integer division — ÷ only; // is always a comment
let half = n ÷ 2

// Mathematical quantifiers
→ ∀ x ∈ values: x > 0.0
→ ∑ x ∈ values: x^2

// Modules
module myapp.geometry
import std.math
pub class Vector2D(pub x: float, pub y: float)

// Derive annotations
@derive(All)                              // Show Eq Compare Hash Copy
struct Point(x: float, y: float)
let p2 = p1.copy(y: 9.0)                 // requires Copy or All

@derive(Show, Eq, Hash)
class Color(pub r: int, pub g: int, pub b: int)

// Builder
@builder
class Request(url: str, method: str = "GET", timeout: Duration = Duration.seconds(30))
let req = Request.build
    url    = "https://example.com"
    method = "POST"

// Newtype — zero-cost nominal wrapper
@newtype struct UserId(int)
@newtype struct Seconds(float)
let id: UserId = UserId(42)
let raw: int   = id.value

// Lazy fields
class Parser(source: str)
    @lazy val tokens: [Token] = tokenize(source)

// by delegation
class LoggingList<T>(inner: MutableList<T>) : MutableList<T> by inner
    override fn add(item: T) → bool
        log("add {item}")
        → inner.add(item)

// Configuration — @config binds a TOML subtree + env vars to a typed struct
@config("server")
struct ServerConfig(
    host: str = "0.0.0.0",
    port: int = 8080,
    tls:  bool = false
)

@config("database")
struct DatabaseConfig(
    @env("DATABASE_URL")
    url: str,                              // required — no default
    @env("DB_PASSWORD", sensitive: true)
    password: str? = nil                   // optional; masked in logs/Show
)

fn main() throws
    let cfg    = ConfigLoader.default().load()?   // merges files + env vars
    let server = cfg.bind<ServerConfig>()?
    let db     = cfg.bind<DatabaseConfig>()?
```
