package nv.compiler.lexer

enum class TokenKind {

    // ── Keywords ────────────────────────────────────────────────────────────

    MODULE, IMPORT, PUB, PKG,
    FN, LET, VAR, RETURN,
    IF, ELSE, THEN,
    FOR, WHILE, BREAK, CONTINUE,
    MATCH,
    CLASS, STRUCT, RECORD, INTERFACE, SEALED, ENUM, EXTEND,
    TYPE, INIT, SELF, SUPER,
    TRUE, FALSE, NIL,
    IN, IS, AS,
    THROWS, THROW, TRY, CATCH, FINALLY,
    DEFER,
    GO, SPAWN, SELECT, FROM, AFTER, DEFAULT,
    ASYNC, AWAIT, YIELD,
    WEAK, UNOWNED, UNSAFE,
    WHERE, GET, SET,
    XOR,    // keyword alias for ⊕
    GUARD,

    // ── Built-in constants ───────────────────────────────────────────────────

    CONST_PI,   // π  (U+03C0)
    CONST_INF,  // ∞  (U+221E)
    // Note: 'e' (Euler's number) is context-sensitive; the lexer emits IDENT("e")
    //        and the parser promotes it to CONST_E in expression position per ADR-002.

    // ── Mathematical / logical operators ────────────────────────────────────

    FORALL,     // ∀
    EXISTS,     // ∃
    SUM,        // ∑
    PRODUCT,    // ∏
    ELEM_OF,    // ∈
    AND,        // ∧  or  &&
    OR,         // ∨  or  ||
    NOT,        // ¬  or  !

    // ── Comparison operators ─────────────────────────────────────────────────

    EQ,         // ==
    NEQ,        // ≠  or  !=
    LT,         // <
    GT,         // >
    LEQ,        // ≤  or  <=
    GEQ,        // ≥  or  >=

    // ── Arithmetic operators ─────────────────────────────────────────────────

    PLUS,       // +
    MINUS,      // -
    STAR,       // *
    SLASH,      // /
    INT_DIV,    // ÷  (U+00F7) — ONLY integer-division operator; // is always a comment
    MOD,        // %
    POWER,      // ^  (exponentiation — NOT bitwise XOR)

    // ── Bitwise operators ────────────────────────────────────────────────────

    AMP,        // &
    PIPE,       // |
    TILDE,      // ~
    XOR_OP,     // ⊕  (U+2295) — bitwise XOR; also keyword 'xor'
    LSHIFT,     // <<
    RSHIFT,     // >>

    // ── Pipeline ────────────────────────────────────────────────────────────

    PIPELINE,   // |>  (maximal munch: distinct from PIPE)

    // ── Arrow ───────────────────────────────────────────────────────────────

    ARROW,      // →  (U+2192)  or  ->  — interchangeable; same token

    // ── Assignment operators ─────────────────────────────────────────────────

    ASSIGN,             // =
    PLUS_ASSIGN,        // +=
    MINUS_ASSIGN,       // -=
    STAR_ASSIGN,        // *=
    SLASH_ASSIGN,       // /=
    INT_DIV_ASSIGN,     // ÷=
    MOD_ASSIGN,         // %=
    AMP_ASSIGN,         // &=
    PIPE_ASSIGN,        // |=
    XOR_ASSIGN,         // ⊕=
    LSHIFT_ASSIGN,      // <<=
    RSHIFT_ASSIGN,      // >>=

    // ── Null / optional operators ────────────────────────────────────────────

    QUEST,          // ?   (postfix: Result propagation / nil-check in patterns)
    DOT_QUEST,      // ?.  (safe navigation)
    NULL_COALESCE,  // ??  (null-coalescing)
    BANG,           // !   (force-unwrap / postfix; same token as NOT — parser disambiguates)

    // ── Delimiters ───────────────────────────────────────────────────────────

    LPAREN,     // (
    RPAREN,     // )
    LBRACE,     // {
    RBRACE,     // }
    LBRACKET,   // [
    RBRACKET,   // ]

    // ── Punctuation ──────────────────────────────────────────────────────────

    DOT,        // .
    COMMA,      // ,
    COLON,      // :
    SEMICOLON,  // ;
    AT,         // @
    UNDERSCORE, // _  (wildcard / positional hole in pipeline)

    // ── Literals ─────────────────────────────────────────────────────────────

