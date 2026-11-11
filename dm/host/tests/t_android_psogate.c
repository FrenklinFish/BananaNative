// The background pipeline compiles' gate (dm/gfx/vk_android/vka_psogate.h; vka_pso.c gate_enter / gate_leave): how many compiles memory and the memory maps allow at
// once, the frame's compiles before the speculative ones (prewarm, optimizer), a known long compile alone, and a simulated market backlog (Ocarina of Time,
// Castle Town, Turnip on the Adreno 840) under the gate against the one-at-a-time mutex it replaced
#include <stdio.h>
#include "../../gfx/vk_android/vka_psogate.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

// A toy scheduler over the gate: jobs arrive (frame or speculative, a cost in ticks), each tick admits waiters in order (frame ones first in the queue the threads
// would form; the gate decides), running jobs advance one tick. Returns the tick the last frame job finished
typedef struct { int frame, heavy, cost, left, state; } job;   // state 0 waiting, 1 running, 2 done
static int run(job *j, int n, int slots, int threads_frame, int threads_spec, int *max_busy, int *spec_before_frame) {
    vkpg_state s = {0}; int t = 0, last_frame = 0; *max_busy = 0; *spec_before_frame = 0;
    for (int i = 0; i < n; i++) { j[i].left = j[i].cost; j[i].state = 0; }
    for (t = 0; t < 100000; t++) {
        // waiting counts: the oldest threads_frame frame jobs and threads_spec speculative jobs are what the threads hold
        int fw = 0, fhw = 0, sw = 0, shw = 0, cf = 0, cs = 0;
        for (int i = 0; i < n; i++) if (!j[i].state) {
            if (j[i].frame && cf < threads_frame) { cf++; if (j[i].heavy) fhw++; else fw++; }
            if (!j[i].frame && cs < threads_spec) { cs++; if (j[i].heavy) shw++; else sw++; }
        }
        s.frame_wait = fw; s.frame_heavy_wait = fhw; s.spec_wait = sw; s.spec_heavy_wait = shw;
        cf = cs = 0;
        for (int i = 0; i < n; i++) {
            if (j[i].state) continue;
            int mine = j[i].frame ? cf++ < threads_frame : cs++ < threads_spec;
            if (!mine) continue;
            // the asking thread is not counted among the waiters
            vkpg_state o = s;
            if (j[i].frame) { if (j[i].heavy) o.frame_heavy_wait--; else o.frame_wait--; } else { if (j[i].heavy) o.spec_heavy_wait--; else o.spec_wait--; }
            if (vkpg_may_enter(&o, slots, j[i].frame, j[i].heavy)) {
                if (!j[i].frame && o.frame_wait + o.frame_heavy_wait > 0) (*spec_before_frame)++;   // (a speculative start while a frame compile waited)
                j[i].state = 1; s.busy++; s.heavy_busy += j[i].heavy;
                if (j[i].frame) { if (j[i].heavy) s.frame_heavy_wait--; else s.frame_wait--; } else { if (j[i].heavy) s.spec_heavy_wait--; else s.spec_wait--; }
            }
        }
        if (s.busy > *max_busy) *max_busy = s.busy;
        int done = 1;
        for (int i = 0; i < n; i++) {
            if (j[i].state == 1 && --j[i].left <= 0) { j[i].state = 2; s.busy--; s.heavy_busy -= j[i].heavy; if (j[i].frame) last_frame = t + 1; }
            if (j[i].state != 2) done = 0;
        }
        if (done) break;
    }
    return last_frame;
}

// The asynchronous queue on one compile at a time (Turnip's default): jobs of a cost in ticks arrive at their tick, one runs at a time; ordered = least cost first with the
// oldest in turn (vkpg_take_oldest; fair = ticks), else arrival order. Fills done[] with each job's finishing tick; returns the tick the last one finished
typedef struct { int arrive, cost, done; } qjob;
static int qrun(qjob *j, int n, int ordered, int fair, int *n_oldest) {
    int t = 0, left = n; long credit = 0; *n_oldest = 0;
    for (int i = 0; i < n; i++) j[i].done = -1;
    while (left) {
        int head = -1, best = -1;   // head: the oldest queued (arrival, then index); best: the least cost (first among equals)
        for (int i = 0; i < n; i++) if (j[i].done < 0 && j[i].arrive <= t) {
            if (head < 0 || j[i].arrive < j[head].arrive) head = i;
            if (best < 0 || j[i].cost < j[best].cost || (j[i].cost == j[best].cost && j[i].arrive < j[best].arrive)) best = i;
        }
        if (head < 0) { t++; continue; }
        int pick = head, old = 1;
        if (ordered && !vkpg_take_oldest((uint64_t)(t - j[head].arrive), (uint64_t)fair, credit)) { pick = best; old = pick == head; }
        t += j[pick].cost; j[pick].done = t; left--;
        if (old) { credit -= j[pick].cost; (*n_oldest)++; } else credit += j[pick].cost;
        if (credit > fair) credit = fair;
    }
    return t;
}
static int qnth(const qjob *j, int n, int pct) {   // the tick by which pct % of the jobs were done
    static int d[4096]; for (int i = 0; i < n; i++) d[i] = j[i].done;
    for (int i = 1; i < n; i++) for (int k = i; k > 0 && d[k - 1] > d[k]; k--) { int x = d[k]; d[k] = d[k - 1]; d[k - 1] = x; }
    return d[(n * pct + 99) / 100 - 1];
}

