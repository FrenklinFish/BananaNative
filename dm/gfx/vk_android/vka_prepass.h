// The depth-image rule of the profile key mesh_prepass_cut (vka_draw.c mesh_cut; docs/reference/rendering.md "Depth-only mesh prepass drawn as spikes on Qualcomm's
// driver"): a depth-only draw of a listed prepass mesh shader is cut only into a depth image of the kind its pair's colour draws tested EQUAL against in the last frames,
// so a shadow draw of the same program is drawn as asked. Pure C, no Vulkan: the unit test dm/host/tests/t_android_prepass.c checks it.
//
// The depth image is told by its shape (format, size, layers, samples, the view's layer and format), not by its texture's Initialize serial: Ocarina of Time initializes
// its scene depth texture anew every frame (a 1440x810 D32 target: serials 13120, 13275, 13312, 13346, ... in frames 764-767), so its colour draws of one frame never
// named the image its prepass draws of the next one used, and no prepass draw was cut (2026-11-12). The shadow images differ in format and size from the scene's depth.
#pragma once
#include <stdint.h>
#include "../nvn/dmsh_abi_vk.h"

// A depth target's identity: a hash of its shape; never 0 (0: no depth target)
static inline uint64_t vkpp_depth_shape(uint32_t format, uint32_t w, uint32_t h, uint32_t depth, uint32_t samples, uint32_t layer) {
    uint64_t x = 0xcbf29ce484222325ull;
    const uint32_t v[6] = {format, w, h, depth, samples > 1 ? samples : 1, layer};
    for (int i = 0; i < 6; i++) { x ^= v[i]; x *= 0x100000001b3ull; x ^= x >> 29; }
    return x | 1ull << 63;
}

enum { VKPP_SLOTS = 4, VKPP_SEEN_FRAMES = 4 };
// A pair's state: the depth images its colour draws tested EQUAL against and the frame each was last seen; the frame its prepass draw was last cut (frames counted from 1)
typedef struct { uint64_t dep[VKPP_SLOTS], seen[VKPP_SLOTS], cut; } vkpp_pair;

// A colour draw of the pair, testing EQUAL against depth image dep in frame f: noted. 1: a prepass draw of the pair was cut in this frame or the one before (the colour
// draw then needs that depth: drawn by prepass_draw, else LEQUAL); 0: none was (the prepass was drawn as the game asked, or not at all): EQUAL as asked
static inline int vkpp_colour(vkpp_pair *p, uint64_t dep, uint64_t f) {
    int s = 0;
    for (int k = 0; k < VKPP_SLOTS; k++) {
        if (p->dep[k] == dep) { s = k; break; }
        if (p->seen[k] < p->seen[s]) s = k;   // else the slot seen longest ago
    }
    p->dep[s] = dep; p->seen[s] = f;
    return p->cut && f - p->cut <= 1;
}
// A depth-only draw of the pair's prepass mesh shader into depth image dep in frame f: 1 = cut it (the pair's colour draws tested EQUAL against an image of that shape
// within the last VKPP_SEEN_FRAMES frames), noted as cut; 0 = draw it as asked (another image: a shadow map; or no colour draw seen yet)
static inline int vkpp_prepass(vkpp_pair *p, uint64_t dep, uint64_t f) {
    if (!dep) return 0;
    for (int k = 0; k < VKPP_SLOTS; k++)
        if (p->seen[k] && p->dep[k] == dep && f - p->seen[k] <= VKPP_SEEN_FRAMES) { p->cut = f; return 1; }
    return 0;
}

// A shader's bindings write guest memory: a binding it writes or does atomics on, or one whose slot or kind the library could not tell. A cut prepass draw whose task or
// mesh shader writes is still run with rasterizer discard (vka_draw.c prepass_side), so what it writes for the draws after it is there
static inline int vkpp_binds_write(const dmsh_vk_bind *b, unsigned n) {
    for (unsigned j = 0; b && j < n; j++)
        if ((b[j].flags & (DMSH_VK_BF_RW | DMSH_VK_BF_ATOMIC | DMSH_VK_BF_NOSLOT)) || b[j].kind == DMSH_VK_KIND_GMEM_LOG || b[j].kind == DMSH_VK_KIND_UNKNOWN) return 1;
    return 0;
}
