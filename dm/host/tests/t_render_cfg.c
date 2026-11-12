// Render configurations the host rewrites (hle_render.c, render_cfg.h): the size arithmetic for every output of the list and every render scale, the rounding, the max_render cap,
// the largest-buffer rule, and the write path on a fake main, heap and alias region: the build check, the slot, heap and alias-region checks, the game's own sizes or the host's last
// write, the pending live change (raised largest buffer at once, lowered at the write). The render target pool (rt_pool.h): the movz encoding, DM_RT_POOL_MB, the device gate, the
// word check, the size the setting needs (1008 / 1536 / 2048 MiB), max_render following the write (a stand-in loader below decides and writes a fake word as loader.c
// rt_pool_apply does), and a change while the game runs that needs a larger pool than the run's (clamped until the next start). Expected values are written by hand.
// Usage: t_render_cfg main | wrong | pool | big (main, wrong: a device of 12 GB or less starting at 1080p 50 %, the pool not written; pool: more than 12 GB, 1536 MiB,
// max_render 1920x1080; big: 12 GB or less starting at 1440p 100 %, 2048 MiB, max_render 2560x1440)
#define DM_RENDER_CFG_ROWS {{"0123456789abcdef0123456789abcdef", 0x100, 6, 16, 0x0, 0x8, \
    {{2560, 1440, 1280, 720}, {2048, 1152, 1024, 576}, {1706, 960, 853, 480}, {1920, 1080, 960, 540}, {1536, 864, 768, 432}, {1280, 720, 640, 360}}, 2048, 1152, 1280, 720, 720}}
#define DM_RENDER_NCFG_ROWS 1
#define DM_RENDER_QUALITY {1, 2, 0, 1}
#define DM_RENDER_DEFAULT_OUT "1920x1080"
#ifndef HLE_RENDER_SOURCE   // a copy beside a stand-in hle.h runs the same code on a desktop without the host's platform layer
#define HLE_RENDER_SOURCE "../hle_render.c"
#endif
#include HLE_RENDER_SOURCE
#include "../rt_pool.h"

// The stand-in loader: one rt_pool row on a fake main word (FEFW's: 1008, 1536 = 1920x1080, 2048 = 2560x1440, out_h 1440), decided from the setting and written as loader.c
// rt_pool_apply does; hle_render.c asks dm_rt_pool_max_render and dm_rt_pool_top
static const rtpool_row_t k_rtp = {"0123456789abcdef0123456789abcdef", "main", 0x646f88, 0x52a7e009u, 1008, 2, {1536, 2048}, {1920, 2560}, {1080, 1440}, 1440};
static const rtpool_row_t k_rtp1 = {"0123456789abcdef0123456789abcdef", "main", 0x646f88, 0x52a7e009u, 1008, 1, {1536}, {1920}, {1080}, 0};   // one larger size, no out_h
static int rtp_raised, rtp_top; static unsigned rtp_w, rtp_h;
int dm_rt_pool_max_render(unsigned *w, unsigned *h) { if (!rtp_raised) return 0; *w = rtp_w; *h = rtp_h; return 1; }
int dm_rt_pool_top(unsigned *w, unsigned *h) { if (!rtp_top) return 0; *w = k_rtp.max_w[1]; *h = k_rtp.max_h[1]; return 1; }
// The size the setting win / pct needs (the test's render_cfg row: max_render 1280x720, min 720, original 2048x1152)
static int need_of(const rtpool_row_t *r, const char *win, const char *pct) {
    unsigned ow, oh, rw, rh; int p;
    rcfg_setting(&k_rcfg[0], win, "1920x1080", pct, &ow, &oh, &p);
    rcfg_render(ow, oh, p, 0, 0, &rw, &rh);
    return rtp_need(r, 1280, 720, oh, rw, rh);
}
static int rtp_load(uint32_t *word, int low_ram, const char *env, int need, unsigned *mb) {   // the write's result (1 written, 0 the game's own, -1 not the row's word)
    int why; *mb = rtp_choose(&k_rtp, low_ram, rtp_env_mb(env), need, &why);
    uint32_t was; int st = rtp_apply(word, &k_rtp, *mb, &was);
    rtp_raised = st >= 0 && rtp_cap(&k_rtp, st, *mb, &rtp_w, &rtp_h);
    rtp_top = st >= 0 && rtp_env_mb(env) < 0;
    return st;
}

