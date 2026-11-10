# Emulator frontends

Banana Native starts a game for an emulator frontend (ES-DE, Daijisho, Beacon, Pegasus, or anything that runs `am start`). The frontend lists your `.nup` files ([NUP.md](NUP.md)) and sends the one you pick to the app. The app's component for this is:

```
dev.bananative.android/dev.bananative.android.Launch
```

`Launch` is an alias of `FrontendLaunch`, and its name follows the application ID, so the test app's is `dev.nxnative.android/dev.nxnative.android.Launch`. The alias, and `Open` for file managers, are the only exported parts of the app that start games.

## What the app accepts

| Form | Example |
|---|---|
| `ACTION_VIEW` with a storage (SAF) URI of the file, from a folder picked in the frontend | `content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fgame.nup` |
| `ACTION_VIEW` with a provider URI whose path carries the file's path (a FileProvider "root" path) | `content://<frontend>.fileprovider/root/storage/emulated/0/ROMs/game.nup` |
| `ACTION_VIEW` with a `file://` URI | `file:///storage/emulated/0/ROMs/game.nup` |
| The extra `game`: the file's path | `-e game /storage/emulated/0/ROMs/game.nup` |
| The extra `title_id`: a game that is already on Home | `-e title_id 040074A01BF12000` |

The extra `game` with the file's path is the most reliable form. The app opens games by path, so a URI is only turned back into a path (`PathUtil.documentToPath`, `Frontend.embeddedPath`). A provider URI that carries no path (Recent, a MediaStore number, a cloud file) is refused and the app says why. The `/sdcard` and `/storage/self/primary` aliases are the same files as `/storage/emulated/0` (`Frontend.normal`). A game folder works too, given by its path, but frontends list files, so `.nup` is the format for them. A frontend cannot choose the pack folder or the save folder: those extras are only adb's ([TESTING.md](TESTING.md)).

## What happens

1. When the game's card is ready to play, the game starts at once and nothing of the launcher is shown. That means the game is on Home, or its pack is found beside it as Add game finds it, and the pack fits: this game build, finished code, this app's shader library, no save backup or restore running. A new file that is ready gets its card added first. When you stop the game (the in-game panel › Stop the game, or Back on a failed start), the frontend comes back.
2. Anything that needs you opens Home with the game, as if you had tapped Play or picked it with Add game: no pack, Prepare, shaders to make or update, a pack that does not fit, All files access. After that, start it again from the frontend.

All files access must be on (the app asks for it on Home).

## Home screen shortcuts

Home's game menu (long-press a card, or ⋯) has **Add to home screen**: the launcher pins a shortcut with the game's name and tile. It sends `title_id` to `FrontendLaunch`, so it behaves like the `title_id` form above (a ready game starts at once; anything that needs you opens Home), and it follows the card if the game or pack is moved. The menu entry shows only when the launcher supports pinning.

## In the frontend's own Switch list

A frontend needs no change of its own: each one lets you add an emulator by its component. Until a frontend ships Banana Native in its bundled emulator list, you add it once, by hand:

- ES-DE: a system in `custom_systems/es_systems.xml` with the same `<name>` as a bundled one (`switch`) replaces that system. Copy the bundled `switch` entry from ES-DE's own `es_systems.xml`, add `.nup` to its `<extension>` and add the Banana Native `<command>` lines below. Then Banana Native is one of the alternative emulators of the Switch system.
- Daijisho and Beacon: add a player to the existing Switch platform with the `am start` arguments below, and add `nup` to its extensions.

## ES-DE

ES-DE reads custom emulators from `ES-DE/custom_systems/es_find_rules.xml` and custom systems from `ES-DE/custom_systems/es_systems.xml`, in its application data folder. Add the emulator:

```xml
<?xml version="1.0"?>
<ruleList>
    <emulator name="BANANA-NATIVE">
        <rule type="androidpackage">
            <entry>dev.bananative.android/dev.bananative.android.Launch</entry>
        </rule>
    </emulator>
</ruleList>
```

Then add a system that lists `.nup` files and uses it. Change `path` to your ROM folder:

