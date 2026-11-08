# GPU drivers

The game's graphics run on a Vulkan driver: the phone's own Qualcomm driver (**System**) or a custom driver such as
Turnip (the open-source Mesa driver for Adreno GPUs). Banana Native is tested with both, on a Snapdragon 8 Elite Gen 5.
Back to the [guides](README.md).

## Which one to use

| Game | Driver |
|---|---|
| Donkey Kong Bananza (`040074A01BF12000`) | both: about 30 fps on each |
| Mario Kart World (`0400C3F00006E000`) | not compared yet |
| Pokémon Pokopia (`0400A3A01EE4C000`) | both: about 60 fps on each |
| The Legend of Zelda: Ocarina of Time (`0400486024DEC000`, experimental) | **System only** |
| Splatoon Raiders (`0400380020056000`, experimental) | a white scene was seen on System: if you see it, try Turnip |

If a game draws wrongly or crashes on one driver, try the other one and say which you used when you report it
([Troubleshooting](troubleshooting.md)).

## Install Turnip

1. Settings › **GPU driver** › **Install…**
2. **Download…** lists Turnip releases to download (only when you tap it), or **Pick a zip…** takes a driver zip you
   already have (a zip with a `meta.json`, as other Android emulators use).
3. The installed driver appears in the list.

## Pick a driver

- **For every game:** Settings › **GPU driver** and pick **System (the phone's own driver)** or an installed driver. It
  takes effect at the next game start.
- **For one game:** the game's ⋯ button › **Game settings** › **GPU driver**: **Default** (the one from Settings),
  **System**, or an installed driver.
- **Remove a driver:** Settings › **GPU driver** › **Remove picked…** deletes the app's copy of the picked driver; the
  game then uses the system driver.

Settings › **Readiness check** shows the GPU and the driver picked. The developer details are in
[android/README.md, Custom GPU drivers](../../android/README.md#custom-gpu-drivers).
