# Nordvest Language Specification

> **Status**: Living document — updated through Phase 5.
> The normative grammar is [`spec/nv.peg`](nv.peg).
> This document provides the narrative rationale and detailed rules that accompany the grammar.

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Lexical Structure](#2-lexical-structure)
3. [Types](#3-types)
4. [Expressions](#4-expressions)
5. [Statements](#5-statements)
6. [Functions](#6-functions)
7. [Classes and Objects](#7-classes-and-objects)
8. [Value Types: Structs and Records](#8-value-types-structs-and-records)
9. [Interfaces and Extensions](#9-interfaces-and-extensions)
10. [Sealed Classes and Enums](#10-sealed-classes-and-enums)
11. [Null Safety](#11-null-safety)
12. [Error Handling](#12-error-handling)
13. [Generics](#13-generics)
14. [Modules and Visibility](#14-modules-and-visibility)
15. [Concurrency](#15-concurrency)
16. [Memory Management](#16-memory-management)
17. [Annotations and Metaprogramming](#17-annotations-and-metaprogramming)

---

## 1. Introduction

### 1.1 Language Goals

Nordvest is a statically typed, compiled, indentation-sensitive language designed around these principles:

- **Mathematical notation as a first class citizen.** Operators such as `∀ ∃ ∑ ∏ ∈ ∧ ∨ ¬ → ≤ ≥ ≠ ÷ ⊕` and the constants `π e ∞` are part of the grammar, not library symbols. Code that describes algorithms should look like the math it implements.
- **Null safety by default.** Every type is non-nullable unless explicitly suffixed with `?`. The compiler tracks nullability through the control flow and narrows types after checks.
- **Explicit error handling.** Errors are values. `Result<T, E>` and `?` propagation replace hidden exception paths. The `throws` keyword is syntactic sugar over `Result` for I/O-style errors.
- **Predictable performance.** Reference counting with atomic operations manages class instances. Structs are stack-allocated value types with no RC overhead. No garbage-collection pauses.
- **Clean tooling.** A single `nv` CLI handles compiling, running, testing, formatting, documenting, and package management.

Non-goals for v1: garbage collection, borrow checking (reserved for an opt-in post-v1 annotation), and dynamic dispatch beyond virtual method tables.

Nordvest draws from Python (whitespace, expressiveness), Kotlin (null safety, extension functions, primary constructors), Go (tooling, goroutines, channels), and adds first-class mathematical notation.

### 1.2 Notation Used in This Document

Grammar productions are quoted from `spec/nv.peg`. PEG rules use `/` for ordered choice and `*`/`+`/`?` with their standard meanings. Code examples are written in Nordvest and are syntactically valid unless a `// ✗` comment marks them as invalid.

### 1.3 Program Structure

Every `.nv` file optionally begins with a `module` declaration followed by `import` statements, then top-level declarations (functions, classes, structs, records, interfaces, enums, sealed classes, extensions, type aliases, and global `let`/`var` bindings).

The entry point is a top-level `fn main()` or `fn main() → int`. The compiler emits a C-ABI `main` that calls it.

```
module myapp

import std.io

fn main()
    print("Hello, world!")
```

---

## 2. Lexical Structure

### 2.1 Source Encoding

Source files must be UTF-8 encoded. An optional BOM at the start of the file is consumed and ignored. The lexer NFC-normalises the entire source before tokenisation, so precomposed and decomposed forms of the same character are treated identically. See ADR-002.

### 2.2 Indentation

Nordvest uses significant indentation to delimit blocks. The lexer injects synthetic `INDENT` and `DEDENT` tokens:

- One tab character equals four spaces for the purpose of indentation comparison.
- Mixing tabs and spaces on the same leading-whitespace sequence is a `MixedIndentation` lexer error.
- `NEWLINE` tokens are suppressed inside open parentheses `()`, square brackets `[]`, and curly braces `{}` (trailing lambda bodies), allowing multi-line expressions to be written without continuation characters.
- A `DEDENT` is injected for each indentation level that is closed by a decrease in indentation. At end of file, all open indentation levels are closed.

### 2.3 Integer and Float Literals

| Form | Example |
|------|---------|
| Decimal integer | `42`, `1_000_000` |
| Hexadecimal | `0xFF`, `0xDEAD_BEEF` |
| Binary | `0b1010_1100` |
| Octal | `0o755` |
| Decimal float | `3.14`, `1.5e-3`, `6.022e23` |
| Float with underscore | `1_000.5` |

Underscores may appear anywhere within a numeric literal (but not at the start or end) as visual separators. They carry no semantic weight.

The type of an integer literal is `int` (64-bit signed) unless the context demands a narrower type. The type of a float literal is `float` (64-bit double) unless the context demands `float32`.

### 2.4 String Literals

**Ordinary strings** are delimited by `"..."`. They support:

- Escape sequences: `\n`, `\t`, `\r`, `\\`, `\"`, `\0`, `\uXXXX` (Unicode code point).
- String interpolation: `{expr}` embeds the result of `expr` (converted via its `str()` method). `{expr:fmt}` passes a format specifier — e.g., `{x:.4f}` for four decimal places.
- Nested braces inside an interpolated expression are allowed and properly matched by the lexer's interpolation mode stack.

**Raw strings** `r"..."` contain no escape sequences and no interpolation — every character is literal. The multi-line form `r"""..."""` spans multiple lines; leading whitespace on continuation lines is stripped up to the indentation of the opening delimiter.

```
let path = r"C:\Users\alice\docs"   // no escaping needed
let json = r"""
    {"key": "value"}
    """
```

### 2.5 Identifiers and Keywords

Identifiers begin with a Unicode `XID_Start` character (letter, underscore, or `_`) and continue with `XID_Continue` characters (letters, digits, underscores). The `_` alone is the blank identifier (discard pattern). Identifiers are NFC-normalised.

**Reserved keywords** (cannot be used as identifiers):

```
and async await break by catch class continue defer default else enum
extend false fn for go guard if import in init interface is let match
mod module nil not or override pub pure record return sealed select
self spawn struct super throw throws true try type unowned unsafe var
weak while xor yield
```

**Contextual keywords** (only reserved in specific positions): `after`, `default`, `from`, `get`, `set`.

### 2.6 Unicode Operators and Constants

The lexer recognises the following Unicode tokens in addition to their ASCII equivalents:

| Unicode | ASCII alias | Meaning |
|---------|-------------|---------|
| `→` | `->` | Return type annotation / return statement |
| `∀` | — | Universal quantifier |
| `∃` | — | Existential quantifier |
| `∑` | — | Summation quantifier |
| `∏` | — | Product quantifier |
| `∈` | `in` | Set membership / loop iterator |
| `∧` | `and` | Logical AND |
| `∨` | `or` | Logical OR |
| `¬` | `not` | Logical NOT |
| `≤` | `<=` | Less-than-or-equal |
| `≥` | `>=` | Greater-than-or-equal |
| `≠` | `!=` | Not-equal |
| `÷` | — | Integer division (no ASCII alias — `//` is always a comment) |
| `⊕` | `xor` | Bitwise XOR |

Built-in mathematical constants:

| Constant | Value |
|----------|-------|
| `π` | 3.141592653589793… |
| `e` | 2.718281828459045… |
| `∞` | Positive infinity (`float`) |

**Disambiguation rules:**
- `//` is always the start of a line comment. There is no `//` integer-division operator; use `÷`.
- `^` is always the exponentiation operator; it is never bitwise XOR.
- `|>` is recognised by maximal munch before `|` is considered as a bitwise OR operator.
- `->`/`→` as a return type annotation is distinguished from a lambda body by context.

### 2.7 Comments

```
// Single-line comment — from // to end of line.

/* Multi-line block comment
   can span many lines. */

/**
 * Doc comment — attached to the declaration that immediately follows.
 * Rendered by `nv doc` as HTML/Markdown.
 *
 * @param r   the radius
 * @returns   the area: π·r²
 */
fn circleArea(r: float) → float
    → π * r^2
```

Doc comments (`/** ... */`) are retained in the AST and emitted by `nv doc`.

---

## 3. Types

### 3.1 Primitive Types

| Type | Width | Notes |
|------|-------|-------|
| `int` | 64-bit signed | Default integer type |
| `int8` | 8-bit signed | |
| `int16` | 16-bit signed | |
| `int32` | 32-bit signed | |
| `int64` | 64-bit signed | Alias for `int` |
| `float` | 64-bit IEEE 754 | Default float type |
| `float32` | 32-bit IEEE 754 | |
| `float64` | 64-bit IEEE 754 | Alias for `float` |
| `bool` | 1-bit | `true` / `false` |
| `str` | — | UTF-8 string (immutable, heap-allocated) |
| `byte` | 8-bit unsigned | Raw byte |
| `char` | Unicode scalar | Single Unicode code point |

Integer overflow wraps silently in release builds. Integer widening (e.g., `int32` to `int`) is implicit. Narrowing requires an explicit cast.

Float literals widen to `float64` unless the target type is `float32`. Arithmetic between `int` and `float` requires an explicit conversion (`x.toFloat()`).

### 3.2 Nullable Types

Appending `?` to any type makes it nullable: the value may be `nil` or an instance of `T`.

```
let name: str? = nil
let count: int? = 42
```

All types are non-nullable by default. Attempting to assign `nil` to a non-nullable binding is a compile error. The compiler tracks nullability through control flow (see §11).

### 3.3 Collection Types

| Syntax | Description |
|--------|-------------|
| `[T]` | Ordered array of `T`, zero-indexed |
| `[K: V]` | Map from `K` to `V` |
| `[[T]]` | Two-dimensional matrix of `T` |

**Array literals:** `[1, 2, 3]`, empty: `[]`  
**Map literals:** `["a": 1, "b": 2]`, empty: `[:]`  
**Matrix literal:**
```
let M: [[float]] = [
    [1.0, 2.0],
    [3.0, 4.0],
]
```

The empty literals `[]` and `[:]` require a type annotation on the binding (or explicit type parameter) to resolve the element/key-value types.

Array indexing: `a[i]` (zero-indexed). Matrix indexing: `M[row, col]`. Out-of-bounds access panics.

### 3.4 Tuple Types

Positional tuple: `(T, U)`. Named-field tuple: `(x: T, y: U)`.

```
fn minmax(values: [int]) → (int, int)
    → values.min(), values.max()

fn divmod(a: int, b: int) → (quotient: int, remainder: int)
    → a / b, a % b
```

Destructuring at the call site:

```
let (lo, hi) = minmax([3, 1, 4])
let (q, r)   = divmod(17, 5)
let (_, rest) = divmod(100, 7)   // _ discards a field
```

Named fields can be accessed by name: `result.quotient`.

### 3.5 Function Types

Function types are written `fn(T, U) → R`. A function that returns nothing uses the unit type `fn() → ()`.

```
let transform: fn(int) → int       = square
let compare:   fn(int, int) → bool = (a, b) → a < b
let callback:  fn() → ()           = () → print("done")
```

### 3.6 Generic Types

Generic type parameters are written in angle brackets: `List<T>`, `Map<K, V>`. Constraints use `:` for a single bound or a `where` clause for multiple bounds. Associated types (`type Item`) in interfaces create implicit genericity over the element type (see §9.3 and §13.3).

### 3.7 Result and Error Types

`Result<T>` is shorthand for `Result<T, Error>` where `Error` is a built-in interface:

```
interface Error
    fn message() → str
    fn cause()   → Error?   // default: nil
```

`Result<T, E>` holds either `Ok(value: T)` or `Err(error: E)`. `Err("string")` constructs a simple string-backed `Error`.

The `?` suffix operator propagates errors upward (see §12.2).

### 3.8 Type Aliases

```
type Meters = float
type IntPair = (int, int)
```

Type aliases are transparent: `Meters` and `float` are structurally equivalent. For a distinct nominal type, use `@newtype` (§17.3).

---

## 4. Expressions

### 4.1 Operator Precedence

Operators are listed from lowest to highest precedence:

| Level | Operators | Associativity |
|-------|-----------|---------------|
| 1 | `∨` / `or` | left |
| 2 | `∧` / `and` | left |
| 3 | `¬` / `not` | prefix (right) |
| 4 | `==` `!=`/`≠` `<` `>` `<=`/`≤` `>=`/`≥` `is` | non-associative |
| 5 | `\|>` | left |
| 6 | `\|` (bitwise OR) | left |
| 7 | `⊕` / `xor` (bitwise XOR) | left |
| 8 | `&` (bitwise AND) | left |
| 9 | `<<` `>>` | left |
| 10 | `+` `-` | left |
| 11 | `*` `/` `%` `÷` | left |
| 12 | `^` (exponentiation) | right |
| 13 | unary `-` `~` | prefix (right) |
| 14 | `?.` `!.` `?` `as?` `as!` | postfix / left |
| 15 | `()` `[]` `.` | left |

### 4.2 Arithmetic, Comparison, and Logical Operators

Standard arithmetic operators: `+`, `-`, `*`, `/` (float division), `%` (modulo), `^` (exponentiation), `÷` (integer division — the only integer division operator; `//` is a comment).

Comparison operators return `bool`: `==`, `!=`/`≠`, `<`, `>`, `<=`/`≤`, `>=`/`≥`.

Logical operators: `∧`/`and`, `∨`/`or`, `¬`/`not`. Short-circuit evaluation: `and` does not evaluate its right operand if the left is `false`; `or` does not evaluate its right operand if the left is `true`.

The built-in constants `π`, `e`, `∞` are of type `float`.

```
fn circleArea(r: float) → float
    → π * r^2

fn clampedDiv(a: int, b: int) → int
    → a ÷ b     // integer division, not float
```

### 4.3 Bitwise Operators

Bitwise operators act on integer values at the bit level:

| Operator | Meaning |
|----------|---------|
| `&` | Bitwise AND |
| `\|` | Bitwise OR |
| `~` | Bitwise NOT (complement) |
| `⊕` / `xor` | Bitwise XOR |
| `<<` | Left shift |
| `>>` | Right shift (arithmetic) |

Compound assignment forms: `&=`, `|=`, `⊕=`/`xor=`, `<<=`, `>>=`.

```
let flags = 0b1010 & 0b1100    // 0b1000
let mask  = 0b1010 | 0b0101    // 0b1111
let xord  = 0b1010 ⊕ 0b1100   // 0b0110
let hi    = 1 << 8              // 256
```

### 4.4 Lambda Expressions

```
// Single parameter — no parens needed
let double  = x → x * 2

// Multiple parameters — parens required
let add     = (x, y) → x + y

// No parameters
let noArgs  = () → print("hi")

// Explicit parameter types (inferred from context when omitted)
let stringify: fn(int) → str = x → "{x}"
```

**Capture semantics:** Closures capture variables from the enclosing scope by reference (RC reference) by default. To snapshot a value at the time the closure is created, use `[name]` in a capture list before the parameters: `[x] y → x + y`. For `go` coroutines that run concurrently, use `[copy name]` to ensure a full independent copy.

### 4.5 Pipeline Operator

`|>` passes the left-hand value as an argument to the right-hand call. `_` is the positional hole that receives the piped value; when `_` is absent the value is passed as the first argument.

```
fn process(raw: [float]) → [float]
    → raw
        |> filter(_, x → x > 0.0)   // _ receives raw
        |> normalize                  // normalize(prev result)
        |> map(_, x → x.round(4))
```

Desugaring: `a |> f(_, b)` ≡ `f(a, b)`.

### 4.6 Quantifier Expressions

Quantifiers desugar to loops over any `Iterable` value. The binding variable (before `∈`/`in`) is scoped to the quantifier body.

| Form | Result type | Semantics |
|------|-------------|-----------|
| `∀ x ∈ coll: pred` | `bool` | `true` if `pred` holds for every element |
| `∃ x ∈ coll: pred` | `bool` | `true` if `pred` holds for at least one element |
| `∑ x ∈ coll: expr` | numeric | Sum of `expr` over each element |
| `∏ x ∈ coll: expr` | numeric | Product of `expr` over each element |
| `∑ coll` | numeric | Direct sum of the collection elements |
| `∏ coll` | numeric | Direct product of the collection elements |

Imperative block form — `∀` and `∃` can open a statement block using `→`:

```
∀ v ∈ values →          // like: for v in values
    print(v)

∃ v ∈ values: v < 0.0 → // runs body for the first negative value, then stops
    print("First negative: {v}")
```

### 4.7 Range Expressions

Range notation mirrors mathematical interval notation:

| Syntax | Meaning | Equivalence |
|--------|---------|-------------|
| `[a, b]` | Closed interval | `a ≤ i ≤ b` |
| `[a, b[` | Half-open (left closed) | `a ≤ i < b` |
| `]a, b[` | Open interval | `a < i < b` |
| `]a, b]` | Half-open (right closed) | `a < i ≤ b` |

Ranges are valid in `for` loops, `match` arms, and slice expressions.

```
for i in [0, n[          // 0 … n-1 — standard array traversal
    print(i)

let middle = primes[1, 4[   // slice: elements at indices 1, 2, 3
```

**Disambiguation from array literals:** a leading `[` followed by an expression, `,`, and another expression followed by `[` or `]` is always parsed as a range. A leading `[` followed by comma-separated values is an array literal.

### 4.8 List Comprehensions

```
let squares = [x^2    for x in [1, 10]]            // 1, 4, 9, …, 100
let evens   = [x      for x in primes if x % 2 == 0]
let grid    = [i + j  for i in [0, 3[, j in [0, 3[]  // nested generators
```

`∈` and `in` are interchangeable in comprehensions. Multiple generators are evaluated as nested loops (leftmost is outermost). An optional `if pred` guard filters elements before the body expression is evaluated.

### 4.9 Match Expressions

`match` is an expression (and can also be used as a statement). The compiler enforces exhaustiveness.

```
fn classify(n: int) → str
    match n
        0:          → "zero"
        1, 2, 3:    → "small"
        [4, 9]:     → "medium"
        [10, 99]:   → "large"
        _:          → "huge"
```

Arm forms:
- **Literal value:** `42:`
- **Comma-separated list:** `1, 2, 3:`
- **Range:** `[a, b]:` — uses the same interval notation as §4.7
- **Sealed class variant:** `Dog(name):` (see §10.1)
- **Wildcard:** `_:` matches anything (required when not all cases are covered)
- **Or-patterns:** `A | B:` — two variants share one arm body
- **Guard clause:** `[90, 100] if score ≠ 100:` — arm is selected only if the guard is true
- **Let binding in arm:** `(200, let b):` — binds part of the matched value

Each arm body is either an inline `→ expr` or an indented block of statements. Inline arms that span multiple logical lines use an indented block.

### 4.10 Inline If Expressions

The `then` keyword distinguishes the inline (expression) form of `if` from the statement form:

```
fn signum(x: float) → int
    → if x > 0.0 then 1 else if x < 0.0 then -1 else 0

let abs = x → if x < 0.0 then -x else x
```

The `else` branch is mandatory in the inline form. There is no ternary `?:` operator; `?` is reserved for nullable types and error propagation.

### 4.11 Type Tests and Casts

| Operator | Result type | Semantics |
|----------|-------------|-----------|
| `expr is T` | `bool` | Tests whether `expr` has type `T`; narrows the type in the then-branch |
| `expr as? T` | `T?` | Safe cast — returns `nil` if the value is not a `T` |
| `expr as! T` | `T` | Forced cast — panics at runtime if the value is not a `T` |

**Smart cast with `is`:** if `v is Circle` is the condition of an `if` statement, the variable `v` has type `Circle` (not `Shape`) within the then-branch, without an explicit cast.

```
if shape is Circle
    print(shape.radius)   // shape: Circle in this branch
```

### 4.12 String Interpolation

Inside a double-quoted string, `{expr}` embeds the string representation of `expr`. `{expr:spec}` passes a format specifier:

| Specifier | Meaning |
|-----------|---------|
| `:.4f` | Float with 4 decimal places |
| `:.2f` | Float with 2 decimal places |
| `:6d` | Integer padded to width 6 |
| `:s` | String (default for `str`) |

Nested braces inside the interpolated expression are matched by the lexer's interpolation mode stack, so `{"key": value}` works correctly.

---

## 5. Statements

### 5.1 Variable Declarations

```
let x = 42           // immutable; type inferred as int
let name: str = "Alice"

var count: int = 0   // mutable; type explicit
var label: str       // mutable; must be assigned before use
```

`let` bindings are immutable after initialisation. Attempting to assign to a `let` binding is a compile error. `var` bindings may be reassigned any number of times with values of the same type.

Type inference is bidirectional: the type can be inferred from the initialiser, or the declared type can constrain the initialiser.

`weak var x: T?` declares a weak reference (does not increment the RC; see §16.1).

### 5.2 Assignment

Simple assignment: `x = expr`. Compound assignment operators: `+=`, `-=`, `*=`, `/=`, `%=`, `^=`, `&=`, `|=`, `⊕=`, `<<=`, `>>=`, `÷=`.

The left-hand side must be an lvalue: a `var` binding, a field access (`obj.field`), or a subscript expression (`arr[i]`).

Tuple destructuring assignment:

```
(lo, hi) = minmax(values)
(_, rest) = divmod(100, 7)
```

### 5.3 If Statement

```
if condition
    // then block

if condition
    // then block
else
    // else block

if condition
    // then block
else if otherCondition
    // else-if block
else
    // fallback block
```

**`if let`** — unwraps a nullable expression. The binding is non-nullable within the then-block:

```
if let u = findUser(42)
    print("Welcome, {u.name}!")   // u: User (not User?)
else
    print("Not found")
```

Chained `if let` — all bindings must succeed for the block to execute:

```
if let u = findUser(42), let city = u.address?.city
    print("User in {city}")
```

**`guard let`** — an early-exit pattern. The `else` block *must* exit the current scope (via `return`, `break`, `continue`, or `throw`). The binding remains in scope *after* the guard:

```
guard let u = user else
    print("User not found")
    return
// u is non-nullable here
print("Welcome, {u.name}!")
```

### 5.4 For and While Loops

**For-in loop:**

```
for x in collection
    print(x)

// With range notation
for i in [0, n[
    print(i)

// Tuple destructuring
for (i, v) in values.enumerate()
    print("{i}: {v}")
```

**While loop:**

```
var x = 1.0
while x < 1000.0
    x *= 2.0
```

**Labeled loops:** prefix the loop keyword with `@label`:

```
@outer for i in [0, n[
    @inner for j in [0, m[
        if condition
            break @outer
```

### 5.5 Break and Continue

`break` exits the immediately enclosing loop. `continue` skips to the next iteration of the immediately enclosing loop.

`break @label` / `continue @label` target the loop with the corresponding `@label` annotation, allowing early exit from nested loops.

### 5.6 Defer Statement

`defer stmt` schedules `stmt` to run when the enclosing *scope* exits, regardless of how it exits (normal return, early `return`, or thrown exception).

Multiple `defer` statements in the same scope execute in LIFO (last-in, first-out) order:

```
fn withLocks()
    lockA()
    defer unlockA()   // fires second
    lockB()
    defer unlockB()   // fires first
    // work…
```

`defer` interacts with the RC lifecycle: the deferred statement runs before the final RC decrement of any local class instances in scope. This guarantees that file handles, locks, and other resources are released before the enclosing function's return path unwinds.

### 5.7 Pattern Matching Statements

`match` used as a statement: the result of each arm is discarded. See §4.9 for arm syntax. Exhaustiveness is still enforced.

```
match direction
    North: moveUp()
    South: moveDown()
    East:  moveRight()
    West:  moveLeft()
```

### 5.8 Try / Catch / Finally

Used with functions that `throws` a checked exception type:

```
try
    let config = readConfig("app.conf")
    process(config)
catch e: IOError
    print("IO error: {e.message}")
catch e: ParseError
    print("Parse error: {e.message}")
finally
    cleanup()
```

Multiple `catch` clauses are tested in order; the first matching type wins. `finally` always executes regardless of whether an exception was thrown. A `try` block without `catch` (but with `finally`) re-throws caught exceptions after the `finally` block runs.

### 5.9 Throw Statement

`throw expr` raises an exception of the declared `throws` type. Valid only inside a function annotated with `throws E`:

```
fn validate(age: int) throws ValidationError
    if age < 0
        throw ValidationError("age cannot be negative: {age}")
```

---

## 6. Functions

### 6.1 Function Declarations

```
fn square(x: float) → float
    → x * x

// Return type inferred from → expression
fn cube(x: float)
    → x^3

// Multi-statement body — return type must be annotated if not inferred
fn abs(x: float) → float
    if x < 0.0
        return -x
    return x
```

`→ expr` and `return expr` are interchangeable. `→` without an expression is equivalent to `return` (returns unit). A function body is an indented block of statements.

### 6.2 Parameters

**Positional parameters** must be supplied in order:

```
fn add(a: int, b: int) → int
    → a + b
```

**Default values** make a parameter optional:

```
fn greet(name: str, greeting: str = "Hello") → str
    → "{greeting}, {name}!"
```

**Named arguments** may be supplied in any order at the call site:

```
connect("db.local", port: 5432, secure: true)
greet(greeting: "Hi", name: "Alice")
```

Rule: positional arguments must precede named arguments. After the first named argument, all subsequent arguments must also be named.

**Nullable with nil default** — the caller may omit the argument entirely:

```
fn log(message: str, level: str? = nil)
    let l = level ?? "INFO"
    print("[{l}] {message}")

log("started")            // level = nil → "INFO"
log("warn", level: "W")
```

**Variadic parameters** — `T...` collects remaining arguments into an array:

```
fn sum(values: int...) → int
    var total = 0
    for v in values
        total += v
    → total

sum(1, 2, 3, 4)   // values = [1, 2, 3, 4]
```

### 6.3 Computed Properties

A function with no parameter list is a *computed property* — called with `obj.name`, not `obj.name()`:

```
class Circle(radius: float)
    fn area → float
        → π * self.radius^2

    fn diameter → float
        → 2.0 * self.radius
```

**Settable properties** use `get`/`set` blocks:

```
class Temperature(celsius: float)
    fn fahrenheit → float
        get → self.celsius * 9.0 / 5.0 + 32.0
        set(f) self.celsius = (f - 32.0) * 5.0 / 9.0
```

Assignment to a settable property (`t.fahrenheit = 32.0`) calls the `set` block.

### 6.4 Subscript Operators

`fn []` (getter) and `fn []=` (setter) enable `obj[key]` / `obj[key] = val` syntax on user-defined types:

```
class Grid<T>(rows: int, cols: int, fill: T)
    data: [T] = [fill for _ in [0, rows * cols[]

    fn [](row: int, col: int) → T
        → self.data[row * self.cols + col]

    fn []=(row: int, col: int, value: T)
        self.data[row * self.cols + col] = value
```

```
let g = Grid<int>(3, 3, fill: 0)
g[1, 1] = 42
print(g[1, 1])     // 42
```

### 6.5 Trailing Lambda Syntax

When the last argument to a function is a lambda, it may be written outside the parentheses using `{ }`. When the lambda is the only argument, the empty `()` may be omitted:

```
let evens = [1, 2, 3, 4, 5].filter { x → x % 2 == 0 }

items.forEach { item →
    print(item.name)
    total += item.price
}
```

Curly braces `{}` are used exclusively for trailing lambda bodies — they are not general block delimiters.

### 6.6 Async Functions

`async fn` marks a function as asynchronous. The `await` keyword is only valid inside an `async fn`:

```
async fn fetchData(url: str) → Result<str>
    let response = await http.get(url)?
    → Ok(response.body)
```

Calling an `async fn` from a non-async context requires `spawn` (returns `Future<T>`) or `go` (fire-and-forget):

```
let fa = spawn fetchData(url)   // Future<Result<str>>
let result = await fa
```

See §15 for full concurrency documentation.

---

## 7. Classes and Objects

### 7.1 Primary Constructors

Fields declared on the class line form the primary constructor. The compiler auto-generates an initialiser that accepts those fields:

```
class Point(x: float, y: float)
    fn distance(other: Point) → float
        let dx = self.x - other.x
        let dy = self.y - other.y
        → (dx^2 + dy^2).sqrt()
```

**Body fields** — declared inside the class body with an initialiser expression. Every new instance starts with that value. They are NOT constructor arguments:

```
class HttpClient(baseUrl: str, timeout: int = 30)
    headers: [str: str] = [:]   // per-instance initialiser
    retries: int = 3
```

**Explicit `init` block** — for custom construction logic, add an `init` block (no `fn` keyword, no return type):

```
class Square(topLeft: Point, side: float) : Rectangle
    init
        super.init(topLeft, Point(topLeft.x + side, topLeft.y - side))
```

Body fields are initialised before the `init` block runs.

### 7.2 Inheritance

Single-class inheritance via `:` on the class declaration:

```
class Animal(name: str)
    fn speak() → str
        → "..."

class Dog(name: str) : Animal
    override fn speak() → str
        → "Woof!"
```

`override fn` is required when replacing a superclass method. `super.method(args)` calls the superclass implementation. `super.init(args)` calls the superclass constructor from within an `init` block.

A class may also implement multiple interfaces: `class C(…) : ParentClass, Interface1, Interface2`.

### 7.3 Reference Semantics

`class` is a reference type. An assignment copies the reference, not the object. Two variables may point to the same instance:

```
let a = Point(1.0, 2.0)
let b = a        // b and a refer to the same Point
```

Reference counts are maintained atomically (safe for goroutine sharing). When the last strong reference drops, the object is freed and any `defer` statements in the constructor scope run.

### 7.4 Weak and Unowned References

Reference cycles between class instances cause memory leaks because the retain counts never reach zero. Break cycles with:

**`weak var x: T?`** — does not increment the retain count. Automatically set to `nil` when the referent is freed. Declared as a `var` because the compiler must be able to zero it:

```
class TreeNode(value: int)
    var children: [TreeNode] = []
    weak var parent: TreeNode? = nil

    fn addChild(child: TreeNode)
        child.parent = self
        self.children.append(child)
```

**`unowned let x: T`** — does not increment the retain count and is never zeroed. Accessing an unowned reference after the referent is freed crashes. Use only when the referenced object's lifetime is guaranteed to exceed the referent's:

```
class Account(name: str)
    unowned let bank: Bank   // account cannot outlive its bank
```

Rule of thumb: parents hold strong references to children; children hold `weak` references back to their parent. See ADR-001.

---

## 8. Value Types: Structs and Records

### 8.1 Structs

`struct` declares a stack-allocated value type. Assignment copies the entire struct; there is no reference counting:

```
struct Vec2(x: float, y: float)
    fn length() → (self.x^2 + self.y^2).sqrt()
    fn +(other: Vec2) → Vec2
        → Vec2(self.x + other.x, self.y + other.y)

var a = Vec2(1.0, 2.0)
var b = a          // b is an independent copy
b.x = 10.0
print(a.x)         // 1.0 — a is unchanged
```

Structs:
- May have methods and implement interfaces.
- Cannot inherit from other structs or classes.
- Have no RC overhead.
- Fields are mutable (`var` semantics) unless the binding holding the struct is `let`.

Use structs for small, performance-sensitive data (coordinates, colours, spans) where copy semantics are natural.

### 8.2 Records

`record` is an immutable reference type. It auto-generates `==`, `hash()`, and `str()` based on its fields:

```
record Color(r: int, g: int, b: int)
record Point(x: float, y: float)

let red  = Color(255, 0, 0)
let blue = Color(0, 0, 255)
print(red == blue)    // false
print(red)            // Color(r: 255, g: 0, b: 0)
```

Records are heap-allocated with atomic reference counting, but their fields cannot be mutated after construction. Because records have no mutable back-references, they cannot form reference cycles.

`.copy(field: newValue)` creates a shallow copy with one field changed — requires `@derive(Copy)` or `@derive(All)` (see §17.1).

**`struct` vs `record` vs `class`:**

| | `struct` | `record` | `class` |
|-|----------|----------|---------|
| Allocation | Stack | Heap (RC) | Heap (RC) |
| Mutability | Mutable fields | Immutable | Mutable fields |
| `==` / `hash()` | Must implement | Auto-generated | Must implement |
| Inheritance | No | No | Single inheritance |
| Copy semantics | By value | By reference | By reference |

---

## 9. Interfaces and Extensions

### 9.1 Interface Declarations

An interface declares a contract — method signatures, optional default implementations, and associated types:

```
interface Shape
    fn area()      → float
    fn perimeter() → float
    fn describe()  → str        // default implementation
        → "Shape with area {self.area():.2f}"
```

A class, struct, or record conforms to an interface by listing it after `:` and implementing all required methods:

```
class Circle(center: Point, radius: float) : Shape
    fn area()      → π * self.radius^2
    fn perimeter() → 2.0 * π * self.radius
```

Default implementations are inherited automatically unless overridden.

### 9.2 Extension Functions

`extend TypeName` adds methods to an existing type without subclassing or modifying the original source. Extension methods are statically dispatched and cannot access private fields:

```
extend int
    fn isPrime() → bool
        if self < 2: → false
        → ∀ i ∈ [2, self ÷ 2]: self % i ≠ 0

    fn factorial() → int
        → ∏ k ∈ [1, self]: k

extend str
    fn words() → [str]   → self.split(" ")
```

Extensions can add methods to any type, including built-in primitives and types from other modules.

**Constrained extensions** — apply only when the element type satisfies a constraint:

```
extend [T] where T: Comparable<T>
    fn minmax() → (T, T)
        → self.min(), self.max()
```

**Retroactive conformance** — `extend T : Interface` declares that an existing type satisfies an interface. This conformance is module-scoped: only code that imports the declaring module can use it. Two modules may not declare conflicting conformances for the same type + interface pair:

```
extend int : Printable
    fn prettyPrint() → str
        → "int({self})"
```

### 9.3 Associated Types

An interface may declare `type Name` — an abstract type filled in by each conforming type:

```
interface Container
    type Item
    fn add(item: Item)
    fn get(index: int) → Item?
    fn size() → int

class Bag<T> : Container
    type Item = T
    items: [T] = []
    fn add(item: T)          self.items.append(item)
    fn get(index: int) → T?  → self.items[index]
    fn size() → int          → self.items.length
```

`where` clauses may constrain associated types:

```
fn printAll<C: Container>(c: C) where C.Item: Printable
    for i in [0, c.size()[
        print(c.get(i)!)
```

### 9.4 Interface Delegation

`class C : I by expr` forwards all methods of interface `I` to `expr`. Individual methods may still be overridden in `C`:

```
class LoggingList<T>(inner: MutableList<T>) : MutableList<T> by inner
    override fn add(item: T) → bool
        log("add {item}")
        → inner.add(item)
```

All methods of `MutableList<T>` not overridden in `LoggingList` are forwarded to `inner` automatically. Delegation is statically dispatched.

---

## 10. Sealed Classes and Enums

### 10.1 Sealed Classes

A sealed class defines a fixed set of variants. The compiler knows the complete set and enforces exhaustive matching:

```
sealed class Shape
    Circle(radius: float)
    Rect(w: float, h: float)
```

Each variant is implicitly a sub-type of the sealed class. Variants may have fields (as shown) or be fieldless:

```
sealed class Animal
    Dog(name: str)
    Cat(name: str, indoor: bool)
    Bird(species: str)

fn describe(a: Animal) → str
    match a
        Dog(name):          → "{name} wags its tail"
        Cat(name, true):    → "{name} lounges inside"
        Cat(name, false):   → "{name} roams outside"
        Bird(species):      → "A {species} takes flight"
```

**Recursive sealed types** — variants may reference the sealed class itself, enabling linked lists, trees, and expression trees:

```
sealed class Tree<T>
    Leaf(value: T)
    Node(left: Tree<T>, right: Tree<T>)

fn depth<T>(t: Tree<T>) → int
    match t
        Leaf(_):            → 1
        Node(left, right):  → 1 + max(depth(left), depth(right))
```

### 10.2 Enums

Simple enums declare named constants with no associated data:

```
enum Direction
    North, South, East, West

enum Suit
    Clubs, Diamonds, Hearts, Spades
```

**Raw-value enums** associate each case with a compile-time constant of a primitive type:

```
enum HttpStatus: int
    Ok = 200, NotFound = 404, ServerError = 500
```

Access the raw value via `.rawValue`. Raw values must be unique within the enum.

`match` on an enum is exhaustive:

```
fn describe(d: Direction) → str
    match d
        North: → "heading north"
        South: → "heading south"
        East:  → "heading east"
        West:  → "heading west"
```

For variants that need associated data, use a sealed class (§10.1).

---

## 11. Null Safety

### 11.1 Nullable Types and Safe Navigation

All types are non-nullable by default. Adding `?` permits `nil`:

```
fn findUser(id: int) → User?   // may return nil

let user: User? = findUser(42)
```

**Safe navigation `?.`** — short-circuits to `nil` if any step is `nil`:

```
let city = user?.address?.city    // str? — nil if user or address is nil
```

**Null-coalescing `??`** — provides a default when the left side is `nil`:

```
let name = user?.name ?? "Guest"   // str — never nil
```

**Forced unwrap `!.`** — accesses the value directly; panics at runtime if `nil`:

```
let name = user!.name    // crashes if user is nil
```

Use `!.` only when you have out-of-band knowledge that the value is non-nil.

### 11.2 if let and guard let

See §5.3 for the full syntax. Key semantics:

- `if let x = expr` — `x` is bound as a non-nullable value; valid only within the then-block.
- `guard let x = expr else { … }` — `x` is bound as a non-nullable value; valid in the scope *after* the guard. The `else` block must unconditionally exit (via `return`, `break`, `continue`, or `throw`).
- Chained `if let` — `if let a = e1, let b = e2` — all bindings must succeed for the block to run.

### 11.3 Flow Analysis and Smart Cast

After an `is` check or a successful `if let`, the compiler narrows the type within the controlled scope:

```
if shape is Circle
    // shape has type Circle here — no cast required
    print(shape.radius)
```

Limitations:
- Smart casts do not cross reassignment. If `shape` is reassigned inside the then-block, the narrowed type is lost.
- Smart casts do not apply inside closures that capture the variable (the closure may outlive the narrowing scope).

---

## 12. Error Handling

### 12.1 Result Types

`Result<T, E>` holds either `Ok(value: T)` or `Err(error: E)`.

`Result<T>` is a shorthand for `Result<T, Error>`.

```
fn parseInt(s: str) → Result<int>
    if ∀ c ∈ s: c.isDigit()
        → Ok(s.toInt())
    → Err("'{s}' is not a valid integer")

fn divide(a: float, b: float) → Result<float>
    if b == 0.0
        → Err("division by zero")
    → Ok(a / b)
```

Combinators:
- `result.getOrElse(default)` — unwraps `Ok` or returns the default.
- `result.getOrThrow()` — unwraps `Ok` or throws the error (in a `throws` context).

### 12.2 The ? Propagation Operator

`expr?` inside a function returning `Result<_, E>` unwraps `Ok` or immediately returns `Err` to the caller:

```
fn compute(a: str, b: str) → Result<float>
    let x = parseInt(a)?     // returns Err if a is not a valid int
    let y = parseInt(b)?
    → divide(x.toFloat(), y.toFloat())
```

`?` may also be used in `async fn` bodies on `Future<Result<T, E>>` values.

### 12.3 throws Sugar

`fn f() throws E → T` is syntactic sugar for `fn f() → Result<T, E>`. The `throws` annotation expresses that the error is a side-effect (I/O, network) rather than a normal outcome:

```
fn readConfig(path: str) → str throws IOError
    → File.read(path)
```

Both forms interoperate: a `throws` function can be called with `?` in a `Result` context, and a `Result`-returning function can be called inside a `try` block.

### 12.4 Checked Exceptions vs. Result

Use `throws E` when:
- The error is a side-effect of I/O or system interaction.
- The call site should use `try`/`catch`.

Use `Result<T, E>` directly when:
- The error is a normal outcome (parsing, lookup, validation).
- Callers will typically handle the error inline with `match` or `?`.

The two styles are interchangeable at the call site: `?` works on both.

---

## 13. Generics

### 13.1 Generic Functions and Classes

Generic type parameters are declared in angle brackets. Type arguments are inferred at call sites:

```
fn identity<T>(x: T) → T  → x

fn first<T>(items: [T]) → T?
    → if items.isEmpty() then nil else items[0]

class Stack<T>
    items: [T] = []
    fn push(item: T)   self.items.append(item)
    fn pop() → T?
        if self.items.isEmpty() → nil
        → self.items.removeLast()
```

Usage:

```
let s = Stack<int>()
s.push(42)
let top = s.pop()   // T inferred as int
```

### 13.2 Type Constraints

**Single constraint** — inline after `:`:

```
fn max<T: Comparable<T>>(a: T, b: T) → T
    → if a.compareTo(b) ≥ 0 then a else b
```

**Multiple constraints** — `where` clause:

```
fn printAndCompare<T>(a: T, b: T) where T: Comparable<T>, T: Printable
    print("{a} vs {b}")
```

`where` can also appear on a class declaration:

```
class SortedList<T> where T: Comparable<T>
    items: [T] = []
    fn insert(item: T)
        self.items.append(item)
        self.items.sort()
```

### 13.3 Associated Types

See §9.3 for associated types in interfaces. The `where` clause may constrain an associated type:

```
fn printAll<C: Container>(c: C) where C.Item: Printable
    for i in [0, c.size()[
        print(c.get(i)!)
```

The built-in `Iterator` and `Iterable` interfaces use associated types:

```
interface Iterator
    type Item
    fn next() → Item?

interface Iterable
    type Item
    fn iter() → Iterator where Iterator.Item == Item
```

`for x in coll` desugars to `let it = coll.iter(); while let x = it.next() { … }`.

---

## 14. Modules and Visibility

### 14.1 Module Declarations and Imports

Each `.nv` file optionally begins with a `module` declaration:

```
module geometry
```

Imports:

```
import std.math
import std.io
import std.collections.HashMap
import myapp.utils as utils     // alias
```

Imported symbols are accessed with the module name prefix unless aliased. A bare `import myapp.utils as utils` makes `utils.symbol` available.

### 14.2 Visibility Levels

| Modifier | Visible to |
|----------|-----------|
| `pub` | All importers of this package |
| `pub(pkg)` | All modules within the same package |
| (none) | The current file only |

```
pub class Vector2D(pub x: float, pub y: float)
    pub fn length() → float
        → (self.x^2 + self.y^2).sqrt()

    pub(pkg) fn validate()      // package-internal
        if self.length() == 0.0
            throw Error("zero vector")

    fn cacheKey() → str         // file-private
        → "{self.x},{self.y}"
```

Individual constructor fields may carry their own `pub`/`pub(pkg)` annotation.

### 14.3 Sub-modules and mod.nv

All `.nv` files in the same directory that share the same `module` declaration form one logical module. Subdirectories form sub-modules automatically:

```
geometry/core.nv     → module geometry          (one logical module)
geometry/helpers.nv  → module geometry          (same module, second file)
geometry/shapes/     → module geometry.shapes   (automatic sub-module)
```

`mod.nv` controls which sub-modules are re-exported:

```
// geometry/mod.nv
module geometry
pub import geometry.shapes    // expose geometry.shapes to consumers
```

Consumers write `import geometry.shapes` and access symbols as `geometry.shapes.Circle`. Symbols are not merged into the parent namespace.

### 14.4 Conditional Compilation

`@if` / `@else` at declaration scope select which declarations are compiled:

```
@if(platform == "linux")
fn getMemoryUsage() → int
    // Linux-specific implementation
    ...

@else
fn getMemoryUsage() → int
    → 0
```

Supported predicates:

| Predicate | Values |
|-----------|--------|
| `platform` | `"linux"`, `"macos"`, `"windows"` |
| `arch` | `"x86_64"`, `"arm64"` |
| `debug` | `true` / `false` |
| `release` | `true` / `false` |
| `feature("name")` | `true` / `false` |

---

## 15. Concurrency

The runtime uses an M:N green-thread scheduler — one OS thread per CPU core with work-stealing. Goroutines are lightweight coroutines; channel sends and I/O suspend the coroutine without blocking the underlying OS thread.

### 15.1 Goroutines (go, spawn)

**`go callExpr`** — fire-and-forget. Launches `callExpr` on the scheduler; the result is discarded:

```
go producer(ch)
```

**`spawn callExpr`** — launches `callExpr` and returns `Future<T>`. The result can be awaited later:

```
let fa = spawn fetchData(urlA)
let fb = spawn fetchData(urlB)
let (a, b) = (await fa getOrElse "", await fb getOrElse "")
```

Capture lists: closures passed to `go` must use `[copy name]` for any variables that are captured by value, to prevent data races:

```
go [copy item] → processItem(item)
```

### 15.2 Channels

Channels provide explicit, typed message passing between goroutines:

```
let ch = Channel<int>(capacity: 5)   // buffered

go producer(ch)
consumer(ch)
```

```
fn producer(ch: Channel<int>)
    for i in [1, 10]
        ch.send(i)
    ch.close()

fn consumer(ch: Channel<int>)
    for value in ch        // iterates until channel is closed
        print("Got: {value}")
```

`ch.send(v)` blocks if the channel is full. `ch.recv()` blocks until a value is available. Iterating `for x in ch` exits when the channel is closed and drained.

### 15.3 Select Statement

`select` multiplexes across channel operations; the first ready branch runs non-deterministically:

```
async fn relay(src: Channel<str>, dst: Channel<str>, quit: Channel<bool>)
    @loop while true
        select
            msg from src:   dst.send(msg)
            _ from quit:    break @loop
            after 5000ms:   print("relay: still waiting…")
```

`after <duration>` provides a timeout branch. `default:` is a non-blocking poll: the `default` arm runs immediately if no other channel is ready.

### 15.4 Async/Await

`async fn` declares an asynchronous function. `await expr` suspends the current goroutine until the future resolves. `await` is only valid inside `async fn`:

```
async fn fetchData(url: str) → Result<str>
    let response = await http.get(url)?
    → Ok(response.body)
```

`Future<T>` is the return type of a `spawn` expression or a call to an `async fn` from a non-async context.

### 15.5 Structured Concurrency

`TaskGroup` ensures all spawned tasks complete (or are cancelled) before the group exits. A failure in any task cancels the rest and propagates the error:

```
async fn fetchAll(urls: [str]) → Result<[str]>
    var results: [str] = []
    await TaskGroup
        for url in urls
            spawn
                let r = fetchData(url)?
                results.append(r)
    → Ok(results)
```

---

## 16. Memory Management

### 16.1 Reference Counting

`class` and `record` instances are heap-allocated. Each object carries a 16-byte header: an atomic retain count and a destructor pointer.

| Kind | Allocation | RC | Mutability |
|------|-----------|-----|------------|
| `class` | Heap | Atomic | Mutable fields |
| `record` | Heap | Atomic | Immutable |
| `struct` | Stack | None | Mutable |

When the retain count reaches zero, the destructor chain runs (calling `defer` statements that were registered in the constructor scope), then the memory is freed.

All RC operations use atomic compare-and-swap so that instances are safe to share across goroutines without additional locking.

`struct` values have zero RC overhead: they are copied on assignment and freed when their owning stack frame is popped.

Cycle prevention: use `weak var` (nullable, zeroed on free) and `unowned let` (non-nullable, crash-on-access after free) to break parent→child reference cycles. See §7.4 and ADR-001.

### 16.2 Unsafe Code

Operations on raw pointers require an `unsafe` block:

```
fn rawFill(dst: ptr<byte>, value: byte, n: int)
    unsafe
        for i in [0, n[
            dst[i] = value
```

`ptr<T>` is the raw pointer type. Pointer arithmetic and dereferencing are only allowed inside `unsafe` blocks.

`@c` and `@cpp` inline blocks are implicitly unsafe — they embed verbatim C or C++ code in the generated output:

```
fn memcopy(dst: ptr<byte>, src: ptr<byte>, n: int)
    @c
        memcpy(dst, src, n);

fn sortInts(data: [int])
    @cpp
        std::sort(data.ptr, data.ptr + data.length);
```

`@extern` declares a function resolved at link time from a C library:

```
@extern("libm")
fn sin(x: float64) → float64

@extern(fn: "stbi_load", lib: "stb_image")
fn loadImage(path: str, w: ptr<int>, h: ptr<int>, ch: ptr<int>, req: int) → ptr<byte>
```

---

## 17. Annotations and Metaprogramming

### 17.1 @derive

`@derive` auto-generates trait implementations at compile time:

| Trait | Generated behaviour |
|-------|---------------------|
| `Show` | `str()` method that prints all fields |
| `Eq` | `==` / `!=` based on structural field equality |
| `Hash` | `hash()` method based on all fields |
| `Compare` | `compareTo()` based on field order |
| `Copy` | `.copy(field: val)` method for shallow-copy-with-override |
| `All` | All five of the above |

```
@derive(All)
struct Point(x: float, y: float)

let p1 = Point(1.0, 2.0)
let p2 = p1.copy(y: 9.0)    // Point(x: 1.0, y: 9.0)

@derive(Show, Eq, Hash)
class Color(pub r: int, pub g: int, pub b: int)
```

### 17.2 @builder

`@builder` generates a Builder class and a `.build { }` DSL block for complex object construction. Required fields (those without defaults) are validated at `.build()` time:

```
@builder
class Request(url: str, method: str = "GET", timeout: Duration = Duration.seconds(30))

let req = Request.build
    url    = "https://example.com"
    method = "POST"
```

### 17.3 @newtype

`@newtype struct T(U)` — a zero-cost nominal wrapper around `U`. It has the same memory layout as `U` but is a distinct type. `.value` unwraps to `U`. Auto-derives `Eq`, `Hash`, `Compare`, and `Show` from the inner type:

```
@newtype struct UserId(int)
@newtype struct Seconds(float)

let id: UserId = UserId(42)
let raw: int   = id.value

// UserId and int are not interchangeable:
// fn process(id: UserId) — cannot pass an int directly
```

### 17.4 @lazy

`@lazy val field = expr` — the field is computed once on first access, then cached. Subsequent accesses return the cached value:

```
class Parser(source: str)
    @lazy val tokens: [Token] = tokenize(source)
```

`@lazy var field = expr` — computed once on first access, but setting the field to `nil` causes it to be recomputed on the next access.

`@lazy` is valid on body fields only, not constructor parameters.

### 17.5 @config and @env

`@config("prefix")` generates a static `.load(from: Config) → Result<T, str>` method that reads configuration values from a `Config` object (TOML files + environment variables). Fields with defaults are optional; non-nullable fields without defaults are required:

```
@config("server")
struct ServerConfig(
    host: str = "0.0.0.0",
    port: int = 8080,
    tls:  bool = false
)

@config("database")
struct DatabaseConfig(
    @env("DATABASE_URL")
    url: str,                               // required

    @env("DB_PASSWORD", sensitive: true)
    password: str? = nil                    // optional; masked in logs
)
```

`@env("VAR_NAME")` binds a specific environment variable to a field. `sensitive: true` masks the value in `Show` output, logs, and debug printouts.

When `@env` is absent, environment variable names are auto-derived as `UPPER_SNAKE(prefix)_UPPER_SNAKE(field)` — e.g., `server.port` → `SERVER_PORT`.

`ConfigLoader.default()` merges: env vars > `config.local.toml` > `config.{NV_ENV}.toml` > `config.toml` > defaults.

```
fn main() throws
    let cfg    = ConfigLoader.default().load()?
    let server = cfg.bind<ServerConfig>()?
    let db     = cfg.bind<DatabaseConfig>()?
    print("Listening on {server.host}:{server.port}")
```

`@config("prefix", reload: true)` enables hot-reload: `cfg.onReload { … }` runs the callback whenever the config file changes.

### 17.6 @extern, @c, @cpp, @asm, @gpu

`@extern` — link-time C symbol binding (see §16.2).

`@c` / `@cpp` — inline C or C++ blocks (see §16.2).

`@asm[arch]` — inline native assembly. The block runs on the target architecture; blocks for non-matching architectures are silently skipped:

```
fn fastAbs(x: int) → int
    @asm[x86_64]
        mov  rax, x
        neg  rax
        cmovl rax, x
        ret
    @asm[arm64]
        cmp  x0, #0
        cneg x0, x0, mi
        ret
```

`@asm[arch, feature]` — additionally gates on a CPU feature flag (e.g., `avx2`, `neon`).

`@bytes[arch]` — embed raw machine code bytes:

```
fn cpuId() → int
    @bytes[x86_64]
        0x0F 0xA2   // CPUID
        0xC3        // RET
```

`@gpu` — marks a function as a GPU kernel compiled for CUDA / Metal / Vulkan depending on the target platform. GPU functions are called identically to CPU functions; the runtime handles data transfer:

```
@gpu
fn addVectors(a: [float32], b: [float32]) → [float32]
    → [a[i] + b[i] for i in [0, a.length[]
```

`@CLayout` — forces C-compatible struct layout (field order + padding) for interop:

```
@CLayout
struct Vec3(x: float32, y: float32, z: float32)
```

### 17.7 Conditional Compilation Annotations

`@if(predicate)` / `@else` select declarations at compile time (see §14.4). Predicates are evaluated by the compiler before code generation; only the selected branch is compiled into the binary.
