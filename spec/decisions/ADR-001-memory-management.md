# ADR-001 — Memory Management Strategy

**Status**: Accepted
**Date**: 2026-03-30
**Deciders**: Nordvest core team

## Context

Nordvest targets systems-level programming with predictable performance. Three primary memory management strategies were considered:

1. **Garbage collection (GC)** — automatic, ergonomic, but introduces stop-the-world or concurrent GC pauses incompatible with latency-sensitive code.
2. **Ownership + borrow checking (Rust model)** — zero overhead, no pauses, but imposes a steep learning curve and constrains expressive patterns (e.g., self-referential structures, shared mutable state in coroutines).
3. **Reference counting (RC)** — deterministic object lifetimes, no GC pauses, moderate ~1 ns overhead per retain/release on modern hardware; programmer-managed cycle discipline via `weak`/`unowned`.

Nordvest's design goals include:

- No GC pauses (ruled out option 1).
- A gentler learning curve than Rust (ruled out option 2 for v1).
- Support for `go` goroutines that share heap objects across threads (requires thread-safe reference counts).
- Stack-allocated value types (`struct`) for performance-critical, short-lived data.

## Decision

**Reference counting (RC) is the primary memory management strategy for Nordvest v1.**

### Allocation classes

| Kind | Allocation | RC | Mutability | Notes |
|------|-----------|-----|------------|-------|
| `class` | Heap | Yes (atomic) | Mutable | Reference type; RC ops use atomic CAS for goroutine safety |
| `struct` | Stack | No | Mutable | Value type; copied on assignment; no heap allocation |
| `record` | Heap | Yes (atomic) | Immutable | Reference type; cycles via records are structurally impossible (no `var` fields) |

### Cycle breaking

Two reference modifiers break ownership cycles without preventing deallocation:

- **`weak var x: T?`** — does not contribute to the retain count. Automatically zeroed (set to `nil`) when the referent is freed. Every access compiles to a nil check. Use for back-references in parent→child→parent graphs (e.g., a delegate or listener pattern).
- **`unowned let x: T`** — does not contribute to the retain count and is not zeroed. Accessing an unowned reference after the referent is freed crashes (undefined behaviour in the runtime). Use only when the ownership hierarchy guarantees the referent outlives the holder (e.g., a child holding a reference to its parent when the parent's lifetime is strictly larger).

### Thread safety

All `class` and `record` reference counts use atomic increment/decrement (equivalent to C++ `std::atomic<int>` with `memory_order_relaxed` for the count and `memory_order_acquire`/`memory_order_release` on the final decrement + destructor call). This is the same model used by Swift and Rust's `Arc<T>`.

### Deferred post-v1 work

- **Escape analysis**: short-lived `class` instances that do not escape their allocation scope could be promoted to the stack, eliminating the RC overhead. Deferred to Phase 3 as a compiler optimization.
- **Ownership / borrow checking**: a Rust-style ownership system would eliminate RC overhead entirely. Deferred post-v1 — it would be added as an opt-in annotation (`@owned`) rather than a language-wide requirement, to preserve the gentler learning curve.
- **Cycle detection**: no automatic cycle collector is planned for v1. Cycles not covered by `weak`/`unowned` will leak. A debug-mode leak detector (tracking all live RC objects) may be added in Phase 3.

## Consequences

### Positive

- **Deterministic destruction**: objects are freed immediately when the last strong reference drops. `defer` statements run LIFO before the final RC decrement, giving reliable cleanup semantics (file handles, locks, etc.).
- **No GC pauses**: suitable for real-time, latency-sensitive, and systems-level code.
- **Familiar model**: similar to Swift's ARC and Python's CPython RC — most programmers understand retain/release intuitively.
- **`struct` is free**: stack allocation with copy semantics has zero RC overhead; performance-critical inner loops should use `struct` values.

### Negative

- **Retain/release cost**: approximately 1 ns per atomic increment/decrement. Hot paths that create and destroy many `class` instances will see measurable overhead compared to ownership/borrow checking.
- **Cycle discipline required**: programmers must consciously choose `weak`/`unowned` for back-references. Cycles that are missed will leak silently in release builds. The compiler will emit a warning when it can detect a trivial cycle (e.g., a class that holds a strong `var` reference to itself).
- **Atomic ops on all class allocs**: even single-threaded programs pay the atomic RC cost because all `class` instances are goroutine-shareable. A non-atomic RC (`Rc<T>` vs `Arc<T>` in Rust) is not provided in v1.

### Neutral

- The RC model is well-understood and straightforward to implement in the Kotlin bootstrap compiler (Phase 1) using integer fields in the runtime heap header.
- Switching to ownership/borrow checking in a future major version would be a breaking change to the language; the RC model can coexist with an opt-in ownership system via annotations.
