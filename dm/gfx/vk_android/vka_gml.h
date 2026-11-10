// The raw global memory write log's two formats (dmsh_abi.h DMSH_KIND_GMEM_LOG; vka_tex.c vka_tex_gml_take, gml_poll). Plain C with no Vulkan, so the Mac unit test
// (dm/host/tests t_android_shlib) reads logs written as the modules write them.
// - The bitmap (this host's ABI): bit i of 32-bit word i / 32 set when a store or atomic went through table entry i (DMSH_GMEM_MAP_ENTRIES bits).
// - The page-hash set (an older ABI with VKA_ABI_LOG_PAGES, vka_abi_compat.h): word 0 set when a page found no free slot, then 1 << VKA_GML_PAGES_LOG2 slots, each 0 (empty)
//   or a guest page (address >> 12) a store or atomic wrote; the modules probe 8 slots from the page's hash with a compare-and-swap (Ryujinx GlobalToStorage before a319e773)
// The replay keeps one buffer size for both (VKA_GML_MAX_BYTES) and binds each log at its format's size
#pragma once
#include <stdint.h>
#include "../nvn/dmsh_abi.h"
#include "vka_abi_compat.h"

enum {
    VKA_GML_BITMAP_BYTES = DMSH_GMEM_MAP_ENTRIES / 8,
    VKA_GML_PAGES_LOG2 = 10,   // the old DMSH_GMEM_LOG_LOG2 (GlobalToStorage.GmemLogLog2)
    VKA_GML_PAGES_BYTES = (1 + (1 << VKA_GML_PAGES_LOG2)) * 4,
    VKA_GML_MAX_BYTES = VKA_GML_PAGES_BYTES > VKA_GML_BITMAP_BYTES ? VKA_GML_PAGES_BYTES : VKA_GML_BITMAP_BYTES,
};

// The bytes a log of a library with these differences (vka_abi_compat_of) is bound with
static inline uint32_t vka_gml_bytes(unsigned differs) { return differs & VKA_ABI_LOG_PAGES ? VKA_GML_PAGES_BYTES : VKA_GML_BITMAP_BYTES; }

// Reads a done log of that format: entry(arg, i) for each table entry i >= 1 of the bitmap (entry 0, the empty one, is never a pool), page(arg, address) for each 4 KiB guest
// page of the page set. Returns 1 when the page set ran out of slots (some stores are not in it), else 0
static inline int vka_gml_walk(const uint32_t *w, unsigned differs, void (*entry)(void *, int), void (*page)(void *, uint64_t), void *arg) {
    if (differs & VKA_ABI_LOG_PAGES) {
        for (int k = 1; k <= 1 << VKA_GML_PAGES_LOG2; k++) if (w[k]) page(arg, (uint64_t)w[k] << 12);
        return w[0] != 0;
    }
    for (int wd = 0; wd < VKA_GML_BITMAP_BYTES / 4; wd++)
        for (uint32_t bits = w[wd]; bits; bits &= bits - 1) { int k = wd * 32 + __builtin_ctz(bits); if (k > 0) entry(arg, k); }
    return 0;
}
