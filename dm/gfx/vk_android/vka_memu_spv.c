// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors (the compute mesh emulation: pavelgajdo/Hokkaido #569, emit_spirv_mesh.cpp EmitMeshEmulationVertex)
// SPDX-FileCopyrightText: Copyright 2026 Banana Native (this port)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// NVN2 task and mesh programs drawn through compute (docs/reference/rendering.md "Mesh draws through compute"). Hokkaido (a yuzu / Eden derived Switch 2 emulator,
// GPL-3.0-or-later) emits a mesh program as a compute shader from its IR when the device has no VK_EXT_mesh_shader: the program's output goes to a storage buffer, one
// block per workgroup, and a generated vertex shader pulls it as a triangle list; a task program runs as compute and writes its payload, and each mesh workgroup finds its
// task by a binary search of the tasks' first workgroups. This file ports that idea to this host, which has no IR at run time: it rewrites the library's own TaskEXT /
// MeshEXT modules (the translator's SpirvMeshEntry.cs form) into GLCompute modules at pipeline time, so every existing library plays as it is. The differences:
//   - the outputs are not copied from a stage buffer at the end: every store into an output (the translator's copy-out, or its direct plane stores) becomes stores of
//     its words into the arena at the vertex's or primitive's place, so the converted module uses no more workgroup memory than the mesh module did (Ocarina of Time's
//     mesh programs output up to 160 vertices of 15 vec4s, 38 KB: more than a compute workgroup has);
//   - triangles without per-primitive values are drawn indexed (the indices the arena's vertex numbers), so a vertex is shaded once rather than once per corner;
//     per-primitive values (flat inputs of the pixel shader) and points are drawn expanded, each corner its own vertex with its primitive's values;
//   - points (OutputPoints, CullPrimitiveEXT) and per-primitive outputs are taken; unwritten primitives are the translator's degenerate (0, 0, 0) triangles or culled points;
//   - a mesh workgroup's own index is its index among its task's (WorkgroupId), and without a task the draw's first workgroup is applied (it was not with VK_EXT_mesh_shader).
// GPL-3.0-or-later: see vka_memu_abi.h.
#include "vka_memu_spv.h"
#include "vka_memu_abi.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum {
    OpNop = 0, OpUndef = 1, OpSource = 3, OpName = 5, OpMemberName = 6, OpString = 7, OpLine = 8, OpExtension = 10, OpExtInstImport = 11, OpExtInst = 12, OpMemoryModel = 14,
    OpEntryPoint = 15, OpExecutionMode = 16, OpCapability = 17, OpTypeVoid = 19, OpTypeBool = 20, OpTypeInt = 21, OpTypeFloat = 22, OpTypeVector = 23, OpTypeArray = 28,
    OpTypeRuntimeArray = 29, OpTypeStruct = 30, OpTypePointer = 32, OpTypeFunction = 33, OpConstantTrue = 41, OpConstantFalse = 42, OpConstant = 43,
    OpFunction = 54, OpFunctionParameter = 55, OpFunctionEnd = 56, OpFunctionCall = 57, OpVariable = 59, OpLoad = 61, OpStore = 62, OpCopyMemory = 63, OpCopyMemorySized = 64,
    OpAccessChain = 65, OpInBoundsAccessChain = 66, OpPtrAccessChain = 67, OpDecorate = 71, OpMemberDecorate = 72, OpCompositeConstruct = 80, OpCompositeExtract = 81,
    OpImageWrite = 99, OpBitcast = 124, OpIAdd = 128, OpISub = 130, OpIMul = 132, OpUDiv = 134, OpUMod = 137, OpLogicalAnd = 167, OpSelect = 169, OpIEqual = 170,
    OpINotEqual = 171, OpULessThan = 176, OpULessThanEqual = 178, OpShiftRightLogical = 194, OpBitwiseAnd = 199, OpControlBarrier = 224, OpMemoryBarrier = 225,
    OpAtomicStore = 228, OpLoopMerge = 246, OpSelectionMerge = 247, OpLabel = 248, OpBranch = 249, OpBranchConditional = 250, OpSwitch = 251, OpKill = 252, OpReturn = 253,
    OpReturnValue = 254, OpUnreachable = 255, OpNoLine = 317, OpModuleProcessed = 330, OpExecutionModeId = 331, OpDecorateId = 332, OpTerminateInvocation = 4416,
    OpEmitMeshTasksEXT = 5294, OpSetMeshOutputsEXT = 5295, OpDemoteToHelperInvocation = 5380, OpDecorateString = 5632, OpMemberDecorateString = 5633,
};
enum { SCUniformConstant = 0, SCInput = 1, SCOutput = 3, SCWorkgroup = 4, SCPrivate = 6, SCFunction = 7, SCPushConstant = 9, SCStorageBuffer = 12, SCTaskPayload = 5402 };
enum { DecBlock = 2, DecArrayStride = 6, DecBuiltIn = 11, DecFlat = 14, DecInvariant = 18, DecNonWritable = 24, DecLocation = 30, DecBinding = 33, DecDescriptorSet = 34,
       DecOffset = 35, DecPerPrimitive = 5271 };
enum { BIPosition = 0, BIPointSize = 1, BIClipDistance = 3, BICullDistance = 4, BINumWorkgroups = 24, BIWorkgroupId = 26, BILocalInvocationIndex = 29, BIVertexIndex = 42,
       BIPrimitivePointIndices = 5294, BIPrimitiveLineIndices = 5295, BIPrimitiveTriangleIndices = 5296, BICullPrimitive = 5299 };
enum { EMVertex = 0, EMFragment = 4, EMGLCompute = 5, EMTask = 5364, EMMesh = 5365 };
enum { XMOutputVertices = 26, XMOutputPoints = 27, XMLocalSize = 17, XMOutputLines = 5269, XMOutputPrimitives = 5270, XMOutputTriangles = 5298 };
enum { CapShader = 1, CapMeshShading = 5283 };

#define FAIL(...) do { snprintf(err, errn, __VA_ARGS__); goto fail; } while (0)

// ---------- Words out ----------
typedef struct { uint32_t *w; size_t n, cap; int oom; } sb_t;
static void sb_put(sb_t *b, uint32_t x) {
    if (b->oom) return;
    if (b->n == b->cap) { size_t c = b->cap ? 2 * b->cap : 1024; uint32_t *p = realloc(b->w, c * 4); if (!p) { b->oom = 1; return; } b->w = p; b->cap = c; }
    b->w[b->n++] = x;
}
static void sb_raw(sb_t *b, const uint32_t *x, size_t n) { for (size_t i = 0; i < n; i++) sb_put(b, x[i]); }
static void sb_ins(sb_t *b, uint32_t op, const uint32_t *a, uint32_t na) { sb_put(b, (na + 1) << 16 | op); sb_raw(b, a, na); }
#define INS(b, op, ...) do { const uint32_t a_[] = {__VA_ARGS__}; sb_ins((b), (op), a_, (uint32_t)(sizeof a_ / sizeof a_[0])); } while (0)
static void sb_str_ins(sb_t *b, uint32_t op, const uint32_t *pre, uint32_t npre, const char *s, const uint32_t *post, uint32_t npost) {   // an instruction with a string operand
    uint32_t sw = (uint32_t)(strlen(s) / 4 + 1);
    sb_put(b, (1 + npre + sw + npost) << 16 | op); sb_raw(b, pre, npre);
    uint32_t t[64] = {0}; memcpy(t, s, strlen(s) < 255 ? strlen(s) : 255); sb_raw(b, t, sw); sb_raw(b, post, npost);
}

