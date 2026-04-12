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

/* ── Phase 5.1: String operations ───────────────────────────────────────── */

/** Return a new heap-allocated substring s[from..to). Caller must free(). */
char *nv_str_slice(const char *s, int64_t from, int64_t to);

/** Return byte offset of first occurrence of needle in s, or -1. */
int64_t nv_str_index_of(const char *s, const char *needle);

/** Return 1 if s contains needle, 0 otherwise. */
int nv_str_contains(const char *s, const char *needle);

/** Return 1 if s starts with prefix. */
int nv_str_starts_with(const char *s, const char *prefix);

/** Return 1 if s ends with suffix. */
int nv_str_ends_with(const char *s, const char *suffix);

/** Return new uppercased copy of s. Caller must free(). */
char *nv_str_to_upper(const char *s);

/** Return new lowercased copy of s. Caller must free(). */
char *nv_str_to_lower(const char *s);

/** Return new copy of s with leading/trailing whitespace removed. Caller must free(). */
char *nv_str_trim(const char *s);

/** Return new copy of s with every occurrence of from replaced by to. Caller must free(). */
char *nv_str_replace(const char *s, const char *from, const char *to);

/** Return s repeated n times. Caller must free(). */
char *nv_str_repeat(const char *s, int64_t n);

/** Parse s as a decimal integer (0 on error). */
int64_t nv_str_parse_int(const char *s);

/** Parse s as a floating-point number (0.0 on error). */
double nv_str_parse_float(const char *s);

/* ── Phase 5.2: List operations ─────────────────────────────────────────── */
/* Array layout: {int64_t count, element_data...}; data starts at byte offset 8. */

/** Return new array with val appended (int elements). Caller must free(). */
char *nv_arr_push_i64(const char *arr, int64_t val);

/** Return new array with val appended (str elements). Caller must free(). */
char *nv_arr_push_str(const char *arr, const char *val);

/** Return 1 if arr contains val (int elements). */
int nv_arr_contains_i64(const char *arr, int64_t val);

/** Return 1 if arr contains val (str elements, strcmp comparison). */
int nv_arr_contains_str(const char *arr, const char *val);

/** Return index of val in arr (int elements), or -1. */
int64_t nv_arr_index_of_i64(const char *arr, int64_t val);

/** Return index of val in arr (str elements), or -1. */
int64_t nv_arr_index_of_str(const char *arr, const char *val);

/* ── Phase 5.2: Map operations (str keys, str values) ──────────────────── */
/* Map layout: {int64_t count, [key_ptr, val_ptr] pairs...} at offset 8, 16 bytes/pair. */

/** Create an empty str→str map. Caller must free(). */
char *nv_map_new(void);

/** Return number of entries in map. */
int64_t nv_map_len(const char *map);

/** Return value for key, or NULL if absent. Do not free the returned pointer. */
char *nv_map_get_str(const char *map, const char *key);

/** Return 1 if key is present in map. */
int nv_map_has_str(const char *map, const char *key);

/** Insert or update key→val. Returns new map pointer (old map may be freed). Caller owns result. */
char *nv_map_set_str(char *map, const char *key, const char *val);

/* ── Phase 5.3: File I/O ─────────────────────────────────────────────────── */

/** Open path with given mode ("r", "w", "a", etc.). Returns FILE* as char*, NULL on failure. */
char *nv_file_open(const char *path, const char *mode);

/** Open path for reading. Returns NULL on failure. */
char *nv_file_open_read(const char *path);

/** Open path for writing (create/truncate). Returns NULL on failure. */
char *nv_file_open_write(const char *path);

/** Open path for appending. Returns NULL on failure. */
char *nv_file_open_append(const char *path);

/** Close an open file handle. */
void nv_file_close(char *file);

/** Write string to file. */
void nv_file_write(char *file, const char *s);

/** Write string followed by newline to file. */
void nv_file_writeln(char *file, const char *s);

/** Read one line from file (strips trailing newline). Returns NULL on EOF. Caller must free(). */
char *nv_file_read_line(char *file);

/** Read entire file contents (up to 64 KB). Caller must free(). */
char *nv_file_read_all(char *file);

/** Return 1 if path exists and is accessible. */
int nv_file_exists(const char *path);

/** Return 1 if file handle is NULL. */
int nv_file_is_null(const char *file);

/* ── Phase 5.5: Reference counting ──────────────────────────────────────── */
/*
 * Class object layout: [i64 strong_count][i8* dtor_fn][user fields...]
 * The object pointer points to the start of this block.
 */

