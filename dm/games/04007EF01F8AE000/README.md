# Fire Emblem Fortune's Weave

The Android profile starts from [nxnative's profile for update v131072](https://github.com/seedds/nxnative/blob/7f50222003e4270e3655d4f798e85744d390edf0/dm/games/04007EF01F8AE000/README.md). The game uses SDK 22.2.0 and NVN2. The profile selects main, subsdk2 and sdk and pins their build IDs.

What a player gets by default (`game.tsv` `host_env`): the game's DLSS evaluation replaced by FSR 3.1, its 1080p display buffer mode (a 960x540 scene upscaled to 1920x1080), view-format learning off and the Lanczos present. Game settings › Aspect ratio offers a native 4:3 version for 4:3 handhelds (below). Measured on a GPU-bound Snapdragon handheld with Turnip: about 30 FPS in Dagsion, in battles and in the movies.

## Android bring-up

Build a separate test app with `-PnxGames=04007EF01F8AE000 -PnxApp=fefw`. Prepare the matching dump with the Android packer. Validate the Vulkan shader library and first rendered frame before importing a save. The source profile's MetalFX path and Mac performance measurements are specific to Apple platforms.

SDK 22.2.0 aborted in `nninitStartup`. The allocator's size calculation assumes a 62 MiB node area, but this SDK reserved nearly 1 GiB. `sdk_patches.py` matches the constructor and updates both its limit and its occupied-range size. With that rule the game passes startup and reaches the autosave notice and the opening movie.

### Vulkan autosave notice flicker

The autosave notice can alternate between normal and bright blue on restart. The first launch uses separate images for the window texture formats. On later launches `vk_view_formats.txt` supplies the learned Vulkan format pairs `64 37 3840 2160` and `64 43 3840 2160`, allowing them to share an image from the start. See [textures of two formats over one image](../../../docs/reference/rendering.md#textures-of-two-formats-over-one-image-on-the-vulkan-replay).

With the stock Qualcomm driver and the same save, pipeline cache and prewarm list, `DM_VK_VIEW_LEARN=0` kept the notice clean on two restarts; with learning on the flicker came back. The profile sets it (`host_env`). This is a workaround: the cause within the shared-image path is still open.

### Missing scene colour after the opening (DLSS)

The Android NVN2 DLSS call was unimplemented, leaving the game's upscaler output unwritten: the UI drew over a black 3D scene. `DM_VK_DLSS_BLIT=1` (set by the profile) records the evaluation as a copy record (`p_dlss_t2t`, `OP_COPY_T2T`) with the game's depth, motion vectors, jitter, motion vector scale and reset, and the replay evaluates it:

- **FSR 3.1** (`vka_fsr3.c`, the default): temporal upscaling with AMD FidelityFX FSR 3.1's passes (SPIR-V in `dm/gfx/vk_android/fsr3/`), using push descriptors. The game renders its scene with a sub-pixel jitter every frame for DLSS to accumulate; a spatial upscaler keeps that jitter, so every edge shook. In the game the jitter is in input pixels and the motion vector scale is 960x540, so the motion vectors are in UV units; depth is reversed Z32. On the static colosseum facade at the spawn view the mean frame-to-frame change fell from 4.98 (13.9% of pixels changed) with FSR 1 to 0.7–0.8 (about 1%). `DM_VK_FSR3_SHARP` controls RCAS from 0 to 1 (default 0.8).
- `DM_VK_DLSS_FSR3=0` falls back to a linear copy (which keeps the jitter).
- `DM_VI_MAX_RES=1080` (set by the profile) gives the game's display query its 1080p preset, raising the scene from 640×360 to 960×540 and the upscaler output from 1280×720 to 1920×1080. The game picks the scene size itself from preset objects (table main+0x223e0b0, `{out_w, out_h, in_w, in_h}` copied to obj+0x3408); `GetOptimalSettings` (and `DM_VK_DLSS_INPUT_X/Y`) does not change it.

### Saves

Point the Android test app's `DM_SAVE` at a writable test directory with the save files under its `000/` subdirectory. The game opens `save:/000/Global` and `save:/000/Auto`. Putting those files directly in the mount root led to the prologue; the nested copy exposed **Continue**.

### Frame time

GPU-bound in Dagsion on a mid-range Snapdragon: about 21 ms of vertex-heavy draws, 7.4 ms of compute and 3.3 ms of FSR 3.1. `patches.py` `CUTS` turns on the game's own "no depth pre-pass" mode (main+0xb9cb0c) for every player: about 2 ms of depth-only passes fewer, rendering the same. No measurable gain from `DM_VK_INSTANCE`, `DM_VK_CLASSIC_RP` or the FSR pyramids' quad version. `DM_VK_FSR3_OUT_W/H` (FSR output at that size, then a linear blit to the game's output) saves about 1 ms at 1280x960 but resizes twice and looks softer.

### Stretched geometry in Dagsion

Turning past the wagon and market stalls could stretch geometry across the sky. A 93-index draw for pixel shader `41b4df634989248b` exposed the state error: its positions stayed identical while location 4 changed format and read past the end of its 24-byte vertex. See [Shorter vertex attribute layouts](../../../docs/reference/rendering.md#shorter-vertex-attribute-layouts).

### Saraminian procession cutscene

The procession (`MV11_06`) plays a 2560×1440 AV1 movie and separate Opus streams for music, effects and dialogue. It dropped to 19–23 FPS before the Android [AV1 and video texture fixes](../../../docs/reference/movies.md#slow-av1-cutscenes-on-android); it now plays at 30 FPS, with the captured stereo mix matching the English dialogue stream.

## Native 4:3 (Game settings › Aspect ratio)

`patches.py` `VARIANT_CUTS['43']` and the `game.tsv` row `variant 43 main 4:3` make a native 4:3 version (see [Native aspect variants](../README.md#native-aspect-variants)). Prepare rewrites `main` a second time into `<pack>/43/`; choosing 4:3 loads it (`DM_PATCH_VARIANT=43`) and shows the picture at 4:3 (`DM_VK_PICTURE_ASPECT=4:3`): it fills a 4:3 screen and gets side bars on a 16:9 one. 16:9 stays the default.

The game keeps its 1920x1080 window and scene; its cameras take a 4:3 aspect and its UI is laid out for a 1920x1440 screen squeezed into the frame. The words started as a `.pchtxt` for Eden-family emulators and were extended here:

| Group in `patches.py` | What it does |
|---|---|
| `_V11` | Camera aspect 4:3; UI projection squeezed; panes at the top and bottom moved to the 4:3 edges (the edge rule); minimap pinned; full-screen overlays stretched (the overlay rule); sliding menu containers anchored |
| `_SCISSOR43` | Clip rectangles (the minimap) squeezed like the rest of the UI |
| `_UI_V12` | Base Menu cursor (the `L_List` container is not scaled), dialog text in its box (`A_Contents` children not moved), tall half-screen dimmers (the shop's `Fade01`) cover the full height |
| `_UI_V13` | The Armorer's Buy/Sell and help panels keep their 4:3 heights while their empty parent panes slide |
| `_UI_V14` | Combat: the smoky bands behind the names (`Player_Fade` / `Target_Fade`) move with them |
| `_UI_V15` | The EXP panel no longer pops into place (its 422x4 sliding parent anchored); the army flag and smoke stay level with the names and battle report while `N_Player_Bg` / `N_Target_Bg` sit at y -80 (forecast, Blaze Arts) |
| `_UI_V16` | Level-up window: the unit's name stays in the panel header (`N_Status` children not moved) |
| `_UI_V17` | Unit details (X on a unit): the stat windows stay below the header (`L_UnitIn*` children not moved inside the stretched `L_UnitInfo`) |
| `_UI_V18` | Obelisks: the darkening `Fade` (1920x856) covers the whole 4:3 screen (overlay stretch); the Flame Lord art `UnitPub` is no longer stretched 4/3 tall |

Checked screens: map HUD and minimap, command menu, forecast, combat and Blaze Arts, EXP and level-up, unit details, items, quick settings, obelisks, Base Menu, shops, dialogue. Not yet checked: dungeon battles, the end-of-chapter battle report. Cave torches miss some of their light pools at a distance in both aspects (likely the game's own light culling; open).
