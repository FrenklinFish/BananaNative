// Older DMSH_VK_ABI values this replay also plays, and what each one's modules do otherwise (android/SHADERS.md "Play while it runs";
// docs/reference/shaders.md "An older library of another ABI plays while the update runs"). A library of another version is taken by its header's abi
// (vka_shlib_check): this host's DMSH_VK_ABI, or one of these, whose differences the replay then follows (vka_shader_lib_differs). Plain C with no Vulkan
// (the Mac unit test t_android_shlib includes it). This file is not hashed into the ABI (vk_android_delta.py abi_digest reads dmsh_abi.h and dmsh_abi_vk.h only), so a
// row here moves no version. Add a row only when every other convention of that ABI is still this host's (the headers' diff, item by item), and give it a bit below for each
// one that is not, handled where the replay meets it
#pragma once
#include <string.h>
#include "../nvn/dmsh_chain_vk.h"

// What an older ABI's modules do otherwise (bits of a row's differs)
#define VKA_ABI_LOG_PAGES 1u   // the raw global memory write log is the page-hash set (word 0 an overflow flag, then 1 << 10 slots of guest pages, 8 probes), not the bitmap of
                               // table entries (vka_gml.h)

// The host ABI the rows were checked against: when DMSH_VK_ABI moves, the table is not used (vka_abi_compat_of) until its rows are checked again against the new
// headers and this line is raised (the unit test t_android_shlib fails meanwhile)
#define VKA_ABI_COMPAT_FOR "228b73bd10f6"
// The rows' ABIs, space-separated, in the table's order: what the launcher reads (build.gradle NX_DMSH_VK_ABI_COMPAT, ShaderLibs.playable)
#define DMSH_VK_ABI_COMPAT "674db0a4ff79"

typedef struct { const char *abi; unsigned differs; } vka_abi_compat_row;
static const vka_abi_compat_row vka_abi_compat[] = {
    // Before a319e773 (2026-11-07; Vulkan chains up to d64f157175fd): a pool table of 4096 entries, searched in 12 steps, and the page-hash write log.
    // The table of 8192 entries is a superset (entries 0..4095 the same, the lookup hint in word 6 unread; entry 0's sink in words 6 and 7 still written); the log is not
    {"674db0a4ff79", VKA_ABI_LOG_PAGES},
};
enum { VKA_ABI_COMPAT_N = sizeof vka_abi_compat / sizeof vka_abi_compat[0] };

// A header's abi field (12 characters, no NUL) against the table: its differences (0 = none), or -1 when it is not a row, or the table is not for this host's DMSH_VK_ABI
static inline int vka_abi_compat_of(const char abi[12]) {
    if (strcmp(VKA_ABI_COMPAT_FOR, DMSH_VK_ABI)) return -1;
    for (int i = 0; i < VKA_ABI_COMPAT_N; i++) if (!memcmp(abi, vka_abi_compat[i].abi, 12)) return (int)vka_abi_compat[i].differs;
    return -1;
}
