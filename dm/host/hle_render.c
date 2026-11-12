// Render configurations rewritten by the host (profile keys render_cfg and render_quality; dm/games/README.md): a game that keeps a table of render configurations, each with an
// output size and a render size its upscaler takes to the output, and picks one by the performance mode, the graphics scale and the display's largest buffer (Fire Emblem Fortune's
// Weave: six, main+0x223e0b0). The host writes the player's output and render size into every configuration, so whichever the game picks has them, and answers the performance
// mode and the largest buffer from the player's graphics quality; the operation mode and the graphics scale are left alone.
//   DM_WINDOW_SIZE=<w>x<h> | original   the output (unset: the profile's android_window; original: the row's original=)
//   DM_RENDER_PCT=33|50|59|67|100        the render size in percent of the output (unset: 50); 33 is exactly 1/3, 67 exactly 2/3, 100 the output, see render_cfg.h
//   DM_GFX_QUALITY=quality|performance   the render_quality row's performance mode and smallest largest-buffer level for each (unset: performance)
//   DM_MAX_RES=0..3                      a fixed largest-buffer answer (diagnostic); DM_RENDER_UNCAPPED=1 lifts the row's max_render cap (diagnostic)
// The cap is the max_render of the rt_pool row's size when the loader wrote a larger render target pool this run (loader.c rt_pool_apply, dm_rt_pool_max_render: it picks the
// pool from the setting the run starts with), and the render_cfg row's otherwise, so a larger render size never runs without the larger pool; a change while the game runs
// that needs a larger pool is clamped to this run's cap until the next start (pend_set)
// The sizes are written in GetPerformanceMode only: the game calls it on its update thread right before it reads the table, at start and on each notification 31, so a change
// while the game runs (DM_OE_SCRIPT outh= / rscale= / quality=, hle_oe.c) is kept pending, then written there after the host pushes 31. Before a write the row's build must be
// the running main's, each configuration must lie in the guest's opened heap or in its alias region with every page mapped (the game's StandardAllocator takes its space
// there: Fire Emblem Fortune's Weave's configurations), and hold the game's own sizes or the host's last write; otherwise one [RED] line and nothing is written. A configuration
// is read and written with the host's fault-safe copies (dm_guest_read_mapped, plat_mem_write), never in place
#include "hle.h"
#include "render_cfg.h"
#include <stdatomic.h>

int dm_guest_heap_readable(uint64_t a, size_t n);   // hle_svc.c
void dm_oe_perfmode_notify(void);                   // hle_oe.c: notification 31 when the game turned it on
int dm_rt_pool_max_render(unsigned *w, unsigned *h); // loader.c: the max_render of the rt_pool row's size written this run, else 0
int dm_rt_pool_top(unsigned *w, unsigned *h);        // loader.c: the max_render of the rt_pool row's largest size, 0 without a row

#ifndef DM_RENDER_CFG_ROWS   // the unit test (tests/t_render_cfg.c) brings its own rows
#define DM_RENDER_CFG_ROWS DM_GAME_RENDER_CFG
#define DM_RENDER_NCFG_ROWS DM_GAME_NRENDER_CFG
#define DM_RENDER_QUALITY DM_GAME_RENDER_QUALITY
#define DM_RENDER_DEFAULT_OUT DM_GAME_RENDER_DEFAULT_OUT
#endif
static const rcfg_row_t k_rcfg[] = DM_RENDER_CFG_ROWS;
static const int k_rq[4] = DM_RENDER_QUALITY;   // Quality: performance mode, smallest level; Performance: the same

