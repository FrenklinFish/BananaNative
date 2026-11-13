// SPDX-License-Identifier: GPL-3.0-or-later
// Donkey Kong Bananza (Switch 2), main v458752 (build ID e25c90c1c136638885659fd55bf8a03b): 3D cameras and the 2D layer that follow the picture's shape.
//
// The game draws a 16:9 frame whatever the screen; Game settings > Aspect ratio / Screen shape fit that frame into DM_VK_PICTURE_ASPECT=<w>:<h> (vka_present.c). For the scene to be neither
// stretched nor cropped, the cameras must use the picture's ratio and the HUD must stay in the middle 16:9. Shared parts (the shape, the table of converted lenses, the row squeeze) are in
// wide_shape.h; this file is the game's two kinds of site (three gates in patches.py, OBS_DKB_WIDE; the kind in the parameter's bits from 5 up, the register of a camera site in bits 0-4):
//   Camera  main+0x45e238 (ldr s0,[x19,#0x104]), in the projection update of the scene camera's updater (x19): near +0xf8, far +0xfc, aspect +0x100, fovy in degrees +0x104. It turns them
//           into a sead::PerspectiveProjection (radians at +0xa0, sin, cos, tan at +0xac, aspect at +0xb0; the next block, +0x1c0..+0x1e0, is the culling frustum made from the same
//           numbers). The gate is before the first of the two reads, so the callback rewrites the INPUTS in the updater, once:
//             wider than 16:9  (Hor+)  the aspect becomes the picture's; the vertical field of view stays;
//             narrower         (Vert+) the aspect becomes the picture's, and the fovy's tangent grows by (16/9) / aspect, so the horizontal reach stays what a 16:9 picture shows.
//           Only an aspect within 0.01 of 16:9 is touched (a split view or an offscreen lens has another one). The game's constructor (main+0x462504, default 16:9) and its aspect setter
//           (main+0x45d5d4) write the 16:9 in again whenever they run; the converted-lens table (the fovy written, per updater) keeps a Vert+ fovy from being scaled twice then.
//   HUD     main+0x45f9e0 and main+0x803c9c, the instruction after the matrix copy main+0xb1314 in the two layout projection setups (main+0x45f840 and main+0x803b60, the
//           same code twice; the HUD's draw info is the second's; x0 the draw info, the matrix at +0x10, 4x4 floats row by row). The game's layouts (HUD, menus) are drawn in
//           perspective, not orthographically: each setup builds a
//           sead::PerspectiveProjection (fovy 15 degrees, near 1, far 10000, aspect = the layout rect's 1920 / 1080) and a camera 4101.7 units away, so the 1920x1080 plane
//           fills the picture. The layout shader (main's own, instanced: one draw per group of panes) gets that projection times the view and pane matrices as its
//           uniform block (seen with DM_VK_UB_PEEK: m00 4.27261, m11 7.59575, m32 -1). Its x row (wider picture: x 16/9 over the picture's ratio) or y row (narrower: the
//           ratio over 16/9) is scaled, which keeps the layer in the middle 16:9 of the picture. Only a perspective matrix with the 16:9 shape (m11 / m00 within 0.01 of
//           16/9, m32 -1) is touched, so a scaled one (its shape is then the picture's) is not scaled twice. A setup runs a few times a run (a layout system made).
//           The orthographic projections the other copies set (main+0xb1260 from the capture pane's calculation main+0xb0efc, 2/1920 x 2/1080 among them) are for panes
//           drawn into textures (captures, dialogue backgrounds, text, icon shadows): scaling them on the phone changed nothing on screen, and they are left alone.
//           The 3D fills the whole picture.
// Credit chain: the sites and the idea (camera aspect at the updater; the UI projection scaled at the copies, main+0x45f9dc among them) are the dkbwide patch set of Lucas-blip-png's dkb-pc-recomp
// (recomp/patches-dkb458752.txt, runtime/dkb_wide.c; GPL-3.0). That project widens the UI and the virtual display; this file squeezes the UI into the middle of our unchanged 16:9 frame and
// adds the Vert+ compensation for narrow pictures. See android/NOTICE.md.
//
// The picture shape is read once at start (restart to change it, as the launcher does). Not set, invalid, or within 0.02 of 16:9: the game stays as it made it.
#include "wide_shape.h"

