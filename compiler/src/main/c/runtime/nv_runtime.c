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
#include <stdatomic.h>
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

/* ── Phase 5.1: String operations ───────────────────────────────────────── */

char *nv_str_slice(const char *s, int64_t from, int64_t to) {
    int64_t len = (int64_t)strlen(s);
    if (from < 0) from = 0;
    if (from > len) from = len;
    if (to < from) to = from;
    if (to > len) to = len;
    int64_t sl = to - from;
    char *buf = (char *)malloc((size_t)(sl + 1));
    if (!buf) nv_panic("nv_str_slice: out of memory");
    memcpy(buf, s + from, (size_t)sl);
    buf[sl] = '\0';
    return buf;
}

int64_t nv_str_index_of(const char *s, const char *needle) {
    const char *found = strstr(s, needle);
    return found ? (int64_t)(found - s) : -1;
}

int nv_str_contains(const char *s, const char *needle) {
    return strstr(s, needle) != NULL;
}

int nv_str_starts_with(const char *s, const char *prefix) {
    size_t plen = strlen(prefix);
    return strncmp(s, prefix, plen) == 0;
}

int nv_str_ends_with(const char *s, const char *suffix) {
    size_t slen = strlen(s);
    size_t suflen = strlen(suffix);
    if (slen < suflen) return 0;
    return strcmp(s + slen - suflen, suffix) == 0;
}

char *nv_str_to_upper(const char *s) {
    size_t len = strlen(s);
    char *buf = (char *)malloc(len + 1);
    if (!buf) nv_panic("nv_str_to_upper: out of memory");
    for (size_t i = 0; i < len; i++) buf[i] = (char)toupper((unsigned char)s[i]);
    buf[len] = '\0';
    return buf;
}

char *nv_str_to_lower(const char *s) {
    size_t len = strlen(s);
    char *buf = (char *)malloc(len + 1);
    if (!buf) nv_panic("nv_str_to_lower: out of memory");
    for (size_t i = 0; i < len; i++) buf[i] = (char)tolower((unsigned char)s[i]);
    buf[len] = '\0';
    return buf;
}

char *nv_str_trim(const char *s) {
    size_t len = strlen(s);
    size_t start = 0;
    while (start < len && isspace((unsigned char)s[start])) start++;
    size_t end = len;
    while (end > start && isspace((unsigned char)s[end - 1])) end--;
    size_t sl = end - start;
    char *buf = (char *)malloc(sl + 1);
    if (!buf) nv_panic("nv_str_trim: out of memory");
    memcpy(buf, s + start, sl);
    buf[sl] = '\0';
    return buf;
}

char *nv_str_replace(const char *s, const char *from, const char *to) {
    size_t flen = strlen(from);
    if (flen == 0) {
        /* empty from: return copy */
        size_t slen = strlen(s);
        char *buf = (char *)malloc(slen + 1);
        if (!buf) nv_panic("nv_str_replace: out of memory");
        memcpy(buf, s, slen + 1);
        return buf;
    }
    size_t slen = strlen(s);
    size_t tlen = strlen(to);
    /* count occurrences */
    size_t count = 0;
    const char *p = s;
    while ((p = strstr(p, from)) != NULL) { count++; p += flen; }
    /* compute new length */
    size_t newlen = slen - count * flen + count * tlen;
    char *buf = (char *)malloc(newlen + 1);
    if (!buf) nv_panic("nv_str_replace: out of memory");
    char *wp = buf;
    p = s;
    const char *match;
    while ((match = strstr(p, from)) != NULL) {
        size_t before = (size_t)(match - p);
        memcpy(wp, p, before);
        wp += before;
        memcpy(wp, to, tlen);
        wp += tlen;
        p = match + flen;
    }
    /* copy tail */
    size_t tail = strlen(p);
    memcpy(wp, p, tail + 1);
    return buf;
}

