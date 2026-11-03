// The free space of one image memory block (vka_imgmem.c) as a sorted list of free extents, in units (256 bytes there): an allocation takes exactly the units it needs at the
// first extent where an aligned start fits (first fit), and a free merges with its neighbours. No Vulkan, no locking (the caller's), so the host tests run it on the Mac
// (dm/host/tests/t_android_imgmem.c). An earlier version rounded every image up to a power-of-two run (a buddy allocator): about a third more memory for mipped textures
#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint32_t a, n; } vke_ext;                    // units [a, a + n)
typedef struct { vke_ext *f; uint32_t nf, cap, units, used; } vke_list;   // free extents by start; used: allocated units

static inline int vke_init(vke_list *l, uint32_t units) {
    memset(l, 0, sizeof *l);
    if (!(l->f = malloc(16 * sizeof *l->f))) return 0;
    l->cap = 16; l->units = units; l->f[0] = (vke_ext){0, units}; l->nf = 1; return 1;
}
static inline void vke_fini(vke_list *l) { free(l->f); memset(l, 0, sizeof *l); }
static inline int vke_empty(const vke_list *l) { return l->used == 0; }
static inline int vke_room(vke_list *l) {   // room for one more extent
    if (l->nf < l->cap) return 1;
    vke_ext *g = realloc(l->f, 2 * (size_t)l->cap * sizeof *g); if (!g) return 0;
    l->f = g; l->cap *= 2; return 1;
}
// n units at a multiple of align (a power of two): the first unit, or -1
static inline int64_t vke_alloc(vke_list *l, uint32_t n, uint32_t align) {
    if (!n || !align) return -1;
    for (uint32_t i = 0; i < l->nf; i++) {
        vke_ext e = l->f[i];
        uint64_t s = ((uint64_t)e.a + align - 1) & ~(uint64_t)(align - 1);
        if (s + n > (uint64_t)e.a + e.n) continue;
        uint32_t front = (uint32_t)(s - e.a), back = (uint32_t)((uint64_t)e.a + e.n - s - n);
        if (front && back) {   // split in two: one more extent
            if (!vke_room(l)) return -1;
            memmove(&l->f[i + 2], &l->f[i + 1], (l->nf - i - 1) * sizeof *l->f); l->nf++;
            l->f[i] = (vke_ext){e.a, front}; l->f[i + 1] = (vke_ext){(uint32_t)(s + n), back};
        } else if (front) l->f[i].n = front;
        else if (back) l->f[i] = (vke_ext){(uint32_t)(s + n), back};
        else { memmove(&l->f[i], &l->f[i + 1], (l->nf - i - 1) * sizeof *l->f); l->nf--; }
        l->used += n;
        return (int64_t)s;
    }
    return -1;
}
// The n units at a back (they must be one vke_alloc gave): 0; -2 when the range overlaps a free extent (a double free, or a range never given: nothing changes);
// -1 when the extent list could not grow (the units stay lost to reuse; vka_imgmem.c still counts them free, so the block can empty and be given back). used changes only on success
static inline int vke_free(vke_list *l, uint32_t a, uint32_t n) {
    uint32_t lo = 0, hi = l->nf;   // the first extent starting after a
    while (lo < hi) { uint32_t m = (lo + hi) / 2; if (l->f[m].a < a) lo = m + 1; else hi = m; }
    if ((lo > 0 && l->f[lo - 1].a + l->f[lo - 1].n > a) || (lo < l->nf && a + n > l->f[lo].a) || n > l->used) return -2;   // checked in release builds too (no assert)
    int left = lo > 0 && l->f[lo - 1].a + l->f[lo - 1].n == a, right = lo < l->nf && a + n == l->f[lo].a;
    if (left && right) { l->f[lo - 1].n += n + l->f[lo].n; memmove(&l->f[lo], &l->f[lo + 1], (l->nf - lo - 1) * sizeof *l->f); l->nf--; }
    else if (left) l->f[lo - 1].n += n;
    else if (right) { l->f[lo].a = a; l->f[lo].n += n; }
    else {
        if (!vke_room(l)) return -1;
        memmove(&l->f[lo + 1], &l->f[lo], (l->nf - lo) * sizeof *l->f); l->nf++; l->f[lo] = (vke_ext){a, n};
    }
    l->used -= n;
    return 0;
}
