// The background pipeline compiles' gate (vka_pso.c gate_enter / gate_leave), free of Vulkan so the Mac's unit tests (dm/host/tests/t_android_psogate.c) can check it.
// Three kinds of thread compile pipelines off the frame path: the asynchronous threads (DM_VK_PSO_ASYNC: pipelines a draw is waiting for, its draws skipped until made),
// the optimizer (vk-psopt: the optimized twin of a linked pipeline that already draws) and the prewarm threads (keys of earlier runs no draw has asked for yet). The
// first kind is the frame's; the other two are speculative. Until 2026-11-11 they shared one mutex on Turnip (g_pw_one, issue #127 / #132): one compile at a time,
// whatever the phone's memory, and a frame's pipeline queued behind hundreds of prewarm keys (Ocarina of Time's Castle Town market: 431 pipelines queued, 280 draws a
// frame skipped for minutes while 'vk-psoasync' and 'vk-prewarm' took turns). The gate instead:
//  - admits as many compiles as the phone's memory allows (vkpg_slots): one always, more while MemAvailable and the memory maps' headroom have room for them, up to
//    DM_VK_PSO_SLOTS (vkpg_cap_default: one on Turnip, where more lost the device)
//    (a compile takes the driver hundreds of MB, and Turnip's compiler thousands of maps: three long ones took 58,619 of 65,530);
//  - serves the frame's compiles first: a speculative compile starts only while no frame compile waits (vkpg_may_enter);
//  - lets a shader known to compile long (the .heavy list) run alone, as pw_heavy_hold did, without letting two of them pass at once.
#ifndef VKA_PSOGATE_H
#define VKA_PSOGATE_H
#include <stdint.h>

enum { VKPG_CAP = 4,                // DM_VK_PSO_SLOTS default: compiles at once at most (vkpg_cap_default: 1 on Turnip)
       VKPG_CAP_MAX = 8,            // ... and the most it may ask for
       VKPG_MEM_FREE_MB = 2048,     // MemAvailable under which only one compile runs (DM_VK_PREWARM_MEM_MB's default: the share the game and the system keep) ...
       VKPG_MEM_PER_MB = 512,       // ... and above it one more for each of these (a compile's share of the driver's memory, hundreds of MB)
       VKPG_MAPS_BUDGET_PCT = 75,   // the memory maps' budget (vka_mapbudget.h VKMB_BUDGET_PCT, where compaction starts) ...
       VKPG_MAPS_PER = 10000 };     // ... and the maps one more compile may take below it (three long Turnip compiles took ~14,000 each outside the guest block)

// Compiles that may run at once: at least 1 (the frame's pipelines must go on), at most cap; avail_mb 0 = MemAvailable not known, maps_max <= 0 or maps < 0 = the maps
// not known: that bound is not applied
static inline int vkpg_slots(uint64_t avail_mb, long maps, long maps_max, int cap) {
    if (cap < 1) cap = 1;
    if (cap > VKPG_CAP_MAX) cap = VKPG_CAP_MAX;
    int n = cap;
    if (avail_mb) {
        int m = avail_mb <= VKPG_MEM_FREE_MB ? 1 : 1 + (int)((avail_mb - VKPG_MEM_FREE_MB) / VKPG_MEM_PER_MB);
        if (m < n) n = m;
    }
    if (maps_max > 0 && maps >= 0) {
        long room = maps_max * VKPG_MAPS_BUDGET_PCT / 100 - maps;
        long m = room <= 0 ? 1 : 1 + room / VKPG_MAPS_PER;
        if (m < n) n = (int)m;
    }
    return n < 1 ? 1 : n;
}

// DM_VK_PSO_SLOTS's default by driver (driverID): one on Turnip (VK_DRIVER_ID_MESA_TURNIP), as g_pw_one ran them before 2026-11-11. With the gate at 4 slots Mario Kart
// World lost the device on Turnip V39 twice in two runs (frames 484 and 416, `submission N: -4`), and ran on with DM_VK_PSO_SLOTS=1; the cause is not known (open in
// docs/reference/rendering.md), so Turnip keeps one background compile at a time until it is
static inline int vkpg_cap_default(int turnip) { return turnip ? 1 : VKPG_CAP; }

// The asynchronous threads' queue (vka_pso.c as_main): which job a thread takes next. Turnip compiles a library part of most shaders in tens of milliseconds and of a
// few in 1.6-3.8 s (Ocarina of Time's Castle Town market, cold: 58 of ~270 libraries took 120 of 125 s of library time, pixel shaders of 99-130 KB SPIR-V and vertex
// shaders of 88-92 KB; those under 70 KB took 25-170 ms). Taken in arrival order, every slow one held the hundreds queued behind it (605 queued, 170 made in 140 s on
// one compile at a time). So a thread takes the job of least predicted cost (its shaders' SPIR-V bytes; a shader in .heavy last), except that the oldest job takes its
// turn once it has waited fair_ns and the cheapest picks have had at least as much compile time as the oldest ones (credit: compile ns of cheapest picks minus those of
// oldest picks). A burst of cheap pipelines is made at once, and the slow ones still get at least half of the compile time while they wait.
static inline int vkpg_take_oldest(uint64_t oldest_wait_ns, uint64_t fair_ns, int64_t credit) { return oldest_wait_ns >= fair_ns && credit >= 0; }

// The gate's state (the caller's lock): compiles running, how many of them are of a known long shader, and the threads waiting (not counting the one asking)
typedef struct { int busy, heavy_busy, frame_wait, frame_heavy_wait, spec_wait, spec_heavy_wait; } vkpg_state;

// Whether a thread may start its compile now. frame: a pipeline a draw waits for (else speculative); heavy: its shader is known to compile long (.heavy).
//  - a known long compile runs alone: none starts beside it, and it starts only once the others are done; no new compile starts while one of the frame's waits for that;
//  - a frame compile starts while a slot is free;
//  - a speculative one only while no frame compile waits and no long one waits to run alone
static inline int vkpg_may_enter(const vkpg_state *s, int slots, int frame, int heavy) {
    if (slots < 1) slots = 1;
    if (s->heavy_busy > 0) return 0;
    if (!frame && (s->frame_wait > 0 || s->frame_heavy_wait > 0)) return 0;
    if (heavy) return s->busy == 0;
    if (s->frame_heavy_wait > 0) return 0;   // draining for the frame's long one
    if (!frame && s->spec_heavy_wait > 0) return 0;
    return s->busy < slots;
}
#endif
