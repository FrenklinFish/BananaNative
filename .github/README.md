<p align="center"><img src="../android/images/logo.svg" alt="Banana Native logo" width="128"></p>

# Banana Native

Switch 2 games, running natively on Android.

This is not an emulator. The game's own ARM64 code runs directly on the phone's CPU. A small host answers the system
calls the game makes, and its graphics (NVN, including the Switch 2's task and mesh shaders) are rebuilt on Vulkan.
As far as I know, it is the first time Switch 2 games run natively on Android.

Banana Native is an Android port of [nxnative](https://github.com/seedds/nxnative) by seedds, which does the same on
the Mac, iPhone and iPad with Metal.

> [!NOTE]
> Unofficial and experimental, tested on one phone. Not affiliated with Nintendo, nxnative or Eden: please don't send
> them bug reports about this. Not related to [nx2android](https://github.com/shirai91/nx2android) either: Banana
> Native is its own codebase, ported from nxnative, and not a fork of that project. Most of the code was written with
> an AI coding assistant (Claude) and tested by hand. Releases are only published on this page.

## Games

| Title ID | Version | Status |
|---|---|---|
| `040074A01BF12000` | update 458752 | Works |
| `0400C3F00006E000` | 1.8.0 (base game) | Work in progress: in game and driving, with graphics problems |

Only these versions run; the app tells you if your dump is a different one.

## Performance

`040074A01BF12000` on the tested phone (Snapdragon 8 Elite Gen 5, 16 GB), first level, frame generation off:

| GPU driver | Resolution | Frame rate |
|---|---|---|
| System (Qualcomm) | Phone 1080p | 30 fps, with stretches of 55-58 |
| Turnip | Phone 1080p | 30 fps, with stretches of 49-59 |

Both drivers are held at 30 fps by the CPU (recording the frame's GPU work); 60 fps is planned.

`0400C3F00006E000`: not measured yet. Your numbers depend on the phone, its temperature and power mode. The first visit to
an area stutters while shaders are built, and a hot phone throttles: a cooler helps. Frame generation (Settings) can
take 30 fps to a 60 / 90 / 120 fps target, with some added latency.

## What you need

- An Android 13+ phone with a Snapdragon (Adreno) GPU and at least 12 GB of RAM. Tested on a Snapdragon 8 Elite Gen 5 with 16 GB.
- A phone cooler is recommended, strongly for `0400C3F00006E000`.
- Tested only with the phone's own Qualcomm (system) GPU driver and the latest Turnip; other drivers may not work.
- Your own dump of the game as a folder. No keys or firmware are needed; NSP and XCI files don't work.
  ```
  <game>/
    base/                  exefs/ and romfs/
    updates/<version>/     the update, if the version above is one
    dlc/<title id>/<n>/    extra content, if you have it
  ```
- Optionally, your own `Lossless.dll` from Lossless Scaling, for frame generation.

## Setup

Each game is prepared once: its code is rewritten for Android and its shaders are translated to Vulkan. A Mac does
this in 10-15 minutes. The phone can do it alone, but it takes about an hour.

### With a Mac

```sh
brew install --cask dotnet-sdk                                   # plus micromamba
micromamba create -p ~/bananative-env -f android/environment.yml
~/bananative-env/bin/python scripts/pack_android.py /path/to/<game>
```

The last command writes the pack to `<title ID>-work/android-pack` next to your game folder. Copy the game folder to
the phone (say `Switch2/<game>`) and the pack next to it (`Switch2/<title ID>-work/android-pack`). Install the APK
from [Releases](https://github.com/moceure/BananaNative/releases), allow **All files access**, tap **Add game** and pick the game folder. It finds the
pack and is ready to play.

### Phone only

Copy the game folder to the phone, install the APK, **Add game**, then **Prepare**. It runs in the background and can
be paused; keep the phone on the charger. Phone-only setup works for `040074A01BF12000` for now: `0400C3F00006E000`
runs out of memory while its code is rewritten on the phone, so use a Mac for that one.

If something doesn't work, start with Settings › **Readiness check**. A game's ⋯ menu has **Share log**.

## Features

- **Fold-aware layout**: separate layouts for the outer and inner screen, switched live when you fold or unfold. Move
  the picture up or down and place the controls where you want them.
- **Frame generation** with Lossless Scaling (LSFG): 2x, 3x, 4x, or a 60 / 90 / 120 fps target.
- On-screen controls with a layout editor, gamepad support, frame-rate cap, resolution (1080p or native), performance
  overlay, Turnip driver downloads, save backups, and pack export / import (one .zip). Saves and packs live in the app's storage (uninstalling asks
  whether to keep them); backups go to `Switch2/save-backups/<title ID>`. Saves from 0.1.0 are copied in once.

## Known issues

- **Warning, `0400C3F00006E000` is a work in progress** and shows black pixels: after a race, going back to the
  character selection screen can turn the 3D scene dark or black (the menus and the race HUD still draw). Free roam
  alone is not affected. Being fixed.
- `040074A01BF12000`: some texture glitches during fights in some caves.
- `040074A01BF12000` runs at 30 fps on both drivers: the CPU's work per frame holds it there. 60 fps is a later task.
- `0400C3F00006E000` has more problems: trees flicker, ground textures are sometimes missing, and memory is tight on
  16 GB. It heats the phone a lot.
- The first visit to an area stutters while its shaders and pipelines are built; later visits are smoother.
- Phones get hot, and the frame rate drops when they throttle. Crashes happen.
- A new app version may need the shaders prepared again; the app says so.

## How it works

```
 Mac or phone, once per game                      Phone
 your dump ─▶ pack_android.py ─▶ pack ─copy─▶  launcher (APK, no game code)
              rewrites the code for Android        host: threads, memory, files, input, audio, video
              translates shaders to SPIR-V         NVN on Vulkan
```

- `dm/tools/patch_nso.py --target android` rewrites the game's modules so they run as an Android process.
- `dm/gfx/tools` translates the shaders to SPIR-V ahead of time, with Ryujinx's shader translator.
- `dm/gfx/vk_android` replays the game's NVN command buffers on Vulkan.
- `dm/host` and `android/host` stand in for the console's services; `android/app` is the launcher.

Building it yourself: [android/README.md](../android/README.md).

## Problems and questions

Something wrong on your phone: open an [issue](https://github.com/moceure/BananaNative/issues/new/choose) with the form
(phone, chip and RAM, GPU driver, game version, and the log from Settings › **Share log**). Every report from a phone
other than the tested one helps. Questions, ideas and "it runs on my phone" reports go to
[Discussions](https://github.com/moceure/BananaNative/discussions).

> [!IMPORTANT]
> **This GitHub repository is the only official place for Banana Native.** The project has no Discord server,
> subreddit, Telegram group, X / social media account or website, and nobody speaks for it anywhere else. Download the
> APK only from [Releases](https://github.com/moceure/BananaNative/releases): a copy from anywhere else may be modified
> or malicious. Nobody connected to the project will ask you for money, keys, game files or account details. To reach
> the developer, use issues or Discussions here.

## Videos and device reports

Videos are welcome. To make yours useful to viewers:

- Say the app version (for example v0.1.0), the phone, its chip and RAM, and the GPU driver.
- Turn on the performance overlay so the frame rate is visible, and say whether frame generation is on.
- Mention the [known issues](#known-issues) you run into, so viewers know which ones are already being fixed.
- Link this page for downloads: releases are only published here, and copies elsewhere may be modified.
- Please don't show or link game dumps, keys or firmware.

Post your video or your "it runs on my phone" report in
[Discussions](https://github.com/moceure/BananaNative/discussions): every phone other than the tested one helps, and
good videos get linked here.

## History

- **v0.1.0, 4 November 2026**: first public release, with `040074A01BF12000` playable and `0400C3F00006E000` in
  progress.
- **v0.1.1, 5 November 2026**: `.nup` files, emulator frontend launch, incremental shader updates (v0.1.0 packs are
  taken over without a rebuild), phone Prepare and about 1 GiB less RAM for `0400C3F00006E000`, RAM in the in-game stats, fewer stutters and less memory
  for large memory pools and large `.nup` files, saves and packs in the app's storage with pack export / import.
- **v0.1.2, 5 November 2026**: hotfix for Prepare on shared storage, driver install, Add game and Turnip GPU clocks;
  `040074A01BF12000` at Phone 720p by default on Turnip as a stopgap.
- **v0.1.3, 5 November 2026**: `040074A01BF12000` at 30 fps on Turnip at Phone 1080p (was about 20): faster upscaler
  shader on Adreno and a GPU clock floor for Turnip; Phone 1080p and dynamic resolution are the defaults again.

## License and credits

nxnative is MIT. The Android app is **GPL-3.0-or-later** as a whole, because it includes frame generation ported from
[Eden](https://git.eden-emu.dev/eden-emu/eden) / [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) and mesh shader
lowering from [switch2mac](https://github.com/azmasm/switch2mac) / [Eglantine](https://github.com/djihads80/eglantine).
Every release is built from its tagged commit. See [android/NOTICE.md](../android/NOTICE.md).

Built on [nxnative](https://github.com/seedds/nxnative) (seedds) and
[d2r-mobile](https://github.com/jhaizhou-ops/d2r-mobile), with findings from
[kartnative](https://github.com/pahorema/kartnative) (pahorema) and switch2mac (azmasm), and
[Ryujinx](https://github.com/Ryujinx/Ryujinx)'s shader translator. Thanks to all of them.

Use games you own. Please don't ask for games, keys or dumps.
