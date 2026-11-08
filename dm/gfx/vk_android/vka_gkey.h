// What a graphics pipeline is made from beyond its shaders and layout (vka_pso.c), free of Vulkan so the prewarm list's unit test (dm/host/tests/t_android_pwlist.c)
// can read it: zero-filled, then compared bytewise. Formats and enums are VkFormat / Vk* values. The prewarm list (vka_pwlist.h) stores keys as these bytes, with a
// fingerprint of this layout (vka_pw_layout): a change here makes older lists, the profiles' shipped seed lists among them, unreadable (t_android_pwlist checks them)
#ifndef VKA_GKEY_H
#define VKA_GKEY_H
#include <stdint.h>
typedef struct {   // what a graphics pipeline is made from beyond its shaders and layout: zero-filled, then compared bytewise
    uint32_t cfmt[8], dfmt, sfmt;     // attachment formats (VK_FORMAT_UNDEFINED = none)
    uint8_t ncolor, samples, topo, poly_mode, depth_clamp, a2c, logic_en, logic_op, gl_depth, fs_variant, alpha_func, gs_fx;   // fs_variant: DMSH_VK_MOD_*; alpha_func: NVN_ALPHA_FUNC_* or 0; gs_fx: 1 = the geometry module with the claim (vka_shader_gs_module)
    uint32_t vin_u, vin_s;            // float vertex inputs fed UINT / SINT formats: retyped in the vertex module (vka_spvpatch.c), by location
    uint64_t tex_u[5], tex_s[5];      // float textures and images bound to UINT / SINT texture views: retyped in their stage's module (vka_shader_tex_module), by binding range
                                      // (DMSH_VK_STAGE_BINDINGS each: vertex or mesh, tess control or task, tess evaluation, geometry, fragment) and binding in it
    struct { uint8_t en, src, dst, op, srca, dsta, opa, mask; } att[8];
    uint32_t vin_static;              // no vertex input dynamic state: the attributes and bindings below are part of the pipeline
    struct { uint32_t fmt, offset; uint8_t binding, used, pad[2]; } va[32];   // by location
    struct { uint32_t stride, divisor; uint8_t used, pad[3]; } vb[33];         // by binding (stream; 32 = the zero buffer of inputs no attribute feeds)
} vka_gkey;
#endif
