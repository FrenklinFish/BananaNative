// The Vulkan shader library ABI (Android): what the offline build (dm/gfx/tools/vk_android_build.py) and the Vulkan replay (dm/gfx/vk_android) agree on. The
// driver segment layout (c0 / c2 tables) and the binding kinds' meanings are dmsh_abi.h's; this file adds where each binding lands in Vulkan and the library's
// file format. android/SHADERS.md explains it.
// vk_android_build.py reads only the "#define DMSH_VK_<NAME> <value>" lines (hex or decimal integer, or a "string", an optional // comment after it); the structs
// below are for C. It is not part of DMSH_CHAIN (the Apple libraries do not depend on it); it is part of DMSH_VK_CHAIN (dmsh_chain_vk.h): after changing it, run
// vk_android_build.py --header and rebuild the Vulkan libraries.
#pragma once
#include <stdint.h>

// The library: <cache dir>/v-<DMSH_VK_CHAIN>/DMSH_VK_LIB_FILE, one file per game (index, binding records, module table, then the blob of SPIR-V words and c1 bytes).
// All integers little-endian. The replay maps the file and takes it when its header names the DMSH_CHAIN and DMSH_VK_CHAIN it was compiled with, or, while that
// version's library is being made, when its header's abi is the host's DMSH_VK_ABI (dmsh_chain_vk.h): the digest of this file and dmsh_abi.h without comments
// (vk_android_delta.py abi_digest), so an older library of the same ABI plays while its delta update runs (android/SHADERS.md#delta-updates)
// DMSH_VK_ABI_REV: raise it with any change of what the replay expects of a library or its modules that neither header shows (a convention in vk_android_spv.py or
// vka_*.c), so such a change moves DMSH_VK_ABI and an older library is no longer played
#define DMSH_VK_ABI_REV 1
#define DMSH_VK_LIB_FILE "dmsh_vk.lib"
#define DMSH_VK_LIB_MAGIC 0x4B56534D     // "MSVK"
#define DMSH_VK_LIB_VERSION 1
#define DMSH_VK_DIR_PREFIX "v-"          // the library directory: v-<DMSH_VK_CHAIN>

// Device answers the translator was given (Program.cs job keys subgroup_size / ssbo_align / gs_passthrough; android/SHADERS.md), also in the library header. A library built
// before they existed has 0 in both header fields: 32-lane subgroups and storage buffers bound at their exact address.
// Subgroup size: Maxwell code is written for 32-lane warps; built for 64 (Adreno's fixed size), ballots, lane masks and shuffles work in 32-lane halves of the subgroup.
// The modules are right on a device whose subgroups are 32 or 64 lanes (with VK_EXT_subgroup_size_control, pin one of them for compute)
#define DMSH_VK_SUBGROUP_SIZE 64
// Storage buffer alignment: every storage buffer the translator made of global memory (kinds SBUF, SBUF_UBO, UBO_PTR) is bound at its guest address rounded down to this
// (addr & ~(DMSH_VK_SSBO_ALIGN - 1)), its range grown by the difference; the shader adds the difference back. The device's minStorageBufferOffsetAlignment must divide it
#define DMSH_VK_SSBO_ALIGN 64
// Shared memory: a compute program declaring more than this (with the translator's HMMA scratch) keeps its main shared memory in a storage buffer, binding kind
// DMSH_VK_KIND_SHARED_SPILL (Program.cs job key shared_max; Ryujinx StructuredIr/SharedSpillInfo.cs). The Switch 2 gives a workgroup 48 KiB, the Adreno 840 32 KiB
// (maxComputeSharedMemorySize 32768, which the device must reach); the entry's shared is then what stays in workgroup memory
#define DMSH_VK_SHARED_MAX 32768

