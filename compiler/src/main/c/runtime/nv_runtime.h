/*
 * nv_runtime.h — Nordvest Phase 1 runtime support library
 *
 * This header declares the minimal C runtime functions that every Nordvest
 * binary links against. In Phase 1 the compiler emits inline LLVM IR
 * equivalents of these functions; this file exists as a reference and for
 * future use when the runtime grows beyond what is convenient to inline.
 *
 * String representation (Phase 1): null-terminated UTF-8 C strings.
 * Future: length-prefixed UTF-8 with small-string optimisation (SSO).
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ── I/O ─────────────────────────────────────────────────────────────────── */

/** Print string to stdout (no newline). */
void nv_print(const char *s);

/** Print string to stdout followed by newline. */
void nv_println(const char *s);

/** Print integer to stdout (no newline). */
void nv_print_int(int64_t n);

/** Print integer to stdout followed by newline. */
void nv_println_int(int64_t n);

/** Print double to stdout (no newline). */
void nv_print_float(double f);

/** Print double to stdout followed by newline. */
void nv_println_float(double f);

/** Print bool ("true"/"false") to stdout (no newline). */
void nv_print_bool(int b);

/** Print bool ("true"/"false") to stdout followed by newline. */
void nv_println_bool(int b);

/* ── Conversions ─────────────────────────────────────────────────────────── */

/** Convert int to heap-allocated C string. Caller is responsible for free(). */
char *nv_int_to_str(int64_t n);

/** Convert double to heap-allocated C string. Caller is responsible for free(). */
char *nv_float_to_str(double f);

/** Return a pointer to the static string "true" or "false". Do not free. */
const char *nv_bool_to_str(int b);

/* ── String operations ───────────────────────────────────────────────────── */

/**
 * Concatenate two strings.
 * Returns a new heap-allocated null-terminated string. Caller must free().
 */
char *nv_str_concat(const char *a, const char *b);

/** Return 1 if a == b (by content), 0 otherwise. */
int nv_str_eq(const char *a, const char *b);

/** Return the length of s in bytes (excluding null terminator). */
int64_t nv_str_len(const char *s);

/* ── Error handling ──────────────────────────────────────────────────────── */

/**
 * Panic: print "panic: <msg>" to stderr and exit with code 1.
 * This function never returns.
 */
__attribute__((noreturn)) void nv_panic(const char *msg);

#ifdef __cplusplus
}
#endif
