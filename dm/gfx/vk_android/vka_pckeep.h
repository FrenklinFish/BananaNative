// Which pipeline cache file a run reads after a shader library update (vka_pso.c pc_open_once), free of Vulkan so the Mac's unit test (dm/host/tests/t_android_pckeep.c)
// checks it. The cache file's stamp, <file>.lib, names the library the file was saved with and lists the shaders whose pipelines it holds, each with the hash of its library
// entry (vka_shlib_entry_hash), and an estimate of the stale shaders it also holds. Text:
//   <library id, 16 hex digits>                  (the only line an older host wrote and reads)
//   keep 1 dead <stale shaders> shaders <n>
//   <key, 16 hex digits> <entry hash, 16 hex digits>   (n lines, by key)
// Vulkan's cache finds a pipeline by the driver's hash of the module and state it is given, so an entry of an older library is never used for a changed shader: it only
// takes space, and memory when a driver deserializes every object of a cache it opens (Mesa's vk_pipeline_cache_load: Turnip uploads each shader with its private memory).
// Nothing can take single objects out of a VkPipelineCache, so the stale ones are counted instead: a file of another library is read when the shaders it lists that the
// new library has byte for byte the same (live) outnumber the stale ones (the changed and dropped shaders of this update and of the earlier updates the file was carried
// over) by the ratio DM_VK_PCACHE_KEEP sets; otherwise the run starts empty and its first save replaces the file with this library's pipelines only
// (docs/reference/memory.md "A shader library update that threw the pipeline cache away")
#ifndef VKA_PCKEEP_H
#define VKA_PCKEEP_H
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint64_t key, hash; } vka_pck_ent;
typedef struct {
    uint64_t lib;                  // the library the file was saved with (0: no stamp, or not readable)
    int table;                     // 1: the stamp lists its shaders; 0: a stamp of the library only (a host before 2026-11-08's second change)
    uint64_t dead;                 // stale shaders in the file (an estimate: shaders of earlier libraries that later ones changed or dropped)
    vka_pck_ent *ent; size_t n;    // the shaders whose pipelines it holds, by key (malloc'd)
} vka_pck_stamp;

enum {
    VKA_PCK_SAME,         // the stamp names this library: read
    VKA_PCK_CARRIED,      // another library, its unchanged shaders kept: read
    VKA_PCK_UNVERIFIED,   // another library, a stamp without the shader list (one library's pipelines, as every such stamp is): read once, its shaders counted from this run on
    VKA_PCK_NO_STAMP,     // no stamp (a file that may hold every library ever played): started empty
    VKA_PCK_STALE,        // another library, with more stale shaders than DM_VK_PCACHE_KEEP allows (or none kept): started empty
    VKA_PCK_OFF,          // another library, DM_VK_PCACHE_KEEP=0: started empty
};
static inline int vka_pck_reads(int d) { return d == VKA_PCK_SAME || d == VKA_PCK_CARRIED || d == VKA_PCK_UNVERIFIED; }

static inline void vka_pck_free(vka_pck_stamp *s) { free(s->ent); memset(s, 0, sizeof *s); }

static inline int vka_pck_cmp_(const void *a, const void *b) { uint64_t x = ((const vka_pck_ent *)a)->key, y = ((const vka_pck_ent *)b)->key; return x < y ? -1 : x > y; }

// The stamp's text (n bytes, not terminated) → *s. A stamp whose first line is no library id has lib 0; one whose list is cut or malformed has its library only (table 0)
static inline void vka_pck_parse(const char *t, size_t n, vka_pck_stamp *s) {
    memset(s, 0, sizeof *s);
    char *b = malloc(n + 1); if (!b) return;
    memcpy(b, t, n); b[n] = 0;
    char *p = b, *nl = strchr(p, '\n'); if (nl) *nl = 0;
    char *end; unsigned long long lib = strtoull(p, &end, 16);
    if (end == p || end - p > 16) { free(b); return; }
    s->lib = lib;
    unsigned long long dead, cnt;
    if (!nl || sscanf(nl + 1, "keep 1 dead %llu shaders %llu", &dead, &cnt) != 2 || cnt > (1u << 24)) { free(b); return; }
    p = strchr(nl + 1, '\n');
    vka_pck_ent *e = malloc((cnt ? cnt : 1) * sizeof *e); size_t k = 0;
    while (e && p && k < cnt) {
        unsigned long long key, h;
        if (sscanf(p + 1, "%16llx %16llx", &key, &h) != 2 || !h) break;
        e[k++] = (vka_pck_ent){key, h};
        p = strchr(p + 1, '\n');
    }
    if (!e || k != cnt) { free(e); free(b); return; }
    qsort(e, k, sizeof *e, vka_pck_cmp_);
    size_t u = 0; for (size_t i = 0; i < k; i++) if (!u || e[u - 1].key != e[i].key) e[u++] = e[i];
    s->table = 1; s->dead = dead; s->ent = e; s->n = u;
    free(b);
}

