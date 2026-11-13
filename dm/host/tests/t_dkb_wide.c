// Donkey Kong Bananza camera and UI callback (android/dkb_wide.c): opt-in and build gates, Hor+ / Vert+ on the scene camera's updater, repeated callbacks, a game that writes 16:9 back,
// objects that are no 16:9 lens, and the layout system's perspective projection squeezed into the middle 16:9 (orthographic and other shapes, scaled ones and null pointers untouched). Every case runs in its own process
// (the picture shape is read once).
#include "../android/dkb_wide.c"
#include <assert.h>
#include <sys/wait.h>
#include <unistd.h>

static module_t test_modules[1];
module_t *g_mod = test_modules;
int g_nmod;
static void (*callbacks[OBS_MAX])(uint64_t *, int);

void gate_obs_set(int id, void (*fn)(uint64_t *, int)) {
    assert(id > 0 && id < OBS_MAX);
    callbacks[id] = fn;
}

static void gate(uint64_t obj) {   // the camera gate as the rewriter's block calls it: the updater in x19, the register in the gate number's upper bits
    uint64_t r[31] = {0};
    r[19] = obj;
    callbacks[OBS_DKB_WIDE](r, 19);
}
static void hud_gate(uint64_t dinfo) {   // the HUD gate: x0 the draw info, kind 1
    uint64_t r[31] = {0};
    r[0] = dinfo;
    callbacks[OBS_DKB_WIDE](r, 1 << 5);
}

static void make(unsigned char *p, double fovy_deg, double asp) { ws_wrf((uintptr_t)p + C_FOVY, (float)fovy_deg); ws_wrf((uintptr_t)p + C_ASPECT, (float)asp); }
// what the projection update turns them into: m00 = 1 / (tan(fovy / 2) * aspect), m11 = 1 / tan(fovy / 2)
static double m00(const unsigned char *p) { return 1.0 / (tan(ws_rdf((uintptr_t)p + C_FOVY) * M_PI / 360.0) * ws_rdf((uintptr_t)p + C_ASPECT)); }
static double m11(const unsigned char *p) { return 1.0 / tan(ws_rdf((uintptr_t)p + C_FOVY) * M_PI / 360.0); }

static const char *shape[] = {"21:9", "1:1", "4:3", "13:6", "34:15", "181:196"};
static double shape_a[] = {21.0 / 9, 1.0, 4.0 / 3, 13.0 / 6, 34.0 / 15, 181.0 / 196};

static void setup(const char *env, const char *build) {
    g_nmod = 1;
    strcpy(g_mod[0].name, "main");
    strcpy(g_mod[0].build_id, build);
    if (env) setenv("DM_VK_PICTURE_ASPECT", env, 1); else unsetenv("DM_VK_PICTURE_ASPECT");
}

static void check(int which) {   // 0..13 no change; 14 another build; 15..20 the shapes
    static const char *off[] = {NULL, "", "0", "0:9", "16:0", "abc", "16:9", "1778:1000", "4:3x", "nan", "-4:3", "100:1", "1:100", "32:18"};
    _Alignas(16) unsigned char p[0x120], before[0x120];
    uint64_t a = (uintptr_t)p;
    memset(p, 0, sizeof p);
    if (which < 14) setup(off[which], DKB_MAIN_BUILD);
    else if (which == 14) setup("4:3", "0000");
    else setup(shape[which - 15], DKB_MAIN_BUILD);
    make(p, 60, K169);
    memcpy(before, p, sizeof p);
    gate(a);
    if (which < 15) { assert(!memcmp(before, p, sizeof p)); return; }

    double A = shape_a[which - 15];
    assert(fabs(ws_rdf(a + C_ASPECT) - A) < 1e-6);
    if (A > K169) {   // Hor+: the vertical field of view stays
        assert(ws_rdf(a + C_FOVY) == 60.0f);
        assert(fabs(m11(p) - m11(before)) < 1e-9);
    } else {         // Vert+: the horizontal reach stays what a 16:9 picture shows
        assert(fabs(m00(p) - 1.0 / (tan(60 * M_PI / 360.0) * K169)) < 1e-5);
        assert(ws_rdf(a + C_FOVY) > 60.0f);
    }
    // the gate fires again and again on the same updater: nothing scales twice
    unsigned char once[0x120];
    memcpy(once, p, sizeof p);
    for (int i = 0; i < 1000; i++) gate(a);
    assert(!memcmp(once, p, sizeof p));
    // the game's setter writes 16:9 back and keeps the fovy we wrote: only the aspect is set again
    ws_wrf(a + C_ASPECT, (float)K169);
    gate(a);
    assert(!memcmp(once, p, sizeof p));
    // fresh authored values (a new zoom) are converted once
    make(p, 45, K169);
    gate(a); gate(a);
    if (A < K169) assert(fabs(m00(p) - 1.0 / (tan(45 * M_PI / 360.0) * K169)) < 1e-5);
    else assert(fabs(m11(p) - 1.0 / tan(45 * M_PI / 360.0)) < 1e-5);
    assert(fabs(ws_rdf(a + C_ASPECT) - A) < 1e-6);
    // not a 16:9 perspective lens: another aspect (a split view), an impossible fovy, a null or unaligned pointer
    make(p, 60, 1.0); memcpy(before, p, sizeof p); gate(a); assert(!memcmp(before, p, sizeof p));
    make(p, 0.0, K169); memcpy(before, p, sizeof p); gate(a); assert(!memcmp(before, p, sizeof p));
    make(p, 400.0, K169); memcpy(before, p, sizeof p); gate(a); assert(!memcmp(before, p, sizeof p));
    gate(0);
    gate(a + 1);
    // a huge field of view is held under 172 degrees
    if (A < K169) { make(p, 150, K169); gate(a); assert(ws_rdf(a + C_FOVY) <= 172.0f && isfinite(ws_rdf(a + C_FOVY))); }
}

