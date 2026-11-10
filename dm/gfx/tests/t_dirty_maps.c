// Long play under the dirty-page tracker (nvn_dirty.c), counting the memory maps its write protections leave in placed pool memory (docs/reference/memory.md "Write
// tracking of small textures fills the process's memory maps"). On the phone a placed pool is the GPU kernel driver's mapping (kgsl), whose pieces never merge again
// once mprotect has split them; the Mac merges, so the test models the driver: every boundary an mprotect call of the tracker cuts inside the range stays cut, and the
// range's maps are the cuts plus one. A pool's compaction (vka_mem.c compact: the CPU map made again at the same address) clears the cuts.
// The game: one-page textures scattered over a 256 MiB pool; every frame uploads some (protect) and writes some (the host's pre-write, as the game's writes and file
// reads go through it). The rest of the process is a fixed number of maps, and the limit is scaled down (MODEL_MAX) so the page-by-page and 64 KiB-block cuts of
// this pool alone go past it, as Mario Kart World's do past vm.max_map_count after minutes of racing.
//   unbounded: the tracker as before the budget (blocks of 64 KiB after DM_DIRTY_BLOCK_AT regions): the count climbs past the limit.
//   budget:    the maps thread's policy (vka_mapbudget.h; vka_mem.c maps_tick) applied every frame: coarser blocks as the count climbs, compaction past the budget.
//              The count must stay under the limit for the whole run, and every write must still be seen.
//   late:      the policy only from the first tick at 80 % of the limit on (splits made before it, as the pages split before blocks started on the phone):
//              compaction must bring the count back under the budget, and coarse blocks keep it there.
#include <stdatomic.h>
#define mprotect t_mprotect
#include "../nvn/nvn_dirty.c"
#undef mprotect
int mprotect(void *, size_t, int);
#include "../vk_android/vka_mapbudget.h"
#include <stdio.h>
#include <sys/wait.h>

module_t *g_mod;
int g_nmod;
module_t *module_of(const void *p) { (void)p; return NULL; }
void crash_install(void);

enum { MODEL_MAX = 4000, OTHER = 1200, FRAMES = 3000, UPLOADS = 64, WRITES = 32, TICK = 1 };
#define POOL (256u << 20)
static uint8_t *g_pool; static size_t g_pg, g_np;
static uint8_t *g_cut; static long g_ncut;   // a boundary before page i was cut (the driver's mapping stays split there)
static void cut(uintptr_t a) { if (a <= (uintptr_t)g_pool || a >= (uintptr_t)g_pool + POOL) return; size_t i = (a - (uintptr_t)g_pool) / g_pg; if (!g_cut[i]) { g_cut[i] = 1; g_ncut++; } }
int t_mprotect(void *a, size_t n, int prot) { cut((uintptr_t)a); cut((uintptr_t)a + n); return mprotect(a, n, prot); }
static long maps(void) { return OTHER + g_ncut + 1; }

static uint64_t rng = 88172645463325252ull;
static uint64_t next(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return rng; }

