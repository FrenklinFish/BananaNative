// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors (the compute mesh emulation: pavelgajdo/Hokkaido #569, #642)
// SPDX-FileCopyrightText: Copyright 2026 Banana Native (this port)
// SPDX-License-Identifier: GPL-3.0-or-later
// NVN2 task and mesh draws through compute (vka_memu.c, the draws in vka_draw.c's memu batch, the look-ahead in vka_exec.c memu_scan). See vka_memu_abi.h for the licence.
#pragma once
#include <stdint.h>
#include <vulkan/vulkan_core.h>
#include "vka_memu_spv.h"
#include "../nvn/dmsh_abi_vk.h"
struct vka_shader;
// A task or mesh shader's emulation: its layout and, made at first use, its compute forms (by GL depth) and the vertex shader of its output
typedef struct vka_memu_sh {
    int state;                       // 1 usable, -1 refused (logged once)
    vka_memu_layout L;
    uint32_t nsb;                    // its storage buffer descriptors (with the emulation's two: within maxPerStageDescriptorStorageBuffers)
    dmsh_vk_entry ce, ve;            // the entries of its compute form (the stage compute) and of its vertex shader (no binding)
    struct vka_shader *cs[2], *vs;
} vka_memu_sh;
#define VKA_MEMU_KEY_CS 0x4d454d555f43535full   // a compute form's key: the shader's key XOR this (XOR VKA_MEMU_KEY_GL for GL depth): no library key
#define VKA_MEMU_KEY_GL 0x00000000474c3130ull
#define VKA_MEMU_KEY_VS 0x4d454d555f56535full
vka_memu_sh *vka_memu_of(struct vka_shader *s);                 // NULL: s cannot be drawn through compute (logged)
struct vka_shader *vka_memu_cs_of(struct vka_shader *s, int gl); // its compute form, NULL = not made (logged)
struct vka_shader *vka_memu_vs_of(struct vka_shader *s);         // its vertex shader (a mesh shader's), NULL = not made
struct vka_shader *vka_memu_by_key(uint64_t key);                // a compute form or vertex shader by its key (the prewarm list), NULL = none
// 0 when the generated vertex shader vs (vka_memu_vs_of) leaves an input of pixel shader fs unwritten, a pipeline Vulkan does not allow (VUID-RuntimeSpirv-OpEntryPoint-08743;
// Qualcomm's driver answers VK_ERROR_UNKNOWN): no mesh draw pairs them, so a prewarm record of the pair is stale (vka_pso.c). 1 otherwise, or when it cannot tell
int vka_memu_vs_feeds(struct vka_shader *vs, struct vka_shader *fs);
// The arena of the submission being recorded (acquired at its first emulated draw: its control words cleared in cb), the records of a batch, the helper passes
int vka_memu_arena(VkCommandBuffer cb, VkBuffer *buf, VkBuffer *ctl, uint32_t *cap_words);   // 0 = none (logged)
void vka_memu_retire(uint64_t fence);                            // the arena used since the last call belongs to the submission of fence
int vka_memu_helpers(VkPipeline *setup, VkPipeline *dist, VkPipelineLayout *layout);   // the setup and distribution pipelines, their layout (push descriptors: records, control, arena)
void vka_memu_stats(char *b, size_t n);                          // the replay's statistics line part
extern uint64_t vka_memu_n_batches, vka_memu_n_draws, vka_memu_n_splits, vka_memu_n_skip;
