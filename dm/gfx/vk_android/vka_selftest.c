// nxhost_vk_selftest: the replay end to end without a game. A region of placed guest memory holds a memory pool and a 1280x720 RGBA8 window texture; NVN command records
// written by hand (the hle_nvn.c layout) clear it, clear a scissored square, draw a textured triangle and run a compute dispatch, read texels and the dispatch's output back
// into the pool, signal an event, and the texture is presented every frame. The draw and the dispatch go through the whole draw path: a shader library made here in memory
// (three small shaders written for the library's descriptor ABI, selftest/*.vert / .frag / .comp, not game code), programs, vertex attributes and streams, a uniform buffer,
// a texture and a sampler registered in the pools, the binding walker, set 0, pipelines, the render pass; for the dispatch a storage buffer and the driver segment c0
#if defined(__ANDROID__)
#include "vka.h"
#include "vk_android.h"
#include "vka_selftest_spv.h"
#include "../nvn/nvn_mtl.h"
#include "../nvn/dmsh_chain.h"
#include "../nvn/dmsh_chain_vk.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

void mtl_pso_line(char *out, size_t n);   // vka_pso.c
static uint8_t *rec(uint8_t *p, uint32_t op, const void *a, uint32_t n) {
    op_hdr h = {op, (8 + n + 7) & ~7u}; memcpy(p, &h, 8); if (n) memcpy(p + 8, a, n); memset(p + 8 + n, 0, h.size - 8 - n); return p + h.size;
}
static void hue(double h, float c[4]) {
    double k[3] = {fmod(5 + h * 6, 6), fmod(3 + h * 6, 6), fmod(1 + h * 6, 6)};
    for (int i = 0; i < 3; i++) { double t = k[i] < 4 - k[i] ? k[i] : 4 - k[i]; c[i] = (float)(1 - (t < 1 ? (t > 0 ? t : 0) : 1)); }
    c[3] = 1;
}
static uint32_t rgba8(const float c[4]) { uint32_t v = 0; for (int i = 0; i < 4; i++) v |= (uint32_t)(c[i] * 255 + 0.5f) << (8 * i); return v; }

