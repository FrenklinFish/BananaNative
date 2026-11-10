#version 450
// Lanczos-2 resample of the window texture for the present's rotated copy: the kernel is widened by the minification on each texture
// axis (from the uv derivatives, so any quarter turn works), every texel under it is weighed. Small text keeps its strokes when
// 1920x1080 is squeezed onto a 1280x960 panel, where one bilinear sample skips a third of the columns.
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 o;
float L(float x) {
    x = abs(x);
    if (x >= 2.0) return 0.0;
    if (x < 1e-4) return 1.0;
    float px = 3.14159265 * x;
    return 2.0 * sin(px) * sin(px * 0.5) / (px * px);
}
void main() {
    vec2 ts = vec2(textureSize(tex, 0));
    vec2 dx = dFdx(uv) * ts, dy = dFdy(uv) * ts;
    vec2 s = clamp(vec2(max(abs(dx.x), abs(dy.x)), max(abs(dx.y), abs(dy.y))), vec2(1.0), vec2(2.0));
    vec2 p = uv * ts - 0.5;
    vec2 c = floor(p);
    ivec2 r = ivec2(ceil(2.0 * s));
    ivec2 hi = ivec2(ts) - 1;
    vec4 acc = vec4(0.0); float ws = 0.0;
#ifdef SHARP
    vec4 box = vec4(0.0); float wb = 0.0, mnl = 1.0, mxl = 0.0; vec4 mn = vec4(1.0), mx = vec4(0.0);
#endif
    for (int j = 1 - r.y; j <= r.y; j++) {
        float wy = L((c.y + float(j) - p.y) / s.y);
        if (wy == 0.0) continue;
        for (int i = 1 - r.x; i <= r.x; i++) {
            float w = wy * L((c.x + float(i) - p.x) / s.x);
            vec4 t = texelFetch(tex, clamp(ivec2(c) + ivec2(i, j), ivec2(0), hi), 0);
            acc += w * t;
            ws += w;
#ifdef SHARP
            vec2 d = abs(c + vec2(i, j) - p) / s;
            if (d.x < 0.75 && d.y < 0.75) { box += t; wb += 1.0; mn = min(mn, t); mx = max(mx, t); }
#endif
        }
    }
    vec4 r0 = acc / ws;
#ifdef SHARP
    // Mild unsharp mask against the footprint's box average, clamped to the footprint's range (no halos around letters)
    if (wb > 0.0) r0 = clamp(r0 + SHARP * (r0 - box / wb), mn, mx);
#endif
    o = clamp(r0, 0.0, 1.0);
}
