# Star Fox (`0400E88025B1A000`)

A Switch 2 game: update v131072, SDK 21.4.4 built for the Switch 2 (`Ounce`), NVN2 with DLSS. A custom engine (its own prefixes: `vfs`, `vrender`, `vjob`, `vscript`, `vspirv`, `veffects`, `vphysics`; project name `eclipse`), with Wwise 2024.1 for audio, Bullet physics and NPLN for online play. Modules `main`, `sdk`, `subsdk0` (the SDK's movie module), `subsdk3` (`nn::camera`); `subsdk1` (the C library) and `subsdk2` (`NintendoSDK_DLSS`) are shipped but `main` imports nothing from them.

**Status on Android:** In bring-up. Host fixes, HLE services, and tooling ported; not run on an Android phone yet. Upstream plays on Mac (M4 Pro / M5 Max): title, prologue cinematic, and tutorial flight.

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_alias 0x400000000`, `mem_reserve 0x550000000`, `mem_stack 0x10000000`, `app_pool 0x240000000` | [`game.tsv`](game.tsv) | the Switch 2's 9 GB. The alias region mapped up to +8.26 GB of its 16 GB, 6.78 GB at most at once, through the tutorial's start; 8.5 GB resident, 4 GB of it allocated on the GPU |
| `dlss_standin 1` | `game.tsv` | Android: NVN2 DLSS runs on vka's stand-in upscaler (`vka_dlss.c`, Snapdragon GSR2 3-pass by default) |
| `sys_resource 0x1000000` | `game.tsv` | the NPDM declares 16 MB, so virtual address memory is on; `main` never sets a heap |
| `modules main subsdk0 subsdk3 sdk` | `game.tsv` | `main` imports 17 movie functions from `subsdk0` and 19 `nn::camera` functions from `subsdk3`, nothing from `subsdk1` or `subsdk2` |
| `romfs_marker data.zip` | `game.tsv` | the RomFS has two files, and this is the game |
| `zip_store .zip` | `game.tsv` | the archive's members are LZ4-compressed already |
| `shader_groups /data.zip=shader` | `game.tsv` | every shader is a member of the archive |

There is no game-specific patch, and only v131072's build row: the base game, v0, has not been run.

## Files

- A NUP container of 30 GB: a base package (v0) and an update package (v131072), each with its own ExeFS and RomFS. Neither package carries a NACP or an icon, so the name comes from the profile, the display version is the placeholder `v131072`, and the app has no icon.
- The RomFS is two files: `data.zip` (14.5 GB) and `veclipse_build.cvar` (a JSON line: `system.is_shipping_build: true`). `main` opens `rom:/data.zip` through its own virtual file system (`vfs.zip.c`): LZ4-chained zip members behind a VZIP preamble. Its 114,154 members are named by a 16-digit hash with no folders or extensions; 92,069 are chained, 22,085 (the Wwise `RIFF` sound files and `BKHD` banks) stored plain.
- `main` is 192 MB of code with 1,036 imports, of which the host and the other modules supply all but two (gRPC thread-local initialisers, `_ZTH…`). No shader is in any code module.

## Shaders

2,856 in [`shader_map.tsv`](shader_map.tsv): 1,483 vertex, 1,305 pixel, 68 compute; 2,828 one per archive member, no task or mesh shaders, and the rest from the two NDSR upscaler networks embedded in `VIPR` containers. All of them translate. Each member is the engine's shader binary (`_vrender_shader_binary_t`), read as a single-stage program and paired at draw time (`separable_pairs`: 1,480 cover pairs).

## Memory Footprint & Phone RAM Constraints

Upstream measured 8.5 GB resident memory, 4 GB of which was allocated on the GPU. On Android:
- Phones with 12 GB or 16 GB of physical RAM are strongly recommended.
- `mem_map_limit` is enabled by default in the host to prevent unconstrained physical mappings beyond the application pool.
- Devices with tighter memory budgets will likely need Vulkan RAM saver options such as `tex_soft` or aggressive swap handling once tested on real hardware.

## The Picture

Docked, 3D targets render at 768x432 / 960x540: DLSS stand-in upscaling takes it up, the game's NDSR compute networks upscale further, and the output reaches the presentation swapchain. Tier 0 graphics scale produces 960x540 handheld baseline.

## Found on this game

- SDK 21 socket initialization and overloads, and socket poll with nothing to wait on.
- SDK 21's `OutputStream` on a virtual output device, and SDK 21's hardware Opus decoder manager (`InitializeHardwareOpusDecoderManager`, etc. in `hle_codec.c`).
- `nn::chat`: `GetChattingUid`, VirtualCamera family, `GetRealtimeAudioLevels`, `ShowLocalCameraConfiguration`, `GetCameraImageProcessingModeForVirtualCamera`.
- `nn::oe`: `BeginPresentPhotographedContents`, `EndPresentPhotographedContents`, `ExitApplicationAndGoBackToInStoreDemoMenu`.
- `nn::nifm`: `GetCurrentIpConfig` (SDK 21 overload).
- `nn::applet`: `BeginUsingCameraResource`, `EndUsingCameraResource`.
- `nn::camera::sdk`: `DestroyCameraContext`, camera commands 3, 4, 21, 29 in `camera_answer`.
- `nvnDlssNetworkResize` in `hle_nvn.c`.
- `shader_split.py`: VZIP preamble and LZ4-chained zip members, `viper_shader` single-stage NVN2 program wrapper.

## Open

- Not run on an Android phone yet; bring-up verification needed on real devices (Adreno 830 / 840, Turnip & stock drivers).
- High memory footprint: 8.5 GB resident, 4 GB GPU; evaluate phone memory pressure and `tex_soft` applicability when run on hardware.
- Whether a console renders above 960x540.
- `nn::socket::EventFd` returns stub error; HTTP thread polls without wake-up (harmless offline).