// ---------- The module read ----------
typedef struct {
    const uint32_t *w; size_t n; uint32_t bound;
    size_t *def;              // per id: the word index of the instruction that declares it before the first function (0: none there)
    uint32_t *rtype;          // per id: its result type (every instruction with one, functions included)
    uint32_t *cval; uint8_t *isc;   // per id: an OpConstant's 32-bit value
    int32_t *bi, *loc; uint8_t *perprim, *block;   // per id: BuiltIn (-1 none), Location (-1 none), PerPrimitiveEXT, Block
    size_t first_fn;          // word index of the first OpFunction
    size_t ep;                // the OpEntryPoint
    uint32_t model, entry;
    uint32_t local[3];
} mod_t;
static int no_result(uint32_t op) {   // function-body instructions without [result type, result] in words 1-2
    return op == OpStore || op == OpBranch || op == OpBranchConditional || op == OpSwitch || op == OpReturn || op == OpReturnValue || op == OpKill || op == OpUnreachable
        || op == OpSelectionMerge || op == OpLoopMerge || op == OpLabel || op == OpCopyMemory || op == OpCopyMemorySized || op == OpImageWrite || op == OpAtomicStore
        || op == OpTerminateInvocation || op == OpFunctionEnd || op == OpControlBarrier || op == OpMemoryBarrier || op == OpLine || op == OpNoLine || op == OpNop
        || op == OpDemoteToHelperInvocation || op == 218 || op == 219 || op == OpEmitMeshTasksEXT || op == OpSetMeshOutputsEXT;
}
static int type_decl(uint32_t op) { return op >= OpTypeVoid && op <= 39; }   // OpType* (result in word 1)
static void mod_free(mod_t *m) { free(m->def); free(m->rtype); free(m->cval); free(m->isc); free(m->bi); free(m->loc); free(m->perprim); free(m->block); }
static int mod_read(mod_t *m, const uint32_t *w, size_t n, char *err, size_t errn) {
    memset(m, 0, sizeof *m); m->w = w; m->n = n;
    if (n < 5 || w[0] != 0x07230203) FAIL("not SPIR-V");
    uint32_t b = m->bound = w[3];
    m->def = calloc(b, sizeof *m->def); m->rtype = calloc(b, 4); m->cval = calloc(b, 4); m->isc = calloc(b, 1);
    m->bi = malloc(b * 4); m->loc = malloc(b * 4); m->perprim = calloc(b, 1); m->block = calloc(b, 1);
    if (!m->def || !m->rtype || !m->cval || !m->isc || !m->bi || !m->loc || !m->perprim || !m->block) FAIL("out of memory");
    for (uint32_t i = 0; i < b; i++) m->bi[i] = m->loc[i] = -1;
    m->first_fn = n;
    for (size_t i = 5; i < n;) {
        uint32_t op = w[i] & 0xffff, len = w[i] >> 16;
        if (!len || i + len > n) FAIL("bad instruction at word %zu", i);
        if (op == OpFunction && m->first_fn == n) m->first_fn = i;
        if (op == OpEntryPoint && !m->ep) { m->ep = i; m->model = w[i + 1]; m->entry = w[i + 2]; }
        else if (op == OpExecutionMode && len >= 6 && w[i + 2] == XMLocalSize) { m->local[0] = w[i + 3]; m->local[1] = w[i + 4]; m->local[2] = w[i + 5]; }
        else if (op == OpDecorate && len >= 3 && w[i + 1] < b) {
            uint32_t t = w[i + 1], d = w[i + 2];
            if (d == DecBuiltIn && len >= 4) m->bi[t] = (int32_t)w[i + 3];
            else if (d == DecLocation && len >= 4) m->loc[t] = (int32_t)w[i + 3];
            else if (d == DecPerPrimitive) m->perprim[t] = 1;
            else if (d == DecBlock) m->block[t] = 1;
        }
        if (i < m->first_fn) {
            if (type_decl(op) && len >= 2 && w[i + 1] < b) m->def[w[i + 1]] = i;
            else if ((op == OpConstant || op == OpConstantTrue || op == OpConstantFalse || op == OpVariable || op == OpUndef || (op >= 41 && op <= 52)) && len >= 3 && w[i + 2] < b) {
                m->def[w[i + 2]] = i; m->rtype[w[i + 2]] = w[i + 1];
                if (op == OpConstant && len >= 4) { m->cval[w[i + 2]] = w[i + 3]; m->isc[w[i + 2]] = 1; }
            }
        } else if (!no_result(op) && len >= 3 && w[i + 2] < b) m->rtype[w[i + 2]] = w[i + 1];
        i += len;
    }
    if (!m->ep) FAIL("no entry point");
    return 1;
fail:
    return 0;
}
static uint32_t T_op(const mod_t *m, uint32_t t) { return t < m->bound && m->def[t] ? m->w[m->def[t]] & 0xffff : 0; }
static const uint32_t *T_w(const mod_t *m, uint32_t t) { return m->w + m->def[t]; }   // (after T_op said it is declared)
static uint32_t ptr_pointee(const mod_t *m, uint32_t pt, uint32_t *sc) { if (T_op(m, pt) != OpTypePointer) return 0; if (sc) *sc = T_w(m, pt)[2]; return T_w(m, pt)[3]; }
// A scalar type: 'f' float 32, 'u' uint 32, 'i' int 32, 'b' bool, 0 other
static int scalar_kind(const mod_t *m, uint32_t t) {
    uint32_t op = T_op(m, t); const uint32_t *d = op ? T_w(m, t) : NULL;
    if (op == OpTypeFloat && d[2] == 32) return 'f';
    if (op == OpTypeInt && d[2] == 32) return d[3] ? 'i' : 'u';
    if (op == OpTypeBool) return 'b';
    return 0;
}
static uint32_t array_len(const mod_t *m, uint32_t t, uint32_t *elem) {   // an OpTypeArray's length (0: not one, or not a constant)
    if (T_op(m, t) != OpTypeArray) return 0;
    const uint32_t *d = T_w(m, t); uint32_t c = d[3];
    if (elem) *elem = d[2];
    return c < m->bound && m->isc[c] ? m->cval[c] : 0;
}
// The member decorations of a struct: BuiltIn per member (-1 none)
static void member_builtins(const mod_t *m, uint32_t st, int32_t out[8]) {
    for (int k = 0; k < 8; k++) out[k] = -1;
    for (size_t i = 5; i < m->first_fn;) {
        uint32_t op = m->w[i] & 0xffff, len = m->w[i] >> 16;
        if (op == OpMemberDecorate && len >= 5 && m->w[i + 1] == st && m->w[i + 3] == DecBuiltIn && m->w[i + 2] < 8) out[m->w[i + 2]] = (int32_t)m->w[i + 4];
        i += len;
    }
}

