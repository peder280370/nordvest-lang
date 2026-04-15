# Operator Overloading — Implementation Plan

Status: Phase 2.5 partially done. Parser + type checker work. Codegen is wired up but untested (stub). Three bugs found, no integration tests.

---

## What's Already Done

- **Parser** (`Parser.kt:tryParseOperatorName`): recognises `+`, `-`, `*`, `/`, `%`, `^`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `&`, `|` as method names inside class/struct bodies.
- **TypeChecker** (`TypeChecker.kt:synthesizeBinary`): looks up `memberTypeMap["TypeName.op"]` before falling through to built-in rules. Returns the operator method's return type on a hit.
- **Codegen** (`LlvmIrEmitter.kt:emitBinaryExpr` lines 2329–2348): has the overload dispatch path — builds mangled name `@nv_<TypeName>_op_<suffix>`, emits a `call` instruction.
- **Method body emit** (`emitMethodDecl` line 1604): already uses `operatorToSuffix(fn.name)` to mangle operator methods identically to how call sites mangle them.
- **9 tests** in `OperatorOverloadTest.kt` (parser + type checker only, no codegen).

---

## Bugs Found

### Bug 1 — TypeChecker lookup mismatch for NEQ / LEQ / GEQ

`synthesizeBinary` line 882 uses `expr.op.symbol` to form the lookup key:

```kotlin
val opName = expr.op.symbol          // BinaryOp.NEQ → "≠", LEQ → "≤", GEQ → "≥"
val overloadKey = "${lType.qualifiedName}.$opName"
```

But `tryParseOperatorName` stores `"!="`, `"<="`, `">="` (ASCII) in `memberTypeMap`. The keys never match for those three operators — type checker overload lookup silently fails for `!=`, `<=`, `>=`.

**Fix:** Add a `BinaryOp.overloadName` extension (bottom of TypeChecker.kt) returning parser-compatible ASCII names, and use it in `synthesizeBinary`.

```kotlin
// Bottom of TypeChecker.kt, alongside BinaryOp.symbol
private val BinaryOp.overloadName: String get() = when (this) {
    BinaryOp.NEQ -> "!="
    BinaryOp.LEQ -> "<="
    BinaryOp.GEQ -> ">="
    else -> this.symbol   // all others already use ASCII or Unicode that the parser also produces
}
```

Change line 882:
```kotlin
val opName = expr.op.overloadName   // was: expr.op.symbol
```

### Bug 2 — Codegen `binaryOpSymbol` missing `BIT_AND` and `BIT_OR`

`binaryOpSymbol` at line 2305 has no explicit case for `BIT_AND` or `BIT_OR`, so they fall through to `op.name` (e.g. `"BIT_AND"`). This produces a lookup key like `"Vec2.BIT_AND"` which never matches the `"Vec2.&"` key set by the type checker.

**Fix:** Add explicit cases in `binaryOpSymbol`:
```kotlin
BinaryOp.BIT_AND -> "&"
BinaryOp.BIT_OR  -> "|"
BinaryOp.BIT_XOR -> "⊕"
```

### Bug 3 — `operatorToSuffix` missing bitwise operators

`operatorToSuffix` at line 2313 has no entries for `&`, `|`, `⊕`. Calls to those overloads would mangle to `@nv_Vec2_op_custom` (the fallback) rather than a unique per-operator name, meaning two different operators would collide.

**Fix:** Add to `operatorToSuffix`:
```kotlin
"&"  -> "bitand"
"|"  -> "bitor"
"⊕"  -> "xor"
```

---

## New Feature — Add `⊕` as a Parseable Operator Name

PLAN.txt 2.5 lists `⊕` as overloadable. The token kind is `XOR_OP`.

**Fix:** In `tryParseOperatorName` (Parser.kt ~line 360), add:
```kotlin
at(XOR_OP) -> "⊕"
```

---

## Step-by-Step Implementation

### Step 1 — Fix TypeChecker (Bug 1)
**File:** `compiler/src/main/kotlin/nv/compiler/typecheck/TypeChecker.kt`

1. At the bottom of the file, add `BinaryOp.overloadName` alongside `BinaryOp.symbol`.
2. In `synthesizeBinary` replace `expr.op.symbol` with `expr.op.overloadName` on the two lines that build `overloadKey` (line ~882).

