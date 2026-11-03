// The launcher's JNI shim (dev.nxnative.android.Native): starts the host's nxhost_main on a thread of its own and carries the host's output to a log file and to logcat.
// It also hands the game screen's Surface, the input events and the Activity lifecycle on to the host, and runs the Vulkan selftest, through the C entry points libnxhost.so
// exports (dm/host/android/input_android.h, dm/gfx/vk_android/vk_android.h). They are weak, so a host without one still loads, and the call does nothing (android/README.md).
#include <android/log.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>

#define TAG "nxnative"
#define HOST_STACK (8u << 20)   // guest code runs on this thread (main.c guest_main): a deep stack, as the Mac's main thread has

int nxhost_main(int argc, char **argv);

// ---- The host's launcher interface: the headers are the host's own, so a signature change there fails here at compile time; every entry point is weak ----
#include "input_android.h"
#include "vk_android.h"
#pragma weak nxhost_set_window
#pragma weak nxhost_vk_selftest
#pragma weak nxhost_input_device_added
#pragma weak nxhost_input_device_removed
#pragma weak nxhost_input_key
#pragma weak nxhost_input_axes
#pragma weak nxhost_input_touch
#pragma weak nxhost_touch_layout
#pragma weak nxhost_touch_controls_enable
#pragma weak nxhost_touch_controls_set
#pragma weak nxhost_touch_controls_state
#pragma weak nxhost_motion_start
#pragma weak nxhost_motion_stop
#pragma weak nxhost_motion_rotation
#pragma weak nxhost_lifecycle
#pragma weak nxhost_paused
#pragma weak nxhost_audio_interrupt
#pragma weak nxhost_audio_route_changed
#pragma weak nxhost_fps
#pragma weak nxhost_framegen_set
#pragma weak nxhost_framegen_status
#pragma weak nxhost_picture_place
#pragma weak nxhost_fps_cap_set
#pragma weak nxhost_fps_cap_status

// ---- Log: the host's stdout and stderr go straight into the log file (nothing is lost when the process dies), and a thread copies new lines to logcat ----
// At the end of the file the thread sleeps until the file is written (inotify IN_MODIFY), not on a 20 ms timer: the timer woke it 50 times a second for the whole game,
// though the host writes a few lines a second once a level runs (power: every wakeup of an idle core costs). Without inotify it falls back to the timer
static int g_log_fd = -1;
static char g_log_path[1024];
static void log_wait(int in) {
    if (in >= 0) { char ev[512]; if (read(in, ev, sizeof ev) > 0 || errno == EINTR) return; }   // blocks until a write (several events in one read)
    struct timespec ts = {0, 20000000}; nanosleep(&ts, NULL);
}
static void *logcat_tail(void *p) {
    (void)p; prctl(PR_SET_NAME, "nxlog");
    int fd = open(g_log_path, O_RDONLY | O_CLOEXEC); if (fd < 0) return NULL;
    int in = inotify_init1(IN_CLOEXEC);   // (blocking reads)
    if (in >= 0 && inotify_add_watch(in, g_log_path, IN_MODIFY) < 0) { close(in); in = -1; }
    char buf[8192]; size_t have = 0;
    for (;;) {
        ssize_t n = read(fd, buf + have, sizeof buf - 1 - have);
        if (n <= 0) { log_wait(in); continue; }   // (a write between the read and the wait leaves an event: the wait returns at once)
        have += (size_t)n; buf[have] = 0;
        char *s = buf, *e;
        while ((e = memchr(s, '\n', have - (size_t)(s - buf)))) {
            *e = 0;
            int prio = strstr(s, "FATAL") || strstr(s, "CRASH") ? ANDROID_LOG_ERROR : strstr(s, "[RED]") ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
            __android_log_write(prio, TAG, s);
            s = e + 1;
        }
        have -= (size_t)(s - buf);
        if (have == sizeof buf - 1) { __android_log_write(ANDROID_LOG_INFO, TAG, buf); have = 0; }   // a line longer than the buffer goes out in pieces
        else memmove(buf, s, have);
    }
    return NULL;
}
static int log_open(const char *path) {
    if (g_log_fd >= 0) return 0;   // one log per process
    snprintf(g_log_path, sizeof g_log_path, "%s", path);
    g_log_fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND | O_CLOEXEC, 0644);
    if (g_log_fd < 0) { __android_log_print(ANDROID_LOG_ERROR, TAG, "cannot open the log %s: %s", path, strerror(errno)); return -1; }
    fflush(stdout); fflush(stderr);
    dup2(g_log_fd, 1); dup2(g_log_fd, 2);
    setvbuf(stdout, NULL, _IOLBF, 0); setvbuf(stderr, NULL, _IONBF, 0);
    pthread_t t; if (!pthread_create(&t, NULL, logcat_tail, NULL)) pthread_detach(t);
    return 0;
}