// ---------- The layout ----------
// Each output variable of a mesh module: what it holds and where its words go
enum { K_POS = 1, K_VLOC, K_PLOC, K_IDX, K_CULL };
typedef struct { uint32_t id; int kind, k; uint32_t elem; int32_t mb[8]; } outvar;   // k: the location's arena order; mb: the block's member BuiltIns (K_POS)
static int cmp_u8(const void *a, const void *b) { return (int)*(const uint8_t *)a - (int)*(const uint8_t *)b; }
static int layout_read(const mod_t *m, vka_memu_layout *L, outvar *ov, int *nov, uint32_t *payload, uint32_t *lidvar, uint32_t *wgvar, char *err, size_t errn) {
    memset(L, 0, sizeof *L); *nov = 0; if (payload) *payload = 0; if (lidvar) *lidvar = 0; if (wgvar) *wgvar = 0;
    if (m->model != EMTask && m->model != EMMesh) FAIL("not a task or mesh module (execution model %u)", m->model);
    L->task = m->model == EMTask;
    for (int k = 0; k < 3; k++) L->local[k] = m->local[k] ? m->local[k] : 1;
    L->threads = L->local[0] * L->local[1] * L->local[2];
    for (size_t i = 5; i < m->first_fn;) {
        uint32_t op = m->w[i] & 0xffff, len = m->w[i] >> 16;
        if (op == OpExecutionMode && len >= 3 && m->w[i + 1] == m->entry) {
            uint32_t x = m->w[i + 2];
            if (x == XMOutputVertices && len >= 4) L->max_v = m->w[i + 3];
            else if (x == XMOutputPrimitives && len >= 4) L->max_p = m->w[i + 3];
            else if (x == XMOutputPoints) L->points = 1;
            else if (x == XMOutputLines) FAIL("a mesh program that outputs lines");
        }
        if (op == OpVariable && len >= 4) {
            uint32_t id = m->w[i + 2], sc = m->w[i + 3], pt = m->w[i + 1], pe = ptr_pointee(m, pt, NULL);
            if (sc == SCInput && m->bi[id] == BILocalInvocationIndex && lidvar) *lidvar = id;
            else if (sc == SCInput && m->bi[id] == BIWorkgroupId && wgvar) *wgvar = id;
            else if (sc == SCTaskPayload) {
                uint32_t el = 0, n = array_len(m, pe, &el);
                if (!n || scalar_kind(m, el) != 'u') FAIL("a task payload that is not an array of uint");
                if (L->payload_words) FAIL("two task payloads");
                L->payload_words = n; if (payload) *payload = id;
            } else if (sc == SCOutput) {
                if (L->task) FAIL("a task module with an output");
                if (*nov == 64) FAIL("more than 64 outputs");
                outvar *o = &ov[(*nov)++]; memset(o, 0, sizeof *o); o->id = id;
                uint32_t el = 0, n = array_len(m, pe, &el); o->elem = el;
                if (!n) FAIL("output %u is not an array", id);
                int32_t b = m->bi[id];
                if (b == BIPrimitiveTriangleIndices || b == BIPrimitivePointIndices) o->kind = K_IDX;
                else if (b == BIPrimitiveLineIndices) FAIL("line indices");
                else if (b == BICullPrimitive) { o->kind = K_CULL; L->cull = 1; }
                else if (b >= 0) FAIL("output built-in %d", b);
                else if (m->loc[id] >= 0) {
                    if (m->loc[id] >= 32) FAIL("output location %d", m->loc[id]);
                    uint32_t ct = 0; if (T_op(m, el) != OpTypeVector || T_w(m, el)[3] != 4 || scalar_kind(m, (ct = T_w(m, el)[2])) != 'f') FAIL("output at location %d is not a vec4 array", m->loc[id]);
                    if (m->perprim[id]) { o->kind = K_PLOC; L->ploc[L->nploc++] = (uint8_t)m->loc[id]; } else { o->kind = K_VLOC; L->vloc[L->nvloc++] = (uint8_t)m->loc[id]; }
                } else if (T_op(m, el) == OpTypeStruct) {
                    member_builtins(m, el, o->mb);
                    int nm = (int)(m->w[m->def[el]] >> 16) - 2;
                    for (int k = 0; k < nm; k++) {
                        if (k >= 8 || o->mb[k] < 0) FAIL("a per-vertex block member that is not a built-in");
                        if (o->mb[k] == BIPointSize) L->point_size = 1;
                        else if (o->mb[k] != BIPosition) FAIL("per-vertex built-in %d", o->mb[k]);
                    }
                    o->kind = K_POS;
                } else FAIL("output %u of no known kind", id);
            }
        }
        i += len;
    }
    if (!L->task) {
        if (!L->max_v || !L->max_p) FAIL("a mesh module without OutputVertices / OutputPrimitivesEXT");
        int np = 0, ni = 0; for (int i = 0; i < *nov; i++) { np += ov[i].kind == K_POS; ni += ov[i].kind == K_IDX; }
        if (np != 1 || ni != 1) FAIL("a mesh module with %d position blocks and %d index arrays", np, ni);
        qsort(L->vloc, (size_t)L->nvloc, 1, cmp_u8); qsort(L->ploc, (size_t)L->nploc, 1, cmp_u8);
        for (int i = 0; i < *nov; i++) {
            uint8_t *a = ov[i].kind == K_VLOC ? L->vloc : ov[i].kind == K_PLOC ? L->ploc : NULL; int n = ov[i].kind == K_VLOC ? L->nvloc : L->nploc;
            if (a) for (int k = 0; k < n; k++) if (a[k] == (uint8_t)m->loc[ov[i].id]) ov[i].k = k;
        }
        L->vw = 4 + (uint32_t)L->point_size + 4 * (uint32_t)L->nvloc;
        L->pw = (L->points ? 1u : 3u) + (uint32_t)L->cull + 4 * (uint32_t)L->nploc;
        L->indexed = !L->points && !L->nploc && !L->cull;
    }
    return 1;
fail:
    return 0;
}
int vka_memu_layout_of(const uint32_t *w, size_t nw, vka_memu_layout *L, char *err, size_t errn) {
    mod_t m; outvar ov[64]; int nov; err[0] = 0;
    int ok = mod_read(&m, w, nw, err, errn) && layout_read(&m, L, ov, &nov, NULL, NULL, NULL, err, errn);
    mod_free(&m);
    return ok;
}
void vka_memu_rec_mesh(const vka_memu_layout *L, uint32_t rec[32]) {
    if (L->indexed) {
        rec[VKA_MEMU_R_VSTRIDE] = L->max_v * L->vw; rec[VKA_MEMU_R_PSTRIDE] = L->max_p * 3; rec[VKA_MEMU_R_DRAWV] = L->max_p * 3;
        rec[VKA_MEMU_R_FLAGS] |= VKA_MEMU_F_INDEXED;
    } else {
        rec[VKA_MEMU_R_VSTRIDE] = rec[VKA_MEMU_R_PSTRIDE] = L->max_v * L->vw + L->max_p * L->pw; rec[VKA_MEMU_R_DRAWV] = L->max_p * (L->points ? 1u : 3u);
        rec[VKA_MEMU_R_FLAGS] |= VKA_MEMU_F_INTERLEAVED;
    }
    rec[VKA_MEMU_R_VW] = L->vw; rec[VKA_MEMU_R_MAXV] = L->max_v;
}

// ---------- Building ----------
// New types, constants and variables (appended to the module's declarations), each made once
typedef struct {
    const mod_t *m; sb_t T; uint32_t next;
    struct { uint32_t op, a, b, c, id; } cache[96]; int nc;
    uint32_t t_void, t_bool, t_u32, t_f32, t_v3u, t_v4f;
} bld_t;
static uint32_t find_type(const mod_t *m, uint32_t op, uint32_t a, uint32_t b, uint32_t c, int na) {   // an existing type of this form (OpTypePointer: not a payload one, which is moved)
    if (!m) return 0;
    for (size_t i = 5; i < m->first_fn;) {
        uint32_t o = m->w[i] & 0xffff, len = m->w[i] >> 16;
        if (o == op && (int)len == na + 2 && (na < 1 || m->w[i + 2] == a) && (na < 2 || m->w[i + 3] == b) && (na < 3 || m->w[i + 4] == c)) return m->w[i + 1];
        i += len;
    }
    return 0;
}
static uint32_t ty(bld_t *B, uint32_t op, uint32_t a, uint32_t b, uint32_t c, int na) {
    if (op != OpTypePointer || a != SCTaskPayload) { uint32_t t = find_type(B->m, op, a, b, c, na); if (t) return t; }
    for (int i = 0; i < B->nc; i++) if (B->cache[i].op == op && B->cache[i].a == a && B->cache[i].b == b && B->cache[i].c == c) return B->cache[i].id;
    uint32_t id = B->next++;
    uint32_t v[3] = {a, b, c}; sb_put(&B->T, (uint32_t)(na + 2) << 16 | op); sb_put(&B->T, id); sb_raw(&B->T, v, (size_t)na);
    if (B->nc < 96) B->cache[B->nc++] = (typeof(B->cache[0])){op, a, b, c, id};
    return id;
}
static uint32_t ptr(bld_t *B, uint32_t sc, uint32_t t) { return ty(B, OpTypePointer, sc, t, 0, 2); }
static uint32_t cu(bld_t *B, uint32_t v) {   // a uint constant
    const mod_t *m = B->m;
    if (m) for (size_t i = 5; i < m->first_fn;) { uint32_t o = m->w[i] & 0xffff, len = m->w[i] >> 16; if (o == OpConstant && len == 4 && m->w[i + 1] == B->t_u32 && m->w[i + 3] == v) return m->w[i + 2]; i += len; }
    for (int i = 0; i < B->nc; i++) if (B->cache[i].op == OpConstant && B->cache[i].a == B->t_u32 && B->cache[i].b == v) return B->cache[i].id;
    uint32_t id = B->next++; INS(&B->T, OpConstant, B->t_u32, id, v);
    if (B->nc < 96) B->cache[B->nc++] = (typeof(B->cache[0])){OpConstant, B->t_u32, v, 0, id};
    return id;
}
static uint32_t cf(bld_t *B, float f) { uint32_t v; memcpy(&v, &f, 4); uint32_t id = B->next++; INS(&B->T, OpConstant, B->t_f32, id, v); return id; }
static uint32_t gvar(bld_t *B, uint32_t ptype, uint32_t sc) { uint32_t id = B->next++; INS(&B->T, OpVariable, ptype, id, sc); return id; }
static void bld_base_types(bld_t *B) {
    B->t_void = ty(B, OpTypeVoid, 0, 0, 0, 0); B->t_bool = ty(B, OpTypeBool, 0, 0, 0, 0);
    B->t_u32 = ty(B, OpTypeInt, 32, 0, 0, 2); B->t_f32 = ty(B, OpTypeFloat, 32, 0, 0, 1);
    B->t_v3u = ty(B, OpTypeVector, B->t_u32, 3, 0, 2); B->t_v4f = ty(B, OpTypeVector, B->t_f32, 4, 0, 2);
}
// A storage buffer of uint words: its block type (decorations into A)
static uint32_t words_block(bld_t *B, sb_t *A, int nonwritable) {
    uint32_t ra = B->next++; INS(&B->T, OpTypeRuntimeArray, ra, B->t_u32);
    uint32_t st = B->next++; INS(&B->T, OpTypeStruct, st, ra);
    INS(A, OpDecorate, ra, DecArrayStride, 4); INS(A, OpDecorate, st, DecBlock); INS(A, OpMemberDecorate, st, 0, DecOffset, 0);
    if (nonwritable) INS(A, OpMemberDecorate, st, 0, DecNonWritable);
    return st;
}

