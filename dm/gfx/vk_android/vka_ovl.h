// Where the present draws a block of the launcher's overlay (vka_present.c, nxhost_overlay_set): a block of bw x bh pixels in a corner of the display (ew x eh, as the user
// sees it), taken from rows ty.. of the overlay image (tw x th), drawn into a swapchain image of W x H turned by its preTransform pre. The result is the draw's viewport in
// the swapchain image and the push constants of the rotated copy's vertex shader (vka_present_rot.h: texture (u, v) = (m.xy . p + t.x, m.zw . p + t.y) for the viewport's own
// position p in 0..1). The turns are rot_copy's: ROTATE_90 lands the display's (u, v) on the image's (1 - v, u), ROTATE_180 on (1 - u, 1 - v), ROTATE_270 on (v, 1 - u).
// Pure (no Vulkan): dm/host/tests/t_android_ovl.c checks it against the turns
#pragma once
#include <stdint.h>

enum { VKO_ROT90 = 2, VKO_ROT180 = 4, VKO_ROT270 = 8 };   // VkSurfaceTransformFlagBitsKHR's values; anything else is no turn
enum { VKO_TOP_LEFT = 0, VKO_TOP_RIGHT = 1, VKO_BOTTOM_LEFT = 2, VKO_BOTTOM_RIGHT = 3 };
typedef struct { int x, y, w, h; float pc[6]; } vko_draw;   // the viewport (whole pixels of the swapchain image) and the push constants

// 1 = something to draw (o filled), 0 = an empty block or display. A block larger than the display is cut to it (its top-left part stays)
static inline int vko_place(int pre, int W, int H, int ew, int eh, int corner, int bw, int bh, int tw, int th, int ty, vko_draw *o) {
    if (bw > ew) bw = ew;
    if (bh > eh) bh = eh;
    if (bw <= 0 || bh <= 0 || tw <= 0 || th <= 0) return 0;
    int dx = corner & 1 ? ew - bw : 0, dy = corner & 2 ? eh - bh : 0;   // the block on the display
    int a, b, c, d, e, f;   // the block's own (ul, vl) = (a px + b py + e, c px + d py + f) for the viewport's (px, py)
    switch (pre) {
    case VKO_ROT90:  o->x = W - (dy + bh); o->y = dx; o->w = bh; o->h = bw; a = 0; b = 1; e = 0; c = -1; d = 0; f = 1; break;
    case VKO_ROT270: o->x = dy; o->y = H - (dx + bw); o->w = bh; o->h = bw; a = 0; b = -1; e = 1; c = 1; d = 0; f = 0; break;
    case VKO_ROT180: o->x = W - (dx + bw); o->y = H - (dy + bh); o->w = bw; o->h = bh; a = -1; b = 0; e = 1; c = 0; d = -1; f = 1; break;
    default:         o->x = dx; o->y = dy; o->w = bw; o->h = bh; a = 1; b = 0; e = 0; c = 0; d = 1; f = 0; break;
    }
    float su = (float)bw / tw, sv = (float)bh / th, ov = (float)ty / th;
    o->pc[0] = su * a; o->pc[1] = su * b; o->pc[2] = sv * c; o->pc[3] = sv * d; o->pc[4] = su * e; o->pc[5] = ov + sv * f;
    return 1;
}
