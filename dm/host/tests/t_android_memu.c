// NVN2 task and mesh draws through compute (dm/gfx/vk_android/vka_memu_spv.c), on the Mac: the hand-written fixtures in memu/ (the translator's task and mesh forms:
// triangles with a payload and the workgroup ID, points with a point size, a per-primitive location and the cull array, a task module, a pixel shader of a
// per-primitive input) converted (the task / mesh module to GLCompute, the vertex shader of each mesh layout, the pixel shader made flat), checked by structure and,
// when spirv-val is on the PATH, by validation (--target-env vulkan1.3). DM_VK_MEMU_LIB=<dmsh_vk.lib> converts and validates every task and mesh module of a real
// library and every pixel shader of per-primitive inputs, as the replay makes them (the specialization constants folded first, GL depth 0 and 1); with DM_VK_MEMU_OUT=<folder>
// each module (GL depth 0) is written there as <key>.in.spv (folded) and <key>.cs.spv / .vs.spv / .flat.spv, for a look with spirv-dis or an interpreter
#include "../../gfx/vk_android/vka_memu_spv.h"
#include "../../gfx/vk_android/vka_memu_abi.h"
#include "../../gfx/vk_android/vka_memu_order.h"
#include "../../gfx/vk_android/vka_spvpatch.h"
#include "../../gfx/vk_android/vka_shlib.h"
#include "memu/memu_fixtures.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
static int g_val;
static int validate(const uint32_t *w, size_t n, const char *tag) {
    if (!g_val) return 1;
    char path[256]; snprintf(path, sizeof path, "/tmp/t_memu_%d.spv", (int)getpid());
    FILE *f = fopen(path, "wb"); if (!f) return 0; fwrite(w, 4, n, f); fclose(f);
    char cmd[400]; snprintf(cmd, sizeof cmd, "spirv-val --target-env vulkan1.3 %s", path);
    int r = system(cmd);
    if (r) { char keep[300]; snprintf(keep, sizeof keep, "/tmp/t_memu_fail_%s.spv", tag); rename(path, keep); printf("  spirv-val failed on %s (kept as %s)\n", tag, keep); }
    else remove(path);
    return r == 0;
}
static int count_op(const uint32_t *w, size_t n, uint32_t op) { int k = 0; for (size_t i = 5; i < n; i += w[i] >> 16) { if (!(w[i] >> 16)) break; k += (w[i] & 0xffff) == op; } return k; }
static int count_dec(const uint32_t *w, size_t n, uint32_t dec) { int k = 0; for (size_t i = 5; i < n; i += w[i] >> 16) { if (!(w[i] >> 16)) break; k += (w[i] & 0xffff) == 71 && (w[i] >> 16) >= 3 && w[i + 2] == dec; } return k; }
static int count_var(const uint32_t *w, size_t n, uint32_t sc) { int k = 0; for (size_t i = 5; i < n; i += w[i] >> 16) { if (!(w[i] >> 16)) break; k += (w[i] & 0xffff) == 59 && (w[i] >> 16) >= 4 && w[i + 3] == sc; } return k; }
static uint32_t model_of(const uint32_t *w, size_t n) { for (size_t i = 5; i < n; i += w[i] >> 16) { if (!(w[i] >> 16)) break; if ((w[i] & 0xffff) == 15) return w[i + 1]; } return ~0u; }

