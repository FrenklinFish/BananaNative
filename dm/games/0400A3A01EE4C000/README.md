# Pokémon Pokopia (`0400A3A01EE4C000`)

A Switch 2 game: update v655360 (the title screen says Ver. 2.0.1), SDK 20.5.25 built for the Switch 2 (`Ounce`). Koei Tecmo's KTGL engine on NVN2, modules `main`, `subsdk0` (WebM movies) and `sdk`. **Plays on the Mac**: the opening, the character choice and the first area's tutorial at about 60 fps, including 31 minutes of the user's playtest with no GPU error and no crash. A save from an earlier run loads and continues. No IPA has been built.

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_reserve 0x270000000`, `app_pool 0x240000000` | [`game.tsv`](game.tsv) | Donkey Kong Bananza's 9 GB layout. The game asks `SetHeapSize` for all of it (8.92 GB) and makes its five memory pools (4,988 MiB) and its allocators inside; what it touches is far less ([Memory](#memory)). The NPDM declares no system resource |
| `mem_used 0x5600000` | `game.tsv` | the rewritten modules take 82 MB, more than the default 64 MB that `SetHeapSize` checks the game's request against |
| `pad_layout full` | `game.tsv` | |

## Files

- A dump folder whose `updates/<version>/` holds the whole RomFS; [`pack_dump.py`](../../../scripts/pack_dump.py) packs it without a base RomFS. RomFS: the newer RDB (as ROMANCE OF THE THREE KINGDOMS 8 REMAKE's) under `motor_asset_re/KIDS_PLATFORM_OUNCE`, and a `LD_C0_R.BIN` / `LI_C0_R.IDX` LINKDATA pair.
- Shaders: 131,426 in [`shader_map.tsv`](shader_map.tsv) ([KTGL on NVN2](../../../docs/reference/shaders.md#ktgl-on-nvn2-switch-2-glslc-outputs-and-ld_--li_-linkdata)). The Mac playtest's library held only the 8,157 that its runs asked for (with their pixel shaders), listed in [`shader_seen.tsv`](shader_seen.tsv); [Building selected Mac shaders](../../../docs/development.md#building-selected-mac-shaders) builds those alone. Such a library has no stamp and no `c1_full.tsv`, and each start logs that in red. The NSP script builds all 131,426: an estimated 9 hours on an M4 Pro (from 1,001 shaders) since [the builder stopped keeping the 4.4 GB of packages in memory](../../../docs/reference/shaders.md#a-shader-build-that-keeps-its-packages-in-memory-runs-one-worker-at-a-time) (before, players saw about one shader a second and ETAs of 20 to 200 hours: GitHub issue #1036).
- No `pso_manifest.tsv` yet, so each start also logs a red line for the missing seed list. In the runs here no draw waited on it.

## Shaders

- Resolved: `cc679c7b4d3e703c` did not translate (`LDC with a bindless bank or an address mode`): an indexed read of c1 with the bank in the address register, found on Hyrule Warriors: Age of Imprisonment, which ships the same shader ([LDC with the bank in its address register](../../../docs/reference/shaders.md#ldc-with-the-bank-in-its-address-register-is)); not rerun on this game. The census also lists f55 three times, two more of that LDC and 16 BRX with no target: whether they are the forms since translated ([Barrier moves around warp shuffles](../../../docs/reference/shaders.md#barrier-moves-around-warp-shuffles)) is not checked (open).
- A `3D binding fallback` line fires once at about frame 2100: a 2D 8x8 texture in a 3D slot. The shader is not identified (open).

## Rendering

- Frames take about 16.7 ms. A cold file cache, as after a reboot, keeps the game on the loading icon for over a minute, and a clean run there says nothing: check that `[nvn] frame N: … calls` reached about 3,500.
- The lit HDR target is RG11B10, exposure a 2x1 RGBA32F image, tone map `075fd7285c64b798` with a 32³ LUT, final `ed5f4c6386ca621d`, the luminance histogram compute `2118ab534fac88c6`.
- `texraw` pads rows: an RG11B10 dump has a 16-byte stride with 4-byte pixels at the head of each row. It returns one slice of a 3D texture, and serials shift between runs.

## Memory

Measured on the phone (Honor PNM-N49, Android, the test app; runs p1a-p8 of 2026-11-05, `/proc/<pid>/smaps`, `DM_NVN_POOLS=1`, `DM_VK_MEMLOG=1`). Current.

- Heap: `SetHeapSize` 8.92 GB (the pool less `mem_used`). Touched by the opening (42 s): 2.2 GiB of anonymous guest memory and 2.4 GiB of placed pool memory; 4.6 GB of the 8.92 GB. The heap's address space costs nothing until touched, so the 9 GB layout stays: whether the game sizes anything by it was not tried (the pools below have the same sizes in every run).
- Pools (census at present 1): 3,500 MiB `0x0a2` (`CPU_UNCACHED | GPU_CACHED | COMPRESSIBLE`; the general GPU heap: at present 300, 978 buffers over 8.8 MiB and 551 textures over 932 MiB of it), 500 and 400 MiB `0x022`, 332 MiB `0x022`, 256 MiB `0x062` (7,739 address queries). Placed whole, the 3.5 GiB pool took 3.5 GiB of committed driver memory at its first buffer and the low-memory killer ended the game 28 s in, before its first frame (current: [Large pools placed whole for one buffer in them](../../../docs/reference/memory.md#large-pools-placed-whole-for-one-buffer-in-them-android), resolved by placing it by parts). Placed pools at the first frame 4,088 → 652 MiB; in the opening 2.9 GiB in all (1.3 GiB of the large pool, in 16 MiB parts).
- Files: its RomFS has a 2.38 GiB `0x38f972af.fdata` and three files of 700-900 MiB. From a `.nup` whose files are zstd frames each was decompressed into memory, 2.4 GiB resident for the big one (resolved: [Large compressed NUP files decompressed into memory](../../../docs/reference/memory.md#large-compressed-nup-files-decompressed-into-memory-android)).
- At frame 7,500 of the opening (147 s): RSS 9.1 GB (counting the files' page cache), `MemAvailable` 1.7 GB of 16 GB, swap 11.8 GB free.

## Android

- First frame at 22 s on a cold file cache, 5 s warm. The opening runs at 60 fps (1,500-1,800 draws a frame in its first 3D scene), the caution and Pokédex screens, the first walking scene with Ditto at 120 s.
- Current, open: **the game stops (no crash) right before the character comes out of a Poké Ball**, 110-150 s into a new save, in every run that got that far (p2, p3, p5, p7, p8, p9, p10). The main thread waits in `nvnQueuePresentTexture` (`main+0x114aef0` returns from it) for the replay. The replay thread (`vk-replay`) is inside the Qualcomm shader compiler making **compute pipeline `19391de31e77a42b`** (p9's watchdog dump: `compute pipeline being made by thread … for 29.2 s`; `libllvm-qgl.so+0x414a10`, `+0x2bca70`, `+0x2df500` in three runs: not one stuck instruction). With `DM_WATCH_STALL=600` (p10) the frame took 99.3 s and the game then went on at 50-60 fps: the compile ends. The shader's SPIR-V is 2.0 MiB (the median in the library is 152 KiB): 1,590 `OpImageFetch`, 26,391 `OpVariable`, 3,180 calls; GLSLC compute `main#GLSLC@0x2b9f4d0`, 64x1x1, 304 bytes of shared memory. The frame reported only 38 ms of process CPU and 162 major page faults, with 10.7-11.2 GiB of 12 GiB swap free falling: the compiler may be paging rather than computing (not measured per thread). Before it, graphics pipelines on the frame path took 340-1,116 ms. Not known: whether the driver's pipeline cache keeps it for the next boot (p10 saved its cache only before the stall), and what in the SPIR-V costs the compiler (the 26,000 function variables are the first suspect). Ways out, none tried: a smaller module from the translator (registers as SSA values, not one variable each), building the pipeline off the replay thread, or the prewarm list.
- Also on Android: `[RED] gfx: mtl_read_texels not yet on Android` once, three `texture ID … has no view of shape 0x1` (1D texture buffers `target 10`, `texture buffers are not images yet`), and a 256 KiB buffer across two parts of the large pool once a run.

