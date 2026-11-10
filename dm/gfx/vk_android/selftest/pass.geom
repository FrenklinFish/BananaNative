#version 450
// A geometry shader as the translator emulates a passthrough one when it is translated alone (EmitterContext, gs_passthrough false): it emits its input triangle with
// gl_Position only and no user output; t_android_spvpatch adds the pixel shader's input through vka_spv_gs_link
layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;
void main() {
    for (int i = 0; i < 3; i++) { gl_Position = gl_in[i].gl_Position; EmitVertex(); }
    EndPrimitive();
}
