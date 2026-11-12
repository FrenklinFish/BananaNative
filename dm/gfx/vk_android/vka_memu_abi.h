// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors (the compute mesh emulation: pavelgajdo/Hokkaido #569, #642)
// SPDX-FileCopyrightText: Copyright 2026 Banana Native (this port)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// NVN2 task and mesh draws through compute on a device without VK_EXT_mesh_shader (vka_memu_spv.c, vka_memu.c; docs/reference/rendering.md "Mesh draws through
// compute"): what the converted task / mesh modules, the generated vertex shaders, the helper passes (memu/memu_setup.comp, memu/memu_dist.comp) and the replay
// agree on. Only #define lines with integers: the GLSL helpers include this file too (glslangValidator, GL_GOOGLE_include_directive).
//
// Each emulated draw of a batch has a record of VKA_MEMU_REC_WORDS words in the batch's records buffer (host-visible scratch). The replay fills the "in" words
// (and copies an indirect draw's {count, first} into COUNT / FIRST on the GPU), the setup pass and the distribution pass fill the "out" words; the record's
// dispatch and draw words are the arguments of the draw's vkCmdDispatchIndirect and vkCmdDraw[Indexed]Indirect.
// The arena (one device-local buffer per submission, vka_memu.c) holds every draw's task payloads, task mesh-workgroup counts (then their offsets), vertices and
// primitives; its words are handed out by the counter in the arena's control buffer (word 0; word 1 the most ever asked, for the host to grow the arena).
//
// This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version. This program is distributed in the hope that it will be useful, but
// WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details. You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
#ifndef VKA_MEMU_ABI_H
#define VKA_MEMU_ABI_H

#define VKA_MEMU_REC_WORDS 32
#define VKA_MEMU_R_TASK_X 0       // out: the task dispatch {x, y, z} (words 0-2)
#define VKA_MEMU_R_MESH_X 3       // out: the mesh dispatch {x, y, z} (words 3-5)
#define VKA_MEMU_R_DRAW 6         // out: the draw, indexed {indexCount, instanceCount, firstIndex, vertexOffset, firstInstance}, else {vertexCount, instanceCount, firstVertex, firstInstance}
#define VKA_MEMU_R_COUNT 11       // in: task workgroups, or mesh workgroups without a task (the direct count, or word 0 of the indirect record)
#define VKA_MEMU_R_FIRST 12       // in: the first workgroup (the direct draw's, or word 1 of the indirect record)
#define VKA_MEMU_R_FLAGS 13       // in: VKA_MEMU_F_*
#define VKA_MEMU_R_WT 14          // in: task payload words per task workgroup (0 without a task)
#define VKA_MEMU_R_VSTRIDE 15     // in: vertex region words per mesh workgroup
#define VKA_MEMU_R_PSTRIDE 16     // in: primitive region words per mesh workgroup
#define VKA_MEMU_R_VW 17          // in: words per vertex
#define VKA_MEMU_R_MAXV 18        // in: the mesh program's most vertices
#define VKA_MEMU_R_DRAWV 19       // in: vertices drawn (indices of an indexed draw) per mesh workgroup
#define VKA_MEMU_R_MAXG 20        // in: the most workgroups in one dispatch dimension (the device's maxComputeWorkGroupCount[0], at most 65535)
#define VKA_MEMU_R_NTASK 21       // out: task workgroups dispatched
#define VKA_MEMU_R_PAYLOAD 22     // out: arena word of the task payloads (WT words each)
#define VKA_MEMU_R_COUNTS 23      // out: arena word of the tasks' mesh workgroup counts, which the distribution pass turns into exclusive offsets
#define VKA_MEMU_R_TOTAL 24       // out: mesh workgroups dispatched
#define VKA_MEMU_R_VBASE 25       // out: arena word of the vertex region (a multiple of VW; of VSTRIDE for an interleaved draw)
#define VKA_MEMU_R_PBASE 26       // out: arena word of the primitive region (an indexed draw's index buffer: firstIndex)
#define VKA_MEMU_R_WANT 27        // out: mesh workgroups asked for, before the arena's room cut them

#define VKA_MEMU_F_TASK 1         // a task program runs first (its mesh workgroups found by the distribution pass)
#define VKA_MEMU_F_INDEXED 2      // indexed triangles: the vertex and index regions apart, the indices the arena's vertex numbers (VBASE / VW + workgroup x MAXV + v)
#define VKA_MEMU_F_INTERLEAVED 4  // one slot of VSTRIDE words per mesh workgroup (vertices, then primitives), its base a multiple of the slot: the vertex shader finds it from VertexIndex

#define VKA_MEMU_CTL_COUNTER 0    // control buffer words: the arena words handed out in this submission
#define VKA_MEMU_CTL_ASKED 1      // the most the counter would have reached (the room asked for)

// Set 0 bindings of the converted modules and of the generated vertex shaders: past every stage range the library uses (compute takes range 0, task 1, mesh 0)
#define VKA_MEMU_ARENA_BINDING 382
#define VKA_MEMU_REC_BINDING 383
// The converted modules' push constant: the draw's record index in the records buffer (bytes 0-3)
#define VKA_MEMU_PUSH_BYTES 16

#endif