// Function code: ids from B->next; F the function's words
typedef struct { bld_t *B; sb_t *F; } fn_t;
static uint32_t op2(fn_t *f, uint32_t op, uint32_t t, uint32_t a, uint32_t b) { uint32_t id = f->B->next++; INS(f->F, op, t, id, a, b); return id; }
static uint32_t op1(fn_t *f, uint32_t op, uint32_t t, uint32_t a) { uint32_t id = f->B->next++; INS(f->F, op, t, id, a); return id; }
static uint32_t sel(fn_t *f, uint32_t t, uint32_t c, uint32_t a, uint32_t b) { uint32_t id = f->B->next++; INS(f->F, OpSelect, t, id, c, a, b); return id; }
static uint32_t ld(fn_t *f, uint32_t t, uint32_t p) { return op1(f, OpLoad, t, p); }
static uint32_t ext(fn_t *f, uint32_t t, uint32_t v, uint32_t k) { return op2(f, OpCompositeExtract, t, v, k); }
static uint32_t chain2(fn_t *f, uint32_t pt, uint32_t base, uint32_t i0, uint32_t i1) { uint32_t id = f->B->next++; INS(f->F, OpAccessChain, pt, id, base, i0, i1); return id; }
static uint32_t chain1(fn_t *f, uint32_t pt, uint32_t base, uint32_t i0) { uint32_t id = f->B->next++; INS(f->F, OpAccessChain, pt, id, base, i0); return id; }
static uint32_t label(fn_t *f) { uint32_t id = f->B->next++; INS(f->F, OpLabel, id); return id; }
// "if (c) {" ... "}": the then label made, the merge returned (fn_endif places it)
static uint32_t fn_if(fn_t *f, uint32_t c) { uint32_t t = f->B->next++, mg = f->B->next++; INS(f->F, OpSelectionMerge, mg, 0); INS(f->F, OpBranchConditional, c, t, mg); INS(f->F, OpLabel, t); return mg; }
static void fn_endif(fn_t *f, uint32_t mg) { INS(f->F, OpBranch, mg); INS(f->F, OpLabel, mg); }

