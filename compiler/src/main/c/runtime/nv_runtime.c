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

/* ── Phase 5.6: Hash functions ───────────────────────────────────────────── */

int64_t nv_hash_fnv1a(const char *s) {
    uint64_t h = UINT64_C(14695981039346656037);
    while (*s) { h ^= (uint8_t)*s++; h *= UINT64_C(1099511628211); }
    return (int64_t)h;
}

int64_t nv_hash_djb2(const char *s) {
    uint64_t h = 5381;
    while (*s) h = h * 33 + (uint8_t)*s++;
    return (int64_t)h;
}

int64_t nv_hash_murmur3(const char *s, int64_t seed) {
    uint64_t h = (uint64_t)seed;
    const uint8_t *p = (const uint8_t *)s;
    size_t len = strlen(s);
    const uint64_t c1 = UINT64_C(0x87c37b91114253d5);
    const uint64_t c2 = UINT64_C(0x4cf5ad432745937f);
    size_t blocks = len / 8;
    for (size_t i = 0; i < blocks; i++) {
        uint64_t k;
        memcpy(&k, p + i * 8, 8);
        k *= c1; k = (k << 31) | (k >> 33); k *= c2;
        h ^= k; h = (h << 27) | (h >> 37); h = h * 5 + 0x52dce729;
    }
    /* tail */
    uint64_t k = 0;
    const uint8_t *tail = p + blocks * 8;
    switch (len & 7) {
        case 7: k ^= (uint64_t)tail[6] << 48; /* fall through */
        case 6: k ^= (uint64_t)tail[5] << 40; /* fall through */
        case 5: k ^= (uint64_t)tail[4] << 32; /* fall through */
        case 4: k ^= (uint64_t)tail[3] << 24; /* fall through */
        case 3: k ^= (uint64_t)tail[2] << 16; /* fall through */
        case 2: k ^= (uint64_t)tail[1] << 8;  /* fall through */
        case 1: k ^= (uint64_t)tail[0];
                k *= c1; k = (k << 31) | (k >> 33); k *= c2; h ^= k;
    }
    h ^= (uint64_t)len;
    h ^= h >> 33; h *= UINT64_C(0xff51afd7ed558ccd);
    h ^= h >> 33; h *= UINT64_C(0xc4ceb9fe1a85ec53);
    h ^= h >> 33;
    return (int64_t)h;
}

int64_t nv_hash_crc32(const char *s) {
    uint32_t crc = 0xFFFFFFFFu;
    while (*s) {
        crc ^= (uint8_t)*s++;
        for (int i = 0; i < 8; i++)
            crc = (crc >> 1) ^ (0xEDB88320u & -(crc & 1u));
    }
    return (int64_t)(crc ^ 0xFFFFFFFFu);
}

int64_t nv_hash_combine(int64_t h1, int64_t h2) {
    return h1 ^ (h2 + (int64_t)0x9e3779b9 + (h1 << 6) + (h1 >> 2));
}

/* SHA-256 ----------------------------------------------------------------- */
static const uint32_t sha256_k[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};
#define SHA256_ROTR(x,n) (((x)>>(n))|((x)<<(32-(n))))
#define SHA256_CH(e,f,g) (((e)&(f))^(~(e)&(g)))
#define SHA256_MAJ(a,b,c) (((a)&(b))^((a)&(c))^((b)&(c)))
#define SHA256_EP0(a) (SHA256_ROTR(a,2)^SHA256_ROTR(a,13)^SHA256_ROTR(a,22))
#define SHA256_EP1(e) (SHA256_ROTR(e,6)^SHA256_ROTR(e,11)^SHA256_ROTR(e,25))
#define SHA256_SIG0(x) (SHA256_ROTR(x,7)^SHA256_ROTR(x,18)^((x)>>3))
#define SHA256_SIG1(x) (SHA256_ROTR(x,17)^SHA256_ROTR(x,19)^((x)>>10))

char *nv_hash_sha256(const char *s) {
    size_t len = strlen(s);
    /* padded message length = smallest multiple of 64 >= len+9 */
    size_t ml = ((len + 9 + 63) / 64) * 64;
    uint8_t *msg = (uint8_t *)calloc(ml, 1);
    if (!msg) nv_panic("nv_hash_sha256: out of memory");
    memcpy(msg, s, len);
    msg[len] = 0x80;
    /* append 64-bit big-endian bit-length */
    uint64_t bitlen = (uint64_t)len * 8;
    for (int i = 7; i >= 0; i--) { msg[ml - 8 + i] = (uint8_t)(bitlen & 0xff); bitlen >>= 8; }
    /* initial hash */
    uint32_t h[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                     0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    /* process each 512-bit block */
    for (size_t b = 0; b < ml; b += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++)
            w[i] = ((uint32_t)msg[b+i*4]<<24)|((uint32_t)msg[b+i*4+1]<<16)|
                   ((uint32_t)msg[b+i*4+2]<<8)|(uint32_t)msg[b+i*4+3];
        for (int i = 16; i < 64; i++)
            w[i] = SHA256_SIG1(w[i-2]) + w[i-7] + SHA256_SIG0(w[i-15]) + w[i-16];
        uint32_t a=h[0],bv=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],hv=h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t t1 = hv + SHA256_EP1(e) + SHA256_CH(e,f,g) + sha256_k[i] + w[i];
            uint32_t t2 = SHA256_EP0(a) + SHA256_MAJ(a,bv,c);
            hv=g; g=f; f=e; e=d+t1; d=c; c=bv; bv=a; a=t1+t2;
        }
        h[0]+=a; h[1]+=bv; h[2]+=c; h[3]+=d; h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=hv;
    }
    free(msg);
    char *out = (char *)malloc(65);
    if (!out) nv_panic("nv_hash_sha256: out of memory");
    for (int i = 0; i < 8; i++) snprintf(out + i*8, 9, "%08x", h[i]);
    out[64] = '\0';
    return out;
}