// A task or mesh module as the replay makes it (vka_shader.c memu_cs_words): its specialization constants folded (GL depth gl), then converted
static const char *g_out;
static void dump(const char *tag, const char *kind, const uint32_t *w, size_t n) {
    if (!g_out) return;
    char p[1024]; snprintf(p, sizeof p, "%s/%s.%s.spv", g_out, tag, kind);
    FILE *f = fopen(p, "wb"); if (f) { fwrite(w, 4, n, f); fclose(f); }
}
static int convert(const uint32_t *w, size_t nw, int gl, const char *tag, vka_memu_layout *L) {
    char err[256]; size_t fn = 0; uint32_t *fz = vka_spv_spec_freeze(w, nw, 0, (uint32_t)gl, &fn);
    const uint32_t *src = fz ? fz : w; size_t sn = fz ? fn : nw;
    if (!vka_memu_layout_of(src, sn, L, err, sizeof err)) { printf("  %s: layout refused: %s\n", tag, err); free(fz); return 0; }
    size_t on = 0; uint32_t *o = vka_memu_cs(src, sn, &on, err, sizeof err);
    int ok = o != NULL, out = g_out && !gl;
    if (out) dump(tag, "in", src, sn);
    if (o && out) dump(tag, "cs", o, on);
    CHECK(o, "%s: converted (%s)", tag, err);
    if (o) {
        CHECK(model_of(o, on) == 5, "%s: GLCompute", tag);
        CHECK(count_var(o, on, 3) == 0, "%s: no Output variable", tag);
        CHECK(count_var(o, on, 5402) == 0, "%s: no task payload variable", tag);
        CHECK(count_op(o, on, 5294) == 0 && count_op(o, on, 5295) == 0, "%s: no OpEmitMeshTasksEXT / OpSetMeshOutputsEXT", tag);
        CHECK(count_dec(o, on, 5271) == 0, "%s: no PerPrimitiveEXT", tag);
        ok = validate(o, on, tag); CHECK(ok, "%s: converted module valid", tag);
        free(o);
    }
    if (ok && !L->task) {
        uint32_t *v = vka_memu_vs(L, &on, err, sizeof err);
        CHECK(v, "%s: vertex shader made (%s)", tag, err);
        if (v) { char t2[200]; snprintf(t2, sizeof t2, "%s_vs", tag); ok = validate(v, on, t2); CHECK(ok, "%s: vertex shader valid", tag); if (out) dump(tag, "vs", v, on); free(v); }
    }
    free(fz);
    return ok;
}

// A batch's entries matched to the draws replayed (vka_memu_order.h): a record met twice in one look-ahead (one command list called for the depth prepass, then for
// the colour pass, another program bound between) has an entry per meeting, taken in order; the old lookup (the record's first entry) gave the second meeting the first's
static void t_order(void) {
    static const char rec[4] = {0};   // three draw records: a, b, c
    const void *a = &rec[0], *b = &rec[1], *c = &rec[2];
    vka_memu_order o; vka_memu_order_reset(&o);
    // the look-ahead met a, b (the prepass), then a, b again (the colour pass), then c
    CHECK(vka_memu_order_add(&o, a) == 0 && vka_memu_order_add(&o, b) == 1 && vka_memu_order_add(&o, a) == 2 && vka_memu_order_add(&o, b) == 3 && vka_memu_order_add(&o, c) == 4, "entries in walk order");
    CHECK(vka_memu_order_find(&o, a) == 0 && vka_memu_order_find(&o, a) == 0, "find does not take");
    CHECK(vka_memu_order_take(&o, a) == 0 && vka_memu_order_take(&o, b) == 1, "prepass meetings: entries 0, 1");
    CHECK(vka_memu_order_find(&o, a) == 2, "after the prepass, record a still has an entry (the colour pass's)");
    CHECK(vka_memu_order_take(&o, a) == 2 && vka_memu_order_take(&o, b) == 3, "colour pass meetings: entries 2, 3, not the prepass's 0, 1");
    CHECK(vka_memu_order_take(&o, a) == -1 && o.next == 4 && vka_memu_order_find(&o, b) == -1, "a third meeting of a has no entry (the replay looks ahead again), the cursor unchanged");
    CHECK(vka_memu_order_take(&o, c) == 4 && o.next == 5, "c");
    // a draw the replay does not meet (a render condition skipped it) is passed over
    vka_memu_order_reset(&o); vka_memu_order_add(&o, a); vka_memu_order_add(&o, b); vka_memu_order_add(&o, c);
    CHECK(vka_memu_order_take(&o, b) == 1 && vka_memu_order_find(&o, a) == -1 && vka_memu_order_take(&o, c) == 2, "an entry not met is passed over");
    // full
    vka_memu_order_reset(&o); for (int i = 0; i < VKA_MEMU_ORDER_MAX; i++) vka_memu_order_add(&o, a);
    CHECK(vka_memu_order_add(&o, b) == -1 && o.n == VKA_MEMU_ORDER_MAX, "a full batch takes no more");
}