int main(void) {
    // The default cap by driver
    CHECK(vkpg_cap_default(1) == 1 && vkpg_cap_default(0) == VKPG_CAP, "one compile at a time on Turnip, the cap elsewhere");
    CHECK(vkpg_slots(6430, 10616, 65530, vkpg_cap_default(1)) == 1, "Turnip with memory and maps to spare: still one");
    CHECK(!vkpg_take_oldest(1999, 2000, 0) && vkpg_take_oldest(2000, 2000, 0) && !vkpg_take_oldest(5000, 2000, -1), "the oldest's turn: waited long enough and the cheap ones had their time");

    // Ocarina of Time's Castle Town market on Turnip, cold (run2-tu, 2026-11-11), in ticks of 10 ms: 552 pipelines queued within a few seconds, 58 of their libraries slow
    // (pixel shaders of 99-130 KB, ~1.9 s each: 120 of the 125 s of library time), the ~210 others ~5 s in all; one compile at a time. In arrival order a slow one came
    // every ~10 jobs, so most of the cheap ones waited behind them
    {
        enum { NQ = 552 }; static qjob Q[NQ]; int no;
        for (int i = 0; i < NQ; i++) Q[i] = (qjob){i / 2, i % 10 == 4 ? 190 : 1, 0};   // (arriving over ~2.8 s, a slow one every 10th: 55)
        int fifo_end = qrun(Q, NQ, 0, 200, &no), fifo90 = qnth(Q, NQ, 90), fifo50 = qnth(Q, NQ, 50);
        int ord_end = qrun(Q, NQ, 1, 200, &no), ord90 = qnth(Q, NQ, 90), ord50 = qnth(Q, NQ, 50);
        int slow_max = 0; for (int i = 0; i < NQ; i++) if (Q[i].cost > 1 && Q[i].done > slow_max) slow_max = Q[i].done;
        CHECK(ord_end == fifo_end, "the same work either way: done at the same tick (%d against %d)", ord_end, fifo_end);
        CHECK(ord50 * 5 < fifo50, "half the pipelines made 5 times sooner (8.5 here) (tick %d against %d)", ord50, fifo50);
        CHECK(ord90 * 5 < fifo90, "90 %% of them 5 times sooner (9.3 here) (tick %d against %d)", ord90, fifo90);
        CHECK(no > 0 && slow_max <= fifo_end, "the slow ones took their turns and all were made (%d oldest picks, last at %d)", no, slow_max);
    }
    // Cheap pipelines that never stop coming (one a tick, each one tick) do not starve a slow one: it waits about the fair time, not for ever
    {
        enum { NQ = 2001 }; static qjob Q[NQ]; int no;
        Q[0] = (qjob){0, 190, 0}; for (int i = 1; i < NQ; i++) Q[i] = (qjob){i - 1, 1, 0};
        qrun(Q, NQ, 1, 200, &no);
        CHECK(Q[0].done > 0 && Q[0].done <= 200 + 190 + 1, "the slow one made after its fair wait (tick %d)", Q[0].done);
        int late = 0; for (int i = 1; i < NQ; i++) if (Q[i].done - Q[i].arrive > 190 + 200 + 2) late++;
        CHECK(!late, "no cheap one waited more than one slow compile and the fair time (%d did)", late);
    }

    // Slots by memory and maps
    CHECK(vkpg_slots(0, -1, 0, 4) == 4, "nothing known: the cap");
    CHECK(vkpg_slots(1700, -1, 0, 4) == 1, "the market's 1.7 GB available: one at a time");
    CHECK(vkpg_slots(2048, -1, 0, 4) == 1, "at the free share: one");
    CHECK(vkpg_slots(2048 + 512, -1, 0, 4) == 2, "512 MiB more: two");
    CHECK(vkpg_slots(6430, -1, 0, 4) == 4, "a fresh start (6.4 GB): the cap");
    CHECK(vkpg_slots(6430, -1, 0, 2) == 2, "DM_VK_PSO_SLOTS=2");
    CHECK(vkpg_slots(6430, -1, 0, 0) == 1 && vkpg_slots(6430, -1, 0, -3) == 1, "a cap under 1: one");
    CHECK(vkpg_slots(64000, -1, 0, 99) == VKPG_CAP_MAX, "the cap's own limit");
    CHECK(vkpg_slots(6430, 10616, 65530, 4) == 4, "16 %% of the maps: room for the cap (got %d)", vkpg_slots(6430, 10616, 65530, 4));
    CHECK(vkpg_slots(6430, 33600, 65530, 4) == 2, "the market's 33,600 of 65,530 maps: 15,500 to the budget, two (got %d)", vkpg_slots(6430, 33600, 65530, 4));
    CHECK(vkpg_slots(6430, 49200, 65530, 4) == 1, "past the budget (75 %%): one");
    CHECK(vkpg_slots(6430, 65530, 65530, 4) == 1, "at the limit: still one (the frame's pipelines must be made)");
    for (long m = 0; m < 65530; m += 101) CHECK(vkpg_slots(6430, m + 101, 65530, 4) <= vkpg_slots(6430, m, 65530, 4), "never more as the maps climb (%ld)", m);
    for (uint64_t a = 1; a < 8000; a += 37) CHECK(vkpg_slots(a + 37, -1, 0, 4) >= vkpg_slots(a, -1, 0, 4), "never fewer as memory frees (%llu)", (unsigned long long)a);

    // Admission
    vkpg_state s = {0};
    CHECK(vkpg_may_enter(&s, 1, 1, 0) && vkpg_may_enter(&s, 1, 0, 0), "an idle gate admits either kind");
    s.busy = 1;
    CHECK(!vkpg_may_enter(&s, 1, 1, 0), "one slot taken: the frame's waits");
    CHECK(vkpg_may_enter(&s, 2, 1, 0) && vkpg_may_enter(&s, 2, 0, 0), "a second slot: either");
    s.frame_wait = 1;
    CHECK(vkpg_may_enter(&s, 2, 1, 0) && !vkpg_may_enter(&s, 2, 0, 0), "a frame compile waiting: no speculative one starts, another frame one does");
    s = (vkpg_state){.busy = 1, .heavy_busy = 1};
    CHECK(!vkpg_may_enter(&s, 4, 1, 0) && !vkpg_may_enter(&s, 4, 1, 1), "a long one runs alone");
    s = (vkpg_state){.busy = 1};
    CHECK(!vkpg_may_enter(&s, 4, 1, 1), "a long one waits for the others to finish");
    s = (vkpg_state){.busy = 0};
    CHECK(vkpg_may_enter(&s, 4, 1, 1) && vkpg_may_enter(&s, 4, 0, 1), "and starts on an idle gate");
    s = (vkpg_state){.busy = 1, .frame_heavy_wait = 1};
    CHECK(!vkpg_may_enter(&s, 4, 1, 0) && !vkpg_may_enter(&s, 4, 0, 0), "the frame's long one waiting: the gate drains for it");
    s = (vkpg_state){.busy = 1, .spec_heavy_wait = 1};
    CHECK(vkpg_may_enter(&s, 4, 1, 0) && !vkpg_may_enter(&s, 4, 0, 0), "a speculative long one waiting: frame compiles go on, speculative ones drain");
    s = (vkpg_state){.busy = 0, .frame_wait = 1};
    CHECK(!vkpg_may_enter(&s, 4, 0, 1), "a speculative long one does not start while the frame's wait");

    // The market: 2 asynchronous threads with 300 frame jobs (5 ticks each, some long), 4 prewarm threads with 700 speculative jobs, arriving at once.
    enum { NF = 300, NS = 700 };
    static job J[NF + NS]; int mb, sbf;
    for (int i = 0; i < NS; i++) J[i] = (job){0, i % 50 == 7, 5, 0, 0};          // the prewarm list, queued first (it starts at the title screen)
    for (int i = 0; i < NF; i++) J[NS + i] = (job){1, i % 60 == 3, 5, 0, 0};     // the market's misses
    int one = run(J, NF + NS, 1, 2, 4, &mb, &sbf);
    CHECK(mb == 1, "one slot: never two at once (%d)", mb);
    CHECK(sbf == 0, "one slot: no speculative compile starts while frame ones wait (%d did)", sbf);
    CHECK(one <= NF * 5 + 5, "one slot: the frame's backlog drains in its own compile time, not behind the prewarm list (tick %d)", one);
    int two = run(J, NF + NS, 2, 2, 4, &mb, &sbf);
    CHECK(mb <= 2 && sbf == 0, "two slots: at most two, frame first (max %d, speculative early %d)", mb, sbf);
    CHECK(two < one * 3 / 4, "two slots drain the frame's backlog faster (%d against %d)", two, one);
    // The mutex it replaced, modelled as one slot taken in arrival order whatever the kind: the frame's backlog waited for the prewarm list
    int fifo_last = (NS + NF) * 5;   // every job before the last frame one ran first, one at a time
    CHECK(one < fifo_last / 2, "frame first beats arrival order by more than half (%d against %d)", one, fifo_last);

    if (fails) { printf("t_android_psogate: %d failed\n", fails); return 1; }
    printf("t_android_psogate: ok\n");
    return 0;
}
