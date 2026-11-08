// The prewarm list's file format (dm/gfx/vk_android/vka_pwlist.h; vka_pso.c pw_*): records of each kind checked and read back, damaged files refused, and every
// profile's seed list (dm/games/<id>/vk_prewarm.bin, compiled into the Android host) readable by this host's key layout: a vka_gkey change that leaves a seed behind
// fails here (make it again with dm/tools/vk_prewarm.py from runs of the new host) instead of every first start on the phone going cold
#include "../../gfx/vk_android/vka_pwlist.h"
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#ifndef GAMES_DIR
#define GAMES_DIR "../../games"   // (the Makefile passes the absolute path)
#endif

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static vka_gkey key_tri(void) {   // a plain opaque triangle pipeline's key with vertex input dynamic state (what the frame path keys)
    vka_gkey k; memset(&k, 0, sizeof k);
    k.cfmt[0] = 37; k.dfmt = 126; k.ncolor = 1; k.samples = 1; k.topo = 3; k.att[0].mask = 0xf;
    return k;
}
// A list of n records into buf (NXVKPW2): its length
static size_t build(uint8_t *buf, const pwrec *r, const vka_gkey *k, int n) {
    pwhdr2 h; memset(&h, 0, sizeof h); memcpy(h.magic, VKA_PW_MAGIC2, 8); h.gkey_size = sizeof(vka_gkey); h.n = (uint32_t)n; h.layout = vka_pw_layout();
    memcpy(buf, &h, sizeof h); size_t o = sizeof h;
    for (int i = 0; i < n; i++) { memcpy(buf + o, &r[i], sizeof r[i]); memset(buf + o + sizeof r[i], 0, vka_pw_size(&r[i]) - sizeof r[i]); memcpy(buf + o + sizeof r[i], &k[i], r[i].klen); o += vka_pw_size(&r[i]); }
    return o;
}

static void test_records(void) {
    vka_gkey k = key_tri(); uint32_t kl = (uint32_t)offsetof(vka_gkey, va);
    pwrec g = {.kind = VKA_PW_GFX, .klen = kl, .key = {0x11, 0x22}};
    pwrec c = {.kind = VKA_PW_CS, .klen = 0, .key = {0x33}};
    pwrec m = {.kind = VKA_PW_MESH, .klen = kl, .key = {0x44, 0x55, 0x66}};   // mesh, fragment, task
    pwrec mt = {.kind = VKA_PW_MESH, .klen = kl, .key = {0x44, 0x55}};          // no task shader
    CHECK(vka_pw_rec_ok(&g, (const uint8_t *)&k), "vertex record");
    CHECK(vka_pw_rec_ok(&c, (const uint8_t *)&k), "compute record");
    CHECK(vka_pw_rec_ok(&m, (const uint8_t *)&k) && vka_pw_rec_ok(&mt, (const uint8_t *)&k), "mesh records");
    pwrec bad = m; bad.key[1] = 0; CHECK(!vka_pw_rec_ok(&bad, (const uint8_t *)&k), "a mesh record needs its fragment shader");
    bad = m; bad.key[3] = 7; CHECK(!vka_pw_rec_ok(&bad, (const uint8_t *)&k), "a mesh record has three slots");
    bad = m; bad.inst[0] = 1; CHECK(!vka_pw_rec_ok(&bad, (const uint8_t *)&k), "a mesh record is never instanced");
    bad = g; bad.kind = 3; CHECK(!vka_pw_rec_ok(&bad, (const uint8_t *)&k), "an unknown kind is skipped (a newer host's)");
    bad = c; bad.klen = 8; CHECK(!vka_pw_rec_ok(&bad, (const uint8_t *)&k), "a compute record has no key bytes");
    bad = g; bad.klen = 8; CHECK(!vka_pw_rec_ok(&bad, (const uint8_t *)&k), "a key cut short");
    vka_gkey kk = k; kk.ncolor = 200; CHECK(!vka_pw_rec_ok(&g, (const uint8_t *)&kk), "an impossible colour count");
    kk = k; kk.samples = 3; CHECK(!vka_pw_rec_ok(&g, (const uint8_t *)&kk), "an impossible sample count");
    kk = k; kk.att[2].op = 9; CHECK(!vka_pw_rec_ok(&g, (const uint8_t *)&kk), "an impossible blend op");
    // The identity ignores the in-memory bad flag and tells kinds apart (a mesh and a vertex record of the same keys are two pipelines)
    pwrec gb = g; gb.bad = 1; CHECK(vka_pw_hash(&g, &k) == vka_pw_hash(&gb, &k), "bad is not part of the identity");
    pwrec gm = g; gm.kind = VKA_PW_MESH; CHECK(vka_pw_hash(&g, &k) != vka_pw_hash(&gm, &k), "kinds differ");
    vka_gkey k2 = k; k2.topo = 4; CHECK(vka_pw_hash(&g, &k) != vka_pw_hash(&g, &k2), "the state is part of the identity");
}

