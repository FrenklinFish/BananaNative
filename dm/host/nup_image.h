// A NUP container (github.com/a55uka/nup, SPEC.md 1.0-draft; android/NUP.md) as a RomFS image: one package's RomFS subtree is turned into the
// in-memory index a DMROMFS1 image has (romfs_image.h), so hle_fs.c's lookups, listings and sizes are the same code for both.
//
// Naming: "<file>.nup", "<file>.nup:<selector>" or "<file>.nup:<selector>/romfs", the selector being base, update/<decimal version> or
// addon/<16 hex digits>; with none, the highest-version update's RomFS, else the base's. The host's own add-on mounts use "aoc/<index>": the addon
// package whose title ID's low 12 bits are the index (pack_android.py's index, tid - application - 0x1000).
//
// Files: a raw file (codec 0) is an image entry like a DMROMFS1 file (a = its extent's offset in the .nup, b = its size) and is read with pread.
// A zstd file (codec 1, one frame for the whole file) has IMG_ENT_ZSTD set, a = its index in the image's nup_z table; it is decompressed whole
// into memory when the game opens it and kept while it is open, then in a small least-recently-used cache (DM_NUP_CACHE_MB, default 128: memory is tight on the phone, MKW runs with under 700 MB free, and a file decompresses again in milliseconds).
// The largest compressed file in Donkey Kong Bananza is 53 MiB and only 8 are over 16 MiB, so whole-file buffers stay small; a cache on disk
// would have given back what compression saved (5.6 GiB of compressed files that shrink by 13 %).
// A large file (DM_NUP_DISK_MB, default 64 MiB and more; 0 = none) is decompressed by streaming into an unlinked temporary file ($TMPDIR, the app's
// cache folder on Android) and mapped read-only: its pages are the file's (clean page cache the kernel reclaims under pressure, read in again when
// touched), not anonymous memory, and it is decompressed once for the image's life. Pokemon Pokopia's 0x38f972af.fdata is 2.38 GiB: decompressed
// into memory it took 2.38 GiB of anonymous memory for as long as it was open, and the extent's 2.4 GiB buffer beside it while it was decompressed
// (12.5 GB resident at boot with the placed pools, killed by the low-memory killer). Without a temporary file it falls back to memory, as before.
#pragma once
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "romfs_image.h"
#include "plat/plat.h"

// zstd's decompressor (dm/third_party/zstd/zstddeclib.c): DM_NUP_ZSTD is defined where it is linked (the Android host, android/host/CMakeLists.txt,
// and the unit tests). Without it (the Mac and iOS hosts) a compressed file fails to open with a red line (nup_z_get); raw files read as usual
#ifdef DM_NUP_ZSTD
size_t ZSTD_decompress(void *dst, size_t dst_cap, const void *src, size_t src_size);
unsigned ZSTD_isError(size_t code);
const char *ZSTD_getErrorName(size_t code);
typedef struct ZSTD_DCtx_s ZSTD_DStream;
typedef struct ZSTD_inBuffer_s { const void *src; size_t size; size_t pos; } ZSTD_inBuffer;
typedef struct ZSTD_outBuffer_s { void *dst; size_t size; size_t pos; } ZSTD_outBuffer;
ZSTD_DStream *ZSTD_createDStream(void);
size_t ZSTD_freeDStream(ZSTD_DStream *zds);
size_t ZSTD_decompressStream(ZSTD_DStream *zds, ZSTD_outBuffer *output, ZSTD_inBuffer *input);
#endif
#include <sys/mman.h>
#include <time.h>
static inline uint64_t dm_now_ns_nup(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }

#define IMG_ENT_ZSTD 4   // in memory only (a NUP image): a = index in nup_z
#define NUP_NONE 0xFFFFFFFFu

typedef struct {
    uint64_t off, stored, size;
    uint8_t *buf;          // the decompressed file, or NULL
    uint8_t mapped;        // buf is a read-only map of a temporary file (a large file): kept for the image's life, not in the cache's budget
    uint32_t refs;         // opens holding buf
    uint8_t loading;       // a thread is decompressing it (the others wait on nup_aux.cv)
    uint64_t used;         // LRU stamp
} nup_z;
typedef struct {
    pthread_mutex_t lock; pthread_cond_t cv;
    uint64_t nz, held, budget, clock, disk_min, ndec;   // disk_min: files of this size and more go to a temporary file (0: none); ndec: decompressions
    nup_z *z;
    char what[160];        // "<file> package <selector>", for messages
} nup_aux;