### Step 2 — Extend parser to accept `⊕`
**File:** `compiler/src/main/kotlin/nv/compiler/parser/Parser.kt`

In `tryParseOperatorName`, add a case for `XOR_OP` (the `⊕` token) → `"⊕"`.

### Step 3 — Fix codegen symbol mapping (Bugs 2 & 3)
**File:** `compiler/src/main/kotlin/nv/compiler/codegen/LlvmIrEmitter.kt`

1. `binaryOpSymbol`: add `BIT_AND → "&"`, `BIT_OR → "|"`, `BIT_XOR → "⊕"`.
2. `operatorToSuffix`: add `"&" → "bitand"`, `"|" → "bitor"`, `"⊕" → "xor"`.

### Step 4 — IR-structure tests
**File:** `tests/src/test/kotlin/nv/tests/OperatorOverloadTest.kt`

Convert the class to extend `NvCompilerTestBase`. Add a nested `CodegenTests` block using `compileOk(src)` to check LLVM IR:

- `@nv_Vec2_op_plus` is defined in the IR (definition)
- `call.*@nv_Vec2_op_plus` appears in the IR (call site)
- `@nv_Vec2_op_eq` defined and called for `==` overload
- `@nv_Vec2_op_bitand` defined and called for `&` overload

Example pattern:
```kotlin
@Test fun `op_plus is defined and called in LLVM IR`() {
    val ir = compileOk("""
        module test
        struct Vec2(pub x: float, pub y: float)
            fn +(other: Vec2) → Vec2
                → Vec2(x + other.x, y + other.y)
        fn main()
            let a = Vec2(1.0, 2.0)
            let b = Vec2(3.0, 4.0)
            let c = a + b
            println(c.x)
    """)
    assertTrue(ir.contains("define i8* @nv_Vec2_op_plus"), "op_plus definition missing")
    assertTrue(ir.contains("@nv_Vec2_op_plus("), "op_plus call site missing")
}
```

### Step 5 — End-to-end integration tests (clang)
**File:** `tests/src/test/kotlin/nv/tests/OperatorOverloadTest.kt`

Add a `IntegrationTests` block. Use `runProgramOrSkip(ir)` to run compiled binaries and assert output.

Minimum coverage:
- Struct with `fn +` — `a + b` prints correct field value
- Struct with `fn ==` returning bool — conditional prints "equal"/"not equal"
- Struct with `fn <` — comparison in an if-expression

### Step 6 — Update IMPL.txt
**File:** `IMPL.txt` line 306

Change:
```
- LlvmIrEmitter.kt: operator overloaded types use normal emit path (Phase 2 stub)
- OperatorOverloadTest.kt: 9 tests covering parser and type checker
```
To:
```
- LlvmIrEmitter.kt: operator dispatch complete; mangled @nv_T_op_<suffix> calls emitted;
    binaryOpSymbol and operatorToSuffix cover +,-,*,/,%,^,==,!=,<,>,<=,>=,&,|,⊕
- OperatorOverloadTest.kt: <N> tests — parser, type checker, IR structure, end-to-end
```

---

## Non-Goals (out of scope for this pass)

- `÷` (INT_DIV) as an overloadable operator — not in PLAN.txt 2.5, would need separate discussion
- `<<` / `>>` as overloadable — not listed in PLAN.txt 2.5
- Compound assignment operators (`+=`, `-=`, `&=`, etc.) — auto-derive from base form; defer to a follow-up pass
- Custom infix operators (Haskell-style) — explicitly deferred post-v1 in PLAN.txt appendix D

---

## File Summary

| File | Change |
|------|--------|
| `compiler/.../typecheck/TypeChecker.kt` | Add `BinaryOp.overloadName`, use it in `synthesizeBinary` |
| `compiler/.../parser/Parser.kt` | Add `XOR_OP → "⊕"` in `tryParseOperatorName` |
| `compiler/.../codegen/LlvmIrEmitter.kt` | Fix `binaryOpSymbol` + `operatorToSuffix` |
| `tests/.../OperatorOverloadTest.kt` | Extend `NvCompilerTestBase`; add IR + integration tests |
| `IMPL.txt` | Update 2.5 status and test count |
