#version 450
// The draw selftest's vertex shader (vka_selftest.c), laid out as the shader library's ABI (dmsh_abi_vk.h): inputs at the NVN attribute index, the uniform slot 0 of the
// vertex stage at set 0 binding 0 (DMSH_VK_KIND_CBUF reg 0), clip space for Metal's y-up convention, and the GL depth constant SpecId 0 (DMSH_VK_SPEC_GL_DEPTH)
layout(location = 0) in vec4 a_pos;
layout(location = 1) in vec4 a_uv;
layout(set = 0, binding = 0, std140) uniform U { vec4 rot; vec4 scale; } u;
layout(constant_id = 0) const bool GL_DEPTH = false;
layout(location = 0) out vec4 v_uv;
void main() {
    vec2 p = vec2(a_pos.x * u.rot.x - a_pos.y * u.rot.y, a_pos.x * u.rot.y + a_pos.y * u.rot.x) * u.scale.xy;
    gl_Position = vec4(p, 0.5, 1.0);
    if (GL_DEPTH) gl_Position.z = gl_Position.z * 0.5 + gl_Position.w * 0.5;
    v_uv = a_uv;
}
