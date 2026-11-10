// Raw global memory's pool table (dmsh_abi.h DMSH_GMEM_MAP_*), the parts that need no device (vka_draw.c gmem_map, vka_mem.c vka_mem_sink; t_android_gmem):
// - the lookup hints: the translator's helper (GlobalToStorage.cs TryGenerateRawStorageOp) reads word 6 of entry (chunk & (entries - 1)) for the address's 2 MiB chunk
//   (DMSH_GMEM_HINT_SHIFT); a hint names the entry holding the whole chunk, tagged with the chunk (chunk << 13 | entry), 0 = none, so a missing or colliding one searches.
//   Entry 0's words 6 and 7 are the sink (vk_android_spv.py gmem_sink, the device address where an access in no pool goes), so the chunks that map to entry 0
//   (c = 8192, 16384, ...) get no hint: written there, a hint overwrote the sink's low word;
// - the sink's address: 8 KiB aligned, so its low word, read as a hint for those chunks, names entry 0 (its low 13 bits are 0), which the helper takes as no hint
#ifndef VKA_GMEM_H
#define VKA_GMEM_H
#include <stdint.h>
#include <string.h>
#include "../nvn/dmsh_abi.h"

#define VKA_GMEM_INDEX_BITS 13   // log2 DMSH_GMEM_MAP_ENTRIES (GlobalToStorage.GmemMapLog2)
#define VKA_GMEM_HINT_WORD 6
#define VKA_GMEM_SINK_ALIGN (1u << VKA_GMEM_INDEX_BITS)   // a sink address whose index bits are 0 is never a hint

_Static_assert(DMSH_GMEM_MAP_ENTRIES == 1 << VKA_GMEM_INDEX_BITS, "the hint's index bits are the table's");

// The entry whose word 6 holds chunk c's hint; 0 for the chunks whose entry is entry 0 (the sink's): they get none
static inline uint32_t vka_gmem_hint_entry(uint64_t c) { return (uint32_t)(c & (DMSH_GMEM_MAP_ENTRIES - 1)); }

// The hints of a table whose entries 1..k hold the pools [lo, hi) by address (entry i + 1 = pool i): every 2 MiB chunk a pool holds whole, whose tag fits (chunks below
// 2^19, the helper's addrHigh < 256), at its entry unless that is entry 0. A later pool's chunk at the same entry replaces an earlier one's (the tag tells them apart)
// Every other entry's hint word is 0 first: an unused entry is all ones (start ~0), whose word 6 would read as entry 8191 tagged with chunk 2^19 - 1
static inline void vka_gmem_hints(uint8_t *table, const uint64_t *lo, const uint64_t *hi, int k) {
    for (uint32_t e = 1; e < DMSH_GMEM_MAP_ENTRIES; e++) memset(table + (size_t)e * DMSH_GMEM_MAP_ENTRY + VKA_GMEM_HINT_WORD * 4, 0, 4);
    for (int i = 0; i < k && i + 1 < DMSH_GMEM_MAP_ENTRIES; i++) {
        uint64_t size = 1ull << DMSH_GMEM_HINT_SHIFT, first = (lo[i] + size - 1) >> DMSH_GMEM_HINT_SHIFT, last = hi[i] >> DMSH_GMEM_HINT_SHIFT;
        for (uint64_t c = first; c < last && c < (1ull << (32 - VKA_GMEM_INDEX_BITS)); c++) {
            uint32_t e = vka_gmem_hint_entry(c);
            if (!e) continue;   // entry 0: the sink
            uint32_t hint = (uint32_t)(c << VKA_GMEM_INDEX_BITS | (unsigned)(i + 1));
            memcpy(table + (size_t)e * DMSH_GMEM_MAP_ENTRY + VKA_GMEM_HINT_WORD * 4, &hint, sizeof hint);
        }
    }
}

// The sink's address in a buffer at device address raw of at least 2 * VKA_GMEM_SINK_ALIGN bytes
static inline uint64_t vka_gmem_sink_at(uint64_t raw) { return (raw + VKA_GMEM_SINK_ALIGN - 1) & ~(uint64_t)(VKA_GMEM_SINK_ALIGN - 1); }
#endif
