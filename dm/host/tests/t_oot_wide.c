// Ocarina of Time camera callback (android/oot_wide.c): opt-in and build gates, Hor+ / Vert+ projections, the horizontal reach kept for a narrower picture, repeated callbacks
// on one projection, a camera update that writes fresh values again, and objects that are no perspective lens. Every case runs in its own process (the picture shape is read once).
#include "../android/oot_wide.c"
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

static void gate(uint64_t proj, int reg) {   // the gate as the rewriter's block calls it: the projection in x<reg>, the register in the gate number's upper bits
    uint64_t r[31] = {0};
    r[reg] = proj;
    callbacks[OBS_OOT_WIDE](r, reg);
}

// sead's projection as the camera update leaves it: fovy, sin, cos and tan of the half angle, aspect
static void make(unsigned char *p, double fovy_deg, double aspect) {
    double half = fovy_deg * M_PI / 360.0;
    wrf((uintptr_t)p + P_FOVY, (float)(2 * half));
    wrf((uintptr_t)p + P_SIN, (float)sin(half));
    wrf((uintptr_t)p + P_COS, (float)cos(half));
    wrf((uintptr_t)p + P_TAN, (float)tan(half));
    wrf((uintptr_t)p + P_ASPECT, (float)aspect);
}
static double m00(const unsigned char *p) { return 1.0 / ((double)rdf((uintptr_t)p + P_TAN) * rdf((uintptr_t)p + P_ASPECT)); }   // the matrix updates' first entry
static double m11(const unsigned char *p) { return 1.0 / rdf((uintptr_t)p + P_TAN); }

static void check(int which) {
    static const char *off[] = {NULL, "", "0", "0:9", "16:0", "abc", "16:9", "1778:1000", "4:3x", "nan", "-4:3", "100:1", "1:100", "32:18"};   // cases 0..13: no change
    _Alignas(16) unsigned char p[0x100], before[0x100];
    uint64_t a = (uintptr_t)p;
    g_nmod = 1;
    strcpy(g_mod[0].name, "main");
    strcpy(g_mod[0].build_id, OOT_MAIN_BUILD);
    if (which < 14) {
        if (off[which]) setenv("DM_VK_PICTURE_ASPECT", off[which], 1); else unsetenv("DM_VK_PICTURE_ASPECT");
    } else if (which == 14) { setenv("DM_VK_PICTURE_ASPECT", "4:3", 1); g_mod[0].build_id[0] = '0'; }   // another build
    else setenv("DM_VK_PICTURE_ASPECT", which == 15 ? "21:9" : which == 16 ? "1:1" : which == 17 ? "4:3" : which == 18 ? "13:6" : which == 19 ? "34:15" : "181:196", 1);   // 18..20: Screen shape of a 2.17:1 window, a 2448x1080 phone and the foldable's portrait inner screen
    make(p, 60, K169);
    memcpy(before, p, sizeof p);
    for (int reg = 0; reg < 29; reg += 7) gate(a, reg);
    if (which < 15) { assert(!memcmp(before, p, sizeof p)); return; }

    double A = which == 15 ? 21.0 / 9 : which == 16 ? 1.0 : which == 17 ? 4.0 / 3 : which == 18 ? 13.0 / 6 : which == 19 ? 34.0 / 15 : 181.0 / 196;
    assert(fabs(rdf(a + P_ASPECT) - A) < 1e-6);
    if (A > K169) {   // Hor+: the vertical field of view and the tangent stay
        assert(!memcmp(before, p, P_ASPECT));
        assert(fabs(m11(p) - m11(before)) < 1e-9);
    } else {         // Vert+: the horizontal reach stays what a 16:9 picture shows, the lens is consistent
        double ref = 1.0 / (tan(60 * M_PI / 360.0) * K169);
        assert(fabs(m00(p) - ref) < 1e-5);
        double half = atan(rdf(a + P_TAN));
        assert(fabs(rdf(a + P_FOVY) - 2 * half) < 1e-5 && fabs(rdf(a + P_SIN) - sin(half)) < 1e-5 && fabs(rdf(a + P_COS) - cos(half)) < 1e-5);
        assert(rdf(a + P_TAN) > tan(60 * M_PI / 360.0));
    }
    // The gates fire again and again on the same projection (five sites, every frame): nothing scales twice
    unsigned char once[0x100];
    memcpy(once, p, sizeof p);
    for (int i = 0; i < 1000; i++) gate(a, i % 29);
    assert(!memcmp(once, p, sizeof p));
    // The game writes 16:9 back and keeps the tangent we wrote: only the aspect is set again
    wrf(a + P_ASPECT, (float)K169);
    gate(a, 3);
    assert(!memcmp(once, p, sizeof p));
    // The camera update writes fresh authored values (a new zoom): they are converted once
    make(p, 45, K169);
    gate(a, 20);
    gate(a, 20);
    if (A < K169) assert(fabs(m00(p) - 1.0 / (tan(45 * M_PI / 360.0) * K169)) < 1e-5);
    else assert(fabs(m11(p) - 1.0 / tan(45 * M_PI / 360.0)) < 1e-5);
    assert(fabs(rdf(a + P_ASPECT) - A) < 1e-6);
    // Objects that are not a 16:9 perspective lens stay as they are: another aspect, an orthographic-like tangent, a null or unaligned pointer
    make(p, 60, 1.0);
    memcpy(before, p, sizeof p);
    gate(a, 0);
    assert(!memcmp(before, p, sizeof p));
    make(p, 60, K169);
    wrf(a + P_TAN, 0.0f);
    memcpy(before, p, sizeof p);
    gate(a, 0);
    assert(!memcmp(before, p, sizeof p));
    gate(0, 0);
    gate(a + 1, 0);
    // A huge field of view is held under 172 degrees
    if (A < K169) {
        make(p, 150, K169);
        gate(a, 0);
        assert(rdf(a + P_FOVY) <= 3.0001f && isfinite(rdf(a + P_TAN)));
    }
}