static void test_file(void) {
    static uint8_t buf[1 << 16]; vka_gkey k = key_tri(); uint32_t kl = (uint32_t)offsetof(vka_gkey, va);
    pwrec r[4] = {{.kind = VKA_PW_GFX, .klen = kl, .key = {1, 2}}, {.kind = VKA_PW_CS, .key = {3}}, {.kind = VKA_PW_MESH, .klen = kl, .key = {4, 5, 6}}, {.kind = 9, .klen = kl, .key = {7}}};
    vka_gkey ks[4] = {k, k, k, k};
    size_t len = build(buf, r, ks, 4), o; uint32_t n;
    CHECK(vka_pw_header(buf, len, &o, &n) == VKA_PW_OK && o == sizeof(pwhdr2) && n == 4, "header");
    pwrec x; const uint8_t *kb; int ok, got = 0, oks = 0, kinds = 0;
    while (got < (int)n && vka_pw_next(buf, len, &o, &x, &kb, &ok)) { got++; oks += ok; if (ok) kinds |= 1 << x.kind; }
    CHECK(got == 4 && oks == 3 && kinds == 7, "records read %d, %d usable, kinds %#x (the unknown kind skipped)", got, oks, kinds);
    // Damage: another layout, another format, a record running past the end
    pwhdr2 h; memcpy(&h, buf, sizeof h); h.layout ^= 1; memcpy(buf, &h, sizeof h);
    CHECK(vka_pw_header(buf, len, &o, &n) == VKA_PW_LAYOUT, "another key layout");
    h.layout ^= 1; h.gkey_size++; memcpy(buf, &h, sizeof h); CHECK(vka_pw_header(buf, len, &o, &n) == VKA_PW_LAYOUT, "another key size");
    h.gkey_size--; memcpy(buf, &h, sizeof h);
    memcpy(buf, "NOTALIST", 8); CHECK(vka_pw_header(buf, len, &o, &n) == VKA_PW_FORMAT, "another format"); memcpy(buf, VKA_PW_MAGIC2, 8);
    CHECK(vka_pw_header(buf, 4, &o, &n) == VKA_PW_SHORT, "too short");
    CHECK(vka_pw_header(buf, len, &o, &n) == VKA_PW_OK, "restored");
    got = 0; while (vka_pw_next(buf, len - 9, &o, &x, &kb, &ok)) got++;
    CHECK(got == 3, "a truncated last record is not read (%d)", got);
}

// Every profile's seed list: this host's layout, every record usable, no record twice
static void test_seeds(void) {
    DIR *d = opendir(GAMES_DIR); struct dirent *e; int seeds = 0;
    while (d && (e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char p[1024]; snprintf(p, sizeof p, "%s/%s/vk_prewarm.bin", GAMES_DIR, e->d_name);
        FILE *f = fopen(p, "rb"); if (!f) continue;
        fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
        uint8_t *b = sz > 0 ? malloc((size_t)sz) : NULL; size_t rd = b ? fread(b, 1, (size_t)sz, f) : 0; fclose(f);
        seeds++;
        size_t o; uint32_t n; int h = vka_pw_header(b, rd, &o, &n);
        CHECK(h == VKA_PW_OK, "%s: %s: make it again with dm/tools/vk_prewarm.py seed from runs of this host", p, h == VKA_PW_LAYOUT ? "another key layout (vka_gkey changed)" : "not a prewarm list");
        if (h == VKA_PW_OK) {
            uint64_t *hs = calloc(n ? n : 1, sizeof *hs); uint32_t got = 0, bad = 0, dup = 0; pwrec x; const uint8_t *kb; int ok;
            while (got < n && vka_pw_next(b, rd, &o, &x, &kb, &ok)) { if (!ok || x.bad) bad++; uint64_t hh = vka_pw_hash(&x, kb); for (uint32_t i = 0; i < got; i++) if (hs[i] == hh) { dup++; break; } hs[got++] = hh; }
            CHECK(got == n && o == rd && !bad && !dup, "%s: %u of %u records read, %u unusable, %u twice, %zu of %zu bytes", p, got, n, bad, dup, o, rd);
            printf("seed %s: %u keys\n", e->d_name, got);
            free(hs);
        }
        free(b);
    }
    if (d) closedir(d);
    CHECK(d != NULL, "no profiles at %s", GAMES_DIR);
    (void)seeds;
}

int main(void) {
    test_records(); test_file(); test_seeds();
    if (g_fail) { printf("t_android_pwlist: %d failures\n", g_fail); return 1; }
    printf("t_android_pwlist: ok\n");
    return 0;
}
