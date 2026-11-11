// The Vulkan shader library reader (vka_shlib.h). Every offset in the file is checked once at open, so lookups afterwards index the tables without further checks
#include "vka_shlib.h"
#include "vka_abi_compat.h"
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

// zstd's decompressor (dm/third_party/zstd/zstddeclib.c, linked beside this file): the two calls used
size_t ZSTD_decompress(void *dst, size_t dst_cap, const void *src, size_t src_size);
unsigned ZSTD_isError(size_t code);
const char *ZSTD_getErrorName(size_t code);

static int fail(char *err, size_t n, const char *fmt, ...) {
    if (err && n) { va_list ap; va_start(ap, fmt); vsnprintf(err, n, fmt, ap); va_end(ap); }
    return 0;
}
void vka_shlib_str(const char field[16], char out[17]) { memcpy(out, field, 16); out[16] = 0; }
static int str_is(const char field[16], const char *want) { char s[17]; vka_shlib_str(field, s); return !strcmp(s, want); }

int vka_shlib_check(vka_shlib *L, const void *base, size_t size, const char *chain, const char *vk_chain, const char *game, const char *abi, char *err, size_t errn) {
    memset(L, 0, sizeof *L);
    if (!base || size < sizeof(dmsh_vk_header)) return fail(err, errn, "%zu bytes: shorter than the header", size);
    const dmsh_vk_header *h = base; char s[17];
    if (h->magic != DMSH_VK_LIB_MAGIC) return fail(err, errn, "magic %#x, not a Vulkan shader library", h->magic);
    if (h->version != DMSH_VK_LIB_VERSION) return fail(err, errn, "library format %u, this host reads %u", h->version, DMSH_VK_LIB_VERSION);
    // Another version is taken only by the ABI the header records (12 characters, no NUL; zero in a library made before the field): what this host reads of it is the same
    // or by an older ABI this host plays too (vka_abi_compat.h), when the ABI asked is this host's own: differs says what its modules do otherwise
    int by_abi = 0, compat = -1;
    if ((chain && !str_is(h->chain, chain)) || (vk_chain && !str_is(h->vk_chain, vk_chain))) {
        by_abi = abi && strlen(abi) == sizeof h->abi && !memcmp(h->abi, abi, sizeof h->abi);
        if (!by_abi && abi && !strcmp(abi, DMSH_VK_ABI) && (compat = vka_abi_compat_of(h->abi)) >= 0) by_abi = 2;
        char a[13]; memcpy(a, h->abi, 12); a[12] = 0; for (int i = 0; i < 12; i++) if (a[i] < 32 || a[i] > 126) { a[0] = 0; break; }
        if (!by_abi && chain && !str_is(h->chain, chain)) { vka_shlib_str(h->chain, s); return fail(err, errn, "translator chain %s, this host has %s (library ABI %s, this host's %s)", s, chain, *a ? a : "none", abi ? abi : "not asked"); }
        if (!by_abi) { vka_shlib_str(h->vk_chain, s); return fail(err, errn, "Vulkan chain %s, this host has %s (library ABI %s, this host's %s)", s, vk_chain, *a ? a : "none", abi ? abi : "not asked"); }
    }
    if (game && !str_is(h->game, game)) { vka_shlib_str(h->game, s); return fail(err, errn, "built for game %s, this is %s", s, game); }
    uint64_t sz = size;
    if ((uint64_t)h->entry_off + (uint64_t)h->entry_count * sizeof(dmsh_vk_entry) > sz || (h->entry_off & 7)) return fail(err, errn, "entry table out of the file");
    if ((uint64_t)h->bind_off + (uint64_t)h->bind_count * sizeof(dmsh_vk_bind) > sz || (h->bind_off & 7)) return fail(err, errn, "binding table out of the file");
    if ((uint64_t)h->mod_off + (uint64_t)h->mod_count * sizeof(dmsh_vk_module) > sz || (h->mod_off & 7)) return fail(err, errn, "module table out of the file");
    if (h->blob_off > sz || h->blob_size > sz - h->blob_off) return fail(err, errn, "blob out of the file");
    const uint8_t *b = base;
    L->base = b; L->size = size; L->h = h; L->by_abi = by_abi; L->differs = compat > 0 ? (unsigned)compat : 0;
    L->ent = (const dmsh_vk_entry *)(b + h->entry_off); L->bind = (const dmsh_vk_bind *)(b + h->bind_off); L->mod = (const dmsh_vk_module *)(b + h->mod_off); L->blob = b + h->blob_off;
    for (uint32_t i = 0; i < h->entry_count; i++) {
        const dmsh_vk_entry *e = &L->ent[i];
        if (i && e->key <= L->ent[i - 1].key) return fail(err, errn, "entry %u (key %016llx) out of order", i, (unsigned long long)e->key);
        if ((uint64_t)e->bind + e->bind_count > h->bind_count) return fail(err, errn, "entry %016llx: bindings past the table", (unsigned long long)e->key);
        if ((uint64_t)e->mod + e->mod_count > h->mod_count || !e->mod_count) return fail(err, errn, "entry %016llx: modules past the table", (unsigned long long)e->key);
        if (vka_shlib_c1_off(e) + e->c1_len > h->blob_size) return fail(err, errn, "entry %016llx: c1 bytes past the blob", (unsigned long long)e->key);
        if (e->stage > DMSH_VK_STAGE_MS) return fail(err, errn, "entry %016llx: stage %u", (unsigned long long)e->key, e->stage);
    }
    for (uint32_t i = 0; i < h->mod_count; i++) {
        const dmsh_vk_module *m = &L->mod[i];
        if (vka_shlib_mod_off(m) + m->stored > h->blob_size) return fail(err, errn, "module %u past the blob", i);
        if (m->codec != DMSH_VK_CODEC_RAW && m->codec != DMSH_VK_CODEC_ZSTD) return fail(err, errn, "module %u: codec %u", i, m->codec);
        if (m->size & 3 || m->size < 20 || (m->codec == DMSH_VK_CODEC_RAW && m->stored != m->size)) return fail(err, errn, "module %u: %u bytes of SPIR-V, %u stored", i, m->size, m->stored);
    }
    return 1;
}

