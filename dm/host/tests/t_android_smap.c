// The texture cache's serial -> image map and its set of early CPU writes (dm/gfx/vk_android/vka_smap.h; vka_tex.c "Images by serial"): against a plain array, through
// serials that collide into one probe chain with tombstones in it (a put of a live key behind a tombstone must not store it twice: the delete would leave a copy), the live count,
// the entries of an image going together, and the table staying small under a churn of many short-lived serials
#include <stdio.h>
#include <stdint.h>
#include <string.h>
typedef struct img { int id; } img;
#define VKA_SMAP_IM img
#include "../../gfx/vk_android/vka_smap.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { if (fails++ < 20) { printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } } while (0)
static uint64_t rng = 88172645463325252ull;
static uint64_t rnd(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return rng; }

static size_t count_key(const vksm *m, uint64_t s) { size_t n = 0; for (size_t i = 0; i < m->cap; i++) n += m->e[i].serial == s; return n; }
static void check_counts(const vksm *m) {
    size_t l = 0, t = 0; for (size_t i = 0; i < m->cap; i++) { if (m->e[i].serial == VKSM_TOMB) t++; else if (m->e[i].serial) l++; }
    CHECK(l == m->live && t == m->tomb, "counts live %zu/%zu tomb %zu/%zu", l, m->live, t, m->tomb);
    CHECK(!m->cap || m->live + m->tomb < m->cap, "no empty slot: %zu + %zu of %zu", m->live, m->tomb, m->cap);
}

int main(void) {
    img ims[8]; for (int i = 0; i < 8; i++) ims[i].id = i;
    // 1. a chain with a tombstone before a live key: re-put must overwrite, delete must remove it for good
    {   vksm m = {0}; uint64_t k[3]; int n = 0; CHECK(vksm_put(&m, 1, &ims[0]), "put");   // (cap 1024 now)
        size_t home = vksm_hslot(1, m.cap);
        for (uint64_t s = 2; n < 3 && s < 1000000; s++) if (vksm_hslot(s, m.cap) == home) k[n++] = s;
        CHECK(n == 3, "no collisions found");
        vksm_put(&m, k[0], &ims[1]); vksm_put(&m, k[1], &ims[2]);   // chain: 1, k0, k1
        CHECK(vksm_del(&m, 1), "del head"); CHECK(vksm_del(&m, k[0]), "del mid");   // tombstones before k1
        CHECK(vksm_put(&m, k[1], &ims[3]), "re-put");
        CHECK(count_key(&m, k[1]) == 1, "k1 stored %zu times", count_key(&m, k[1]));
        CHECK(vksm_get(&m, k[1]) && vksm_get(&m, k[1])->im == &ims[3], "k1 image");
        CHECK(m.live == 1 && m.tomb == 2, "live %zu tomb %zu", m.live, m.tomb);
        CHECK(vksm_del(&m, k[1]), "del k1"); CHECK(!vksm_get(&m, k[1]), "k1 still found after delete: a stale copy");
        vksm_put(&m, k[2], &ims[4]); CHECK(m.tomb == 2, "a new key takes the first tombstone: tomb %zu", m.tomb);
        check_counts(&m); free(m.e); }
    // 2. random operations against a reference
    {   enum { K = 3000 }; static img *ref[K]; static int has[K]; vksm m = {0};
        for (int it = 0; it < 400000; it++) {
            int k = (int)(rnd() % K); uint64_t s = (uint64_t)k * 4096 + 7;
            if (rnd() % 3 < 2) { img *im = &ims[rnd() % 8]; if (rnd() % 16 == 0) im = NULL; CHECK(vksm_put(&m, s, im), "put"); ref[k] = im; has[k] = 1; }
            else { int r = vksm_del(&m, s); CHECK(r == has[k], "del said %d, had %d", r, has[k]); has[k] = 0; }
            if (it % 5000 == 0) { check_counts(&m);
                for (int j = 0; j < K; j++) { uint64_t t = (uint64_t)j * 4096 + 7; smap_ent *e = vksm_get(&m, t); size_t c = count_key(&m, t);
                    CHECK(has[j] ? e && e->im == ref[j] && c == 1 : !e && c == 0, "key %d: has %d found %d copies %zu", j, has[j], e != NULL, c); } }
        }
        // an image's entries go together
        int want = 0; for (int j = 0; j < K; j++) if (has[j] && ref[j] == &ims[1]) { has[j] = 0; want++; }
        CHECK(vksm_del_im(&m, &ims[1]) == want && want > 0, "del_im %d", want);
        for (size_t i = 0; i < m.cap; i++) CHECK(!(m.e[i].serial && m.e[i].serial != VKSM_TOMB && m.e[i].im == &ims[1]), "image left an entry");
        check_counts(&m); CHECK(m.cap <= 16384, "cap %zu after random ops on %d keys", m.cap, K); free(m.e); }
    // 3. churn: a million short-lived serials, a few live at a time: the table does not grow
    {   vksm m = {0}; for (uint64_t s = 1; s <= 1000000; s++) { vksm_put(&m, s, &ims[s & 7]); if (s > 40) CHECK(vksm_del(&m, s - 40), "del"); }
        CHECK(m.live == 40, "live %zu", m.live); CHECK(m.cap == 1024, "cap grew to %zu", m.cap); check_counts(&m);
        for (uint64_t s = 1; s <= 1000000 - 40; s += 9973) CHECK(!vksm_get(&m, s), "stale %llu", (unsigned long long)s);
        // many live entries do grow it, and keep them all
        for (uint64_t s = 2000000; s < 2000000 + 5000; s++) vksm_put(&m, s, &ims[1]);
        CHECK(m.live == 5040 && m.cap >= 8192 && m.cap <= 32768, "live %zu cap %zu", m.live, m.cap);
        for (uint64_t s = 2000000; s < 2000000 + 5000; s++) CHECK(vksm_get(&m, s), "lost %llu", (unsigned long long)s);
        check_counts(&m); free(m.e); }
    // 4. the set (cpuw): add twice, remove once
    {   vksm m = {0}; vksm_put(&m, 5, NULL); vksm_put(&m, 5, NULL); CHECK(m.live == 1, "set live %zu", m.live);
        CHECK(vksm_del(&m, 5) && !vksm_del(&m, 5) && !vksm_get(&m, 5), "set remove"); free(m.e); }
    printf(fails ? "t_android_smap: %d FAILED\n" : "t_android_smap: OK\n", fails); return fails != 0;
}
