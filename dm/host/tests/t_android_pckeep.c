// Which pipeline cache file a run reads after a shader library update (dm/gfx/vk_android/vka_pckeep.h; vka_pso.c pc_open_once): the stamp's text, the keep / drop
// decision and the stale count across updates, with a library made of a table of keys and entry hashes
#include "../../gfx/vk_android/vka_pckeep.h"
#include <stdio.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

typedef struct { const vka_pck_ent *e; size_t n; } lib_t;   // a library: key -> entry hash
static uint64_t hash_of(void *ctx, uint64_t key) { const lib_t *L = ctx; for (size_t i = 0; i < L->n; i++) if (L->e[i].key == key) return L->e[i].hash; return 0; }

// A save of library lib with the file's kept list and this run's shaders, read back as the next run sees it
static vka_pck_stamp save(uint64_t lib, const vka_pck_stamp *kept, const vka_pck_ent *run, size_t nr) {
    size_t len = 0; char *t = vka_pck_format(lib, kept ? kept->dead : 0, kept ? kept->ent : NULL, kept ? kept->n : 0, run, nr, &len);
    vka_pck_stamp s; vka_pck_parse(t ? t : "", t ? len : 0, &s); free(t);
    return s;
}

static void test_parse(void) {
    vka_pck_stamp s;
    vka_pck_parse("", 0, &s); CHECK(!s.lib && !s.table, "empty stamp: lib %llx", (unsigned long long)s.lib);
    const char *old = "72c5210a46e5abcd\n";   // an older host's stamp: the library only
    vka_pck_parse(old, strlen(old), &s); CHECK(s.lib == 0x72c5210a46e5abcdull && !s.table && !s.n, "older stamp: %llx table %d", (unsigned long long)s.lib, s.table); vka_pck_free(&s);
    const char *v2 = "00000000000000aa\nkeep 1 dead 3 shaders 2\n0000000000000002 00000000000000b2\n0000000000000001 00000000000000b1\n";
    vka_pck_parse(v2, strlen(v2), &s);
    CHECK(s.lib == 0xaa && s.table && s.dead == 3 && s.n == 2 && s.ent[0].key == 1 && s.ent[0].hash == 0xb1 && s.ent[1].key == 2, "stamp with its list (sorted by key)");
    vka_pck_free(&s);
    const char *cut = "00000000000000aa\nkeep 1 dead 0 shaders 3\n0000000000000001 00000000000000b1\n";   // a list cut short: the library only, never a short list
    vka_pck_parse(cut, strlen(cut), &s); CHECK(s.lib == 0xaa && !s.table && !s.n, "a cut list is not taken"); vka_pck_free(&s);
    const char *junk = "not a stamp\n";
    vka_pck_parse(junk, strlen(junk), &s); CHECK(!s.lib, "junk read as library %llx", (unsigned long long)s.lib); vka_pck_free(&s);
    // The text an older host reads: its first line is the library, as before
    size_t len; vka_pck_ent e[1] = {{5, 6}}; char *t = vka_pck_format(0x1234, 0, NULL, 0, e, 1, &len);
    CHECK(t && !strncmp(t, "0000000000001234\n", 17) && strtoull(t, NULL, 16) == 0x1234, "first line: %.17s", t ? t : "");
    free(t);
}

static void test_format_merge(void) {
    vka_pck_ent keep[2] = {{1, 0x11}, {3, 0x33}}, run[3] = {{4, 0x44}, {1, 0x1f}, {2, 0x22}};
    vka_pck_stamp k = {.lib = 7, .table = 1, .dead = 2, .ent = keep, .n = 2};
    vka_pck_stamp s = save(7, &k, run, 3);
    CHECK(s.table && s.n == 4 && s.dead == 2, "merged list: %zu shaders, dead %llu", s.n, (unsigned long long)s.dead);
    if (s.n == 4) {
        CHECK(s.ent[0].key == 1 && s.ent[0].hash == 0x1f, "a key of both lists takes this run's hash (%llx)", (unsigned long long)s.ent[0].hash);
        CHECK(s.ent[1].key == 2 && s.ent[2].key == 3 && s.ent[2].hash == 0x33 && s.ent[3].key == 4, "the kept and new shaders by key");
    }
    vka_pck_free(&s);
}

