// The rest of the Metal side of dm/gfx on Android: what hle_nvn.c, hle_vi.c and the host call in nvn_mtl.m, nvn_capture.inc, hle_vk.m and hle_gl.m that the NVN → Vulkan replay
// (dm/gfx/vk_android) does not implement yet, as stubs that log "[RED] gfx: <name> not yet on Android" once and return what "nothing to report" means (counters 0, no snapshots,
// no CPU texel reads; frame captures are vka_capture.c). The replay took over memory pools, textures and descriptor pools, submissions, fences and the present (android/PORTING.md). The signatures are nvn_mtl.h / nvn_obj.h's
#if defined(__ANDROID__)   // the Mac and iOS builds take every .c here (dm/host/Makefile's wildcard) and have the real nvn_mtl.m
#include "nvn_mtl.h"
#include "nvn_obj.h"
#include "nvn_capture.h"
#include "../../host/dm.h"
#include <stdatomic.h>

// One line per function, the first time it is called
#define RED(name) do { static _Atomic int once_; if (!atomic_exchange(&once_, 1)) LOG("[RED] gfx: %s not yet on Android (NVN → Vulkan replay)", name); } while (0)

void dm_gfx_passdump_next(long n) { (void)n; RED(__func__); }
void dm_gfx_texdump_next(const char *path) { (void)path; RED(__func__); }
void dm_gfx_texlist(int target) { (void)target; RED(__func__); }
void dm_gfx_texraw(uint64_t serial, const char *path) { (void)serial; (void)path; RED(__func__); }
void mtl_ab_gpu(uint64_t f, double gpu_ms, double frame_ms) { (void)f; (void)gpu_ms; (void)frame_ms; RED(__func__); }
void mtl_alloc_stats(uint64_t *a0, uint64_t *a1, int *a2) { (void)a0; (void)a1; (void)a2; if (a0) *a0 = 0; if (a1) *a1 = 0; if (a2) *a2 = 0; RED(__func__); }
void mtl_args_stats(uint64_t *enc, uint64_t *reuse) { (void)enc; (void)reuse; if (enc) *enc = 0; if (reuse) *reuse = 0; RED(__func__); }
void mtl_bw(uint64_t *load, uint64_t *store, uint64_t *full_rw_passes) { (void)load; (void)store; (void)full_rw_passes; if (load) *load = 0; if (store) *store = 0; if (full_rw_passes) *full_rw_passes = 0; RED(__func__); }
uint64_t mtl_ds_omit(void) { RED(__func__); return 0; }
void mtl_mem_stat(unsigned long long a0, unsigned long long a1) { (void)a0; (void)a1; RED(__func__); }
uint64_t mtl_pkg_edge_skips(void) { RED(__func__); return 0; }
uint64_t mtl_pkg_miss(void) { RED(__func__); return 0; }
int mtl_pso_prebuild_tool(void) { RED(__func__); return 0; }
void mtl_read_texels(const tex_t *t, unsigned level, unsigned layer0, const NVNcopyRegion *r, uint8_t *dst, size_t row, size_t img) { (void)t; (void)level; (void)layer0; (void)r; (void)dst; (void)row; (void)img; RED(__func__); }
void mtl_ub_present(double iv_ms) { (void)iv_ms; RED(__func__); }
uint64_t mtl_udc_saved_bytes(void) { RED(__func__); return 0; }
uint64_t mtl_upload_gpu_ns_take(void) { RED(__func__); return 0; }
void mtl_use_stats(uint64_t *call, uint64_t *skip) { (void)call; (void)skip; if (call) *call = 0; if (skip) *skip = 0; RED(__func__); }
void mtl_vbuf_stats(uint64_t *resolve, uint64_t *reuse, uint64_t *set, uint64_t *skip, uint64_t *copies, uint64_t *copy_bytes) { (void)resolve; (void)reuse; (void)set; (void)skip; (void)copies; (void)copy_bytes; if (resolve) *resolve = 0; if (reuse) *reuse = 0; if (set) *set = 0; if (skip) *skip = 0; if (copies) *copies = 0; if (copy_bytes) *copy_bytes = 0; RED(__func__); }
#endif
