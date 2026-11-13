// SPDX-License-Identifier: GPL-3.0-or-later
// Mario Kart World (Switch 2), main 1.8.0 (build ID d00e4f399e411d0613aa8d26b1c02fef): 3D cameras and 2D layer that follow the picture's shape.
//
// The game draws a 16:9 frame whatever the screen; Game settings > Aspect ratio shows the frame at another shape (DM_VK_PICTURE_ASPECT=<w>:<h>, vka_present.c). See wide_shape.h.
// Gate frame (entry.S dm_gate): x0..x30 at r[0..30], q0..q31 at r[32 + 2 * n]; a write to a register takes effect when the gate returns.
//
// Camera (kind 0, parameter bits 0-4 = the register that holds the sead::PerspectiveProjection): main+0x8a6d8 and main+0x8a788 are `str s0,[xN,#0xb0]`, the store of a layer
// camera's aspect, the viewport's width over its height (fdiv s0,s0,s1 just before). The gate runs before the store, so s0 (q0) is still ours to change: a 16:9 view, or an 8:9 one
// (half of a two-player split), within 0.008, gets its aspect times (picture aspect / (16/9)); other views (menus' own) keep theirs. For a narrower picture the projection's half-angle
// tangent grows by (16/9) / aspect as well (Vert+): fovy (+0xa0, proportional to the half angle), sin, cos and tan (+0xa4, +0xa8, +0xac) are rewritten, with the table of converted
// projections stopping a second scale if the game does not write them again. The layer update (main+0x89480..0x894cc) writes near / far, fovy, sin, cos, tan before it calls the
// function holding these stores (main+0x8a5b0), and the matrix update (main+0x75b30, `ldp s1,s2,[x0,#0xac]`) reads tan and aspect after.
// The first store (main+0x8a6d8, x8) is in the loop over the layer's first camera list, which holds the camera of the layers that draw 2D layouts (character and vehicle
// select; near 1, far 10000, 45 degrees; the 3D world's camera is the second store's, near 0.1). nn::ui2d takes that camera's projection and puts its 1080-high canvas at the
// distance that fills the view's height (540 / tan), so a Vert+ camera only moves the canvas nearer: it filled the picture's height and spilled past its sides, and its matrix, no
// longer 16:9, was left by the 2D gates below. In a narrower picture that camera keeps 16:9 and its layouts go to the middle 16:9 through the 2D gates like every other. In a wider
// one Hor+ keeps the distance, so the canvas stays whole in the middle of the wider view, and it is converted as before.
//
// 2D layer: nn::ui2d draws in a 45 degree perspective projection on a 1920x1080 canvas (rows 1.358 0 0 0 / 0 2.414 0 0 / ...), DrawInfo's matrix at +0x10. A 16:9 one (x and y
// unskewed) is kept in the middle 16:9 of the picture by scaling its x row (wider picture) or y row (narrower). The DrawInfo's own matrix is scaled, once (a scaled one is no
// longer 16:9): other readers than the gated ones place the menus' and the race HUD's text by it (with a scaled copy only, that text stayed at the 16:9 frame's edges).
//   kind 1  main+0x17f3c1c (str s0,[x25,#0xdc], just before `bl main+0x17ed900`, DrawInfo's projection getter): the DrawInfo (x0) is scaled
//   kind 2  main+0x1805f18 (stp x8,x8,[x22,#0xd0], before the same bl, the text setup): the same
//   kind 3  main+0x17f5088 (fmul v7.4s,v1.4s,v0.s[0], after `ldp q0,q4,[x21,#0x10]`): q0 (x row) or q4 (y row) is scaled
// A pane that covers the whole canvas (fades, blurred backdrops: size x global matrix >= 98% of 1920x1080) keeps the whole picture: kinds 1 and 2 point x0 at a thread-local
// DrawInfo stand-in holding the unscaled matrix.
//
// Credit: the sites and the idea are adriadam10's mkw-recomp (MIT; recomp/patches-mkw-180.txt, runtime/core/boot/patches.c host_aspect / host_ui_proj / host_ui_rows2), re-expressed
// as gates the rewriter places (patches.py). See android/NOTICE.md.
// Not done: the HUD of a split screen is not moved to its half of the picture; a movie keeps its pane's shape only by the pane rule above (no movie detection).
#include "wide_shape.h"

#define MKW_MAIN_BUILD "d00e4f399e411d0613aa8d26b1c02fef"
#define K169 WS_K169
#define LAYOUT_STORE_REG 8   // the register of the main+0x8a6d8 store (patches.py): the layout layers' camera list
enum { P_FOVY = 0xa0, P_SIN = 0xa4, P_COS = 0xa8, P_TAN = 0xac, P_ASPECT = 0xb0 };

static ws_cfg_t g_cfg = WS_CFG_INIT;
static ws_seen_t g_seen;
static _Atomic unsigned g_nview;
static _Atomic int g_ui_logged[4];
static _Thread_local _Alignas(16) unsigned char t_di[0x60];   // a DrawInfo stand-in: the getter reads +0x10..+0x4f only

static double aspect(void) { return ws_aspect(&g_cfg, MKW_MAIN_BUILD, "mkw"); }

