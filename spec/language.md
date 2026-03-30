# Nordvest Language Specification

> **Status**: Outline only — Phase 0.4.
> The normative grammar is [`spec/nv.peg`](nv.peg).
> This document provides the narrative rationale and detailed rules that accompany the grammar.
> Sections marked *TODO* will be filled in progressively during Phases 1–3.

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

> *TODO: fill in — design axioms, non-goals, comparison to related languages.*

### 1.2 Notation Used in This Document

> *TODO: fill in — how grammar productions are cited, how examples are formatted.*

### 1.3 Program Structure

> *TODO: fill in — module declaration, file layout, entry point (`fn main()`).*

---

## 2. Lexical Structure

### 2.1 Source Encoding

> *TODO: fill in — UTF-8 only, BOM handling. See ADR-002.*

### 2.2 Indentation

> *TODO: fill in — INDENT/DEDENT injection rules, tab expansion (1 tab = 4 spaces), mixed-indent error.*

### 2.3 Integer and Float Literals

> *TODO: fill in — decimal/hex/binary/octal forms, underscore separators, float exponent.*

### 2.4 String Literals

> *TODO: fill in — double-quoted, escape sequences, string interpolation `{expr}` and `{expr:fmt}`, raw strings `r"..."` and `r"""..."""`.*

### 2.5 Identifiers and Keywords

> *TODO: fill in — XID_Start/XID_Continue, NFC normalization, exhaustive keyword list. See ADR-002.*

### 2.6 Unicode Operators and Constants

> *TODO: fill in — operator token table (→ ∀ ∃ ∑ ∏ ∈ ∧ ∨ ¬ ≤ ≥ ≠ ⊕ ÷), ASCII aliases, π/e/∞ constants, disambiguation rules (`//` always comment, `^` always exponentiation, `|>` maximal munch).*

### 2.7 Comments

> *TODO: fill in — `//` line comments, `/* */` block comments, `/** */` doc comments (retained in AST).*

---

## 3. Types

### 3.1 Primitive Types

> *TODO: fill in — `int`, `int8`, `int16`, `int32`, `int64`, `float`, `float32`, `float64`, `bool`, `str`, `byte`, `char`; overflow semantics; widening rules.*

### 3.2 Nullable Types

> *TODO: fill in — `T?` syntax, nil value, non-nullable by default, flow analysis.*

### 3.3 Collection Types

> *TODO: fill in — `[T]` arrays, `[K:V]` maps, `[[T]]` matrices; literal syntax; empty literals `[]` and `[:]`.*

### 3.4 Tuple Types

> *TODO: fill in — positional `(T, U)` and named-field `(x: T, y: U)` tuples; destructuring.*

### 3.5 Function Types

> *TODO: fill in — `fn(T, U) → R` type syntax; void type `fn() → ()`; type inference for lambdas.*

### 3.6 Generic Types

> *TODO: fill in — `List<T>`, `Map<K, V>`, constraints, `where` clauses, associated types.*

### 3.7 Result and Error Types

> *TODO: fill in — `Result<T>` (= `Result<T, Error>`), `Result<T, E>`, `Ok(v)`, `Err(e)`, `?` propagation.*

### 3.8 Type Aliases

> *TODO: fill in — `type Meters = float`; transparent (structural) aliasing; contrast with `@newtype`.*

---

## 4. Expressions

### 4.1 Operator Precedence

> *TODO: fill in — full precedence table (12 levels), associativity rules.*

### 4.2 Arithmetic, Comparison, and Logical Operators

> *TODO: fill in — standard operators; `÷` for integer division; `^` for exponentiation; `∧` `∨` `¬` with ASCII aliases.*

### 4.3 Bitwise Operators

> *TODO: fill in — `&` `|` `~` `⊕`/`xor` `<<` `>>`; compound assignment forms.*

### 4.4 Lambda Expressions

> *TODO: fill in — `x → expr`, `(x, y) → expr`, `() → expr`; type inference; capture semantics (by-reference RC default, `[name]` value snapshot, `[copy name]` for `go`).*

### 4.5 Pipeline Operator

> *TODO: fill in — `|>` left-to-right composition; `_` as the positional hole; desugaring.*

### 4.6 Quantifier Expressions

> *TODO: fill in — `∀ x ∈ coll: pred`, `∃`, `∑`, `∏`; inline and block forms; desugaring to for-loops.*

### 4.7 Range Expressions

> *TODO: fill in — `[a, b]` closed, `[a, b[` half-open, `]a, b[` open, `]a, b]` half-open right; valid contexts (for, match, slice); disambiguation from array literals.*

### 4.8 List Comprehensions

> *TODO: fill in — `[expr for x in coll if pred]`; multiple generators; desugaring.*

