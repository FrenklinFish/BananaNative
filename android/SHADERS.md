# Shaders on Android: SPIR-V for Vulkan

The Android port runs the game's shaders as SPIR-V on Vulkan 1.3 (first device: Adreno 840, [PORTING.md](PORTING.md)). They are translated offline, as on Apple platforms: the same translator makes the SPIR-V, and the Metal half of the chain (SPIRV-Cross, the MSL rewrites, the Metal compiler) is left out. This page has the build, what was kept of the Apple chain, the descriptor ABI the Vulkan replay (`dm/gfx/vk_android`) binds against, the library format, the results on Donkey Kong Bananza and the open problems.

## Status (2026-11-02)

- **Works:** `DM_GAME=<id> python3 dm/gfx/tools/vk_android_build.py --target vulkan <package dir> <output cache dir>` translates a game's shaders to SPIR-V and writes one library (`v-<DMSH_VK_CHAIN>/dmsh_vk.lib`, zstd modules) with the descriptor ABI of `dm/gfx/nvn/dmsh_abi_vk.h`. `--check <lib>` reads it back as the replay would and runs spirv-val on every module.
- **Validated:** Donkey Kong Bananza (040074A01BF12000, update 458752), library `v-9b3a2294056a` (chain `725d300e1fae`, inputs `5dd843bed8d1`): 22,852 of 22,852 shaders translated with the Android answers, 24,034 modules (22,852 base, 1,149 `MOD_UINT`, 33 `MOD_UINT_MIXED`), every one valid for `spirv-val --target-env vulkan1.3` at build time and after `--check`. 364.5 MB library (1.82 GB of SPIR-V). No NVIDIA capability or extension left. The Apple translations are byte-identical with the answers unset ([Translator answers](#translator-answers)); `DMSH_CHAIN` moved to `725d300e1fae` because `Program.cs` changed. Nothing has gone through Adreno's compiler yet. The replay's own self-test passed on the phone (branch android-vk), before draws.
- **Packed:** `scripts/pack_android.py` puts the library into `<pack>/shaders/v-<DMSH_VK_CHAIN>/` ([README](README.md#pack-a-game)).
- **Replay:** the loader, binder, variants, alpha test, pipelines, draws and dispatches are in `dm/gfx/vk_android` ([What the replay does](#what-the-replay-does)). The graphics selftest draws (with the alpha test) and dispatches through them with its own shaders and passes on the Android emulator; `t_android_shlib` walks all of this library's entries on the Mac and decompresses its variants. No DKB frame drawn yet, and no DKB module through Adreno's compiler.
- **Upstream translator (2026-11-03, branch android-xlate2, library version `0b077f88b2c8`, chain `513b0310a6c9`):** upstream nxnative's Ampere decoder / ALU / memory changes (c8521eeb, 0a759de7, 03d92a9f, 3fe755a2 LDSM stride), the TXQ type fix (d145b956) and the split readers, with this fork's warp-segment fix (8fac22d9) kept. DKB: 22,852 of 22,852 translated before and after, every module valid; 2,322 entries have a different module (ps 2,124, vs 168, cs 30). The ps / vs ones are control flow only (GotoElimination: loop wrappers and duplicated returns gone); the cs ones are agl's 24 upscaler network shaders (`agl_ndsr.pck`, shared memory addresses by their stride, fewer `FAbs`) and 6 others (`agl_technique_pfx`, `agl_common`, `SilverStarPass`). Not byte-identical, so no `vk_android_same.tsv` row: phones rebuild their library. The DKB split with the new `shader_split.py` gives the profile's four tables byte for byte. Mario Kart World 1.8.0 (profile of branch android-mkw2): 11,568 of 11,604 translate (11,524 before; failures 80 → 36: cs 25 → 2, ps 29 → 8, vs 13 and all 13 domain shaders unchanged), 471 common entries with a different module (ps 320, cs 85, vs 66), every module valid. The figures were measured on the intermediate version `f2299a2fa69f`; adding upstream's `DMSH_C0_DISPATCH_NVN2` to `dmsh_abi.h` (NVN2 compute's workgroup counts at c0 + 4, which the replay now fills) moved the chain to `513b0310a6c9` and the version to `0b077f88b2c8` with the same modules (only the 24 header bytes that carry the versions differ). Library SHA-256: DKB `de76d0eee01e4502bf2a8e7aae67a4e74a2c18960cf4042756251fa0ad833c45`, MKW 1.8.0 `69b27201866d1a0b269775a8fa38116335d7258a4938796e7e1d71e53e5e4c53`.
- **Build cache, resumable (2026-11-03, library version still `e38a586a8a89`):** one result file per shader, a killed build resumes, progress lines; the translator and the validator can be shared libraries called through `ctypes` (the phone's form, [ONDEVICE.md](ONDEVICE.md)). A full DKB build with it equals `v-e38a586a8a89` byte for byte, and so does a build resumed after a third of its results were deleted, so the version did not move ([Versions](#versions), `vk_android_same.tsv`).
- **Not done:** `SINT`-attachment and transform-feedback variants. Integer vertex formats into float inputs are a rewrite at pipeline time, not a library variant ([Stage interface](#stage-interface)).
- **Next steps:** (1) the graphics selftest on the phone (Adreno's compiler, push descriptors, vertex input dynamic state, null descriptors: the emulator has none of them); (2) a DKB run with `DM_VK_SHLIB_CHECK=1` (every module and compute pipeline through Adreno's compiler); (3) DKB's title frame.

## Build

```sh
DM_GAME=040074A01BF12000 python3 dm/gfx/tools/vk_android_build.py --target vulkan <package dir> <output cache dir> [key...]
python3 dm/gfx/tools/vk_android_build.py --check <output cache dir>/v-<V>/dmsh_vk.lib    # read it back, spirv-val every module
```

- The package dir is the one `dmsh_build.py` takes (`shader_split.py`'s output); the profile's tables give the keys, pairs and answers. Each shader is read from the record the map names once that record's hash is its key; the map's numbering is one split's (a split of the base, update and DLC RomFS together numbers them otherwise), so a record that is not the key's is looked up by key in the package's files of that stage, and a key the package lacks stops the build before any translation ([A shader package of another split](../docs/reference/shaders.md#a-shader-package-of-another-split-gives-shaders-other-shaders-code)).
- Needs: Python 3.14 (`compression.zstd`; or `backports.zstd`), the .NET 10 runtime for the vendored translator, `spirv-val` (SPIRV-Tools: `brew install spirv-tools` or `micromamba install -c conda-forge spirv-tools`). No Xcode.
- Output in `<output cache dir>/v-<DMSH_VK_CHAIN>/`: `dmsh_vk.lib` (the library), `index.json` (the same reflection as JSON, for tools), `inputs.tsv` (the stamp: game and profile inputs, as the Apple libraries carry), `_vk_build.json` (each key's status). `DMSH_VK_LOOSE=1` also writes each module as `spv/<key>.spv`.
- **Results and resuming.** Each shader's result (its modules after zstd, its reflection, the input locations a pixel shader reads) is one file `<results>/<key>.r` (default `v-<V>/results/`, `DMSH_VK_RESULTS` elsewhere), written to `.tmp` and renamed, stamped with the tools digest (below), the profile inputs digest, the codec setting (`DMSH_VK_RAW`) and the key. A run skips every key with a valid result and translates the rest in batches (`DMSH_VK_BATCH`, default 64 shaders a translator call, `J` batches at a time): pixel and other stages first, then the vertex shaders, whose padding reads their pixel shaders' inputs from the result files. The library, `index.json` and `_vk_build.json` are always written from the result files (the reflection goes through JSON once, as `index.json` writes it). Failed shaders are results too (deterministic); a translator that dies (exit code other than 0 / 1) leaves its batch without results, so a rerun redoes it. A killed run leaves at most its unfinished batches (`.tmp` files and `results/.work/`, removed by the next run). The results stay as the build cache of that version (434 MB for DKB); `DMSH_VK_KEEP=0` deletes them once the library is written.
- **Progress:** `progress <done> <total> <stage>` lines on stdout (`translate` after every batch, then `library`, `done`), and the same as JSON in the file `DMSH_VK_PROGRESS` names (replaced by rename). The total is the shader count of the profile's `shader_map.tsv` (or of the keys given), known before any translation; `done` counts the result files, so it survives a restart.
- **Tools in process:** `XLATE` or `SPIRV_VAL` naming a `.so` / `.dylib` loads it with `ctypes` instead of starting a program: the translator's library form (`dm/gfx/tools/dmsh_xlate_android`, `dmsh_xlate_main`; its embedded source digest is checked against the lock, there are no dlls to fingerprint) and SPIRV-Tools' `libSPIRV-Tools-shared` (`spvValidateWithOptions` with spirv-val's default options in the `vulkan1.3` environment; the same first error line as spirv-val). `ctypes` drops the GIL for both, as does `compression.zstd`. On the Mac: `XLATE=<…>/libdmsh_xlate.dylib` (`dotnet publish dmsh_xlate_android.csproj -r osx-arm64 -p:NativeLib=Shared -p:OutputType=Library`) and `SPIRV_VAL=/opt/homebrew/lib/libSPIRV-Tools-shared.dylib`.
- Donkey Kong Bananza on the Mac (2026-11-03): 8 min 16 s with `J=12` from nothing (1,013 s user, 310 s system; it was 10-15 min before the batches); a rerun with every result present writes the library in 8 s. A build killed (SIGKILL) at 3,734 shaders and resumed with `J=6` and the in-process tools (Mac dylibs) translated the other 19,118 in 4 min 48 s and wrote the same library, `index.json` and `_vk_build.json` byte for byte.

### Versions

`DMSH_VK_CHAIN` (`dm/gfx/nvn/dmsh_chain_vk.h`) is the library version: the hash of `DMSH_CHAIN`, `vk_android_build.py`, `vk_android_spv.py` and `dmsh_abi_vk.h` (the tools digest, `vk_android_build.py --digest`), or the earlier version that `dm/gfx/tools/vk_android_same.tsv` names for that digest. After changing any of the files, or after `DMSH_CHAIN` changes, run `vk_android_build.py --header` and commit the header (`dm/tools/tests/test_vk_android_abi.py` fails on a stale one).

A change that cannot change the libraries (a cache, progress, how a tool is called) need not move the version and make every library already on a phone stale: build the full library of a game with the new tools, compare `dmsh_vk.lib` (and `index.json`, `_vk_build.json`) with the current version's byte for byte, and only when they are equal add the row `<new digest>\t<current version>\t<what changed and the evidence>` to `vk_android_same.tsv` (then `--header` leaves the header as it is). A change of output never gets a row. The result files are stamped with the digest, so the build cache starts over either way. The library header records both versions; the replay loads only a library whose `chain` and `vk_chain` are the ones it was compiled with.

The Vulkan tools are not among `DMSH_CHAIN`'s files, so adding or changing them does not move the Apple libraries: they stay byte-identical, in the same `b-<V>/`. The translator is the vendored `dmsh_xlate_bin` checked against `dmsh_chain.lock` (only its half of the lock: the Metal compilers are not needed). The build reads control sections and makes the translator's jobs with `dmsh_build.py`'s own functions (`job_for`, `vertex_ab`, `tf_layout`, `decode_tex` and the other handle decoders), taken from that file's source by name, so they stay one copy.

## The Apple chain on Vulkan

| Step (Apple) | Vulkan | Why |
|---|---|---|
| `shader_split.py`: containers, keys, pairs, answer table | kept | the keys are the host's |
| `dmsh_build.py` `job_for`: NVN / NVN2 control sections, c1, VertexA + B merge, compute header, transform feedback layout, early fragment tests | kept, the same functions | |
| `dmsh_xlate` (Ryujinx, `TargetApi.Vulkan`, SPIR-V 1.3) | kept, the same binary and jobs, plus three device answers | [Translator answers](#translator-answers) |
| FP64 retry with `softfloat64` (`Program.cs`) | kept | the phone has no `shaderFloat64`; DKB declares no double |
| `int_inputs`: vertex inputs used only as integer bits translated again as `uint` | kept, read from the SPIR-V (`integer_only_inputs`) | a vertex input's numeric class must match its format |
| `precise`: NaN results of min / max / clamp | kept as `FMin` / `FMax` / `FClamp` → `NMin` / `NMax` / `NClamp` | GLSL.std.450 leaves the F forms' NaN result to the device |
| `vs_init`, `vs_pad`: outputs start (0, 0, 0, 1); outputs the paired pixel shaders read | kept, in SPIR-V (`init_outputs`) | an unmatched pixel input is undefined in Vulkan too |
| `vs_depth`: two entry points for the depth convention | changed: one module, a bool specialization constant | see [Depth and Y](#depth-and-y) |
| SPIRV-Cross, `pool_arrays`, `border_emu`, `hmma_msl`, `dmsh_atomic` MSL checks, `ios_metal.py`, the Metal compiler and its caches | skipped | Metal only. Pools are a real descriptor set; Vulkan has custom border colours (`VK_EXT_custom_border_color`) |
| `spv_single_lane` (subgroups in vertex functions) | skipped | Vulkan has subgroup operations in every stage the device lists in `subgroupSupportedStages` |
| Geometry lowering (`dmsh_geometry.py` `_bg` / `_bl` / `_bgv`, `dmsh_mesh.py` `_m3`), `gs_slice`, `vs_layer`, `vs_layer_entry` | skipped | the phone has geometry and tessellation stages: a geometry shader is translated as one (`gs` modules) |
| `vs_discard` | skipped | rasterizer discard keeps the vertex stage's side effects |
| `vs_base_instance` (the base instance from Metal) | not yet | the replay writes `c0 + 0x644` per draw; DKB has no shader that reads it |
| `vs_tf` (transform feedback by stores) | not yet | flag `F_TF`; the phone has `VK_EXT_transform_feedback`, which wants `Xfb` decorations; DKB has none |
| `fs_uint` (`main0_u`, `main0_m`), `fs_dual` (`main0_d`) | kept, as module variants `MOD_UINT`, `MOD_UINT_MIXED`, `MOD_DUAL` | [Variants](#variants) |
| `dmsh_alpha` (`_ba`) | changed: a uint specialization constant in the base module, the reference in the support buffer | [Variants](#variants) |
| `dmsh_vin` (`_bv`) | changed: integer formats into float inputs are rewritten by the replay (`vka_spvpatch.c`); float formats into integer inputs not yet (no DKB input is integer) | Maxwell hands an integer attribute's bits to the register the shader bit-casts |
| `dmsh_fetch` (`_bf`), `dmsh_pull` (`_bp`), `dmsh_logic` (`_bl`), `raster_facts`, the TBDR passes (`tbdr_*.py`) | skipped | Metal tile memory, vertex pulling and logic operations; Vulkan has vertex input dynamic state and `logicOp`. The TBDR passes are Mac pass analyses, off by default |
| `c1_guard.py`, `cb_extent.py` profile tables | not made | they come from the Mac's MSL; a replay that defers uniform updates as `nvn_mtl.m` does can read the same tables from a Mac build of the same chain |

Capabilities the translator declares in every module whatever it uses are dropped where nothing uses them (`strip_unused_caps`): `Float64`, the fragment shader interlock capabilities with their extension, and the subgroup capabilities. Storage images declared without a format get `StorageImageRead/WriteWithoutFormat` when they are read or written (the translator leaves them out).

### Translator answers

The translator asks the host a few questions whose Apple answers are wrong on the phone. `vk_android_build.py` puts the phone's answers into every job (`ANSWERS`), as job keys `Program.cs` reads; the Apple jobs (`dmsh_build.py`) carry none of them, so `Program.cs` gives Ryujinx's defaults there and the Apple translations stay byte-identical (checked on 387 DKB keys of every stage: the `.spv` and `.res.json` of the translator before and after the change are the same bytes). `DMSH_CHAIN` moved with `Program.cs` (`725d300e1fae`), so the Apple libraries have a new version with the same contents.

| Job key | Ryujinx query | Apple (unset) | Android | Effect |
|---|---|---|---|---|
| `subgroup_size` | `QueryHostSubgroupSize` | 32 | 64 (`DMSH_VK_SUBGROUP_SIZE`) | Maxwell code assumes 32-lane warps. With 64, ballots read the 32-bit half of the lane (`laneId & 32`), the lane masks likewise, votes go through ballots, shuffles stay inside the lane's 32-lane segment (`InstEmitWarp.cs`, `HelperFunctionManager`). Right on 32- and 64-lane devices |
| `ssbo_align` | `QueryHasUnalignedStorageBuffer`, `QueryHostStorageBufferOffsetAlignment` | false, 16 | true, 64 (`DMSH_VK_SSBO_ALIGN`) | every access computes its offset from the base address rounded down to 64 (`GlobalToStorage`): the replay binds `SBUF`, `SBUF_UBO` and `UBO_PTR` buffers at `addr & ~63` with the range grown by `addr & 63` |
| `gs_passthrough` | `QueryHostSupportsGeometryShaderPassthrough` | true (false only in Metal's compute lowering) | false | a passthrough geometry shader writes its vertices itself (`EmitterContext`); no `SPV_NV_geometry_shader_passthrough` |
| `shared_max` | `QueryHostComputeSharedMemoryMax` | 0 (no limit) | 32768 (`DMSH_VK_SHARED_MAX`) | a compute program whose shared memory (with the HMMA scratch) is over it keeps its shared memory in a storage buffer, a slice per workgroup (`SHARED_SPILL`, [Compute programs over the device's shared memory](../docs/reference/shaders.md#compute-programs-over-the-devices-shared-memory)); the entry's `shared` is then what stays in workgroup memory |

The emulated passthrough keeps NVN's viewport mask for one use: it drops a primitive whose mask is 0 (the culling geometry shaders of DKB's terrain do this). Adreno has no `SPV_NV_viewport_array2`, so the build turns the `ViewportMaskNV` output into a `Private` variable (`private_viewport_mask`, entry flag `F_VIEWPORT_CULL`): the cull stays, the routing of a primitive to the viewports its mask names is lost (no difference in a single-viewport draw). The library header records `subgroup_size` and `ssbo_align`; a library made before them has 0 in both (32 lanes, exact binding).

A note for the Apple side, not changed here: Ryujinx's multi-target storage path (`TryGenerateMultiTargetStorageOp`, an address traced to several possible buffers) always rounds the base down to the alignment answer (16), while the inline path does so only when `QueryHasUnalignedStorageBuffer` is true, and `nvn_mtl.m` binds at the exact address. A multi-target access to a storage buffer whose address is not 16-aligned reads 1–15 bytes off on Metal. The Android answers make both paths round to 64.

### Variants

A pixel shader's entry can hold several modules (`dmsh_vk_module.variant`, base first); the replay picks one by the pipeline's state:

| Variant | Which shaders | Module | Pick it when |
|---|---|---|---|
| `MOD_BASE` 0 | all | as translated | otherwise |
| `MOD_UINT` 1 | `F_INT_OUT` (an output written from integer bits) or a shader that writes only constants (a clear into an integer target): `fs_uint`'s rule | every float colour output retyped to the `uint` vector of its bits (`OpBitcast` at each store, back at each load) | every colour attachment is a `UINT` format |
| `MOD_UINT_MIXED` 2 | the above when it also writes float outputs | only the outputs written from integer bits retyped; entry byte `uint_mask` names them | the `UINT` attachments are exactly `uint_mask` |
| `MOD_DUAL` 3 | the profile's `dual_want.tsv` shaders that write exactly colours 0 and 1 (`fs_dual`; DKB has no table, so none) | output 1 at `Location 0, Index 1` | target 0 blends with a `SRC1` factor (`dualSrcBlend`) |

A pipeline with `SINT` attachments has no variant yet (neither has Metal). The alpha test is not a variant: every pixel shader whose colour 0 is a float `vec4` carries a uint specialization constant `SpecId 1` (`DMSH_VK_SPEC_ALPHA_FUNC`, entry flag `F_ALPHA_TEST`, in every variant): `NVN_ALPHA_FUNC_*` (1 never … 7 gequal, 8 always), default 0 = no test, which the driver folds away. The comparison of colour 0's alpha with the reference runs before every return and discards on failure. The reference is per draw: the float bits of word 0 of the fragment stage's `SUPPORT` buffer (Ryujinx's `alpha_test` field, which no translated shader reads), so a reference that changes every draw keeps the pipeline, as `dmsh_alpha.py` does on Metal with its buffer.

## Descriptor ABI

`dm/gfx/nvn/dmsh_abi_vk.h` holds the numbers; `dm/gfx/nvn/dmsh_abi.h` still defines the driver segments (`c0`, `c2` tables) and what each kind means. Every pipeline has the same layout shape:

| Set | Binding | Holds |
|---|---|---|
| 0 | stage × 64 + n | the per-draw resources of each stage: n in the translator's order. Stages numbered as NVN does: vertex 0, tess control 1, tess evaluation 2, geometry 3, fragment 4. A compute pipeline uses range 0 |
| 1 | 0 | `SAMPLED_IMAGE[2^20]`: the NVN texture pool by texture ID. Update-after-bind, partially bound |
| 1 | 1 | `SAMPLER[4096]`: the NVN sampler pool by sampler ID |

No push constants: NVN2's pushed constants (`c[24]`, 256 bytes) are a uniform buffer like the driver segments.

Each shader's binding records (`dmsh_vk_bind`) give the kind, the Vulkan descriptor type, the binding and count, and the NVN slot to fill it from:

| Kind | Descriptor | Fill from |
|---|---|---|
| `CBUF` reg | `UNIFORM_BUFFER` | uniform slot reg (`c[3 + reg]`): the bound address and size. Declared as `vec4[4096]`: bind the game's size, at most 64 KiB |
| `C1` | `UNIFORM_BUFFER` | the entry's c1 bytes (`c1_off` / `c1_len` in the blob), a static buffer per shader |
| `DRVCB` reg 0 / 2 | `UNIFORM_BUFFER` | a `DMSH_DRV_SIZE` buffer filled as `nvn_mtl.m` fills `NVN_RB_DRV0` / `DRV2`: the stage's storage buffer table, the c2 handle tables, the words of `dmsh_abi.h` (dispatch size, point size 2047, sample table, base instance). `index.json` `drv_reads` lists the offsets each shader reads |
| `DRVCB` reg 24 | `UNIFORM_BUFFER` | the stage group's `DMSH_PUSH_SIZE` pushed bytes (`NVN_RB_PUSH`) |
| `SUPPORT` | `UNIFORM_BUFFER` | Ryujinx's support buffer: zeros, then `render_scale` floats at 192 + 16 i set to 1.0 (`support_buf` in `nvn_mtl.m`); in the fragment stage word 0 holds the alpha test reference (float bits) |
| `SBUF` reg | `STORAGE_BUFFER` | storage slot reg: address and size from the stage's `c0` table, bound at the address rounded down to the header's `ssbo_align` (64), the range grown by the difference. Flag `RW` when written |
| `SBUF_UBO` reg, off | `STORAGE_BUFFER` | the address the game keeps in uniform slot reg at byte off, bound as `SBUF` |
| `UBO_PTR` reg | `STORAGE_BUFFER` | compute uniform slot reg, through its `c0` entry (`DMSH_C0_CS_UBO`), bound as `SBUF` |
| `TEX` reg + `skind` | `COMBINED_IMAGE_SAMPLER` (or `SAMPLED_IMAGE`) | the texture ID of slot reg's handle (combined slots `0x20 + 8 r`, separate textures 128 + r) and the sampler the sampler half names: `SAMP_TEX` the same handle's sampler ID, `SAMP` separate sampler slot sreg, `SAMP_ID` the constant sampler ID sreg, `SAMP_UBO` the handle in uniform slot sreg at byte soff |
| `TEX_UBO` reg, off | `COMBINED_IMAGE_SAMPLER` | the 32-bit handle in uniform slot reg at byte off (low 20 bits texture ID, high 12 sampler ID), sampler as above |
| `TBUF` reg | `UNIFORM_TEXEL_BUFFER` | the buffer texture in slot reg. Flag `FETCH1D` on a `TEX`: a 1D integer fetch the translator made a 2D read of 16384-texel rows (`tbuf_as_2d`) |
| `IMG` reg | `STORAGE_IMAGE` / `STORAGE_TEXEL_BUFFER` | image slot reg (`c2 0x120 + 8 r`). Flags `RW`, `ATOMIC` (R32Uint / R32Sint only) |
| `TEX_C2A` / `SAMP_C2A` off | arrays, count elements | element k from the c2 handle at byte off + 8 k; `soff` a sampler word ORed into every element |
| `TEX_POOL` / `SAMP_POOL` | set 1 | nothing per draw: the pools |
| `GMEM_MAP` | `STORAGE_BUFFER` | the pool table for raw global memory (`DMSH_GMEM_MAP_*`), delta = buffer device address − guest address: the shader goes through `PhysicalStorageBuffer` pointers, so the device needs `bufferDeviceAddress` |
| `GMEM_LOG` | `STORAGE_BUFFER` | the write log of those stores, cleared per draw or dispatch |
| `SHARED_SPILL` | `STORAGE_BUFFER` | a compute program's shared memory past `shared_max`: the record's `bytes` per workgroup slice, slices for the dispatch's workgroups (or as many as the replay's spill buffer holds, the dispatch then made in parts) |
| `UNKNOWN` | | the replay refuses the shader (none on DKB) |

`dim` gives the image shape (SPIR-V `Dim`, array, multisample, depth compare, sampled type) for a null or placeholder descriptor of the right type. The aliased pool binding wants the descriptor at texture ID i to be a view of the type every shader samples it with; the same ID read as two view types needs a second set of views (none seen yet: DKB reads no pool).

### Depth and Y

- **Depth.** The game sets NVN's depth mode at run time. Modules that write a position (vertex, tess evaluation, geometry) carry the bool specialization constant `SpecId 0` (`DMSH_VK_SPEC_GL_DEPTH`, entry flag `F_GL_DEPTH`): false leaves z as translated (near = 0), true writes z' = fma(z, 0.5, w × 0.5) (near = −w, NVN's default), the arithmetic of the Apple `main0` entry point. Set it on the last pre-raster stage only (a vertex shader that feeds a geometry shader keeps false), or keep false everywhere and use `VK_EXT_depth_clip_control` with `negativeOneToOne` where the device has it.
- **Y.** The translator writes clip space for Metal: y up, window origin upper-left (`QueryGraphicsState`). Vulkan's y points down, so the replay sets each NVN viewport with a negative height (y + h, −h), which every Vulkan 1.1 device takes; `FragCoord` stays upper-left as on Metal.

### Stage interface

- Vertex inputs: `Location` = NVN attribute index, all `vec4`; `vin_mask` / `vin_types` (2 bits a location: float, sint, uint) give the class the vertex input format must have (`VK_EXT_vertex_input_dynamic_state` takes the formats per draw). A float input fed a `UINT` / `SINT` format (DKB: RGBA8UI, RG8UI, R8UI, RG32UI) keeps that format, and the pipeline's vertex module is the base one rewritten (`vka_spv_vin_retype`, keyed by the locations in `vka_gkey.vin_u` / `vin_s`): the input becomes a `uvec4` / `ivec4` and every load of it (or of a component through an access chain) is bit-cast back to float, so the shader sees the integer bits Maxwell's fetch puts in the register. A USCALED stand-in gave the bits of the converted float (5 → `0x40a00000`). `t_android_spvpatch` checks the rewrite with spirv-val (`DM_VK_SPV_DIR=<folder of .spv>`: 400 DKB vertex modules, all rewritten and valid). 32-bit `*_I2F` formats into float inputs still bind a `UINT` format (logged).
- Colour outputs: `Location` = render target; `fout_mask` / `fout_types` (the base module's). `F_INT_OUT`: an output written from integer bits through a float output, which an integer attachment does not take: take the `MOD_UINT` or `MOD_UINT_MIXED` module ([Variants](#variants)).
- Compute: the workgroup size is in the module (`LocalSize`) and in `local`; shared memory is declared in the module, except a `SHARED_SPILL` program's, which is in its storage buffer.
- Entry point `main` in every module; `F_EARLY_Z` modules have `EarlyFragmentTests`.

## Library format

One file, `dmsh_vk.lib`, little-endian (structs in `dmsh_abi_vk.h`):

| Part | Size | |
|---|---|---|
| `dmsh_vk_header` | 128 | magic `MSVK`, version 1, `chain`, `vk_chain`, `game`, `inputs` (16 bytes each, NUL padded), then counts and offsets of the three tables and the blob, `subgroup_size` and `ssbo_align` (the translator answers; 0 in older libraries) |
| `dmsh_vk_entry` × n | 64 each | sorted by `key` (the 16-hex shader key as a big-endian number): binary search with the host's program key. Stage, flags, first module and count, first binding and count, compute size, c1 bytes, vertex input and colour output types, `uint_mask` (the `MOD_UINT_MIXED` module's integer targets) |
| `dmsh_vk_bind` | 24 each | the records above |
| `dmsh_vk_module` | 16 each | offset in the blob, SPIR-V size, stored size, variant (`MOD_*`, the base first), codec: `RAW` or `ZSTD` (one frame; the replay links zstd's decompressor) |
| blob | | from `blob_off`, 16-byte aligned pieces: the modules and the c1 bytes |

The format stays version 1: the header and entry fields above came out of reserved bytes (zero in an older library, which means 32 lanes, exact storage buffer binding, no mixed module), and an older library's entries have only the base module. A new variant is another module record of the same entry, with the next `DMSH_VK_MOD_*` number.

## What the replay does

In `dm/gfx/vk_android` (branch `android-draws`): `vka_shlib.c` (reader), `vka_bind.c` (binding walker), `vka_shader.c` (library, modules, programs), `vka_pso.c` (layouts, pipelines, pipeline cache), `vka_draw.c` (draw state, render pass, descriptors, draws and dispatches). [PORTING.md](PORTING.md#the-nvn--vulkan-replay) has the replay as a whole.

1. **Library.** At the first program a draw resolves, map `<pack folder>/shaders/v-<DMSH_VK_CHAIN>/dmsh_vk.lib` (the folder the modules came from; `DM_VK_SHLIB=<file>` overrides). Refused with one `[RED] vk: shader library … refused: …` line unless `magic`, `version`, `chain`, `vk_chain` and `game` (the profile's `DM_GAME_ID`) match, or when the device's `minStorageBufferOffsetAlignment` does not divide the header's `ssbo_align`; every table and range is checked once at open. A device whose subgroups are not 32 or 64 lanes (for `subgroup_size` 64), or vary, is logged. Without a library the game runs on and draws nothing.
2. **Programs.** A program (by its serial, which `nvnProgramSetShaders` renews) resolves each stage key (`prog_key`, read as a big-endian number) by binary search; the entry's `stage` says which NVN stage it is. A shader is made once per key: the base module decompressed and `vkCreateShaderModule`, its c1 bytes copied into a uniform buffer kept for the process; variant modules are made when a pipeline first asks for them. Refused once per key with a `[RED]` line, their draws skipped: NV-only capabilities (none since the answers), interlock, a geometry or tessellation stage the device lacks, an `UNKNOWN` binding. NVN binds programs per stage, so each stage takes its shader from the program last bound to it (`nvn_mtl.m`'s `bind_stages`).
3. **Layouts.** Set 0: the union of the stages' set-0 records, cached by content: a push-descriptor layout when its descriptors fit `maxPushDescriptors`, else a set per draw from pools of the submission's slot. Set 1: the pool layout of `vka_tex.c`, bound only for shaders with pool records; the pool tables leave 5 × 64 descriptors of each kind under the device's update-after-bind limits for set 0.
4. **Pipelines** are keyed on the shaders, the layout, the pixel shader's [variant](#variants) and what EDS3 would have made dynamic: attachment formats and sample count, per-attachment blend state and write mask, logic operation, polygon mode, depth clamp, alpha to coverage, topology class, the depth convention (`SpecId 0` on the last pre-raster `F_GL_DEPTH` stage unless the game set `NEAR_IS_ZERO`), the alpha function (`SpecId 1` on an `F_ALPHA_TEST` pixel shader, 0 when the colour state's function is `ALWAYS`); and the vertex input when the device lacks `VK_EXT_vertex_input_dynamic_state`. Variant: `MOD_UINT` when every target the shader writes is a `UINT` attachment, `MOD_UINT_MIXED` when the `UINT` ones are exactly `uint_mask`, `MOD_DUAL` when target 0 blends with a `SRC1` factor and the device has `dualSrcBlend`; else the base. Outputs the shader does not write, and outputs whose class (after the variant) is not the attachment's (a `SINT` target), get write mask 0; without `MOD_DUAL` a `SRC1` factor reads zero. Dynamic: one viewport and one scissor, cull mode, front face, topology, depth and stencil tests and masks, depth bias, rasterizer discard, primitive restart, blend constants, vertex input. One `VkPipelineCache`, read from and written to `$DM_TEMP/vk_pipelines.bin` (`DM_VK_PCACHE` overrides).
5. **Per draw or dispatch** `vka_walk` turns each record into its source, as `encode_args` does on Metal: uniform slot → the bound range (in place when `minUniformBufferOffsetAlignment` allows, else a copy of the CPU's bytes); `SBUF` / `SBUF_UBO` / `UBO_PTR` → the address rounded down to `ssbo_align`, the range grown by the difference (a library without `ssbo_align` binds the exact address and refuses a draw off the device's alignment); combined slot → the view of the texture ID in the record's `dim` shape and the sampler of its sampler half; handles and addresses in uniform buffers read from guest memory with the bound pools' bases; `DRVCB` → a fresh buffer filled by `vka_drv_fill` (c0 storage tables, point size 2047, base instance, the sample table, dispatch size and compute uniform table; c2 handle tables; the push block); `SUPPORT` → zeros with `render_scale` 1.0, and in the fragment stage of an alpha-tested draw word 0 = the reference's float bits; `GMEM_MAP` / `GMEM_LOG` → the chunks' address table and a cleared log. An indirect dispatch copies its GPU-written counts into c0 first.
6. **Unbound slots** read a null descriptor (`VK_EXT_robustness2` `nullDescriptor`), else a zero buffer (storage: a sink) or a 1x1 zero image of the record's shape.

Not done yet, each logged once when met: `SINT` attachments, float vertex formats into integer inputs (bound as the same layout in the input's class), transform feedback, texel buffers, DrawTexture, tessellation, quads and line loops through indirect draws, viewport swizzles other than a y flip, the base instance of indirect draws in c0, the gmem write log's pages.

Draws and dispatches run only with placed guest memory (or `DM_VK_DRAW=1`); on an emulator without it they are skipped (`DM_VK_DRAW=0` turns them off anywhere). Events, counter reports and fences are written by the completion thread whatever happens to the draws. `DM_VK_SHLIB_CHECK=1` (a game run) makes every module and variant of the library and every compute pipeline right after it loads (`[vk] shader library check: …`); the graphics selftest does the same for the library `DM_VK_SHLIB` names.

Device features the DKB library asks for: `geometryShader` (7 modules), `shaderStorageImageWriteWithoutFormat` (161) and `ReadWithoutFormat` (30), `shaderDrawParameters` (every vertex module), subgroup ballot / shuffle / vote (344), `bufferDeviceAddress` with `shaderInt64` (2 modules), and, for `MOD_DUAL` modules, `dualSrcBlend`. No NVIDIA-only capability is left.

## Donkey Kong Bananza

Title 040074A01BF12000, update 458752, the profile's 22,852 shaders (205 compute, 7 geometry, 13,546 pixel, 9,094 vertex). 

## Status (2026-11-02)

Full DKB build on the Mac (`J=12`, 14.5 min), library `v-9b3a2294056a`: **22,852 / 22,852 shaders translated** (cs 205, gs 7, ps 13,546, vs 9,094), 24,034 modules, every one valid for `spirv-val --target-env vulkan1.3`; 364.5 MB with zstd (1,822 MB of SPIR-V before). No binding with an undecoded slot or unknown kind; no 64-bit float left; no NVIDIA capability or extension. Flags: `alpha_test` 13,442, `gl_depth` 9,101, `int_out` 733, `subgroup` 344, `hmma` 24, `viewport_cull` 4. Library stamps: chain `725d300e1fae`, vk chain `9b3a2294056a`, inputs `5dd843bed8d1`. The library is private (game-derived) and lives in the work folder, `040074A01BF12000-work/android-shaders/lib2/`, and in the pack, never in git. The earlier library (`lib/v-380640952314`, before the answers) is stale.

## Open problems

1. **Subgroup size** (closed): the job answer `subgroup_size: 64` ([Translator answers](#translator-answers)). The `F_SUBGROUP` modules split ballots and lane masks into 32-lane halves and shuffle within 32-lane segments; checked in the SPIR-V (two ballots per vote / ballot, `SubgroupLtMask` component 1, `laneId & 32`). Not yet run on Adreno: a compute workgroup whose 32-lane warps the game relies on (warp IDs, shared-memory layouts by warp) is still a question for the phone.
2. **Storage buffer alignment** (closed): the job answer `ssbo_align: 64`; every `SBUF` / `SBUF_UBO` / `UBO_PTR` access computes its offset from the base rounded down to 64 (`OpBitwiseAnd … -64` in the modules), so the replay binds at `addr & ~63`. If a device's `minStorageBufferOffsetAlignment` is above 64 (Vulkan allows 256), the library must be built with that value.
3. **NV-only geometry** (closed): the job answer `gs_passthrough: false` and the viewport mask kept in a `Private` variable: the four passthrough geometry shaders (DKB's terrain culling one, `5d537f7630ec3671`, among them) have no NVIDIA capability or extension left. What remains: a primitive whose mask names viewports other than 0 is drawn in the pipeline's viewport (`F_VIEWPORT_CULL`).
4. **Fragment shader interlock** is declared but used by no DKB shader (the capability is dropped); a game that uses it needs `VK_EXT_fragment_shader_interlock`, not in the probe.
5. **Variants:** built: integer colour outputs (`MOD_UINT`, `MOD_UINT_MIXED`), dual-source blending (`MOD_DUAL`, for `dual_want.tsv`; DKB has none, and a DKB pipeline that blends with `SRC1` needs the Mac host's `DM_NVN_DUAL_WANT` table first), the alpha test (a specialization constant, [Variants](#variants)). Not built: `SINT` attachments, integer vertex formats fed to float inputs (`dmsh_vin`), transform feedback (`vs_tf`, or `Xfb` decorations from `tf_layout`), the base instance of indirect draws (`vs_base_instance`, through `BaseInstance`).
6. **Size.** The translator runs in debug mode (`XLATE_DEBUG` default 1, the chain's option): its SPIR-V is full of temporaries. `spirv-opt -O` takes a module to about 40 % (measured on three), zstd to about 20 %; the library is stored with zstd. Optimizing too would change the code the phone runs against the Mac's, so it is left for when the replay runs.
7. **Pool view types:** see the aliasing note above; and a combined or storage image traced to no slot is not in the ABI (kind `UNKNOWN`; none on DKB).
8. **Tessellation:** the build passes hull and domain shaders through like the others (stage ranges 1 and 2), but no game here has one and their `c0` storage tables were never measured.
9. **Not run on the phone.** Everything above is checked by `spirv-val` only; no module has been through Adreno's compiler.
