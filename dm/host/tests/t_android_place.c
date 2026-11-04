// Large memory pools placed by parts (dm/gfx/vk_android/vka_place.h): which pools are placed by parts, and the part an ask places: the granules it touches, counted from
// the pool's start and clipped to it; an ask over the whole pool (or outside it) places the whole pool
#include <stdio.h>
#include "../../gfx/vk_android/vka_place.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define MIB (1ull << 20)

int main(void) {
    const uint64_t G = 64 * MIB, MIN = 1024 * MIB;
    // which pools
    CHECK(vkp_by_parts(0xdac00000ull, MIN, G), "Pokopia's 3.4 GiB pool is placed by parts");
    CHECK(!vkp_by_parts(0x14c00000ull, MIN, G), "a 332 MiB pool is placed whole");
    CHECK(!vkp_by_parts(0xdac00000ull, 0, G), "part minimum 0: whole");
    CHECK(!vkp_by_parts(96 * MIB, 64 * MIB, G), "less than two granules: whole");
    CHECK(vkp_by_parts(128 * MIB, 64 * MIB, G), "two granules: by parts");

    // the part: Pokopia's first ask, a buffer 0x25c0000 into the pool (the granule it lies in)
    uint64_t mem = 0x41479fb000ull, size = 0xdac00000ull, lo = 0, hi = 0;
    CHECK(vkp_part(mem, size, mem + 0x25c0000, mem + 0x25c0000 + 0x10000, G, &lo, &hi) && lo == mem && hi == mem + G, "first granule: %#llx-%#llx", (unsigned long long)lo, (unsigned long long)hi);
    // an ask across a granule's end takes both granules
    CHECK(vkp_part(mem, size, mem + G - 4096, mem + G + 4096, G, &lo, &hi) && lo == mem && hi == mem + 2 * G, "two granules");
    // an ask that ends on a granule's end takes no more
    CHECK(vkp_part(mem, size, mem + G, mem + 2 * G, G, &lo, &hi) && lo == mem + G && hi == mem + 2 * G, "exactly one granule");
    // the last granule is short (3,500 MiB = 54 granules + 44 MiB): clipped to the pool
    CHECK(vkp_part(mem, size, mem + size - 4096, mem + size, G, &lo, &hi) && lo == mem + 54 * G && hi == mem + size, "last, short granule: %#llx-%#llx", (unsigned long long)lo, (unsigned long long)hi);
    // an ask past the pool's end is clipped to it
    CHECK(vkp_part(mem, size, mem + size - 4096, mem + size + G, G, &lo, &hi) && lo == mem + 54 * G && hi == mem + size, "past the end: clipped");
    // an ask that starts before the pool is clipped to its start
    CHECK(vkp_part(mem, size, mem - 4096, mem + 4096, G, &lo, &hi) && lo == mem && hi == mem + G, "before the start: clipped");
    // whole-pool asks (nvnMemoryPoolGetBufferAddress) and asks outside: 0, the whole pool
    CHECK(!vkp_part(mem, size, mem, mem + size, G, &lo, &hi), "the pool's own range: whole");
    CHECK(!vkp_part(mem, size, mem - G, mem + size + G, G, &lo, &hi), "more than the pool: whole");
    CHECK(!vkp_part(mem, size, mem + size, mem + size + 4096, G, &lo, &hi), "after the pool: not a part");
    CHECK(!vkp_part(mem, size, mem - 8192, mem, G, &lo, &hi), "before the pool: not a part");
    CHECK(!vkp_part(mem, size, mem + 4096, mem + 4096, G, &lo, &hi), "empty ask: not a part");
    // a pool of two granules: an ask over both is the whole pool
    CHECK(!vkp_part(mem, 2 * G, mem + 4096, mem + 2 * G - 4096, G, &lo, &hi), "both granules of a two-granule pool: whole");
    // an ask near the top of the address space does not wrap
    uint64_t top = 0xffffffffffff0000ull - 4 * G;
    CHECK(vkp_part(top, 4 * G, top + 3 * G + 4096, top + 4 * G, G, &lo, &hi) && lo == top + 3 * G && hi == top + 4 * G, "near the top");

    if (fails) { printf("t_android_place: %d FAILED\n", fails); return 1; }
    printf("t_android_place: ok\n");
    return 0;
}
