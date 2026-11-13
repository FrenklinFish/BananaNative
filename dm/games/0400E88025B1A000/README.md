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

The 3D scene is the output size times the engine's own resolution scale times its dynamic resolution; the DLSS stand-in then takes it to the output. From code and the game's settings file (update v131072, main `a7730d0f…`):

- **Output:** 1920x1080 on the phone, the host's display size. `QueryDisplayBufferMaxResolution` (`main+0xb448874`; 4K in console mode, 1080p handheld) only clamps it.
- **Scale:** the window update (`main+0xb448780`) reads `render.resolution_scale_console` in console mode, `render.resolution_scale_handheld` in handheld mode (`main+0xb449868`, `main+0xb449898`, getter `main+0xb6ac800`), falling back to `render.resolution_scale` (1.0). The settings file (a JSON member of `data.zip`) gives console 0.833 on other platforms and **0.5 on this console**, handheld 0.833. Some levels set 0.833 for both while they run (their `set_resolution_scale` script; it puts the earlier value back when it ends).
- **Dynamic resolution:** `render.dynres_enabled` true, at least `render.dynres_min_scale_x` / `_y` 0.8 (0.75 handheld). The phone run's GPU time (19.5 to 20.5 ms a frame) keeps it at 0.8.
- So the phone's 768x432 (`DLSS network resized to 768x432`, the one case measured) = 1920 x 0.5 x 0.8. Graphics scale 0 / 1 / 2 only sends the engine a "scale is 0" flag nothing reads; operation mode only picks the console or handheld setting and the clamp. Neither is a lever, so the profile has no `graphics_scale` rows.

**The lever: 3D scene (`scene_arg` rows).** The engine takes any setting as a game argument (`-render_resolution_scale_console=0.75`; a flag as `-<name>` / `-no_<name>`, `main+0xb6b0740`). Game settings › 3D scene passes the choice as `DM_GAME_ARGS_ADD`; nothing is made at Prepare. Expected 3D sizes at the 1920x1080 output (the most the game draws, then x0.8 under load):

| Choice | Arguments | 3D at full dynamic resolution | 3D under load |
|---|---|---|---|
| Game default | none | 960x540 | 768x432 (measured) |
| 60% | both scales 0.6 | 1152x648 | 922x518 |
| 75% | both scales 0.75 | 1440x810 | 1152x648 |
| 100% | both scales 1.0 | 1920x1080 | 1536x864 |
| The game's 50%, no dynamic resolution | `-no_render_dynres_enabled` | 960x540 | 960x540 |

The GPU is already past its budget at 768x432, so the higher choices cost frame rate.

**Static check (resolved, from code): the settings file does not overwrite the argument.** The settings are registered when the engine's settings object is made (`main+0xb6ab800`, called at `main+0xb66414c`, runs the registration callbacks such as `main+0xb38aa60`), before the settings files load (`main+0xb664aa8` → `main+0xb669380` → `main+0xb66caa0`). Registration (`main+0xb6abe20`) finds the argument (`main+0xb6b0740`), logs `Command line overriding value of '%s'.` and writes it through the setter `main+0xb6af9c0`, which marks the setting as set (byte +0xd of its entry, `main+0xb6afddc`). The file's values are parsed into a list (`main+0xb6ae3a0`) and applied by `main+0xb6af7e0` with its flag 1, which skips every setting already marked (`main+0xb6af868`..`main+0xb6af878`). Not checked: whether anything else (the online settings fetch, off offline) sets them later.

**Future option, not built:** the window update reads the scale through the getter on its common path (`main+0xb449810` onward, after the planar-reflection check, on every call), and the levels' script changes the scale while they run, so a host write of the stored value would probably take effect without a restart. Today's choices apply at the next start only.

**Phone verification (open):** with a choice set, the log should show `game arguments added: -render_resolution_scale_console=0.75 -render_resolution_scale_handheld=0.75`, the engine's `Command line overriding value of 'render.resolution_scale_console'.` (and `_handheld`), and `DLSS network resized to 1440x810` or `1152x648` for 75%.

## Found on this game

- [Shared fonts depend on the phone's fonts (one Roboto for six types)](../../../docs/reference/services.md#shared-fonts-depend-on-the-phones-fonts-one-roboto-for-six-types): issue #223; the APK's Noto Sans is now `standard.ttf`, per-type order in the host. Phone check open.
- SDK 21 socket initialization and overloads, and socket poll with nothing to wait on.
- SDK 21's `OutputStream` on a virtual output device, and SDK 21's hardware Opus decoder manager (`InitializeHardwareOpusDecoderManager`, etc. in `hle_codec.c`).
- `nn::chat`: `GetChattingUid`, VirtualCamera family, `GetRealtimeAudioLevels`, `ShowLocalCameraConfiguration`, `GetCameraImageProcessingModeForVirtualCamera`.
- `nn::oe`: `BeginPresentPhotographedContents`, `EndPresentPhotographedContents`, `ExitApplicationAndGoBackToInStoreDemoMenu`.
- `nn::nifm`: `GetCurrentIpConfig` (SDK 21 overload).
- `nn::applet`: `BeginUsingCameraResource`, `EndUsingCameraResource`.
- `nn::camera::sdk`: `DestroyCameraContext`, camera commands 3, 4, 21, 29 in `camera_answer`.
- `nvnDlssNetworkResize` in `hle_nvn.c`.
- The 3D scene from the engine's own settings as game arguments (`DM_GAME_ARGS_ADD`, profile `scene_arg`): [Scene resolution from the engine's own settings](../../../docs/reference/frame-rate.md#scene-resolution-from-the-engines-own-settings-passed-as-game-arguments-android).
- [The DLSS stand-in ignored sRGB textures](../../../docs/reference/rendering.md#the-dlss-stand-in-ignored-srgb-textures-android): too bright with the Upscaler on FSR. DLSS flags 0x4a (LDR colour, auto exposure), colour image RGBA8 (VkFormat 37, which an sRGB texture also has); fixed by the textures' sRGB, pending the phone check of the new `DLSS stand-in: colour NVN format` line (open until it names the formats).
- `shader_split.py`: VZIP preamble and LZ4-chained zip members, `viper_shader` single-stage NVN2 program wrapper.

## Open

- Not run on an Android phone yet; bring-up verification needed on real devices (Adreno 830 / 840, Turnip & stock drivers).
- High memory footprint: 8.5 GB resident, 4 GB GPU; evaluate phone memory pressure and `tex_soft` applicability when run on hardware.
- The 3D scene choices on a phone: whether the engine takes the arguments (see [The Picture](#the-picture), phone verification) and what each costs in frame rate.
- `nn::socket::EventFd` returns stub error; HTTP thread polls without wake-up (harmless offline).