// the draw info: the projection copy at +0x10 (4x4 floats row by row), the layout system's perspective (main+0x45f840: fovy 15 degrees, aspect 1920 / 1080, near 1, far 10000)
static void layout_proj(float *m) {
    double c = 1.0 / tan(15.0 * M_PI / 360.0), n = 1, f = 10000;
    memset(m, 0, 64);
    m[0] = (float)(c / (1920.0 / 1080.0)); m[5] = (float)c; m[10] = (float)(-(f + n) / (f - n)); m[11] = (float)(-2 * f * n / (f - n)); m[14] = -1.0f;
}
static void ui_check(int which) {   // 0..2 no change (unset, 16:9, another build), 3 21:9, 4 1:1, 5 4:3
    _Alignas(16) float d[0x20] = {0}, keep[0x20];
    float *m = d + 4;
    layout_proj(m);
    memcpy(keep, d, sizeof d);
    setup(which == 0 ? NULL : which == 1 ? "16:9" : which == 2 ? "21:9" : which == 3 ? "21:9" : which == 4 ? "1:1" : "4:3", which == 2 ? "0000" : DKB_MAIN_BUILD);
    double A = which == 4 ? 1.0 : which == 5 ? 4.0 / 3 : 21.0 / 9;
    int on = which >= 3;
    hud_gate((uintptr_t)d);
    if (!on) { assert(!memcmp(d, keep, sizeof d)); return; }
    float q = (float)(A > K169 ? K169 / A : A / K169);
    for (int j = 0; j < 16; j++) {
        int xrow = j < 4, yrow = j >= 4 && j < 8;
        float want = keep[4 + j] * ((A > K169 ? xrow : yrow) ? q : 1.0f);
        assert(fabs(m[j] - want) < 1e-6);
    }
    assert(fabs(m[5] / m[0] - A) < 1e-4);   // the layer's shape is now the picture's: 16:9 content in the middle 16:9
    assert(!memcmp(d, keep, 16) && !memcmp(d + 20, keep + 20, 12 * 4));   // the head and the rows past the second are untouched
    // the scaled projection no longer has the 16:9 shape: a second setup of the same draw info (or the gate again) does not scale it twice
    float once[0x20]; memcpy(once, d, sizeof d);
    for (int i = 0; i < 100; i++) hud_gate((uintptr_t)d);
    assert(!memcmp(once, d, sizeof d));
    // an orthographic projection (a capture's 2/1920 x 2/1080), a perspective of another shape, null and unaligned pointers: untouched
    memset(d, 0, sizeof d); m[0] = 2.0f / 1920.0f; m[3] = -1.0f; m[5] = 2.0f / 1080.0f; m[7] = -1.0f; m[10] = -1.0f; m[15] = 1.0f; memcpy(once, d, sizeof d);
    hud_gate((uintptr_t)d); assert(!memcmp(once, d, sizeof d));
    memcpy(d, keep, sizeof d); m[0] = m[5]; memcpy(once, d, sizeof d);   // a square layout
    hud_gate((uintptr_t)d); assert(!memcmp(once, d, sizeof d));
    hud_gate(0);
    hud_gate((uintptr_t)d + 1);
    // an unknown kind and a negative parameter do nothing
    uint64_t r[31] = {0}; r[0] = (uintptr_t)d; r[19] = (uintptr_t)d;
    memcpy(d, keep, sizeof d); memcpy(once, d, sizeof d);
    callbacks[OBS_DKB_WIDE](r, 5 << 5); callbacks[OBS_DKB_WIDE](r, -1); callbacks[OBS_DKB_WIDE](r, 29);
    assert(!memcmp(once, d, sizeof d));
}

int main(void) {
    for (int i = 0; i < 6; i++) {
        pid_t child = fork();
        assert(child >= 0);
        if (!child) { ui_check(i); _exit(0); }
        int status;
        assert(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) { fprintf(stderr, "dkb wide UI case %d failed\n", i); return 1; }
    }
    puts("dkb wide: 6 layout projection cases passed");
    for (int i = 0; i < 21; i++) {
        pid_t child = fork();
        assert(child >= 0);
        if (!child) { check(i); _exit(0); }
        int status;
        assert(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) { fprintf(stderr, "dkb wide case %d failed\n", i); return 1; }
    }
    puts("dkb wide: 21 opt-in, build, Hor+, Vert+ and repeat cases passed");
    return 0;
}