```xml
<?xml version="1.0"?>
<systemList>
    <system>
        <name>banananative</name>
        <fullname>Banana Native</fullname>
        <path>%ROMPATH%/banananative</path>
        <extension>.nup .NUP</extension>
        <command label="Banana Native">%EMULATOR_BANANA-NATIVE% %ACTION%=android.intent.action.VIEW %DATA%=%ROMSAF%</command>
        <command label="Banana Native (path)">%EMULATOR_BANANA-NATIVE% %EXTRA_game%=%ROM%</command>
        <platform>switch</platform>
        <theme>switch</theme>
    </system>
</systemList>
```

`%ROMSAF%` needs ES-DE's ROM folder to be picked through its storage access. If it is not, pick the "(path)" command in the system's alternative emulators.

## Daijisho

Daijisho has no screen for adding a player by hand. Its platforms are JSON files: the bundled ones come from its repository (`TapiocaFox/Daijishou`, `platforms/NintendoSwitch.json`), and it imports a platform file you give it. A Switch platform with Banana Native in its player list is that file with these entries added at the top of `playerList` and `revisionNumber` raised by one:

```json
{
  "name": "switch - Banana Native",
  "uniqueId": "switch.dev.bananative.android",
  "description": "Supported extensions: nup.",
  "acceptedFilenameRegex": "^(.*)\\.(?:nup)$",
  "amStartArguments": "-n dev.bananative.android/dev.bananative.android.Launch\n -e game {file.path}",
  "killPackageProcesses": false,
  "killPackageProcessesWarning": false,
  "extra": ""
}
```

A second entry with `-a android.intent.action.VIEW\n -d {file.uri}` is the URI form, which is what Daijisho's other Switch players use. For the test app, use `dev.nxnative.android` in both places. Import the file in Daijisho (Settings › Library › import a platform), then pick the player on the Switch platform. Once Daijisho's repository has the entry, every Daijisho user gets it with no import.

## Beacon, Pegasus and others

Any frontend that runs `am start` works with the same arguments. In Pegasus's `metadata.pegasus.txt`:

```
launch: am start -n dev.bananative.android/dev.bananative.android.Launch -e game "{file.path}"
```

From a computer, with the phone on adb:

```bash
adb shell am start -n dev.bananative.android/dev.bananative.android.Launch -e game /storage/emulated/0/ROMs/game.nup
```

## Opening a .nup from a file manager

A second alias, `<application ID>.Open`, shows the app in "Open with" for a file manager's `.nup`. That works when the file manager sends a path or a URI with one (see above).

## When the frontend says "Unable to find explicit activity class"

On Android 15, an explicit intent reaches an exported component that has intent filters only when the intent matches one of them, and an intent with no action matches none (adb's shell is exempt, so adb tests do not show it). Daijisho met both: its `-d {file.uri}` player sends `VIEW` with no MIME type, and its `-e game {file.path}` player sends no action. So the `Launch` alias has no intent filters at all, and takes every form. The "Open with" filter for file managers is on a second alias, `<application ID>.Open` (2026-11-04). If a frontend still gets this message, the log shows what it sent: `adb logcat | grep -E "AmStart args|START u0"`.

## Code

- `FrontendLaunch` (the alias's activity): reads the intent, checks the game as Play does (`ready`), then starts `GameActivity` with the extra `frontend`, or sends the game to Home (`MainActivity.fromFrontend`). It draws nothing (a translucent activity in a task of its own, not in Recents).
- `Frontend`: the plain-Java part (URIs, paths, cards, title IDs), checked on the JVM by `dm/tools/tests/test_android_frontend.py` (`FrontendJvmTest`).
- `GameActivity.backToFrontend`: when the game ends, the app's task goes behind the frontend instead of showing Home.

## Status

- 2026-11-04, Honor phone, test app: over adb, the extra `game` (with `/sdcard`), `ACTION_VIEW` of a `file://` URI and of a SAF document URI, and the extra `title_id` each start the game straight away; a missing file shows its dialog. From Daijisho, on its Switch platform imported with the Banana Native players, both players (`-e game {file.path}` and `-a VIEW -d {file.uri}`) start the game. ES-DE, Beacon and Pegasus are not tried.