/* MD5 -------------------------------------------------------------------- */
static const uint32_t md5_T[64] = {
    0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
    0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
    0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
    0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
    0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
    0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
    0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
    0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391
};
static const uint8_t md5_s[64] = {
    7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
    5, 9,14,20,5, 9,14,20,5, 9,14,20,5, 9,14,20,
    4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
    6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21
};
#define MD5_ROTL(x,n) (((x)<<(n))|((x)>>(32-(n))))

char *nv_hash_md5(const char *s) {
    size_t len = strlen(s);
    size_t ml = ((len + 9 + 63) / 64) * 64;
    uint8_t *msg = (uint8_t *)calloc(ml, 1);
    if (!msg) nv_panic("nv_hash_md5: out of memory");
    memcpy(msg, s, len);
    msg[len] = 0x80;
    /* append 64-bit little-endian bit-length */
    uint64_t bitlen = (uint64_t)len * 8;
    for (int i = 0; i < 8; i++) { msg[ml - 8 + i] = (uint8_t)(bitlen & 0xff); bitlen >>= 8; }
    uint32_t a0=0x67452301,b0=0xefcdab89,c0=0x98badcfe,d0=0x10325476;
    for (size_t blk = 0; blk < ml; blk += 64) {
        uint32_t M[16];
        for (int i = 0; i < 16; i++) {
            M[i] = (uint32_t)msg[blk+i*4] | ((uint32_t)msg[blk+i*4+1]<<8) |
                   ((uint32_t)msg[blk+i*4+2]<<16) | ((uint32_t)msg[blk+i*4+3]<<24);
        }
        uint32_t A=a0,B=b0,C=c0,D=d0;
        for (int i = 0; i < 64; i++) {
            uint32_t F; int g;
            if      (i < 16) { F = (B&C)|(~B&D);           g = i; }
            else if (i < 32) { F = (D&B)|(~D&C);           g = (5*i+1)%16; }
            else if (i < 48) { F = B^C^D;                  g = (3*i+5)%16; }
            else             { F = C^(B|(~D));              g = (7*i)%16; }
            F = F + A + md5_T[i] + M[g];
            A = D; D = C; C = B;
            B = B + MD5_ROTL(F, md5_s[i]);
        }
        a0+=A; b0+=B; c0+=C; d0+=D;
    }
    free(msg);
    char *out = (char *)malloc(33);
    if (!out) nv_panic("nv_hash_md5: out of memory");
    uint32_t digest[4] = {a0,b0,c0,d0};
    for (int i = 0; i < 4; i++) {
        /* little-endian byte order for md5 output */
        snprintf(out + i*8, 9, "%02x%02x%02x%02x",
            digest[i]&0xff, (digest[i]>>8)&0xff,
            (digest[i]>>16)&0xff, (digest[i]>>24)&0xff);
    }
    out[32] = '\0';
    return out;
}

/* ── Phase 5.6: Formatting functions ─────────────────────────────────────── */

char *nv_fmt_int(int64_t n, int64_t width, const char *padChar, int64_t radix) {
    char tmp[72]; /* enough for base-2 64-bit int */
    char *p = tmp + sizeof(tmp) - 1;
    *p = '\0';
    int neg = 0;
    uint64_t v;
    if (radix == 10 && n < 0) { neg = 1; v = (uint64_t)(-(n + 1)) + 1; }
    else v = (uint64_t)n;
    if (radix < 2 || radix > 16) radix = 10;
    const char *digits = "0123456789abcdef";
    do { *--p = digits[v % (uint64_t)radix]; v /= (uint64_t)radix; } while (v);
    if (neg) *--p = '-';
    size_t dlen = (size_t)(tmp + sizeof(tmp) - 1 - p);
    char pc = (padChar && padChar[0]) ? padChar[0] : ' ';
    size_t total = (width > 0 && (int64_t)dlen < width) ? (size_t)width : dlen;
    char *out = (char *)malloc(total + 1);
    if (!out) nv_panic("nv_fmt_int: out of memory");
    size_t pad = total - dlen;
    memset(out, pc, pad);
    memcpy(out + pad, p, dlen + 1);
    return out;
}

