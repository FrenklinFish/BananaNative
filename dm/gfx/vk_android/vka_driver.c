// Custom GPU drivers: DM_VK_DRIVER names a Vulkan driver the launcher installed into the app's private files (android/README.md "Custom GPU drivers"), such as a Mesa Turnip
// build or another Qualcomm release, and the replay runs on it instead of the system's. The driver is loaded as Eden does it, through libadrenotools (BSD-2-Clause,
// dm/third_party/libadrenotools): a private copy of the system libvulkan.so (the loader) in a linker namespace of its own whose hook answers the loader's request for the
// vendor driver with the custom one. libadrenotools is a library of its own, libnxadreno.so, opened here only when a driver is named: its constructor (liblinkernsbypass)
// changes dlopen's page protection, which the system driver path must not see.
//   DM_VK_DRIVER=<folder>   the folder holds the driver and its meta.json ("libraryName": the .so to load), as the driver zips Eden and others use
//   DM_VK_DRIVER=<path>.so  that library (its folder must hold whatever else it loads)
//   unset, empty, "system"  the system libvulkan.so, as before
// DM_VK_HOOKDIR overrides where the hook libraries are (by default the folder libnxhost.so was loaded from, inside the APK). Restart only (vka_ctl.c g_restart).
// A driver that fails to load falls back to the system one, with a [RED] line saying why.
#if defined(__ANDROID__)
#include "vka.h"
#include <ctype.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define ADRENOTOOLS_DRIVER_CUSTOM 1   // dm/third_party/libadrenotools/include/adrenotools/priv.h
typedef void *(*open_libvulkan_fn)(int dlopen_mode, int feature_flags, const char *tmp_lib_dir, const char *hook_lib_dir, const char *custom_driver_dir,
                                   const char *custom_driver_name, const char *file_redirect_dir, void **user_mapping_handle);

static char g_tag[96];   // the custom driver in use (file-name safe), empty for the system's

const char *vka_driver_tag(void) { return g_tag[0] ? g_tag : NULL; }

// "libraryName" from a driver folder's meta.json: the one string value the loader needs (a small JSON file; no parser for one key)
static int meta_library(const char *dir, char *out, size_t cap) {
    char p[1024]; snprintf(p, sizeof p, "%s/meta.json", dir);
    FILE *f = fopen(p, "rb"); if (!f) return 0;
    char buf[8192]; size_t n = fread(buf, 1, sizeof buf - 1, f); fclose(f); buf[n] = 0;
    char *k = strstr(buf, "\"libraryName\""); if (!k) return 0;
    char *c = strchr(k + 13, ':'); if (!c) return 0;
    char *q = strchr(c, '"'); if (!q) return 0;
    char *e = strchr(q + 1, '"'); if (!e || (size_t)(e - q - 1) >= cap || e == q + 1) return 0;
    memcpy(out, q + 1, (size_t)(e - q - 1)); out[e - q - 1] = 0;
    return !strchr(out, '/');
}

static void set_tag(const char *s) {
    size_t n = 0;
    for (; *s && n + 1 < sizeof g_tag; s++) g_tag[n++] = isalnum((unsigned char)*s) || *s == '-' || *s == '_' || *s == '.' ? *s : '_';
    g_tag[n] = 0;
}

