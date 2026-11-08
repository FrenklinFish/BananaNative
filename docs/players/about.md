# About the project

Back to the [main page](../../.github/README.md).

Unofficial and experimental, tested on one phone. Not affiliated with Nintendo, nxnative, Eden or
[nx2android](https://github.com/shirai91/nx2android): please don't send them bug reports about this. Banana Native is
its own codebase, ported from [nxnative](https://github.com/seedds/nxnative), and not a fork of nx2android; a few shader
translator fixes are adapted from nx2android (MIT), credited in [NOTICE](../../android/NOTICE.md). Most of the code was
written with an AI coding assistant (Claude) and tested by hand. Releases are only published on the project's GitHub
page.

## What it contains, and what it needs

- **No Nintendo code, firmware, BIOS or keys.** The repository and the app contain none, and no game files are
  distributed with them.
- **No console keys, no decryption of console containers.** Nothing in the project reads console keys or decrypts NSP,
  XCI or NCA files: it reads only a dump folder (or a `.nup` file of one) that already holds the game's `exefs/` and
  `romfs/` files, and rewrites a copy of the game's code on your own machine.
- **Your own dump.** You need a dump of a game you own, made from your own console. The project does not explain how
  to obtain games and does not help with it.
- **Not affiliated with or endorsed by Nintendo.** Nintendo Switch, Switch 2 and the game names are trademarks of
  their owners and are used here only to say which software runs.
- **Project rules.** No support for a title before its official release date, and no monetization of any kind (no
  donations, no paid or early builds), ever.

## The only official place

**The [GitHub repository](https://github.com/moceure/BananaNative) is the only official place for Banana Native.** The
project has no Discord server, subreddit, Telegram group, X / social media account or website, and nobody speaks for it
anywhere else. Download the APK only from [Releases](https://github.com/moceure/BananaNative/releases): a copy from
anywhere else may be modified or malicious. Nobody connected to the project will ask you for money, keys, game files or
account details. To reach the developer, use [issues](https://github.com/moceure/BananaNative/issues) or
[Discussions](https://github.com/moceure/BananaNative/discussions).

Licence and credits: [README](../../README.md#licence-and-credits).