typedef struct { unsigned ow, oh; int pct, quality; } rset_t;   // a setting: output, render scale, quality (1) or performance (0)
typedef struct { unsigned rw, rh; int clamped, max_res, perf; } rout_t;   // what it gives
static pthread_mutex_t g_rl = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t g_rfind = PTHREAD_ONCE_INIT;
static const rcfg_row_t *g_row; static uint8_t *g_rbase;
static rset_t g_pend, g_logged; static int g_have_logged;
static _Atomic int g_max_res = -1;   // the largest-buffer answer: raised as soon as a live change asks for more, lowered only when the change is written
static int g_dbg_max = -1, g_uncapped, g_cap_pool;
static unsigned g_cap_w, g_cap_h;   // the max_render in effect: the render_cfg row's, or the rt_pool row's (g_cap_pool)
static unsigned g_last[4]; static int g_wrote;
static unsigned g_red;   // [RED] reasons already logged (one line each)
static int g_where_nh = -1;   // configurations in the heap at the last region line (logged when it changes)
static unsigned g_next_start;   // changes while the game runs clamped until the next start (pend_set's line)

static rout_t rset_eval(const rset_t *s) {
    rout_t o = {0};
    o.clamped = rcfg_render(s->ow, s->oh, s->pct, g_uncapped ? 0 : g_cap_w, g_uncapped ? 0 : g_cap_h, &o.rw, &o.rh);
    o.max_res = g_dbg_max >= 0 ? g_dbg_max : rcfg_max_res(s->ow, s->oh, k_rq[s->quality ? 1 : 3]);
    o.perf = k_rq[s->quality ? 0 : 2];
    return o;
}
static void red_once(unsigned bit, const char *what) { if (!(g_red & bit)) { g_red |= bit; LOG("[RED] [res] %s: the game's own sizes stay", what); } }

static void rfind(void) {
    for (int i = 0; i < g_nmod; i++) if (!strcmp(g_mod[i].name, "main"))
        for (int k = 0; k < DM_RENDER_NCFG_ROWS; k++) if (k_rcfg[k].build[0] && !strcmp(g_mod[i].build_id, k_rcfg[k].build)) { g_row = &k_rcfg[k]; g_rbase = g_mod[i].base; }
    if (!g_row) {
        if (DM_RENDER_NCFG_ROWS) LOG("[RED] [res] the profile has no render_cfg row for main build %s: the game's own render configurations", dm_main_build());
        return;
    }
    // the setting as the loader read it for the render target pool (render_cfg.h rcfg_setting), and what was wrong in it
    const char *w = getenv("DM_WINDOW_SIZE"), *e = getenv("DM_RENDER_PCT"), *src = w && *w ? "DM_WINDOW_SIZE" : "the profile's android_window";
    int bad = rcfg_setting(g_row, w, DM_RENDER_DEFAULT_OUT, e, &g_pend.ow, &g_pend.oh, &g_pend.pct);
    if (!w || !*w) w = DM_RENDER_DEFAULT_OUT;
    if (bad & 1) LOG("[RED] [res] %s=%s: must be <width>x<height> or original; the output is the original %ux%u", src, w, g_row->orig_w, g_row->orig_h);
    if (bad & 2) LOG("[RED] [res] %s=%s: below the smallest output height %u; the output is %ux%u", src, w, g_row->min_h, g_pend.ow, g_pend.oh);
    if (bad & 4) LOG("[RED] [res] DM_RENDER_PCT=%s: must be 33, 50, 59, 67 or 100; 50", e);
    e = getenv("DM_GFX_QUALITY");
    g_pend.quality = e && !strcmp(e, "quality");
    if (e && *e && strcmp(e, "quality") && strcmp(e, "performance")) LOG("[RED] [res] DM_GFX_QUALITY=%s: must be quality or performance; performance", e);
    e = getenv("DM_MAX_RES");
    if (e && *e) { if (*e >= '0' && *e <= '3' && !e[1]) g_dbg_max = *e - '0'; else LOG("[RED] [res] DM_MAX_RES=%s: must be 0 to 3; the setting's own", e); }
    e = getenv("DM_RENDER_UNCAPPED"); g_uncapped = e && atoi(e) > 0;
    g_cap_w = g_row->max_w; g_cap_h = g_row->max_h; g_cap_pool = dm_rt_pool_max_render(&g_cap_w, &g_cap_h);
    rout_t o = rset_eval(&g_pend);
    atomic_store(&g_max_res, o.max_res);
    LOG("[res] render configurations: main+%#llx, %u of them; settings output %ux%u, render scale %d %%, %s; max_render %ux%u (%s)%s%s", (unsigned long long)g_row->table,
        g_row->n, g_pend.ow, g_pend.oh, g_pend.pct, g_pend.quality ? "quality" : "performance", g_cap_w, g_cap_h, g_cap_pool ? "rt_pool's: a larger pool is written" : "render_cfg's",
        g_dbg_max >= 0 ? ", DM_MAX_RES" : "", g_uncapped ? ", uncapped (DM_RENDER_UNCAPPED)" : "");
}
int dm_render_active(void) { pthread_once(&g_rfind, rfind); return g_row != NULL; }

