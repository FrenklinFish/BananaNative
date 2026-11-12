// SPDX-FileCopyrightText: Copyright 2026 Banana Native
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Mesh draws through compute (vka_memu_draw.inc): which entry of a look-ahead's batch a draw being replayed takes. The batch holds the mesh draw records the look-ahead
// met, in walk order, one entry per meeting; the replay then meets them in the same order. One look-ahead can meet the same record more than once: the game calls one
// command list twice in a render pass with another program bound in between (Ocarina of Time draws a model's list for its depth prepass, then again for its colour
// pass). Each meeting has its own shaders and its own mesh output, so a draw takes the next entry of its record from the replay's cursor, which then moves past it; the
// first entry of the record (the old lookup) gave the colour draw the prepass's mesh output and vertex shader (docs/reference/rendering.md "Mesh draws through compute
// matched to the wrong meeting of a record"). Plain C, no Vulkan: unit-tested on the Mac (dm/host/tests t_android_memu).
#ifndef VKA_MEMU_ORDER_H
#define VKA_MEMU_ORDER_H
#include <stddef.h>

enum { VKA_MEMU_ORDER_MAX = 128 };
typedef struct { const void *p[VKA_MEMU_ORDER_MAX]; int n, next; } vka_memu_order;   // the records in walk order; next: the replay's cursor

static inline void vka_memu_order_reset(vka_memu_order *o) { o->n = o->next = 0; }
// A meeting of record p appended: its entry, -1 when the batch is full
static inline int vka_memu_order_add(vka_memu_order *o, const void *p) { if (o->n >= VKA_MEMU_ORDER_MAX) return -1; o->p[o->n] = p; return o->n++; }
// The next entry of record p from the cursor, -1 none (it is not taken)
static inline int vka_memu_order_find(const vka_memu_order *o, const void *p) { for (int i = o->next; i < o->n; i++) if (o->p[i] == p) return i; return -1; }
// The same, taken: the cursor moves past it (entries before it that the replay did not meet are passed over). -1 none, the cursor unchanged
static inline int vka_memu_order_take(vka_memu_order *o, const void *p) { int i = vka_memu_order_find(o, p); if (i >= 0) o->next = i + 1; return i; }
#endif
