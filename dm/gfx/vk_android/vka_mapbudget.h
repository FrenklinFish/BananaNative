// The process's memory maps budget (vka_mem.c maps_tick), free of Vulkan so the Mac's unit tests (dm/host/tests/t_android_mapbudget.c, dm/gfx/tests/t_dirty_maps.c)
// can check it. Linux gives a process at most vm.max_map_count mappings (65,530 by default); past it any mmap, mprotect or munmap that needs one more fails with ENOMEM,
// in whatever thread asks (docs/reference/memory.md "Write tracking of small textures fills the process's memory maps"). The dirty-page tracker's write protections
// split placed pool memory, whose kernel driver mappings (kgsl) never merge again: a split stays until the mapping goes. So the count is read (the lines of
// /proc/self/maps) and the tracker made coarser as it climbs, so that new splits land on fewer boundaries; past the budget the placed segments with the most
// pieces get their CPU map made again in one piece (compaction), which is the only way a driver mapping's pieces become one again.
#ifndef VKA_MAPBUDGET_H
#define VKA_MAPBUDGET_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>

enum { VKMB_FIRST_PCT = 40,     // from this share of vm.max_map_count on, the tracker works in blocks of the base size (whatever DM_DIRTY_BLOCK_AT says) ...
       VKMB_STEP_PCT = 10,      // ... and in blocks 4 times larger at every further 10 % ...
       VKMB_STEPS = 3,          // ... up to 3 steps (64 KiB base: 256 KiB at 50 %, 1 MiB at 60 %, 4 MiB at 70 %)
       VKMB_BUDGET_PCT = 75,    // compaction from here (DM_VK_MAPS_BUDGET) ...
       VKMB_TARGET_PCT = 55,    // ... down to here
       VKMB_MIN_PIECES = 16 };  // a segment of fewer pieces is not worth its compaction (it stamps every page of it: its textures upload again)

// The tracker's block size at count maps of max (base: its first block size, 64 KiB on Android); 0 = no change asked for (under VKMB_FIRST_PCT, or nothing known)
static inline size_t vkmb_block(long count, long max, size_t base) {
    if (max <= 0 || count < 0 || !base || count * 100 < (long)VKMB_FIRST_PCT * max) return 0;
    size_t b = base;
    for (int s = 1; s <= VKMB_STEPS && count * 100 >= (long)(VKMB_FIRST_PCT + s * VKMB_STEP_PCT) * max; s++) b <<= 2;
    return b;
}
// Maps to shed by compaction: down to target_pct of max once at budget_pct or over; 0 under the budget
static inline long vkmb_shed(long count, long max, int budget_pct, int target_pct) {
    if (max <= 0 || count * 100 < (long)budget_pct * max) return 0;
    long t = max * target_pct / 100;
    return count > t ? count - t : 0;
}
// The segments to compact: the most pieces first (a segment of p pieces becomes one: p - 1 maps fewer), until shed is covered or maxpick are taken; segments under
// VKMB_MIN_PIECES are left. Their indexes go to idx; the count picked is returned and *covered says how many maps they shed
static inline int vkmb_pick(const int *pieces, int n, long shed, int *idx, int maxpick, long *covered) {
    int k = 0; long got = 0;
    while (k < maxpick && got < shed) {
        int best = -1;
        for (int i = 0; i < n; i++) {
            if (pieces[i] < VKMB_MIN_PIECES) continue;
            int taken = 0; for (int j = 0; j < k; j++) if (idx[j] == i) { taken = 1; break; }
            if (!taken && (best < 0 || pieces[i] > pieces[best])) best = i;
        }
        if (best < 0) break;
        idx[k++] = best; got += pieces[best] - 1;
    }
    if (covered) *covered = got;
    return k;
}

// One line of /proc/self/maps ("lo-hi perms offset dev inode   name", without its newline): its start address, and whether it is the GPU kernel driver's (kgsl). 0 = not a line
static inline int vkmb_line(const char *ln, size_t len, uint64_t *start, int *kgsl) {
    uint64_t a = 0; size_t i = 0;
    for (; i < len; i++) {
        char c = ln[i]; int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        if (d < 0) break;
        a = a << 4 | (uint64_t)d;
    }
    if (!i || i >= len || ln[i] != '-') return 0;
    *start = a;
    *kgsl = 0;
    for (size_t j = i; j + 4 <= len; j++) if (!memcmp(ln + j, "kgsl", 4)) { *kgsl = 1; break; }
    return 1;
}
// The segment (sorted, disjoint [lo, hi)) holding a, or -1
static inline int vkmb_seg(const uint64_t *lo, const uint64_t *hi, int n, uint64_t a) {
    int l = 0, h = n - 1, hit = -1;
    while (l <= h) { int m = (l + h) / 2; if (lo[m] <= a) { hit = m; l = m + 1; } else h = m - 1; }
    return hit >= 0 && a < hi[hit] ? hit : -1;
}
#endif
