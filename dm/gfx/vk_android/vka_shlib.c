// The Vulkan shader library reader (vka_shlib.h). Every offset in the file is checked once at open, so lookups afterwards index the tables without further checks
#include "vka_shlib.h"
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

int vka_shlib_check(vka_shlib *L, const void *base, size_t size, const char *chain, const char *vk_chain, const char *game, char *err, size_t errn) {
    memset(L, 0, sizeof *L);
    if (!base || size < sizeof(dmsh_vk_header)) return fail(err, errn, "%zu bytes: shorter than the header", size);
    const dmsh_vk_header *h = base; char s[17];
    if (h->magic != DMSH_VK_LIB_MAGIC) return fail(err, errn, "magic %#x, not a Vulkan shader library", h->magic);
    if (h->version != DMSH_VK_LIB_VERSION) return fail(err, errn, "library format %u, this host reads %u", h->version, DMSH_VK_LIB_VERSION);
    if (chain && !str_is(h->chain, chain)) { vka_shlib_str(h->chain, s); return fail(err, errn, "translator chain %s, this host has %s", s, chain); }
    if (vk_chain && !str_is(h->vk_chain, vk_chain)) { vka_shlib_str(h->vk_chain, s); return fail(err, errn, "Vulkan chain %s, this host has %s", s, vk_chain); }
    if (game && !str_is(h->game, game)) { vka_shlib_str(h->game, s); return fail(err, errn, "built for game %s, this is %s", s, game); }
    uint64_t sz = size;
    if ((uint64_t)h->entry_off + (uint64_t)h->entry_count * sizeof(dmsh_vk_entry) > sz || (h->entry_off & 7)) return fail(err, errn, "entry table out of the file");
    if ((uint64_t)h->bind_off + (uint64_t)h->bind_count * sizeof(dmsh_vk_bind) > sz || (h->bind_off & 7)) return fail(err, errn, "binding table out of the file");
    if ((uint64_t)h->mod_off + (uint64_t)h->mod_count * sizeof(dmsh_vk_module) > sz || (h->mod_off & 7)) return fail(err, errn, "module table out of the file");
    if (h->blob_off > sz || h->blob_size > sz - h->blob_off) return fail(err, errn, "blob out of the file");
    const uint8_t *b = base;
    L->base = b; L->size = size; L->h = h;
    L->ent = (const dmsh_vk_entry *)(b + h->entry_off); L->bind = (const dmsh_vk_bind *)(b + h->bind_off); L->mod = (const dmsh_vk_module *)(b + h->mod_off); L->blob = b + h->blob_off;
    for (uint32_t i = 0; i < h->entry_count; i++) {
        const dmsh_vk_entry *e = &L->ent[i];
        if (i && e->key <= L->ent[i - 1].key) return fail(err, errn, "entry %u (key %016llx) out of order", i, (unsigned long long)e->key);
        if ((uint64_t)e->bind + e->bind_count > h->bind_count) return fail(err, errn, "entry %016llx: bindings past the table", (unsigned long long)e->key);
        if ((uint64_t)e->mod + e->mod_count > h->mod_count || !e->mod_count) return fail(err, errn, "entry %016llx: modules past the table", (unsigned long long)e->key);
        if ((uint64_t)e->c1_off + e->c1_len > h->blob_size) return fail(err, errn, "entry %016llx: c1 bytes past the blob", (unsigned long long)e->key);
        if (e->stage > DMSH_VK_STAGE_MS) return fail(err, errn, "entry %016llx: stage %u", (unsigned long long)e->key, e->stage);
    }
    for (uint32_t i = 0; i < h->mod_count; i++) {
        const dmsh_vk_module *m = &L->mod[i];
        if ((uint64_t)m->off + m->stored > h->blob_size) return fail(err, errn, "module %u past the blob", i);
        if (m->codec != DMSH_VK_CODEC_RAW && m->codec != DMSH_VK_CODEC_ZSTD) return fail(err, errn, "module %u: codec %u", i, m->codec);
        if (m->size & 3 || m->size < 20 || (m->codec == DMSH_VK_CODEC_RAW && m->stored != m->size)) return fail(err, errn, "module %u: %u bytes of SPIR-V, %u stored", i, m->size, m->stored);
    }
    return 1;
}

int vka_shlib_open(vka_shlib *L, const char *path, const char *chain, const char *vk_chain, const char *game, char *err, size_t errn) {
    memset(L, 0, sizeof *L);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fail(err, errn, "cannot open %s", path);
    struct stat st;
    if (fstat(fd, &st) || st.st_size < (off_t)sizeof(dmsh_vk_header)) { close(fd); return fail(err, errn, "%s: no header", path); }
    void *p = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) return fail(err, errn, "%s: mmap failed", path);
    if (!vka_shlib_check(L, p, (size_t)st.st_size, chain, vk_chain, game, err, errn)) { munmap(p, (size_t)st.st_size); return 0; }
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
const dmsh_vk_module *vka_shlib_module(const vka_shlib *L, const dmsh_vk_entry *e, int variant) {
    for (unsigned i = 0; i < e->mod_count; i++) if (L->mod[e->mod + i].variant == variant) return &L->mod[e->mod + i];
    return NULL;
}

uint32_t *vka_shlib_spirv(const vka_shlib *L, const dmsh_vk_module *m, size_t *nbytes, char *err, size_t errn) {
    uint32_t *w = malloc(m->size);
    if (!w) { fail(err, errn, "%u bytes for a module", m->size); return NULL; }
    const uint8_t *src = L->blob + m->off;
    if (m->codec == DMSH_VK_CODEC_RAW) memcpy(w, src, m->size);
    else {
        size_t r = ZSTD_decompress(w, m->size, src, m->stored);
        if (ZSTD_isError(r) || r != m->size) { fail(err, errn, "zstd: %s (%zu of %u bytes)", ZSTD_isError(r) ? ZSTD_getErrorName(r) : "short frame", ZSTD_isError(r) ? 0 : r, m->size); free(w); return NULL; }
    }
    if (w[0] != 0x07230203u) { fail(err, errn, "not SPIR-V (first word %#x)", w[0]); free(w); return NULL; }
    if (nbytes) *nbytes = m->size;
    return w;
}