// Descriptor sets. One pipeline layout shape for every pipeline: set 0 holds the per-draw resources of every stage, set 1 the texture and sampler pools
#define DMSH_VK_SET_DRAW 0
#define DMSH_VK_SET_POOL 1
#define DMSH_VK_SETS 2
// Set 0: stage s's resources have bindings s * DMSH_VK_STAGE_BINDINGS + n, n in the translator's order. Stages are numbered as NVN does: compute pipelines use stage 0's range
#define DMSH_VK_STAGE_BINDINGS 64
#define DMSH_VK_STAGE_VS 0
#define DMSH_VK_STAGE_TCS 1              // NVN tess control (hull)
#define DMSH_VK_STAGE_TES 2              // NVN tess evaluation (domain)
#define DMSH_VK_STAGE_GS 3
#define DMSH_VK_STAGE_FS 4
#define DMSH_VK_STAGE_CS 5               // compute: its bindings use the range of DMSH_VK_STAGE_VS (binding n)
// NVN2 task and mesh programs (VK_EXT_mesh_shader; a mesh pipeline is task (optional) + mesh + fragment, with no vertex, tessellation or geometry stage): the entry's local
// is the workgroup size. Their bindings reuse ranges those stages leave free, so set 0 keeps 6 ranges: task the range of DMSH_VK_STAGE_TCS, mesh that of DMSH_VK_STAGE_VS
// (a descriptor set layout for a mesh pipeline gives those ranges VK_SHADER_STAGE_TASK_BIT_EXT / MESH_BIT_EXT). Their storage buffer tables are c0 DMSH_C0_SSBO_TS / _MS
#define DMSH_VK_STAGE_TS 6
#define DMSH_VK_STAGE_MS 7
// Set 1, update-after-bind and partially bound: the NVN texture pool by texture ID (20 bits) and the sampler pool by sampler ID (12 bits). Only shaders whose handle the
// translator could not trace to a slot read them (dmsh_build.py pool_arrays). Every sampled-image array a shader declares there aliases binding 0, whatever its image type:
// the descriptor at texture ID i must be a view of the type that shader samples
#define DMSH_VK_POOL_TEX_BINDING 0
#define DMSH_VK_POOL_TEX_COUNT 0x100000
#define DMSH_VK_POOL_SAMP_BINDING 1
#define DMSH_VK_POOL_SAMP_COUNT 0x1000

// Binding kinds as numbers: the same kinds as dmsh_abi.h's DMSH_KIND_* strings (the comment names each); what fills them is the same as on Metal
#define DMSH_VK_KIND_CBUF 1              // cbuf: uniform slot reg (c[3 + reg]), UNIFORM_BUFFER at the bound address and size
#define DMSH_VK_KIND_C1 2                // c1: the shader's own c1 bytes (entry c1_off / c1_len in the blob), UNIFORM_BUFFER
#define DMSH_VK_KIND_DRVCB 3             // drvcb: driver segment c[reg] (0 or 2: DMSH_DRV_SIZE bytes filled from the bindings; DMSH_PUSH_BANK: the pushed DMSH_PUSH_SIZE bytes)
#define DMSH_VK_KIND_SUPPORT 4           // support: Ryujinx's support buffer (render scale 1.0, no BGRA swap), UNIFORM_BUFFER
#define DMSH_VK_KIND_SBUF 5              // sbuf: storage buffer slot reg, address and size from the stage's c0 table; flag RW
#define DMSH_VK_KIND_SBUF_UBO 6          // sbuf_ubo: storage buffer whose address is in uniform slot reg at byte off
#define DMSH_VK_KIND_UBO_PTR 7           // ubo_ptr: compute uniform slot reg, read through c0 (DMSH_C0_CS_UBO) as a storage buffer
#define DMSH_VK_KIND_TEX 8               // tex: texture slot reg (combined, or DMSH_SEPTEX_SLOT0 + r); with skind, a combined image sampler
#define DMSH_VK_KIND_TBUF 9              // tbuf: buffer texture in texture slot reg, UNIFORM_TEXEL_BUFFER
#define DMSH_VK_KIND_IMG 10              // img: image slot reg, STORAGE_IMAGE or STORAGE_TEXEL_BUFFER; flag ATOMIC
#define DMSH_VK_KIND_SAMP 11             // samp: separate sampler slot reg
#define DMSH_VK_KIND_SAMP_TEX 12         // samp_tex: the sampler of the combined handle in texture slot reg
#define DMSH_VK_KIND_SAMP_ID 13          // samp_id: sampler ID reg, a constant of the shader
#define DMSH_VK_KIND_TEX_UBO 14          // tex_ubo: texture handle in uniform slot reg at byte off (low 20 bits texture ID)
#define DMSH_VK_KIND_SAMP_UBO 15         // samp_ubo: sampler handle in uniform slot reg at byte off (high 12 bits sampler ID)
#define DMSH_VK_KIND_TEX_C2A 16          // tex_c2a: array of count c2 handles, element k = the handle at c2 byte off + 8 k
#define DMSH_VK_KIND_SAMP_C2A 17         // samp_c2a: the same for samplers; soff = the c2 byte offset of one sampler word ORed into every element, 0xFFFF none
#define DMSH_VK_KIND_TEX_POOL 18         // tex_pool: set 1 binding 0
#define DMSH_VK_KIND_SAMP_POOL 19        // samp_pool: set 1 binding 1
#define DMSH_VK_KIND_GMEM_MAP 20         // gmem_map: the pool table for raw global memory (DMSH_GMEM_MAP_*; delta = buffer device address - guest address), STORAGE_BUFFER
#define DMSH_VK_KIND_GMEM_LOG 21         // gmem_log: the write log of those stores, STORAGE_BUFFER, cleared per draw or dispatch
#define DMSH_VK_KIND_SHARED_SPILL 22     // shared_spill: a compute program's shared memory past DMSH_VK_SHARED_MAX, STORAGE_BUFFER, the record's bytes per workgroup slice. Workgroup w
                                         // (linear index (z * groups y + y) * groups x + x) uses slice w mod (the bound range / bytes); slices not cleared (shared memory starts undefined)