// ---------- Task / mesh as compute ----------
typedef struct { uint32_t var; uint32_t idx[6]; int n; } chain_t;
uint32_t *vka_memu_cs(const uint32_t *w, size_t nw, size_t *out_nw, char *err, size_t errn) {
    mod_t m; bld_t B; memset(&B, 0, sizeof B); sb_t A = {0}, F = {0}, O = {0};
    chain_t *ch = NULL; uint32_t *oid = NULL;   // oid: per id, 1 + the output it is (an output variable or a chain into one), 0 none
    uint8_t *wgch = NULL;   // per id: a chain into the workgroup ID variable (retyped as Private)
    outvar ov[64]; int nov = 0; vka_memu_layout L; uint32_t payload = 0, lidvar = 0, wgvar = 0;
    err[0] = 0;
    if (!mod_read(&m, w, nw, err, errn)) { mod_free(&m); return NULL; }
    if (!layout_read(&m, &L, ov, &nov, &payload, &lidvar, &wgvar, err, errn)) goto fail;
    B.m = &m; B.next = m.bound;
    bld_base_types(&B);
    ch = calloc(m.bound, sizeof *ch); oid = calloc(m.bound, 4); wgch = calloc(m.bound, 1);
    if (!ch || !oid || !wgch) FAIL("out of memory");
    for (int i = 0; i < nov; i++) oid[ov[i].id] = (uint32_t)i + 1;
    // New declarations: the arena and the records (storage buffers of words), the push constant, the dispatch's workgroup ID and counts, the private values
    uint32_t sbw = words_block(&B, &A, 0), p_sbw = ptr(&B, SCStorageBuffer, sbw), p_sb_u32 = ptr(&B, SCStorageBuffer, B.t_u32);
    uint32_t arena = gvar(&B, p_sbw, SCStorageBuffer), recs = gvar(&B, p_sbw, SCStorageBuffer);
    INS(&A, OpDecorate, arena, DecDescriptorSet, 0); INS(&A, OpDecorate, arena, DecBinding, VKA_MEMU_ARENA_BINDING);
    INS(&A, OpDecorate, recs, DecDescriptorSet, 0); INS(&A, OpDecorate, recs, DecBinding, VKA_MEMU_REC_BINDING);
    uint32_t pcs = B.next++; INS(&B.T, OpTypeStruct, pcs, B.t_u32); INS(&A, OpDecorate, pcs, DecBlock); INS(&A, OpMemberDecorate, pcs, 0, DecOffset, 0);
    uint32_t pc = gvar(&B, ptr(&B, SCPushConstant, pcs), SCPushConstant), p_pc_u32 = ptr(&B, SCPushConstant, B.t_u32);
    uint32_t p_in_v3u = ptr(&B, SCInput, B.t_v3u);
    uint32_t in_wg = gvar(&B, p_in_v3u, SCInput), in_nwg = gvar(&B, p_in_v3u, SCInput);
    INS(&A, OpDecorate, in_wg, DecBuiltIn, BIWorkgroupId); INS(&A, OpDecorate, in_nwg, DecBuiltIn, BINumWorkgroups);
    if (!lidvar) { lidvar = gvar(&B, ptr(&B, SCInput, B.t_u32), SCInput); INS(&A, OpDecorate, lidvar, DecBuiltIn, BILocalInvocationIndex); }
    uint32_t p_priv_u32 = ptr(&B, SCPrivate, B.t_u32), p_priv_v3u = ptr(&B, SCPrivate, B.t_v3u), p_wg_u32 = ptr(&B, SCWorkgroup, B.t_u32);
    uint32_t pv_a = gvar(&B, p_priv_u32, SCPrivate), pv_b = gvar(&B, p_priv_u32, SCPrivate), pv_c = gvar(&B, p_priv_u32, SCPrivate);   // mesh: vertex base, primitive base, index bias; task: its slot
    uint32_t new_wgvar = wgvar;   // the program's WorkgroupId variable, re-declared Private (same id) after the new types
    // The chains into the old workgroup ID variable: their pointer types as Private (made before the functions are copied)
    uint32_t pin_from[8] = {0}, pin_to[8] = {0}; int npin = 0;
    for (size_t i = m.first_fn; i < m.n;) {
        uint32_t op = m.w[i] & 0xffff, len = m.w[i] >> 16;
        if ((op == OpAccessChain || op == OpInBoundsAccessChain) && len >= 4) {
            uint32_t base = m.w[i + 3], rt = m.w[i + 1], res = m.w[i + 2];
            if (wgvar && (base == wgvar || wgch[base])) {
                wgch[res] = 1; int k = 0; while (k < npin && pin_from[k] != rt) k++;
                if (k == npin) { if (npin == 8) FAIL("too many workgroup ID pointer types"); uint32_t sc = 0, pe = ptr_pointee(&m, rt, &sc); pin_from[npin] = rt; pin_to[npin++] = ptr(&B, SCPrivate, pe); }
            }
        }
        i += len;
    }
    uint32_t c0 = cu(&B, 0), c1 = cu(&B, 1), c32 = cu(&B, VKA_MEMU_REC_WORDS), cwg = cu(&B, 2), csem = cu(&B, 0x108);   // (Workgroup scope; AcquireRelease | WorkgroupMemory)
    uint32_t t_fnv = ty(&B, OpTypeFunction, B.t_void, 0, 0, 1), t_fnvu = ty(&B, OpTypeFunction, B.t_void, B.t_u32, 0, 2);
    uint32_t emu_main = B.next++, task_out = L.task ? B.next++ : 0, cfalse = 0;
    if (new_wgvar) INS(&B.T, OpVariable, p_priv_v3u, new_wgvar, SCPrivate);   // (after its pointer type)

    // The header, capabilities, extensions, entry point, modes, debug and decorations
    sb_raw(&O, m.w, 5);
    for (size_t i = 5; i < m.first_fn;) {
        uint32_t op = m.w[i] & 0xffff, len = m.w[i] >> 16; const uint32_t *x = m.w + i;
        i += len;
        if (op == OpCapability && x[1] == CapMeshShading) continue;
        if (op == OpExtension && !strcmp((const char *)(x + 1), "SPV_EXT_mesh_shader")) continue;
        if (op == OpEntryPoint) {
            size_t sw = strlen((const char *)(x + 3)) / 4 + 1;
            uint32_t ifc[256]; uint32_t ni = 0;
            for (size_t k = 3 + sw; k < len; k++) if (!(x[k] < m.bound && oid[x[k]]) && x[k] != wgvar && ni < 240) ifc[ni++] = x[k];
            uint32_t add[] = {arena, recs, pc, in_wg, in_nwg, pv_a, pv_b, pv_c, new_wgvar, lidvar};
            for (size_t k = 0; k < sizeof add / 4; k++) if (add[k]) { int dup = 0; for (uint32_t j = 0; j < ni; j++) dup |= ifc[j] == add[k]; if (!dup) ifc[ni++] = add[k]; }
            uint32_t pre[2] = {EMGLCompute, emu_main};
            sb_str_ins(&O, OpEntryPoint, pre, 2, (const char *)(x + 3), ifc, ni);
            continue;
        }
        if (op == OpExecutionMode || op == OpExecutionModeId) {
            if (x[1] != m.entry) { sb_raw(&O, x, len); continue; }
            if (op == OpExecutionMode && (x[2] == XMOutputVertices || x[2] == XMOutputPrimitives || x[2] == XMOutputPoints || x[2] == XMOutputTriangles || x[2] == XMOutputLines)) continue;
            sb_put(&O, x[0]); sb_put(&O, emu_main); sb_raw(&O, x + 2, len - 2); continue;
        }
        if ((op == OpName || op == OpMemberName) && x[1] < m.bound && oid[x[1]]) continue;
        if (op == OpDecorate || op == OpDecorateId || op == OpDecorateString) {
            uint32_t t = x[1];
            if (t < m.bound && oid[t]) continue;
            if (t == wgvar && x[2] == DecBuiltIn) continue;
            int pvb = 0; for (int k = 0; k < nov; k++) pvb |= ov[k].kind == K_POS && ov[k].elem == t;
            if (pvb && x[2] == DecBlock) continue;
        }
        if (op == OpMemberDecorate || op == OpMemberDecorateString) {
            int pvb = 0; for (int k = 0; k < nov; k++) pvb |= ov[k].kind == K_POS && ov[k].elem == x[1];
            if (pvb) continue;
        }
        if (op == OpTypeFunction || type_decl(op) || op == OpConstant || op == OpVariable || (op >= 41 && op <= 52) || op == OpUndef || op == OpLine || op == OpNoLine || op == OpExtInst) {
            // The declarations: first the annotations made here, once, before the first type (the decorations section ends where the types begin)
            if (A.n) { sb_raw(&O, A.w, A.n); A.n = 0; }
            if (op == OpVariable && x[3] == SCOutput) continue;
            if (op == OpVariable && x[2] == wgvar) continue;
            if (op == OpTypePointer && x[2] == SCTaskPayload) { INS(&O, OpTypePointer, x[1], SCWorkgroup, x[3]); continue; }
            if (op == OpVariable && x[3] == SCTaskPayload) { sb_put(&O, x[0]); sb_put(&O, x[1]); sb_put(&O, x[2]); sb_put(&O, SCWorkgroup); sb_raw(&O, x + 4, len - 4); continue; }
        }
        sb_raw(&O, x, len);
    }
    if (A.n) { sb_raw(&O, A.w, A.n); A.n = 0; }
    // (B.T now only grows by constants made below: ids are taken as we go, the words placed at the end of the declarations, before the functions)
    size_t tpos = O.n;

    // The functions, with the outputs' stores turned into arena stores
    for (size_t i = m.first_fn; i < m.n;) {
        uint32_t op = m.w[i] & 0xffff, len = m.w[i] >> 16; const uint32_t *x = m.w + i;
        i += len;
        if ((op == OpAccessChain || op == OpInBoundsAccessChain) && len >= 4) {
            uint32_t base = x[3], res = x[2];
            if (base < m.bound && oid[base]) {
                chain_t c = {0}; if (ch[base].var) c = ch[base]; else c.var = base;
                for (uint32_t k = 4; k < len; k++) { if (c.n == 6) FAIL("an output chain of more than 6 indices"); c.idx[c.n++] = x[k]; }
                ch[res] = c; oid[res] = oid[base]; continue;
            }
            if (wgch[res]) {
                uint32_t rt = x[1]; for (int k = 0; k < npin; k++) if (pin_from[k] == rt) rt = pin_to[k];
                sb_put(&O, x[0]); sb_put(&O, rt); sb_raw(&O, x + 2, len - 2); continue;
            }
        }
        if (op == OpLoad && len >= 4 && x[3] < m.bound && oid[x[3]]) FAIL("a load from an output");
        if ((op == OpCopyMemory || op == OpCopyMemorySized) && ((x[1] < m.bound && oid[x[1]]) || (x[2] < m.bound && oid[x[2]]))) FAIL("a copy of an output");
        if (op == OpFunctionCall) for (uint32_t k = 4; k < len; k++) if (x[k] < m.bound && oid[x[k]]) FAIL("an output passed to a function");
        if (op == OpSetMeshOutputsEXT) continue;
        // A Function bool without an initializer starts false: the translated code spills predicates to such locals and may read one its branch never wrote (a
        // visibility test passed over), which SPIR-V leaves undefined; a driver that kept the stale value dropped whole mesh workgroups (Forgejo PR #170's finding
        // on Qualcomm's driver: one primitive of 32 in most foliage workgroups). Numeric locals are left as they are
        if (op == OpVariable && len == 4 && x[3] == SCFunction && scalar_kind(&m, ptr_pointee(&m, x[1], NULL)) == 'b') {
            if (!cfalse) { cfalse = B.next++; INS(&B.T, OpConstantFalse, B.t_bool, cfalse); }
            INS(&O, OpVariable, x[1], x[2], SCFunction, cfalse); continue;
        }
        if (op == OpEmitMeshTasksEXT) { uint32_t r = B.next++; INS(&O, OpFunctionCall, B.t_void, r, task_out, x[1]); INS(&O, OpReturn); continue; }
        if (op == OpStore && len >= 3 && x[1] < m.bound && oid[x[1]]) {
            const outvar *o = &ov[oid[x[1]] - 1];
            chain_t c = ch[x[1]];
            if (!c.var || c.n < 1) FAIL("a store into a whole output");
            fn_t f = {&B, &O};
            int prim = o->kind == K_PLOC || o->kind == K_IDX || o->kind == K_CULL;
            uint32_t stride = prim ? (L.indexed ? 3 : L.pw) : L.vw, off = 0;
            uint32_t pidx = c.idx[0];
            int isg = scalar_kind(&m, m.rtype[pidx]) == 'i';
            if (isg) pidx = op1(&f, OpBitcast, B.t_u32, pidx);
            uint32_t t = m.rtype[x[2]];   // the stored value's type
            int k = 1; uint32_t dyn = 0;
            switch (o->kind) {
            case K_POS: {
                if (c.n < 2 || !m.isc[c.idx[1]] || m.cval[c.idx[1]] >= 8) FAIL("a per-vertex block store without a constant member");
                int32_t b = o->mb[m.cval[c.idx[1]]]; off = b == BIPosition ? 0 : 4; k = 2; break; }
            case K_VLOC: off = 4 + (uint32_t)L.point_size + 4 * (uint32_t)o->k; break;
            case K_PLOC: off = (L.points ? 1u : 3u) + (uint32_t)L.cull + 4 * (uint32_t)o->k; break;
            case K_IDX: off = 0; break;
            case K_CULL: off = L.points ? 1 : 3; break;
            }
            if (k < c.n) {   // a component
                if (c.n > k + 1) FAIL("an output chain past a component");
                uint32_t ci = c.idx[k];
                if (m.isc[ci]) off += m.cval[ci];
                else { dyn = ci; if (scalar_kind(&m, m.rtype[ci]) == 'i') dyn = op1(&f, OpBitcast, B.t_u32, ci); }
            }
            uint32_t basev = ld(&f, B.t_u32, prim ? pv_b : pv_a);
            uint32_t a = op2(&f, OpIAdd, B.t_u32, basev, op2(&f, OpIMul, B.t_u32, pidx, cu(&B, stride)));
            if (off) a = op2(&f, OpIAdd, B.t_u32, a, cu(&B, off));
            if (dyn) a = op2(&f, OpIAdd, B.t_u32, a, dyn);
            uint32_t bias = o->kind == K_IDX && L.indexed ? ld(&f, B.t_u32, pv_c) : 0;
            uint32_t vt = t, nc = 1, ct = t;
            if (T_op(&m, t) == OpTypeVector) { nc = T_w(&m, t)[3]; ct = T_w(&m, t)[2]; }
            int sk = scalar_kind(&m, ct);
            if (!sk || nc > 4) FAIL("a store of type %u into an output", t);
            for (uint32_t j = 0; j < nc; j++) {
                uint32_t v = nc > 1 ? ext(&f, ct, x[2], j) : x[2];
                uint32_t u = sk == 'u' ? v : sk == 'b' ? sel(&f, B.t_u32, v, c1, c0) : op1(&f, OpBitcast, B.t_u32, v);
                if (bias) u = op2(&f, OpIAdd, B.t_u32, u, bias);
                uint32_t aj = j ? op2(&f, OpIAdd, B.t_u32, a, cu(&B, j)) : a;
                INS(&O, OpStore, chain2(&f, p_sb_u32, arena, c0, aj), u);
            }
            (void)vt;
            continue;
        }
        sb_raw(&O, x, len);
    }
    // The record word k of this draw: its pointer
    #define RECW(f, rb, k) chain2((f), p_sb_u32, recs, c0, op2((f), OpIAdd, B.t_u32, (rb), cu(&B, (k))))
    #define AREA(f, a) chain2((f), p_sb_u32, arena, c0, (a))
    {   // The entry point: which workgroup of the draw this is, its record; past the draw's count it returns
        fn_t f = {&B, &O};
        INS(&O, OpFunction, B.t_void, emu_main, 0, t_fnv); label(&f);
        uint32_t wv = ld(&f, B.t_v3u, in_wg), nv = ld(&f, B.t_v3u, in_nwg);
        uint32_t flat = op2(&f, OpIAdd, B.t_u32, ext(&f, B.t_u32, wv, 0), op2(&f, OpIMul, B.t_u32, ext(&f, B.t_u32, wv, 1), ext(&f, B.t_u32, nv, 0)));
        uint32_t rb = op2(&f, OpIMul, B.t_u32, ld(&f, B.t_u32, chain1(&f, p_pc_u32, pc, c0)), c32);
        uint32_t lim = ld(&f, B.t_u32, RECW(&f, rb, L.task ? VKA_MEMU_R_NTASK : VKA_MEMU_R_TOTAL));
        uint32_t mg = fn_if(&f, op2(&f, OpULessThan, B.t_bool, flat, lim));
        uint32_t first = ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_FIRST));
        uint32_t own;
        if (L.task) {
            own = op2(&f, OpIAdd, B.t_u32, flat, first);
            INS(&O, OpStore, pv_c, flat);
        } else {
            // With a task: the task t whose first mesh workgroup is the last one not past this one (a branchless binary search of the offsets; a task without mesh
            // workgroups shares the next one's offset and loses to it), and this one's index among t's; t's payload into the workgroup's own
            uint32_t flags = ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_FLAGS));
            uint32_t istask = op2(&f, OpINotEqual, B.t_bool, op2(&f, OpBitwiseAnd, B.t_u32, flags, cu(&B, VKA_MEMU_F_TASK)), c0);
            uint32_t nt = ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_NTASK)), cb = ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_COUNTS));
            nt = sel(&f, B.t_u32, istask, nt, c1);   // (no task: one, at an offset of 0 read at the arena's start: unused)
            cb = sel(&f, B.t_u32, istask, cb, c0);
            uint32_t last = op2(&f, OpISub, B.t_u32, sel(&f, B.t_u32, op2(&f, OpULessThan, B.t_bool, nt, c1), c1, nt), c1);
            uint32_t t = c0, rem = nt;
            for (int s = 0; s < 17; s++) {
                uint32_t half = op2(&f, OpShiftRightLogical, B.t_u32, rem, c1);
                uint32_t mid = op2(&f, OpIAdd, B.t_u32, t, half);
                mid = sel(&f, B.t_u32, op2(&f, OpULessThan, B.t_bool, mid, last), mid, last);
                uint32_t o = ld(&f, B.t_u32, AREA(&f, op2(&f, OpIAdd, B.t_u32, cb, mid)));
                t = sel(&f, B.t_u32, op2(&f, OpULessThanEqual, B.t_bool, o, flat), mid, t);
                rem = op2(&f, OpISub, B.t_u32, rem, half);
            }
            uint32_t ot = ld(&f, B.t_u32, AREA(&f, op2(&f, OpIAdd, B.t_u32, cb, t)));
            own = sel(&f, B.t_u32, istask, op2(&f, OpISub, B.t_u32, flat, ot), op2(&f, OpIAdd, B.t_u32, flat, first));
            if (payload) {   // words past the task's own (or every word without a task) read 0
                uint32_t wt = ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_WT)), pl = ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_PAYLOAD));
                uint32_t src0 = op2(&f, OpIAdd, B.t_u32, pl, op2(&f, OpIMul, B.t_u32, t, wt));
                uint32_t lid = ld(&f, B.t_u32, lidvar);
                for (uint32_t k = 0; k * L.threads < L.payload_words; k++) {
                    uint32_t i = k ? op2(&f, OpIAdd, B.t_u32, lid, cu(&B, k * L.threads)) : lid, mg2 = 0;
                    if ((k + 1) * L.threads > L.payload_words) mg2 = fn_if(&f, op2(&f, OpULessThan, B.t_bool, i, cu(&B, L.payload_words)));
                    uint32_t use = op2(&f, OpLogicalAnd, B.t_bool, istask, op2(&f, OpULessThan, B.t_bool, i, wt));
                    uint32_t v = ld(&f, B.t_u32, AREA(&f, sel(&f, B.t_u32, use, op2(&f, OpIAdd, B.t_u32, src0, i), c0)));
                    INS(&O, OpStore, chain1(&f, p_wg_u32, payload, i), sel(&f, B.t_u32, use, v, c0));
                    if (mg2) fn_endif(&f, mg2);
                }
                INS(&O, OpControlBarrier, cwg, cwg, csem);
            }
            uint32_t vb = op2(&f, OpIAdd, B.t_u32, ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_VBASE)), op2(&f, OpIMul, B.t_u32, flat, ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_VSTRIDE))));
            uint32_t pb = op2(&f, OpIAdd, B.t_u32, ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_PBASE)), op2(&f, OpIMul, B.t_u32, flat, ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_PSTRIDE))));
            INS(&O, OpStore, pv_a, vb); INS(&O, OpStore, pv_b, pb);
            INS(&O, OpStore, pv_c, op2(&f, OpUDiv, B.t_u32, vb, cu(&B, L.vw)));
        }
        if (new_wgvar) { uint32_t v = B.next++; INS(&O, OpCompositeConstruct, B.t_v3u, v, own, c0, c0); INS(&O, OpStore, new_wgvar, v); }
        uint32_t r = B.next++; INS(&O, OpFunctionCall, B.t_void, r, m.entry);
        fn_endif(&f, mg);
        INS(&O, OpReturn); INS(&O, OpFunctionEnd);
    }
    if (L.task) {   // OpEmitMeshTasksEXT(count, 1, 1): the payload and the count into the arena (after the program's last barrier, in uniform control flow)
        fn_t f = {&B, &O};
        uint32_t cnt = B.next++;
        INS(&O, OpFunction, B.t_void, task_out, 0, t_fnvu); INS(&O, OpFunctionParameter, B.t_u32, cnt); label(&f);
        uint32_t rb = op2(&f, OpIMul, B.t_u32, ld(&f, B.t_u32, chain1(&f, p_pc_u32, pc, c0)), c32);
        uint32_t ts = ld(&f, B.t_u32, pv_c), lid = ld(&f, B.t_u32, lidvar);
        if (payload) {
            uint32_t base = op2(&f, OpIAdd, B.t_u32, ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_PAYLOAD)), op2(&f, OpIMul, B.t_u32, ts, cu(&B, L.payload_words)));
            for (uint32_t k = 0; k * L.threads < L.payload_words; k++) {
                uint32_t i = k ? op2(&f, OpIAdd, B.t_u32, lid, cu(&B, k * L.threads)) : lid, mg2 = 0;
                if ((k + 1) * L.threads > L.payload_words) mg2 = fn_if(&f, op2(&f, OpULessThan, B.t_bool, i, cu(&B, L.payload_words)));
                INS(&O, OpStore, AREA(&f, op2(&f, OpIAdd, B.t_u32, base, i)), ld(&f, B.t_u32, chain1(&f, p_wg_u32, payload, i)));
                if (mg2) fn_endif(&f, mg2);
            }
        }
        uint32_t mg = fn_if(&f, op2(&f, OpIEqual, B.t_bool, lid, c0));
        INS(&O, OpStore, AREA(&f, op2(&f, OpIAdd, B.t_u32, ld(&f, B.t_u32, RECW(&f, rb, VKA_MEMU_R_COUNTS)), ts)), cnt);
        fn_endif(&f, mg);
        INS(&O, OpReturn); INS(&O, OpFunctionEnd);
    }
    #undef RECW
    #undef AREA
    // The new declarations go before the functions
    if (B.T.oom || O.oom || A.oom) FAIL("out of memory");
    {
        uint32_t *o = malloc((O.n + B.T.n) * 4); if (!o) FAIL("out of memory");
        memcpy(o, O.w, tpos * 4); memcpy(o + tpos, B.T.w, B.T.n * 4); memcpy(o + tpos + B.T.n, O.w + tpos, (O.n - tpos) * 4);
        o[3] = B.next; *out_nw = O.n + B.T.n;
        free(O.w); free(A.w); free(B.T.w); free(F.w); free(ch); free(oid); free(wgch); mod_free(&m);
        return o;
    }