// ---- the 2D layer: HUD canvas, movie viewport, save picture ----
static void ui_check(int which) {   // 0..3: no change (unset, 16:9, another build, another module layout), 4 21:9, 5 1:1, 6 4:3, 7 21:9 with a keep mask of 0, 8 21:9 with mask 2 (pause menu whole)
    static _Alignas(16) unsigned char fake_main[16];
    _Alignas(16) float rows[8] = {2.0f / 3840.0f, 0, 0, -1.0f, 0, 2.0f / 2160.0f, 0, -1.0f};   // the full-screen UI orthographic projection's first two rows, as in the game
    _Alignas(16) float keep_rows[8];
    memcpy(keep_rows, rows, sizeof rows);
    g_nmod = 1;
    strcpy(g_mod[0].name, "main");
    strcpy(g_mod[0].build_id, which == 2 ? "0000" : OOT_MAIN_BUILD);
    g_mod[0].base = fake_main;
    if (which == 0) unsetenv("DM_VK_PICTURE_ASPECT");
    else setenv("DM_VK_PICTURE_ASPECT", which == 1 ? "16:9" : which == 2 ? "21:9" : which == 3 ? "1:1" : which == 4 ? "21:9" : which == 5 ? "1:1" : which == 6 ? "4:3" : "21:9", 1);
    if (which == 7) setenv("DM_OOT_WIDE_KEEP", "0", 1);
    if (which == 8) setenv("DM_OOT_WIDE_KEEP", "2", 1);
    if (which == 9) setenv("DM_OOT_WIDE_KEEP", "zz", 1);
    double A = which == 5 ? 1.0 : which == 6 ? 4.0 / 3 : 21.0 / 9;
    int on = which >= 4;
    if (which == 3) { setenv("DM_VK_PICTURE_ASPECT", "1:1", 1); on = 0; g_nmod = 0; }   // no main module: nothing known to rewrite
    // HUD: the canvas (x0) is 0x200 into a buffer, its screen's vtable at -0x1b8; main's base is fake_main, so a screen's vtable is its main-relative offset added to that
    _Alignas(16) unsigned char obj[0x400];
    uint64_t cv = (uintptr_t)obj + 0x200;
    memset(obj, 0, sizeof obj);
    uint64_t r[31] = {0};
    for (int k = -1; k < 4; k++) {   // k = -1: a screen that is not listed
        static const uint32_t vt[4] = {0x4bed098, 0x4be4fb0, 0x4be61c8, 0x4be7b30};
        uint64_t v = k < 0 ? 0x1234 : (uint64_t)(uintptr_t)fake_main + vt[k];
        memcpy(obj + 0x200 - 0x1b8, &v, 8);
        memset(r, 0, sizeof r);
        r[0] = cv; r[2] = (uintptr_t)rows;
        callbacks[OBS_OOT_WIDE](r, 1 << 5);
        int whole = k >= 0 && ((which == 7 ? 0 : which == 8 ? 2 : which == 9 ? 0xd : 0xd) >> k & 1);   // the keep mask
        if (!on || whole) { assert(r[2] == (uintptr_t)rows); continue; }
        assert(r[2] != (uintptr_t)rows && !(r[2] & 0));
        const float *o = (const float *)(uintptr_t)r[2];
        assert(!memcmp(rows, keep_rows, sizeof rows));   // the game's own rows are never written
        if (A > K169) { for (int j = 0; j < 4; j++) { assert(fabs(o[j] - rows[j] * K169 / A) < 1e-6); assert(o[4 + j] == rows[4 + j]); } }
        else { for (int j = 0; j < 4; j++) { assert(o[j] == rows[j]); assert(fabs(o[4 + j] - rows[4 + j] * A / K169) < 1e-6); } }
    }
    // not the full-screen UI projection (another orthographic grid, a perspective matrix): untouched
    float other[8] = {2.0f / 1920.0f, 0, 0, -1, 0, 2.0f / 1080.0f, 0, -1};
    memset(r, 0, sizeof r); r[0] = cv; r[2] = (uintptr_t)other;
    callbacks[OBS_OOT_WIDE](r, 1 << 5);
    assert(r[2] == (uintptr_t)other);
    memset(r, 0, sizeof r); r[0] = cv; r[2] = 0;
    callbacks[OBS_OOT_WIDE](r, 1 << 5);   // a null matrix pointer
    // Movie: the viewport struct at x29-0x28, min x +8, min y +12, max x +16, max y +20, in a frame buffer
    _Alignas(16) float frame[24], full[24];
    for (int i = 0; i < 24; i++) frame[i] = 1000.0f + i;
    float *vp = frame + 2;           // x29 - 0x28 (the struct) ; x29 = struct + 40
    vp[2] = 0; vp[3] = 0; vp[4] = 1920; vp[5] = 1080;
    memcpy(full, frame, sizeof frame);
    memset(r, 0, sizeof r); r[29] = (uintptr_t)vp + 40;
    callbacks[OBS_OOT_WIDE](r, 2 << 5 | 29);
    if (!on) assert(!memcmp(frame, full, sizeof frame));
    else if (A > K169) { float in = (float)(1920 * (1 - K169 / A) / 2); assert(fabs(vp[2] - in) < 0.01 && fabs(vp[4] - (1920 - in)) < 0.01 && vp[3] == 0 && vp[5] == 1080); }
    else { float in = (float)(1080 * (1 - A / K169) / 2); assert(fabs(vp[3] - in) < 0.01 && fabs(vp[5] - (1080 - in)) < 0.01 && vp[2] == 0 && vp[4] == 1920); }
    for (int i = 0; i < 24; i++) if (i < 4 || i > 7) assert(frame[i] == full[i]);   // nothing else is written
    // a viewport with no extent is left alone
    vp[2] = vp[4] = vp[3] = vp[5] = 5; memcpy(full, frame, sizeof frame);
    callbacks[OBS_OOT_WIDE](r, 2 << 5 | 29);
    assert(!memcmp(frame, full, sizeof frame));
    // Save picture: x, y, w, h = 0, 0, 543, 306 in x1..x4
    memset(r, 0, sizeof r); r[3] = 543; r[4] = 306;
    callbacks[OBS_OOT_WIDE](r, 3 << 5);
    if (!on) assert(r[1] == 0 && r[2] == 0 && r[3] == 543 && r[4] == 306);
    else if (A > K169) { int w = (int)(543.0 * A / K169 + 0.5); assert((int32_t)r[1] == (543 - w) / 2 && (int32_t)r[1] < 0 && r[3] == (uint32_t)w && r[2] == 0 && r[4] == 306); }
    else { int h = (int)(306.0 * K169 / A + 0.5); assert((int32_t)r[2] == (306 - h) / 2 && r[4] == (uint32_t)h && r[1] == 0 && r[3] == 543); }
    // any other viewport call of that helper is left alone
    memset(r, 0, sizeof r); r[1] = 10; r[3] = 543; r[4] = 306;
    callbacks[OBS_OOT_WIDE](r, 3 << 5);
    assert(r[1] == 10 && r[2] == 0 && r[3] == 543 && r[4] == 306);
    memset(r, 0, sizeof r); r[3] = 1920; r[4] = 1080;
    callbacks[OBS_OOT_WIDE](r, 3 << 5);
    assert(r[1] == 0 && r[2] == 0 && r[3] == 1920 && r[4] == 1080);
}

int main(void) {
    for (int i = 0; i < 10; i++) {
        pid_t child = fork();
        assert(child >= 0);
        if (!child) { ui_check(i); _exit(0); }
        int status;
        assert(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) {
            fprintf(stderr, "oot wide 2D case %d failed\n", i);
            return 1;
        }
    }
    puts("oot wide: 10 HUD, movie and save picture cases passed");
    for (int i = 0; i < 21; i++) {
        pid_t child = fork();
        assert(child >= 0);
        if (!child) { check(i); _exit(0); }
        int status;
        assert(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) {
            fprintf(stderr, "oot wide case %d failed\n", i);
            return 1;
        }
    }
    puts("oot wide: 21 opt-in, build, Hor+, Vert+ and repeat cases passed");
    return 0;
}
