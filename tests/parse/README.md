# Parse Golden Tests

This directory will contain AST golden tests once the parser is implemented in Phase 1.2.

## Format (to be defined in Phase 1.2)

Each test case consists of a pair of files:

- `<name>.nv` — Nordvest source input
- `<name>.ast.expected` — expected serialized AST output

The AST serialization format (S-expression, JSON, or custom text format) will be decided
when the AST data structures are defined in `compiler/src/main/kotlin/nv/compiler/parser/`.

## Test Runner

A `ParseGoldenTest.kt` will be added in Phase 1.2 alongside the parser implementation.
It follows the same `@TestFactory` + `DynamicTest` pattern as `GoldenFileTest.kt`,
invoking `nv parse --dump-ast` and diffing against `.ast.expected`.

## Why Not Yet

The AST serialization format cannot be specified until the AST node types exist.
Defining it prematurely would either require breaking changes when the AST is designed
or produce a spec disconnected from the actual implementation.
