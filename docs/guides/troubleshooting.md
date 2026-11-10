# Troubleshooting

Back to the [guides](README.md).

## Readiness check

Settings › **Readiness check** checks whether this phone can run your games: Android, memory, file access, storage,
the GPU and its driver, and each game's own needs. An item that needs fixing has a button that opens the right place
(for example **Allow file access**). **Run again** checks again; **Share** and **Copy report** give you the report for
an issue.

When a game would not start, the app says so before it starts, with the reason.

## Share log

Each game start writes a log. To send it:

- the game's ⋯ button › **Share log**: that game's newest log, or
- Settings › **Share log**: the newest log of any game.

## Common problems

- **The game is not on the list or says "Not in this app version":** your app does not include that game. Settings ›
  **About** lists the games it has.
- **The card says the version is untested:** your dump is another version than the one the game was tested with. It
  may still work; the app asks before it starts.
- **Preparing stopped:** its card says why and offers **Retry**. Close other apps if the phone ran out of memory, and
  free space if the storage is full.
- **The picture is wrong or the game crashes:** try the other GPU driver ([GPU drivers](gpu-drivers.md)).
- **The phone gets hot and the frame rate drops:** let it cool down; a phone cooler helps.

## Opening an issue

Open an [issue](https://github.com/moceure/BananaNative/issues/new/choose) with the form. It asks for:

- what happened, and what you did just before;
- the phone, its chip and RAM, and the Android version;
- the GPU driver (Settings › **GPU driver**) and its version if it is a custom one;
- the game's title ID and version, and the Banana Native release;
- the Readiness check result (**Copy report**);
- the log (**Share log**).

Screenshots or a short video help for a wrong picture. Questions and "it runs on my phone" reports go to
[Discussions](https://github.com/moceure/BananaNative/discussions). Please don't attach, offer or link game files, keys
or firmware.
