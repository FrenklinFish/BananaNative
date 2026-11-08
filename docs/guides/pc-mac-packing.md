# Preparing a game on a PC or a Mac

Each game is prepared once: its code is rewritten for Android and its shaders are translated for Vulkan. The result is
the game's **pack**. A computer does this much faster than the phone: a Mac prepares Donkey Kong Bananza
(`040074A01BF12000`) in about 10-15 minutes. Back to the [guides](README.md).

## 1. Get the packer

Get this repository from GitHub (clone it, or download it). The packer is in its `scripts` folder:

- **Windows:** `scripts\bananative.bat`. New and not yet tested on a real PC — please report how it goes.
- **Mac:** `scripts/bananative.command`. Tested.

## 2. First start

Double-click the packer (or run it in a terminal). The first time, it asks for a folder for its tools and installs them
there by itself: Python, the shader tools and the .NET runtime, about 400 MB. The drive with your dumps is a good
place. If you already have a working environment, it uses that one. Later starts open the window right away.

## 3. Prepare the game

1. In the window, pick your game: **.nup file…** or **Dump folder…** (the folder with `base/`). The window shows what
   it found and checks its tools.
2. Click **Prepare**. The progress bar follows the code rewrite, the shader split and the shader build. **Stop** ends
   it; the next run goes on from where it stopped.
3. When it is done, the pack is in `<title ID>-work/android-pack` next to the game folder, for example
   `Switch2/040074A01BF12000-work/android-pack` (about 250-400 MB). **Show pack** opens it.

The same from the command line:

```sh
scripts/bananative.command pack /path/to/Switch2/DonkeyKongBananza     # Mac
scripts\bananative.bat pack D:\Switch2\DonkeyKongBananza               # Windows
```

## 4. Get the pack onto the phone

Copy the game folder to the phone as well (the app reads the game from it), for example to `Switch2/DonkeyKongBananza`.
Then either:

- **Copy the pack beside the game:** copy `android-pack` to the phone as
  `Switch2/040074A01BF12000-work/android-pack`, next to the game folder. Only `android-pack`: the rest of the `-work`
  folder stays on the computer. Then, in the app, tap **Add game** and pick the game folder: the app finds the pack and
  the game is ready to play.
- **Or import it:** copy the `android-pack` folder anywhere on the phone. In the app, tap **Add game** and pick the
  game folder. The app asks **Prepare …?**: tap **Import pack…** › **A pack folder (with pack.tsv and shaders/)** and
  pick the folder. The app copies the pack into its own storage, so you can delete the copied folder afterwards.

For a game that is already on the list, the game's ⋯ button › **Import pack…** does the same. **A .zip file (made by
Export pack…)** takes a pack exported from another phone ([Packs and app updates](packs-and-updates.md)).

The app also offers **Use an existing pack…** in the same question: it uses a pack folder where it is, without copying.

## If it fails

- The window's log and its tool check say what is missing. `scripts/bananative.command check` (or
  `scripts\bananative.bat check`) checks the tools again.
- Open an issue as described in [Troubleshooting](troubleshooting.md), with the packer's log.