    INT_LIT,        // integer literal (decimal / hex / binary / octal)
    FLOAT_LIT,      // floating-point literal
    CHAR_LIT,       // character literal  'a'
    RAW_STRING_LIT, // raw string  r"..."  or  r"""..."""  (no escapes, no interpolation)

    // ── String interpolation tokens ──────────────────────────────────────────
    //
    // An interpolated string "hello {name}!" is tokenized as:
    //   STR_START  STR_TEXT("hello ")  INTERP_START  IDENT("name")  INTERP_END
    //   STR_TEXT("!")  STR_END
    //
    // A plain string with no interpolation "hi" is:
    //   STR_START  STR_TEXT("hi")  STR_END

    STR_START,   // the opening "
    STR_TEXT,    // a run of literal characters inside the string (may be empty)
    INTERP_START,// the {  that begins an interpolation region
    INTERP_END,  // the }  that closes an interpolation region
    STR_END,     // the closing "

    // ── Identifiers ──────────────────────────────────────────────────────────

    IDENT,       // user-defined or built-in name (NFC-normalized)

    // ── Comments ─────────────────────────────────────────────────────────────

    DOC_COMMENT, // /** ... */ — retained in token stream for nv doc; body is Markdown
    // Line comments (//) and block comments (/* */) are consumed silently (no token emitted).

    // ── Structure tokens (lexer-injected) ────────────────────────────────────

    INDENT,  // indentation increased
    DEDENT,  // indentation decreased
    NEWLINE, // logical line ending (suppressed inside open brackets/parens)
    EOF,     // end of file
}

/** Keyword string → TokenKind mapping (used by the lexer for identifier disambiguation). */
val KEYWORDS: Map<String, TokenKind> = mapOf(
    "module"    to TokenKind.MODULE,
    "import"    to TokenKind.IMPORT,
    "pub"       to TokenKind.PUB,
    "fn"        to TokenKind.FN,
    "let"       to TokenKind.LET,
    "var"       to TokenKind.VAR,
    "return"    to TokenKind.RETURN,
    "if"        to TokenKind.IF,
    "else"      to TokenKind.ELSE,
    "then"      to TokenKind.THEN,
    "for"       to TokenKind.FOR,
    "while"     to TokenKind.WHILE,
    "break"     to TokenKind.BREAK,
    "continue"  to TokenKind.CONTINUE,
    "match"     to TokenKind.MATCH,
    "class"     to TokenKind.CLASS,
    "struct"    to TokenKind.STRUCT,
    "record"    to TokenKind.RECORD,
    "interface" to TokenKind.INTERFACE,
    "sealed"    to TokenKind.SEALED,
    "enum"      to TokenKind.ENUM,
    "extend"    to TokenKind.EXTEND,
    "type"      to TokenKind.TYPE,
    "init"      to TokenKind.INIT,
    "self"      to TokenKind.SELF,
    "super"     to TokenKind.SUPER,
    "true"      to TokenKind.TRUE,
    "false"     to TokenKind.FALSE,
    "nil"       to TokenKind.NIL,
    "in"        to TokenKind.IN,
    "is"        to TokenKind.IS,
    "as"        to TokenKind.AS,
    "throws"    to TokenKind.THROWS,
    "throw"     to TokenKind.THROW,
    "try"       to TokenKind.TRY,
    "catch"     to TokenKind.CATCH,
    "finally"   to TokenKind.FINALLY,
    "defer"     to TokenKind.DEFER,
    "go"        to TokenKind.GO,
    "spawn"     to TokenKind.SPAWN,
    "select"    to TokenKind.SELECT,
    "from"      to TokenKind.FROM,
    "after"     to TokenKind.AFTER,
    "default"   to TokenKind.DEFAULT,
    "async"     to TokenKind.ASYNC,
    "await"     to TokenKind.AWAIT,
    "yield"     to TokenKind.YIELD,
    "weak"      to TokenKind.WEAK,
    "unowned"   to TokenKind.UNOWNED,
    "unsafe"    to TokenKind.UNSAFE,
    "where"     to TokenKind.WHERE,
    "get"       to TokenKind.GET,
    "set"       to TokenKind.SET,
    "xor"       to TokenKind.XOR,
    "guard"     to TokenKind.GUARD,
    "pkg"       to TokenKind.PKG,
)
