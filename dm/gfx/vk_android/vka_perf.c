// CPU scheduling for the replay on Android: what Apple's time-constraint policy does for nvn_mtl.m's present thread (hle_nvn.c set_time_constraint_comp), here an ADPF
// performance hint session (APerformanceHint_*, Android 13): the threads that carry a frame (the game's submitting and presenting threads, the replay's present and completion
// threads) are in one session whose target is the present period, and each frame reports how long the submitting thread worked on it (its CPU time between two presents).
// The system then keeps the clocks those threads need, and moves them to faster cores, instead of ramping down between frames and back up late (the frame-to-frame swings on the
// SM8850 phone). The replay's own threads also run at THREAD_PRIORITY_URGENT_DISPLAY (nice -8). DM_VK_ADPF=0 turns the session off.
// The entry points are looked up at run time: libandroid has them from API 33, setThreads from 34.
// From API 35 the report also carries the frame's GPU time (APerformanceHint_reportActualWorkDuration2, AWorkDuration_setActualGpuDurationNanos): the GPU's governor sees
// the frame's GPU work against the target instead of only its busy percentage (on the SM8850 phone the same replayed frame took 35 ms of GPU time right after a busy
// stretch and 94-98 ms after 2.6 s of idle GPU: its clock follows load). DM_VK_ADPF_GPU=0 (live) reports CPU time only
#if defined(__ANDROID__)
#include "vka.h"
#include <dlfcn.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <time.h>
#include <stdio.h>
#include <unistd.h>
#include <dirent.h>
#include <string.h>
#include <pthread.h>
uint64_t vka_frame(void); double mtl_gpu_ms(uint64_t f);

typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;
static struct {
    APerformanceHintManager *(*get)(void);
    APerformanceHintSession *(*create)(APerformanceHintManager *, const int32_t *, size_t, int64_t);
    int (*target)(APerformanceHintSession *, int64_t);
    int (*report)(APerformanceHintSession *, int64_t);
    int (*threads)(APerformanceHintSession *, const int32_t *, size_t);
    void (*close)(APerformanceHintSession *);
    void *(*wd_create)(void); void (*wd_release)(void *); void (*wd_start)(void *, int64_t); void (*wd_total)(void *, int64_t); void (*wd_cpu)(void *, int64_t);
    void (*wd_gpu)(void *, int64_t); int (*report2)(APerformanceHintSession *, void *);
} H;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static APerformanceHintSession *g_s; static int g_state;   // 0 not tried, 1 usable, -1 not available / off
enum { MAXT = 16 };
static int32_t g_tid[MAXT]; static int g_nt, g_dirty;
static int64_t g_target = 16666667;

