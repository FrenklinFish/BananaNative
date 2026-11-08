// The Android replay's SPIR-V rewrite of vertex inputs fed integer formats (dm/gfx/vk_android/vka_spvpatch.c), on the Mac: the graphics selftest's vertex shader
// (a component read through an access chain at location 0, whole loads at location 1) retyped as uint / int, checked by structure and, when spirv-val is on the PATH,
// by validation. DM_VK_SPV_DIR=<folder of .spv vertex modules> also rewrites each of those (every vec4 input as uint) and validates it. DM_VK_GS_SPV=<a geometry module
// that writes memory> puts its writes under a per-primitive claim (vka_spv_gs_claim, after the one-invocation subgroup rewrite) and validates it
#include "../../gfx/vk_android/vka_spvpatch.h"
#include "../../gfx/vk_android/vka_selftest_spv.h"
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
static int have_val(void) { return system("spirv-val --version >/dev/null 2>&1") == 0; }
static int validate(const uint32_t *w, size_t n, const char *tag) {
    char path[256]; snprintf(path, sizeof path, "/tmp/t_spvpatch_%d.spv", (int)getpid());
    FILE *f = fopen(path, "wb"); if (!f) return 0; fwrite(w, 4, n, f); fclose(f);
    char cmd[400]; snprintf(cmd, sizeof cmd, "spirv-val --target-env vulkan1.3 %s", path);
    int r = system(cmd); remove(path);
    if (r) printf("  spirv-val failed on %s\n", tag);
    return r == 0;
}
static int count_op(const uint32_t *w, size_t n, uint32_t op) { int k = 0; for (size_t i = 5; i < n; i += w[i] >> 16) { if (!(w[i] >> 16)) break; k += (w[i] & 0xffff) == op; } return k; }
int main(void) {
    int val = have_val();
    size_t n = sizeof SPV_TRI_VERT / 4, on = 0; char err[200] = "";
    uint32_t *o = vka_spv_vin_retype(SPV_TRI_VERT, n, 1, 2, &on, err, sizeof err);
    CHECK(o, "selftest vertex shader not rewritten: %s", err);
    if (o) {
        CHECK(o[3] > SPV_TRI_VERT[3], "bound grows (%u → %u)", SPV_TRI_VERT[3], o[3]);
        CHECK(count_op(o, on, 124) >= count_op(SPV_TRI_VERT, n, 124) + 2, "a bitcast after each load of the two inputs");
        CHECK(count_op(o, on, 21) >= 2, "an int type of each signedness");
        if (val) CHECK(validate(o, on, "selftest vertex"), "valid SPIR-V");
        free(o);
    }
    CHECK(!vka_spv_vin_retype(SPV_TRI_VERT, n, 4, 0, &on, err, sizeof err), "a location the module does not read is refused");
    CHECK(!vka_spv_vin_retype(SPV_TRI_VERT, n, 1, 1, &on, err, sizeof err), "a location both uint and int is refused");
    {   // A float colour output into an integer target (vka_spv_fout_retype): the selftest's fragment shader, its vec4 output at location 0 as uvec4 / ivec4
        size_t fn = sizeof SPV_TRI_FRAG / 4;
        for (int c = 0; c < 2; c++) {
            uint32_t *f = vka_spv_fout_retype(SPV_TRI_FRAG, fn, c ? 0 : 1, c ? 1 : 0, &on, err, sizeof err);
            CHECK(f, "selftest fragment shader output not retyped (%s): %s", c ? "int" : "uint", err);
            if (!f) continue;
            CHECK(count_op(f, on, 124) >= count_op(SPV_TRI_FRAG, fn, 124) + 1, "a bitcast before each store of the output");
            CHECK(count_op(f, on, 21) >= 1, "an integer type");
            if (val) CHECK(validate(f, on, c ? "selftest fragment (int)" : "selftest fragment (uint)"), "valid SPIR-V");
            free(f);
        }
        CHECK(!vka_spv_fout_retype(SPV_TRI_FRAG, fn, 2, 0, &on, err, sizeof err), "a location the module does not write is refused");
        CHECK(!vka_spv_fout_retype(SPV_TRI_FRAG, fn, 1, 1, &on, err, sizeof err), "a location both uint and int is refused");
    }
    {   // The components a pixel shader's colour outputs write (vka_spv_fout_comps): the selftest's whole vec4 store, and a module that stores only z and w of location 0
        uint8_t m[8]; size_t fn = sizeof SPV_TRI_FRAG / 4;
        CHECK(!vka_spv_fout_comps(SPV_TRI_FRAG, fn, m) && m[0] == 0xf && !m[1], "selftest fragment: location 0 all four components (%x %x)", m[0], m[1]);
        // ids: 1 float, 2 vec4, 3 ptr Output vec4, 4 ptr Output float, 5 int, 6 const 2, 7 const 3, 8 the output, 9 / 10 chains, 11 const 1.0, 12 void, 13 fn type, 14 fn, 15 label
        static const uint32_t zw[] = {0x07230203, 0x00010000, 0, 16, 0,
            4u << 16 | 71, 8, 30, 0,                    // OpDecorate %8 Location 0
            3u << 16 | 22, 1, 32,                       // OpTypeFloat
            4u << 16 | 23, 2, 1, 4,                     // OpTypeVector %2 %1 4
            4u << 16 | 32, 3, 3, 2, 4u << 16 | 32, 4, 3, 1,
            4u << 16 | 21, 5, 32, 1,
            4u << 16 | 43, 5, 6, 2, 4u << 16 | 43, 5, 7, 3, 4u << 16 | 43, 1, 11, 0x3f800000,
            4u << 16 | 59, 3, 8, 3,                     // OpVariable %3 %8 Output
            2u << 16 | 19, 12, 3u << 16 | 33, 13, 12,
            5u << 16 | 54, 12, 14, 0, 13, 2u << 16 | 248, 15,
            5u << 16 | 65, 4, 9, 8, 6, 3u << 16 | 62, 9, 11,    // z
            5u << 16 | 65, 4, 10, 8, 7, 3u << 16 | 62, 10, 11,  // w
            1u << 16 | 253, 1u << 16 | 56};
        CHECK(!vka_spv_fout_comps(zw, sizeof zw / 4, m) && m[0] == 0xc, "stores into z and w only: mask %x", m[0]);
    }
    {   // A geometry shader writing only gl_Position between the selftest's vertex and pixel shaders (vka_spv_gs_link): the pixel input at location 0 added, copied
        size_t gn = sizeof SPV_PASS_GEOM / 4, vn = sizeof SPV_TRI_VERT / 4, fn = sizeof SPV_TRI_FRAG / 4; uint32_t cp = 0, nl = 0;
        uint32_t *g = vka_spv_gs_link(SPV_PASS_GEOM, gn, SPV_TRI_VERT, vn, SPV_TRI_FRAG, fn, &cp, &nl, &on, err, sizeof err);
        CHECK(g && cp == 1 && nl == 0, "geometry shader linked: location 0 copied (copied %#x, null %#x: %s)", cp, nl, err);
        if (g) {
            CHECK(count_op(g, on, 218) == count_op(SPV_PASS_GEOM, gn, 218), "emits as before");
            CHECK(count_op(g, on, 62) == count_op(SPV_PASS_GEOM, gn, 62) + count_op(g, on, 218), "a store of the added output before each emit");
            if (val) CHECK(validate(g, on, "gs link"), "linked geometry module valid");
            free(g);
        }
        g = vka_spv_gs_link(SPV_PASS_GEOM, gn, SPV_FILL_COMP, sizeof SPV_FILL_COMP / 4, SPV_TRI_FRAG, fn, &cp, &nl, &on, err, sizeof err);   // no vertex output there: null
        CHECK(g && cp == 0 && nl == 1, "an input no earlier stage writes is written null (copied %#x, null %#x: %s)", cp, nl, err);
        if (g && val) CHECK(validate(g, on, "gs link null"), "linked geometry module (null) valid");
        free(g);
        g = vka_spv_gs_link(SPV_PASS_GEOM, gn, SPV_TRI_VERT, vn, SPV_FILL_COMP, sizeof SPV_FILL_COMP / 4, &cp, &nl, &on, err, sizeof err);   // a next stage with no inputs
        CHECK(!g && !err[0], "nothing to add: no module, no error (%s)", err);
    }
    const char *dir = getenv("DM_VK_SPV_DIR");
    if (dir) {
        DIR *d = opendir(dir); struct dirent *e; int done = 0, refused = 0;
        while (d && (e = readdir(d))) {
            size_t l = strlen(e->d_name); if (l < 4 || strcmp(e->d_name + l - 4, ".spv")) continue;
            char p[1024]; snprintf(p, sizeof p, "%s/%s", dir, e->d_name);
            FILE *f = fopen(p, "rb"); if (!f) continue; fseek(f, 0, SEEK_END); long sz = ftell(f); rewind(f);
            uint32_t *w = malloc((size_t)sz); if (fread(w, 1, (size_t)sz, f) != (size_t)sz) { fclose(f); free(w); continue; } fclose(f);
            // every Location-decorated input: find them by a first rewrite of all 32 locations, narrowing on refusal
            uint32_t mask = 0;
            for (size_t i = 5; i < (size_t)sz / 4; i += w[i] >> 16) { if (!(w[i] >> 16)) break; if ((w[i] & 0xffff) == 71 && (w[i] >> 16) >= 4 && w[i + 2] == 30 && w[i + 3] < 32) {
                uint32_t id = w[i + 1]; for (size_t j = 5; j < (size_t)sz / 4; j += w[j] >> 16) { if (!(w[j] >> 16)) break; if ((w[j] & 0xffff) == 59 && w[j + 2] == id && w[j + 3] == 1) mask |= 1u << w[i + 3]; } } }
            uint32_t *x = mask ? vka_spv_vin_retype(w, (size_t)sz / 4, mask, 0, &on, err, sizeof err) : NULL;
            if (!x) { refused++; if (refused <= 4) printf("  %s: %s\n", e->d_name, mask ? err : "no input"); }
            else { done++; if (val) CHECK(validate(x, on, e->d_name), "%s valid", e->d_name); free(x); }
            free(w);
        }
        if (d) closedir(d);
        printf("t_android_spvpatch: %d modules rewritten, %d refused in %s\n", done, refused, dir);
    }
    const char *gs = getenv("DM_VK_GS_SPV");
    if (gs) {
        FILE *f = fopen(gs, "rb"); CHECK(f, "%s not readable", gs);
        if (f) { fseek(f, 0, SEEK_END); long sz = ftell(f); rewind(f); uint32_t *w = malloc((size_t)sz); size_t nw = fread(w, 1, (size_t)sz, f) / 4; fclose(f);
            int ch = 0; size_t sn = 0; uint32_t *sg = vka_spv_subgroup_single(w, nw, &sn, &ch, err, sizeof err); if (sg) { free(w); w = sg; nw = sn; }
            int found = vka_spv_gs_claim(w, nw, 255, NULL, NULL, err, sizeof err);
            CHECK(found >= 0, "%s: memory writes counted (%s)", gs, err);
            uint32_t *x = NULL; size_t xn = 0; int r = found > 0 ? vka_spv_gs_claim(w, nw, 255, &x, &xn, err, sizeof err) : -1;
            if (found > 0) CHECK(r == found && x, "claim module made (%s)", err);
            if (x) {
                CHECK(count_op(x, xn, 218) == count_op(w, nw, 218), "emits as before");
                CHECK(count_op(x, xn, 247) == count_op(w, nw, 247) + found, "a selection around each write");
                CHECK(count_op(x, xn, 229) == count_op(w, nw, 229) + 1, "one exchange for the claim");
                if (val) CHECK(validate(x, xn, "gs claim"), "claim module valid");
                free(x);
            }
            printf("t_android_spvpatch: %s: %d memory writes claimed\n", gs, found);
            free(w); }
    }
    // DM_VK_IMG_SPV=<module>:<binding>[,<module>:<binding>…]: the float storage image at that set 0 binding retyped as uint and as int (vka_spv_img_retype), validated; every
    // read and write of it gains a bit-cast. A binding with no such image (the stage's last) and an empty mask are refused
    const char *is = getenv("DM_VK_IMG_SPV");
    for (const char *p = is; p && *p;) {
        char path[1024]; const char *c = strchr(p, ':'); if (!c || (size_t)(c - p) >= sizeof path) break;
        memcpy(path, p, (size_t)(c - p)); path[c - p] = 0; char *e; unsigned b = (unsigned)strtoul(c + 1, &e, 10); p = *e == ',' ? e + 1 : e;
        FILE *f = fopen(path, "rb"); CHECK(f, "%s not readable", path); if (!f) continue;
        fseek(f, 0, SEEK_END); long sz = ftell(f); rewind(f); uint32_t *w = malloc((size_t)sz); size_t nw = fread(w, 1, (size_t)sz, f) / 4; fclose(f);
        int reads = count_op(w, nw, 98), writes = count_op(w, nw, 99);
        for (int k = 0; k < 2; k++) {
            uint32_t *x = vka_spv_img_retype(w, nw, b & ~63u, k ? 0 : 1ull << (b & 63), k ? 1ull << (b & 63) : 0, &on, err, sizeof err);
            CHECK(x, "%s binding %u not retyped (%s): %s", path, b, k ? "int" : "uint", err);
            if (!x) continue;
            int bc = count_op(x, on, 124) - count_op(w, nw, 124);   // (the module's other images keep theirs as they are)
            CHECK(bc > 0 && bc <= reads + writes, "%s: a bit-cast at each read and write of the image (%d of %d + %d)", path, bc, reads, writes);
            CHECK(count_op(x, on, 25) == count_op(w, nw, 25) + 1, "%s: one integer image type", path);
            if (val) CHECK(validate(x, on, path), "%s retyped valid", path);
            free(x);
        }
        CHECK((b & 63) == 63 || !vka_spv_img_retype(w, nw, b & ~63u, 1ull << 63, 0, &on, err, sizeof err), "%s: binding %u (no storage image) refused", path, (b | 63));
        CHECK(!vka_spv_img_retype(w, nw, 0, 0, 0, &on, err, sizeof err), "an empty mask refused");
        printf("t_android_spvpatch: %s binding %u retyped (%d reads, %d writes)\n", path, b, reads, writes);
        free(w);
    }
    // DM_VK_TEX_SPV=<module>:<binding>[,…]: the float texture (combined image and sampler, or sampled image) at that set 0 binding retyped as uint and as int (vka_spv_img_retype),
    // validated; each sample, fetch and gather of it gains a bit-cast back to the float vector
    const char *ts = getenv("DM_VK_TEX_SPV");
    for (const char *p = ts; p && *p;) {
        char path[1024]; const char *c = strchr(p, ':'); if (!c || (size_t)(c - p) >= sizeof path) break;
        memcpy(path, p, (size_t)(c - p)); path[c - p] = 0; char *e; unsigned b = (unsigned)strtoul(c + 1, &e, 10); p = *e == ',' ? e + 1 : e;
        FILE *f = fopen(path, "rb"); CHECK(f, "%s not readable", path); if (!f) continue;
        fseek(f, 0, SEEK_END); long sz = ftell(f); rewind(f); uint32_t *w = malloc((size_t)sz); size_t nw = fread(w, 1, (size_t)sz, f) / 4; fclose(f);
        int reads = 0; for (int op = 87; op <= 96; op++) if (op != 89 && op != 90 && op != 93 && op != 94) reads += count_op(w, nw, op);
        for (int k = 0; k < 2; k++) {
            uint32_t *x = vka_spv_img_retype(w, nw, b & ~63u, k ? 0 : 1ull << (b & 63), k ? 1ull << (b & 63) : 0, &on, err, sizeof err);
            CHECK(x, "%s texture binding %u not retyped (%s): %s", path, b, k ? "int" : "uint", err);
            if (!x) continue;
            int bc = count_op(x, on, 124) - count_op(w, nw, 124);
            CHECK(bc > 0 && bc <= reads, "%s: a bit-cast at each read of the texture (%d of %d)", path, bc, reads);
            if (val) CHECK(validate(x, on, path), "%s texture retyped valid", path);
            free(x);
        }
        printf("t_android_spvpatch: %s texture binding %u retyped (%d texture reads in the module)\n", path, b, reads);
        free(w);
    }
    printf("t_android_spvpatch: %s%s\n", g_fail ? "FAILED" : "ok", val ? "" : " (no spirv-val: structure only)");
    return g_fail != 0;
}
