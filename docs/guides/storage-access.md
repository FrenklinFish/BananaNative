# Storage access

The app reads your game in one of three ways. Back to the [guides](README.md).

| | All files access | A .nup used where it is | A dump folder copied into the app |
|---|---|---|---|
| Needs All files access | yes | no | no |
| Where the game is read from | where it is, for example `Switch2/DonkeyKongBananza` | where it is, through the permission the system picker gives | the app's own storage (`Android/data/<app>/files/games/`) |
| Extra space | none | none | the dump's size a second time (you can delete the original afterwards) |
| On uninstall | your dump stays | your `.nup` stays | the copy is deleted with the app's data, unless you keep the data |

## All files access

With **All files access** on, the app reads your dump where it is and finds a pack next to it. Turn it on with the
**Allow** button of the **Allow file access** card on Home, or in Android's settings (Apps › Banana Native › All files
access, or Special app access › All files access, depending on the phone). Settings › **Readiness check** shows
whether it is on and opens the setting. A dump folder read where it is needs it: the game opens thousands of files in
it by path. Home shows the card only while a game on the list (or an empty list) needs it.

## Without All files access

Without All files access, **Add game** offers:

- **A .nup file (used where it is)**: pick your game's `.nup` file in the system picker. Nothing is copied: the app
  keeps the picker's permission to read it, also after a restart. A `.nup` in a place that only streams files (a cloud
  folder) cannot be used where it is; the app says so and offers **Copy into the app**.
- **A dump folder (copied into the app: a folder needs All files access to be read where it is)**: pick your dump
  folder (the one with `base/`) in the system picker. The copy shows its progress and can be stopped (**Stop**); a
  stopped or failed copy keeps nothing. Make sure the phone has room for the dump's size again, and 2 GB more.
- **Allow All files access**.

Either way, the game is then added as if you had picked it, and **Prepare**, **Play** and starting it from frontends
work without All files access. Its pack goes to the app's own storage (`packs/<title ID>`). **Back up saves**,
**Restore saves** and **Import save** work too ([Saves](saves.md)); **Export pack…** and **Import pack…** still ask for
All files access ([Packs and app updates](packs-and-updates.md)).

## A game file that is gone

If a game's `.nup` or dump folder was deleted, moved or renamed (or, for a `.nup` used where it is, the permission was
taken back), its card says **Game file gone**: "The game file is gone: <name>. Pick it again, or remove the game." Its
button reads **Pick it again** (**Play** asks the same, with **Remove**); picking a dump folder again needs All files
access. When the file you pick holds the same game,
it takes the card's place and the card keeps its pack, settings and saves; another game is refused. Settings ›
**Readiness check** says the same in its "Game files" row.

A pack folder you picked that is gone is forgotten: the card takes the app's own pack (or the one next to the game)
instead of asking to prepare again, when there is one.

## What uninstalling deletes

The app keeps its data in its own storage, `Android/data/<app>/files`: your saves (`saves/`), the prepared packs
(`packs/`) and games copied into the app (`games/`). When you uninstall, Android asks whether to keep this data. If you
don't keep it, all of that is deleted.

Kept either way: your dumps and `.nup` files in their own folders, and the save backups made with All files access,
in `save-backups/<title ID>/` next to the game folder (for example `Switch2/save-backups/040074A01BF12000/`). Back up
your saves before you uninstall ([Saves](saves.md)).

The backups of a copied game or a `.nup` used where it is are exported as a `.zip` to a place you pick, which stays;
the copy the app keeps of them in its own storage is deleted with it.

## Removing a copied game

The game's ⋯ button › **Remove from list** asks, for a copied game: **Remove only** (the copy stays in the app's
storage) or **Delete the copy** (asked once more; it cannot be undone). Your original dump, the pack and your saves
stay either way. For a `.nup` used where it is, **Remove from list** gives the app's read permission back; the file
itself is not touched.