#define DMSH_VK_KIND_UNKNOWN 255         // ?: not placed; the replay refuses the shader

// Binding record flags
#define DMSH_VK_BF_RW 1                  // the shader writes it (storage buffer, image)
#define DMSH_VK_BF_ATOMIC 2              // image atomics on it (R32Uint / R32Sint only)
#define DMSH_VK_BF_FETCH1D 4             // a 1D integer fetch read as 2D rows of 16384 texels (dmsh_build.py fetch1d): bind a buffer texture there as such rows
#define DMSH_VK_BF_NOSLOT 8              // a slot kind whose slot was not decoded (reg -1)

// Image shape of a binding (dim): SPIR-V Dim in the low 3 bits, then these
#define DMSH_VK_DIM_MASK 7               // 0 1D, 1 2D, 2 3D, 3 cube, 5 buffer (SPIR-V Dim values)
#define DMSH_VK_DIM_ARRAY 8
#define DMSH_VK_DIM_MS 16
#define DMSH_VK_DIM_DEPTH 32             // a depth-compare image (sampled with a compare sampler)
#define DMSH_VK_DIM_SINT 64              // sampled type: neither bit = float
#define DMSH_VK_DIM_UINT 128

// Modules of an entry (variant). Entry name "main" in every module. The base module comes first; a pixel shader may have more, which the replay picks by the pipeline's
// attachments and blend state (android/SHADERS.md "Variants"). An older library has only DMSH_VK_MOD_BASE
#define DMSH_VK_MOD_BASE 0
#define DMSH_VK_MOD_UINT 1               // every colour output as the unsigned integer vector of its bits: for a pipeline whose colour attachments are all UINT formats
#define DMSH_VK_MOD_UINT_MIXED 2         // only the targets of the entry's uint_mask as unsigned integers, the others float: for exactly those UINT attachments
#define DMSH_VK_MOD_DUAL 3               // output 1 as Location 0 Index 1: for a pipeline blending target 0 with a SRC1 factor (dualSrcBlend); profile table dual_want.tsv
#define DMSH_VK_MOD_SHUFFLE_LDS 4        // compute: its 32-bit subgroup shuffles through workgroup memory (vk_android_spv.py shuffle_lds), for a driver without an arbitrary-index
                                         // shuffle instruction (Turnip makes each one a loop); its workgroup memory, with the rows this adds, is at most DMSH_VK_SHARED_MAX bytes
