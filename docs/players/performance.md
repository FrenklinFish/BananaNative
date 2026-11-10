# Performance

Back to the [main page](../../.github/README.md).

Donkey Kong Bananza (`040074A01BF12000`) on the tested phone (Snapdragon 8 Elite Gen 5, 16 GB), first level, frame
generation off:

| GPU driver | Resolution | Frame rate |
|---|---|---|
| System (Qualcomm) | Phone 1080p | 30 fps, with stretches of 55-58 |
| Turnip | Phone 1080p | 30 fps, with stretches of 49-59 |

Both drivers are held at 30 fps by the CPU (recording the frame's GPU work); 60 fps is planned.

Pokémon Pokopia (`0400A3A01EE4C000`): about 60 fps on the system driver and Turnip.

Mario Kart World (`0400C3F00006E000`): not measured yet.

Splatoon Raiders (`0400380020056000`, experimental): not playable yet, a very low frame rate.

Your numbers depend on the phone, its temperature and power mode. The first visit to an area stutters while its
shaders and pipelines are built; later visits are smoother. A hot phone throttles and the frame rate drops: a cooler
helps. Frame generation (Settings) can take 30 fps to a 60 / 90 / 120 fps target, with some added latency.

The measurements behind these numbers are in each game's note ([games table](../../dm/games/README.md#games)).