// The guest's alias region as hle_svc.c partitions the block (mem_init_once: alias, then the profile's stack region, then the heap; hle_cheats.c region 2 the same): [lo, hi).
// With a larger DM_ALIAS_SIZE the region starts lower, so this range is its upper part: an address it gives is always inside the region
static void alias_range(uint64_t *lo, uint64_t *hi) {
    uint64_t hb; size_t hopen; dm_guest_heap_range(&hb, &hopen);
    *hi = hb > (uint64_t)DM_GAME_MEM_STACK + (uint64_t)DM_GAME_MEM_ALIAS ? hb - (uint64_t)DM_GAME_MEM_STACK : 0;
    *lo = *hi ? *hi - (uint64_t)DM_GAME_MEM_ALIAS : 0;
}
// Where [p, p + n) lies: 1 the opened heap, 2 the alias region with every page mapped by the guest, 0 neither
static int cfg_where(uint64_t p, size_t n) {
    if (dm_guest_heap_readable(p, n)) return 1;
    uint64_t lo, hi; alias_range(&lo, &hi);
    return lo && p >= lo && n <= hi - lo && p - lo <= hi - lo - n && !dm_guest_unmapped((uintptr_t)p, n) ? 2 : 0;
}

// Every configuration checked first, then all written: 0 when none was written
static int rcfg_write(unsigned ow, unsigned oh, unsigned rw, unsigned rh) {
    const rcfg_row_t *r = g_row; uint64_t cfg[RCFG_MAX]; int where[RCFG_MAX];
    unsigned span = (r->out_off > r->ren_off ? r->out_off : r->ren_off) + 8;
    for (unsigned i = 0; i < r->n; i++) {
        uint64_t p = 0;
        if (plat_mem_read((uint64_t)(uintptr_t)(g_rbase + r->table + (uint64_t)r->stride * i), &p, sizeof p)) { red_once(8, "the slot table cannot be read"); return 0; }
        if (!p) { red_once(1, "a configuration slot is empty (the game has not built its table yet)"); return 0; }
        if (!(where[i] = cfg_where(p, span))) {
            if (!(g_red & 2)) {
                g_red |= 2; uint64_t hb, lo, hi; size_t hopen; dm_guest_heap_range(&hb, &hopen); alias_range(&lo, &hi);
                LOG("[RED] [res] configuration %u at %#llx+%#x is neither inside the opened guest heap %#llx+%#llx nor mapped inside the guest alias region %#llx-%#llx: the game's own sizes stay",
                    i, (unsigned long long)p, span, (unsigned long long)hb, (unsigned long long)hopen, (unsigned long long)lo, (unsigned long long)hi);
            }
            return 0;
        }
        cfg[i] = p;
        uint32_t cur[4];
        if (dm_guest_read_mapped(cur, (uintptr_t)(p + r->out_off), 8) || dm_guest_read_mapped(cur + 2, (uintptr_t)(p + r->ren_off), 8)) {
            if (!(g_red & 16)) { g_red |= 16; LOG("[RED] [res] configuration %u at %#llx was unmapped while it was read: the game's own sizes stay", i, (unsigned long long)p); }
            return 0;
        }
        int own = cur[0] == r->sz[i][0] && cur[1] == r->sz[i][1] && cur[2] == r->sz[i][2] && cur[3] == r->sz[i][3];
        int mine = g_wrote && !memcmp(cur, g_last, sizeof cur);
        if (!own && !mine) {
            if (!(g_red & 4)) { g_red |= 4; LOG("[RED] [res] configuration %u holds %ux%u / %ux%u, neither the game's own %ux%u / %ux%u nor the host's last write: the game's own sizes stay",
                                                i, cur[0], cur[1], cur[2], cur[3], r->sz[i][0], r->sz[i][1], r->sz[i][2], r->sz[i][3]); }
            return 0;
        }
    }
    int nh = 0; for (unsigned i = 0; i < r->n; i++) nh += where[i] == 1;
    if (nh != g_where_nh) {
        uint64_t lo, hi; alias_range(&lo, &hi); g_where_nh = nh;
        LOG("[res] configurations: %u in the guest heap, %u in the alias region %#llx-%#llx (configuration 0 at %#llx)", nh, r->n - nh, (unsigned long long)lo, (unsigned long long)hi,
            (unsigned long long)cfg[0]);
    }
    uint32_t o[2] = {ow, oh}, n[2] = {rw, rh};
    for (unsigned i = 0; i < r->n; i++) {
        uint8_t *c = (uint8_t *)(uintptr_t)cfg[i];
        dm_guest_write_prepare(c + r->out_off, 8); dm_guest_write_prepare(c + r->ren_off, 8);
        if (plat_mem_write(cfg[i] + r->out_off, o, 8) || plat_mem_write(cfg[i] + r->ren_off, n, 8)) {
            // unmapped since the check: the configurations before it hold the new sizes, so neither the game's own nor a last write matches until the game builds them again
            LOG("[RED] [res] configuration %u at %#llx could not be written (unmapped since the check): %u of %u configurations hold the new sizes", i, (unsigned long long)cfg[i], i, r->n);
            g_wrote = 0; return 0;
        }
    }
    g_last[0] = ow; g_last[1] = oh; g_last[2] = rw; g_last[3] = rh; g_wrote = 1;
    return 1;
}