### 4.9 Match Expressions

> *TODO: fill in — `match expr` syntax; arm forms (single-line, block); exhaustiveness; guard clauses.*

### 4.10 Inline If Expressions

> *TODO: fill in — `if cond then a else b`; `then` as the disambiguator; always requires `else`; no ternary operator.*

### 4.11 Type Tests and Casts

> *TODO: fill in — `is` (boolean + smart cast), `as?` (safe → T?), `as!` (forced, panics); smart cast scope rules.*

### 4.12 String Interpolation

> *TODO: fill in — `{expr}` and `{expr:format_spec}` inside string literals; nested braces; format spec grammar.*

---

## 5. Statements

### 5.1 Variable Declarations

> *TODO: fill in — `let` (immutable), `var` (mutable); type inference; `weak` modifier.*

### 5.2 Assignment

> *TODO: fill in — simple assignment and compound assignment operators; lvalue rules.*

### 5.3 If Statement

> *TODO: fill in — multi-line block form; `if let` unwrapping; `guard let` early-exit; chained `else if`.*

### 5.4 For and While Loops

> *TODO: fill in — `for binding in expr`; tuple destructuring in for; `while expr`; labeled loops with `@label`.*

### 5.5 Break and Continue

> *TODO: fill in — unlabeled and labeled forms; `break @label`, `continue @label`.*

### 5.6 Defer Statement

> *TODO: fill in — LIFO execution order; interaction with `return`, exceptions, and panics.*

### 5.7 Pattern Matching Statements

> *TODO: fill in — `match` as a statement; arm exhaustiveness; or-patterns `A | B`; nested patterns.*

### 5.8 Try / Catch / Finally

> *TODO: fill in — syntax; interaction with `throws` and `Result<T, E>`; rethrowing.*

### 5.9 Throw Statement

> *TODO: fill in — `throw expr`; valid only inside a `throws` function.*

---

## 6. Functions

### 6.1 Function Declarations

> *TODO: fill in — `fn name(params) → ReturnType`; body block; return type inference; `→` and `return` interchangeable.*

### 6.2 Parameters

> *TODO: fill in — positional, named, default values, variadic (`T...`); ordering rules (positional before named).*

### 6.3 Computed Properties

> *TODO: fill in — `fn name → T` (no parens = property); `get`/`set` blocks for settable properties.*

### 6.4 Subscript Operators

> *TODO: fill in — `fn [](key: K) → V` (getter) and `fn []=(key: K, _ value: V)` (setter); `obj[key]` / `obj[key] = val`.*

### 6.5 Trailing Lambda Syntax

> *TODO: fill in — `call { x → expr }` and multi-line form; `{}` used exclusively for trailing lambdas.*

### 6.6 Async Functions

> *TODO: fill in — `async fn`; `await` only valid inside `async fn`; calling async from non-async requires `spawn` or `go`.*

---

## 7. Classes and Objects

### 7.1 Primary Constructors

> *TODO: fill in — fields declared on the class line; auto-generated init; `init {}` block for additional setup; `super.init(...)`.*

### 7.2 Inheritance

> *TODO: fill in — single class inheritance; `override fn`; `super.method()`.*

### 7.3 Reference Semantics

> *TODO: fill in — `class` is a reference type; RC lifecycle; `=` copies the reference, not the object.*

### 7.4 Weak and Unowned References

> *TODO: fill in — `weak var`, `unowned let`; cycle-breaking patterns; see ADR-001.*

---

## 8. Value Types: Structs and Records

### 8.1 Structs

> *TODO: fill in — `struct` keyword; stack allocation; copy-on-assignment; no inheritance; implements interfaces.*

### 8.2 Records

> *TODO: fill in — `record` keyword; immutable reference type; auto `==`, `hash()`, `str()`; `.copy(field: val)` requires `@derive(Copy)` or `@derive(All)`.*

---

## 9. Interfaces and Extensions

### 9.1 Interface Declarations

> *TODO: fill in — method signatures; default implementations; associated types; conformance via `:` on class/struct/record.*

### 9.2 Extension Functions

> *TODO: fill in — `extend TypeName`; statically dispatched; no access to private fields; retroactive conformance.*

### 9.3 Associated Types

> *TODO: fill in — `type Item` inside an interface; `where C.Item: Constraint` clauses.*

### 9.4 Interface Delegation

> *TODO: fill in — `class C : I by expr`; method delegation; individual overrides.*

---

## 10. Sealed Classes and Enums

### 10.1 Sealed Classes

> *TODO: fill in — `sealed class`; variant syntax; recursive sealed types (lists, trees); exhaustive match required.*

### 10.2 Enums

> *TODO: fill in — simple enums; raw-value enums; associated-data enums (not sealed); `match` on enum values.*

