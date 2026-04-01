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
#include <pthread.h>

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

/* ── Concurrency primitives (Phase 2.1) ─────────────────────────────────── */

/*
 * nv_channel_t — mutex + condvar ring buffer.
 *
 * capacity == 0 is treated as capacity 1 (synchronous send blocks until
 * the receiver has consumed the value; a production implementation would
 * use a separate rendezvous mechanism, but this is sufficient for the
 * bootstrap).
 */

#define NV_CHAN_MAX_CAP 1024

struct nv_channel {
    pthread_mutex_t  lock;
    pthread_cond_t   not_full;
    pthread_cond_t   not_empty;
    int64_t          capacity;   /* effective buffer size (>= 1) */
    int64_t          head;       /* index of next slot to read   */
    int64_t          tail;       /* index of next slot to write  */
    int64_t          count;      /* number of items currently in buffer */
    int              closed;
    void           **buf;        /* ring buffer of void* */
};

nv_channel_t *nv_channel_create(int64_t capacity) {
    nv_channel_t *ch = (nv_channel_t *)malloc(sizeof(nv_channel_t));
    if (!ch) nv_panic("nv_channel_create: out of memory");
    int64_t cap = (capacity <= 0) ? 1 : (capacity > NV_CHAN_MAX_CAP ? NV_CHAN_MAX_CAP : capacity);
    ch->buf = (void **)malloc((size_t)cap * sizeof(void *));
    if (!ch->buf) nv_panic("nv_channel_create: out of memory (buf)");
    ch->capacity = cap;
    ch->head = 0;
    ch->tail = 0;
    ch->count = 0;
    ch->closed = 0;
    pthread_mutex_init(&ch->lock, NULL);
    pthread_cond_init(&ch->not_full,  NULL);
    pthread_cond_init(&ch->not_empty, NULL);
    return ch;
}

void nv_channel_send(nv_channel_t *ch, void *value) {
    pthread_mutex_lock(&ch->lock);
    if (ch->closed) {
        pthread_mutex_unlock(&ch->lock);
        nv_panic("nv_channel_send: send on closed channel");
    }
    while (ch->count == ch->capacity) {
        pthread_cond_wait(&ch->not_full, &ch->lock);
    }
    ch->buf[ch->tail] = value;
    ch->tail = (ch->tail + 1) % ch->capacity;
    ch->count++;
    pthread_cond_signal(&ch->not_empty);
    pthread_mutex_unlock(&ch->lock);
}

void *nv_channel_receive(nv_channel_t *ch) {
    pthread_mutex_lock(&ch->lock);
    while (ch->count == 0 && !ch->closed) {
        pthread_cond_wait(&ch->not_empty, &ch->lock);
    }
    if (ch->count == 0) {
        /* channel closed and empty */
        pthread_mutex_unlock(&ch->lock);
        return NULL;
    }
    void *value = ch->buf[ch->head];
    ch->head = (ch->head + 1) % ch->capacity;
    ch->count--;
    pthread_cond_signal(&ch->not_full);
    pthread_mutex_unlock(&ch->lock);
    return value;
}

int nv_channel_try_receive(nv_channel_t *ch, void **out) {
    pthread_mutex_lock(&ch->lock);
    if (ch->count == 0) {
        pthread_mutex_unlock(&ch->lock);
        return 0;
    }
    *out = ch->buf[ch->head];
    ch->head = (ch->head + 1) % ch->capacity;
    ch->count--;
    pthread_cond_signal(&ch->not_full);
    pthread_mutex_unlock(&ch->lock);
    return 1;
}

void nv_channel_close(nv_channel_t *ch) {
    pthread_mutex_lock(&ch->lock);
    ch->closed = 1;
    pthread_cond_broadcast(&ch->not_empty);
    pthread_cond_broadcast(&ch->not_full);
    pthread_mutex_unlock(&ch->lock);
}

/* nv_go_spawn — fire and forget detached thread */

void *nv_go_spawn(void *(*fn)(void *), void *arg) {
    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&tid, &attr, fn, arg) != 0)
        nv_panic("nv_go_spawn: pthread_create failed");
    pthread_attr_destroy(&attr);
    return NULL;
}

/* nv_future_spawn / nv_future_await */

struct nv_future {
    pthread_t  tid;
    void      *result;  /* populated after join */
};

nv_future_t *nv_future_spawn(void *(*fn)(void *), void *arg) {
    nv_future_t *f = (nv_future_t *)malloc(sizeof(nv_future_t));
    if (!f) nv_panic("nv_future_spawn: out of memory");
    f->result = NULL;
    if (pthread_create(&f->tid, NULL, fn, arg) != 0)
        nv_panic("nv_future_spawn: pthread_create failed");
    return f;
}

void *nv_future_await(nv_future_t *f) {
    if (!f) return NULL;
    void *retval = NULL;
    pthread_join(f->tid, &retval);
    free(f);
    return retval;
}
