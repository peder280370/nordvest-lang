# ADR-002 — Unicode and Identifier Policy

**Status**: Accepted
**Date**: 2026-03-30
**Deciders**: Nordvest core team

## Context

Nordvest uses Unicode mathematical symbols as first-class syntax elements — `∀ ∃ ∑ ∏ ∈ ∧ ∨ ¬ → ≤ ≥ ≠ ⊕ π e ∞` — and targets scientific and mathematical code where Greek letters (`μ σ λ α β`) are natural variable names. This requires clear policies for:

1. **Source file encoding**: UTF-8, UTF-16, or platform-dependent?
2. **Identifier normalization**: Unicode allows multiple byte sequences for the same visible character (e.g., `é` as precomposed U+00E9 or decomposed U+0065 + U+0301). Should these be treated as the same identifier?
3. **Mathematical constants**: `π` and `e` are common variable names in non-Nordvest code but are built-in constants in Nordvest. How are they lexed?
4. **Operator vs. identifier boundary**: Which Unicode code points are operators and which are identifiers?
5. **ASCII aliases**: Are ASCII spellings (`->`, `&&`, `||`, `!=`, etc.) accepted alongside Unicode forms?

## Decision

### 1. Source file encoding: UTF-8 only

All Nordvest source files (`.nv`) must be encoded in UTF-8. UTF-16 and UTF-32 are not accepted. A UTF-8 BOM (U+FEFF at position 0) is silently stripped and otherwise ignored; it does not affect parsing.

Rationale: UTF-8 is the de facto standard for source code on all major platforms. UTF-16 adds complexity (byte-order marks, surrogate pairs) with no benefit for source files.

### 2. Identifier normalization: NFC at lex time

The lexer applies **Unicode NFC normalization** (Canonical Decomposition followed by Canonical Composition) to every identifier token before interning it in the symbol table. Two identifiers that are NFC-equivalent resolve to the same symbol.

Example: the identifier `café` written as `cafe\u0301` (decomposed) and `caf\u00E9` (precomposed) are the same symbol after NFC normalization.

**Scope**: NFC normalization applies only to identifier tokens. String literal contents are NOT normalized; they retain their exact byte sequence as written in the source file.

### 3. Identifier character categories