int vka_shlib_open(vka_shlib *L, const char *path, const char *chain, const char *vk_chain, const char *game, const char *abi, char *err, size_t errn) {
    memset(L, 0, sizeof *L);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fail(err, errn, "cannot open %s", path);
    struct stat st;
    if (fstat(fd, &st) || st.st_size < (off_t)sizeof(dmsh_vk_header)) { close(fd); return fail(err, errn, "%s: no header", path); }
    void *p = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) return fail(err, errn, "%s: mmap failed", path);
    if (!vka_shlib_check(L, p, (size_t)st.st_size, chain, vk_chain, game, abi, err, errn)) { munmap(p, (size_t)st.st_size); return 0; }
    L->mapped = 1;
    return 1;
}
void vka_shlib_close(vka_shlib *L) {
    if (L->mapped && L->base) munmap((void *)L->base, L->size);
    memset(L, 0, sizeof *L);
}

uint64_t vka_shlib_key(const uint8_t md[8]) { uint64_t k = 0; for (int i = 0; i < 8; i++) k = k << 8 | md[i]; return k; }

const dmsh_vk_entry *vka_shlib_find(const vka_shlib *L, uint64_t key) {
    if (!L->h) return NULL;
    uint32_t lo = 0, hi = L->h->entry_count;
    while (lo < hi) {
        uint32_t m = lo + (hi - lo) / 2; uint64_t k = L->ent[m].key;
        if (k == key) return &L->ent[m];
        if (k < key) lo = m + 1; else hi = m;
    }
    return NULL;
}
static uint64_t eh_mix(const void *p, size_t n, uint64_t h) {   // 8 bytes a step (the stored modules of a game's shaders are tens of MB)
    const uint8_t *b = p; size_t i = 0;
    for (; i + 8 <= n; i += 8) { uint64_t w; memcpy(&w, b + i, 8); h = (h ^ w) * 0x9E3779B97F4A7C15ull; h ^= h >> 29; }
    for (; i < n; i++) h = (h ^ b[i]) * 1099511628211ull;
    return h;
}
uint64_t vka_shlib_entry_hash(const vka_shlib *L, const dmsh_vk_entry *e) {
    dmsh_vk_entry z = *e; z.mod = 0; z.bind = 0; z.c1_off = 0; z.c1_off_hi = 0;
    uint64_t h = eh_mix("DMVKPC1", 8, 1469598103934665603ull);
    h = eh_mix(&z, sizeof z, h);
    h = eh_mix(L->bind + e->bind, (size_t)e->bind_count * sizeof(dmsh_vk_bind), h);
    if (e->c1_len) h = eh_mix(vka_shlib_c1(L, e), e->c1_len, h);
    for (unsigned i = 0; i < e->mod_count; i++) {
        dmsh_vk_module m = L->mod[e->mod + i]; const uint8_t *src = L->blob + vka_shlib_mod_off(&m);
        m.off = 0; m.off_hi = 0;
        h = eh_mix(&m, sizeof m, h); h = eh_mix(src, m.stored, h);
    }
    h ^= h >> 32;
    return h ? h : 1;
}
const dmsh_vk_module *vka_shlib_module(const vka_shlib *L, const dmsh_vk_entry *e, int variant) {
    for (unsigned i = 0; i < e->mod_count; i++) if (L->mod[e->mod + i].variant == variant) return &L->mod[e->mod + i];
    return NULL;
}

