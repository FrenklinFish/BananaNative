// Tegra block-linear ↔ linear. Level / layer offsets and per-level block height and block depth always come from nvn/nvn_tex.c (nvn_tex_level_offset / nvn_tex_layout / nvn_tex_level_tiles).
#pragma once
#include <stddef.h>
#include <stdint.h>
#include "../nvn/nvn_tex.h"

// Decode level number level, layer number layer (layer of an array / cube; 0 for non-layered textures) from the whole-texture storage src_storage into linear dst_linear.
// dst is laid out by block rows: dst_pitch bytes per row (≥ width in blocks of this level × bytes per block); for compressed formats one row = one row of blocks.
// 3D textures: all depth slices of this level are decoded at once; slice k is at dst_linear + k × dst_pitch × block row count of this level.
// Not supported: multisampling, the LINEAR flag (already linear), BUFFER; returns -1 on these, 0 on success.
int nvn_deswizzle(const nvn_texdesc *d, unsigned level, unsigned layer, const void *src_storage, void *dst_linear, size_t dst_pitch);
// The same for block rows [y0, y1) of every slice only, each at its place in dst_linear (several threads can decode one level in parts: the Vulkan replay's uploads)
int nvn_deswizzle_rows(const nvn_texdesc *d, unsigned level, unsigned layer, const void *src_storage, void *dst_linear, size_t dst_pitch, uint32_t y0, uint32_t y1);
// Block rows of a level (one slice), or -1 where nvn_deswizzle refuses the texture
int nvn_deswizzle_block_rows(const nvn_texdesc *d, unsigned level);
// A level's bands (not 3D): band k is block rows [k * band_rows, (k + 1) * band_rows) (one row of GOB blocks), stored in bytes [k * band_bytes, (k + 1) * band_bytes) from the level
// and layer's start, so nvn_deswizzle_rows over a band reads only those bytes (the Vulkan replay uploads a sparse texture's mapped bands only). -1 where nvn_deswizzle refuses it or for 3D
int nvn_deswizzle_bands(const nvn_texdesc *d, unsigned level, uint32_t *band_rows, uint64_t *band_bytes);
// Reverse (for unit test round trips): linear src_linear → written into the corresponding level / layer of the whole-texture storage dst_storage
int nvn_swizzle(const nvn_texdesc *d, unsigned level, unsigned layer, const void *src_linear, size_t src_pitch, void *dst_storage);
// Single-point address: within this level and layer, linear (x byte, y block row, z slice) → block-linear byte offset relative to the start of this level and layer (unit tests verify against hand calculation).
// row_bytes: the row as laid out, which a sparse texture's tiled levels pad to whole tiles (nvn_tex_level_gobs × 64)
// The GOB's inner order: 0 the Tegra X1's (Switch), 1 the Switch 2's, whose 16-byte pieces go x5 y2 x4 y1 y0 from the GOB's start where the X1's go x5 y2 y1 x4 y0 (bits 5 and 6 of the
// offset exchanged). Measured on Donkey Kong Bananza's BNTX textures (BC4, BC7; level 0 decoded both ways): with the X1's order every 16-byte piece kept its GOB but not its place in it,
// which left the rocks' normal maps a weave of misplaced 4x4 blocks along every edge. The NVN layer sets it for games built for the Switch 2 (hle_nvn.c)
extern int nvn_gob_switch2;
uint64_t nvn_bl_offset(uint32_t x, uint32_t y, uint32_t z, uint32_t row_bytes, uint32_t rows, unsigned tileH, unsigned tileD);

int nvn_deswizzle_geom(const nvn_texdesc *d, unsigned level, unsigned layer,
                       uint32_t *row_bytes, uint32_t *rows, uint32_t *slices,
                       uint64_t *base, unsigned *tileH, unsigned *tileD, uint32_t *bpb);
