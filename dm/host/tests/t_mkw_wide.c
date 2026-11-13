// Mario Kart World camera and 2D layer callback (android/mkw_wide.c): opt-in and build gates, the camera aspect store (16:9 and 8:9 views widened, other views kept, Vert+ field of
// view, the layout camera list kept at 16:9 in a narrower picture), the UI projection scaled in place once (full-screen panes whole through a stand-in) and the rows site. Every case runs in its own process (the shape is read once).
#include "../android/mkw_wide.c"
#include <assert.h>
#include <sys/wait.h>
#include <unistd.h>

static module_t test_modules[1];
module_t *g_mod = test_modules;
int g_nmod;
static void (*callbacks[OBS_MAX])(uint64_t *, int);
void gate_obs_set(int id, void (*fn)(uint64_t *, int)) { assert(id > 0 && id < OBS_MAX); callbacks[id] = fn; }

static void setup(int which, double *A, int *on) {   // which: 0 unset, 1 16:9, 2 other build, 3 21:9, 4 1:1, 5 4:3
    g_nmod = 1;
    strcpy(g_mod[0].name, "main");
    strcpy(g_mod[0].build_id, which == 2 ? "0000" : MKW_MAIN_BUILD);
    if (which == 0) unsetenv("DM_VK_PICTURE_ASPECT");
    else setenv("DM_VK_PICTURE_ASPECT", which == 1 ? "16:9" : which == 2 || which == 3 ? "21:9" : which == 4 ? "1:1" : "4:3", 1);
    *A = which == 4 ? 1.0 : which == 5 ? 4.0 / 3 : 21.0 / 9;
    *on = which >= 3;
}

static void cam_case(int which) {
    double A; int on;
    setup(which, &A, &on);
    _Alignas(16) unsigned char p[0x100];
    uint64_t r[100];
    float views[] = {1280.0f / 720, 640.0f / 720, 1.0f, 2.4f};   // full, half of a split, a menu's own, another
    for (int reg = 8; reg <= 11; reg += 3) for (int v = 0; v < 4; v++) {   // x8: the layout layers' camera list, x11: the 3D world's
        double half = 22.5 * M_PI / 180;
        memset(p, 0, sizeof p);
        ws_wrf((uintptr_t)p + P_FOVY, 22.5f); ws_wrf((uintptr_t)p + P_SIN, (float)sin(half)); ws_wrf((uintptr_t)p + P_COS, (float)cos(half)); ws_wrf((uintptr_t)p + P_TAN, (float)tan(half));
        memset(r, 0, sizeof r);
        r[reg] = (uintptr_t)p;
        *(float *)&r[32] = views[v];
        callbacks[OBS_MKW_WIDE](r, reg);
        float out = *(float *)&r[32];
        int widened = on && v < 2 && (reg != 8 || A > K169);   // a layout camera keeps 16:9 in a narrower picture
        if (!widened) { assert(out == views[v]); assert(fabs(ws_rdf((uintptr_t)p + P_TAN) - tan(half)) < 1e-6); continue; }
        assert(fabs(out - views[v] * A / K169) < 1e-5);
        float tn = ws_rdf((uintptr_t)p + P_TAN);
        if (A > K169) assert(fabs(tn - tan(half)) < 1e-6 && ws_rdf((uintptr_t)p + P_FOVY) == 22.5f);   // Hor+: the vertical field stays
        else {
            assert(fabs(tn * out - tan(half) * views[v]) < 1e-4);   // the horizontal reach stays (Vert+)
            assert(fabs(ws_rdf((uintptr_t)p + P_SIN) - sin(atan(tn))) < 1e-5 && fabs(ws_rdf((uintptr_t)p + P_COS) - cos(atan(tn))) < 1e-5);
            float again = tn;   // a second store on the same projection (the game did not rewrite tan) must not scale again
            *(float *)&r[32] = views[v];
            callbacks[OBS_MKW_WIDE](r, reg);
            assert(ws_rdf((uintptr_t)p + P_TAN) == again);
        }
    }
    // null / unaligned projection and nonsense aspects: the store alone is changed or nothing
    memset(r, 0, sizeof r); *(float *)&r[32] = (float)K169; callbacks[OBS_MKW_WIDE](r, 8);
    memset(r, 0, sizeof r); *(float *)&r[32] = NAN; r[8] = 1; callbacks[OBS_MKW_WIDE](r, 8);
    assert(isnan(*(float *)&r[32]));
}

