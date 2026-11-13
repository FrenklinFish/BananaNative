// Camera callback regression checks: opt-in/build gates, horizontal reach,
// copied render cameras, orthographic exclusion and repeated projection builds.
#include "../android/pkp_aspect.c"
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

static void submit(void *camera) {
    uint64_t r[31] = {0};
    r[20] = (uintptr_t)camera;
    callbacks[OBS_PKP_CAM_SUBMIT](r, 0);
}
static void build(void *camera) {
    uint64_t r[31] = {0};
    r[0] = (uintptr_t)camera;
    callbacks[OBS_PKP_PROJ_BUILD](r, 0);
}
static void done(void *camera) {
    uint64_t r[31] = {0};
    r[19] = (uintptr_t)camera;
    callbacks[OBS_PKP_PROJ_DONE](r, 0);
}
static void projection(void *camera) {
    uint64_t a = (uintptr_t)camera;
    float p11 = 1.0f / tanf(rdf(a + CAM_FOV) * 0.5f);
    wrf(a + CAM_PROJ + 20, p11);
    wrf(a + CAM_PROJ, p11 / (float)K169);
}

static void check(int which) {
    static const char *invalid[] = {NULL, "0", "1.109756oops", "nan", "inf", "-1", "1.777778", "1e999"};
    _Alignas(16) unsigned char camera[384] = {0}, copy[384], before[384];
    uint64_t a = (uintptr_t)camera;
    g_nmod = 1;
    strcpy(g_mod[0].name, "main");
    strcpy(g_mod[0].build_id, "76a489f3bd3642fa5e18bd83d32c98ba");
    if (which < 8) {
        if (invalid[which]) setenv("DM_PKP_ASPECT", invalid[which], 1);
        else unsetenv("DM_PKP_ASPECT");
    } else setenv("DM_PKP_ASPECT", "1.109756", 1);
    if (which == 8) g_mod[0].build_id[0] = '0';
    float original_fov = (float)(45.0 / 57.29577951308232);
    wrf(a + CAM_FOV, original_fov);
    wrf(a + CAM_ASPECT, (float)K169);
    if (which == 9) {
        uint32_t orthographic = 1;
        memcpy(camera + CAM_FLAGS, &orthographic, 4);
    }
    projection(camera);
    memcpy(before, camera, sizeof camera);
    submit(camera);
    build(camera);
    done(camera);
    if (which < 10) {
        assert(!memcmp(before, camera, sizeof camera));
        return;
    }

    float corrected = rdf(a + CAM_FOV);
    double expected = 2 * atan(tan(original_fov * 0.5) * K169 / 1.109756);
    assert(fabs(corrected - expected) < 1e-6);
    assert(rdf(a + CAM_ASPECT) == (float)K169);
    projection(camera);
    done(camera);
    float expected_p00 = (float)(1.0 / tan(original_fov * 0.5) / K169);
    assert(fabsf(rdf(a + CAM_PROJ) - expected_p00) < 1e-5f);

    // The game can copy the camera without submitting the copy again.
    memcpy(copy, camera, sizeof copy);
    projection(copy);
    done(copy);
    assert(fabsf(rdf((uintptr_t)copy + CAM_PROJ) - expected_p00) < 1e-5f);

    for (int i = 0; i < 1024; i++) {
        // A live environment edit cannot discard provenance and apply FOV twice.
        if (which == 11 && i == 100) setenv("DM_PKP_ASPECT", "0", 1);
        if (which == 11 && i == 200) setenv("DM_PKP_ASPECT", "1.109756", 1);
        build(camera);
        projection(camera);
        done(camera);
        assert(rdf(a + CAM_FOV) == corrected);
        assert(fabsf(rdf(a + CAM_PROJ) - expected_p00) < 1e-5f);
    }
    // A changed game-owned FOV (zoom/cutscene) is converted once again.
    wrf(a + CAM_FOV, original_fov * 0.8f);
    build(camera);
    assert(fabs(rdf(a + CAM_FOV) - 2 * atan(tan(original_fov * 0.8f * 0.5) * K169 / 1.109756)) < 1e-6);
    if (which == 13) {
        // A copy retains the old corrected FOV after its original zooms. It
        // needs the P00 fix and must not be corrected again if submitted later.
        projection(copy);
        done(copy);
        assert(fabsf(rdf((uintptr_t)copy + CAM_PROJ) - expected_p00) < 1e-5f);
        submit(copy);
        build(copy);
        projection(copy);
        done(copy);
        assert(rdf((uintptr_t)copy + CAM_FOV) == corrected);
        assert(fabsf(rdf((uintptr_t)copy + CAM_PROJ) - expected_p00) < 1e-5f);
    }
    if (which == 12) {
        // An unchanged camera must survive another camera churning the recent
        // FOV history; its game-owned render copy must still be recognized.
        float keep_fov = rdf(a + CAM_FOV);
        float keep_p00 = (float)(1.0 / tan(original_fov * 0.8f * 0.5) / K169);
        memcpy(copy, camera, sizeof copy);
        _Alignas(16) unsigned char other[384] = {0};
        uint64_t b = (uintptr_t)other;
        wrf(b + CAM_ASPECT, (float)K169);
        submit(other);
        for (int i = 0; i < 40; i++) {
            wrf(b + CAM_FOV, 0.2f + i * 0.02f);
            build(other);
            projection(other);
            done(other);
        }
        build(camera);
        projection(camera);
        done(camera);
        assert(rdf(a + CAM_FOV) == keep_fov);
        assert(fabsf(rdf(a + CAM_PROJ) - keep_p00) < 1e-5f);
        projection(copy);
        done(copy);
        assert(fabsf(rdf((uintptr_t)copy + CAM_PROJ) - keep_p00) < 1e-5f);
    }

}

int main(void) {
    for (int i = 0; i < 14; i++) {
        pid_t child = fork();
        assert(child >= 0);
        if (!child) { check(i); _exit(0); }
        int status;
        assert(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) {
            fprintf(stderr, "pkp aspect case %d failed\n", i);
            return 1;
        }
    }
    puts("pkp aspect: 14 opt-in, build, camera and startup cases passed");
    return 0;
}