// oe::GetPerformanceMode (hle_oe.c) for a game with a render_cfg row: the pending setting written into the configurations, its performance mode returned; -1 without a row
int dm_render_perf_mode(void) {
    if (!dm_render_active()) return -1;
    pthread_mutex_lock(&g_rl);
    rset_t s = g_pend; rout_t o = rset_eval(&s);
    int ok = rcfg_write(s.ow, s.oh, o.rw, o.rh);
    atomic_store(&g_max_res, o.max_res);   // lowered (or kept) now that the game reads the configurations again
    if (ok && (!g_have_logged || memcmp(&s, &g_logged, sizeof s))) {
        g_logged = s; g_have_logged = 1;
        LOG("[res] output %ux%u, render %ux%u (%d %%), graphics quality %s (performance mode %s), max-res %d, clamped %s: %u configurations written", s.ow, s.oh, o.rw, o.rh, s.pct,
            s.quality ? "quality" : "performance", o.perf ? "Boost" : "Normal", o.max_res, o.clamped ? "y" : "n", g_row->n);
    }
    pthread_mutex_unlock(&g_rl);
    return o.perf;
}
// vi::QueryDisplayBufferMaxResolution (hle_vi.c): -1 without a row
int dm_render_max_res(void) { return dm_render_active() ? atomic_load(&g_max_res) : -1; }

