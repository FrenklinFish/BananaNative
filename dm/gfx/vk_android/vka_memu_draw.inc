// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors (the compute mesh emulation: pavelgajdo/Hokkaido #569)
// SPDX-FileCopyrightText: Copyright 2026 Banana Native (this port)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Included by vka_draw.c (its bindings and descriptor writes): the compute work of NVN2 task and mesh draws drawn through compute (docs/reference/rendering.md "Mesh draws
// through compute"; vka_memu.c, vka_memu_spv.c). vka_exec.c's look-ahead (memu_scan) finds, when a render pass is about to open, every mesh draw from there to the next
// record that could change what they read or the pass's targets (a dispatch, copy, fill, inline update, barrier, render target change, wait, render condition), and walks
// those records three times with the replay state as it will be at each draw (vka_state_op on a copy):
//   1 (MEMU_ADD) each draw's shaders, compute pipelines and record (its count and first workgroup: the direct draw's, or its indirect record copied on the GPU);
//     then the setup pass: payload and count space for task draws, the mesh output and the dispatch / draw arguments of draws without a task;
//   2 (MEMU_TASK) each task draw's task workgroups (vkCmdDispatchIndirect); then the distribution pass: each task draw's offsets and mesh output;
//   3 (MEMU_MESH) each draw's mesh workgroups;
// with one barrier between the steps, so the pass's whole mesh work costs four barriers and no render pass break; Hokkaido ended the pass for each draw. The draws
// then find their record (vka_memu_cur) and draw the arena with the mesh shader's vertex shader: vkCmdDrawIndexedIndirect (indexed triangles) or vkCmdDrawIndirect.
// A mesh draw met inside an open pass that the look-ahead did not reach ends the pass and has the look-ahead run from it (counted: "render passes split"). GPL-3.0-or-later.
// A draw's entry is the next one of its record in walk order, not the first one of its record (vka_memu_order.h: one look-ahead can meet a record more than once)
#include "vka_memu_order.h"
enum { MEMU_MAX = VKA_MEMU_ORDER_MAX };
typedef struct { int ok, rec, indexed, points; vka_shader *vs, *ts, *ms, *tcs, *mcs; vka_layout tl, ml; VkPipeline tp, mp; } memu_ent;
static struct { int live; vka_memu_order o; memu_ent e[MEMU_MAX]; vka_span rec; VkBuffer arena, ctl; uint32_t cap; } MB;
static memu_ent *g_memu_cur;
// The record being replayed: its entry (the next of its record from the batch's cursor, taken), NULL when the batch has none left for it
void vka_memu_cur(const uint8_t *p) { int i = vka_memu_order_take(&MB.o, p); g_memu_cur = i < 0 ? NULL : &MB.e[i]; }
int vka_memu_on(void) { return V.memu; }
int vka_memu_has(const uint8_t *p) { return vka_memu_order_find(&MB.o, p) >= 0; }   // the batch still has an entry for a meeting of record p
static memu_ent *memu_find(void) { return g_memu_cur; }   // the entry of the draw being replayed (vka_memu_cur), NULL none
static void memu_write(uint32_t binding, VkBuffer b, VkDeviceSize off, VkDeviceSize range) {
    if (g_w.n == 512) return;
    uint32_t k = g_w.n++;
    g_w.bi[k] = (VkDescriptorBufferInfo){b, off, range};
    g_w.w[k] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstBinding = binding, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &g_w.bi[k]};
}
static void memu_bind_arena(void) { memu_write(VKA_MEMU_ARENA_BINDING, MB.arena, 0, VK_WHOLE_SIZE); }
// A compute form's dispatch: its stage's bindings walked with the replay state of its draw, the arena and the records, its record's index, its indirect arguments
static void memu_dispatch(const vka_dstate *d, int st, vka_shader *cs, const vka_layout *lay, VkPipeline p, int idx, uint32_t argw) {
    vka_res *r = g_wk.r[5]; int pool = 0; char why[200];
    int n = vka_walk(&d->b, st, cs->e, cs->b, cs->lib->h->ssbo_align, V.sb_align, rd_cb, NULL, r, 512, &pool, why, sizeof why);
    if (n < 0) { static _Atomic int k; if (atomic_fetch_add(&k, 1) < 8) VRED("%s shader through compute %016llx: %s; skipped", st == 6 ? "task" : "mesh", (unsigned long long)cs->key, why); return; }
    for (int i = 0; i < n; i++) if ((r[i].what == VKA_R_TEX || r[i].what == VKA_R_IMG) && r[i].tid && (vka_tex_id_pending_rec(r[i].tid, r[i].dim) || (r[i].what == VKA_R_IMG && vka_tex_id_storage_pending(r[i].tid))))
        vka_tex_id_view(r[i].tid, g_cb, r[i].dim, r[i].what == VKA_R_IMG, NULL);   // (images first used: made now, outside the pass)
    g_w.n = 0;
    if (!stage_writes(d, st, cs, r, n, pool, NULL)) return;
    memu_write(VKA_MEMU_ARENA_BINDING, MB.arena, 0, VK_WHOLE_SIZE);
    memu_write(VKA_MEMU_REC_BINDING, MB.rec.buf, MB.rec.off, (VkDeviceSize)MEMU_MAX * VKA_MEMU_REC_WORDS * 4);
    if (!sets_push(lay, VK_PIPELINE_BIND_POINT_COMPUTE, pool)) return;
    uint32_t pc[4] = {(uint32_t)idx, 0, 0, 0};
    vk.vkCmdPushConstants(g_cb, lay->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, VKA_MEMU_PUSH_BYTES, pc);
    vk.vkCmdBindPipeline(g_cb, VK_PIPELINE_BIND_POINT_COMPUTE, p);
    vk.vkCmdDispatchIndirect(g_cb, MB.rec.buf, MB.rec.off + ((VkDeviceSize)idx * VKA_MEMU_REC_WORDS + argw) * 4);
}
// A helper pass over the batch's records (setup: one invocation each; distribution: one workgroup each)
static void memu_helper(int dist) {
    VkPipeline ps, pd; VkPipelineLayout pl; if (!vka_memu_helpers(&ps, &pd, &pl)) return;
    VkDescriptorBufferInfo bi[3] = {{MB.rec.buf, MB.rec.off, (VkDeviceSize)MEMU_MAX * VKA_MEMU_REC_WORDS * 4}, {MB.ctl, 0, 64}, {MB.arena, 0, VK_WHOLE_SIZE}};
    VkWriteDescriptorSet w[3];
    for (uint32_t i = 0; i < 3; i++) w[i] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstBinding = i, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &bi[i]};
    vk.vkCmdBindPipeline(g_cb, VK_PIPELINE_BIND_POINT_COMPUTE, dist ? pd : ps);
    vk.vkCmdPushDescriptorSetKHR(g_cb, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 3, w);
    g_pd[1].n = 0;   // (another layout's push: the replay's last compute push is forgotten)
    uint32_t pc[4] = {0, (uint32_t)MB.o.n, MB.cap, 0};
    vk.vkCmdPushConstants(g_cb, pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, pc);
    vk.vkCmdDispatch(g_cb, dist ? (uint32_t)MB.o.n : ((uint32_t)MB.o.n + 63) / 64, 1, 1);
}
enum { MEMU_BEGIN, MEMU_ADD, MEMU_SETUP, MEMU_TASK, MEMU_DIST, MEMU_MESH, MEMU_END };
void vka_memu_batch(int phase, const vka_dstate *d, const p_draw *dw, const uint8_t *p, int idx) {
    if (phase == MEMU_BEGIN) { vka_memu_order_reset(&MB.o); MB.live = 0; g_memu_cur = NULL; return; }
    if (phase == MEMU_ADD) {
        if (MB.o.n == MEMU_MAX || !g_cb) return;
        if (!MB.live) {
            MB.live = -1;
            int sa = V.sb_align > 64 ? (int)V.sb_align : 64;
            if (!vka_scratch((VkDeviceSize)MEMU_MAX * VKA_MEMU_REC_WORDS * 4, (VkDeviceSize)sa, &MB.rec) || !vka_memu_arena(g_cb, &MB.arena, &MB.ctl, &MB.cap)) return;
            vka_disp_settle(g_cb);   // (the dispatches before write what the batch reads: indirect records, buffers)
            MB.live = 1;
        }
        if (MB.live < 0) return;
        int ei = vka_memu_order_add(&MB.o, p); if (ei < 0) return;
        memu_ent *E = &MB.e[ei]; memset(E, 0, sizeof *E); E->rec = ei;
        uint32_t *r = (uint32_t *)(MB.rec.ptr + (size_t)E->rec * VKA_MEMU_REC_WORDS * 4); memset(r, 0, VKA_MEMU_REC_WORDS * 4);
        vka_shader *ts = NULL, *ms = NULL; unsigned bad = 0;
        const prog_t *mp = d->bst[7];   // (as draw_body: the fragment stage's program when it has a mesh shader)
        { vka_shader *m = NULL; unsigned b2 = 0; if (d->bst[1] && d->bst[1] != mp && resolve(d->bst[1], 7, &m, &b2) && (m || (b2 & 1u << 7))) mp = d->bst[1]; }
        if (!resolve(mp, 6, &ts, &bad) || !resolve(mp, 7, &ms, &bad) || bad || !ms) return;
        vka_memu_sh *mi = vka_memu_of(ms), *ti = ts ? vka_memu_of(ts) : NULL;
        if (!mi || (ts && !ti)) return;
        E->ts = ts; E->ms = ms; E->vs = vka_memu_vs_of(ms); E->mcs = vka_memu_cs_of(ms, !vka_depth_zero()); E->tcs = ts ? vka_memu_cs_of(ts, 0) : NULL;
        if (!E->vs || !E->mcs || (ts && !E->tcs)) return;
        vka_shader *c5[VKA_NSTAGE] = {0};
        c5[5] = E->mcs; if (!vka_layout_of(c5, 1, &E->ml) || !(E->mp = vka_cs_pipeline(E->mcs, &E->ml))) return;
        if (ts) { c5[5] = E->tcs; if (!vka_layout_of(c5, 1, &E->tl) || !(E->tp = vka_cs_pipeline(E->tcs, &E->tl))) return; }
        vka_memu_rec_mesh(&mi->L, r);
        r[VKA_MEMU_R_FLAGS] |= ts ? VKA_MEMU_F_TASK : 0; r[VKA_MEMU_R_WT] = ts ? ti->L.payload_words : 0;
        r[VKA_MEMU_R_MAXG] = V.cs_groups < 65535 ? V.cs_groups : 65535;
        if (dw->ind) {   // {count, first}, as the GPU holds it now
            VkBuffer b; VkDeviceSize o;
            if (!vka_addr(dw->ind, 8, &b, &o, NULL) || (o & 3)) { static _Atomic int k; if (atomic_fetch_add(&k, 1) < 4) VRED("indirect mesh draw record at %#llx not in placed guest RAM: skipped", (unsigned long long)dw->ind); return; }
            VkBufferCopy c = {o, MB.rec.off + ((VkDeviceSize)E->rec * VKA_MEMU_REC_WORDS + VKA_MEMU_R_COUNT) * 4, 8}; vk.vkCmdCopyBuffer(g_cb, b, MB.rec.buf, 1, &c);
        } else { r[VKA_MEMU_R_COUNT] = dw->count > 0 ? (uint32_t)dw->count : 0; r[VKA_MEMU_R_FIRST] = dw->first > 0 ? (uint32_t)dw->first : 0; }
        E->indexed = mi->L.indexed; E->points = mi->L.points; E->ok = 1;
        return;
    }
    if (MB.live <= 0 || !MB.o.n) return;
    switch (phase) {
    case MEMU_SETUP: vka_barrier(g_cb); memu_helper(0); vka_barrier(g_cb); break;
    case MEMU_TASK: if (idx < MB.o.n && MB.e[idx].ok && MB.e[idx].tcs) memu_dispatch(d, 6, MB.e[idx].tcs, &MB.e[idx].tl, MB.e[idx].tp, idx, VKA_MEMU_R_TASK_X); break;
    case MEMU_DIST: vka_barrier(g_cb); memu_helper(1); vka_barrier(g_cb); break;
    case MEMU_MESH: if (idx < MB.o.n && MB.e[idx].ok) memu_dispatch(d, 7, MB.e[idx].mcs, &MB.e[idx].ml, MB.e[idx].mp, idx, VKA_MEMU_R_MESH_X); break;
    case MEMU_END: vka_barrier(g_cb); vka_memu_n_batches++; vka_memu_n_draws += (uint64_t)MB.o.n; break;
    }
}
