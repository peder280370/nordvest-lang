# Nordvest IntelliJ Plugin

IntelliJ IDEA support for the [Nordvest](../../README.md) programming language — syntax highlighting, LSP-backed diagnostics and completion, run configurations, and first-class mathematical symbol input.

> **Status:** Phase 6.2 — Tier 1 (LSP4IJ).  Local development target; marketplace distribution planned after stabilisation.

---

## Features

### Syntax highlighting
Every Nordvest token category is coloured independently:

| Category | Examples |
|---|---|
| **Keywords** | `fn` `let` `var` `match` `sealed` `async` `await` |
| **Annotations** | `@derive` `@builder` `@config` `@test` |
| **Built-in types** | `int` `float` `str` `bool` `Result` `Sequence` |
| **Math operators** | `∀ ∃ ∑ ∏ ∈ ∧ ∨ ¬ → ≤ ≥ ≠ ÷ ⊕ π ∞` |
| **Strings** | `"hello {name}!"` `r"raw\nstring"` |
| **Numbers** | `42` `3.14` `0xFF` `0b1010` |
| **Comments** | `// line comment` |

Colours are fully configurable under **Preferences › Editor › Color Scheme › Nordvest**.

---

### Mathematical symbol input

Nordvest uses Unicode math operators as first-class syntax.  The plugin provides three complementary input mechanisms so you never have to copy-paste from a character map.

#### Mechanism 1 — Live backslash substitution *(primary)*

Type a LaTeX-style escape name followed by **Space** or **Tab**:

```
\forall<Space>   →   ∀ 
\->    <Space>   →   → 
\leq   <Space>   →   ≤ 
\sigma <Space>   →   σ 
\sum   <Tab>     →   ∑⇥
```

The `\name` text is replaced in-place.  If the name is not recognised, the text is left unchanged.

#### Mechanism 2 — Completion popup

