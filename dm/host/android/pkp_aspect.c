// SPDX-License-Identifier: GPL-3.0-or-later
// Pokopia camera aspect adjustment, adapted from Recompia's
// runtime/games/pkp/render/pkp_aspect.cpp (GPL-3.0-or-later).
// Source: https://github.com/Dittobyte/Recompia/tree/75dae2f2ccdada74e6b8ba12ea7de30560a84221
// The Android adaptation preserves the game's 16:9 aspect field and corrects P00 after the builder.
// Camera-only: UI anchoring and canvas changes are deliberately excluded.
//
// KTGL's camera (init main+0x1f12368): projection rows +192, vertical FOV
// (radians) +328, aspect +332, flags +344 (bit 0: orthographic).
// patches.py pins the three observation gates to main build 76a489f3...:
//   +0x8cc128, x20: remember a scene camera handed to the render view.
//   +0x1f12130, x0: before the builder reads the fields, increase vertical FOV.
//   +0x1f12354, x19: after the builder writes P, restore horizontal reach.
//
// The game copies the scene camera into a secondary render camera. Remember
// corrected FOV values too, so that copy receives the same projection fix.
// The aspect FIELD stays 16:9: terrain visibility and LOD derive from it.
// Changing that field to the display aspect made terrain cells disappear.
//
// DM_PKP_ASPECT=<picture width/height> is opt-in and read once per game process.
// Changing it requires a restart: live changes cannot safely restore cameras
// that the game has copied or stopped rebuilding. Default/off never writes a
// camera. The picture may use DM_VK_PICTURE_ASPECT; this does not fix the UI.
#include "hle.h"
#include "dm.h"
#include <math.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define K169 (16.0 / 9.0)
enum { CAM_PROJ = 192, CAM_FOV = 328, CAM_ASPECT = 332, CAM_FLAGS = 344, NCAM = 8, NPUT = 32 };

static _Atomic uint64_t g_cam[NCAM];
static _Atomic uint32_t g_cam_fov[NCAM]; // an unchanged scene camera never loses provenance
static _Atomic uint32_t g_put[NPUT];   // corrected FOV bit patterns, including copies
static _Atomic unsigned g_put_at, g_puts;
static pthread_once_t g_config_once = PTHREAD_ONCE_INIT;
static double g_aspect;

static int close_to(double a, double b) { return fabs(a - b) < 0.02; }
static float rdf(uint64_t a) { float f; memcpy(&f, (void *)a, 4); return f; }
static void wrf(uint64_t a, float f) { memcpy((void *)a, &f, 4); }
static uint32_t rdu(uint64_t a) { uint32_t u; memcpy(&u, (void *)a, 4); return u; }

static void config_init(void) {
    const char *e = getenv("DM_PKP_ASPECT");
    if (!e || !*e) return;
    char *end;
    errno = 0;
    double a = strtod(e, &end);
    if (end == e || *end || errno == ERANGE || !isfinite(a) || a <= 0.4 || a >= K169 - 0.02) return;
    g_aspect = a;
    LOG("pkp aspect: startup %.6f (restart to change; world camera only)", a);
}

static double aspect(void) {
    if (strcmp(dm_main_build(), "76a489f3bd3642fa5e18bd83d32c98ba")) return 0;
    pthread_once(&g_config_once, config_init);
    return g_aspect;
}

static int cam_index(uint64_t cam) {
    for (int i = 0; i < NCAM; i++)
        if (atomic_load_explicit(&g_cam[i], memory_order_relaxed) == cam) return i;
    return -1;
}
static void remember(uint64_t cam) {
    if (cam_index(cam) >= 0) return;
    for (int i = 0; i < NCAM; i++) {
        uint64_t z = 0;
        if (atomic_compare_exchange_strong(&g_cam[i], &z, cam)) return;
    }
}
static int ours(uint32_t fb) {
    if (!fb) return 0;
    for (int i = 0; i < NCAM; i++)
        if (atomic_load_explicit(&g_cam_fov[i], memory_order_relaxed) == fb) return 1;
    for (int i = 0; i < NPUT; i++)
        if (atomic_load_explicit(&g_put[i], memory_order_relaxed) == fb) return 1;
    return 0;
}
static void put(uint32_t fb) {
    // Deduplicate only the recent history: the scene slot already holds fb,
    // but its game-owned copies need this value after the scene zoom changes.
    for (int i = 0; i < NPUT; i++)
        if (atomic_load_explicit(&g_put[i], memory_order_relaxed) == fb) return;
    atomic_store_explicit(&g_put[atomic_fetch_add(&g_put_at, 1) % NPUT], fb, memory_order_relaxed);
}

static void cam_submit(uint64_t *r, int param) {
    (void)param;
    if (!aspect() || !r[20]) return;
    remember(r[20]);
}

// A scene camera's original FOV becomes taller. One already corrected is left
// alone, including when the game submits its copied render camera again.
static void proj_build(uint64_t *r, int param) {
    (void)param;
    double a = aspect();
    uint64_t cam = r[0];
    int index = cam_index(cam);
    if (!a || !cam || index < 0 || (rdu(cam + CAM_FLAGS) & 1)) return;
    float f = rdf(cam + CAM_FOV);
    uint32_t fb = rdu(cam + CAM_FOV);
    if (ours(fb)) {
        atomic_store_explicit(&g_cam_fov[index], fb, memory_order_relaxed);
        return;
    }
    if (!(f > 0.05f && f < 3.0f) || !close_to(rdf(cam + CAM_ASPECT), K169)) return;
    float nf = (float)(2.0 * atan(tan(f * 0.5) * K169 / a));
    wrf(cam + CAM_FOV, nf);
    fb = rdu(cam + CAM_FOV);
    atomic_store_explicit(&g_cam_fov[index], fb, memory_order_relaxed);
    put(fb);
    if (atomic_fetch_add_explicit(&g_puts, 1, memory_order_relaxed) == 0)
        LOG("pkp aspect %.4f: camera %d FOV %.1f -> %.1f deg vertical",
            a, cam_index(cam), f * 57.29578, nf * 57.29578);
}

// Restore the original horizontal reach for a corrected scene camera or its
// game-owned copy. The view-projection multiplication follows this gate.
static void proj_done(uint64_t *r, int param) {
    (void)param;
    double a = aspect();
    uint64_t cam = r[19];
    if (!a || !cam || (rdu(cam + CAM_FLAGS) & 1) || !ours(rdu(cam + CAM_FOV)) || !close_to(rdf(cam + CAM_ASPECT), K169)) return;
    float *p = (float *)(cam + CAM_PROJ);
    if (!isfinite(p[0]) || !isfinite(p[5]) || !(fabsf(p[5]) > 1e-6f)) return;
    p[0] = copysignf((float)(fabsf(p[5]) / a), p[0]);
}

__attribute__((constructor)) static void pkp_aspect_attach(void) {
    gate_obs_set(OBS_PKP_CAM_SUBMIT, cam_submit);
    gate_obs_set(OBS_PKP_PROJ_BUILD, proj_build);
    gate_obs_set(OBS_PKP_PROJ_DONE, proj_done);
}
