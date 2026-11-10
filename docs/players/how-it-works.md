# How it works

Back to the [main page](../../.github/README.md).

Banana Native works in the spirit of Wine: it reproduces no console hardware. The game's own ARM64 code runs directly
on the phone's CPU, as an Android process. Banana Native supplies its own implementation of the system interfaces that
code calls (Horizon's system calls and the SDK's services: threads, memory, files, input, audio, video), translates the
game's graphics API (NVN, including the Switch 2's task and mesh shaders) to Vulkan, and translates its shaders to
SPIR-V ahead of time.

```
 PC, Mac or phone, once per game                  Phone
 your dump ─▶ pack_android.py ─▶ pack ─copy─▶  launcher (APK, no game code)
              rewrites the code for Android        host: threads, memory, files, input, audio, video
              translates shaders to SPIR-V         NVN on Vulkan
```

- `dm/tools/patch_nso.py --target android` rewrites a copy of the game's modules so they run as an Android process.
- `dm/gfx/tools` translates the shaders to SPIR-V ahead of time, with Ryujinx's shader translator.
- `dm/gfx/vk_android` replays the game's NVN command buffers on Vulkan.
- `dm/host` and `android/host` implement the system services the game calls; `android/app` is the launcher.
- `scripts/bananative.bat` (Windows) and `scripts/bananative.command` (Mac) set up the tools and open the packer's
  window, `scripts/pack_gui.py`, which runs `scripts/pack_android.py`.

Banana Native is an Android port of [nxnative](https://github.com/seedds/nxnative) by seedds, which does the same on
the Mac, iPhone and iPad with Metal ([nxnative's README](../nxnative.md)).

Building it yourself: [android/README.md](../../android/README.md). Working on the code: [AGENTS.md](../../AGENTS.md).