- **Start character**: must satisfy the Unicode property `XID_Start` (defined in Unicode Standard Annex #31). This includes letters from all scripts, the underscore `_`, and many other characters. Greek letters (α β γ δ ε ζ η θ μ σ τ φ ψ ω etc.) are in `XID_Start`.
- **Continuation characters**: must satisfy `XID_Continue` (superset of `XID_Start`, adds digits and certain combining marks).
- The lexer applies NFC normalization before the `XID_Start`/`XID_Continue` check.

### 4. Built-in mathematical constants: π, e, ∞

Three tokens are reserved as built-in constants and must NOT be used as user-defined identifiers:

| Symbol | Code point | Lexer token | Value |
|--------|-----------|-------------|-------|
| `π` | U+03C0 (GREEK SMALL LETTER PI) | `CONST_PI` | 3.14159265358979… |
| `∞` | U+221E (INFINITY) | `CONST_INF` | positive infinity (float) |
| `e` | U+0065 (LATIN SMALL LETTER E) | `CONST_E` | 2.71828182845904… |

**Special case for `e`**: The letter `e` satisfies `XID_Start` and is commonly used as an identifier. The lexer resolves this as follows: after tokenizing an identifier, if the token text equals exactly the string `"e"` (a single code point U+0065) and the token does NOT appear in the context `<digit>e<digit>` (which is a float exponent, e.g., `1.5e-3`), the lexer emits `CONST_E` rather than `IDENT`. In practice: `e` as a standalone expression is Euler's number; `ep` or `epsilon` are valid user identifiers; `1.5e-3` is a float literal.

`π` (U+03C0) does not satisfy ASCII `XID_Start` rules but does satisfy Unicode `XID_Start`, so it is naturally identified as an identifier candidate before the reserved-word check promotes it to `CONST_PI`. `∞` (U+221E) does not satisfy `XID_Start` and is therefore lexed as an operator/constant token directly.

### 5. Mathematical operator tokens

The following Unicode symbols are operator tokens, not identifiers. They do not satisfy `XID_Start` and are lexed before the identifier rule fires:

| Symbol | Code point | Token | Meaning |
|--------|-----------|-------|---------|
| `→` | U+2192 | `ARROW` | return type / return statement |
| `∀` | U+2200 | `FORALL` | universal quantifier |
| `∃` | U+2203 | `EXISTS` | existential quantifier |
| `∑` | U+2211 | `SUM` | summation quantifier |
| `∏` | U+220F | `PRODUCT` | product quantifier |
| `∈` | U+2208 | `IN` | set membership (also used in `for`) |
| `∧` | U+2227 | `AND` | logical and |
| `∨` | U+2228 | `OR` | logical or |
| `¬` | U+00AC | `NOT` | logical not |
| `≤` | U+2264 | `LE` | less-than-or-equal |
| `≥` | U+2265 | `GE` | greater-than-or-equal |
| `≠` | U+2260 | `NE` | not-equal |
| `⊕` | U+2295 | `XOR` | bitwise exclusive-or |
| `÷` | U+00F7 | `IDIV` | integer division (the ONLY integer division operator) |

### 6. ASCII aliases

Every Unicode operator has a fully accepted ASCII alias. The compiler treats them as identical tokens:

| Unicode | ASCII alias |
|---------|------------|
| `→` | `->` |
| `∧` | `&&` |
| `∨` | `\|\|` |
| `¬` | `!` |
| `≤` | `<=` |
| `≥` | `>=` |
| `≠` | `!=` |
| `⊕` | `xor` (keyword) |

**`nv fmt` normalization**: by default, `nv fmt` converts ASCII aliases to their Unicode forms (e.g., `->` → `→`, `&&` → `∧`). The `--ascii` flag suppresses this normalization, emitting all operators in ASCII form for terminals or environments that cannot render Unicode.

### 7. Critical lexer disambiguation rules

- **`//` is ALWAYS a line comment**. There is no integer-division `//` operator. Integer division uses `÷` exclusively. The sequence `//` anywhere in the source (even inside an expression) begins a comment that runs to end of line.
- **`^` is exponentiation**, not bitwise XOR. Bitwise XOR is `⊕` or the keyword `xor`.
- **`|>` vs `|`**: the pipeline operator `|>` is lexed as a single `PIPE_ARROW` token via maximal munch — when the character `|` is immediately followed by `>`, the lexer emits `PIPE_ARROW` rather than `BITOR` + `GT`.
- **`{ }` is only for trailing lambdas**. Curly braces do not delimit blocks; indentation does. Curly braces appear only as delimiters for trailing lambda expressions.

## Consequences

### Positive

- Source files are portable across all modern editors, terminals, and version control systems (UTF-8 is universal).
- Identifiers compare correctly regardless of keyboard/IME decomposition differences (NFC normalization).
- Scientific code can use natural variable names: `μ`, `σ`, `λ`, `Δt`, `α`, `β` are valid identifiers.
- Mathematical notation (`∀`, `∑`, `→`) is idiomatic first-class syntax, not library-level ASCII workarounds.

### Negative

- The `e` constant special case is a lexer irregularity. It must be clearly documented and tested. Programmers migrating from other languages may be surprised that `e` is not a valid identifier.
- NFC normalization means a programmer who deliberately uses different Unicode representations for identifiers (rare but possible) will find them collapsed to the same symbol.
- Requiring `÷` for integer division (instead of `//`) is a departure from Python/Go conventions and requires Unicode input support.

### Neutral

- The `--ascii` flag in `nv fmt` ensures Nordvest code is writable and readable on ASCII-only environments; Unicode forms are preferred but never mandatory in source code.
- The Unicode property tables (`XID_Start`, `XID_Continue`, NFC normalization) are available in standard Java/Kotlin libraries (`java.lang.Character`, `java.text.Normalizer`), so no external Unicode library is needed in the bootstrap compiler.
