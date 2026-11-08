// The prewarm list's file format (vka_pso.c pw_*), free of Vulkan so the Mac's unit test (dm/host/tests/t_android_pwlist.c) checks it and the profiles' seed lists.
// A list is the pipeline keys a run made, each naming its shaders by library key and its state as vka_gkey bytes, so one list serves every driver:
//   pwhdr2 (magic NXVKPW2, sizeof(vka_gkey), record count, vka_pw_layout()), then the records: a pwrec, then klen bytes of vka_gkey padded to 8.
// Kinds: VKA_PW_GFX a vertex pipeline (key[i] = the shader of stage slot i: vertex, fragment, geometry, tess control, tess evaluation), VKA_PW_CS a compute pipeline
// (key[0], klen 0), VKA_PW_MESH a mesh pipeline (key[0] mesh, key[1] fragment, key[2] task or 0; 2026-11-08: before, mesh pipelines were left out, and Ocarina of Time's,
// 2.5-9 s each on Qualcomm's compiler, were never made ahead). A host that does not know a kind skips its records (vka_pw_rec_ok) and drops them at its next save.
// Sources: <cache>.keys, the device's own (written by every run), and the profile's seed (dm/games/<id>/vk_prewarm.bin, compiled into the host as vk_prewarm_seed.h by
// dm/games/game.py seed), which a first start makes ahead before the game reaches its first 3D frame (docs/reference/rendering.md "Pipelines made on the frame path")
#ifndef VKA_PWLIST_H
#define VKA_PWLIST_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "vka_gkey.h"

enum { VKA_PW_GFX = 0, VKA_PW_CS = 1, VKA_PW_MESH = 2 };
typedef struct { uint8_t kind, bad, inst[2]; uint32_t klen; uint64_t key[5]; } pwrec;   // then klen bytes of vka_gkey, padded to 8; bad: in memory only (written 0)
typedef struct { char magic[8]; uint32_t gkey_size, n; } pwhdr;      // NXVKPW1 (read only)
typedef struct { char magic[8]; uint32_t gkey_size, n, layout, pad; } pwhdr2;   // NXVKPW2: layout = vka_pw_layout(), the key's field offsets (a size that stays the same hides a reordering)
static const char VKA_PW_MAGIC[8] = "NXVKPW1", VKA_PW_MAGIC2[8] = "NXVKPW2";

// The keys' hash, 8 bytes a step (vka_pso.c hashes a pipeline key per draw with it)
static inline uint64_t vka_pw_fnv(const void *p, size_t n, uint64_t h) {
    const uint8_t *b = (const uint8_t *)p; size_t i = 0;
    for (; i + 8 <= n; i += 8) { uint64_t w; memcpy(&w, b + i, 8); h = (h ^ w) * 0x9E3779B97F4A7C15ull; h ^= h >> 29; }
    for (; i < n; i++) h = (h ^ b[i]) * 1099511628211ull;
    return h ^ (h >> 32);
}
#define VKA_PW_FNV0 1469598103934665603ull
static inline uint32_t vka_pw_layout(void) {
    static const uint32_t o[] = {offsetof(vka_gkey, dfmt), offsetof(vka_gkey, ncolor), offsetof(vka_gkey, samples), offsetof(vka_gkey, topo), offsetof(vka_gkey, poly_mode),
        offsetof(vka_gkey, fs_variant), offsetof(vka_gkey, alpha_func), offsetof(vka_gkey, gs_fx), offsetof(vka_gkey, vin_u), offsetof(vka_gkey, tex_u), offsetof(vka_gkey, att), offsetof(vka_gkey, vin_static),
        offsetof(vka_gkey, va), offsetof(vka_gkey, vb), sizeof(vka_gkey), 3 /* the format's own version */};
    return (uint32_t)vka_pw_fnv(o, sizeof o, VKA_PW_FNV0);
}
static inline size_t vka_pw_size(const pwrec *r) { return sizeof *r + ((r->klen + 7) & ~(size_t)7); }
// A record's identity: its kind, shaders and key bytes (bad zeroed)
static inline uint64_t vka_pw_hash(const pwrec *r, const void *kb) { pwrec c = *r; c.bad = 0; return vka_pw_fnv(kb, r->klen, vka_pw_fnv(&c, sizeof c, VKA_PW_FNV0)); }