fail:
    free(O.w); free(A.w); free(B.T.w); free(F.w); free(ch); free(oid); free(wgch); mod_free(&m);
    return NULL;
}

// ---------- The vertex shader ----------
uint32_t *vka_memu_vs(const vka_memu_layout *L, size_t *out_nw, char *err, size_t errn) {
    bld_t B; memset(&B, 0, sizeof B); sb_t A = {0}, O = {0}, F = {0};
    err[0] = 0;
    if (L->task || !L->max_v || !L->max_p || L->vw < 4) { snprintf(err, errn, "not a mesh layout"); return NULL; }
    B.next = 1;
    bld_base_types(&B);
    uint32_t t_i32 = ty(&B, OpTypeInt, 32, 1, 0, 2);
    uint32_t sbw = words_block(&B, &A, 1), arena = gvar(&B, ptr(&B, SCStorageBuffer, sbw), SCStorageBuffer), p_sb_u32 = ptr(&B, SCStorageBuffer, B.t_u32);
    INS(&A, OpDecorate, arena, DecDescriptorSet, 0); INS(&A, OpDecorate, arena, DecBinding, VKA_MEMU_ARENA_BINDING);
    uint32_t vid = gvar(&B, ptr(&B, SCInput, t_i32), SCInput); INS(&A, OpDecorate, vid, DecBuiltIn, BIVertexIndex);
    uint32_t p_out_v4 = ptr(&B, SCOutput, B.t_v4f), p_out_f = ptr(&B, SCOutput, B.t_f32);
    uint32_t pos = gvar(&B, p_out_v4, SCOutput); INS(&A, OpDecorate, pos, DecBuiltIn, BIPosition); INS(&A, OpDecorate, pos, DecInvariant);
    uint32_t psz = 0; if (L->points) { psz = gvar(&B, p_out_f, SCOutput); INS(&A, OpDecorate, psz, DecBuiltIn, BIPointSize); }
    uint32_t vo[32], po[32];
    for (int k = 0; k < L->nvloc; k++) { vo[k] = gvar(&B, p_out_v4, SCOutput); INS(&A, OpDecorate, vo[k], DecLocation, L->vloc[k]); }
    for (int k = 0; k < L->nploc; k++) { po[k] = gvar(&B, p_out_v4, SCOutput); INS(&A, OpDecorate, po[k], DecLocation, L->ploc[k]); }
    uint32_t c0 = cu(&B, 0), t_fnv = ty(&B, OpTypeFunction, B.t_void, 0, 0, 1), main_ = B.next++;
    uint32_t f2 = cf(&B, 2.0f), f1 = cf(&B, 1.0f), fh = cf(&B, 0.5f);
    fn_t f = {&B, &F};
    INS(&F, OpFunction, B.t_void, main_, 0, t_fnv); label(&f);
    #define WORD(a) ld(&f, B.t_u32, chain2(&f, p_sb_u32, arena, c0, (a)))
    #define FWORD(a) op1(&f, OpBitcast, B.t_f32, WORD(a))
    uint32_t n = op1(&f, OpBitcast, B.t_u32, ld(&f, t_i32, vid));
    uint32_t vb, pb = 0, culled = 0;
    if (L->indexed) vb = op2(&f, OpIMul, B.t_u32, n, cu(&B, L->vw));
    else {
        uint32_t vpp = L->points ? 1 : 3, dv = L->max_p * vpp, sw = L->max_v * L->vw + L->max_p * L->pw;
        uint32_t slot = op2(&f, OpUDiv, B.t_u32, n, cu(&B, dv)), r = op2(&f, OpUMod, B.t_u32, n, cu(&B, dv));
        uint32_t p = vpp == 1 ? r : op2(&f, OpUDiv, B.t_u32, r, cu(&B, vpp)), corner = vpp == 1 ? c0 : op2(&f, OpUMod, B.t_u32, r, cu(&B, vpp));
        uint32_t sb = op2(&f, OpIMul, B.t_u32, slot, cu(&B, sw));
        pb = op2(&f, OpIAdd, B.t_u32, op2(&f, OpIAdd, B.t_u32, sb, cu(&B, L->max_v * L->vw)), op2(&f, OpIMul, B.t_u32, p, cu(&B, L->pw)));
        uint32_t vi = WORD(op2(&f, OpIAdd, B.t_u32, pb, corner)), lastv = cu(&B, L->max_v - 1);
        vi = sel(&f, B.t_u32, op2(&f, OpULessThan, B.t_bool, vi, lastv), vi, lastv);
        vb = op2(&f, OpIAdd, B.t_u32, sb, op2(&f, OpIMul, B.t_u32, vi, cu(&B, L->vw)));
        if (L->cull) culled = op2(&f, OpINotEqual, B.t_bool, WORD(op2(&f, OpIAdd, B.t_u32, pb, cu(&B, L->points ? 1 : 3))), c0);
    }
    uint32_t pc_[4];
    for (uint32_t c = 0; c < 4; c++) pc_[c] = FWORD(c ? op2(&f, OpIAdd, B.t_u32, vb, cu(&B, c)) : vb);
    if (culled) {   // a culled point: outside the clip volume (x = y = 2w), which drops it
        pc_[0] = sel(&f, B.t_f32, culled, f2, pc_[0]); pc_[1] = sel(&f, B.t_f32, culled, f2, pc_[1]);
        pc_[2] = sel(&f, B.t_f32, culled, fh, pc_[2]); pc_[3] = sel(&f, B.t_f32, culled, f1, pc_[3]);
    }
    { uint32_t v = B.next++; INS(&F, OpCompositeConstruct, B.t_v4f, v, pc_[0], pc_[1], pc_[2], pc_[3]); INS(&F, OpStore, pos, v); }
    if (psz) INS(&F, OpStore, psz, L->point_size ? FWORD(op2(&f, OpIAdd, B.t_u32, vb, cu(&B, 4))) : f1);
    for (int k = 0; k < L->nvloc + L->nploc; k++) {
        int prim = k >= L->nvloc; uint32_t base = prim ? pb : vb, o = prim ? (L->points ? 1u : 3u) + (uint32_t)L->cull + 4u * (uint32_t)(k - L->nvloc) : 4u + (uint32_t)L->point_size + 4u * (uint32_t)k;
        uint32_t c4[4]; for (uint32_t c = 0; c < 4; c++) c4[c] = FWORD(op2(&f, OpIAdd, B.t_u32, base, cu(&B, o + c)));
        uint32_t v = B.next++; INS(&F, OpCompositeConstruct, B.t_v4f, v, c4[0], c4[1], c4[2], c4[3]); INS(&F, OpStore, prim ? po[k - L->nvloc] : vo[k], v);
    }
    #undef WORD
    #undef FWORD
    INS(&F, OpReturn); INS(&F, OpFunctionEnd);
    // The module: SPIR-V 1.3 (the interface lists the inputs and outputs only)
    uint32_t hdr[5] = {0x07230203, 0x00010300, 0, 0, 0}; sb_raw(&O, hdr, 5);
    INS(&O, OpCapability, CapShader);
    INS(&O, OpMemoryModel, 0, 1);
    { uint32_t ifc[72]; uint32_t ni = 0; ifc[ni++] = vid; ifc[ni++] = pos; if (psz) ifc[ni++] = psz;
      for (int k = 0; k < L->nvloc; k++) ifc[ni++] = vo[k]; for (int k = 0; k < L->nploc; k++) ifc[ni++] = po[k];
      uint32_t pre[2] = {EMVertex, main_}; sb_str_ins(&O, OpEntryPoint, pre, 2, "main", ifc, ni); }
    sb_raw(&O, A.w, A.n); sb_raw(&O, B.T.w, B.T.n); sb_raw(&O, F.w, F.n);
    if (O.oom || A.oom || B.T.oom || F.oom) { snprintf(err, errn, "out of memory"); free(O.w); free(A.w); free(B.T.w); free(F.w); return NULL; }
    O.w[3] = B.next; *out_nw = O.n;
    free(A.w); free(B.T.w); free(F.w);
    return O.w;
}