// ---------- The selftest's own shader library ----------
#define K_VS 0x5e1f7e5700000001ull
#define K_FS 0x5e1f7e5700000002ull
#define K_CS 0x5e1f7e5700000003ull
static const float TINT[4] = {1, 0, 1, 1};   // the fragment shader's c1: a white texel comes out magenta
static uint8_t *lib_make(size_t *size) {
    enum { NE = 3, NB = 6, NM = 3 };
    size_t eo = DMSH_VK_HEADER_SIZE, bo = eo + NE * DMSH_VK_ENTRY_SIZE, mo = bo + NB * DMSH_VK_BIND_SIZE, blo = (mo + NM * DMSH_VK_MOD_SIZE + 15) & ~(size_t)15;
    size_t m0 = 0, m1 = (m0 + sizeof SPV_TRI_VERT + 15) & ~(size_t)15, m2 = (m1 + sizeof SPV_TRI_FRAG + 15) & ~(size_t)15, c1 = (m2 + sizeof SPV_FILL_COMP + 15) & ~(size_t)15, blob = c1 + 16;
    uint8_t *L = calloc(1, blo + blob); *size = blo + blob;
    dmsh_vk_header *h = (dmsh_vk_header *)L;
    h->magic = DMSH_VK_LIB_MAGIC; h->version = DMSH_VK_LIB_VERSION;
    memcpy(h->chain, DMSH_CHAIN, strlen(DMSH_CHAIN)); memcpy(h->vk_chain, DMSH_VK_CHAIN, strlen(DMSH_VK_CHAIN)); memcpy(h->game, "selftest", 8); memcpy(h->inputs, "selftest", 8);
    h->entry_count = NE; h->entry_off = (uint32_t)eo; h->bind_count = NB; h->bind_off = (uint32_t)bo; h->mod_count = NM; h->mod_off = (uint32_t)mo; h->blob_off = blo; h->blob_size = blob;
    dmsh_vk_entry *e = (dmsh_vk_entry *)(L + eo);
    e[0] = (dmsh_vk_entry){.key = K_VS, .stage = DMSH_VK_STAGE_VS, .mod_count = 1, .mod = 0, .bind = 0, .bind_count = 1, .flags = DMSH_VK_F_GL_DEPTH, .vin_mask = 3};
    e[1] = (dmsh_vk_entry){.key = K_FS, .stage = DMSH_VK_STAGE_FS, .mod_count = 1, .mod = 1, .bind = 1, .bind_count = 3, .fout_mask = 1, .flags = DMSH_VK_F_ALPHA_TEST, .c1_off = (uint32_t)c1, .c1_len = 16};
    e[2] = (dmsh_vk_entry){.key = K_CS, .stage = DMSH_VK_STAGE_CS, .mod_count = 1, .mod = 2, .bind = 4, .bind_count = 2, .local = {64, 1, 1}};
    dmsh_vk_bind *b = (dmsh_vk_bind *)(L + bo);
    b[0] = (dmsh_vk_bind){.kind = DMSH_VK_KIND_CBUF, .desc = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .binding = 0, .count = 1, .reg = 0, .soff = 0xFFFF};
    b[1] = (dmsh_vk_bind){.kind = DMSH_VK_KIND_TEX, .desc = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .binding = DMSH_VK_STAGE_FS * DMSH_VK_STAGE_BINDINGS, .count = 1, .reg = 0,
                          .skind = DMSH_VK_KIND_SAMP_TEX, .sreg = 0, .dim = 1 /* 2D */, .soff = 0};
    b[2] = (dmsh_vk_bind){.kind = DMSH_VK_KIND_C1, .desc = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .binding = DMSH_VK_STAGE_FS * DMSH_VK_STAGE_BINDINGS + 1, .count = 1, .reg = -1, .soff = 0xFFFF};
    b[3] = (dmsh_vk_bind){.kind = DMSH_VK_KIND_SUPPORT, .desc = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .binding = DMSH_VK_STAGE_FS * DMSH_VK_STAGE_BINDINGS + 2, .count = 1, .reg = -1, .soff = 0xFFFF};
    b[5] = (dmsh_vk_bind){.kind = DMSH_VK_KIND_SBUF, .desc = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .binding = 0, .count = 1, .reg = 0, .flags = DMSH_VK_BF_RW, .soff = 0xFFFF};
    b[4] = (dmsh_vk_bind){.kind = DMSH_VK_KIND_DRVCB, .desc = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .binding = 1, .count = 1, .reg = 0, .soff = 0xFFFF};
    dmsh_vk_module *m = (dmsh_vk_module *)(L + mo);
    m[0] = (dmsh_vk_module){(uint32_t)m0, sizeof SPV_TRI_VERT, sizeof SPV_TRI_VERT, DMSH_VK_MOD_BASE, DMSH_VK_CODEC_RAW, 0};
    m[1] = (dmsh_vk_module){(uint32_t)m1, sizeof SPV_TRI_FRAG, sizeof SPV_TRI_FRAG, DMSH_VK_MOD_BASE, DMSH_VK_CODEC_RAW, 0};
    m[2] = (dmsh_vk_module){(uint32_t)m2, sizeof SPV_FILL_COMP, sizeof SPV_FILL_COMP, DMSH_VK_MOD_BASE, DMSH_VK_CODEC_RAW, 0};
    memcpy(L + blo + m0, SPV_TRI_VERT, sizeof SPV_TRI_VERT); memcpy(L + blo + m1, SPV_TRI_FRAG, sizeof SPV_TRI_FRAG); memcpy(L + blo + m2, SPV_FILL_COMP, sizeof SPV_FILL_COMP);
    memcpy(L + blo + c1, TINT, sizeof TINT);
    return L;
}