// A change while the game runs (the launcher's in-game panel: nxhost_render_set; DM_OE_SCRIPT: dm_render_script), with g_rl held: kept pending and written at the next
// GetPerformanceMode, which notification 31 asks for; a larger largest-buffer level is answered at once. A render size above this run's cap (the render target pool the
// loader picked at start for the starting setting) is clamped to it; when the largest pool of the profile's rt_pool row holds it, the next start picks a pool that does
// (loader.c rt_pool_apply), and one line says so
static void pend_set(rset_t s, const char *src) {
    g_pend = s;
    rout_t o = rset_eval(&s);
    int was = atomic_load(&g_max_res);
    if (o.max_res > was) atomic_store(&g_max_res, o.max_res);
    LOG("[res] pending: output %ux%u, render scale %d %%, %s; max-res %d%s; from %s", s.ow, s.oh, s.pct, s.quality ? "quality" : "performance", o.max_res,
        o.max_res > was ? " (raised now)" : o.max_res < was ? " (lowered when written)" : "", src);
    unsigned uw, uh, tw, th;
    if (o.clamped) {
        rcfg_render(s.ow, s.oh, s.pct, 0, 0, &uw, &uh);
        if (dm_rt_pool_top(&tw, &th) && uw <= tw && uh <= th) {
            g_next_start++;
            LOG("[res] render %ux%u is above this run's max_render %ux%u (the render-target pool it started with): %ux%u until then; applies in full at the next start", uw, uh,
                g_cap_w, g_cap_h, o.rw, o.rh);
        }
    }
}

// The launcher's in-game panel (Native.renderSet): the output height (0 original), the render scale and the graphics quality (1 quality, 0 performance) the player picked; -1
// keeps one as it is. Values the row does not take are ignored (one [RED] line). Ignored without a render_cfg row
__attribute__((visibility("default"))) void nxhost_render_set(int outh, int pct, int quality) {
    if (!dm_render_active()) { LOG("[RED] [res] the in-game panel's render setting: the profile has no render_cfg row for this main build; ignored"); return; }
    pthread_mutex_lock(&g_rl);
    rset_t s = g_pend;
    if (outh == 0) { s.ow = g_row->orig_w; s.oh = g_row->orig_h; }
    else if (outh >= (int)g_row->min_h && outh <= 4320) { s.oh = (unsigned)outh; s.ow = rcfg_width_of(g_row, s.oh); }
    else if (outh != -1) LOG("[RED] [res] in-game panel output height %d: must be 0 (original) or %u to 4320; unchanged", outh, g_row->min_h);
    if (rcfg_scale_ok(pct)) s.pct = pct;
    else if (pct != -1) LOG("[RED] [res] in-game panel render scale %d: must be 33, 50, 59, 67 or 100; unchanged", pct);
    if (quality == 0 || quality == 1) s.quality = quality;
    pend_set(s, "the in-game panel");
    pthread_mutex_unlock(&g_rl);
    dm_oe_perfmode_notify();
}

// DM_OE_SCRIPT keys (hle_oe.c oe_script): outh=<output height> (0: original), rscale=33|50|59|67|100, quality=1|0, applied as pend_set says. 0 when the key is not one of these
int dm_render_script(const char *key, int v) {
    if (strcmp(key, "outh") && strcmp(key, "rscale") && strcmp(key, "quality")) return 0;
    if (!dm_render_active()) { LOG("[RED] [res] DM_OE_SCRIPT %s=%d: the profile has no render_cfg row for this main build; ignored", key, v); return 1; }
    pthread_mutex_lock(&g_rl);
    rset_t s = g_pend;
    if (!strcmp(key, "outh")) {
        if (v == 0) { s.ow = g_row->orig_w; s.oh = g_row->orig_h; }
        else if (v >= (int)g_row->min_h && v <= 4320) { s.oh = (unsigned)v; s.ow = rcfg_width_of(g_row, s.oh); }
        else LOG("[RED] [res] DM_OE_SCRIPT outh=%d: must be 0 (original) or %u to 4320; unchanged", v, g_row->min_h);
    } else if (!strcmp(key, "rscale")) {
        if (rcfg_scale_ok(v)) s.pct = v; else LOG("[RED] [res] DM_OE_SCRIPT rscale=%d: must be 33, 50, 59, 67 or 100; unchanged", v);
    } else s.quality = v != 0;
    pend_set(s, "DM_OE_SCRIPT");
    pthread_mutex_unlock(&g_rl);
    dm_oe_perfmode_notify();
    return 1;
}