// ---------- The pixel shader of per-primitive inputs ----------
uint32_t *vka_memu_fs_flat(const uint32_t *w, size_t nw, size_t *out_nw, char *err, size_t errn) {
    err[0] = 0;
    if (nw < 5 || w[0] != 0x07230203) { snprintf(err, errn, "not SPIR-V"); return NULL; }
    uint32_t bound = w[3]; uint8_t *flat = calloc(bound, 1), *pp = calloc(bound, 1); int any = 0, other_mesh = 0;
    if (!flat || !pp) { free(flat); free(pp); snprintf(err, errn, "out of memory"); return NULL; }
    for (size_t i = 5; i < nw;) {
        uint32_t op = w[i] & 0xffff, len = w[i] >> 16;
        if (!len || i + len > nw) { free(flat); free(pp); snprintf(err, errn, "bad instruction at word %zu", i); return NULL; }
        if (op == OpDecorate && len >= 3 && w[i + 1] < bound) { if (w[i + 2] == DecPerPrimitive) { pp[w[i + 1]] = 1; any = 1; } else if (w[i + 2] == DecFlat) flat[w[i + 1]] = 1; }
        if (op == OpMemberDecorate && len >= 4 && w[i + 3] == DecPerPrimitive) other_mesh = 1;
        if (op == OpEntryPoint && w[i + 1] != EMFragment) other_mesh = 1;
        i += len;
    }
    if (!any) { free(flat); free(pp); return NULL; }
    if (other_mesh) { free(flat); free(pp); snprintf(err, errn, "per-primitive block members, or not a pixel shader"); return NULL; }
    uint32_t *o = malloc((nw + 64) * 4); size_t n = 5;
    if (!o) { free(flat); free(pp); snprintf(err, errn, "out of memory"); return NULL; }
    memcpy(o, w, 20);
    for (size_t i = 5; i < nw;) {
        uint32_t op = w[i] & 0xffff, len = w[i] >> 16;
        if (op == OpCapability && w[i + 1] == CapMeshShading) { i += len; continue; }
        if (op == OpExtension && !strcmp((const char *)(w + i + 1), "SPV_EXT_mesh_shader")) { i += len; continue; }
        if (op == OpDecorate && len >= 3 && w[i + 2] == DecPerPrimitive) {
            uint32_t t = w[i + 1];
            if (!flat[t]) { o[n++] = 3u << 16 | OpDecorate; o[n++] = t; o[n++] = DecFlat; flat[t] = 1; }   // (n stays within nw + 64: each such line is replaced by one no longer)
            i += len; continue;
        }
        memcpy(o + n, w + i, len * 4); n += len; i += len;
    }
    free(flat); free(pp);
    *out_nw = n; return o;
}