// Vk enum bounds the check uses (vka_pso.c asserts them against the Vulkan headers)
enum { VKA_PW_TOPO_MAX = 10 /* PATCH_LIST */, VKA_PW_POLY_MAX = 2 /* POINT */, VKA_PW_LOGIC_MAX = 15 /* SET */, VKA_PW_BLENDF_MAX = 18 /* ONE_MINUS_SRC1_ALPHA */, VKA_PW_BLENDOP_MAX = 4 /* MAX */,
       VKA_PW_FS_VARIANT_MAX = 4 /* VKA_FS_RETYPE */ };
// A record of a file before it is used: a file from another build, a truncated or damaged one must not reach pstate_fill (an ncolor of 200 indexes past cf[8]). 0 = skipped
static inline int vka_pw_rec_ok(const pwrec *r, const uint8_t *kb) {
    if (r->kind > VKA_PW_MESH || !r->key[0] || r->klen > sizeof(vka_gkey)) return 0;
    if (r->kind == VKA_PW_CS) return r->klen == 0;
    if (r->kind == VKA_PW_MESH && (!r->key[1] || r->key[3] || r->key[4] || r->inst[0] || r->inst[1])) return 0;   // a mesh pipeline: mesh, fragment, task; never instanced
    if (r->klen < offsetof(vka_gkey, va)) return 0;
    vka_gkey k; memset(&k, 0, sizeof k); memcpy(&k, kb, r->klen);
    if (k.ncolor > 8 || k.fs_variant > VKA_PW_FS_VARIANT_MAX || (k.samples != 1 && k.samples != 2 && k.samples != 4 && k.samples != 8)) return 0;
    if (k.topo > VKA_PW_TOPO_MAX || k.poly_mode > VKA_PW_POLY_MAX || k.alpha_func > 8 || k.logic_op > VKA_PW_LOGIC_MAX) return 0;
    if (k.depth_clamp > 1 || k.a2c > 1 || k.logic_en > 1 || k.gl_depth > 1 || k.gs_fx > 1 || k.vin_static > 1) return 0;
    for (int i = 0; i < 8; i++) if (k.att[i].en > 1 || k.att[i].src > VKA_PW_BLENDF_MAX || k.att[i].dst > VKA_PW_BLENDF_MAX || k.att[i].srca > VKA_PW_BLENDF_MAX
                                    || k.att[i].dsta > VKA_PW_BLENDF_MAX || k.att[i].op > VKA_PW_BLENDOP_MAX || k.att[i].opa > VKA_PW_BLENDOP_MAX) return 0;
    if (k.vin_static) { for (int i = 0; i < 32; i++) if (k.va[i].used > 1 || (k.va[i].used && k.va[i].binding > 32)) return 0;
                        for (int i = 0; i < 33; i++) if (k.vb[i].used > 1) return 0; }
    return 1;
}

// A list's header: VKA_PW_OK and where its records start and how many it says it has, or why it is not read
enum { VKA_PW_OK = 0, VKA_PW_SHORT = 1, VKA_PW_FORMAT = 2, VKA_PW_LAYOUT = 3 };
static inline int vka_pw_header(const uint8_t *d, size_t len, size_t *first, uint32_t *n) {
    pwhdr2 h; memset(&h, 0, sizeof h);
    if (!d || len < sizeof(pwhdr)) return VKA_PW_SHORT;
    memcpy(&h, d, len < sizeof h ? sizeof(pwhdr) : sizeof h);
    int v2 = !memcmp(h.magic, VKA_PW_MAGIC2, 8) && len >= sizeof(pwhdr2), v1 = !memcmp(h.magic, VKA_PW_MAGIC, 8);
    if (!v1 && !v2) return VKA_PW_FORMAT;
    if (h.gkey_size != sizeof(vka_gkey) || (v2 && h.layout != vka_pw_layout())) return VKA_PW_LAYOUT;
    *first = v2 ? sizeof(pwhdr2) : sizeof(pwhdr); *n = h.n;
    return VKA_PW_OK;
}
// The next record at *o of a list of len bytes: 1 with *r and *kb (*ok: it passed vka_pw_rec_ok), 0 at the end or at a record whose size runs past the file (the rest is
// not trustworthy and not read)
static inline int vka_pw_next(const uint8_t *d, size_t len, size_t *o, pwrec *r, const uint8_t **kb, int *ok) {
    if (*o + sizeof(pwrec) > len) return 0;
    memcpy(r, d + *o, sizeof *r);
    if (r->klen > sizeof(vka_gkey) || *o + vka_pw_size(r) > len) return 0;
    *kb = d + *o + sizeof *r; *ok = vka_pw_rec_ok(r, *kb); *o += vka_pw_size(r);
    return 1;
}
#endif
