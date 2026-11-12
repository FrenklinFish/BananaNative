// Render configurations the host rewrites (profile keys render_cfg and render_quality, hle_render.c): the row type and the size arithmetic, pure functions so that the unit test
// (tests/t_render_cfg.c) checks every output, ratio and cap without a game
#pragma once
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RCFG_MAX 8   // configurations per row (game.py checks the count)
typedef struct {
    const char *build;                 // main build ID (32 hex)
    uint64_t table;                    // main+table: the slot table, one pointer to a configuration per slot
    unsigned n, stride;                // slots, bytes from one slot to the next
    unsigned out_off, ren_off;         // the output and render size fields in a configuration (u32 width, u32 height each)
    unsigned sz[RCFG_MAX][4];          // the game's own sizes per configuration: output w, h, render w, h (checked before any write)
    unsigned orig_w, orig_h;           // the output DM_WINDOW_SIZE=original gives
    unsigned max_w, max_h;             // the largest render size written (a larger one is scaled down to fit, the output's aspect kept)
    unsigned min_h;                    // the smallest output height the launcher offers
} rcfg_row_t;

// Render scale in percent: 33 (exactly 1/3), 50, 59, 67 (exactly 2/3), 100 (the output: a ratio of 1, the upscaler only anti-aliases); 0 when another value
static inline int rcfg_scale_ok(int pct) { return pct == 33 || pct == 50 || pct == 59 || pct == 67 || pct == 100; }

// x * num / den rounded to the nearest even number (halves up), at least 2
static inline unsigned rcfg_even(unsigned x, unsigned num, unsigned den) {
    uint64_t v = (uint64_t)x * num, q = (v + den) / (2ull * den) * 2;
    return q < 2 ? 2 : (unsigned)q;
}
// x * num / den rounded down to an even number, at least 2
static inline unsigned rcfg_even_down(unsigned x, unsigned num, unsigned den) {
    uint64_t q = (uint64_t)x * num / (2ull * den) * 2;
    return q < 2 ? 2 : (unsigned)q;
}

// The render size for an output and a scale: each side rounded to even, except 100 %, which is the output as it is, and 33 %, which is the output / 3 rounded down (the heights of 720, 1080, 1152 and 1440 divide by 3,
// so the ratio is exactly 3 and the game's DLSS call picks its 3x method; a width may be odd, as the game's own 853x480). Then capped at max_w x max_h (0: no cap): a larger size
// is the output scaled by the largest factor that fits, rounded down to even, so the output's aspect is kept. Returns 1 when the cap changed it
static inline int rcfg_render(unsigned ow, unsigned oh, int pct, unsigned max_w, unsigned max_h, unsigned *rw, unsigned *rh) {
    unsigned num = pct == 67 ? 2 : pct == 50 ? 1 : (unsigned)pct, den = pct == 67 ? 3 : pct == 50 ? 2 : 100;
    if (pct == 100) { *rw = ow; *rh = oh; }
    else if (pct == 33) { *rw = ow / 3; *rh = oh / 3; }
    else { *rw = rcfg_even(ow, num, den); *rh = rcfg_even(oh, num, den); }
    if (!max_w || !max_h || (*rw <= max_w && *rh <= max_h)) return 0;
    if ((uint64_t)max_w * oh <= (uint64_t)max_h * ow) { *rw = rcfg_even_down(ow, max_w, ow); *rh = rcfg_even_down(oh, max_w, ow); }   // the width limits
    else { *rw = rcfg_even_down(ow, max_h, oh); *rh = rcfg_even_down(oh, max_h, oh); }
    return 1;
}

// The display's largest buffer the host answers (vi::QueryDisplayBufferMaxResolution: 0 1280x720, 1 1920x1080, 2 2560x1440, 3 3840x2160): the smallest level that holds the output
// (the game cuts an output larger than that level, it does not scale it), and at least the quality's minimum (the game's selector also reads the level)
static inline int rcfg_max_res(unsigned ow, unsigned oh, int quality_min) {
    static const unsigned lw[4] = {1280, 1920, 2560, 3840}, lh[4] = {720, 1080, 1440, 2160};
    int hold = 3;
    for (int i = 3; i >= 0; i--) if (ow <= lw[i] && oh <= lh[i]) hold = i;
    return hold > quality_min ? hold : quality_min;
}

// An output height as a size of the row's original aspect (720 → 1280x720 for a 16:9 original); width rounded to even
static inline unsigned rcfg_width_of(const rcfg_row_t *r, unsigned h) { return rcfg_even(h, r->orig_w, r->orig_h); }

// The setting a run starts with, from DM_WINDOW_SIZE (win; NULL or empty: def, the profile's android_window) and DM_RENDER_PCT (pct; NULL or empty: 50), read the same way by the
// loader (the render target pool it picks, loader.c rt_pool_apply) and by hle_render.c (the sizes it writes, and the [RED] lines for what was wrong). Returns 0, or the bits: 1 the
// size is not WxH or original (the row's original instead), 2 below the row's smallest output height (that height, at the value's aspect), 4 the percent is not one of
// rcfg_scale_ok's (50 instead)
static inline int rcfg_setting(const rcfg_row_t *r, const char *win, const char *def, const char *pct, unsigned *ow, unsigned *oh, int *p) {
    int bad = 0; unsigned a = 0, b = 0; char x;
    if (!win || !*win) win = def;
    if (!strcmp(win, "original")) { a = r->orig_w; b = r->orig_h; }
    else if (sscanf(win, "%ux%u%c", &a, &b, &x) != 2 || !a || !b || a > 7680 || b > 4320) { bad |= 1; a = r->orig_w; b = r->orig_h; }
    else if (b < r->min_h) { bad |= 2; a = rcfg_even(r->min_h, a, b); b = r->min_h; }
    *ow = a; *oh = b;
    *p = pct && *pct ? atoi(pct) : 50;
    if (!rcfg_scale_ok(*p)) { bad |= 4; *p = 50; }
    return bad;
}