---

## 11. Null Safety

### 11.1 Nullable Types and Safe Navigation

> *TODO: fill in — `T?`; `?.` safe member access; `??` null-coalescing; `!.` force-unwrap (panic on nil).*

### 11.2 if let and guard let

> *TODO: fill in — `if let x = expr`; `guard let x = expr else`; chained `if let`.*

### 11.3 Flow Analysis and Smart Cast

> *TODO: fill in — narrowing in then-branch after `is` or `if let`; limitations (reassignment, closures).*

---

## 12. Error Handling

### 12.1 Result Types

> *TODO: fill in — `Result<T>` and `Result<T, E>`; `Ok(v)` and `Err(e)` constructors; `getOrElse`, `getOrThrow`.*

### 12.2 The ? Propagation Operator

> *TODO: fill in — `expr?` inside a function returning `Result<_, E>`; short-circuits on `Err`.*

### 12.3 throws Sugar

> *TODO: fill in — `fn f() throws E → T` desugars to `fn f() → Result<T, E>`; interoperability with `?`.*

### 12.4 Checked Exceptions vs. Result

> *TODO: fill in — when to use `throws` vs. returning `Result<T, E>` directly; practical guidelines.*

---

## 13. Generics

### 13.1 Generic Functions and Classes

> *TODO: fill in — `fn f<T>(x: T) → T`; `class Box<T>(value: T)`; type argument inference.*

### 13.2 Type Constraints

> *TODO: fill in — `<T: Comparable>` single constraint; `where T: A, T: B` multi-constraint.*

### 13.3 Associated Types

> *TODO: fill in — `type Item` in interfaces; `Sequence<T>` and `Iterator` protocols.*

---

## 14. Modules and Visibility

### 14.1 Module Declarations and Imports

> *TODO: fill in — `module name.path`; `import module.path`; `import ... as alias`; multi-file modules.*

### 14.2 Visibility Levels

> *TODO: fill in — `pub` (exported), `pub(pkg)` (package-internal), default (file-private).*

### 14.3 Sub-modules and mod.nv

> *TODO: fill in — subdirectory = sub-module; `mod.nv` controls re-exports with `pub import`.*

### 14.4 Conditional Compilation

> *TODO: fill in — `@if(platform == "linux")` / `@else` at declaration scope; predicates: `platform`, `arch`, `debug`, `release`, `feature("name")`.*

---

## 15. Concurrency

### 15.1 Goroutines (go, spawn)

> *TODO: fill in — `go callExpr` (fire-and-forget); `spawn callExpr` (returns `Future<T>`); capture lists `[copy x]`.*

### 15.2 Channels

> *TODO: fill in — `Channel<T>(capacity:)`; send/receive; `close()`; `for x in channel` iteration.*

### 15.3 Select Statement

> *TODO: fill in — `select` arms (receive, discard, after, default); non-determinism; timeout.*

### 15.4 Async/Await

> *TODO: fill in — `async fn`; `await expr`; compile-time restriction (await only inside async); Future<T>.*

### 15.5 Structured Concurrency

> *TODO: fill in — `TaskGroup`; all tasks complete or are cancelled before group exits.*

---

## 16. Memory Management

### 16.1 Reference Counting

> *TODO: fill in — RC lifecycle; atomic counts for goroutine safety; struct stack allocation. See ADR-001.*

### 16.2 Unsafe Code

> *TODO: fill in — `unsafe { }` blocks; `ptr<T>` raw pointer type; `@c` / `@cpp` inline foreign code.*

---

## 17. Annotations and Metaprogramming

### 17.1 @derive

> *TODO: fill in — `@derive(Show, Eq, Hash, Compare, Copy)` and `@derive(All)`; `.copy(field: val)` for Copy/All.*

### 17.2 @builder

> *TODO: fill in — generated Builder class; `.build { }` DSL; required-field validation.*

### 17.3 @newtype

> *TODO: fill in — `@newtype struct T(U)`; zero-cost nominal wrapper; `.value` unwrap; auto-derived impls.*

### 17.4 @lazy

> *TODO: fill in — `@lazy val field = expr` (computed once); `@lazy var field = expr` (recomputed after nil assignment).*

### 17.5 @config and @env

> *TODO: fill in — `@config("prefix")`; `@env("VAR_NAME")`; `@env(..., sensitive: true)`; `ConfigLoader.default()`.*

### 17.6 @extern, @c, @cpp, @asm, @gpu

> *TODO: fill in — C/C++ interop; inline assembly; GPU kernels; `ptr<T>` in unsafe context. See ADR-003.*

### 17.7 Conditional Compilation Annotations

> *TODO: fill in — `@if`/`@else` at declaration scope; compile-time predicate evaluation.*