static module_t modules[1];
module_t *g_mod = modules; int g_nmod = 1;
static uint8_t heap[0x1000] __attribute__((aligned(16))), mainimg[0x400] __attribute__((aligned(16)));
// The alias region: the host's partition puts it right below the stack region and the heap, so the fake heap base is this buffer + the profile's alias and stack sizes (only
// compared, never read); the guest has mapped this buffer's pages while alias_mapped, nothing else of the region
static uint8_t alias[0x1000] __attribute__((aligned(16))); static int alias_mapped = 1;
static int inside(const uint8_t *b, size_t bn, uint64_t a, size_t n) { uint64_t x = (uint64_t)(uintptr_t)b; return a >= x && n <= bn && a - x <= bn - n; }
int dm_guest_heap_readable(uint64_t a, size_t n) { return inside(heap, sizeof heap, a, n); }
void dm_guest_heap_range(uint64_t *base, size_t *open_len) {
    *base = (uint64_t)(uintptr_t)alias + (uint64_t)DM_GAME_MEM_ALIAS + (uint64_t)DM_GAME_MEM_STACK; *open_len = 0x100000;
}
uint64_t dm_guest_unmapped(uintptr_t a, size_t n) { return inside(alias, sizeof alias, a, n) && alias_mapped ? 0 : 1; }
uint64_t dm_guest_read_mapped(void *dst, uintptr_t a, size_t n) {
    if (inside(heap, sizeof heap, a, n) || (inside(alias, sizeof alias, a, n) && alias_mapped)) { memcpy(dst, (const void *)a, n); return 0; }
    memset(dst, 0, n); return 1;
}
static int prepared, notified;
void dm_guest_write_prepare(void *p, size_t n) { (void)p; (void)n; prepared++; }
void dm_oe_perfmode_notify(void) { notified++; }
static int checks, failed;
#define CHECK(c) do { checks++; if (!(c)) { failed++; fprintf(stderr, "failed line %d: %s\n", __LINE__, #c); } } while (0)

static const unsigned outs[4][2] = {{1280, 720}, {1920, 1080}, {2048, 1152}, {2560, 1440}};
static const int pcts[4] = {33, 50, 59, 67};
// render size per output and scale, capped at 1280x720 (and uncapped where the cap changes it), and whether the cap changed it
static const unsigned want[4][4][2] = {
    {{426, 240}, {640, 360}, {756, 424}, {854, 480}},
    {{640, 360}, {960, 540}, {1132, 638}, {1280, 720}},
    {{682, 384}, {1024, 576}, {1208, 680}, {1280, 720}},
    {{853, 480}, {1280, 720}, {1280, 720}, {1280, 720}}};
static const int clamped[4][4] = {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 1}, {0, 0, 1, 1}};
static const unsigned uncapped[4][4][2] = {
    {{426, 240}, {640, 360}, {756, 424}, {854, 480}},
    {{640, 360}, {960, 540}, {1132, 638}, {1280, 720}},
    {{682, 384}, {1024, 576}, {1208, 680}, {1366, 768}},
    {{853, 480}, {1280, 720}, {1510, 850}, {1706, 960}}};
static const int maxres_perf[4] = {1, 1, 2, 2}, maxres_quality[4] = {2, 2, 2, 2};