// DM_VK_SPV_OVERRIDE=<original.spv>:<patched.spv>[,...] (read once at the first module): a library module whose SPIR-V is byte for byte the original file is replaced by the
// patched one, on every path that unpacks it (the base module and every variant made from it), to test a hand-made fix of one shader's translation
static uint8_t *ov_read(const char *path, size_t *n) {
    FILE *f = fopen(path, "rb"); if (!f) return NULL;
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = sz > 0 ? malloc((size_t)sz) : NULL;
    if (b && fread(b, 1, (size_t)sz, f) != (size_t)sz) { free(b); b = NULL; }
    fclose(f); *n = b ? (size_t)sz : 0; return b;
}
static uint32_t *spv_override(uint32_t *w, size_t *nb) {   // DM_VK_SPV_OVERRIDE=<orig>:<patched>[,<orig>:<patched>...] (up to 4 pairs)
    static int ready, n; static uint8_t *orig[4], *patched[4]; static size_t no[4], np[4];
    if (!ready) { ready = 1; const char *e = getenv("DM_VK_SPV_OVERRIDE"); char a[2048];
        if (e && strlen(e) < sizeof a) { strcpy(a, e); char *save = NULL;
            for (char *t = strtok_r(a, ",", &save); t && n < 4; t = strtok_r(NULL, ",", &save)) { char *c = strchr(t, ':'); if (!c) continue; *c = 0;
                orig[n] = ov_read(t, &no[n]); patched[n] = ov_read(c + 1, &np[n]);
                if (orig[n] && patched[n]) fprintf(stderr, "[dm] spv override %d: on (original %zu bytes, patched %zu bytes)\n", n, no[n], np[n]); else fprintf(stderr, "[dm] [RED] spv override %d: files not read (original %zu bytes, patched %zu bytes)\n", n, no[n], np[n]); n++; } } }
    for (int i = 0; i < n; i++) {
        if (!orig[i] || !patched[i] || *nb != no[i] || memcmp(w, orig[i], no[i])) continue;
        uint32_t *o = malloc(np[i]); if (!o) return w;
        memcpy(o, patched[i], np[i]); free(w); *nb = np[i];
        static int nlog; if (nlog++ < 8) fprintf(stderr, "[dm] spv override: a module replaced by patched file %d (%zu -> %zu bytes)\n", i, no[i], np[i]);
        return o;
    }
    return w;
}

uint32_t *vka_shlib_spirv(const vka_shlib *L, const dmsh_vk_module *m, size_t *nbytes, char *err, size_t errn) {
    uint32_t *w = malloc(m->size);
    if (!w) { fail(err, errn, "%u bytes for a module", m->size); return NULL; }
    const uint8_t *src = L->blob + vka_shlib_mod_off(m);
    if (m->codec == DMSH_VK_CODEC_RAW) memcpy(w, src, m->size);
    else {
        size_t r = ZSTD_decompress(w, m->size, src, m->stored);
        if (ZSTD_isError(r) || r != m->size) { fail(err, errn, "zstd: %s (%zu of %u bytes)", ZSTD_isError(r) ? ZSTD_getErrorName(r) : "short frame", ZSTD_isError(r) ? 0 : r, m->size); free(w); return NULL; }
    }
    if (w[0] != 0x07230203u) { fail(err, errn, "not SPIR-V (first word %#x)", w[0]); free(w); return NULL; }
    size_t nb = m->size; w = spv_override(w, &nb);
    if (nbytes) *nbytes = nb;
    return w;
}
