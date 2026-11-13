# Banana Native: license notice for the Android build

Banana Native (the Android launcher in `android/app` and the host it builds, `libnxhost.so`) is based on [nxnative](https://github.com/seedds/nxnative) by seedds,
which is MIT-licensed ([LICENSE](../LICENSE)).

**The Android build that includes LSFG is GPL-3.0-or-later.** By default the host links Lossless Scaling frame generation, a C port of
[Eden](https://git.eden-emu.dev/eden-emu/eden)'s implementation (itself based on [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)), in
[`dm/gfx/vk_android/lsfg`](../dm/gfx/vk_android/lsfg/LICENSE.md), licensed GPL-3.0-or-later. An APK or `libnxhost.so` built with it is therefore distributed as a
whole under the GNU General Public License, version 3 or later ([COPYING](../dm/gfx/vk_android/lsfg/COPYING)); whoever passes such an APK on must offer its
complete source (this repository at the commit in the build tag). The MIT files keep their MIT license on their own; the rest of nxnative (the Mac and iPhone
builds) stays MIT. `./gradlew apk -PnxLsfg=false` builds an APK without LSFG; it is still GPL-3.0-or-later as a whole when it carries the on-device packer, whose translator is GPL too (below).

**The shader translator is GPL-3.0-or-later as a whole.** It translates Switch 2 task and mesh programs with
[`InstEmitAmpereMesh.cs`](../dm/third_party/ryujinx/src/Ryujinx.Graphics.Shader/Instructions/InstEmitAmpereMesh.cs), ported from
[switch2mac](https://github.com/azmasm/switch2mac)'s `nx2_lower` (from [Eglantine](https://github.com/djihads80/eglantine)), GPL-3.0-or-later
([COPYING.GPL-3.0](../dm/third_party/ryujinx/COPYING.GPL-3.0)). The on-phone translator `libdmsh_xlate.so` and the Mac `dmsh_xlate_bin` are built with it, so
they are combined works under the GPL (see [dm/third_party/ryujinx/README.md](../dm/third_party/ryujinx/README.md)); the shader libraries they produce are the
games' own shaders translated, not part of this code. The other translator files keep their MIT license. Every APK that carries the on-device packer (`libdmsh_xlate.so`) is
therefore a GPL-3.0-or-later work whatever `nxLsfg` says, and the Mac and iPhone builds stay MIT only as apps: the committed translator `dm/gfx/tools/dmsh_xlate_bin` they run to
build shader libraries is the same GPL combined work.

**Mesh draws through compute are GPL-3.0-or-later.** On a device without `VK_EXT_mesh_shader` the host draws NVN2 task and mesh programs through compute with
[`dm/gfx/vk_android/vka_memu.c`](../dm/gfx/vk_android/vka_memu.c), `vka_memu_spv.c`, `vka_memu_draw.inc`, `vka_memu_abi.h` and the passes in `memu/`, after
[Hokkaido](https://github.com/pavelgajdo/Hokkaido)'s compute mesh emulation (GPL-3.0-or-later; its #569 and #642), so every `libnxhost.so` is a GPL-3.0-or-later work as a whole.

File-backed memory pools (the launcher's per-game **RAM saver (uses storage)**, `DM_MEM_FILEBACK`) come from [Anka Native](https://github.com/EddizEge/AnkaNative)
v0.2.2 by EddizEge (MIT, "Copyright (c) 2026 EddizEge" with the MIT permission notice in [LICENSE](../LICENSE)): `dm/gfx/vk_android/vka_fileback.h` and its test
`dm/host/tests/t_android_fileback.c` as they were there, the "File-backed pools" part of `dm/gfx/vk_android/vka_mem.c` with its hook in `dm/gfx/nvn/hle_nvn.c`, and the
"Shader-code pools" part of `dm/gfx/nvn/hle_nvn.c` (`DM_VK_PLACE_SHADER_POOLS`, `DM_VK_SHPOOL_LOG`, `nvn_shader_code_readable`) with its hooks in the same file, fitted to
this host (here the default for every game on Android). Thanks to them.

Some fixes follow other projects' findings without copying their code: [kartnative](https://github.com/pahorema/kartnative) by pahorema (MIT) for
GPU-written sparse textures (`dm/gfx/vk_android/vka_tex.c`) and the per-primitive header of mesh programs (`dm/gfx/tools/shader_split.py`), and
[switch2mac](https://github.com/azmasm/switch2mac) by azmasm (MIT outside `nx2_lower`) for the SPIR-V mesh entry points marked in the translator.. The translator's handling of
atomics in `AstOptimizer.cs`, of else-ifs for SPIR-V and of TMML's negative LOD, and the Android gather bias, follow
[nx2android](https://github.com/shirai91/nx2android) by Tung Pham (MIT, as nxnative: "Copyright (c) 2026 seedds" and the MIT permission notice in
[LICENSE](../LICENSE)), with the TMML finding from yuzu #10234 (no yuzu code); the changes are listed in
[dm/third_party/ryujinx/README.md](../dm/third_party/ryujinx/README.md). Thanks to them.

The launcher's gate on adb control commands (`AdbControl.java`, Settings › ADB control in `MainActivity.java` and `Prefs.java`) is ported from
[Anka Native](https://github.com/EddizEge/AnkaNative) 0.2.2 by EddizEge (MIT: "Copyright (c) 2026 seedds", "Copyright (c) 2026 EddizEge" and the MIT permission notice
in [LICENSE](../LICENSE)). Thanks to them.

The texture pool's read reports (`DM_VK_POOL_FB`: `vka_spv_pool_fb` in `dm/gfx/vk_android/vka_spvpatch.c`, "Texture pool reads" in `vka_tex.c`) follow the design of
[Anka Native](https://github.com/EddizEge/AnkaNative) 0.2.2's texture-use feedback by EddizEge (MIT: "Copyright (c) 2026 seedds", "Copyright (c) 2026 EddizEge" and the MIT
permission notice in [LICENSE](../LICENSE)): a bit per texture ID set before each read of the pool, the bitmap copied out and cleared at each submission's end. Written
anew for this host, which rewrites the modules at pipeline time instead of in the shader library. Thanks to them.

The on-device packer's Python home (`assets/pack/python.zip`) carries, besides the [CPython](https://www.python.org/) Android build (PSF License),
[Capstone](https://www.capstone-engine.org/) 5.0.7 (BSD-3-Clause) and [python-lz4](https://github.com/python-lz4/python-lz4) 4.4.5 (BSD-3-Clause, with LZ4's BSD-2-Clause
sources). Since 0.2.2 the APK also carries `lib/arm64-v8a/libzbicdec.so`, which decodes the ZBIC segments of system 22.0.0+ modules for the packer's
`dm/tools/nso.py`: [Zstandard](https://github.com/facebook/zstd) 1.5.7's decoder by Meta (BSD; zstd is BSD or GPL-2.0, the BSD terms are taken), unmodified, with
an independent reader of ZBIC's entropy tables and the ZBIC frame magic (MIT, Banana Native), written from a description of the format with no code from any other
ZBIC implementation ([dm/third_party/zstd-zbic](../dm/third_party/zstd-zbic/README.md), zstd's licence in its `LICENSE`). Thanks to
[Atmosphère](https://github.com/Atmosphere-NX/Atmosphere) and Yannik Marchand's [zbic](https://github.com/kinnay/zbic) for discovering and documenting the format.
On a PC or Mac the packer may instead use the zbic package from PyPI (GPL-2.0), which the user installs (`android/environment.yml` lists it); it is not in the APK.

The Upscaler choice **SGSR 1** (`DM_VK_NDSR=4`) runs Qualcomm's [Snapdragon Game Super Resolution 1](https://github.com/SnapdragonStudios/snapdragon-gsr),
ported to a compute shader in [`dm/gfx/vk_android/sgsr/ndsr_sgsr.comp`](../dm/gfx/vk_android/sgsr/ndsr_sgsr.comp) and built into the host as SPIR-V
(`dm/gfx/vk_android/vka_ndsr_sgsr.h`), under the BSD 3-Clause License:

> Copyright (c) 2023, Qualcomm Innovation Center, Inc. All rights reserved.
>
> Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation
>    and/or other materials provided with the distribution.
> 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software
>    without specific prior written permission.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
> IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
> LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
> GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
> LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

The shader file itself carries Qualcomm's 2025 header of the file it was ported from (`sgsr1_shader_mobile_edge_direction.frag`, SPDX-License-Identifier: BSD-3-Clause). Thanks to them.

The Vulkan replay's stand-in for NVN2's DLSS (`dm/gfx/vk_android/vka_dlss.c`) runs Qualcomm's [Snapdragon Game Super Resolution 2](https://github.com/SnapdragonStudios/snapdragon-gsr)
(GSR2) 3-pass shaders, adapted to compute passes in [`dm/gfx/vk_android/gsr2`](../dm/gfx/vk_android/gsr2/LICENSE) and compiled into the host (`gsr2/vka_gsr2_spv.h`):
Copyright (c) 2023-2024, Qualcomm Innovation Center, Inc. All rights reserved, under the BSD-3-Clause license, whose full text, conditions and disclaimer are in
[`dm/gfx/vk_android/gsr2/LICENSE`](../dm/gfx/vk_android/gsr2/LICENSE) and apply to the shaders in source and binary form. Its FSR 1 mode uses AMD's FidelityFX FSR 1
headers (MIT, [dm/third_party/fidelityfx-fsr1](../dm/third_party/fidelityfx-fsr1/LICENSE.txt)), as the present's FSR 1 does. Its FSR 3.1 mode (`vka_fsr3.c`) runs AMD FidelityFX
Super Resolution 3.1's upscaler passes from the FidelityFX SDK 1.1.4, compiled to SPIR-V (`dm/gfx/vk_android/fsr3/*.h`): Copyright (c) Advanced Micro Devices, Inc., under the MIT
license in [dm/third_party/fidelityfx-fsr3](../dm/third_party/fidelityfx-fsr3/LICENSE.txt).

`Lossless.dll` belongs to Lossless Scaling (proprietary, sold separately). It is never in this repository or in an APK: the user supplies their own copy, which the
launcher finds on the phone or lets the user pick, and copies into the app's private files.

Custom GPU drivers are never in this repository or in an APK either. The launcher's **Download…** (`DriverFetch.java`) fetches a driver zip only when the user
taps it, from the public GitHub release pages of [MrPurple666/purple-turnip](https://github.com/MrPurple666/purple-turnip),
[K11MCH1/AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers), [whitebelyash/freedreno_turnip-CI](https://github.com/whitebelyash/freedreno_turnip-CI),
[Weab-chan/freedreno_turnip-CI](https://github.com/Weab-chan/freedreno_turnip-CI), [crueter/GameHub-8Elite-Drivers](https://github.com/crueter/GameHub-8Elite-Drivers)
and [StevenMXZ/Adreno-Tools-Drivers](https://github.com/StevenMXZ/Adreno-Tools-Drivers);
thanks to their authors. Each download keeps its own licence: Mesa's Turnip driver is MIT-licensed, Qualcomm's Adreno driver binaries are proprietary.

**The optional Mario Kart World camera and 2D layer aspect (`dm/host/android/mkw_wide.c`, with the five gates in `dm/games/0400C3F00006E000/patches.py`) is GPL-3.0-or-later.**
The sites (the camera aspect stores, DrawInfo's projection getter calls and the rows read of 1.8.0) and the idea of widening a view's aspect where it is stored and keeping the 2D layer in the middle 16:9 come from adriadam10's [mkw-recomp](https://github.com/adriadam10/mkw-recomp) (MIT; `recomp/patches-mkw-180.txt`, `runtime/core/boot/patches.c`). The Android file re-expresses them as host callbacks on rewriter gates (the original instruction of a site is a branch in two cases, so the gates sit on the instruction before it), keeps the game's frame 16:9 and adds the vertical field-of-view correction for pictures narrower than 16:9. It lives under `dm/host/android/` so the Mac and iPhone builds do not include it.

**The optional Ocarina of Time camera and 2D layer aspect (`dm/host/android/oot_wide.c`, with the eight gates in `dm/games/0400486024DEC000/patches.py`) is GPL-3.0-or-later.**
The camera, HUD, movie and save picture sites (the UI, movie and matrix sites are Ivan0xFF's, the save picture and the narrow-screen HUD Hookshot's own) and the idea of replacing the 16:9 ratio where the projection is read come from Jbuck7's GameBanana mod 737495 (Ocarina of Time 1.1.1 ultrawide), carried to the
NX2navi patch list `patches-v111.txt` by Ivan0xFF (MIT) and to [`nx2/oot_wide.py`](https://github.com/NotRexed/Hookshot) of Hookshot by NotRexed (GPL-3.0). The Android file re-expresses
them as host callbacks on rewriter gates and adds the vertical field-of-view correction for pictures narrower than 16:9. It lives under `dm/host/android/` so the Mac and iPhone builds do not include it.

**The optional Donkey Kong Bananza camera and 2D layer aspect (`dm/host/android/dkb_wide.c`, with the three gates in `dm/games/040074A01BF12000/patches.py`) is GPL-3.0-or-later.**
The idea of changing the scene camera's aspect and of scaling the UI projection where it is copied into the draw info (two of their four sites, `main+0x45f9dc` and `main+0x803c94`, are the layout projection setups gated here) come from the `dkbwide` patch set of Lucas-blip-png's
[dkb-pc-recomp](https://github.com/Lucas-blip-png/dkb-pc-recomp) (`recomp/patches-dkb458752.txt`, GPL-3.0). The Android file re-expresses them as host callbacks on rewriter gates, reads the camera's
values where the game uses them, squeezes the UI into the middle of the unchanged 16:9 frame instead of widening it, and adds the vertical field-of-view correction for pictures narrower than 16:9.
It lives under `dm/host/android/` so the Mac and iPhone builds do not include it. The shared parts of both games' callbacks are in `dm/host/android/wide_shape.h`.

**The optional Pokopia world-camera correction is GPL-3.0-or-later.**
[`dm/host/android/pkp_aspect.c`](../dm/host/android/pkp_aspect.c) adapts the camera/FOV handling in
[`runtime/games/pkp/render/pkp_aspect.cpp`](https://github.com/Dittobyte/Recompia/blob/75dae2f2ccdada74e6b8ba12ea7de30560a84221/runtime/games/pkp/render/pkp_aspect.cpp)
from Recompia revision `75dae2f2ccdada74e6b8ba12ea7de30560a84221`, whose file carries `SPDX-License-Identifier: GPL-3.0-or-later`.
The Android adaptation keeps the game's original aspect field and corrects its completed projection. The GPL implementation lives under
`dm/host/android/` so the Mac and iPhone host builds do not include it; the MIT files retain their own license.