// A generated vertex shader against a pixel shader (vka_memu_vs_feeds' parts): the locations each writes and reads. Every pixel shader input must be a vertex output
// (Vulkan; Qualcomm refuses the pipeline with -13), so the prewarm list drops a record of a pair that leaves one unwritten
static void t_feeds(void) {
    char err[256]; vka_memu_layout L; uint64_t in = 1;
    CHECK(vka_memu_fs_input_locs(MEMU_FS_PRIM, sizeof MEMU_FS_PRIM / 4, &in) && in == 0x3, "pixel shader inputs at locations 0 and 1 (%#llx)", (unsigned long long)in);
    CHECK(vka_memu_layout_of(MEMU_MESH_TRI, sizeof MEMU_MESH_TRI / 4, &L, err, sizeof err) && vka_memu_vs_locs(&L) == 0x5, "triangle mesh writes 0 and 2");
    CHECK(in & ~vka_memu_vs_locs(&L), "triangle mesh leaves location 1 unwritten");
    CHECK(vka_memu_layout_of(MEMU_MESH_PTS, sizeof MEMU_MESH_PTS / 4, &L, err, sizeof err) && vka_memu_vs_locs(&L) == 0x2, "points mesh writes location 1 (per primitive)");
    CHECK(!vka_memu_fs_input_locs(MEMU_MESH_TRI, 3, &in), "not SPIR-V: cannot tell");
    CHECK(vka_memu_fs_input_locs(MEMU_MESH_TRI, sizeof MEMU_MESH_TRI / 4, &in) && in == 0, "a mesh module has no user inputs");
}