int nxhost_vk_selftest(ANativeWindow *w, int frames) {
    if (w) nxhost_set_window(w);
    if (!vka_init()) { VRED("selftest: no Vulkan device"); return 1; }
    int bad = 0;
    size_t sz = 64u << 20;
    uint8_t *base = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0), *anon = base;
    if (base == MAP_FAILED) { VRED("selftest: no address space"); return 1; }
    mtl_pool_add(base, sz); mtl_pool_need(base, sz, "the selftest");   // places the pool's pages (anonymous memory until now), as a game's pool once the GPU is given its address
    int placed = vka_addr((uintptr_t)base, sz, NULL, NULL, NULL), mapped = 0;
    if (!placed) {   // no placed maps (an emulator): the replay's draws are still checked, on a mapped allocation where the driver put it. The placed check stays failed
        mtl_pool_del(base);
        uint8_t *m = vka_mem_mapped(sz);
        if (m) { base = m; mapped = 1; }
    }
    VLOG("selftest: %zu MiB of guest memory at %p, %s", sz >> 20, (void *)base, placed ? "placed Vulkan memory" : mapped ? "NOT placed (no placed maps): a mapped Vulkan allocation instead, for the other checks" : "anonymous (no placed maps: the GPU cannot see it)");
    if (!placed) bad++;
    if (!placed) mtl_pool_add(base, sz);
    VkDeviceAddress bda = 0; if (vka_pool_bda((uintptr_t)base + 4096, &bda)) VLOG("selftest: pool byte 4096 at device address %#llx", (unsigned long long)bda);

    // The window texture, at the pool's start
    tex_t t; memset(&t, 0, sizeof t);
    t.magic = MAGIC('T', 'E', 'X', 0); t.d.target = NVN_TEXTURE_TARGET_2D; t.d.format = NVN_FORMAT_RGBA8; t.d.flags = NVN_TEXTURE_FLAGS_DISPLAY | NVN_TEXTURE_FLAGS_COMPRESSIBLE;
    t.d.width = 1280; t.d.height = 720; t.d.depth = 1; t.d.levels = 1;
    nvn_tex_layout(&t.d, &t.L); t.mem = base; t.serial = nvn_new_serial(); mtl_tex_init(&t);
    uint8_t *rb = base + (32u << 20); volatile uint32_t *ev = (volatile uint32_t *)(base + (48u << 20));

    // The draw: a 64x64 linear RGBA8 checkerboard (8-texel cells, opaque white where the cell's x + y is even, transparent black elsewhere: the alpha test, GREATER than 0.5, drops those) registered as texture ID 300 with sampler ID 5
    // (nearest, repeat), a triangle of three vertices (position xy, uv: 16 bytes each), the vertex stage's uniform buffer (rotation cos / sin, scale) in four slots
    uint8_t *texmem = base + (36u << 20), *vbuf = base + (40u << 20), *ubo = base + (41u << 20); uint32_t *sbuf = (uint32_t *)(base + (42u << 20));
    for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) { uint32_t v = ((x >> 3) + (y >> 3)) & 1 ? 0x00000000u : 0xffffffffu; memcpy(texmem + 4 * (64 * y + x), &v, 4); }
    tex_t ct; memset(&ct, 0, sizeof ct);
    ct.magic = MAGIC('T', 'E', 'X', 0); ct.d.target = NVN_TEXTURE_TARGET_2D; ct.d.format = NVN_FORMAT_RGBA8; ct.d.flags = NVN_TEXTURE_FLAGS_LINEAR; ct.d.stride = 256;
    ct.d.width = 64; ct.d.height = 64; ct.d.depth = 1; ct.d.levels = 1;
    nvn_tex_layout(&ct.d, &ct.L); ct.mem = texmem; ct.serial = nvn_new_serial(); mtl_tex_init(&ct);
    mtl_tex_cpu_written(&ct); mtl_reg_tex(300, &ct, NULL);
    samp_par sp; memset(&sp, 0, sizeof sp); sp.minf = NVN_MIN_FILTER_NEAREST; sp.magf = NVN_MAG_FILTER_NEAREST; sp.ws = sp.wt = sp.wr = NVN_WRAP_MODE_REPEAT; sp.lod_max = 1000; sp.aniso = 1;
    mtl_reg_samp(5, &sp);
    // uv = position / 2 + 0.8125: the triangle's centre (the origin, the window's centre) samples texel (52, 52), the middle of an even cell
    static const float tri[3][2] = {{-0.7f, -0.6f}, {0.7f, -0.6f}, {0.0f, 0.8f}};
    for (int i = 0; i < 3; i++) { float v[4] = {tri[i][0], tri[i][1], tri[i][0] * 0.5f + 0.8125f, tri[i][1] * 0.5f + 0.8125f}; memcpy(vbuf + 16 * i, v, 16); }

    // The library (kept for the process: the shaders made from it point into it) and its program objects
    static uint8_t *limg; static size_t lsz; static vka_shlib L; static int lib_ok = -1; char err[200] = "";
    if (lib_ok < 0) { limg = lib_make(&lsz); lib_ok = vka_shlib_check(&L, limg, lsz, DMSH_CHAIN, DMSH_VK_CHAIN, NULL, NULL, err, sizeof err); }
    if (!lib_ok) { VRED("selftest: its own shader library is refused: %s", err); bad++; }
    vka_progsh gps, cps; memset(&gps, 0, sizeof gps); memset(&cps, 0, sizeof cps);
    if (lib_ok) { gps.sh[0] = vka_shader_make(&L, K_VS); gps.sh[1] = vka_shader_make(&L, K_FS); cps.sh[5] = vka_shader_make(&L, K_CS); }
    int shaders = gps.sh[0] && gps.sh[1] && cps.sh[5];
    if (!shaders) bad++;
    prog_t gp, cp; memset(&gp, 0, sizeof gp); memset(&cp, 0, sizeof cp);
    gp.magic = cp.magic = MAGIC('P', 'R', 'O', 'G'); gp.nstage = 2; cp.nstage = 1; gp.serial = nvn_new_serial(); cp.serial = nvn_new_serial();
    vka_prog_put(gp.serial, &gps); vka_prog_put(cp.serial, &cps);
    VLOG("selftest: shaders %s; push descriptors %s, vertex input dynamic state %s, null descriptors %s", shaders ? "made" : "MISSING", V.push_desc ? "yes" : "no", V.vin_dyn ? "yes" : "no", V.null_desc ? "yes" : "no");

    vka_draw_force(1);
    uint8_t *cmd = malloc(8192);
    uint64_t q = nvn_new_serial(), last = 0, fences[4] = {0};
    if (frames <= 0) frames = 1;
    for (int f = 0; f < frames; f++) {
        float c[4]; hue(f / 360.0, c);   // round the hues every 6 s at 60 Hz
        float wsq[4] = {1, 1, 1, 1};
        if (fences[f & 3]) mtl_wait(fences[f & 3], 2000000000ull);   // the uniform slot this frame writes is free again
        float a = (float)(f * 2 * M_PI / 360.0), u[8] = {cosf(a), sinf(a), 0, 0, 0.5f * 720 / 1280, 0.5f, 0, 0};   // a turn every 6 s, the aspect kept
        memcpy(ubo + 256 * (f & 3), u, sizeof u);
        p_set_rt rt; memset(&rt, 0, sizeof rt); rt.n = 1; rt.color[0] = &t;
        p_scissor all = {0, 0, 1 << 16, 1 << 16}, sq = {64, 64, 128, 128};
        p_clear_color cc = {0, 0xf, {c[0], c[1], c[2], c[3]}}, cw = {0, 0xf, {wsq[0], wsq[1], wsq[2], wsq[3]}};
        p_copy_b2t r1; memset(&r1, 0, sizeof r1); r1.src = (uintptr_t)rb; r1.tex = &t; r1.r = (NVNcopyRegion){1200, 650, 0, 1, 1, 1};
        p_copy_b2t r2 = r1; r2.src = (uintptr_t)rb + 16; r2.r.xoffset = 100; r2.r.yoffset = 100;
        p_copy_b2t r3 = r1; r3.src = (uintptr_t)rb + 32; r3.r.xoffset = 640; r3.r.yoffset = 360;
        p_copy_b2t r4 = r1; r4.src = (uintptr_t)rb + 48; r4.r.xoffset = 685; r4.r.yoffset = 360;   // one cell to the right of the centre: a dropped (transparent) cell at frame 0
        uint8_t se[16]; uint64_t ea = (uintptr_t)ev; uint32_t m = NVN_EVENT_SIGNAL_MODE_WRITE, val = (uint32_t)f + 1; memcpy(se, &ea, 8); memcpy(se + 8, &m, 4); memcpy(se + 12, &val, 4);
        // The draw's state, as hle_nvn.c records it
        p_bind_program bp = {&gp, 0x3}, bc = {&cp, 0x20};
        p_bind_state cs_; memset(&cs_, 0, sizeof cs_); cs_.kind = 1; color_st col = {0, NVN_LOGIC_OP_COPY, NVN_ALPHA_FUNC_GREATER}; memcpy(cs_.bytes, &col, sizeof col);
        p_alpha_ref ar = {0.5f};
        p_bind_vattr va; memset(&va, 0, sizeof va); va.first = 0; va.n = 2;
        va_st a0 = {0x16 /* RG32F */, 0, 0}, a1 = {0x16, 8, 0}; memcpy(&va.st[0], &a0, 4); memcpy(&va.st[1], &a1, 4);
        p_bind_vstream vs; memset(&vs, 0, sizeof vs); vs.first = 0; vs.n = 1; vs.st[0] = (vs_st){16, 0};
        p_bind_vbuf vb; memset(&vb, 0, sizeof vb); vb.first = 0; vb.n = 1; vb.r[0] = (NVNbufferRange){(uintptr_t)vbuf, 48};
        p_bind_buf ub = {0, 0, (uintptr_t)ubo + 256 * (f & 3), 256};
        p_bind_handles th; memset(&th, 0, sizeof th); th.stage = 1; th.first = 0; th.n = 1; th.h[0] = 300 | 5ull << 20;
        p_viewports vp; memset(&vp, 0, sizeof vp); vp.first = 0; vp.n = 1; vp.v[0][2] = 1280; vp.v[0][3] = 720; vp.v[0][5] = 1;
        p_draw dr; memset(&dr, 0, sizeof dr); dr.prim = NVN_DRAW_PRIMITIVE_TRIANGLES; dr.count = 3; dr.instances = 1;
        p_bind_buf sb = {5, 0, (uintptr_t)sbuf, 4 * 256};
        p_dispatch dp; memset(&dp, 0, sizeof dp); dp.x = 4; dp.y = 1; dp.z = 1;
        uint8_t *p = cmd;
        p = rec(p, OP_SET_RT, &rt, sizeof rt); p = rec(p, OP_SCISSOR, &all, sizeof all); p = rec(p, OP_CLEAR_COLOR, &cc, sizeof cc);
        p = rec(p, OP_SCISSOR, &sq, sizeof sq); p = rec(p, OP_CLEAR_COLOR, &cw, sizeof cw); p = rec(p, OP_SCISSOR, &all, sizeof all);
        p = rec(p, OP_BIND_PROGRAM, &bp, sizeof bp); p = rec(p, OP_BIND_VATTR, &va, sizeof va); p = rec(p, OP_BIND_VSTREAM, &vs, sizeof vs); p = rec(p, OP_BIND_VBUF, &vb, sizeof vb);
        p = rec(p, OP_BIND_STATE, &cs_, 4 + sizeof col); p = rec(p, OP_ALPHA_REF, &ar, sizeof ar); p = rec(p, OP_BIND_UBO, &ub, sizeof ub); p = rec(p, OP_BIND_TEX, &th, sizeof th); p = rec(p, OP_VIEWPORTS, &vp, sizeof vp); p = rec(p, OP_DRAW, &dr, sizeof dr);
        if (f == 0) {
            p = rec(p, OP_BIND_PROGRAM, &bc, sizeof bc); p = rec(p, OP_BIND_SSBO, &sb, sizeof sb); p = rec(p, OP_DISPATCH, &dp, sizeof dp);
            p = rec(p, OP_COPY_T2B, &r1, sizeof r1); p = rec(p, OP_COPY_T2B, &r2, sizeof r2); p = rec(p, OP_COPY_T2B, &r3, sizeof r3); p = rec(p, OP_COPY_T2B, &r4, sizeof r4);
        }
        p = rec(p, OP_SIGNAL_EVENT, se, sizeof se); p = rec(p, OP_END, NULL, 0);
        mtl_queue_activate(q);
        mtl_sub_begin(); mtl_exec(cmd); uint64_t fence = mtl_sub_commit();
        fences[f & 3] = fence;
        if (f == 0) {
            int ok = mtl_wait(fence, 2000000000ull);
            uint32_t a_, b_, t_, h_; memcpy(&a_, rb, 4); memcpy(&b_, rb + 16, 4); memcpy(&t_, rb + 32, 4); memcpy(&h_, rb + 48, 4);
            uint32_t want = rgba8(c), white = 0xffffffffu, magenta = rgba8(TINT);
            uint64_t nd, ns, nc; vka_draw_stats(&nd, &ns, &nc, NULL);
            VLOG("selftest: fence %llu %s; texel (1200,650) %08x (want %08x), texel (100,100) %08x (want %08x), event %u (want 1)",
                 (unsigned long long)fence, ok ? "done" : "TIMED OUT", a_, want, b_, white, *ev);
            VLOG("selftest: draw: %llu drawn, %llu skipped; triangle centre texel (640,360) %08x (want %08x: a white texel times the tint), texel (685,360) %08x (want %08x: dropped by the alpha test)",
                 (unsigned long long)nd, (unsigned long long)ns, t_, magenta, h_, want);
            VLOG("selftest: dispatch: %llu run; storage words 5 and 255: %u, %u (want 4005, 4255)", (unsigned long long)nc, sbuf[5], sbuf[255]);
            bad += !ok || a_ != want || b_ != white || *ev != 1;
            bad += nd != 1 || t_ != magenta || h_ != want;
            bad += nc != 1 || sbuf[5] != 4005 || sbuf[255] != 4255;
        }
        uint64_t tk = mtl_present(&t, 1 / 60.0, NULL);
        if (last) mtl_present_wait(last);   // at most two presents in flight, as a double-buffered game
        last = tk;
    }
    mtl_present_flush(); mtl_finish();
    // DM_VK_SHLIB=<a game's dmsh_vk.lib>: every shader of it through this device's compiler (any game's library: the chain must match, the game is not checked)
    const char *gl = getenv("DM_VK_SHLIB");
    if (gl && *gl) {
        static vka_shlib G; char e2[256];
        if (!vka_shlib_open(&G, gl, DMSH_CHAIN, DMSH_VK_CHAIN, NULL, NULL, e2, sizeof e2)) { VRED("selftest: shader library %s refused: %s", gl, e2); bad++; }
        else { char game[17]; vka_shlib_str(G.h->game, game); VLOG("selftest: shader library %s (game %s)", gl, game); bad += vka_shlib_check_all(&G) != 0; }
    }
    char pl[200]; mtl_pso_line(pl, sizeof pl);
    VLOG("selftest: %d frames presented to %s; %s; %s", frames, mtl_has_window() ? "the window" : "no window (dropped)", pl, bad ? "FAILED" : "passed");
    mtl_prog_release(&gp); mtl_prog_release(&cp);
    mtl_tex_release(&ct); mtl_tex_release(&t); mtl_pool_del(base); free(cmd);
    if (!mapped) munmap(anon, sz);
    return bad;
}
#endif