// Opened once per process: the readiness check (vka_readiness.c) may open it before the replay's vka_init does, and both get the same library (adrenotools would
// otherwise load the custom driver into a second namespace)
static void *driver_open(void);
void *vka_driver_open(void) {
    static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER; static int tried; static void *lib;
    pthread_mutex_lock(&mu);
    if (!tried) { tried = 1; lib = driver_open(); }
    pthread_mutex_unlock(&mu);
    return lib;
}
// Adreno 740: the texture unit's UBWC flag hint (TPL1_DBG_ECO_CNTL1.TP_UBWC_FLAG_HINT) is one register for every driver on the GPU, and it has to match the system
// driver's: Qualcomm's v6xx drivers leave it off, v7xx ones set it. Mesa's default for the 740 is off (most 740 phones shipped v6xx), so Turnip next to a v7xx
// system driver flips it under SurfaceFlinger, and what the system then composites from compressed buffers comes out garbled: the picture flickers while Android
// composites on the GPU (a screenshot, an overlay). So Turnip on a 740 gets FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=<1 or 0> from the system driver's version
// (the "V@0863" of libGLESv2_adreno.so: 863, at least 700), as DroidDeck does (its #963). The environment's own FD_DEV_FEATURES wins; DM_VK_TU_UBWC_HINT=0 leaves it alone
static int adreno_system_version(void) {   // the system driver's version (863 for 512.863), 0 when not found
    int fd = open("/vendor/lib64/libGLESv2_adreno.so", O_RDONLY | O_CLOEXEC); if (fd < 0) return 0;
    static char buf[1 << 20]; size_t keep = 0; int v = 0; ssize_t n;
    while (!v && (n = read(fd, buf + keep, sizeof buf - keep)) > 0) {
        size_t len = keep + (size_t)n;
        for (size_t i = 0; i + 7 <= len && !v; i++)
            if (buf[i] == 'V' && buf[i + 1] == '@' && isdigit((unsigned char)buf[i + 2]) && isdigit((unsigned char)buf[i + 5])) v = atoi(buf + i + 2);
        keep = len < 8 ? len : 8; memmove(buf, buf + len - keep, keep);
    }
    close(fd); return v;
}
static char g_ubwc_note[200];   // what the hint did, logged at device creation (vka_driver_notes): the driver is opened before the game's log is
static void ubwc_hint(void) {
    if (getenv("FD_DEV_FEATURES") || !VKA_ENV("DM_VK_TU_UBWC_HINT", 1)) { snprintf(g_ubwc_note, sizeof g_ubwc_note, "Turnip: FD_DEV_FEATURES %s", getenv("FD_DEV_FEATURES") ? "set by the environment, kept" : "left alone (DM_VK_TU_UBWC_HINT=0)"); return; }
    char m[64] = ""; int fd = open("/sys/class/kgsl/kgsl-3d0/gpu_model", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { ssize_t n = read(fd, m, sizeof m - 1); close(fd); if (n > 0) m[n] = 0; }
    if (strncmp(m, "Adreno740", 9)) { snprintf(g_ubwc_note, sizeof g_ubwc_note, "Turnip: not an Adreno 740 (gpu_model \"%s\"): FD_DEV_FEATURES left alone", m); return; }
    int v = adreno_system_version();
    if (!v) { snprintf(g_ubwc_note, sizeof g_ubwc_note, "Turnip on an Adreno 740: the system driver's version was not found, FD_DEV_FEATURES left alone"); return; }
    setenv("FD_DEV_FEATURES", v >= 700 ? "enable_tp_ubwc_flag_hint=1" : "enable_tp_ubwc_flag_hint=0", 0);
    snprintf(g_ubwc_note, sizeof g_ubwc_note, "Turnip on an Adreno 740 (%s), system driver V@%04d: FD_DEV_FEATURES=%s (DM_VK_TU_UBWC_HINT)", m, v, getenv("FD_DEV_FEATURES"));
}
void vka_driver_notes(void) { if (g_ubwc_note[0]) { VLOG("%s", g_ubwc_note); g_ubwc_note[0] = 0; } }   // at device creation, once the log is open

static void *driver_open(void) {
    const char *drv = getenv("DM_VK_DRIVER");
    if (!drv || !*drv || !strcmp(drv, "system")) return NULL;
    char dir[1024], name[256];
    size_t l = strlen(drv);
    if (l > 3 && !strcmp(drv + l - 3, ".so")) {
        const char *sl = strrchr(drv, '/');
        if (!sl || (size_t)(sl - drv) + 2 > sizeof dir || strlen(sl + 1) >= sizeof name) { VRED("DM_VK_DRIVER=%s: not a full path: the system driver is used", drv); return NULL; }
        snprintf(dir, sizeof dir, "%.*s/", (int)(sl - drv), drv); snprintf(name, sizeof name, "%s", sl + 1);
    } else {
        snprintf(dir, sizeof dir, "%s%s", drv, drv[l - 1] == '/' ? "" : "/");
        if (!meta_library(drv, name, sizeof name)) { VRED("DM_VK_DRIVER=%s: no meta.json with a libraryName there: the system driver is used", drv); return NULL; }
    }
    if (!strncmp(dir, "/sdcard/", 8) || !strncmp(dir, "/storage/", 9))   // shared storage is mounted without exec: dlopen fails there (adrenotools' own note)
        VRED("DM_VK_DRIVER=%s is on shared storage, where libraries cannot be loaded: install the driver from the launcher (it goes into the app's private files)", drv);
    char hook[1024];
    const char *hd = getenv("DM_VK_HOOKDIR");
    if (hd && *hd) snprintf(hook, sizeof hook, "%s%s", hd, hd[strlen(hd) - 1] == '/' ? "" : "/");
    else {   // the folder libnxhost.so came from: inside the APK ("…/base.apk!/lib/arm64-v8a"), where the hook libraries are beside it; the linker searches zip paths too
        Dl_info di; const char *sl;
        if (!dladdr((void *)vka_driver_open, &di) || !di.dli_fname || !(sl = strrchr(di.dli_fname, '/'))) { VRED("DM_VK_DRIVER: cannot tell where libnxhost.so is: the system driver is used"); return NULL; }
        snprintf(hook, sizeof hook, "%.*s/", (int)(sl - di.dli_fname), di.dli_fname);
    }
    if (!strncmp(name, "vulkan.ad0", 10)) ubwc_hint();   // Turnip (Mesa's Adreno driver): before the driver reads FD_DEV_FEATURES at its first device
    void *at = dlopen("libnxadreno.so", RTLD_NOW | RTLD_LOCAL);
    open_libvulkan_fn open_vk = at ? (open_libvulkan_fn)dlsym(at, "adrenotools_open_libvulkan") : NULL;
    if (!open_vk) { VRED("DM_VK_DRIVER: libnxadreno.so not loaded (%s): the system driver is used", dlerror()); return NULL; }
    void *lib = open_vk(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, NULL, hook, dir, name, NULL, NULL);
    if (!lib) { VRED("DM_VK_DRIVER: adrenotools could not load %s%s (hooks in %s; logcat tags adrenotools / hook_impl say why): the system driver is used", dir, name, hook); return NULL; }
    const char *base = dir + strlen(dir) - 1; while (base > dir && base[-1] != '/') base--;   // the folder's name, else the library's
    char b[128]; snprintf(b, sizeof b, "%.*s", (int)(dir + strlen(dir) - 1 - base), base);
    set_tag(b[0] ? b : name);
    VLOG("custom GPU driver: %s%s (hooks in %s), pipeline cache vk_pipelines-%s" VKA_PCACHE_GEN ".bin", dir, name, hook, g_tag);
    return lib;
}
#endif
