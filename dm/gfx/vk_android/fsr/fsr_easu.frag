#version 450
#extension GL_GOOGLE_include_directive : require
// FSR 1 EASU: the game's picture (tex, the crop the constants name) scaled to the intermediate image, one output pixel per fragment
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform P { uvec4 con0, con1, con2, con3; } pc;
layout(location = 0) out vec4 o;
#define A_GPU 1
#define A_GLSL 1
#include "ffx_a.h"
#define FSR_EASU_F 1
AF4 FsrEasuRF(AF2 p) { return textureGather(tex, p, 0); }
AF4 FsrEasuGF(AF2 p) { return textureGather(tex, p, 1); }
AF4 FsrEasuBF(AF2 p) { return textureGather(tex, p, 2); }
#include "ffx_fsr1.h"
void main() {
    AF3 c;
    FsrEasuF(c, AU2(gl_FragCoord.xy), pc.con0, pc.con1, pc.con2, pc.con3);
    o = vec4(c, 1.0);
}