char *nv_str_repeat(const char *s, int64_t n) {
    size_t slen = strlen(s);
    size_t total = (n > 0) ? slen * (size_t)n : 0;
    char *buf = (char *)malloc(total + 1);
    if (!buf) nv_panic("nv_str_repeat: out of memory");
    for (int64_t i = 0; i < n; i++) memcpy(buf + i * slen, s, slen);
    buf[total] = '\0';
    return buf;
}

int64_t nv_str_parse_int(const char *s) {
    return (int64_t)strtoll(s, NULL, 10);
}

double nv_str_parse_float(const char *s) {
    return strtod(s, NULL);
}

/* ── Phase 5.2: List operations ─────────────────────────────────────────── */

static int64_t arr_count(const char *arr) {
    return *(const int64_t *)arr;
}

char *nv_arr_push_i64(const char *arr, int64_t val) {
    int64_t count = arr_count(arr);
    int64_t new_count = count + 1;
    size_t total = (size_t)(8 + new_count * 8);
    char *na = (char *)malloc(total);
    if (!na) nv_panic("nv_arr_push_i64: out of memory");
    *(int64_t *)na = new_count;
    memcpy(na + 8, arr + 8, (size_t)(count * 8));
    ((int64_t *)(na + 8))[count] = val;
    return na;
}

char *nv_arr_push_str(const char *arr, const char *val) {
    int64_t count = arr_count(arr);
    int64_t new_count = count + 1;
    size_t total = (size_t)(8 + new_count * 8);
    char *na = (char *)malloc(total);
    if (!na) nv_panic("nv_arr_push_str: out of memory");
    *(int64_t *)na = new_count;
    memcpy(na + 8, arr + 8, (size_t)(count * 8));
    ((const char **)(na + 8))[count] = val;
    return na;
}

int nv_arr_contains_i64(const char *arr, int64_t val) {
    int64_t count = arr_count(arr);
    const int64_t *data = (const int64_t *)(arr + 8);
    for (int64_t i = 0; i < count; i++) if (data[i] == val) return 1;
    return 0;
}

int nv_arr_contains_str(const char *arr, const char *val) {
    int64_t count = arr_count(arr);
    const char **data = (const char **)(arr + 8);
    for (int64_t i = 0; i < count; i++) if (strcmp(data[i], val) == 0) return 1;
    return 0;
}

int64_t nv_arr_index_of_i64(const char *arr, int64_t val) {
    int64_t count = arr_count(arr);
    const int64_t *data = (const int64_t *)(arr + 8);
    for (int64_t i = 0; i < count; i++) if (data[i] == val) return i;
    return -1;
}

int64_t nv_arr_index_of_str(const char *arr, const char *val) {
    int64_t count = arr_count(arr);
    const char **data = (const char **)(arr + 8);
    for (int64_t i = 0; i < count; i++) if (strcmp(data[i], val) == 0) return i;
    return -1;
}

/* ── Phase 5.2: Map (str→str) ─────────────────────────────────────────── */

char *nv_map_new(void) {
    char *m = (char *)malloc(8);
    if (!m) nv_panic("nv_map_new: out of memory");
    *(int64_t *)m = 0;
    return m;
}

int64_t nv_map_len(const char *map) {
    return *(const int64_t *)map;
}

char *nv_map_get_str(const char *map, const char *key) {
    int64_t count = nv_map_len(map);
    for (int64_t i = 0; i < count; i++) {
        const char *k = *(const char **)(map + 8 + i * 16);
        if (strcmp(k, key) == 0) {
            return *(char **)(map + 16 + i * 16);
        }
    }
    return NULL;
}

int nv_map_has_str(const char *map, const char *key) {
    return nv_map_get_str(map, key) != NULL;
}