## Driving it on the Mac

The first boot shows the language list: `A`, `LUp`, `A` picks English. The character choice takes `LLeft` before `A`. From a warm cache, `A A LLeft A A Plus` reaches walking in about 300 s. On later boots, `A` at the title screen a few times continues the save and reaches walking in about 110 s. The work folder has `run/boot.sh`, `run/hdrive.sh` (no window, timed control lines) and `run/spam.sh`.

## Open

- The picture has not been compared with a Switch 2. The user's playtest found it fine. Sunlit ground matches published iPhone shots of the game. Shaded ground and walls are lavender. The player carries fine contour lines, already present in colour target 5435, which may be the material. In some moving shots far blocks showed through near ones; this has not been rechecked since the fixes below.
- Only the first area has been played on this build. A new save written through the game has not been checked: the 31-minute playtest wrote none.
- An IPA has not been built. A full iOS shader build would be needed first.

## Found on this game

- [Large pools placed whole for one buffer in them](../../../docs/reference/memory.md#large-pools-placed-whole-for-one-buffer-in-them-android): killed before its first frame on the phone (resolved).
- [Large compressed NUP files decompressed into memory](../../../docs/reference/memory.md#large-compressed-nup-files-decompressed-into-memory-android) (resolved).
- [A log line inside a placed-memory swap stops every thread](../../../docs/reference/memory.md#a-log-line-inside-a-placed-memory-swap-stops-every-thread-android) (resolved).

- [KTGL on NVN2: Switch 2 GLSLC outputs and LD_ / LI_ LINKDATA](../../../docs/reference/shaders.md#ktgl-on-nvn2-switch-2-glslc-outputs-and-ld_--li_-linkdata): the split found almost none of its shaders.
- [HADD2 and HMUL2 with an immediate](../../../docs/reference/shaders.md#hadd2-and-hmul2-with-an-immediate): the GPU hang and 88 ms frames.
- [Scaled shared memory addresses](../../../docs/reference/shaders.md#scaled-shared-memory-addresses): stride 3 in use, on accesses of every size.
- [HFMA2 .H0_NH1](../../../docs/reference/shaders.md#hfma2-h0_nh1).
- [Ampere instructions new in Pokémon Pokopia](../../../docs/reference/shaders.md#ampere-instructions-new-in-pokémon-pokopia).
- [A shader build that keeps its packages in memory runs one worker at a time](../../../docs/reference/shaders.md#a-shader-build-that-keeps-its-packages-in-memory-runs-one-worker-at-a-time): the full build at one or two shaders a second (resolved).
- [Integer buffer textures read through float slots](../../../docs/reference/rendering.md#integer-buffer-textures-read-through-float-slots).
- [The Joy-Con mouse pointer](../../../docs/reference/services.md#the-joy-con-mouse-pointer) and [GameShare guest displays](../../../docs/reference/services.md#gameshare-guest-displays): boot aborts.
