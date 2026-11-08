// The pace line (vka_perf.c, DM_VK_PACE_LOG): one log line every few seconds with what decides a dynamic present interval's frame rate, so a log tells which limit
// holds it: the game's intervals in that window, its frame rate, the GPU's busy time and the span (recording start to GPU end) a frame, the replay's recording and its
// CPU time a frame, draws a frame, the GPU clock floor, the ADPF session, the window's rate and the panel's refresh, the thermal status and the CPU clocks. The parts
// here are free of Android and Vulkan so the Mac's unit test (dm/host/tests/t_android_pace.c) can check them
#ifndef VKA_PACE_H
#define VKA_PACE_H
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum { VKP_MAXITV = 6, VKP_N = 1024 };
typedef struct {
    uint64_t t0;                    // window start (ns, 0 = not started)
    uint32_t itv[VKP_MAXITV + 1];   // frames at each present interval (60 Hz vsyncs; [0] other)
    uint32_t gpu_us[VKP_N], span_us[VKP_N]; int ng, ns;
    uint64_t f0, draws0, rec0, cpu0;   // frame, draws, replay recording and its CPU time (ns) at the window's start
} vkpace;

// The present interval of a period in 60 Hz vsyncs (1 = 16.7 ms), 0 when it is none of 1..VKP_MAXITV
static inline int vkpace_interval(double period_s) {
    if (!(period_s > 0.004) || !(period_s < (VKP_MAXITV + 0.5) / 60.0)) return 0;   // (NaN, negative and huge periods stay out of the cast)
    long k = lround(period_s * 60.0);
    return k >= 1 && k <= VKP_MAXITV ? (int)k : 0;
}
static inline void vkpace_frame(vkpace *p, double period_s) { p->itv[vkpace_interval(period_s)]++; }
// A time in ms as whole us, clamped to 0..1000 s (a bogus time cannot overflow the cast)
static inline uint32_t vkpace_us(double ms) { return ms >= 1e6 ? 1000000000u : (uint32_t)lround(ms * 1000.0); }
static inline void vkpace_gpu(vkpace *p, double gpu_ms, double span_ms) {   // a time <= 0 or NaN is not in yet: not counted
    if (gpu_ms > 0 && p->ng < VKP_N) p->gpu_us[p->ng++] = vkpace_us(gpu_ms);
    if (span_ms > 0 && p->ns < VKP_N) p->span_us[p->ns++] = vkpace_us(span_ms);
}
// The pct-th percentile (0-100) of v[0..n) in ms; sorts v. -1 when n is 0
static inline double vkpace_pct(uint32_t *v, int n, int pct) {
    if (n <= 0) return -1;
    for (int i = 1; i < n; i++) { uint32_t x = v[i]; int j = i; while (j > 0 && v[j - 1] > x) { v[j] = v[j - 1]; j--; } v[j] = x; }
    int k = (int)((int64_t)n * pct / 100); if (k >= n) k = n - 1;
    return v[k] / 1000.0;
}
// "1 40 %, 2 60 %" (frames at each interval, the share of the window); "none" with no frames
static inline void vkpace_intervals(const vkpace *p, char *b, size_t n) {
    uint32_t t = 0; for (int i = 0; i <= VKP_MAXITV; i++) t += p->itv[i];
    size_t o = 0; if (n) b[0] = 0;
    if (!t) { snprintf(b, n, "none"); return; }
    for (int i = 1; i <= VKP_MAXITV + 1 && o < n; i++) {
        int k = i <= VKP_MAXITV ? i : 0; if (!p->itv[k]) continue;
        int w = k ? snprintf(b + o, n - o, "%s%d %.0f %%", o ? ", " : "", k, 100.0 * p->itv[k] / t) : snprintf(b + o, n - o, "%sother %.0f %%", o ? ", " : "", 100.0 * p->itv[k] / t);
        if (w < 0) break; o += (size_t)w;
    }
}
// A thread's scheduling counters (the pace line's second line: where the replay and the encoder threads' wall time goes): /proc/<pid>/task/<tid>/schedstat is
// "<ns on a core> <ns runnable, waiting for a core> <timeslices>", status has "voluntary_ctxt_switches:" (the thread blocked: a lock, a futex, a fence) and
// "nonvoluntary_ctxt_switches:" (it was preempted). 1 = parsed
typedef struct { uint64_t run_ns, wait_ns, vol, invol; } vkpace_thr;
static inline int vkpace_schedstat(const char *t, vkpace_thr *o) {
    unsigned long long r, w;
    if (!t || sscanf(t, "%llu %llu", &r, &w) != 2) return 0;
    o->run_ns = r; o->wait_ns = w; return 1;
}
static inline int vkpace_status(const char *t, vkpace_thr *o) {
    const char *v = t ? strstr(t, "\nvoluntary_ctxt_switches:") : NULL, *n = t ? strstr(t, "nonvoluntary_ctxt_switches:") : NULL;
    unsigned long long a, b;
    if (!v || !n || sscanf(v + 25, "%llu", &a) != 1 || sscanf(n + 27, "%llu", &b) != 1) return 0;
    o->vol = a; o->invol = b; return 1;
}
// The counters' change from a to b (a counter that went back, a new thread under the same slot, reads as 0)
static inline vkpace_thr vkpace_thr_delta(const vkpace_thr *a, const vkpace_thr *b) {
    vkpace_thr d;
    d.run_ns = b->run_ns >= a->run_ns ? b->run_ns - a->run_ns : 0; d.wait_ns = b->wait_ns >= a->wait_ns ? b->wait_ns - a->wait_ns : 0;
    d.vol = b->vol >= a->vol ? b->vol - a->vol : 0; d.invol = b->invol >= a->invol ? b->invol - a->invol : 0;
    return d;
}
// Start the next window: the counters at its start, and nothing counted yet
static inline void vkpace_reset(vkpace *p, uint64_t now, uint64_t f, uint64_t draws, uint64_t rec, uint64_t cpu) {
    memset(p->itv, 0, sizeof p->itv); p->ng = p->ns = 0;
    p->t0 = now; p->f0 = f; p->draws0 = draws; p->rec0 = rec; p->cpu0 = cpu;
}
#endif