// ---- The host thread ----
static struct { int argc; char **argv; } g_run;
static _Atomic int g_started;
static void *host_thread(void *p) {
    (void)p; prctl(PR_SET_NAME, "nxhost");
    fprintf(stderr, "[nxlaunch] nxhost_main starts on its own thread (%u MiB stack)\n", HOST_STACK >> 20);
    int rc = nxhost_main(g_run.argc, g_run.argv);
    fprintf(stderr, "[nxlaunch] nxhost_main returned %d: the game ended\n", rc);
    struct timespec ts = {0, 300000000}; nanosleep(&ts, NULL);   // logcat_tail's last lines
    _exit(rc);   // the host cannot be started twice in one process: the game process ends with it
}
static char *jstr(JNIEnv *env, jstring s) { const char *c = (*env)->GetStringUTFChars(env, s, NULL); char *r = strdup(c ? c : ""); if (c) (*env)->ReleaseStringUTFChars(env, s, c); return r; }

// start(args, env, log path, cwd): args[0] is the program name (main.c reads args[1] as the pack folder), env is KEY=VALUE strings. 0 = started; the host runs on
JNIEXPORT jint JNICALL Java_dev_nxnative_android_Native_start(JNIEnv *env, jclass cls, jobjectArray args, jobjectArray envs, jstring log, jstring cwd) {
    (void)cls;
    if (atomic_exchange(&g_started, 1)) return -1;
    char *lp = jstr(env, log); int lr = log_open(lp); free(lp);
    if (lr) return -2;
    for (jsize i = 0, n = (*env)->GetArrayLength(env, envs); i < n; i++) {   // the build first: every log says which build it came from
        char *kv = jstr(env, (jstring)(*env)->GetObjectArrayElement(env, envs, i));
        if (!strncmp(kv, "NX_BUILD_TAG=", 13)) fprintf(stderr, "[nxlaunch] build %s\n", kv + 13);
        free(kv);
    }
    for (jsize i = 0, n = (*env)->GetArrayLength(env, envs); i < n; i++) {
        char *kv = jstr(env, (jstring)(*env)->GetObjectArrayElement(env, envs, i)); char *eq = strchr(kv, '=');
        if (eq) { *eq = 0; setenv(kv, eq + 1, 1); fprintf(stderr, "[nxlaunch] env %s=%s\n", kv, eq + 1); }
        free(kv);
    }
    char *wd = jstr(env, cwd); if (chdir(wd)) fprintf(stderr, "[nxlaunch] chdir %s: %s\n", wd, strerror(errno)); free(wd);
    g_run.argc = (*env)->GetArrayLength(env, args);
    g_run.argv = calloc((size_t)g_run.argc + 1, sizeof *g_run.argv);
    for (int i = 0; i < g_run.argc; i++) { g_run.argv[i] = jstr(env, (jstring)(*env)->GetObjectArrayElement(env, args, i)); fprintf(stderr, "[nxlaunch] arg %d: %s\n", i, g_run.argv[i]); }
    fprintf(stderr, "[nxlaunch] hooks: window %s, input %s, lifecycle %s\n", nxhost_set_window ? "yes" : "not in this build", nxhost_input_key ? "yes" : "not in this build",
            nxhost_lifecycle ? "yes" : "not in this build");
    pthread_attr_t a; pthread_attr_init(&a); pthread_attr_setstacksize(&a, HOST_STACK);
    pthread_t t; int r = pthread_create(&t, &a, host_thread, NULL); pthread_attr_destroy(&a);
    if (r) { fprintf(stderr, "[nxlaunch] cannot start the host thread: %s\n", strerror(r)); return -3; }
    pthread_detach(t);
    return 0;
}
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_note(JNIEnv *env, jclass cls, jstring line) {   // a launcher line into the same log
    (void)cls; char *s = jstr(env, line); fprintf(stderr, "[nxlaunch] %s\n", s); free(s);
}

