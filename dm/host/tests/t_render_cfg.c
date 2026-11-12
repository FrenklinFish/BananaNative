// Render configurations the host rewrites (hle_render.c, render_cfg.h): the size arithmetic for every output of the list and every render scale, the rounding, the max_render cap,
// the largest-buffer rule, and the write path on a fake main, heap and alias region: the build check, the slot, heap and alias-region checks, the game's own sizes or the host's last
// write, the pending live change (raised largest buffer at once, lowered at the write). Expected values are written by hand. Usage: t_render_cfg main | wrong
#define DM_RENDER_CFG_ROWS {{"0123456789abcdef0123456789abcdef", 0x100, 6, 16, 0x0, 0x8, \
    {{2560, 1440, 1280, 720}, {2048, 1152, 1024, 576}, {1706, 960, 853, 480}, {1920, 1080, 960, 540}, {1536, 864, 768, 432}, {1280, 720, 640, 360}}, 2048, 1152, 1280, 720, 720}}
#define DM_RENDER_NCFG_ROWS 1
#define DM_RENDER_QUALITY {1, 2, 0, 1}
#define DM_RENDER_DEFAULT_OUT "1920x1080"
#ifndef HLE_RENDER_SOURCE   // a copy beside a stand-in hle.h runs the same code on a desktop without the host's platform layer
#define HLE_RENDER_SOURCE "../hle_render.c"
#endif
#include HLE_RENDER_SOURCE

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
    CHECK(rcfg_scale_ok(33) && rcfg_scale_ok(67) && !rcfg_scale_ok(66) && !rcfg_scale_ok(100) && !rcfg_scale_ok(0));
    CHECK(rcfg_even(1, 1, 2) == 2 && rcfg_even(1080, 2048, 1152) == 1920 && rcfg_even(720, 2048, 1152) == 1280 && rcfg_even(1440, 2048, 1152) == 2560);
    unsigned rw, rh; CHECK(rcfg_render(2560, 1080, 67, 1280, 720, &rw, &rh) == 1 && rw == 1280 && rh == 540);   // a wider output: the width limits, the aspect kept

    // the write path
    strcpy(g_mod[0].name, "main"); g_mod[0].base = mainimg;
    strcpy(g_mod[0].build_id, !strcmp(argv[1], "wrong") ? "fedcba9876543210fedcba9876543210" : "0123456789abcdef0123456789abcdef");
    table_reset();
    setenv("DM_WINDOW_SIZE", "2560x1440", 1); setenv("DM_RENDER_PCT", "67", 1); setenv("DM_GFX_QUALITY", "quality", 1);
    if (!strcmp(argv[1], "wrong")) {
        CHECK(!dm_render_active() && dm_render_perf_mode() == -1 && dm_render_max_res() == -1 && dm_render_script("outh", 720) == 1);
        CHECK(all_hold(2560, 1440, 1280, 720) == 0 && cfg(3)[0] == 1920 && cfg(3)[2] == 960 && prepared == 0 && notified == 0);   // the game's own sizes
    } else {
        CHECK(dm_render_active() && dm_render_max_res() == 2);              // answered before the first write: the boot selection reads it
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
    printf("t_render_cfg %s: %d checks, %d failed\n", argv[1], checks, failed);
    return failed != 0;
}
