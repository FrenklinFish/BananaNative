# Experimental games

Experimental games start, but they are little tested and have known problems; some are not playable yet. Reports and help are welcome.
Back to the [guides](README.md).

## The Legend of Zelda: Ocarina of Time (`0400486024DEC000`)

- **Driver:** the phone's own Qualcomm driver (System) or Turnip ([GPU drivers](gpu-drivers.md)). On Qualcomm's driver the game page recommends
  the system driver, the Performance preset and Full vegetation off: tap **Recommended** at the top of its ⋯ page.
- **Version:** only version 2.0 is tested. Other versions get a warning, and you can still try them.
- **State:** plays. The foliage is drawn on every driver.
- **Known problems:** Kokiri Forest runs at about 26-30 fps on Qualcomm's driver (indoors about 50); Castle Town on Turnip is slow on its first visits.
- **Slow first starts:** the GPU driver builds its pipelines the first time it sees each shader. The first start can sit on the loading
  screen for minutes, and the first minutes of play (especially right after the main menu) can run at 1-20 fps with black frames while
  it catches up. Let it run, or restart the game: each start is faster, and once the cache is full it runs at 30-60 fps. A hot phone
  makes this slower. Changing the GPU driver starts the cache again for that driver.
- Details for contributors: [its game note](../../dm/games/0400486024DEC000/README.md).

## Star Fox (`0400E88025B1A000`)

- **State:** new. It boots and reaches its first scenes on the system driver and on Turnip; not tested further.
- **Memory:** it needs a lot (on the Mac about 8.5 GB); a phone with 16 GB is the safe choice.
- Details for contributors: [its game note](../../dm/games/0400E88025B1A000/README.md).

## Splatoon Raiders (`0400380020056000`)

- **State:** not playable yet. It boots and runs, but at a very low frame rate. It ships so that people can test it and help.
- **Known problems:** the frame rate; little tested beyond that. A white scene was seen on the system driver.
- Details for contributors: [its game note](../../dm/games/0400380020056000/README.md).

## How to help

- **Play and report.** Open an issue ([Troubleshooting](troubleshooting.md#opening-an-issue)) with the log (**Share
  log**), your phone model and the GPU driver you used, and where in the game it happened.
- **Reference screenshots.** A screenshot from the console of the same place, next to one from the phone, shows what
  is wrong in the picture.
- **Try the other driver** when something looks wrong, and say whether it changes.
- **Code.** Pull requests are welcome. Each game's note says what is known and what is open; the
  [games table](../../dm/games/README.md#games) lists them all, and [docs/agents](../agents/README.md) explains how to
  debug a game. Issues marked
  [help wanted](https://git.eden-emu.dev/moceure/BananaNative/issues?state=open&labels=help+wanted) are a
  good start.