// ---- Surface, input and lifecycle, forwarded when the host has the entry points ----
static ANativeWindow *g_window;
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_setSurface(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    ANativeWindow *w = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    if (nxhost_set_window) nxhost_set_window(w);
    if (g_window) ANativeWindow_release(g_window);   // the host takes its own reference when it keeps the window
    g_window = w;
}
JNIEXPORT jboolean JNICALL Java_dev_nxnative_android_Native_hasInput(JNIEnv *env, jclass cls) { (void)env; (void)cls; return nxhost_input_key != NULL; }
JNIEXPORT jint JNICALL Java_dev_nxnative_android_Native_deviceAdded(JNIEnv *env, jclass cls, jint device, jstring name, jboolean labelled) {
    (void)cls; if (!nxhost_input_device_added) return -1;
    char *n = name ? jstr(env, name) : NULL; int p = nxhost_input_device_added(device, n, labelled); free(n); return p;
}
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_deviceRemoved(JNIEnv *env, jclass cls, jint device) { (void)env; (void)cls; if (nxhost_input_device_removed) nxhost_input_device_removed(device); }
JNIEXPORT jboolean JNICALL Java_dev_nxnative_android_Native_key(JNIEnv *env, jclass cls, jint device, jint keycode, jboolean down) {
    (void)env; (void)cls; return nxhost_input_key ? nxhost_input_key(device, keycode, down) : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_dev_nxnative_android_Native_axes(JNIEnv *env, jclass cls, jint device, jintArray axes, jfloatArray values) {
    (void)cls; if (!nxhost_input_axes) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, axes), m = (*env)->GetArrayLength(env, values); if (m < n) n = m; if (n > 64) n = 64;
    jint a[64]; jfloat v[64];
    (*env)->GetIntArrayRegion(env, axes, 0, n, a); (*env)->GetFloatArrayRegion(env, values, 0, n, v);
    return nxhost_input_axes(device, (const int *)a, v, n);
}
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_touch(JNIEnv *env, jclass cls, jint action, jint pointer, jfloat x, jfloat y) {
    (void)env; (void)cls; if (nxhost_input_touch) nxhost_input_touch(action, pointer, x, y);
}
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_touchLayout(JNIEnv *env, jclass cls, jfloat vw, jfloat vh, jfloat px, jfloat py, jfloat pw, jfloat ph) {
    (void)env; (void)cls; if (nxhost_touch_layout) nxhost_touch_layout(vw, vh, px, py, pw, ph);
}
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_picturePlace(JNIEnv *env, jclass cls, jfloat x, jfloat y) {
    (void)env; (void)cls; if (nxhost_picture_place) nxhost_picture_place(x, y);
}
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_touchControlsEnable(JNIEnv *env, jclass cls, jboolean on) {
    (void)env; (void)cls; if (nxhost_touch_controls_enable) nxhost_touch_controls_enable(on);
}
// The launcher's layout (TouchControlsView): six floats per control, kind, buttons, stick, x, y, r (input_android.h). The number the host kept; -1 = this host draws none
JNIEXPORT jint JNICALL Java_dev_nxnative_android_Native_touchControlsSet(JNIEnv *env, jclass cls, jfloatArray a) {
    (void)cls; if (!nxhost_touch_controls_set || !nxhost_touch_controls_state) return -1;
    int n = a ? (*env)->GetArrayLength(env, a) / 6 : 0; if (n > NXT_MAX) n = NXT_MAX;
    jfloat f[NXT_MAX * 6]; nxhost_touch_control t[NXT_MAX]; memset(t, 0, sizeof t);
    if (n > 0) (*env)->GetFloatArrayRegion(env, a, 0, n * 6, f);
    for (int i = 0; i < n; i++) { const jfloat *o = f + 6 * i; t[i].kind = (int)o[0]; t[i].buttons = (uint64_t)o[1]; t[i].stick = (int)o[2]; t[i].x = o[3]; t[i].y = o[4]; t[i].r = o[5]; }
    return nxhost_touch_controls_set(n > 0 ? t : NULL, n);
}
// What each control holds now (pressed[i]) and the sticks' deflection (lx, ly, rx, ry; -1..1, y up), for drawing and the haptic tick
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_touchControlsState(JNIEnv *env, jclass cls, jlongArray pressed, jfloatArray stick) {
    (void)cls; if (!nxhost_touch_controls_state) return;
    uint64_t p[NXT_MAX] = {0}; float s[4] = {0};
    nxhost_touch_controls_state(p, NXT_MAX, s);
    if (pressed) { int n = (*env)->GetArrayLength(env, pressed); if (n > NXT_MAX) n = NXT_MAX; (*env)->SetLongArrayRegion(env, pressed, 0, n, (const jlong *)p); }
    if (stick && (*env)->GetArrayLength(env, stick) >= 4) (*env)->SetFloatArrayRegion(env, stick, 0, 4, s);
}
JNIEXPORT jboolean JNICALL Java_dev_nxnative_android_Native_motionStart(JNIEnv *env, jclass cls) { (void)env; (void)cls; return nxhost_motion_start ? nxhost_motion_start() : JNI_FALSE; }
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_motionStop(JNIEnv *env, jclass cls) { (void)env; (void)cls; if (nxhost_motion_stop) nxhost_motion_stop(); }
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_motionRotation(JNIEnv *env, jclass cls, jint r) { (void)env; (void)cls; if (nxhost_motion_rotation) nxhost_motion_rotation(r); }
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_lifecycle(JNIEnv *env, jclass cls, jint ev) { (void)env; (void)cls; if (nxhost_lifecycle) nxhost_lifecycle(ev); }
JNIEXPORT jint JNICALL Java_dev_nxnative_android_Native_paused(JNIEnv *env, jclass cls) { (void)env; (void)cls; return nxhost_paused ? nxhost_paused() : -1; }
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_audioInterrupt(JNIEnv *env, jclass cls, jboolean on) { (void)env; (void)cls; if (nxhost_audio_interrupt) nxhost_audio_interrupt(on); }
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_audioRouteChanged(JNIEnv *env, jclass cls) { (void)env; (void)cls; if (nxhost_audio_route_changed) nxhost_audio_route_changed(); }

