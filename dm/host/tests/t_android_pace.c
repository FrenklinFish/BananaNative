// The pace line's parts (dm/gfx/vk_android/vka_pace.h): a present period as an interval in 60 Hz vsyncs, the intervals' shares of a window, percentiles of the GPU times
#include <math.h>
#include <stdio.h>
#include <string.h>
#include "../../gfx/vk_android/vka_pace.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main(void) {
    // intervals: 16.7 ms is 1, 33.3 ms 2, 50 ms 3; a period off the 60 Hz grid by a little rounds; none, too short or too long is 0
    CHECK(vkpace_interval(1 / 60.0) == 1 && vkpace_interval(2 / 60.0) == 2 && vkpace_interval(0.05) == 3, "1/2/3");
    CHECK(vkpace_interval(0.0172) == 1 && vkpace_interval(0.0345) == 2, "rounds to the nearest vsync");
    CHECK(vkpace_interval(0) == 0 && vkpace_interval(0.002) == 0 && vkpace_interval(0.2) == 0 && vkpace_interval(NAN) == 0, "none");
    CHECK(vkpace_interval(-0.05) == 0 && vkpace_interval(1e300) == 0 && vkpace_interval(INFINITY) == 0 && vkpace_interval(6.4 / 60) == 6 && vkpace_interval(6.6 / 60) == 0, "out of range");
    CHECK(vkpace_us(1e300) == 1000000000u && vkpace_us(INFINITY) == 1000000000u && vkpace_us(0.0004) == 0 && vkpace_us(16.6666) == 16667, "us clamp and rounding");

    // a window of 60 frames at 2 and 40 at 3 (DKB in the heavy area: the controller between 30 and 20 fps)
    static vkpace p; vkpace_reset(&p, 1, 100, 0, 0, 0);
    for (int i = 0; i < 60; i++) vkpace_frame(&p, 2 / 60.0);
    for (int i = 0; i < 40; i++) vkpace_frame(&p, 3 / 60.0);
    char b[96]; vkpace_intervals(&p, b, sizeof b);
    CHECK(!strcmp(b, "2 60 %, 3 40 %"), "intervals: '%s'", b);
    vkpace_frame(&p, 0.3); vkpace_intervals(&p, b, sizeof b);
    CHECK(strstr(b, "other 1 %") != NULL, "a period outside 1..6 counts as other: '%s'", b);
    char small[8]; vkpace_intervals(&p, small, sizeof small);
    CHECK(strlen(small) < sizeof small, "a short buffer stays terminated");
    vkpace_reset(&p, 2, 200, 0, 0, 0); vkpace_intervals(&p, b, sizeof b);
    CHECK(!strcmp(b, "none") && p.ng == 0 && p.t0 == 2 && p.f0 == 200, "reset: '%s'", b);

    // percentiles: GPU times 1..100 ms (in any order) give p50 51 and p90 91; a missing time (<= 0) is not counted; none gives -1
    for (int i = 100; i >= 1; i--) vkpace_gpu(&p, i, i % 2 ? 0 : 2.0 * i);
    vkpace_gpu(&p, -1, -1);
    CHECK(p.ng == 100 && p.ns == 50, "counted %d GPU times, %d spans", p.ng, p.ns);
    CHECK(fabs(vkpace_pct(p.gpu_us, p.ng, 50) - 51) < 1e-9 && fabs(vkpace_pct(p.gpu_us, p.ng, 90) - 91) < 1e-9, "p50 %.3f p90 %.3f", vkpace_pct(p.gpu_us, p.ng, 50), vkpace_pct(p.gpu_us, p.ng, 90));
    CHECK(fabs(vkpace_pct(p.gpu_us, p.ng, 100) - 100) < 1e-9, "p100 is the largest");
    CHECK(vkpace_pct(p.gpu_us, 0, 50) == -1, "none");
    // the threads' scheduling counters: schedstat's first two numbers, status's two switch counts (the voluntary line is not matched inside the nonvoluntary one)
    vkpace_thr a = {0}, z = {0};
    CHECK(vkpace_schedstat("123456789 2345678 901\n", &a) && a.run_ns == 123456789ull && a.wait_ns == 2345678ull, "schedstat %llu %llu", (unsigned long long)a.run_ns, (unsigned long long)a.wait_ns);
    CHECK(!vkpace_schedstat("", &z) && !vkpace_schedstat(NULL, &z) && !vkpace_schedstat("12", &z), "schedstat: not parsed");
    const char *st = "Name:\tvk-replay\nState:\tS (sleeping)\nvoluntary_ctxt_switches:\t4321\nnonvoluntary_ctxt_switches:\t87\n";
    CHECK(vkpace_status(st, &a) && a.vol == 4321 && a.invol == 87, "status %llu %llu", (unsigned long long)a.vol, (unsigned long long)a.invol);
    const char *rev = "Name:\tx\nnonvoluntary_ctxt_switches:\t5\nvoluntary_ctxt_switches:\t6\n";
    CHECK(vkpace_status(rev, &z) && z.vol == 6 && z.invol == 5, "status in another order %llu %llu", (unsigned long long)z.vol, (unsigned long long)z.invol);
    CHECK(!vkpace_status("Name:\tx\nnonvoluntary_ctxt_switches:\t5\n", &z) && !vkpace_status(NULL, &z), "status: not parsed");
    vkpace_thr b2 = {200, 50, 10, 3}, a2 = {100, 70, 4, 1}, d = vkpace_thr_delta(&a2, &b2);
    CHECK(d.run_ns == 100 && d.wait_ns == 0 && d.vol == 6 && d.invol == 2, "delta %llu %llu %llu %llu", (unsigned long long)d.run_ns, (unsigned long long)d.wait_ns, (unsigned long long)d.vol, (unsigned long long)d.invol);
    // the ring of times is bounded: more frames than VKP_N in one window keep the first VKP_N
    for (int i = 0; i < VKP_N + 50; i++) vkpace_gpu(&p, 10, 10);
    CHECK(p.ng == VKP_N && p.ns == VKP_N, "bounded: %d %d", p.ng, p.ns);

    if (fails) { printf("t_android_pace: %d failed\n", fails); return 1; }
    printf("t_android_pace: ok\n");
    return 0;
}