static void test_decide(void) {
    // Library A: 10 shaders; B: A restamped (header only: every entry the same); C: B with 2 changed and 1 dropped; D: C with 3 more changed
    vka_pck_ent A[10]; for (int i = 0; i < 10; i++) A[i] = (vka_pck_ent){0x100 + (uint64_t)i, 0xa00 + (uint64_t)i};
    lib_t LA = {A, 10}, LB = {A, 10};
    vka_pck_ent C[9]; memcpy(C, A, 9 * sizeof *C); C[0].hash = 0xc00; C[1].hash = 0xc01;   // 0x109 dropped
    lib_t LC = {C, 9};
    vka_pck_ent D[9]; memcpy(D, C, sizeof D); D[2].hash = 0xd02; D[3].hash = 0xd03; D[4].hash = 0xd04;
    lib_t LD = {D, 9};
    size_t live, changed; uint64_t dead; vka_pck_stamp out;

    // A run of A saves its 10 shaders; the next run of A reads the file
    vka_pck_stamp sa = save(0xA, NULL, A, 10);
    int d = vka_pck_decide(&sa, 0xA, 50, hash_of, &LA, &out, &live, &changed, &dead);
    CHECK(d == VKA_PCK_SAME && out.n == 10 && !dead, "same library: %d, %zu kept", d, out.n);
    vka_pck_free(&out);

    // B is A restamped: everything kept, nothing stale
    d = vka_pck_decide(&sa, 0xB, 50, hash_of, &LB, &out, &live, &changed, &dead);
    CHECK(d == VKA_PCK_CARRIED && live == 10 && !changed && !dead && out.n == 10, "restamped library: %d, live %zu changed %zu dead %llu", d, live, changed, (unsigned long long)dead);
    vka_pck_stamp sb = save(0xB, &out, A, 10); vka_pck_free(&out);
    CHECK(sb.lib == 0xB && sb.n == 10 && !sb.dead, "B's stamp");

    // C: 2 changed, 1 dropped; 3 stale of 7 kept is within 50 %
    d = vka_pck_decide(&sb, 0xC, 50, hash_of, &LC, &out, &live, &changed, &dead);
    CHECK(d == VKA_PCK_CARRIED && live == 7 && changed == 3 && dead == 3 && out.n == 7, "delta update: %d, live %zu changed %zu dead %llu", d, live, changed, (unsigned long long)dead);
    for (size_t i = 0; i < out.n; i++) CHECK(out.ent[i].key >= 0x102 && out.ent[i].key <= 0x108, "kept %llx, which C changed or dropped", (unsigned long long)out.ent[i].key);
    // the run makes C's shaders (the changed ones with their new hashes): the stamp lists all 9 as live and keeps the 3 stale
    vka_pck_stamp sc = save(0xC, &out, C, 9); vka_pck_free(&out);
    CHECK(sc.n == 9 && sc.dead == 3, "C's stamp: %zu shaders, %llu stale", sc.n, (unsigned long long)sc.dead);
    // ... and the same file is read again by C
    d = vka_pck_decide(&sc, 0xC, 50, hash_of, &LC, &out, &live, &changed, &dead);
    CHECK(d == VKA_PCK_SAME && dead == 3 && out.dead == 3, "C again: the stale count stays (%llu)", (unsigned long long)dead);
    vka_pck_free(&out);

    // D changes 3 more: 6 stale against 6 live passes 50 % -> started empty, nothing carried
    d = vka_pck_decide(&sc, 0xD, 50, hash_of, &LD, &out, &live, &changed, &dead);
    CHECK(d == VKA_PCK_STALE && live == 6 && changed == 3 && !out.n && !dead, "stale over the limit: %d, live %zu dead %llu", d, live, (unsigned long long)dead);
    vka_pck_free(&out);
    // with a laxer limit the file is kept, with its 6 stale shaders counted
    d = vka_pck_decide(&sc, 0xD, 100, hash_of, &LD, &out, &live, &changed, &dead);
    CHECK(d == VKA_PCK_CARRIED && dead == 6 && out.dead == 6, "DM_VK_PCACHE_KEEP=100: %d, dead %llu", d, (unsigned long long)dead);
    vka_pck_free(&out);
    // the run after a start from empty: a stamp of D's shaders only, no stale
    vka_pck_stamp sd = save(0xD, NULL, D, 9);
    CHECK(sd.n == 9 && !sd.dead, "after a start from empty: %zu shaders, %llu stale", sd.n, (unsigned long long)sd.dead);

    // DM_VK_PCACHE_KEEP=0: another library is never read; the same one is
    d = vka_pck_decide(&sb, 0xC, 0, hash_of, &LC, &out, &live, &changed, &dead); CHECK(d == VKA_PCK_OFF && !out.n, "keep 0: %d", d); vka_pck_free(&out);
    d = vka_pck_decide(&sb, 0xB, 0, hash_of, &LB, &out, &live, &changed, &dead); CHECK(d == VKA_PCK_SAME, "keep 0, same library: %d", d); vka_pck_free(&out);

    // An older host's stamp (library only): read once when it is another library's, with nothing listed (this run's shaders are, from its save on)
    const char *old = "000000000000000a\n"; vka_pck_stamp so; vka_pck_parse(old, strlen(old), &so);
    d = vka_pck_decide(&so, 0xB, 50, hash_of, &LB, &out, &live, &changed, &dead);
    CHECK(d == VKA_PCK_UNVERIFIED && vka_pck_reads(d) && !out.n && !dead, "older stamp, another library: %d", d); vka_pck_free(&out);
    d = vka_pck_decide(&so, 0xA, 50, hash_of, &LA, &out, &live, &changed, &dead); CHECK(d == VKA_PCK_SAME, "older stamp, same library: %d", d); vka_pck_free(&out);
    // No stamp: the file may hold every library's shaders; never read
    vka_pck_stamp none; vka_pck_parse("", 0, &none);
    d = vka_pck_decide(&none, 0xB, 50, hash_of, &LB, &out, &live, &changed, &dead); CHECK(d == VKA_PCK_NO_STAMP && !vka_pck_reads(d), "no stamp: %d", d); vka_pck_free(&out);
    // A library of which nothing in the file is left: started empty
    lib_t LE = {D, 0};
    d = vka_pck_decide(&sb, 0xE, 50, hash_of, &LE, &out, &live, &changed, &dead); CHECK(d == VKA_PCK_STALE && !live && changed == 10, "nothing left: %d", d); vka_pck_free(&out);

    vka_pck_free(&sa); vka_pck_free(&sb); vka_pck_free(&sc); vka_pck_free(&sd); vka_pck_free(&so);
}

int main(void) {
    test_parse(); test_format_merge(); test_decide();
    if (g_fail) { printf("t_android_pckeep: %d FAILED\n", g_fail); return 1; }
    printf("t_android_pckeep: ok\n");
    return 0;
}
