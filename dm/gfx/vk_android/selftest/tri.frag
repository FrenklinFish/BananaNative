#version 450
// The draw selftest's fragment shader: the combined texture of slot 0 at binding 4 * 64 + 0 (DMSH_VK_KIND_TEX, sampler from the same handle) times a tint in the shader's own
// c1 bytes at binding 4 * 64 + 1 (DMSH_VK_KIND_C1), and the alpha test as the translator writes it (entry flag F_ALPHA_TEST): the NVN alpha function as SpecId 1
// (DMSH_VK_SPEC_ALPHA_FUNC, 0 = off), the reference in word 0 of the support buffer at binding 4 * 64 + 2 (DMSH_VK_KIND_SUPPORT)
layout(location = 0) in vec4 v_uv;
layout(set = 0, binding = 256) uniform sampler2D t0;
layout(set = 0, binding = 257, std140) uniform C1 { vec4 tint; } c1;
layout(set = 0, binding = 258, std140) uniform Support { uint alpha_test; } sup;
layout(constant_id = 1) const uint ALPHA_FUNC = 0u;
layout(location = 0) out vec4 o;
void main() {
    o = texture(t0, v_uv.xy) * c1.tint;
    float r = uintBitsToFloat(sup.alpha_test);
    bool pass = ALPHA_FUNC == 0u || ALPHA_FUNC == 8u || (ALPHA_FUNC == 2u && o.a < r) || (ALPHA_FUNC == 3u && o.a == r) || (ALPHA_FUNC == 4u && o.a <= r)
             || (ALPHA_FUNC == 5u && o.a > r) || (ALPHA_FUNC == 6u && o.a != r) || (ALPHA_FUNC == 7u && o.a >= r);
    if (!pass) discard;
}