/** Atomically increment strong_count. No-op on NULL. */
void nv_rc_retain(void *ptr);

/** Atomically decrement strong_count. When it reaches 0, calls dtor_fn(ptr)
 *  (which must free the allocation) or calls free(ptr) if dtor_fn is NULL. */
void nv_rc_release(void *ptr);

/** Return ptr if strong_count > 0, else NULL. Safe for weak-reference loads. */
void *nv_weak_load(void *ptr);

/* ── Phase 5.6: Hash functions ───────────────────────────────────────────── */

/** FNV-1a hash of s. */
int64_t nv_hash_fnv1a(const char *s);

/** djb2 hash of s. */
int64_t nv_hash_djb2(const char *s);

/** MurmurHash3-inspired 64-bit hash of s with seed. */
int64_t nv_hash_murmur3(const char *s, int64_t seed);

/** CRC-32 of s (IEEE polynomial, bit-by-bit). */
int64_t nv_hash_crc32(const char *s);

/** Combine two hash values (boost-style). */
int64_t nv_hash_combine(int64_t h1, int64_t h2);

/** SHA-256 of s; returns 64-char hex string. Caller must free(). */
char *nv_hash_sha256(const char *s);

/** MD5 of s; returns 32-char hex string. Caller must free(). */
char *nv_hash_md5(const char *s);

/* ── Phase 5.6: Formatting functions ─────────────────────────────────────── */

/** Format integer n in radix (2/8/10/16), left-padded to width with padChar[0]. Caller must free(). */
char *nv_fmt_int(int64_t n, int64_t width, const char *padChar, int64_t radix);

/** Format float x with given decimal precision; notation "e"→scientific, "f"→fixed, else %g. Caller must free(). */
char *nv_fmt_float(double x, int64_t precision, const char *notation);

/** Truncate s to width chars; if longer prepend suffix at end. Returns new string. Caller must free(). */
char *nv_fmt_truncate(const char *s, int64_t width, const char *suffix);

/** Format byte count as human-readable size ("42 B", "1 KB", "3.2 MB" …). Caller must free(). */
char *nv_fmt_file_size(int64_t bytes);

/** Format millisecond count as human-readable duration ("500ms", "2s", "1m 5s" …). Caller must free(). */
char *nv_fmt_duration(int64_t ms);

/** Format integer with thousands separators (sep is typically ","). Caller must free(). */
char *nv_fmt_thousands(int64_t n, const char *sep);

/* ── Phase 5.6: Iterator / range functions ───────────────────────────────── */
/* Array layout: {int64_t count, int64_t elements...} */

/** Return int array [start, start+1, …, end-1]. Caller must free(). */
char *nv_iter_range(int64_t start, int64_t end);

/** Return int array [start, start+step, …] up to (exclusive) end. Caller must free(). */
char *nv_iter_range_step(int64_t start, int64_t end, int64_t step);

/* ── Concurrency primitives (Phase 2.1) ─────────────────────────────────── */

/**
 * Opaque channel type backed by a mutex-protected ring buffer.
 * capacity == 0 means unbuffered (synchronous rendezvous).
 */
typedef struct nv_channel nv_channel_t;

/** Opaque future type representing a running pthread and its result. */
typedef struct nv_future nv_future_t;

/** Create a channel with the given buffer capacity (0 = unbuffered). */
nv_channel_t *nv_channel_create(int64_t capacity);

/** Send a value pointer into the channel. Blocks if the buffer is full. */
void nv_channel_send(nv_channel_t *ch, void *value);

/** Receive a value pointer from the channel. Blocks until a value is available. */
void *nv_channel_receive(nv_channel_t *ch);

/**
 * Non-blocking receive attempt.
 * Returns 1 and writes the value into *out if a value was available.
 * Returns 0 and leaves *out unchanged if the channel is empty.
 */
int nv_channel_try_receive(nv_channel_t *ch, void **out);

/** Close the channel. Any subsequent sends panic; receives drain the buffer. */
void nv_channel_close(nv_channel_t *ch);

/**
 * Spawn a detached ("fire and forget") thread that calls fn(arg).
 * Returns NULL; the result is discarded.
 */
void *nv_go_spawn(void *(*fn)(void *), void *arg);

/**
 * Spawn a thread and return a future that can be awaited.
 * The future holds the thread id and will store fn's return value.
 */
nv_future_t *nv_future_spawn(void *(*fn)(void *), void *arg);

/**
 * Await a future: join the thread and return its return value.
 * The future is freed after this call; do not use it again.
 */
void *nv_future_await(nv_future_t *f);

#ifdef __cplusplus
}
#endif