// What a run with library cur does with a file whose stamp is old. keep_pct: DM_VK_PCACHE_KEEP, the most stale shaders as a percent of the live ones (0: a file of another
// library is never read). hash_of(ctx, key): the entry hash of key in library cur, 0 = not in it. *out: the shaders the file keeps for this library (by key, malloc'd; empty
// unless it is read), *live / *changed: of old's list, the shaders the same in cur / changed or dropped; *dead: the stale estimate carried on (0 when started empty)
static inline int vka_pck_decide(const vka_pck_stamp *old, uint64_t cur, int keep_pct, uint64_t (*hash_of)(void *, uint64_t), void *ctx,
                                 vka_pck_stamp *out, size_t *live, size_t *changed, uint64_t *dead) {
    memset(out, 0, sizeof *out); out->lib = cur; out->table = 1; *live = *changed = 0; *dead = 0;
    if (!old->lib) return VKA_PCK_NO_STAMP;
    if (old->lib == cur) {
        if (old->n && (out->ent = malloc(old->n * sizeof *out->ent))) { memcpy(out->ent, old->ent, old->n * sizeof *out->ent); out->n = old->n; }
        *live = out->n; *dead = out->dead = old->dead;
        return VKA_PCK_SAME;
    }
    if (keep_pct <= 0) return VKA_PCK_OFF;
    if (!old->table) return VKA_PCK_UNVERIFIED;
    vka_pck_ent *e = malloc((old->n ? old->n : 1) * sizeof *e); size_t k = 0;
    if (!e) return VKA_PCK_STALE;
    for (size_t i = 0; i < old->n; i++) {
        uint64_t h = hash_of(ctx, old->ent[i].key);
        if (h && h == old->ent[i].hash) e[k++] = old->ent[i]; else (*changed)++;
    }
    *live = k;
    uint64_t d = old->dead + *changed;
    if (!k || d * 100 > (uint64_t)k * (uint64_t)keep_pct) { free(e); return VKA_PCK_STALE; }
    out->ent = e; out->n = k; out->dead = *dead = d;
    return VKA_PCK_CARRIED;
}

// The stamp a save writes: lib, the stale estimate, the shaders the file kept (keep, by key) and those this run made (run, any order: their hashes win). malloc'd text; *len
static inline char *vka_pck_format(uint64_t lib, uint64_t dead, const vka_pck_ent *keep, size_t nk, const vka_pck_ent *run, size_t nr, size_t *len) {
    vka_pck_ent *k = malloc((nk ? nk : 1) * sizeof *k), *r = malloc((nr ? nr : 1) * sizeof *r), *all = malloc((nk + nr ? nk + nr : 1) * sizeof *all);
    if (!k || !r || !all) { free(k); free(r); free(all); return NULL; }
    memcpy(k, keep, nk * sizeof *k); memcpy(r, run, nr * sizeof *r);
    qsort(k, nk, sizeof *k, vka_pck_cmp_); qsort(r, nr, sizeof *r, vka_pck_cmp_);
    size_t i = 0, j = 0, u = 0;
    while (i < nk || j < nr) {
        vka_pck_ent e;
        if (j < nr && (i >= nk || r[j].key <= k[i].key)) { e = r[j++]; while (i < nk && k[i].key == e.key) i++; }   // (a key of both: this run's hash)
        else e = k[i++];
        if (!e.hash) continue;
        if (!u || all[u - 1].key != e.key) all[u++] = e;
    }
    free(k); free(r);
    size_t cap = 96 + u * 34; char *t = malloc(cap); if (!t) { free(all); return NULL; }
    size_t o = (size_t)snprintf(t, cap, "%016llx\nkeep 1 dead %llu shaders %zu\n", (unsigned long long)lib, (unsigned long long)dead, u);
    for (size_t q = 0; q < u; q++) o += (size_t)snprintf(t + o, cap - o, "%016llx %016llx\n", (unsigned long long)all[q].key, (unsigned long long)all[q].hash);
    free(all);
    *len = o;
    return t;
}
#endif
