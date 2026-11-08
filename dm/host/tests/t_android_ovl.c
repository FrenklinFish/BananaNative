// The overlay's blocks drawn into the swapchain image (dm/gfx/vk_android/vka_ovl.h): for every turn and corner, each display pixel of a block maps through the swapchain
// image's turn (rot_copy's: ROTATE_90 lands the display's (u, v) on the image's (1 - v, u), 180 on (1 - u, 1 - v), 270 on (v, 1 - u)) into the draw's viewport, and the
// viewport's texture coordinates there are the block's own pixel in the overlay image
#include <math.h>
#include <stdio.h>
#include "../../gfx/vk_android/vka_ovl.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

// The display pixel centre (X, Y) on a display ew x eh, in the swapchain image turned by pre
static void to_image(int pre, int ew, int eh, double X, double Y, double *ix, double *iy) {
    switch (pre) {
    case VKO_ROT90:  *ix = eh - Y; *iy = X; break;
    case VKO_ROT180: *ix = ew - X; *iy = eh - Y; break;
    case VKO_ROT270: *ix = Y; *iy = ew - X; break;
    default:         *ix = X; *iy = Y; break;
    }
}

int main(void) {
    const int pres[4] = {1, VKO_ROT90, VKO_ROT180, VKO_ROT270};
    const int ew = 2400, eh = 1080, bw = 130, bh = 210, tw = 192, th = 320, ty = 64;
    for (int pi = 0; pi < 4; pi++) for (int corner = 0; corner < 4; corner++) {
        int pre = pres[pi], quarter = pre == VKO_ROT90 || pre == VKO_ROT270, W = quarter ? eh : ew, H = quarter ? ew : eh;
        vko_draw o;
        CHECK(vko_place(pre, W, H, ew, eh, corner, bw, bh, tw, th, ty, &o), "pre %d corner %d: a block", pre, corner);
        CHECK(o.x >= 0 && o.y >= 0 && o.x + o.w <= W && o.y + o.h <= H, "pre %d corner %d: viewport %d,%d %dx%d inside the %dx%d image", pre, corner, o.x, o.y, o.w, o.h, W, H);
        CHECK(o.w * o.h == bw * bh, "pre %d corner %d: the viewport is the block turned", pre, corner);
        int dx = corner & 1 ? ew - bw : 0, dy = corner & 2 ? eh - bh : 0;
        const int pts[5][2] = {{0, 0}, {bw - 1, 0}, {0, bh - 1}, {bw - 1, bh - 1}, {bw / 3, bh / 2}};
        for (int q = 0; q < 5; q++) {
            double X = dx + pts[q][0] + 0.5, Y = dy + pts[q][1] + 0.5, ix, iy;
            to_image(pre, ew, eh, X, Y, &ix, &iy);
            double px = (ix - o.x) / o.w, py = (iy - o.y) / o.h;
            CHECK(px > 0 && px < 1 && py > 0 && py < 1, "pre %d corner %d point %d: in the viewport (%.3f, %.3f)", pre, corner, q, px, py);
            double u = o.pc[0] * px + o.pc[1] * py + o.pc[4], v = o.pc[2] * px + o.pc[3] * py + o.pc[5];
            double eu = (pts[q][0] + 0.5) / tw, ev = (ty + pts[q][1] + 0.5) / th;
            CHECK(fabs(u - eu) < 1e-5 && fabs(v - ev) < 1e-5, "pre %d corner %d point %d: texture (%.5f, %.5f), want (%.5f, %.5f)", pre, corner, q, u, v, eu, ev);
        }
    }
    // the corners land where they should on an unturned image
    vko_draw o;
    vko_place(1, ew, eh, ew, eh, VKO_TOP_RIGHT, bw, bh, tw, th, 0, &o);
    CHECK(o.x == ew - bw && o.y == 0, "top right: %d,%d", o.x, o.y);
    vko_place(1, ew, eh, ew, eh, VKO_BOTTOM_LEFT, bw, bh, tw, th, 0, &o);
    CHECK(o.x == 0 && o.y == eh - bh, "bottom left: %d,%d", o.x, o.y);
    // a block wider or taller than the display is cut to it; empty blocks draw nothing
    CHECK(vko_place(1, 100, 50, 100, 50, VKO_TOP_RIGHT, 300, 80, 320, 96, 0, &o) && o.x == 0 && o.w == 100 && o.h == 50, "cut to the display: %d %dx%d", o.x, o.w, o.h);
    CHECK(fabs(o.pc[0] - 100.0 / 320) < 1e-6 && fabs(o.pc[3] - 50.0 / 96) < 1e-6, "cut: the texture's part");
    CHECK(!vko_place(1, ew, eh, ew, eh, 0, 0, bh, tw, th, 0, &o), "no width: nothing");
    CHECK(!vko_place(1, ew, eh, ew, eh, 0, bw, 0, tw, th, 0, &o), "no height: nothing");
    CHECK(!vko_place(1, 0, 0, 0, 0, 0, bw, bh, tw, th, 0, &o), "no display: nothing");
    printf("t_android_ovl: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
