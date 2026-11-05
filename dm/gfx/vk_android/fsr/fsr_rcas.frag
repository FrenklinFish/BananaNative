#version 450
#extension GL_GOOGLE_include_directive : require
// FSR 1 RCAS: sharpens the EASU output (tex, the intermediate image, the picture unturned) into the swapchain image. The swapchain pixel q is turned back to the picture's
// pixel by q.x * m.xy + q.y * m.zw + t: the letterbox corner, and for a pre-rotated swapchain the quarter or half turn (vka_present.c fsr_copy)
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform P { uvec4 con0; ivec4 m; ivec2 t; } pc;
layout(location = 0) out vec4 o;
#define A_GPU 1
#define A_GLSL 1
#include "ffx_a.h"
#define FSR_RCAS_F 1
// Clamped: RCAS reads a 3x3 neighbourhood, and the replay leaves robustness off, so the edge pixels must not read outside the image
AF4 FsrRcasLoadF(ASU2 p) { return texelFetch(tex, clamp(p, ASU2(0), textureSize(tex, 0) - 1), 0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}
#include "ffx_fsr1.h"
void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    AF3 c;
    FsrRcasF(c.r, c.g, c.b, AU2(q.x * pc.m.xy + q.y * pc.m.zw + pc.t), pc.con0);
    o = vec4(c, 1.0);
}