char *nv_fmt_float(double x, int64_t precision, const char *notation) {
    char fmt[16];
    char *out = (char *)malloc(64);
    if (!out) nv_panic("nv_fmt_float: out of memory");
    int prec = (int)(precision < 0 ? 6 : precision > 20 ? 20 : precision);
    char spec = 'g';
    if (notation && notation[0] == 'e') spec = 'e';
    else if (notation && notation[0] == 'f') spec = 'f';
    snprintf(fmt, sizeof(fmt), "%%.%d%c", prec, spec);
    snprintf(out, 64, fmt, x);
    return out;
}

char *nv_fmt_truncate(const char *s, int64_t width, const char *suffix) {
    size_t slen = strlen(s);
    size_t suflen = suffix ? strlen(suffix) : 0;
    if ((int64_t)slen <= width) {
        char *out = (char *)malloc(slen + 1);
        if (!out) nv_panic("nv_fmt_truncate: out of memory");
        memcpy(out, s, slen + 1);
        return out;
    }
    size_t keep = (width > (int64_t)suflen) ? (size_t)(width - (int64_t)suflen) : 0;
    char *out = (char *)malloc(keep + suflen + 1);
    if (!out) nv_panic("nv_fmt_truncate: out of memory");
    memcpy(out, s, keep);
    if (suflen) memcpy(out + keep, suffix, suflen);
    out[keep + suflen] = '\0';
    return out;
}

char *nv_fmt_file_size(int64_t bytes) {
    char *out = (char *)malloc(32);
    if (!out) nv_panic("nv_fmt_file_size: out of memory");
    if (bytes < 0) { snprintf(out, 32, "0 B"); return out; }
    if (bytes < 1024) { snprintf(out, 32, "%" PRId64 " B", bytes); return out; }
    double v = (double)bytes;
    const char *units[] = {"KB","MB","GB","TB","PB"};
    int u = 0;
    while (v >= 1024.0 && u < 4) { v /= 1024.0; u++; }
    if (v == (double)(int64_t)v) snprintf(out, 32, "%" PRId64 " %s", (int64_t)v, units[u]);
    else                         snprintf(out, 32, "%.1f %s", v, units[u]);
    return out;
}

char *nv_fmt_duration(int64_t ms) {
    char *out = (char *)malloc(32);
    if (!out) nv_panic("nv_fmt_duration: out of memory");
    if (ms < 0) ms = 0;
    if (ms < 1000) { snprintf(out, 32, "%" PRId64 "ms", ms); return out; }
    int64_t s  = ms / 1000;
    if (s  < 60) { snprintf(out, 32, "%" PRId64 "s", s); return out; }
    int64_t m  = s  / 60;  s  %= 60;
    if (m  < 60) { snprintf(out, 32, "%" PRId64 "m %" PRId64 "s", m, s); return out; }
    int64_t h  = m  / 60;  m  %= 60;
    snprintf(out, 32, "%" PRId64 "h %" PRId64 "m", h, m);
    return out;
}

char *nv_fmt_thousands(int64_t n, const char *sep) {
    char tmp[32];
    int neg = n < 0;
    uint64_t v = neg ? (uint64_t)(-(n + 1)) + 1 : (uint64_t)n;
    snprintf(tmp, sizeof(tmp), "%" PRIu64, v);
    size_t dlen = strlen(tmp);
    size_t seplen = sep ? strlen(sep) : 0;
    size_t groups = (dlen - 1) / 3;
    size_t total = dlen + groups * seplen + (neg ? 1 : 0);
    char *out = (char *)malloc(total + 1);
    if (!out) nv_panic("nv_fmt_thousands: out of memory");
    char *wp = out;
    if (neg) *wp++ = '-';
    size_t first = dlen % 3;
    if (first == 0) first = 3;
    memcpy(wp, tmp, first); wp += first;
    for (size_t i = first; i < dlen; i += 3) {
        if (seplen) { memcpy(wp, sep, seplen); wp += seplen; }
        memcpy(wp, tmp + i, 3); wp += 3;
    }
    *wp = '\0';
    return out;
}

/* ── Phase 5.6: Iterator / range functions ───────────────────────────────── */

char *nv_iter_range(int64_t start, int64_t end) {
    int64_t count = (end > start) ? end - start : 0;
    char *arr = (char *)malloc((size_t)(8 + count * 8));
    if (!arr) nv_panic("nv_iter_range: out of memory");
    *(int64_t *)arr = count;
    for (int64_t i = 0; i < count; i++) ((int64_t *)(arr + 8))[i] = start + i;
    return arr;
}

char *nv_iter_range_step(int64_t start, int64_t end, int64_t step) {
    if (step <= 0) { char *a = (char *)malloc(8); *(int64_t *)a = 0; return a; }
    int64_t count = (end > start) ? (end - start + step - 1) / step : 0;
    char *arr = (char *)malloc((size_t)(8 + count * 8));
    if (!arr) nv_panic("nv_iter_range_step: out of memory");
    *(int64_t *)arr = count;
    for (int64_t i = 0; i < count; i++) ((int64_t *)(arr + 8))[i] = start + i * step;
    return arr;
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
