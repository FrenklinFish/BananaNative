// The Vulkan shader library reader (android/SHADERS.md "Library format"): one file per game, dmsh_vk.lib, made by dm/gfx/tools/vk_android_build.py. Plain C with no Vulkan,
// so the Mac unit test (dm/host/tests t_android_shlib) runs the same code against a real library. The replay (vka_shader.c) maps the file once and takes modules from it by key
#pragma once
#include <stddef.h>
#include <stdint.h>
#include "../nvn/dmsh_abi_vk.h"

typedef struct {
    const uint8_t *base; size_t size;          // the whole file (mapped, or a caller's buffer)
    const dmsh_vk_header *h;
    const dmsh_vk_entry *ent; const dmsh_vk_bind *bind; const dmsh_vk_module *mod; const uint8_t *blob;
    int mapped;
    int by_abi;   // taken by its abi only: its chain or vk_chain is not the host's (an older library played while its update runs)
} vka_shlib;

// Checks a library image against what the host was built with: magic, version, chain, vk_chain and game (NULL = not checked), then every table and range inside the file and the
// entries' key order. abi (the host's DMSH_VK_ABI; NULL = versions only): a library whose chain or vk_chain differs is still taken when its header's abi is this one (by_abi),
// the rule for an older library while its delta update runs (android/SHADERS.md#delta-updates). 1 = usable; else 0 and err says why (one line)
int vka_shlib_check(vka_shlib *L, const void *base, size_t size, const char *chain, const char *vk_chain, const char *game, const char *abi, char *err, size_t errn);
// Maps path read-only and checks it as above
int vka_shlib_open(vka_shlib *L, const char *path, const char *chain, const char *vk_chain, const char *game, const char *abi, char *err, size_t errn);
void vka_shlib_close(vka_shlib *L);

uint64_t vka_shlib_key(const uint8_t md[8]);   // the host's 8-byte program key (prog_key) as the library's sort key: its 16 hex digits read as one big-endian number
const dmsh_vk_entry *vka_shlib_find(const vka_shlib *L, uint64_t key);   // NULL = not in the library
static inline const dmsh_vk_bind *vka_shlib_binds(const vka_shlib *L, const dmsh_vk_entry *e) { return L->bind + e->bind; }
const dmsh_vk_module *vka_shlib_module(const vka_shlib *L, const dmsh_vk_entry *e, int variant);   // NULL = no module of that variant
static inline const uint8_t *vka_shlib_c1(const vka_shlib *L, const dmsh_vk_entry *e) { return e->c1_len ? L->blob + e->c1_off : NULL; }
// The module's SPIR-V words, decompressed when stored with zstd: malloc'd (the caller frees), *nbytes its size. NULL with err on a bad module
uint32_t *vka_shlib_spirv(const vka_shlib *L, const dmsh_vk_module *m, size_t *nbytes, char *err, size_t errn);
// A header string (chain, vk_chain, game, inputs: 16 bytes NUL padded) as a C string
void vka_shlib_str(const char field[16], char out[17]);