// ---------- What a vertex shader of a layout writes, what a pixel shader reads ----------
uint64_t vka_memu_vs_locs(const vka_memu_layout *L) {
    uint64_t m = 0;
    for (int k = 0; k < L->nvloc; k++) m |= 1ull << L->vloc[k];
    for (int k = 0; k < L->nploc; k++) m |= 1ull << L->ploc[k];
    return m;
}
int vka_memu_fs_input_locs(const uint32_t *w, size_t nw, uint64_t *mask) {
    mod_t m; char err[64]; *mask = 0;
    if (!mod_read(&m, w, nw, err, sizeof err)) { mod_free(&m); return 0; }
    int ok = 1;
    for (size_t i = 5; i < m.first_fn && ok;) {
        uint32_t op = m.w[i] & 0xffff, len = m.w[i] >> 16;
        if (op == OpVariable && len >= 4 && m.w[i + 3] == SCInput) {
            uint32_t id = m.w[i + 2], t = ptr_pointee(&m, m.w[i + 1], NULL), n = 1, el = t;
            if (m.bi[id] < 0) {
                if (T_op(&m, t) == OpTypeArray) { n = array_len(&m, t, &el); if (!n) ok = 0; }
                uint32_t ct = T_op(&m, el) == OpTypeVector ? T_w(&m, el)[2] : el;
                if (m.loc[id] < 0 || !scalar_kind(&m, ct) || m.loc[id] + n > 64) ok = 0;   // (a block, a 64-bit or matrix input, past 63)
                else for (uint32_t k = 0; k < n; k++) *mask |= 1ull << (m.loc[id] + k);
            }
        }
        i += len;
    }
    mod_free(&m);
    if (!ok) *mask = 0;
    return ok;
}