typedef struct { long peak, end, compactions, missed; size_t block; } result;
static result play(int budget) {   // 0 unbounded, 1 budget, 2 late
    result r = {0};
    g_pg = (size_t)getpagesize(); g_np = POOL / g_pg; g_cut = calloc(g_np, 1);
    g_pool = mmap(NULL, POOL, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (g_pool == MAP_FAILED) { perror("mmap"); exit(2); }
    nvn_dirty_add_range(g_pool, POOL);
    size_t ntex = g_np / 2; uint64_t *stamp = calloc(ntex, sizeof *stamp);   // texture t: page 2t (one page each, a page apart)
    for (int f = 1; f <= FRAMES; f++) {
        for (int k = 0; k < UPLOADS; k++) { size_t t = next() % ntex; stamp[t] = nvn_dirty_now(); nvn_dirty_protect(g_pool + 2 * t * g_pg, g_pg); }
        for (int k = 0; k < WRITES; k++) {
            size_t t = next() % ntex; uint8_t *p = g_pool + 2 * t * g_pg;
            prep_write(p, 16); p[0] = (uint8_t)f;   // (a page left read-only here would stop the test with SIGBUS)
            if (stamp[t] && !nvn_dirty_since(p, 16, stamp[t])) r.missed++;   // a write after the texture's upload must be seen
        }
        if (budget == 2 && maps() * 100 >= MODEL_MAX * 80) budget = 1;
        if (budget == 1 && !(f % TICK)) {   // the maps thread's tick
            size_t want = vkmb_block(maps(), MODEL_MAX, 64 << 10);
            if (want > nvn_dirty_block_bytes()) nvn_dirty_block_at_least(want);
            long shed = vkmb_shed(maps(), MODEL_MAX, VKMB_BUDGET_PCT, VKMB_TARGET_PCT);
            int pieces = (int)(g_ncut + 1), idx; long covered = 0;
            if (shed > 0 && vkmb_pick(&pieces, 1, shed, &idx, 1, &covered)) {   // compaction: the tracker held (its pages released and stamped), the map made again in one piece
                nvn_dirty_hold(g_pool, POOL); memset(g_cut, 0, g_np); g_ncut = 0; nvn_dirty_unhold(); r.compactions++;
            }
        }
        if (maps() > r.peak) r.peak = maps();
    }
    r.end = maps(); r.block = nvn_dirty_block_bytes();
    return r;
}

static int g_fail;
#define CHECK(c, ...) do { int ok_ = (c); printf("%-80s %s\n", __VA_ARGS__, ok_ ? "✓" : "✗"); if (!ok_) g_fail++; } while (0)

// Each run in a process of its own: the tracker's state is the process's
static result run(int budget) {
    int fd[2]; if (pipe(fd)) { perror("pipe"); exit(2); }
    pid_t pid = fork();
    if (!pid) { close(fd[0]); crash_install(); result r = play(budget); if (write(fd[1], &r, sizeof r) != sizeof r) _exit(3); _exit(0); }
    close(fd[1]); result r = {0}; ssize_t n = read(fd[0], &r, sizeof r); close(fd[0]);
    int st = 0; waitpid(pid, &st, 0);
    if (n != sizeof r || !WIFEXITED(st) || WEXITSTATUS(st)) { printf("run (budget %d) did not finish (status %#x)\n", budget, st); g_fail++; }
    return r;
}

int main(void) {
    setenv("DM_DIRTY_BLOCK_KB", "64", 1);     // Android's block size (the Mac's default is by the page)
    setenv("DM_DIRTY_BLOCK_AT", "4096", 1);   // scaled down with the pool (8,192 regions on the phone)
    result a = run(0), b = run(1), c = run(2);
    printf("unbounded: peak %ld maps of a model limit of %d, %ld at the end, blocks of %zu KiB, %ld writes missed\n", a.peak, MODEL_MAX, a.end, a.block >> 10, a.missed);
    printf("budget:    peak %ld maps, %ld at the end, blocks of %zu KiB, %ld compactions, %ld writes missed\n", b.peak, b.end, b.block >> 10, b.compactions, b.missed);
    printf("late:      peak %ld maps, %ld at the end, blocks of %zu KiB, %ld compactions, %ld writes missed\n", c.peak, c.end, c.block >> 10, c.compactions, c.missed);
    CHECK(a.peak > MODEL_MAX, "without the budget the pool's splits go past the limit (the failure this models)");
    CHECK(b.peak < MODEL_MAX, "with the budget the count stays under the limit for the whole run");
    CHECK(b.peak < MODEL_MAX * (VKMB_BUDGET_PCT + 5) / 100, "and close to the budget (it is acted on within a tick)");
    CHECK(b.block >= (1u << 20) && !b.compactions, "coarser blocks alone hold it there when the policy runs from the start");
    CHECK(c.peak < MODEL_MAX && c.end < MODEL_MAX * VKMB_BUDGET_PCT / 100, "started late: under the limit, and under the budget at the end");
    CHECK(c.compactions >= 1 && c.compactions <= 2, "started late: compaction runs, once or twice (coarse blocks keep the regrowth small)");
    CHECK(!a.missed && !b.missed && !c.missed, "no write after an upload is missed, in any run");
    printf("t_dirty_maps: %s\n", g_fail ? "FAILED" : "all passed");
    return g_fail != 0;
}
