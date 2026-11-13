// SPDX-License-Identifier: GPL-3.0-or-later
// Shared parts of the wide-picture callbacks (oot_wide.c, dkb_wide.c, mkw_wide.c): the picture's shape, a table of the projections already converted, and the 2D layer's squeeze.
//
// The game draws a 16:9 frame whatever the screen; the present fits that frame into DM_VK_PICTURE_ASPECT=<w>:<h> (vka_present.c). A game's callback makes its 3D cameras use that
// aspect (wider: Hor+, the vertical field of view stays; narrower: Vert+, the tangent of the half angle grows by (16/9) / aspect, so the horizontal reach stays) and keeps its 2D layer
// in the middle 16:9 by scaling a copy of the UI projection's x row (wider) or y row (narrower), never the game's own matrix. Header only (static functions): each game's file keeps its own
// state, so the games share no variable. A callback does nothing on an unset, invalid or 16:9 (within 0.02) shape, and on a main NSO whose build ID is not the one it was verified on.
#pragma once
#include "hle.h"
#include "dm.h"
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WS_K169 (16.0 / 9.0)
#define WS_NSEEN 256

static inline int ws_close_to(double a, double b, double e) { return fabs(a - b) < e; }
static inline float ws_rdf(uint64_t a) { float f; memcpy(&f, (void *)a, 4); return f; }
static inline void ws_wrf(uint64_t a, float f) { memcpy((void *)a, &f, 4); }
static inline uint32_t ws_bits(float f) { uint32_t u; memcpy(&u, &f, 4); return u; }

// DM_VK_PICTURE_ASPECT=<w>:<h> -> w / h, or 0 (unset, invalid, outside 0.4..4, or within 0.02 of 16:9)
static inline double ws_parse_aspect(const char *e) {
    int w = 0, h = 0;
    char tail;
    if (!e || sscanf(e, "%d:%d%c", &w, &h, &tail) != 2 || w <= 0 || h <= 0) return 0;
    double a = (double)w / h;
    return a > 0.4 && a < 4.0 && !ws_close_to(a, WS_K169, 0.02) ? a : 0;
}

// The picture's shape, read once per game (restart to change it, as the launcher does)
typedef struct { pthread_mutex_t lock; _Atomic int ready; double aspect; } ws_cfg_t;
#define WS_CFG_INIT { PTHREAD_MUTEX_INITIALIZER, 0, 0 }

// The shape for a main NSO of build ID `build` (0 on any other build or when nothing is to change); `tag` names the game in the one log line.
static inline double ws_aspect(ws_cfg_t *c, const char *build, const char *tag) {
    if (strcmp(dm_main_build(), build)) return 0;
    if (!atomic_load_explicit(&c->ready, memory_order_acquire)) {
        pthread_mutex_lock(&c->lock);
        if (!atomic_load_explicit(&c->ready, memory_order_relaxed)) {
            c->aspect = ws_parse_aspect(getenv("DM_VK_PICTURE_ASPECT"));
            if (c->aspect) LOG("%s wide: cameras follow the %.4f picture (%s; restart to change)", tag, c->aspect, c->aspect > WS_K169 ? "Hor+" : "Vert+");
            atomic_store_explicit(&c->ready, 1, memory_order_release);
        }
        pthread_mutex_unlock(&c->lock);
    }
    return c->aspect;
}

static inline uint8_t *ws_main_base(void) { for (int i = 0; i < g_nmod; i++) if (!strcmp(g_mod[i].name, "main")) return g_mod[i].base; return NULL; }

// Projections already converted (where, and the tangent written): a callback fires many times a frame on one projection and must not scale twice
typedef struct { _Atomic uint64_t proj; _Atomic uint32_t tan; } ws_seen_t[WS_NSEEN];
static inline unsigned ws_slot(uint64_t p) { return (unsigned)((p >> 4) ^ (p >> 12)) % WS_NSEEN; }
static inline int ws_seen_has(ws_seen_t t, uint64_t proj, float tan_written) {
    unsigned s = ws_slot(proj);
    return atomic_load_explicit(&t[s].proj, memory_order_relaxed) == proj && atomic_load_explicit(&t[s].tan, memory_order_relaxed) == ws_bits(tan_written);
}
static inline void ws_seen_set(ws_seen_t t, uint64_t proj, float tan_written) {
    unsigned s = ws_slot(proj);
    atomic_store_explicit(&t[s].tan, ws_bits(tan_written), memory_order_relaxed);
    atomic_store_explicit(&t[s].proj, proj, memory_order_relaxed);
}

// Vert+: the half angle that keeps the horizontal reach of a 16:9 picture for a narrower aspect `a` (tangent `tn` of the authored half angle); held under 172 degrees
static inline double ws_vertplus_half(float tn, double a) {
    double half = atan(tn * WS_K169 / a);
    return half > 1.50 ? 1.50 : half;
}

// The 2D layer's squeeze: the factor on the x row (wider picture) or y row (narrower) that keeps a 16:9 layer in the middle 16:9 of the picture
static inline float ws_squeeze(double a) { return (float)(a > WS_K169 ? WS_K169 / a : a / WS_K169); }
// A copy of two projection rows (8 floats, the x row first) with the row the shape calls for scaled; the game's own matrix is never written
static inline void ws_squeeze_rows(float out[8], const float *rows, double a) {
    memcpy(out, rows, 8 * sizeof(float));
    float q = ws_squeeze(a);
    for (int k = 0; k < 4; k++) out[(a > WS_K169 ? 0 : 4) + k] *= q;
}
// The margin (fraction of the extent, each side) a 16:9 layer leaves in a picture of shape `a`
static inline double ws_margin(double a) { return (1.0 - (a > WS_K169 ? WS_K169 / a : a / WS_K169)) / 2; }
