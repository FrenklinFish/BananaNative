# Banana Native

> [!NOTE]
> The page GitHub shows for this repository is [.github/README.md](.github/README.md): what Banana Native is, the games,
> and how to set it up. This file is the short version for anyone who opens the repository's root.

Banana Native runs Switch 2 games from your own dumps on Android phones, in the spirit of Wine: the game's own code runs
directly on the phone's CPU, Banana Native implements the system services it calls, and the game's graphics are
translated to Vulkan. It is the Android port of [nxnative](https://github.com/seedds/nxnative) by seedds. It needs only a
dump of a game you own, made from your own console, and no keys; nothing from any game is in this repository or in the
app, and the project is not affiliated with or endorsed by Nintendo, any game publisher, nxnative or Eden.

- **Players:** [.github/README.md](.github/README.md) (games, setup on a PC, a Mac or the phone alone, problems) and
  the [guides](docs/guides/README.md).
- **Building the app:** [android/README.md](android/README.md); tests on the phone: [android/TESTING.md](android/TESTING.md).
  Releases are built with `-PnxLive=true` and the release key (never in this repository).
- **Working on the code:** [AGENTS.md](AGENTS.md), the [game notes](dm/games/README.md#games) and [docs/agents](docs/agents/README.md).
- **Upstream nxnative** (Mac, iPhone and iPad, with Metal), whose tools this repository keeps: [nxnative's README](docs/nxnative.md).

## Licence and credits

The Android app is **GPL-3.0-or-later** as a whole ([android/NOTICE.md](android/NOTICE.md)), because it includes frame
generation ported from [Eden](https://git.eden-emu.dev/eden-emu/eden) / [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)
and task and mesh shader lowering from [switch2mac](https://github.com/azmasm/switch2mac) /
[Eglantine](https://github.com/djihads80/eglantine). Each release is built from its tagged commit. nxnative's own files
keep their MIT licence ([LICENSE](LICENSE)); third-party code keeps its own ([dm/third_party](dm/third_party/README.md)),
and the shader translator built from it is GPL-3.0-or-later ([dm/third_party/ryujinx](dm/third_party/ryujinx/README.md)).
A few shader translator fixes are adapted from [nx2android](https://github.com/shirai91/nx2android) (MIT), credited in
[android/NOTICE.md](android/NOTICE.md).

Built on **[nxnative](https://github.com/seedds/nxnative)** by seedds (MIT) and everything it credits in its
[acknowledgements](docs/nxnative.md#acknowledgements), among them [d2r-mobile](https://github.com/jhaizhou-ops/d2r-mobile)
and [Ryujinx](https://github.com/Ryujinx/Ryujinx)'s shader translator. Mario Kart World (`0400C3F00006E000`) findings
from [kartnative](https://github.com/pahorema/kartnative) (pahorema) and switch2mac (azmasm). Thanks to all of them and
to everyone who reported issues or sent pull requests.

Only use games you own. Don't ask for games, keys or dumps.
