# History

Back to the [main page](../../.github/README.md).

- **v0.1.0, 4 November 2026**: first public release, with Donkey Kong Bananza (`040074A01BF12000`) playable and Mario
  Kart World (`0400C3F00006E000`) in progress.
- **v0.1.1, 5 November 2026**: `.nup` files, launching from frontends, incremental shader updates (v0.1.0 packs are
  taken over without a rebuild), phone Prepare and about 1 GiB less RAM for Mario Kart World (`0400C3F00006E000`), RAM
  in the in-game stats, fewer stutters and less memory for large memory pools and large `.nup` files, saves and packs
  in the app's storage with pack export / import.
- **v0.1.2, 5 November 2026**: hotfix for Prepare on shared storage, driver install, Add game and Turnip GPU clocks;
  Donkey Kong Bananza (`040074A01BF12000`) at Phone 720p by default on Turnip as a stopgap.
- **v0.1.3, 5 November 2026**: Donkey Kong Bananza (`040074A01BF12000`) at 30 fps on Turnip at Phone 1080p (was about
  20): faster upscaler shader on Adreno and a GPU clock floor for Turnip; Phone 1080p and dynamic resolution are the
  defaults again.
- **v0.1.4, 5 November 2026**: in-app updates (a popup at start when a new release is out; Settings › Check for
  updates), Prepare's code step in a fifth of the memory (fixes Mario Kart World (`0400C3F00006E000`) stopping at
  rewriting the game code, #20), no false "Pack from other tools" chip.
- **v0.1.5, 5 November 2026**: Pokémon Pokopia (`0400A3A01EE4C000`) playable at about 60 fps on the system driver and
  Turnip, with Game resolution working for it (thanks to [Raphix91](https://github.com/Raphix91), #28 #29 #30); a text
  box for games that ask you to type; shaders already on the phone carry over without an update.
- **v0.2.0**: preparing a game on a PC (Windows) or a Mac in a window (`scripts/bananative.bat`,
  `scripts/bananative.command`), **Import pack…** in the app for packs made elsewhere, and two experimental games: The
  Legend of Zelda: Ocarina of Time (`0400486024DEC000`) and Splatoon Raiders (`0400380020056000`).