static void nup_aux_free(void *p) {
    nup_aux *a = p; if (!a) return;
    for (uint64_t i = 0; i < a->nz; i++) { if (a->z[i].mapped) munmap(a->z[i].buf, a->z[i].size ? (size_t)a->z[i].size : 1); else free(a->z[i].buf); }
    free(a->z); pthread_mutex_destroy(&a->lock); pthread_cond_destroy(&a->cv); free(a);
}

// "<file>.nup[:<selector>[/romfs]]" → the file's path in file (NUL-terminated) and the selector in sel ("" for none, "/romfs" dropped). 0 for a
// spec that does not name a .nup.
static int nup_split(const char *spec, char *file, size_t fn, char *sel, size_t sn) {
    const char *c = NULL;
    for (const char *p = spec; (p = strstr(p, ".nup:")); p++) c = p;
    size_t L = strlen(spec);
    if (!c) {
        if (L < 4 || strcmp(spec + L - 4, ".nup")) return 0;
        if (L >= fn) return 0;
        memcpy(file, spec, L + 1); if (sn) sel[0] = 0; return 1;
    }
    size_t flen = (size_t)(c - spec) + 4;
    if (flen >= fn || strlen(c + 5) >= sn) return 0;
    memcpy(file, spec, flen); file[flen] = 0;
    strlcpy(sel, c + 5, sn);
    size_t s = strlen(sel);
    while (s && sel[s - 1] == '/') sel[--s] = 0;
    if (s >= 6 && !strcmp(sel + s - 6, "/romfs")) sel[s - 6] = 0;
    return 1;
}
static int nup_is_spec(const char *spec) { char file[1100], sel[128]; return spec && nup_split(spec, file, sizeof file, sel, sizeof sel); }

static uint32_t nup_u32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t nup_u64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }

// Opens spec as an image; NULL with errno set (EINVAL for a file that is not a valid NUP or a selector naming no package) and a reason in why.
static romfs_img *nup_image_open(const char *spec, char *why, size_t wn) {
    char file[1100], sel[128]; uint8_t sb[0x80]; uint8_t *toc = NULL; romfs_img *m = NULL; nup_aux *aux = NULL; uint32_t *queue = NULL; uint8_t *seen = NULL;
    int fd = -1, e = EINVAL; struct stat st;
#define NUP_BAD(...) do { snprintf(why, wn, __VA_ARGS__); goto bad; } while (0)
    if (!nup_split(spec, file, sizeof file, sel, sizeof sel)) NUP_BAD("not a .nup path");
    if ((fd = open(file, O_RDONLY)) < 0) { e = errno; NUP_BAD("cannot open %s", file); }
    if (fstat(fd, &st) || !romfs_image_read(fd, sb, sizeof sb, 0)) { e = errno ? errno : EINVAL; NUP_BAD("cannot read the superblock"); }
    e = EINVAL;
    if (memcmp(sb, "NUP\x01", 4)) NUP_BAD("not a NUP file (magic)");
    uint16_t hsize; memcpy(&hsize, sb + 4, 2);
    uint64_t toc_off = nup_u64(sb + 8), toc_size = nup_u64(sb + 0x10), blob = nup_u64(sb + 0x38), fsize = nup_u64(sb + 0x40);
    uint32_t npk = nup_u32(sb + 0x48), sflags = nup_u32(sb + 0x4C);
    if (hsize < 0x80 || toc_off != hsize || blob % 16 || toc_size < 0x30 || toc_size > (1ull << 31) || blob < toc_off + toc_size || fsize != (uint64_t)st.st_size)
        NUP_BAD("superblock sizes do not match the file (copied in part?)");
    if (sflags) NUP_BAD("unknown superblock flags %#x", sflags);
    if (!npk) NUP_BAD("no packages");
    if (!(toc = malloc((size_t)toc_size))) { e = ENOMEM; NUP_BAD("out of memory for the TOC"); }
    if (!romfs_image_read(fd, toc, (size_t)toc_size, (off_t)toc_off)) { e = errno ? errno : EINVAL; NUP_BAD("cannot read the TOC"); }
    e = EINVAL;
    { uint8_t h[32]; plat_sha256(toc, (size_t)toc_size, h); if (memcmp(h, sb + 0x18, 32)) NUP_BAD("the TOC's SHA-256 does not match (a damaged file)"); }
    if (memcmp(toc, "TOC\x01", 4) || nup_u32(toc + 4) != npk) NUP_BAD("bad TOC header");
    uint64_t nd = nup_u64(toc + 8), nf = nup_u64(toc + 0x10), pool_off = nup_u64(toc + 0x18), pool_size = nup_u64(toc + 0x20);
    uint64_t o_pk = 0x30, o_dir = o_pk + 0x40ull * npk;
    if (nd > toc_size / 0x18 || nf > toc_size / 0x50 || nd + nf > UINT32_MAX) NUP_BAD("bad TOC counts");
    uint64_t o_file = o_dir + 0x18 * nd, o_kids = o_file + 0x50 * nf;
    if (o_kids > toc_size || pool_off < o_kids || pool_off > toc_size || pool_size > toc_size - pool_off) NUP_BAD("TOC regions out of bounds");
    uint64_t nkids = (pool_off - o_kids) / 4;   // the children array runs up to the string pool (SPEC §3: the pool comes after it)
    const uint8_t *pool = toc + pool_off;

    // The package
    int64_t pick = -1; uint32_t best_ver = 0; int want_kind = -1; uint64_t want_tid = 0; uint32_t want_ver = 0; int aoc = -1;
    if (!sel[0]) want_kind = -2;   // the highest-version update, else the base
    else if (!strcmp(sel, "base")) want_kind = 0;
    else if (!strncmp(sel, "update/", 7) && sel[7] && strspn(sel + 7, "0123456789") == strlen(sel + 7)) { want_kind = 1; want_ver = (uint32_t)strtoul(sel + 7, NULL, 10); }
    else if (!strncmp(sel, "addon/", 6) && strlen(sel + 6) == 16 && strspn(sel + 6, "0123456789abcdefABCDEF") == 16) { want_kind = 2; want_tid = strtoull(sel + 6, NULL, 16); }
    else if (!strncmp(sel, "aoc/", 4) && sel[4] && strspn(sel + 4, "0123456789") == strlen(sel + 4)) { want_kind = 2; aoc = atoi(sel + 4); }
    else NUP_BAD("unknown selector \"%s\" (base, update/<version> or addon/<title ID>)", sel);
    for (uint32_t i = 0; i < npk; i++) {
        const uint8_t *p = toc + o_pk + 0x40ull * i; uint8_t kind = p[0x18];
        if (kind >= 5) NUP_BAD("package %u: unknown kind %u", i, kind);
        if (nup_u32(p + 0x10) == NUP_NONE) continue;   // no RomFS
        uint64_t tid = nup_u64(p); uint32_t ver = nup_u32(p + 0x14);
        if (want_kind == -2) { if (kind == 1 && (pick < 0 || toc[o_pk + 0x40 * pick + 0x18] != 1 || ver > best_ver)) { pick = i; best_ver = ver; }
                               else if (kind == 0 && pick < 0) pick = i; }
        else if (kind == want_kind && (want_kind == 0 || (want_kind == 1 && ver == want_ver) || (want_kind == 2 && (aoc >= 0 ? (int)(tid & 0xfff) == aoc : tid == want_tid)))) { pick = i; break; }
    }
    if (pick < 0) NUP_BAD("no package %s with a RomFS", sel[0] ? sel : "(update or base)");
    uint32_t root = nup_u32(toc + o_pk + 0x40 * pick + 0x10);
    if (root >= nd) NUP_BAD("package romfs_dir out of range");

    // Breadth-first walk from the RomFS root: entry 0 is the root, each directory's children are one run of entries (romfs_image_open's shape)
    uint64_t n = 1, names = 0, nz = 0;
    if (!(queue = malloc(sizeof *queue * (size_t)(nd + 1))) || !(seen = calloc((size_t)nd, 1))) { e = ENOMEM; NUP_BAD("out of memory"); }
    // pass 1: count entries, name bytes and compressed files, and check the tree
    { uint64_t qh = 0, qt = 0; queue[qt++] = root; seen[root] = 1;
      while (qh < qt) {
          const uint8_t *d = toc + o_dir + 0x18ull * queue[qh++];
          uint64_t first = nup_u32(d + 8), cnt = nup_u32(d + 12);
          if (first > nkids || cnt > nkids - first) NUP_BAD("a directory's children are out of range");
          const uint8_t *prev = NULL; uint32_t plen = 0;
          for (uint64_t j = 0; j < cnt; j++) {
              uint32_t id = nup_u32(toc + o_kids + 4 * (first + j));
              if (id >= nd + nf) NUP_BAD("a child node id is out of range");
              const uint8_t *r = id < nd ? toc + o_dir + 0x18ull * id : toc + o_file + 0x50ull * (id - nd);
              uint32_t no = nup_u32(r), nl = nup_u32(r + 4);
              if ((uint64_t)no + nl > pool_size || !nl || nl > 0x300) NUP_BAD("a name is out of range");
              const uint8_t *nm = pool + no;
              if (memchr(nm, 0, nl) || memchr(nm, '/', nl) || (nl == 1 && nm[0] == '.') || (nl == 2 && !memcmp(nm, "..", 2))) NUP_BAD("a name is not a plain file name");
              if (prev) { int c = memcmp(prev, nm, plen < nl ? plen : nl); if (c > 0 || (!c && plen >= nl)) NUP_BAD("children are not sorted by name (or repeat a name)"); }
              prev = nm; plen = nl;
              if (id < nd) { if (seen[id]) NUP_BAD("a directory is reached twice"); seen[id] = 1; queue[qt++] = id; }
              else {
                  uint64_t size = nup_u64(r + 8), xo = nup_u64(r + 0x10), xs = nup_u64(r + 0x18); uint8_t codec = r[0x40];
                  if (codec > 1) NUP_BAD("unknown codec %u", codec);
                  if (size && (xo < blob || xo % 16 || xs > fsize - xo || xo > fsize)) NUP_BAD("a file's extent is out of bounds");
                  if (!codec && xs != size) NUP_BAD("a raw file's extent is not its size");
                  if (codec == 1) { if (!xs) NUP_BAD("an empty zstd extent"); nz++; }
              }
              n++; names += nl;
          }
      }
    }
    if (n > UINT32_MAX) NUP_BAD("too many entries");
    // pass 2: the entries, in the same order
    size_t isz = (size_t)n * sizeof(img_ent) + (size_t)names;
    if (!(m = calloc(1, sizeof *m))) { e = ENOMEM; NUP_BAD("out of memory"); }
    m->fd = fd; fd = -1;
    if (!(m->e = malloc(isz ? isz : 1)) || !(aux = calloc(1, sizeof *aux))) { e = ENOMEM; NUP_BAD("out of memory"); }
    pthread_mutex_init(&aux->lock, NULL); pthread_cond_init(&aux->cv, NULL);
    if (nz && !(aux->z = calloc((size_t)nz, sizeof *aux->z))) { e = ENOMEM; NUP_BAD("out of memory"); }
    m->n = n; m->names = (const char *)m->e + n * sizeof(img_ent); aux->nz = nz;
    { const char *mb = getenv("DM_NUP_CACHE_MB"); aux->budget = (uint64_t)(mb && *mb ? atoll(mb) : 128) << 20; }
    { const char *mb = getenv("DM_NUP_DISK_MB"); aux->disk_min = (uint64_t)((mb && *mb ? strtod(mb, NULL) : 64) * 1048576.0); }   // (a fraction of a MiB: the unit test)
    { const char *b = strrchr(file, '/'); snprintf(aux->what, sizeof aux->what, "%s package %s", b ? b + 1 : file, sel[0] ? sel : "(newest)"); }
    m->aux = aux; m->aux_free = nup_aux_free;
    { char *np = (char *)m->names; uint64_t at = 0, k = 1, zi = 0, qh = 0, qt = 0;
      memset(&m->e[0], 0, sizeof m->e[0]); queue[qt++] = root;
      // entry k of a directory is dequeued in entry order: the queue holds NUP dir ids and, in step, the image entries they became (entry 0, then each directory child in turn)
      uint64_t *ent_of = malloc(sizeof *ent_of * (size_t)(nd + 1)); if (!ent_of) { e = ENOMEM; NUP_BAD("out of memory"); }
      ent_of[0] = 0;
      while (qh < qt) {
          uint64_t me = ent_of[qh]; const uint8_t *d = toc + o_dir + 0x18ull * queue[qh++];
          uint64_t first = nup_u32(d + 8), cnt = nup_u32(d + 12);
          m->e[me].a = k; m->e[me].b = cnt;
          for (uint64_t j = 0; j < cnt; j++, k++) {
              uint32_t id = nup_u32(toc + o_kids + 4 * (first + j));
              const uint8_t *r = id < nd ? toc + o_dir + 0x18ull * id : toc + o_file + 0x50ull * (id - nd);
              uint32_t no = nup_u32(r), nl = nup_u32(r + 4);
              img_ent *x = &m->e[k]; memset(x, 0, sizeof *x);
              x->name = (uint32_t)at; x->nlen = (uint16_t)nl; memcpy(np + at, pool + no, nl); at += nl;
              if (id < nd) { ent_of[qt] = k; queue[qt++] = id; }
              else {
                  x->type = 1; x->b = nup_u64(r + 8);
                  if (r[0x40] == 1) { nup_z *z = &aux->z[zi]; z->off = nup_u64(r + 0x10); z->stored = nup_u64(r + 0x18); z->size = x->b; x->a = zi++; x->flags = IMG_ENT_ZSTD; }
                  else x->a = nup_u64(r + 0x10);
              }
          }
      }
      free(ent_of);
    }
    free(queue); free(seen); free(toc);
    return m;
bad:
    if (m) { m->aux = NULL; romfs_image_close(m); m = NULL; } else if (fd >= 0) close(fd);
    nup_aux_free(aux); free(queue); free(seen); free(toc);
    errno = e; return NULL;
#undef NUP_BAD
}