// How a module is stored: the SPIR-V words as they are, or one zstd frame of them (about a fifth of the size; the replay links zstd's decompressor)
#define DMSH_VK_CODEC_RAW 0
#define DMSH_VK_CODEC_ZSTD 1
// Specialization constants. The depth convention is the game's run-time state (nvnDeviceSetDepthMode), so the vertex, tess evaluation and geometry modules that write a
// position carry it as a bool (entry flag DMSH_VK_F_GL_DEPTH): false (default) leaves z as translated (near = 0), true writes z' = fma(z, 0.5, w * 0.5) (near = -w, the GL
// convention and NVN's default). Set it true on the last pre-raster stage of a GL-mode pipeline only, or leave it false and use VK_EXT_depth_clip_control's negativeOneToOne
#define DMSH_VK_SPEC_GL_DEPTH 0          // SpecId
// The alpha test (entry flag DMSH_VK_F_ALPHA_TEST, pixel shaders with a float vec4 colour 0): a uint constant, NVN_ALPHA_FUNC_* (1 never … 8 always; default 0 = no test).
// The reference is the float bits of the fragment stage's support buffer word 0, written per draw (Ryujinx's alpha_test field, which no translated shader reads)
#define DMSH_VK_SPEC_ALPHA_FUNC 1        // SpecId

// Entry flags
#define DMSH_VK_F_EARLY_Z 1              // early fragment tests (control +0x718; also an EarlyFragmentTests execution mode in the module)
#define DMSH_VK_F_SOFTFLOAT64 2          // FP64 lowered to FP32 (the device has no shaderFloat64)
#define DMSH_VK_F_SUBGROUP 4             // subgroup operations (built for header subgroup_size lanes: see DMSH_VK_SUBGROUP_SIZE)
#define DMSH_VK_F_TF 8                   // transform feedback in the program's control section: not captured by this library (no Xfb decorations)
#define DMSH_VK_F_NV_PASSTHROUGH 16      // geometry passthrough through SPV_NV_geometry_shader_passthrough (none since the gs_passthrough answer)
#define DMSH_VK_F_NV_VIEWPORT 32         // ViewportMaskNV / SPV_NV_viewport_array2 (none: see DMSH_VK_F_VIEWPORT_CULL)
#define DMSH_VK_F_INTERLOCK 64           // fragment shader interlock (VK_EXT_fragment_shader_interlock)
#define DMSH_VK_F_HMMA 128               // the HMMA helper dmsh_hmma16816_f16 (agl's upscaler), its calls lowered in the module (vk_android_spv.py hmma_lds)
#define DMSH_VK_F_NO_FACTS 256           // not in the answer table: queries took defaults
#define DMSH_VK_F_INT_OUT 512            // a colour output written from integer bits: has a DMSH_VK_MOD_UINT module (and UINT_MIXED when it writes float outputs too)
#define DMSH_VK_F_GL_DEPTH 1024          // has the DMSH_VK_SPEC_GL_DEPTH constant
#define DMSH_VK_F_ALPHA_TEST 2048        // has the DMSH_VK_SPEC_ALPHA_FUNC constant (every variant of the entry)
#define DMSH_VK_F_VIEWPORT_CULL 4096     // emulated geometry passthrough that drops a primitive whose NVN viewport mask is 0; the mask's other bits are not routed to viewports
#define DMSH_VK_F_MESH_PRIM 8192         // pixel shader with PerPrimitiveEXT inputs (the per-primitive outputs of the mesh programs it is drawn with): valid only in a mesh pipeline
                                         // (needs the device's meshShader feature); a mesh entry with the flag writes per-primitive outputs

// Vertex input / fragment output component types, 2 bits per location
#define DMSH_VK_TYPE_FLOAT 0
#define DMSH_VK_TYPE_SINT 1
#define DMSH_VK_TYPE_UINT 2

#define DMSH_VK_HEADER_SIZE 128
#define DMSH_VK_ENTRY_SIZE 64
#define DMSH_VK_BIND_SIZE 24
#define DMSH_VK_MOD_SIZE 16

typedef struct {
    uint32_t magic, version;
    char chain[16];          // DMSH_CHAIN of the translator front half (NUL padded, so not terminated when 16 characters long, as game is)
    char vk_chain[16];       // DMSH_VK_CHAIN
    char game[16];           // title ID of the profile
    char inputs[16];         // the profile's input digest (dmsh_chain.py inputs), as b-<V>/inputs.tsv records it
    uint32_t entry_count, entry_off;   // entries sorted by key
    uint32_t bind_count, bind_off;
    uint32_t mod_count, mod_off;
    uint64_t blob_off, blob_size;      // module words and c1 bytes, offsets in the entries and modules relative to blob_off, 16-byte aligned
    uint16_t subgroup_size;  // DMSH_VK_SUBGROUP_SIZE the modules were built for; 0 in an older library: 32
    uint16_t ssbo_align;     // DMSH_VK_SSBO_ALIGN the modules were built for; 0 in an older library: bind storage buffers at their exact address
    char abi[12];            // DMSH_VK_ABI of the tools that made it (12 hex digits, no NUL); zero in a library made before it, which plays only with its own version
} dmsh_vk_header;