int main(void) {
    g_val = system("spirv-val --version >/dev/null 2>&1") == 0;
    t_order();
    t_feeds();
    char err[256]; vka_memu_layout L; size_t on;
    // The layouts
    CHECK(vka_memu_layout_of(MEMU_MESH_TRI, sizeof MEMU_MESH_TRI / 4, &L, err, sizeof err), "triangle mesh layout (%s)", err);
    CHECK(!L.task && L.max_v == 64 && L.max_p == 126 && !L.points && L.nvloc == 2 && L.vloc[0] == 0 && L.vloc[1] == 2 && !L.nploc && L.vw == 12 && L.indexed && L.payload_words == 8 && L.threads == 32,
          "triangle mesh: 64 / 126, locations 0 and 2, 12 words a vertex, indexed, 8 payload words (v %u p %u n %d vw %u idx %d pl %u)", L.max_v, L.max_p, L.nvloc, L.vw, L.indexed, L.payload_words);
    uint32_t rec[32] = {0}; vka_memu_rec_mesh(&L, rec);
    CHECK(rec[VKA_MEMU_R_VSTRIDE] == 64 * 12 && rec[VKA_MEMU_R_PSTRIDE] == 126 * 3 && rec[VKA_MEMU_R_DRAWV] == 378 && (rec[VKA_MEMU_R_FLAGS] & VKA_MEMU_F_INDEXED), "indexed record words");
    CHECK(vka_memu_layout_of(MEMU_MESH_PTS, sizeof MEMU_MESH_PTS / 4, &L, err, sizeof err), "points mesh layout (%s)", err);
    CHECK(L.points && L.point_size && L.cull && L.nploc == 1 && L.ploc[0] == 1 && !L.nvloc && L.vw == 5 && L.pw == 6 && !L.indexed && !L.payload_words,
          "points mesh: point size, cull, per-primitive location 1, 5 words a vertex, 6 a point, expanded (vw %u pw %u)", L.vw, L.pw);
    memset(rec, 0, sizeof rec); vka_memu_rec_mesh(&L, rec);
    CHECK(rec[VKA_MEMU_R_VSTRIDE] == 32 * 5 + 32 * 6 && rec[VKA_MEMU_R_VSTRIDE] == rec[VKA_MEMU_R_PSTRIDE] && rec[VKA_MEMU_R_DRAWV] == 32 && (rec[VKA_MEMU_R_FLAGS] & VKA_MEMU_F_INTERLEAVED), "interleaved record words");
    CHECK(vka_memu_layout_of(MEMU_TASK, sizeof MEMU_TASK / 4, &L, err, sizeof err) && L.task && L.payload_words == 8, "task layout (%s)", err);
    CHECK(!vka_memu_layout_of(MEMU_FS_PRIM, sizeof MEMU_FS_PRIM / 4, &L, err, sizeof err), "a pixel shader is no task or mesh module");
    // The conversions
    convert(MEMU_MESH_TRI, sizeof MEMU_MESH_TRI / 4, 0, "mesh_tri", &L);
    convert(MEMU_MESH_PTS, sizeof MEMU_MESH_PTS / 4, 0, "mesh_pts", &L);
    convert(MEMU_TASK, sizeof MEMU_TASK / 4, 0, "task", &L);
    {   // the converted triangle mesh stores every word of its outputs into the arena: 4 (position) + 1 (z again) + 4 (location 0) + 1 (location 2's component) + 3 (indices)
        size_t n; uint32_t *o = vka_memu_cs(MEMU_MESH_TRI, sizeof MEMU_MESH_TRI / 4, &n, err, sizeof err);
        CHECK(o && count_op(o, n, 62) >= 13, "13 word stores into the arena at least (%d stores)", o ? count_op(o, n, 62) : -1);
        int bind = 0; for (size_t i = 5; o && i < n; i += o[i] >> 16) if ((o[i] & 0xffff) == 71 && o[i + 2] == 33 && (o[i + 3] == VKA_MEMU_ARENA_BINDING || o[i + 3] == VKA_MEMU_REC_BINDING)) bind++;
        CHECK(bind == 2, "the arena and the records bound at VKA_MEMU_ARENA_BINDING / _REC_BINDING");
        free(o);
    }
    {   // the pixel shader made flat
        uint32_t *o = vka_memu_fs_flat(MEMU_FS_PRIM, sizeof MEMU_FS_PRIM / 4, &on, err, sizeof err);
        CHECK(o && count_dec(o, on, 5271) == 0 && count_dec(o, on, 14) == 1 && count_op(o, on, 10) == 0, "per-primitive input made flat, the extension dropped (%s)", err);
        if (o) CHECK(validate(o, on, "fs_flat"), "flat pixel shader valid");
        free(o);
        o = vka_memu_fs_flat(MEMU_MESH_TRI, sizeof MEMU_MESH_TRI / 4, &on, err, sizeof err);
        CHECK(!o && !err[0], "a module without per-primitive inputs: nothing to do");
    }
    // A real library (DM_VK_MEMU_LIB)
    const char *lp = getenv("DM_VK_MEMU_LIB"); g_out = getenv("DM_VK_MEMU_OUT");
    if (lp) {
        vka_shlib S; CHECK(vka_shlib_open(&S, lp, NULL, NULL, NULL, NULL, err, sizeof err), "%s: %s", lp, err);
        int nt = 0, nm = 0, nf = 0, bad = 0, refused = 0, nidx = 0, npts = 0, nprim = 0;
        for (uint32_t i = 0; S.h && i < S.h->entry_count; i++) {
            const dmsh_vk_entry *e = &S.ent[i];
            int ts = e->stage == DMSH_VK_STAGE_TS, ms = e->stage == DMSH_VK_STAGE_MS, fs = e->stage == DMSH_VK_STAGE_FS && (e->flags & DMSH_VK_F_MESH_PRIM);
            if (!ts && !ms && !fs) continue;
            const dmsh_vk_module *m = vka_shlib_module(&S, e, DMSH_VK_MOD_BASE); if (!m) continue;
            size_t nb; uint32_t *w = vka_shlib_spirv(&S, m, &nb, err, sizeof err); if (!w) { bad++; continue; }
            char tag[64]; snprintf(tag, sizeof tag, "%s_%016llx", ts ? "ts" : ms ? "ms" : "fs", (unsigned long long)e->key);
            if (fs) { nf++; uint32_t *o = vka_memu_fs_flat(w, nb / 4, &on, err, sizeof err); if (!o || !validate(o, on, tag)) { bad++; if (!o) printf("  %s: %s\n", tag, err); } if (o) { dump(tag, "in", w, nb / 4); dump(tag, "flat", o, on); } free(o); }
            else {
                for (int gl = 0; gl < 2; gl++) {
                    if (gl && !(e->flags & DMSH_VK_F_GL_DEPTH)) break;
                    char t2[80]; snprintf(t2, sizeof t2, "%s_gl%d", tag, gl);
                    vka_memu_layout L2; char e2[256];
                    if (!vka_memu_layout_of(w, nb / 4, &L2, e2, sizeof e2)) { refused++; if (refused <= 8) printf("  %s refused: %s\n", tag, e2); break; }
                    if (!gl) { if (ts) nt++; else { nm++; nidx += L2.indexed; npts += L2.points; nprim += L2.nploc > 0; } }
                    if (!convert(w, nb / 4, gl, gl ? t2 : tag, &L2)) bad++;
                }
            }
            free(w);
        }
        printf("t_android_memu: %s: %d task and %d mesh modules converted (%d indexed, %d points, %d with per-primitive values), %d pixel shaders made flat; %d refused, %d failed\n",
               lp, nt, nm, nidx, npts, nprim, nf, refused, bad);
        CHECK(!bad, "every module of the library converts and validates");
        vka_shlib_close(&S);
    }
    printf("t_android_memu: %s%s\n", g_fail ? "FAILED" : "ok", g_val ? "" : " (no spirv-val: structure only)");
    return g_fail ? 1 : 0;
}