// Frees cached buffers no open holds, oldest first, until the cache fits its budget (or only held ones are left). Called with a->lock held
static void nup_z_trim(nup_aux *a) {
    while (a->held > a->budget) {
        nup_z *old = NULL;
        for (uint64_t i = 0; i < a->nz; i++) { nup_z *z = &a->z[i]; if (z->buf && !z->mapped && !z->refs && !z->loading && (!old || z->used < old->used)) old = z; }
        if (!old) return;
        free(old->buf); old->buf = NULL; a->held -= old->size;
    }
}
#ifdef DM_NUP_ZSTD
// A large file: its frame decompressed by streaming (8 MiB at a time) into an unlinked temporary file, mapped read-only. NULL when there is no
// temporary file (the caller falls back to memory) or the frame is bad (*bad set, the reason in why)
static uint8_t *nup_z_disk(int fd, const nup_z *z, int *bad, char *why, size_t wn) {
    const char *td = getenv("TMPDIR"); char path[512];
    snprintf(path, sizeof path, "%s/nup-XXXXXX", td && *td ? td : "/tmp");
    int t = mkstemp(path); if (t < 0) { LOG("[RED] fs NUP: no temporary file in %s (errno %d): a %llu MiB compressed file is decompressed into memory", td && *td ? td : "/tmp", errno, (unsigned long long)(z->size >> 20)); return NULL; }
    unlink(path);
    enum { CH = 8 << 20 };
    uint8_t *in = malloc(CH), *outb = malloc(CH); ZSTD_DStream *ds = ZSTD_createDStream(); uint64_t done = 0, rd = 0; int e = 0;
    if (!in || !outb || !ds) e = ENOMEM;
    while (!e && rd < z->stored) {
        size_t n = z->stored - rd < CH ? (size_t)(z->stored - rd) : CH;
        if (!romfs_image_read(fd, in, n, (off_t)(z->off + rd))) { e = errno ? errno : EIO; snprintf(why, wn, "cannot read the extent (errno %d)", e); *bad = 1; break; }
        rd += n;
        ZSTD_inBuffer ib = {in, n, 0};
        // Until this chunk is consumed, and at the extent's end until the decoder has given out all it holds (a full output buffer may leave decoded bytes inside it)
        for (int more = 1; !e && more;) {
            ZSTD_outBuffer ob = {outb, CH, 0};
            size_t r = ZSTD_decompressStream(ds, &ob, &ib);
            if (ZSTD_isError(r)) { LOG("[RED] fs NUP: streaming zstd refused a %llu MiB file (%s): decompressed into memory", (unsigned long long)(z->size >> 20), ZSTD_getErrorName(r)); e = EIO; break; }   // (e.g. a frame window beyond the streaming limit: the one-shot decoder has none)
            more = ib.pos < ib.size || (rd == z->stored && r != 0 && ob.pos == ob.size);
            if (done + ob.pos > z->size) { snprintf(why, wn, "zstd: the frame is longer than the file's %llu bytes", (unsigned long long)z->size); e = EIO; *bad = 1; break; }
            for (size_t w = 0; w < ob.pos;) { ssize_t k = pwrite(t, outb + w, ob.pos - w, (off_t)(done + w)); if (k <= 0) { e = errno ? errno : EIO; break; } w += (size_t)k; }
            done += ob.pos;
        }
    }
    if (!e && done != z->size) { snprintf(why, wn, "zstd: short frame (%llu of %llu bytes)", (unsigned long long)done, (unsigned long long)z->size); e = EIO; *bad = 1; }
    free(in); free(outb); if (ds) ZSTD_freeDStream(ds);
    void *p = MAP_FAILED;
    if (!e) p = mmap(NULL, z->size ? (size_t)z->size : 1, PROT_READ, MAP_SHARED, t, 0);
    close(t);   // (the map keeps the file; unlinked, it goes with the map)
    if (p == MAP_FAILED) { if (!*bad && e != EIO) LOG("[RED] fs NUP: no temporary file for a %llu MiB compressed file in %s (errno %d): decompressed into memory", (unsigned long long)(z->size >> 20), td && *td ? td : "/tmp", e ? e : errno); return NULL; }
    return p;
}
#endif
// The decompressed bytes of compressed file zi, held for the caller (one reference: nup_z_put gives it back). NULL with errno set on failure, the
// reason in why. A file being decompressed by another thread is waited for, not decompressed twice
static const uint8_t *nup_z_get(romfs_img *m, uint64_t zi, char *why, size_t wn) {
    nup_aux *a = m->aux; nup_z *z = &a->z[zi];
    pthread_mutex_lock(&a->lock);
    while (z->loading) pthread_cond_wait(&a->cv, &a->lock);
    if (z->buf) { z->refs++; z->used = ++a->clock; pthread_mutex_unlock(&a->lock); return z->buf; }
    z->loading = 1; pthread_mutex_unlock(&a->lock);
    uint8_t *out = NULL, *src = NULL; int e = 0;
#ifndef DM_NUP_ZSTD
    snprintf(why, wn, "this host is built without a zstd decompressor (DM_NUP_ZSTD)"); e = ENOTSUP;
#else
    int bad = 0; uint8_t mapped = 0;
    if (a->disk_min && z->size >= a->disk_min) {
        uint64_t t0 = dm_now_ns_nup();
        if ((out = nup_z_disk(m->fd, z, &bad, why, wn))) { mapped = 1; LOG("fs NUP: a %.0f MiB compressed file of %s decompressed into a temporary file (%.1f s), mapped read-only", z->size / 1048576.0, a->what, (dm_now_ns_nup() - t0) / 1e9); }
        else if (bad) e = EIO;
    }
    if (mapped || e) {}
    else if (!(out = malloc(z->size ? (size_t)z->size : 1)) || !(src = malloc((size_t)z->stored))) { snprintf(why, wn, "out of memory for %llu bytes", (unsigned long long)z->size); e = ENOMEM; }
    else if (!romfs_image_read(m->fd, src, (size_t)z->stored, (off_t)z->off)) { e = errno ? errno : EIO; snprintf(why, wn, "cannot read the extent (errno %d)", e); }
    else {
        size_t r = ZSTD_decompress(out, (size_t)z->size, src, (size_t)z->stored);
        if (ZSTD_isError(r) || r != z->size) { snprintf(why, wn, "zstd: %s (%zu of %llu bytes)", ZSTD_isError(r) ? ZSTD_getErrorName(r) : "short frame", ZSTD_isError(r) ? 0 : r, (unsigned long long)z->size); e = EIO; }
    }
#endif
    free(src);
    pthread_mutex_lock(&a->lock);
    z->loading = 0;
#ifdef DM_NUP_ZSTD
    if (e) { if (!mapped) free(out); out = NULL; }
    else {
        z->buf = out; z->mapped = mapped; z->refs++; z->used = ++a->clock; a->ndec++; if (!mapped) a->held += z->size; nup_z_trim(a);
        if (!(a->ndec & 255)) LOG("fs NUP: %llu decompressions of the %llu compressed files of %s so far (a file the cache gave up is decompressed again at its next open)", (unsigned long long)a->ndec, (unsigned long long)a->nz, a->what);
    }
#else
    if (e) { free(out); out = NULL; }
    else { z->buf = out; z->refs++; z->used = ++a->clock; a->held += z->size; nup_z_trim(a); }
#endif
    pthread_cond_broadcast(&a->cv); pthread_mutex_unlock(&a->lock);
    if (e) errno = e;
    return out;
}
static void nup_z_put(romfs_img *m, uint64_t zi) {
    nup_aux *a = m->aux; pthread_mutex_lock(&a->lock);
    if (a->z[zi].refs) a->z[zi].refs--;
    nup_z_trim(a); pthread_mutex_unlock(&a->lock);
}