typedef struct {
    uint64_t key;            // the shader key (dm_ctl_sha16): its 16 hex digits read as one big-endian number
    uint64_t vin_types;      // DMSH_VK_TYPE_* per vertex input location
    uint8_t stage;           // DMSH_VK_STAGE_*
    uint8_t mod_count;
    uint16_t flags;          // DMSH_VK_F_*
    uint32_t mod;            // index of the first module record
    uint32_t bind;           // index of the first binding record
    uint16_t bind_count;
    uint16_t fout_types;     // DMSH_VK_TYPE_* per colour target
    uint16_t local[3];       // compute workgroup size
    uint8_t fout_mask;       // colour targets written (output locations)
    uint8_t uint_mask;       // the DMSH_VK_MOD_UINT_MIXED module's unsigned integer targets (0: no such module; older libraries: always 0)
    uint32_t shared;         // compute shared memory bytes (the answer table's)
    uint32_t c1_off;         // the c1 bytes, in the blob
    uint32_t c1_len;
    uint32_t vin_mask;       // vertex inputs read, by location (NVN attribute index)
    uint32_t reserved[2];
} dmsh_vk_entry;

typedef struct {
    uint8_t kind;            // DMSH_VK_KIND_*
    uint8_t desc;            // VkDescriptorType
    uint16_t binding;
    uint8_t set;             // DMSH_VK_SET_*
    uint8_t flags;           // DMSH_VK_BF_*
    uint16_t count;          // descriptors in the binding (an array's length); 0 for set 1 (DMSH_VK_POOL_*_COUNT)
    int16_t reg;             // slot, bank, uniform slot or sampler ID, by kind; -1 none
    uint16_t off;            // byte offset (tex_ubo, sbuf_ubo, *_c2a), else 0
    uint8_t skind;           // a combined image sampler's sampler half: DMSH_VK_KIND_SAMP / SAMP_TEX / SAMP_ID / SAMP_UBO / SAMP_C2A, else 0
    uint8_t dim;             // DMSH_VK_DIM_*
    int16_t sreg;            // the sampler half's reg
    uint16_t soff;           // the sampler half's off; 0xFFFF none
    uint16_t reserved;
    uint32_t bytes;          // DMSH_VK_KIND_SHARED_SPILL: bytes of one workgroup's slice; else 0
} dmsh_vk_bind;

typedef struct {
    uint32_t off;            // the module's bytes in the blob (from blob_off)
    uint32_t size;           // SPIR-V bytes (decompressed)
    uint32_t stored;         // bytes in the blob
    uint8_t variant;         // DMSH_VK_MOD_*
    uint8_t codec;           // DMSH_VK_CODEC_*
    uint16_t reserved;
} dmsh_vk_module;

#ifdef __cplusplus
static_assert(sizeof(dmsh_vk_header) == DMSH_VK_HEADER_SIZE, "header");
static_assert(sizeof(dmsh_vk_entry) == DMSH_VK_ENTRY_SIZE, "entry");
static_assert(sizeof(dmsh_vk_bind) == DMSH_VK_BIND_SIZE, "bind");
static_assert(sizeof(dmsh_vk_module) == DMSH_VK_MOD_SIZE, "module");
#else
_Static_assert(sizeof(dmsh_vk_header) == DMSH_VK_HEADER_SIZE, "header");
_Static_assert(sizeof(dmsh_vk_entry) == DMSH_VK_ENTRY_SIZE, "entry");
_Static_assert(sizeof(dmsh_vk_bind) == DMSH_VK_BIND_SIZE, "bind");
_Static_assert(sizeof(dmsh_vk_module) == DMSH_VK_MOD_SIZE, "module");
#endif
