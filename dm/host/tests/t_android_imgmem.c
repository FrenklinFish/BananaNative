// The image memory blocks' extent allocator (dm/gfx/vk_android/vka_extents.h): allocations never overlap, keep their alignment, take exactly their units, fill a block,
// and merge back to one free extent whatever the order they are given back in
#include <stdio.h>
#include "../../gfx/vk_android/vka_extents.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define UNITS 262144u   // a 64 MiB block of 256-byte units

static uint8_t owner[UNITS];
typedef struct { int64_t u; uint32_t n; } run;
static run live[UNITS];

int main(void) {
    vke_list l; CHECK(vke_init(&l, UNITS), "init");
    CHECK(vke_empty(&l) && l.nf == 1, "a new block is one free extent");
    int64_t u = vke_alloc(&l, UNITS, 1); CHECK(u == 0, "the whole block at 0 (%lld)", (long long)u);
    CHECK(vke_alloc(&l, 1, 1) < 0 && l.nf == 0, "a full block has no unit");
    vke_free(&l, 0, UNITS); CHECK(vke_empty(&l) && l.nf == 1, "and is one extent again");
    // exact sizes: 7 units take 7, and the next allocation starts right after
    int64_t a = vke_alloc(&l, 7, 1), b = vke_alloc(&l, 9, 1), c = vke_alloc(&l, 16, 16);
    CHECK(a == 0 && b == 7 && c == 16, "first fit, exact sizes, alignment (%lld %lld %lld)", (long long)a, (long long)b, (long long)c);
    CHECK(l.used == 32, "32 units used (%u)", l.used);
    vke_free(&l, 7, 9);
    CHECK(vke_free(&l, 7, 9) == -2 && vke_free(&l, 8, 2) == -2 && l.used == 23, "a double free or a range inside a free extent is refused, used unchanged (%u)", l.used);
    vke_free(&l, 0, 7); vke_free(&l, 16, 16); CHECK(vke_empty(&l) && l.nf == 1, "freed in any order, merged");
    // random sizes and alignments, random order
    int n = 0; srand(7); uint64_t used = 0;
    for (int step = 0; step < 300000; step++) {
        if (n && (rand() % 3 == 0)) {
            int j = rand() % n; run r = live[j]; live[j] = live[--n];
            for (uint32_t x = 0; x < r.n; x++) owner[r.u + x] = 0;
            CHECK(vke_free(&l, (uint32_t)r.u, r.n) == 0, "free"); used -= r.n;
        } else {
            uint32_t sz = 1 + (uint32_t)(rand() % (rand() % 8 ? 64 : 4096)), al = 1u << (rand() % 7);
            int64_t x = vke_alloc(&l, sz, al);
            if (x < 0) continue;
            CHECK(x % al == 0 && x + sz <= UNITS, "%u units at %lld, alignment %u", sz, (long long)x, al);
            for (uint32_t y = 0; y < sz; y++) { if (owner[x + y]) { CHECK(0, "unit %lld given twice", (long long)(x + y)); break; } owner[x + y] = 1; }
            live[n++] = (run){x, sz}; used += sz;
        }
        if (fails > 10) break;
    }
    CHECK(used == l.used, "used units %u, counted %llu", l.used, (unsigned long long)used);
    for (uint32_t i = 1; i < l.nf; i++) CHECK(l.f[i - 1].a + l.f[i - 1].n < l.f[i].a, "free extents sorted and apart (%u)", i);
    while (n) { run r = live[--n]; vke_free(&l, (uint32_t)r.u, r.n); }
    CHECK(vke_empty(&l) && l.nf == 1 && l.f[0].a == 0 && l.f[0].n == UNITS, "everything given back leaves one free extent");
    vke_fini(&l);
    printf("t_android_imgmem: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