#define DKB_MAIN_BUILD "e25c90c1c136638885659fd55bf8a03b"
#define K169 WS_K169
enum { C_ASPECT = 0x100, C_FOVY = 0x104, HUD_MTX = 0x10 };   // the draw info's projection, row by row: m[r][c] at +0x10 + 16r + 4c

static ws_cfg_t g_cfg = WS_CFG_INIT;
static ws_seen_t g_seen;   // updaters whose fovy we scaled: where, and the fovy (degrees) we wrote
static _Atomic unsigned g_fixes, g_huds;

static double aspect(void) { return ws_aspect(&g_cfg, DKB_MAIN_BUILD, "dkb"); }

// One camera updater, in place
static void camera(uint64_t obj) {
    double a = aspect();
    if (!a || !obj || (obj & 3)) return;
    float asp = ws_rdf(obj + C_ASPECT), fov = ws_rdf(obj + C_FOVY);
    if (!ws_close_to(asp, K169, 0.01)) return;
    if (!(fov > 1.0f && fov < 170.0f)) return;   // not a lens the game made (degrees)
    if (a < K169 && !ws_seen_has(g_seen, obj, fov)) {
        double half = ws_vertplus_half((float)tan(fov * M_PI / 360.0), a);   // under 172 degrees
        float nf = (float)(2 * half * 180.0 / M_PI);
        if (atomic_fetch_add_explicit(&g_fixes, 1, memory_order_relaxed) == 0)
            LOG("dkb wide: first camera fovy %.2f -> %.2f deg vertical (aspect %.4f -> %.4f)", fov, nf, asp, a);
        ws_wrf(obj + C_FOVY, nf);
        ws_seen_set(g_seen, obj, nf);
    }
    ws_wrf(obj + C_ASPECT, (float)a);
}

// The layout system's draw info after its projection was set (x0); the matrix is a copy of the game's, row by row
static void hud(uint64_t dinfo) {
    double a = aspect();
    if (!a || !dinfo || (dinfo & 3)) return;
    uint64_t m = dinfo + HUD_MTX;
    float m00 = ws_rdf(m), m11 = ws_rdf(m + 20), m32 = ws_rdf(m + 56), m33 = ws_rdf(m + 60);
    if (!(m00 > 0 && m11 > 0) || m32 != -1.0f || m33 != 0.0f || !ws_close_to(m11 / m00, K169, 0.01)) return;   // not the 16:9 layout perspective (or scaled already)
    float q = ws_squeeze(a);
    int row = a > K169 ? 0 : 1;
    for (int k = 0; k < 4; k++) ws_wrf(m + 16 * row + 4 * k, ws_rdf(m + 16 * row + 4 * k) * q);
    if (atomic_fetch_add_explicit(&g_huds, 1, memory_order_relaxed) == 0)
        LOG("dkb wide: layout perspective (m00 %.5f, m11 %.5f) -> %s row squeezed into the middle 16:9 (x%.4f)", m00, m11, row ? "y" : "x", q);
}

// The gate number's upper bits (param): the site kind from bit 5 (0 camera: the register in bits 0-4, 1 HUD: x0 the draw info)
static void dkb_wide_gate(uint64_t *r, int param) {
    if (param < 0) return;
    switch (param >> 5) {
    case 0: if ((param & 31) <= 28) camera(r[param & 31]); break;
    case 1: hud(r[0]); break;
    }
}

__attribute__((constructor)) static void dkb_wide_attach(void) { gate_obs_set(OBS_DKB_WIDE, dkb_wide_gate); }