// The overlay's frame rate (nxhost_fps): fills out (base fps, output fps, frame time ms, frame generation state, images per game frame, GPU ms a frame or -1); false = stale or not in this host
JNIEXPORT jboolean JNICALL Java_dev_nxnative_android_Native_fps(JNIEnv *env, jclass cls, jfloatArray out) {
    (void)cls; if (!nxhost_fps || !out) return JNI_FALSE;
    float v[6] = {0, 0, 0, 0, 0, -1}; int n = (*env)->GetArrayLength(env, out); if (n > 6) n = 6;
    int r = nxhost_fps(v, n);
    (*env)->SetFloatArrayRegion(env, out, 0, n, v);
    return r ? JNI_TRUE : JNI_FALSE;
}

// Frame generation (nxhost_framegen_set / _status): the in-game settings, and the state with its reason as "<state> <reason>" (state 0 off, 1 generating, 2 on and not
// generating, -1 unavailable; "-2 ..." when this host has no frame generation)
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_frameGenSet(JNIEnv *env, jclass cls, jboolean on, jint mult, jint target, jint flow) {
    (void)env; (void)cls; if (nxhost_framegen_set) nxhost_framegen_set(on, mult, target, flow);
}
JNIEXPORT jstring JNICALL Java_dev_nxnative_android_Native_frameGenStatus(JNIEnv *env, jclass cls) {
    (void)cls; char buf[300], out[320];
    if (!nxhost_framegen_status) return (*env)->NewStringUTF(env, "-2 not in this host");
    int st = nxhost_framegen_status(buf, sizeof buf);
    snprintf(out, sizeof out, "%d %s", st, buf);
    return (*env)->NewStringUTF(env, out);
}