Press **Ctrl+Space** (macOS: **Ctrl+Space**) while typing a `\`-prefixed name to see matching suggestions:

```
\fo<Ctrl+Space>  →  popup: ∀ \forall, …
```

Each suggestion shows the glyph, escape name, description, and Unicode code point.  Selecting an entry replaces `\<prefix>` with the symbol.

#### Mechanism 3 — Symbol picker *(Alt+\)*

Press **Alt+Backslash** (or search "Insert Math Symbol" in Find Action) to open a searchable symbol palette:

- Type in the search box to filter by name or description
- Click any symbol to insert it at the caret
- The dialog is modeless — you can keep it open while editing

#### Full escape table

| Escape | Symbol | Description |
|---|---|---|
| `\forall` | ∀ | For all (universal quantifier) |
| `\exists` | ∃ | There exists |
| `\sum` | ∑ | Summation |
| `\prod` | ∏ | Product |
| `\in` | ∈ | Element of |
| `\and` | ∧ | Logical and |
| `\or` | ∨ | Logical or |
| `\not` | ¬ | Logical not |
| `\->` | → | Return / lambda arrow |
| `\leq` | ≤ | Less than or equal |
| `\geq` | ≥ | Greater than or equal |
| `\neq` | ≠ | Not equal |
| `\div` | ÷ | Integer division |
| `\xor` | ⊕ | Bitwise XOR |
| `\pi` | π | Pi constant |
| `\inf` | ∞ | Infinity |
| `\lambda` | λ | Lambda |
| `\alpha … \omega` | α … ω | Greek lowercase letters |
| `\Gamma … \Omega` | Γ … Ω | Greek uppercase letters |
| `\approx` | ≈ | Approximately equal |
| `\equiv` | ≡ | Identical / equivalent |
| `\sqrt` | √ | Square root |
| `\empty` | ∅ | Empty set |
| `\subset` | ⊂ | Subset |
| … | … | *60+ symbols total; browse with Alt+\* |

---

### LSP integration

The plugin launches the `nv lsp` language server as a child process and connects via LSP4IJ.  Once connected, the following features are available without any extra configuration:

| Feature | How to trigger |
|---|---|
| **Diagnostics** | Errors shown inline and in the Problems panel |
| **Completion** | Ctrl+Space — keywords, members, module symbols |
| **Hover** | Mouse-over any identifier — shows inferred type |
| **Go to definition** | Ctrl+B / Cmd+B |
| **Find usages** | Alt+F7 |
| **Rename** | Shift+F6 |
| **Format file** | Ctrl+Alt+L / Cmd+Alt+L (delegates to `nv fmt`) |

The LSP server path and arguments are configured under **Preferences › Tools › Nordvest**.

---

### Run configurations

Two configuration types are available under **Run › Edit Configurations › Nordvest**:

- **nv run** — compile and run a `.nv` file; output in the Run tool window
- **nv test** — run `nv test [filter]` on a file or directory

A ▶ gutter icon appears next to `fn main(` and `@test fn` declarations.  Clicking it creates and runs a configuration for that function.

---

### Bracket matching and commenting

- `()`, `[]`, `{}`, and `[[]]` pairs are matched and highlighted
- **Ctrl+/** (macOS: **Cmd+/**) toggles `//` line comments on the selected lines

---

## Getting started (local development)

### Prerequisites

| Tool | Version |
|---|---|
| IntelliJ IDEA | 2025.2 or later (Community or Ultimate) |
| JDK | 21 |
| Gradle | 8.12 (via wrapper) |
| `nv` toolchain | On PATH (build from project root: `./gradlew :tools:fatJar`) |

### Build and run

```bash
# Launch a sandboxed IntelliJ with the plugin loaded:
cd ide/nv-intellij
./gradlew runIde
```

> `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are symlinks
> to the project-root Gradle wrapper — no extra setup needed.

A new IntelliJ window opens with the plugin active.  Open any `.nv` file to see highlighting.  Open a project that has `nv` on PATH to see LSP diagnostics.

### Build a distributable ZIP

```bash
./gradlew buildPlugin
# Output: build/distributions/nv-intellij-0.1.0-SNAPSHOT.zip
```

Install manually: **Preferences › Plugins › ⚙ › Install Plugin from Disk…**

### Run tests

```bash
./gradlew test
```

---

## Configuration

**Preferences › Tools › Nordvest**

| Setting | Default | Description |
|---|---|---|
| nv binary path | *(auto from PATH)* | Full path to the `nv` binary |
| LSP arguments | `lsp` | Arguments passed after the binary |
| Format on save | Off | Invoke `nv fmt` automatically on save |

---

## Project layout

```
ide/nv-intellij/
├── build.gradle.kts            Gradle build (IntelliJ Platform Plugin v2)
├── settings.gradle.kts
├── src/main/
│   ├── kotlin/nv/intellij/
│   │   ├── lang/               Language, FileType, PSI file, parser definition
│   │   ├── lexer/              Token types, lexer, highlighter, colour settings
│   │   ├── editor/             Math symbol input, bracket matcher, commenter
│   │   ├── lsp/                LSP4IJ server definition
│   │   ├── run/                Run configurations, gutter icons
│   │   └── settings/           Persistent settings
│   └── resources/
│       ├── META-INF/plugin.xml
│       └── icons/nordvest.svg
```

---

## Architecture notes

### Tier 1 vs Tier 2

This is a **Tier 1** plugin — semantic intelligence is entirely delegated to `nv lsp` via the LSP4IJ bridge.  The plugin itself provides:

- A hand-written `NordvestLexer` (extends `LexerBase`) for keystroke-level syntax highlighting
- A stub `ParserDefinition` that produces a single root PSI node (no semantic structure)
- Three complementary math symbol input mechanisms
- Run configurations that invoke the `nv` CLI

A future **Tier 2** plugin will replace the stub parser with a full recursive-descent PSI parser, enabling richer inspections, quick-fixes, and a native debugger integration — without depending on the LSP server for core features.

### Why the lexer is standalone

`NordvestLexer` does not depend on the compiler's `Lexer.kt`.  The compiler's lexer emits `INDENT`/`DEDENT` tokens that IntelliJ does not understand, and it is designed for full compilation rather than incremental keypress-level scanning.  The plugin's lexer is intentionally simpler: it skips `INDENT`/`DEDENT`, treats string interpolation as one token, and is tuned for the latency requirements of syntax highlighting.

### Math symbol substitution

`MathSymbolTypedHandler` implements `TypedHandlerDelegate` and is registered with `order="first"` to fire before IntelliJ's built-in handlers.  It is gated on `file.fileType == NordvestFileType`, so it has no effect outside `.nv` files.

---

## Roadmap

| Item | Status |
|---|---|
| Syntax highlighting | Done |
| Math symbol input (3 mechanisms) | Done |
| LSP4IJ wiring | Done |
| Run / test configurations | Done |
| Bracket matching, commenter | Done |
| Settings page | Done |
| `./gradlew runIde` workflow | Done |
| Inlay hints (type annotations) | Pending nv-lsp work |
| Semantic token highlighting | Pending nv-lsp work |
| Tier 2 PSI parser | Future |
| Debugger (DWARF + XDebugger) | Future |
| Marketplace publication | Future |
