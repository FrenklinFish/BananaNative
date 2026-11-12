// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors (the distribution pass: pavelgajdo/Hokkaido #569 EmitMeshEmulationDistribution)
// SPDX-FileCopyrightText: Copyright 2026 Banana Native (this port)
// SPDX-License-Identifier: GPL-3.0-or-later
// The mesh emulation's helper passes (memu_setup.comp, memu_dist.comp): the batch's records, the arena's control words and the arena (vka_memu_abi.h). See vka_memu.c.
#include "../vka_memu_abi.h"
layout(std430, binding = 0) buffer Rec { uint r[]; };
layout(std430, binding = 1) buffer Ctl { uint ctl[]; };
layout(std430, binding = 2) buffer Arena { uint a[]; };
layout(push_constant) uniform P { uint first, n, cap, pad; } pc;   // the batch's first record, its records, the arena's words

// words arena words aligned to align, or ~0 when the arena has no room (the counter keeps growing past it: the host reads how much the submission wanted)
uint alloc(uint words, uint align) {
    uint want = words + align - 1u;
    uint b = atomicAdd(ctl[VKA_MEMU_CTL_COUNTER], want);
    atomicMax(ctl[VKA_MEMU_CTL_ASKED], b + want);
    uint o = (b + align - 1u) / align * align;
    return b <= pc.cap && want <= pc.cap - b ? o : 0xffffffffu;
}
// The mesh output of record R for want mesh workgroups: its regions, its mesh dispatch and its draw. Nothing drawn when the arena has no room
void place(uint R, uint want) {
    uint flags = r[R + VKA_MEMU_R_FLAGS], vs = r[R + VKA_MEMU_R_VSTRIDE], ps = r[R + VKA_MEMU_R_PSTRIDE], vw = r[R + VKA_MEMU_R_VW], dv = r[R + VKA_MEMU_R_DRAWV];
    r[R + VKA_MEMU_R_WANT] = want;
    uint per = (flags & VKA_MEMU_F_INTERLEAVED) != 0u ? vs : vs + ps;
    uint total = min(want, pc.cap / max(per, 1u)), vb = 0u, pb = 0u;   // (total x words stays within 32 bits)
    if ((flags & VKA_MEMU_F_INTERLEAVED) != 0u) {
        vb = total != 0u ? alloc(total * vs, vs) : 0u;
        pb = vb + r[R + VKA_MEMU_R_MAXV] * vw;
        if (vb == 0xffffffffu) { total = 0u; vb = pb = 0u; }
    } else {
        vb = total != 0u ? alloc(total * vs, vw) : 0u;
        pb = total != 0u ? alloc(total * ps, 1u) : 0u;
        if (vb == 0xffffffffu || pb == 0xffffffffu) { total = 0u; vb = pb = 0u; }
    }
    uint mx = max(min(r[R + VKA_MEMU_R_MAXG], 65535u), 1u);
    uint x = min(total, mx);
    r[R + VKA_MEMU_R_TOTAL] = total; r[R + VKA_MEMU_R_VBASE] = vb; r[R + VKA_MEMU_R_PBASE] = pb;
    r[R + VKA_MEMU_R_MESH_X] = x; r[R + VKA_MEMU_R_MESH_X + 1] = x != 0u ? (total + x - 1u) / x : 1u; r[R + VKA_MEMU_R_MESH_X + 2] = 1u;
    if ((flags & VKA_MEMU_F_INDEXED) != 0u) {
        r[R + VKA_MEMU_R_DRAW] = total * dv; r[R + VKA_MEMU_R_DRAW + 1] = 1u; r[R + VKA_MEMU_R_DRAW + 2] = pb; r[R + VKA_MEMU_R_DRAW + 3] = 0u; r[R + VKA_MEMU_R_DRAW + 4] = 0u;
    } else {
        r[R + VKA_MEMU_R_DRAW] = total * dv; r[R + VKA_MEMU_R_DRAW + 1] = 1u; r[R + VKA_MEMU_R_DRAW + 2] = vs != 0u ? vb / vs * dv : 0u; r[R + VKA_MEMU_R_DRAW + 3] = 0u;
    }
}