static int avail(void) {
    if (g_state) return g_state > 0;
    g_state = -1;
    const char *e = getenv("DM_VK_ADPF"); if (e && !atoi(e)) { VLOG("DM_VK_ADPF=0: no performance hint session"); return 0; }
    void *h = dlopen("libandroid.so", RTLD_NOW);
    if (!h) return 0;
    H.get = dlsym(h, "APerformanceHint_getManager"); H.create = dlsym(h, "APerformanceHint_createSession"); H.target = dlsym(h, "APerformanceHint_updateTargetWorkDuration");
    H.report = dlsym(h, "APerformanceHint_reportActualWorkDuration"); H.threads = dlsym(h, "APerformanceHint_setThreads"); H.close = dlsym(h, "APerformanceHint_closeSession");
    H.wd_create = dlsym(h, "AWorkDuration_create"); H.wd_release = dlsym(h, "AWorkDuration_release"); H.wd_start = dlsym(h, "AWorkDuration_setWorkPeriodStartTimestampNanos");
    H.wd_total = dlsym(h, "AWorkDuration_setActualTotalDurationNanos"); H.wd_cpu = dlsym(h, "AWorkDuration_setActualCpuDurationNanos");
    H.wd_gpu = dlsym(h, "AWorkDuration_setActualGpuDurationNanos"); H.report2 = dlsym(h, "APerformanceHint_reportActualWorkDuration2");
    if (!H.get || !H.create || !H.target || !H.report || !H.get()) { VRED("no ADPF performance hint manager: the frame's threads run on the default governor"); return 0; }
    g_state = 1;
    return 1;
}
static void session_update(void) {   // g_mu held
    if (!g_dirty || !g_nt) return;
    g_dirty = 0;
    if (g_s && H.threads && !H.threads(g_s, g_tid, (size_t)g_nt)) return;
    if (g_s && H.close) H.close(g_s);
    g_s = H.create(H.get(), g_tid, (size_t)g_nt, g_target);
    if (!g_s) { static int n; if (n++ < 2) VRED("ADPF session of %d threads not made", g_nt); }
    else VLOG("ADPF session: %d threads, target %.2f ms", g_nt, g_target / 1e6);
}
// The calling thread takes part in frames: into the session (once)
void vka_perf_thread(int nice) {
    static __thread int in;
    if (in) return;
    in = 1;
    if (nice) setpriority(PRIO_PROCESS, 0, nice);
    if (!avail()) return;
    int32_t t = (int32_t)gettid();
    pthread_mutex_lock(&g_mu);
    int k = 0; while (k < g_nt && g_tid[k] != t) k++;
    if (k == g_nt && g_nt < MAXT) { g_tid[g_nt++] = t; g_dirty = 1; }
    session_update();
    pthread_mutex_unlock(&g_mu);
}
// Once per frame, on the thread that presents (mtl_present): the present period the game asked for, and this thread's CPU time since its last present as the work done
// DM_VK_ADPF_SPAN=p (live; default 0 = off): the reports with GPU time say how long the frame's work took, not how long the frame was shown. Without it the total
// duration of each report is the wall time between two presents, which is the present period itself: every report reads "exactly on target" whatever the work took
// (DKB's 20 ms of recording at 30 fps, or 36 ms at a 2 GHz cap that put it at 20 fps), so the session can neither see slack to give back nor a frame it is about to
// miss. With p > 0 the report is the newest frame whose GPU work is done: its start the replay's start of recording its first submission, its total that start to
// the GPU end of its last submission (vka_exec.c, the same span as the adaptive GPU clock floor; the game's frame-rate controller measures about the same), CPU the
// replay thread's time, GPU its busy time; the target p % of the period, at most 33.3 ms (so a game the controller dropped to 20 fps still aims at 30: with the 50 ms
// period as target the session would lower the clocks until the frame took 50 ms and keep it there). Each frame is reported once. Not phone-checked (STUTTER-TEST.md)
static int span_pct(void) { int p = VKA_ENV("DM_VK_ADPF_SPAN", 0); return p <= 0 ? 0 : p > 100 ? 100 : p; }
static int64_t span_target(double period_s, int pct) {
    double per = period_s > 0.004 && period_s < 0.2 ? period_s : 1 / 60.0; if (per > 1 / 30.0) per = 1 / 30.0;
    return (int64_t)(per * 1e9 * pct / 100);
}
uint64_t vka_floor_span_of(uint64_t f); uint64_t vka_frame_start_ns(uint64_t f); uint64_t now_ns(void);
_Atomic int64_t vka_period_ns;   // the present period the game asked for last (vka_dev.c's adaptive GPU clock floor)
void vka_perf_frame(double period_s) {
    if (period_s > 0.004 && period_s < 0.2) atomic_store_explicit(&vka_period_ns, (int64_t)(period_s * 1e9), memory_order_relaxed);
    if (!avail()) return;
    vka_perf_thread(0);
    static __thread int64_t last; struct timespec ts; clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    int64_t c = (int64_t)ts.tv_sec * 1000000000ll + ts.tv_nsec, work = last ? c - last : 0; last = c;
    static __thread int64_t wlast; clock_gettime(CLOCK_MONOTONIC, &ts); int64_t w = (int64_t)ts.tv_sec * 1000000000ll + ts.tv_nsec, w0 = wlast; wlast = w;
    int64_t gpu = 0;   // the newest frame whose GPU time is in (it comes in a few frames late)
    int64_t s_start = 0, s_total = 0;   // DM_VK_ADPF_SPAN: that frame's start (CLOCK_MONOTONIC) and its span
    int with_gpu = H.report2 && H.wd_create && H.wd_gpu && VKA_ENV("DM_VK_ADPF_GPU", 1), pct = with_gpu ? span_pct() : 0;   // (the span goes in the reports with GPU time only)
    if (with_gpu) {
        static __thread uint64_t reported; uint64_t f = vka_frame();
        for (uint64_t k = 2; k < 14 && k < f; k++) {
            uint64_t g = f - k; double gm = mtl_gpu_ms(g); if (!(gm > 0)) continue;
            if (!pct) { gpu = (int64_t)(gm * 1e6); break; }
            if (g <= reported) break;   // reported already: nothing newer is in
            uint64_t sp = vka_floor_span_of(g), st = vka_frame_start_ns(g), now = now_ns();
            if (!sp || !st || st > now || now - st > 2000000000ull) continue;
            gpu = (int64_t)(gm * 1e6); s_total = (int64_t)sp; s_start = w - (int64_t)(now - st); reported = g;
            break;
        }
    }
    int64_t tgt = pct ? span_target(period_s, pct) : period_s > 0.004 && period_s < 0.2 ? (int64_t)(period_s * 1e9) : 16666667;
    pthread_mutex_lock(&g_mu);
    session_update();
    if (g_s && tgt != g_target) { g_target = tgt; H.target(g_s, tgt); VLOG("ADPF target %.2f ms", tgt / 1e6); }
    if (g_s && pct) {
        if (gpu > 0 && s_total > 0 && s_start > 0) {
            static void *wd; if (!wd) wd = H.wd_create();
            if (wd) { H.wd_start(wd, s_start); H.wd_total(wd, s_total); H.wd_cpu(wd, work > 0 ? work : 0); H.wd_gpu(wd, gpu);
                      int r = H.report2(g_s, wd); static int nl; if (r && nl++ < 3) VRED("ADPF report of the frame's span: %d", r); else if (!r && !nl++) VLOG("ADPF: frames report their span (%.2f ms, GPU %.2f ms) against %.2f ms (DM_VK_ADPF_SPAN=%d)", s_total / 1e6, gpu / 1e6, tgt / 1e6, pct); }
        }
    } else if (g_s && gpu > 0 && w0 && w > w0) {
        static void *wd; if (!wd) wd = H.wd_create();
        if (wd) { H.wd_start(wd, w0); H.wd_total(wd, w - w0); H.wd_cpu(wd, work > 0 ? work : 0); H.wd_gpu(wd, gpu);
                  int r = H.report2(g_s, wd); static int nl; if (r && nl++ < 3) VRED("ADPF report with GPU time: %d", r); else if (!r && !nl++) VLOG("ADPF: frames report their GPU time (%.2f ms)", gpu / 1e6); }
    } else if (g_s && work > 0) H.report(g_s, work);
    pthread_mutex_unlock(&g_mu);
}
// hle_nvn.c's time-constraint request for its acquire / present thread (Apple's THREAD_TIME_CONSTRAINT_POLICY): that thread joins the session, at display priority
void dm_perf_time_constraint(uint64_t period_ns, uint64_t comp_ns) {
    (void)comp_ns;
    vka_perf_thread(-8);
    if (!avail()) return;
    pthread_mutex_lock(&g_mu);
    if (!(H.report2 && span_pct()) && period_ns >= 4000000 && period_ns <= 200000000 && (int64_t)period_ns != g_target) { g_target = (int64_t)period_ns; if (g_s) H.target(g_s, g_target); }   // (with DM_VK_ADPF_SPAN vka_perf_frame sets it)
    pthread_mutex_unlock(&g_mu);
}
// ---------- The [longframe] line's attribution on the Vulkan replay (hle_nvn.c lf_attr's hooks) ----------
// mtl_frame_extra, at the end of each game frame on the game's presenting thread: what the replay side spent or waited since the last frame. "game waited for the replay
// worker" is the game's submit blocked on a full job queue (vka_worker.c push: the replay is a frame behind and still recording); "replay recording" the worker's time in
// submissions; "replay waited for the encoder" the replay thread blocked on the command encoder's ring (vka_enc.c); "cache save" the saver thread's time writing the
// pipeline cache (off the frame path since 2026-11-03, listed to rule it out); "waited for prewarm" the frame path waiting for a prewarm thread that was making the same
// pipeline; "pool placement" the time memory pools took to become placed Vulkan memory on any thread (vka_mem.c; the work only, not the wait for another placement),
// "give-back" the time finalized pools took to become anonymous memory again (with the wait for the GPU to be done with them), and "the pool table's lock waited for"
// the lookups that waited for the segment table meanwhile, on the replay thread and on the others (the game's NVN calls). mtl_gpu_extra, when the line is printed (8 frames later): the GPU's span against its busy time (vka_exec.c): a span far above the busy time is GPU time spent
// waiting for the recording, i.e. a CPU stall on the replay thread, not GPU work
void vka_worker_waits(uint64_t *room_ns, uint64_t *sub_ns); uint64_t vka_enc_waits(void); uint64_t vka_worker_sub_cpu(void);
void vka_mem_waits(uint64_t *place_ns, uint64_t *place_bytes, uint64_t *place_n, uint64_t *give_ns, uint64_t *wait_rec_ns, uint64_t *wait_other_ns);
// The busiest threads of a long frame. A sampler thread (dm-lfthreads) reads every thread's run time (/proc/self/task/<tid>/schedstat, ns; else stat's utime + stime in
// clock ticks) every DM_LONGFRAME_THREADS ms (250; 0 = off, restart) into a ring. A frame of at least 100 ms posts a request at its end; the sampler takes a fresh reading
// and names the 3 threads that ran most since the newest reading older than the frame's start (the window is the frame plus at most one period, and the moment the sampler
// took it). The line is printed 8 frames later (mtl_gpu_extra), by when the answer is in; the presenting thread never scans /proc itself. Names are /proc's comm: the guest
// threads carry "<handle> <guest name>" (hle_thread.c), the replay's "vk-…". A loading hitch whose own thread is idle then says who ran: the game's loader, a vk- thread, nobody
enum { TS_MAX = 192, TS_RING = 16, TS_REQ = 16 };
typedef struct { uint64_t t; int n; struct { int tid; uint64_t ns; } e[TS_MAX]; } tsnap;
static tsnap g_ts[TS_RING]; static unsigned g_tsn;
static struct { uint64_t f, since; int state; char s[220]; } g_tsq[TS_REQ];   // state 1 = asked, 2 = answered (under g_tsmu)
static pthread_mutex_t g_tsmu = PTHREAD_MUTEX_INITIALIZER; static pthread_cond_t g_tscv = PTHREAD_COND_INITIALIZER;
static uint64_t ts_now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }
static int ts_read(tsnap *s) {
    DIR *d = opendir("/proc/self/task"); if (!d) return 0;
    static long hz; if (!hz) { hz = sysconf(_SC_CLK_TCK); if (hz <= 0) hz = 100; }
    struct dirent *de; s->n = 0; s->t = ts_now(); char path[64], b[512];
    while ((de = readdir(d)) && s->n < TS_MAX) {
        int tid = atoi(de->d_name); if (tid <= 0) continue;
        uint64_t ns = 0; int ok = 0;
        snprintf(path, sizeof path, "/proc/self/task/%d/schedstat", tid);
        FILE *f = fopen(path, "re");
        if (f) { unsigned long long r; ok = fscanf(f, "%llu", &r) == 1; fclose(f); ns = ok ? r : 0; }
        if (!ok) {
            snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
            if ((f = fopen(path, "re"))) {
                size_t k = fread(b, 1, sizeof b - 1, f); fclose(f); b[k] = 0;
                char *q = strrchr(b, ')'); unsigned long long ut, st;
                if (q && sscanf(q + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &ut, &st) == 2) { ns = (ut + st) * (1000000000ull / (uint64_t)hz); ok = 1; }
            }
        }
        if (ok) { s->e[s->n].tid = tid; s->e[s->n].ns = ns; s->n++; }
    }
    closedir(d);
    return s->n;
}
static int ts_period_ms(void) { static int v = -1; if (v < 0) { const char *e = getenv("DM_LONGFRAME_THREADS"); v = e ? atoi(e) : 250; if (v < 0) v = 0; } return v; }
static void ts_busiest(const tsnap *base, const tsnap *now, char *out, size_t n) {   // "busiest threads over <ms>: '<name>' <ms>, …" between two readings
    struct { int tid; uint64_t d; } top[3] = {{0}};
    for (int i = 0; i < now->n; i++) {
        uint64_t prev = 0; for (int j = 0; j < base->n; j++) if (base->e[j].tid == now->e[i].tid) { prev = base->e[j].ns; break; }   // a thread born since: all of its time
        uint64_t d = now->e[i].ns > prev ? now->e[i].ns - prev : 0;
        for (int k = 0; k < 3; k++) if (d > top[k].d) { for (int m = 2; m > k; m--) top[m] = top[m - 1]; top[k].tid = now->e[i].tid; top[k].d = d; break; }
    }
    size_t o = (size_t)snprintf(out, n, "busiest threads over %.0f ms:", (now->t - base->t) / 1e6);
    for (int k = 0; k < 3 && top[k].d && o < n; k++) {
        char path[64], nm[32] = "?"; snprintf(path, sizeof path, "/proc/self/task/%d/comm", top[k].tid);
        FILE *f = fopen(path, "re"); if (f) { if (fgets(nm, sizeof nm, f)) nm[strcspn(nm, "\n")] = 0; fclose(f); }
        o += (size_t)snprintf(out + o, n - o, "%s '%s' %.0f", k ? "," : "", nm, top[k].d / 1e6);
    }
}
static void *ts_main(void *a) {
    (void)a; pthread_setname_np(pthread_self(), "dm-lfthreads");
    tsnap *tmp = malloc(sizeof *tmp), *base = malloc(sizeof *base); if (!tmp || !base) return NULL;
    uint64_t next = 0;
    pthread_mutex_lock(&g_tsmu);
    for (;;) {
        int q = -1; for (int i = 0; i < TS_REQ; i++) if (g_tsq[i].state == 1) { q = i; break; }
        uint64_t now = ts_now();
        if (q < 0 && now < next) {   // nothing asked and not yet time for a reading: wait for either
            uint64_t dl = next - now; struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_sec += (time_t)(dl / 1000000000ull); ts.tv_nsec += (long)(dl % 1000000000ull); if (ts.tv_nsec >= 1000000000l) { ts.tv_sec++; ts.tv_nsec -= 1000000000l; }
            pthread_cond_timedwait(&g_tscv, &g_tsmu, &ts); continue;
        }
        uint64_t since = q >= 0 ? g_tsq[q].since : 0, fq = q >= 0 ? g_tsq[q].f : 0;
        pthread_mutex_unlock(&g_tsmu);
        int ok = ts_read(tmp);
        pthread_mutex_lock(&g_tsmu);
        if (q >= 0) {
            int got = 0;
            for (unsigned k = 0; k < TS_RING && k < g_tsn; k++) { const tsnap *s = &g_ts[(g_tsn - 1 - k) % TS_RING]; if (s->t <= since) { *base = *s; got = 1; break; } }
            g_tsq[q].s[0] = 0;
            char b[220] = "";
            if (got && ok) { pthread_mutex_unlock(&g_tsmu); ts_busiest(base, tmp, b, sizeof b); pthread_mutex_lock(&g_tsmu); }
            if (g_tsq[q].f == fq && g_tsq[q].state == 1) { memcpy(g_tsq[q].s, b, sizeof b); g_tsq[q].state = 2; }   // (else a newer frame took the slot meanwhile: asked again)
        }
        if (ok) { g_ts[g_tsn++ % TS_RING] = *tmp; next = ts_now() + (uint64_t)ts_period_ms() * 1000000ull; }
    }
    return NULL;
}
void mtl_frame_extra(uint64_t f, char *out, size_t n) {
    static uint64_t proom, psub, penc, psave, ppw, ppl, pplb, ppln, pgv, pwr, pwo, pt, psc; static int init;
    uint64_t room = 0, sub = 0, enc = vka_enc_waits(), save = 0, pw = 0, pl = 0, plb = 0, pln = 0, gv = 0, wr = 0, wo = 0, t = ts_now(), sc = vka_worker_sub_cpu(); vka_worker_waits(&room, &sub); vka_pso_waits(&save, &pw);
    vka_mem_waits(&pl, &plb, &pln, &gv, &wr, &wo);
    static int ts_on = -1; if (ts_on < 0) { ts_on = ts_period_ms() > 0; if (ts_on) { pthread_t th; if (pthread_create(&th, NULL, ts_main, NULL)) ts_on = 0; else pthread_detach(th); } }
    if (init && n) {
        snprintf(out, n, "game waited for the replay worker %.1f, replay recording %.1f (its CPU %.1f), replay waited for the encoder %.1f, cache save %.1f, waited for prewarm %.1f; "
                         "pool placement %.1f ms (%llu pools, %.1f MB), give-back %.1f ms, the pool table's lock waited for: replay %.1f, others %.1f",
                 (room - proom) / 1e6, (sub - psub) / 1e6, (sc - psc) / 1e6, (enc - penc) / 1e6, (save - psave) / 1e6, (pw - ppw) / 1e6,
                 (pl - ppl) / 1e6, (unsigned long long)(pln - ppln), (plb - pplb) / 1048576.0, (gv - pgv) / 1e6, (wr - pwr) / 1e6, (wo - pwo) / 1e6);
        if (ts_on && t - pt >= 100000000ull) {   // a long frame: the sampler names its busiest threads (read back at print time, mtl_gpu_extra)
            pthread_mutex_lock(&g_tsmu);
            int q = (int)(f % TS_REQ); g_tsq[q].f = f; g_tsq[q].since = pt; g_tsq[q].state = 1; g_tsq[q].s[0] = 0;
            pthread_cond_signal(&g_tscv);
            pthread_mutex_unlock(&g_tsmu);
        }
    } else if (n) out[0] = 0;
    proom = room; psub = sub; penc = enc; psave = save; ppw = pw; ppl = pl; pplb = plb; ppln = pln; pgv = gv; pwr = wr; pwo = wo; pt = t; psc = sc; init = 1;
}
void mtl_gpu_extra(uint64_t f, char *out, size_t n) {
    double s = vka_gpu_span_ms(f), b = mtl_gpu_ms(f);
    if (!n) return;
    size_t o = 0; out[0] = 0;
    if (s >= 0 && b >= 0) o = (size_t)snprintf(out, n, " (busy; span %.1f ms, %.1f waiting for the recording)", s, s > b ? s - b : 0.0);
    pthread_mutex_lock(&g_tsmu);
    int q = (int)(f % TS_REQ);
    if (g_tsq[q].f == f && g_tsq[q].state == 2 && g_tsq[q].s[0] && o < n) snprintf(out + o, n - o, "; %s", g_tsq[q].s);
    else if (g_tsq[q].f == f && g_tsq[q].state == 1 && o < n) snprintf(out + o, n - o, "; busiest threads: not read yet");
    pthread_mutex_unlock(&g_tsmu);
}
#endif