static uint32_t *cfg(int i) { return (uint32_t *)(heap + 0x100 * i); }
static void table_reset(void) {
    static const unsigned own[6][4] = {{2560, 1440, 1280, 720}, {2048, 1152, 1024, 576}, {1706, 960, 853, 480}, {1920, 1080, 960, 540}, {1536, 864, 768, 432}, {1280, 720, 640, 360}};
    for (int i = 0; i < 6; i++) { uint64_t p = (uint64_t)(uintptr_t)cfg(i); memcpy(mainimg + 0x100 + 16 * i, &p, 8); memcpy(cfg(i), own[i], 16); }
}
static int all_hold(unsigned ow, unsigned oh, unsigned rw, unsigned rh) {
    for (int i = 0; i < 6; i++) if (cfg(i)[0] != ow || cfg(i)[1] != oh || cfg(i)[2] != rw || cfg(i)[3] != rh) return 0;
    return 1;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    // arithmetic
    for (int o = 0; o < 4; o++) for (int k = 0; k < 4; k++) {
        unsigned rw, rh; int c = rcfg_render(outs[o][0], outs[o][1], pcts[k], 1280, 720, &rw, &rh);
        CHECK(rw == want[o][k][0] && rh == want[o][k][1] && c == clamped[o][k]);
        if (rw != want[o][k][0] || rh != want[o][k][1]) fprintf(stderr, "  %ux%u at %d %%: %ux%u\n", outs[o][0], outs[o][1], pcts[k], rw, rh);
        c = rcfg_render(outs[o][0], outs[o][1], pcts[k], 0, 0, &rw, &rh);
        CHECK(rw == uncapped[o][k][0] && rh == uncapped[o][k][1] && !c);
        if (pcts[k] == 33) CHECK(rh * 3 == outs[o][1]);   // 1/3 exact: the heights divide
        else CHECK(rw % 2 == 0 && rh % 2 == 0);
        CHECK(rcfg_max_res(outs[o][0], outs[o][1], 1) == maxres_perf[o] && rcfg_max_res(outs[o][0], outs[o][1], 2) == maxres_quality[o]);
    }
    CHECK(rcfg_max_res(3840, 2160, 1) == 3 && rcfg_max_res(4000, 2000, 0) == 3 && rcfg_max_res(1280, 720, 0) == 0 && rcfg_max_res(1281, 720, 0) == 1);
    CHECK(rcfg_scale_ok(33) && rcfg_scale_ok(67) && rcfg_scale_ok(100) && !rcfg_scale_ok(66) && !rcfg_scale_ok(99) && !rcfg_scale_ok(101) && !rcfg_scale_ok(0));
    // 100 %: the output itself (a ratio of 1, odd sides kept), capped as any other size
    for (int o = 0; o < 4; o++) { unsigned w, h; CHECK(rcfg_render(outs[o][0], outs[o][1], 100, 0, 0, &w, &h) == 0 && w == outs[o][0] && h == outs[o][1]); }
    { unsigned w, h;
      CHECK(rcfg_render(1365, 767, 100, 0, 0, &w, &h) == 0 && w == 1365 && h == 767);
      CHECK(rcfg_render(1920, 1080, 100, 1280, 720, &w, &h) == 1 && w == 1280 && h == 720);
      CHECK(rcfg_render(1920, 1080, 100, 1920, 1080, &w, &h) == 0 && w == 1920 && h == 1080);
      CHECK(rcfg_render(2048, 1152, 100, 1920, 1080, &w, &h) == 1 && w == 1920 && h == 1080);
      CHECK(rcfg_render(2560, 1440, 100, 1920, 1080, &w, &h) == 1 && w == 1920 && h == 1080);
      CHECK(rcfg_render(2560, 1440, 67, 1920, 1080, &w, &h) == 0 && w == 1706 && h == 960);    // 1440p @ 67 % fits the raised cap
      CHECK(rcfg_render(2048, 1152, 67, 1920, 1080, &w, &h) == 0 && w == 1366 && h == 768); }
    // the render target pool: movz w9, #(MiB << 4), lsl #16
    CHECK(rtp_encode(0x52a7e009u, 1008) == 0x52a7e009u && rtp_encode(0x52a7e009u, 1536) == 0x52ac0009u && rtp_encode(0x52a7e009u, 2048) == 0x52b00009u);
    CHECK(rtp_encode(0x52a7e009u, 4095) == 0x52bffe09u && rtp_encode(0x52a7e009u, 16) == 0x52a02009u && rtp_encode(0x52a7e01fu, 1536) == 0x52ac001fu);   // the register kept
    CHECK(rtp_word_mb(0x52a7e009u) == 1008 && rtp_word_mb(0x52ac0009u) == 1536 && rtp_word_mb(0x52b00009u) == 2048 && rtp_word_mb(rtp_encode(0x52a7e009u, 1234)) == 1234);
    CHECK(rtp_is_movz16(0x52a7e009u) && !rtp_is_movz16(0x52807e09u) && !rtp_is_movz16(0xd2a7e009u) && !rtp_is_movz16(0x72a7e009u) && rtp_word_mb(0x52a7e029u) == 0);
    // DM_RT_POOL_MB
    CHECK(rtp_env_mb(NULL) == -1 && rtp_env_mb("") == -1 && rtp_env_mb("0") == 0 && rtp_env_mb("1008") == 1008 && rtp_env_mb("1536") == 1536 && rtp_env_mb("2048") == 2048);
    CHECK(rtp_env_mb("16") == 16 && rtp_env_mb("4095") == 4095 && rtp_env_mb("15") == -2 && rtp_env_mb("4096") == -2 && rtp_env_mb("-1") == -2 && rtp_env_mb("1536x") == -2);
    CHECK(rtp_env_mb("0x600") == -2 && rtp_env_mb(" 1536") == -2 && rtp_env_mb("99999999999999999999") == -2 && rtp_env_mb("1.5") == -2);
    // the size each setting needs (D322's table): 1008 while the render fits 1280x720 and the output is below 1440 lines; 1536 for a 1440 output or a render up to 1920x1080;
    // 2048 above that. On a device of more than 12 GB at least 1536; DM_RT_POOL_MB over both, on any device
    { static const char *const wins[4] = {"1280x720", "1920x1080", "original", "2560x1440"};
      static const char *const ps[5] = {"33", "50", "59", "67", "100"};
      static const unsigned small[4][5] = {{1008, 1008, 1008, 1008, 1008}, {1008, 1008, 1008, 1008, 1536}, {1008, 1008, 1008, 1536, 2048}, {1536, 1536, 1536, 1536, 2048}};
      for (int o = 0; o < 4; o++) for (int k = 0; k < 5; k++) {
          int why, need = need_of(&k_rtp, wins[o], ps[k]);
          unsigned lo = rtp_choose(&k_rtp, 1, -1, need, &why), none = rtp_choose(&k_rtp, -1, -1, need, &why), hi = rtp_choose(&k_rtp, 0, -1, need, &why);
          CHECK(lo == small[o][k] && none == small[o][k] && hi == (small[o][k] < 1536 ? 1536 : small[o][k]));
          if (lo != small[o][k]) fprintf(stderr, "  %s at %s %%: %u MiB\n", wins[o], ps[k], lo);
          CHECK(rtp_choose(&k_rtp, 1, 0, need, &why) == 1008 && why == 0 && rtp_choose(&k_rtp, 0, 1200, need, &why) == 1200 && why == 0);
          CHECK(rtp_choose(&k_rtp, 1, -2, need, &why) == small[o][k]);   // a bad DM_RT_POOL_MB: the setting and the device decide
      }
      int why;
      CHECK(rtp_choose(&k_rtp, 0, -1, -1, &why) == 1536 && why == 2 && rtp_choose(&k_rtp, 0, -1, 0, &why) == 1536 && why == 1 && rtp_choose(&k_rtp, 0, -1, 1, &why) == 2048 && why == 1);
      CHECK(rtp_choose(&k_rtp, 1, -1, -1, &why) == 1008 && why == 3 && rtp_choose(&k_rtp, -1, -1, 1, &why) == 2048 && why == 1);
      // the rule's edges: a render just above a size's max_render takes the next; above the largest, the largest (capped there); out_h alone; a row without out_h
      CHECK(rtp_need(&k_rtp, 1280, 720, 1080, 1280, 720) == -1 && rtp_need(&k_rtp, 1280, 720, 1080, 1282, 720) == 0 && rtp_need(&k_rtp, 1280, 720, 1080, 1920, 1082) == 1);
      CHECK(rtp_need(&k_rtp, 1280, 720, 2160, 3840, 2160) == 1 && rtp_need(&k_rtp, 1280, 720, 1439, 640, 360) == -1 && rtp_need(&k_rtp, 1280, 720, 1440, 640, 360) == 0);
      CHECK(rtp_need(&k_rtp1, 1280, 720, 1440, 1280, 720) == -1 && rtp_need(&k_rtp1, 1280, 720, 1440, 2560, 1440) == 0 && need_of(&k_rtp1, "2560x1440", "100") == 0);
      // the setting as both sides read it: unset is the profile's default, below min the min, a bad size the original, a bad percent 50
      unsigned ow, oh; int p;
      CHECK(rcfg_setting(&k_rcfg[0], NULL, "1920x1080", NULL, &ow, &oh, &p) == 0 && ow == 1920 && oh == 1080 && p == 50);
      CHECK(rcfg_setting(&k_rcfg[0], "", "1920x1080", "", &ow, &oh, &p) == 0 && ow == 1920 && p == 50);
      CHECK(rcfg_setting(&k_rcfg[0], "original", "x", "100", &ow, &oh, &p) == 0 && ow == 2048 && oh == 1152 && p == 100);
      CHECK(rcfg_setting(&k_rcfg[0], "960x540", "x", "67", &ow, &oh, &p) == 2 && ow == 1280 && oh == 720 && p == 67);
      CHECK(rcfg_setting(&k_rcfg[0], "big", "x", "40", &ow, &oh, &p) == 5 && ow == 2048 && oh == 1152 && p == 50);
      CHECK(rcfg_setting(&k_rcfg[0], "2560x1440x", "x", "59", &ow, &oh, &p) == 1 && oh == 1152 && p == 59); }
    // the write and the cap it raises: only with the row's word, and the max_render of the largest size at or below the one written
    { uint32_t w; unsigned mb;
      w = 0x52a7e009u; CHECK(rtp_load(&w, 0, NULL, -1, &mb) == 1 && w == 0x52ac0009u && mb == 1536 && rtp_raised && rtp_w == 1920 && rtp_h == 1080);
      w = 0x52a7e009u; CHECK(rtp_load(&w, 1, NULL, -1, &mb) == 0 && w == 0x52a7e009u && mb == 1008 && !rtp_raised);
      w = 0x52a7e009u; CHECK(rtp_load(&w, 1, NULL, 0, &mb) == 1 && w == 0x52ac0009u && mb == 1536 && rtp_raised && rtp_w == 1920);   // 12 GB or less: the setting needs it
      w = 0x52a7e009u; CHECK(rtp_load(&w, 1, NULL, 1, &mb) == 1 && w == 0x52b00009u && mb == 2048 && rtp_raised && rtp_w == 2560 && rtp_h == 1440);
      w = 0x52a7e009u; CHECK(rtp_load(&w, 0, NULL, 1, &mb) == 1 && w == 0x52b00009u && rtp_w == 2560);
      w = 0x52a7e009u; CHECK(rtp_load(&w, 1, "2048", -1, &mb) == 1 && w == 0x52b00009u && rtp_raised && rtp_w == 2560 && !rtp_top);
      w = 0x52a7e009u; CHECK(rtp_load(&w, 1, "1800", -1, &mb) == 1 && rtp_raised && rtp_w == 1920 && rtp_h == 1080);   // between two sizes: the lower one's cap
      w = 0x52a7e009u; CHECK(rtp_load(&w, 0, "1200", 1, &mb) == 1 && w == rtp_encode(0x52a7e009u, 1200) && !rtp_raised);   // written, smaller than the row's: the cap stays
      w = 0x52a7e009u; CHECK(rtp_load(&w, 0, "0", 1, &mb) == 0 && w == 0x52a7e009u && !rtp_raised);
      w = 0x52a7e009u; CHECK(rtp_load(&w, -1, NULL, -1, &mb) == 0 && w == 0x52a7e009u && !rtp_raised && rtp_top);
      w = 0x52ac0009u; CHECK(rtp_load(&w, 0, NULL, 0, &mb) == -1 && w == 0x52ac0009u && !rtp_raised);   // not the row's word (another build, a pack already patched): nothing
      w = 0x94000000u; CHECK(rtp_load(&w, 0, "2048", 1, &mb) == -1 && w == 0x94000000u && !rtp_raised);
      unsigned cw = 0, ch = 0;
      CHECK(!rtp_cap(&k_rtp, 0, 2048, &cw, &ch) && !rtp_cap(&k_rtp, -1, 2048, &cw, &ch) && !rtp_cap(&k_rtp, 1, 1535, &cw, &ch) && cw == 0);
      CHECK(rtp_cap(&k_rtp, 1, 4095, &cw, &ch) && cw == 2560 && ch == 1440 && rtp_cap(&k_rtp1, 1, 4095, &cw, &ch) && cw == 1920); }
    CHECK(rcfg_even(1, 1, 2) == 2 && rcfg_even(1080, 2048, 1152) == 1920 && rcfg_even(720, 2048, 1152) == 1280 && rcfg_even(1440, 2048, 1152) == 2560);
    unsigned rw, rh; CHECK(rcfg_render(2560, 1080, 67, 1280, 720, &rw, &rh) == 1 && rw == 1280 && rh == 540);   // a wider output: the width limits, the aspect kept

    // the write path. The stand-in loader picks the pool from the starting setting and the device as loader.c does. pool: more than 12 GB starting at 1080p 50 %, so 1536 MiB
    // and max_render 1920x1080; big: 12 GB or less starting at 1440p 100 %, so 2048 MiB and 2560x1440; main and wrong: 12 GB or less at 1080p 50 %, the game's own 1008 MiB
    uint32_t pool_word = 0x52a7e009u; unsigned pool_mb;
    int pool = !strcmp(argv[1], "pool"), big = !strcmp(argv[1], "big");
    CHECK(rtp_load(&pool_word, pool ? 0 : 1, NULL, need_of(&k_rtp, big ? "2560x1440" : "1920x1080", big ? "100" : "50"), &pool_mb) == (pool || big) && rtp_raised == (pool || big));
    CHECK(pool_mb == (big ? 2048u : pool ? 1536u : 1008u) && pool_word == rtp_encode(0x52a7e009u, pool_mb));
    strcpy(g_mod[0].name, "main"); g_mod[0].base = mainimg;
    strcpy(g_mod[0].build_id, !strcmp(argv[1], "wrong") ? "fedcba9876543210fedcba9876543210" : "0123456789abcdef0123456789abcdef");
    table_reset();
    // the setting hle_render.c starts from (the env is read at its first call; the loader read the starting setting above)
    setenv("DM_WINDOW_SIZE", "2560x1440", 1); setenv("DM_RENDER_PCT", big ? "100" : "67", 1); setenv("DM_GFX_QUALITY", "quality", 1);
    if (big) {
        // 1440p 100 % with the 2048 MiB pool: written as it is, 2560x1440
        CHECK(dm_render_active() && g_cap_pool && g_cap_w == 2560 && g_cap_h == 1440);
        CHECK(dm_render_perf_mode() == 1 && all_hold(2560, 1440, 2560, 1440) && g_next_start == 0);
        // the panel: 1152p (original) 100 %, then 1080p 67 % performance; nothing above this run's cap
        nxhost_render_set(0, -1, -1);
        CHECK(dm_render_perf_mode() == 1 && all_hold(2048, 1152, 2048, 1152) && notified == 1);
        nxhost_render_set(1080, 67, 0);
        CHECK(dm_render_perf_mode() == 0 && all_hold(1920, 1080, 1280, 720) && dm_render_max_res() == 1 && notified == 2 && g_next_start == 0);
        nxhost_render_set(500, 40, 7);   // none taken: unchanged
        CHECK(dm_render_perf_mode() == 0 && all_hold(1920, 1080, 1280, 720) && notified == 3);
    } else if (pool) {
        // the raised cap: 1440p @ 67 % quality is written as it is (1706x960), not 1280x720
        CHECK(dm_render_active() && g_cap_pool && g_cap_w == 1920 && g_cap_h == 1080);
        CHECK(dm_render_perf_mode() == 1 && all_hold(2560, 1440, 1706, 960) && prepared == 12);
        // 100 %: 1080p native (DLAA), then 1440p @ 100 % capped at 1920x1080, 1152p @ 67 % (1366x768), 1080p @ 33 % performance
        CHECK(dm_render_script("rscale", 100) && dm_render_script("outh", 1080) && dm_render_perf_mode() == 1 && all_hold(1920, 1080, 1920, 1080) && dm_render_max_res() == 2);
        CHECK(dm_render_script("outh", 1440) && dm_render_perf_mode() == 1 && all_hold(2560, 1440, 1920, 1080));
        CHECK(dm_render_script("outh", 1152) && dm_render_script("rscale", 67) && dm_render_perf_mode() == 1 && all_hold(2048, 1152, 1366, 768));
        CHECK(dm_render_script("outh", 1080) && dm_render_script("rscale", 33) && dm_render_script("quality", 0) && dm_render_perf_mode() == 0 && all_hold(1920, 1080, 640, 360)
              && dm_render_max_res() == 1);
        CHECK(dm_render_script("rscale", 101) && dm_render_perf_mode() == 0 && all_hold(1920, 1080, 640, 360));   // 101 refused: unchanged
    } else if (!strcmp(argv[1], "wrong")) {
        CHECK(!dm_render_active() && dm_render_perf_mode() == -1 && dm_render_max_res() == -1 && dm_render_script("outh", 720) == 1);
        CHECK(all_hold(2560, 1440, 1280, 720) == 0 && cfg(3)[0] == 1920 && cfg(3)[2] == 960 && prepared == 0 && notified == 0);   // the game's own sizes
    } else {
        CHECK(dm_render_active() && dm_render_max_res() == 2 && !g_cap_pool && g_cap_w == 1280 && g_cap_h == 720);   // answered before the first write; the pool not written: the row's cap
        CHECK(dm_render_perf_mode() == 1 && all_hold(2560, 1440, 1280, 720) && prepared == 12);   // Boost, 1706x960 capped to 1280x720, all six
        CHECK(dm_render_perf_mode() == 1 && all_hold(2560, 1440, 1280, 720));   // the host's last write is accepted
        // live: 720p performance 33 %: pending, the largest buffer stays 2 until the write, then 1
        CHECK(dm_render_script("outh", 720) && dm_render_script("quality", 0) && dm_render_script("rscale", 33) && notified == 3);
        CHECK(dm_render_max_res() == 2 && all_hold(2560, 1440, 1280, 720));
        CHECK(dm_render_perf_mode() == 0 && all_hold(1280, 720, 426, 240) && dm_render_max_res() == 1);
        // live: 1440p raises the largest buffer at once
        CHECK(dm_render_script("outh", 1440) && dm_render_max_res() == 2 && all_hold(1280, 720, 426, 240));
        CHECK(dm_render_script("outh", 600) && dm_render_script("rscale", 40) && dm_render_script("outh", 0));   // 600 (below min) and 40 refused (unchanged), 0 = original
        CHECK(dm_render_perf_mode() == 0 && all_hold(2048, 1152, 682, 384) && dm_render_max_res() == 2);
        CHECK(dm_render_script("focus", 1) == 0);   // not a render key: hle_oe.c's
        // a configuration the game changed itself: nothing written
        cfg(4)[2] = 700; int before = prepared;
        CHECK(dm_render_script("rscale", 50) && dm_render_perf_mode() == 0 && prepared == before && cfg(0)[2] == 682);
        cfg(4)[2] = 682;
        CHECK(dm_render_perf_mode() == 0 && all_hold(2048, 1152, 1024, 576));
        // an empty slot, then a configuration outside the heap: nothing written
        uint64_t keep; memcpy(&keep, mainimg + 0x100 + 16 * 5, 8); uint64_t z = 0, out = (uint64_t)(uintptr_t)mainimg;
        memcpy(mainimg + 0x100 + 16 * 5, &z, 8); before = prepared;
        CHECK(dm_render_script("rscale", 59) && dm_render_perf_mode() == 0 && prepared == before && cfg(0)[2] == 1024);
        memcpy(mainimg + 0x100 + 16 * 5, &out, 8);
        CHECK(dm_render_perf_mode() == 0 && prepared == before && cfg(0)[2] == 1024);
        memcpy(mainimg + 0x100 + 16 * 5, &keep, 8);
        CHECK(dm_render_perf_mode() == 0 && all_hold(2048, 1152, 1208, 680));
        // the configurations in the alias region (the game's StandardAllocator blocks): the same checks, then written there
        for (int i = 0; i < 6; i++) { uint64_t a = (uint64_t)(uintptr_t)(alias + 0x100 * i); memcpy(alias + 0x100 * i, cfg(i), 16); memcpy(mainimg + 0x100 + 16 * i, &a, 8); }
        uint32_t *ac0 = (uint32_t *)alias, *ac5 = (uint32_t *)(alias + 0x500);
        before = prepared;
        CHECK(dm_render_script("rscale", 67) && dm_render_perf_mode() == 0 && prepared == before + 12);
        CHECK(ac0[0] == 2048 && ac0[1] == 1152 && ac0[2] == 1280 && ac0[3] == 720 && ac5[2] == 1280 && cfg(0)[2] == 1208);   // 1366x768 capped; the heap copies untouched
        // an alias page the guest has not mapped: nothing written
        alias_mapped = 0; before = prepared;
        CHECK(dm_render_script("rscale", 50) && dm_render_perf_mode() == 0 && prepared == before && ac0[2] == 1280);
        alias_mapped = 1;
        // below the alias region (inside neither range): nothing written
        uint64_t below = (uint64_t)(uintptr_t)alias - 0x1000; memcpy(&keep, mainimg + 0x100 + 16 * 2, 8); memcpy(mainimg + 0x100 + 16 * 2, &below, 8);
        CHECK(dm_render_perf_mode() == 0 && prepared == before && ac0[2] == 1280);
        memcpy(mainimg + 0x100 + 16 * 2, &keep, 8);
        // a mix of heap and alias configurations, the alias one holding the game's own sizes and the others the host's last write
        uint64_t h3 = (uint64_t)(uintptr_t)cfg(3); memcpy(mainimg + 0x100 + 16 * 3, &h3, 8);
        static const unsigned own3[4] = {1920, 1080, 960, 540}; memcpy(cfg(3), own3, 16);
        CHECK(dm_render_perf_mode() == 0 && prepared == before + 12 && ac0[2] == 1024 && ac0[3] == 576 && cfg(3)[0] == 2048 && cfg(3)[2] == 1024);
        // a value the game changed in the alias region: nothing written
        ac5[3] = 1; before = prepared;
        CHECK(dm_render_script("quality", 1) && dm_render_perf_mode() == 1 && prepared == before && ac0[2] == 1024);
    }
    if (!strcmp(argv[1], "main")) {
        // the in-game panel on the game's own 1008 MiB pool: 1440p 67 % needs 1536 MiB, so it runs at 1280x720 until the next start (one line); 1080p 100 % likewise;
        // 720p fits (no line). The pool's largest size (2560x1440) holds every render size, so each clamp says the next start applies it
        uint32_t *ac0 = (uint32_t *)alias, *ac5 = (uint32_t *)(alias + 0x500);
        ac5[3] = 576; unsigned n0 = g_next_start;
        nxhost_render_set(1440, 67, -1);
        CHECK(g_next_start == n0 + 1 && dm_render_perf_mode() == 1 && ac0[0] == 2560 && ac0[2] == 1280 && ac0[3] == 720);
        nxhost_render_set(1080, 100, -1);
        CHECK(g_next_start == n0 + 2 && dm_render_perf_mode() == 1 && ac0[0] == 1920 && ac0[2] == 1280 && ac0[3] == 720);
        nxhost_render_set(720, -1, 0);
        CHECK(g_next_start == n0 + 2 && dm_render_perf_mode() == 0 && ac0[0] == 1280 && ac0[2] == 1280 && ac0[3] == 720 && dm_render_max_res() == 1);
        rtp_top = 0;   // a run whose pool DM_RT_POOL_MB fixed: no next-start line
        nxhost_render_set(1440, -1, -1);
        CHECK(g_next_start == n0 + 2 && dm_render_perf_mode() == 0 && ac0[0] == 2560 && ac0[2] == 1280);
    }
    printf("t_render_cfg %s: %d checks, %d failed\n", argv[1], checks, failed);
    return failed != 0;
}