static void cam(uint64_t *r, int reg) {
    double A = aspect();
    if (!A || reg < 0 || reg > 28) return;
    float *s0 = (float *)(void *)&r[32];
    float in = *s0;
    if (!(in > 0.1f && in < 10.0f)) return;
    float full = in < 1.2f ? 2 * in : in;
    if (!ws_close_to(full, K169, 0.008)) return;
    if (reg == LAYOUT_STORE_REG && A < K169) return;   // a layout layer's camera keeps 16:9 in a narrower picture (see the top)
    float out = (float)(in * (A / K169));
    unsigned n = atomic_load_explicit(&g_nview, memory_order_relaxed);
    if (n < 16 && atomic_fetch_add_explicit(&g_nview, 1, memory_order_relaxed) < 16)
        LOG("mkw wide: camera view aspect %.4f -> %.4f (%s)", in, out, A > K169 ? "Hor+" : "Vert+");
    *s0 = out;
    uint64_t proj = r[reg];
    if (A < K169 && proj && !(proj & 3)) {
        float tn = ws_rdf(proj + P_TAN);
        if (tn > 0.01f && tn < 20.0f && !ws_seen_has(g_seen, proj, tn)) {
            double half0 = atan(tn), half = ws_vertplus_half(tn, A);
            float ntan = (float)tan(half);
            ws_wrf(proj + P_FOVY, (float)(ws_rdf(proj + P_FOVY) * (half / half0)));
            ws_wrf(proj + P_SIN, (float)sin(half));
            ws_wrf(proj + P_COS, (float)cos(half));
            ws_wrf(proj + P_TAN, ntan);
            ws_seen_set(g_seen, proj, ntan);
        }
    }
}

// x and y unskewed, aspect m[5] / m[0] within 0.008 of 16:9 (row-major, x row first)
static int is_16x9(const float *m) {
    if (m[1] || m[2] || m[4] || m[6] || m[8] || m[9] || m[12] || m[13]) return 0;
    float a = m[0] ? m[5] / m[0] : 0, d = (a < 0 ? -a : a) - (float)K169;
    return d < 0.008f && d > -0.008f;
}

static void ui_log(int kind, const char *what) {
    if (!atomic_exchange_explicit(&g_ui_logged[kind], 1, memory_order_relaxed)) LOG("mkw wide: 2D layer (%s): %s row scaled into the middle 16:9", what, aspect() > K169 ? "x" : "y");
}

// A pane over the whole 1920x1080 canvas: mtx = its global matrix, size = its size, mtx - 0x20 when they belong together
static int full_screen_pane(uint64_t mtx, uint64_t size) {
    if (size != mtx - 0x20 || (mtx & 3) || mtx < 0x70) return 0;
    const float *g = (const float *)(uintptr_t)mtx, *sz = (const float *)(uintptr_t)size;
    return fabsf(g[0] * sz[0]) > 1920 * 0.98f && fabsf(g[5] * sz[1]) > 1080 * 0.98f;
}

// A 16:9 projection squeezed here before: x and y unskewed, aspect m[5] / m[0] the picture's. A wider picture's layout camera (Hor+, distance kept) gives the same matrix.
static int is_squeezed(const float *m, double A) {
    if (m[1] || m[2] || m[4] || m[6] || m[8] || m[9] || m[12] || m[13]) return 0;
    float a = m[0] ? m[5] / m[0] : 0;
    return ws_close_to(a < 0 ? -a : a, A, 0.004);
}

// Squeezes the DrawInfo's own matrix (once: a squeezed one is no longer 16:9), so every reader of it sees the middle 16:9, those without a gate as well (the text of menus and
// of the race HUD). Returns 1 when m (a copy) holds the squeezed matrix, 0 for another projection.
static int squeeze_di(uint64_t di, float *m, double A) {
    memcpy(m, (void *)(di + 0x10), 16 * sizeof *m);
    if (!is_16x9(m)) return is_squeezed(m, A);
    float q = ws_squeeze(A);
    for (int k = 0; k < 4; k++) m[(A > K169 ? 0 : 4) + k] *= q;
    memcpy((void *)(di + 0x10), m, 16 * sizeof *m);
    return 1;
}

static void ui_getter(uint64_t *r, int text) {
    double A = aspect();
    uint64_t di = r[0];
    if (!A || !di || (di & 3)) return;
    float m[16];
    if (!squeeze_di(di, m, A)) return;
    ui_log(text ? 2 : 1, text ? "text setup" : "draw setup");
    if (!(text ? full_screen_pane(r[21] + 0x70, r[21] + 0x50) : full_screen_pane(r[23], r[22]))) return;
    float q = ws_squeeze(A);   // a pane over the whole canvas: the getter copies the unsqueezed matrix
    for (int k = 0; k < 4; k++) m[(A > K169 ? 0 : 4) + k] /= q;
    memcpy(t_di + 0x10, m, sizeof m);
    r[0] = (uint64_t)(uintptr_t)t_di;
}

static void ui_rows(uint64_t *r) {
    double A = aspect();
    uint64_t di = r[21];
    if (!A || !di || (di & 3)) return;
    float m[16];
    memcpy(m, (void *)(di + 0x10), sizeof m);
    if (!is_16x9(m)) return;   // already squeezed: q0 and q4 were loaded from the squeezed matrix
    float *row = (float *)(void *)&r[32 + (A > K169 ? 0 : 8)];   // q0 = x row, q4 = y row
    float q = ws_squeeze(A);
    for (int k = 0; k < 4; k++) row[k] *= q;
    squeeze_di(di, m, A);
    ui_log(3, "projection rows");
}

static void mkw_wide_gate(uint64_t *r, int param) {
    if (param < 0) return;
    switch (param >> 5) {
    case 0: cam(r, param & 31); break;
    case 1: ui_getter(r, 0); break;
    case 2: ui_getter(r, 1); break;
    case 3: ui_rows(r); break;
    }
}

__attribute__((constructor)) static void mkw_wide_attach(void) { gate_obs_set(OBS_MKW_WIDE, mkw_wide_gate); }