char *nv_map_set_str(char *map, const char *key, const char *val) {
    int64_t count = nv_map_len(map);
    /* update in place if key exists */
    for (int64_t i = 0; i < count; i++) {
        const char *k = *(const char **)(map + 8 + i * 16);
        if (strcmp(k, key) == 0) {
            *(const char **)(map + 16 + i * 16) = val;
            return map;
        }
    }
    /* insert new pair */
    int64_t new_count = count + 1;
    size_t new_size = (size_t)(8 + new_count * 16);
    char *nm = (char *)malloc(new_size);
    if (!nm) nv_panic("nv_map_set_str: out of memory");
    memcpy(nm, map, (size_t)(8 + count * 16));
    *(int64_t *)nm = new_count;
    *(const char **)(nm + 8 + count * 16)      = key;
    *(const char **)(nm + 8 + count * 16 + 8)  = val;
    return nm;
}

/* ── Phase 5.3: File I/O ─────────────────────────────────────────────────── */

#include <ctype.h>
#include <unistd.h>

char *nv_file_open(const char *path, const char *mode) {
    return (char *)fopen(path, mode);
}

char *nv_file_open_read(const char *path)   { return (char *)fopen(path, "r"); }
char *nv_file_open_write(const char *path)  { return (char *)fopen(path, "w"); }
char *nv_file_open_append(const char *path) { return (char *)fopen(path, "a"); }

void nv_file_close(char *file) { fclose((FILE *)file); }

void nv_file_write(char *file, const char *s) { fputs(s, (FILE *)file); }

void nv_file_writeln(char *file, const char *s) {
    fputs(s, (FILE *)file);
    fputc('\n', (FILE *)file);
}

char *nv_file_read_line(char *file) {
    char *buf = (char *)malloc(4096);
    if (!buf) nv_panic("nv_file_read_line: out of memory");
    if (fgets(buf, 4096, (FILE *)file) == NULL) { free(buf); return NULL; }
    size_t len = strlen(buf);
    if (len > 0 && buf[len - 1] == '\n') buf[len - 1] = '\0';
    return buf;
}

char *nv_file_read_all(char *file) {
    char *buf = (char *)malloc(65536);
    if (!buf) nv_panic("nv_file_read_all: out of memory");
    size_t total = 0;
    size_t n;
    while (total < 65535 && (n = fread(buf + total, 1, 65535 - total, (FILE *)file)) > 0)
        total += n;
    buf[total] = '\0';
    return buf;
}

int nv_file_exists(const char *path) { return access(path, F_OK) == 0; }
int nv_file_is_null(const char *file) { return file == NULL; }

/* ── Phase 5.5: Reference counting ──────────────────────────────────────── */
/*
 * Class object layout: [int64_t strong_count][void* dtor_fn][user fields...]
 * The object pointer points to the start of the block.
 * strong_count is manipulated with C11 stdatomic operations.
 */

typedef struct { _Atomic int64_t strong_count; void (*dtor_fn)(void *); } nv_rc_hdr_t;

void nv_rc_retain(void *ptr) {
    if (!ptr) return;
    nv_rc_hdr_t *h = (nv_rc_hdr_t *)ptr;
    atomic_fetch_add_explicit(&h->strong_count, (int64_t)1, memory_order_seq_cst);
}

void nv_rc_release(void *ptr) {
    if (!ptr) return;
    nv_rc_hdr_t *h = (nv_rc_hdr_t *)ptr;
    int64_t old = atomic_fetch_sub_explicit(&h->strong_count, (int64_t)1, memory_order_seq_cst);
    if (old == 1) {
        if (h->dtor_fn) {
            h->dtor_fn(ptr);   /* destructor must call free(ptr) */
        } else {
            free(ptr);
        }
    }
}

void *nv_weak_load(void *ptr) {
    if (!ptr) return NULL;
    nv_rc_hdr_t *h = (nv_rc_hdr_t *)ptr;
    int64_t sc = atomic_load_explicit(&h->strong_count, memory_order_seq_cst);
    return (sc > 0) ? ptr : NULL;
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
