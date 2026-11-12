// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors (the compute mesh emulation: pavelgajdo/Hokkaido #569)
// SPDX-FileCopyrightText: Copyright 2026 Banana Native (this port)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// NVN2 task and mesh programs as compute shaders, and the vertex shaders that draw what they wrote (vka_memu_spv.c). Plain C, no Vulkan: unit-tested on the Mac
// (dm/host/tests t_android_memu). The record and arena layout is vka_memu_abi.h's. GPL-3.0-or-later: see vka_memu_abi.h.
#pragma once
#include <stddef.h>
#include <stdint.h>

// What a task or mesh module of the library is, read from the module itself (the translator's SpirvMeshEntry.cs forms). Words: what one vertex or primitive takes in
// the arena. A vertex: position x, y, z, w, then the point size (points), then 4 words per per-vertex location in vloc order. A primitive: its indices (3, or 1 for
// a point), then the cull word (points), then 4 words per per-primitive location in ploc order; an indexed draw's primitive is its 3 indices only
typedef struct {
    int task;                       // 1 task (TaskEXT), 0 mesh (MeshEXT)
    uint32_t local[3], threads;     // the workgroup
    uint32_t payload_words;         // the task payload the module writes (task) or reads (mesh; 0: it reads none)
    uint32_t max_v, max_p;          // mesh: OutputVertices, OutputPrimitivesEXT
    int points;                     // mesh: OutputPoints (else OutputTrianglesEXT)
    int point_size, cull;           // mesh: the per-vertex block has PointSize; the CullPrimitiveEXT array is there
    uint8_t vloc[32], ploc[32];     // mesh: the per-vertex and per-primitive generic locations, in arena order (ascending)
    int nvloc, nploc;
    uint32_t vw, pw;                // mesh: words per vertex, words per primitive (the expanded form's)
    int indexed;                    // mesh: drawn as indexed triangles (triangles without per-primitive values); else expanded (each corner a vertex of its own)
} vka_memu_layout;

// The layout of a task or mesh module. 1 = known; 0 with the reason in err (a form the emulation does not take: lines, other built-in outputs, 16-bit outputs)
int vka_memu_layout_of(const uint32_t *w, size_t nw, vka_memu_layout *L, char *err, size_t errn);
// The record words a mesh draw of this layout takes from the host (vka_memu_abi.h): VKA_MEMU_R_VSTRIDE, _PSTRIDE, _VW, _MAXV, _DRAWV and the VKA_MEMU_F_INDEXED /
// _INTERLEAVED flags
void vka_memu_rec_mesh(const vka_memu_layout *L, uint32_t rec[32]);

// The task or mesh module w as a GLCompute module (malloc'd, *out_nw words; NULL with the reason in err): its entry point dispatched over the draw's workgroups
// (WorkgroupId.x + WorkgroupId.y x NumWorkgroups.x, past VKA_MEMU_R_NTASK / _TOTAL returning), its record at the push constant. A task module writes its payload and its
// mesh workgroup count into the arena where OpEmitMeshTasksEXT was; a mesh module finds its task by a binary search of the offsets and copies that task's payload into
// its own workgroup memory, and stores its vertices and primitives into the arena where it stored its outputs (no OpSetMeshOutputsEXT). Its workgroup index is the one
// the program reads as WorkgroupId (CtaId): its index among its task's mesh workgroups, or, without a task, the draw's first workgroup plus its own index (task: the same)
uint32_t *vka_memu_cs(const uint32_t *w, size_t nw, size_t *out_nw, char *err, size_t errn);
// The vertex shader that draws a mesh layout's arena words: indexed (VertexIndex = the arena vertex) or expanded (VertexIndex = slot x DRAWV + primitive x corners +
// corner), with Position (Invariant), PointSize for points, every per-vertex location, and every per-primitive location as an output of each of its corners. Set 0
// binding VKA_MEMU_ARENA_BINDING. malloc'd (*out_nw words), NULL with the reason in err
uint32_t *vka_memu_vs(const vka_memu_layout *L, size_t *out_nw, char *err, size_t errn);
// A pixel shader of per-primitive inputs (PerPrimitiveEXT, made Flat by the library build) for a vertex pipeline: the decoration, MeshShadingEXT and SPV_EXT_mesh_shader
// dropped, every such input flat. malloc'd (*out_nw words); NULL with err empty when the module has none
uint32_t *vka_memu_fs_flat(const uint32_t *w, size_t nw, size_t *out_nw, char *err, size_t errn);
// The locations a mesh layout's vertex shader (vka_memu_vs) writes: bit L for location L (per-vertex and per-primitive ones)
uint64_t vka_memu_vs_locs(const vka_memu_layout *L);
// The user input locations a pixel shader reads (bit L for location L; an array takes one location per element) into *mask: 1, or 0 when it cannot tell (a block of
// inputs, a 64-bit or matrix input, a location past 63, not SPIR-V)
int vka_memu_fs_input_locs(const uint32_t *w, size_t nw, uint64_t *mask);
