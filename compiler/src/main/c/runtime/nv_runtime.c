/*
 * nv_runtime.c — Nordvest Phase 1 runtime support library
 *
 * This C implementation mirrors the inline LLVM IR functions emitted by
 * LlvmIrEmitter.kt. It exists as a reference and as a drop-in alternative
 * that can be compiled with `clang -c nv_runtime.c -o nv_runtime.o` and
 * linked separately.
 *
 * Phase 1 strings are null-terminated UTF-8 C strings. The layout will
 * evolve to length-prefixed + SSO in Phase 2.
 */

#include "nv_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>

/* ── I/O ─────────────────────────────────────────────────────────────────── */

void nv_print(const char *s) {
    fputs(s, stdout);
    fflush(stdout);
}

void nv_println(const char *s) {
    puts(s);
}

void nv_print_int(int64_t n) {
    printf("%" PRId64, n);
    fflush(stdout);
}

void nv_println_int(int64_t n) {
    printf("%" PRId64 "\n", n);
}

void nv_print_float(double f) {
    printf("%g", f);
    fflush(stdout);
}

void nv_println_float(double f) {
    printf("%g\n", f);
}

void nv_print_bool(int b) {
    fputs(b ? "true" : "false", stdout);
    fflush(stdout);
}

void nv_println_bool(int b) {
    puts(b ? "true" : "false");
}

/* ── Conversions ─────────────────────────────────────────────────────────── */

char *nv_int_to_str(int64_t n) {
    /* Maximum length of a 64-bit decimal integer: 20 digits + sign + NUL */
    char *buf = (char *)malloc(32);
    if (!buf) nv_panic("nv_int_to_str: out of memory");
    snprintf(buf, 32, "%" PRId64, n);
    return buf;
}

char *nv_float_to_str(double f) {
    char *buf = (char *)malloc(64);
    if (!buf) nv_panic("nv_float_to_str: out of memory");
    snprintf(buf, 64, "%g", f);
    return buf;
}

const char *nv_bool_to_str(int b) {
    return b ? "true" : "false";
}

/* ── String operations ───────────────────────────────────────────────────── */

char *nv_str_concat(const char *a, const char *b) {
    size_t la = strlen(a);
    size_t lb = strlen(b);
    char *result = (char *)malloc(la + lb + 1);
    if (!result) nv_panic("nv_str_concat: out of memory");
    memcpy(result, a, la);
    memcpy(result + la, b, lb + 1);  /* copies b including its NUL */
    return result;
}

int nv_str_eq(const char *a, const char *b) {
    return strcmp(a, b) == 0;
}

int64_t nv_str_len(const char *s) {
    return (int64_t)strlen(s);
}

/* ── Error handling ──────────────────────────────────────────────────────── */

__attribute__((noreturn)) void nv_panic(const char *msg) {
    fprintf(stderr, "panic: %s\n", msg);
    fflush(stderr);
    exit(1);
}