// The frame rate cap (nxhost_fps_cap_set / _status): "<cap> <rates> <game fps>" (input_android.h), "-1 0 0" when this host has no cap
JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_fpsCapSet(JNIEnv *env, jclass cls, jint fps) { (void)env; (void)cls; if (nxhost_fps_cap_set) nxhost_fps_cap_set(fps); }
JNIEXPORT jstring JNICALL Java_dev_nxnative_android_Native_fpsCapStatus(JNIEnv *env, jclass cls) {
    (void)cls; char buf[64] = "-1 0 0";
    if (nxhost_fps_cap_status) nxhost_fps_cap_status(buf, sizeof buf);
    return (*env)->NewStringUTF(env, buf);
}

// ---- The readiness check (Readiness.java): the Vulkan facts of the driver the game would run on (nxhost_vk_readiness), before the host starts. Of env (KEY=VALUE, the
// game's environment as start would get it) only the driver's keys are set here, the last of each winning as in start; null when this host has no such entry point ----
#pragma weak nxhost_vk_readiness
JNIEXPORT jstring JNICALL Java_dev_nxnative_android_Native_readiness(JNIEnv *env, jclass cls, jobjectArray envs) {
    (void)cls; if (!nxhost_vk_readiness) return NULL;
    for (jsize i = 0, n = envs ? (*env)->GetArrayLength(env, envs) : 0; i < n; i++) {
        char *kv = jstr(env, (jstring)(*env)->GetObjectArrayElement(env, envs, i)); char *eq = strchr(kv, '=');
        if (eq && (!strncmp(kv, "DM_VK_DRIVER=", 13) || !strncmp(kv, "DM_VK_HOOKDIR=", 14))) { *eq = 0; setenv(kv, eq + 1, 1); }
        free(kv);
    }
    size_t cap = 16384; char *buf = calloc(1, cap);
    if (!buf) return NULL;
    nxhost_vk_readiness(buf, (int)cap);
    jstring s = (*env)->NewStringUTF(env, buf); free(buf);
    return s;
}

// ---- The graphics selftest (SelftestActivity, in a process of its own): no host start, only the log and nxhost_vk_selftest ----
JNIEXPORT jint JNICALL Java_dev_nxnative_android_Native_openLog(JNIEnv *env, jclass cls, jstring log) {
    (void)cls; char *lp = jstr(env, log); int r = log_open(lp); free(lp); return r;
}
JNIEXPORT jboolean JNICALL Java_dev_nxnative_android_Native_hasSelftest(JNIEnv *env, jclass cls) { (void)env; (void)cls; return nxhost_vk_selftest != NULL; }
// Blocks for about frames / 60 s: call it on a thread of its own. 0 = every check passed; -1 = this host has no selftest
JNIEXPORT jint JNICALL Java_dev_nxnative_android_Native_selftest(JNIEnv *env, jclass cls, jobject surface, jint frames) {
    (void)cls; if (!nxhost_vk_selftest) return -1;
    ANativeWindow *w = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    fprintf(stderr, "[nxlaunch] graphics selftest: nxhost_vk_selftest(%p, %d)\n", (void *)w, frames);
    int r = nxhost_vk_selftest(w, frames);
    fprintf(stderr, "[nxlaunch] graphics selftest returned %d: %s\n", r, r ? "FAIL" : "PASS");
    if (w) ANativeWindow_release(w);   // the replay holds its own reference
    return r;
}
