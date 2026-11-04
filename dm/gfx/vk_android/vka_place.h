// Placing large memory pools by parts (vka_mem.c mtl_pool_need; docs/reference/memory.md "Placed pools commit what the game only reserved"): the range arithmetic, free of
// Vulkan so the Mac's unit test (dm/host/tests/t_android_place.c) can check it. A pool of at least the part minimum is placed in parts: when the GPU is given [a, e) in it,
// the granules of the pool that [a, e) touches become placed memory (granules counted from the pool's start), not the whole pool. An ask that covers the whole pool
// (nvnMemoryPoolGetBufferAddress) places all of it. The caller aligns the part outward to the placed-map alignment, as it does a whole pool
#ifndef VKA_PLACE_H
#define VKA_PLACE_H
#include <stdint.h>

// 1 = the pool [mem, mem + size) is placed by parts (part_min 0: never; a pool of less than two granules is placed whole, a part would be most of it)
static inline int vkp_by_parts(uint64_t size, uint64_t part_min, uint64_t granule) {
    return part_min && granule && size >= part_min && size >= 2 * granule;
}
// The part of the pool to place for the ask [a, e): the granules it touches, clipped to the pool, into [*lo, *hi). Returns 0 when the ask covers the whole pool or lies
// outside it (the caller places the whole pool, as before), 1 for a part
static inline int vkp_part(uint64_t mem, uint64_t size, uint64_t a, uint64_t e, uint64_t granule, uint64_t *lo, uint64_t *hi) {
    uint64_t end = mem + size;
    if (!granule || e <= a || a >= end || e <= mem || (a <= mem && e >= end)) return 0;
    uint64_t s = a > mem ? a - mem : 0, t = (e < end ? e : end) - mem;   // [s, t): the ask in the pool, as offsets
    s -= s % granule;
    t = t % granule ? t + (granule - t % granule) : t;
    if (t > size || t < s) t = size;   // (the last granule may be short; t < s: overflow of the round up)
    if (s == 0 && t == size) return 0;
    *lo = mem + s; *hi = mem + t;
    return 1;
}
#endif