static void ui_case(int which) {
    double A; int on;
    setup(which, &A, &on);
    float proj[16] = {1.358f, 0, 0, 0, 0, 2.414f, 0, 0, 0, 0, -1.0002f, 1301.94f, 0, 0, -1, 1303.68f};
    _Alignas(16) unsigned char di[0x80];
    memset(di, 0, sizeof di);
    memcpy(di + 0x10, proj, sizeof proj);
    float keep[16]; memcpy(keep, proj, sizeof proj);
    // pane: global matrix (identity scale) and size, size = mtx - 0x20; small pane then full-canvas pane
    _Alignas(16) unsigned char pane[0x200];
    memset(pane, 0, sizeof pane);
    float *g = (float *)(pane + 0x70), *sz = (float *)(pane + 0x50);
    g[0] = g[5] = 1; sz[0] = 300; sz[1] = 200;
    float sq[16]; memcpy(sq, proj, sizeof proj);   // the squeezed matrix
    for (int k = 0; k < 4; k++) {
        if (A > K169) sq[k] *= (float)(K169 / A);
        if (A < K169) sq[4 + k] *= (float)(A / K169);
    }
    // each pass twice: the first squeezes the DrawInfo's own matrix, the second finds it squeezed and must not squeeze again
    for (int pass = 0; pass < 2; pass++) for (int text = 0; text < 2; text++) for (int fullpane = 0; fullpane < 2; fullpane++) {
        sz[0] = fullpane ? 1920 : 300; sz[1] = fullpane ? 1080 : 200;
        uint64_t r[100] = {0};
        r[0] = (uintptr_t)di;
        if (text) r[21] = (uintptr_t)pane; else { r[23] = (uintptr_t)pane + 0x70; r[22] = (uintptr_t)pane + 0x50; }
        callbacks[OBS_MKW_WIDE](r, (text ? 2 : 1) << 5);
        const float *own = (const float *)(di + 0x10);
        for (int k = 0; k < 16; k++) assert(fabs(own[k] - (on ? sq[k] : keep[k])) < 1e-4);
        if (!on || !fullpane) { assert(r[0] == (uintptr_t)di); continue; }
        assert(r[0] != (uintptr_t)di);   // a full-canvas pane: a stand-in with the whole picture's matrix
        const float *o = (const float *)(uintptr_t)(r[0] + 0x10);
        for (int k = 0; k < 16; k++) assert(fabs(o[k] - keep[k]) < 1e-3);
    }
    // not a 16:9 projection (a menu's own): untouched
    float other[16]; memcpy(other, proj, sizeof proj); other[5] = 1.358f;
    memcpy(di + 0x10, other, sizeof other);
    sz[0] = 300; sz[1] = 200;
    uint64_t r[100] = {0}; r[0] = (uintptr_t)di; r[23] = (uintptr_t)pane + 0x70; r[22] = (uintptr_t)pane + 0x50;
    callbacks[OBS_MKW_WIDE](r, 1 << 5);
    assert(r[0] == (uintptr_t)di);
    r[0] = 0; callbacks[OBS_MKW_WIDE](r, 1 << 5);
    // rows site: q0 and q4 after the ldp
    memcpy(di + 0x10, proj, sizeof proj);
    memset(r, 0, sizeof r); r[21] = (uintptr_t)di;
    memcpy(&r[32], proj, 16); memcpy(&r[40], proj + 4, 16);
    callbacks[OBS_MKW_WIDE](r, 3 << 5);
    float *q0 = (float *)&r[32], *q4 = (float *)&r[40];
    for (int k = 0; k < 4; k++) {
        float w0 = proj[k], w4 = proj[4 + k];
        if (on && A > K169) w0 *= (float)(K169 / A);
        if (on && A < K169) w4 *= (float)(A / K169);
        assert(fabs(q0[k] - w0) < 1e-4 && fabs(q4[k] - w4) < 1e-4);
    }
    // the DrawInfo is squeezed with them, and a later load of its rows is not scaled again
    for (int k = 0; k < 16; k++) assert(fabs(((const float *)(di + 0x10))[k] - (on ? sq[k] : keep[k])) < 1e-4);
    float again[8]; memcpy(again, &r[32], 16); memcpy(again + 4, &r[40], 16);
    memset(r, 0, sizeof r); r[21] = (uintptr_t)di;
    memcpy(&r[32], again, 16); memcpy(&r[40], again + 4, 16);
    callbacks[OBS_MKW_WIDE](r, 3 << 5);
    assert(!memcmp(&r[32], again, 16) && !memcmp(&r[40], again + 4, 16));
}

int main(void) {
    for (int i = 0; i < 6; i++) {
        pid_t c = fork(); assert(c >= 0);
        if (!c) { cam_case(i); ui_case(i); _exit(0); }
        int st; assert(waitpid(c, &st, 0) == c);
        if (!WIFEXITED(st) || WEXITSTATUS(st)) { fprintf(stderr, "mkw wide case %d failed\n", i); return 1; }
    }
    puts("mkw wide: 6 shape cases (opt-in, build, 21:9, 1:1, 4:3) of camera, 2D layer and rows passed");
    return 0;
}
