# Saves

Back to the [guides](README.md).

A game's save is in the app's own storage, `Android/data/<app>/files/saves/<title ID>` (and its device save,
`saves/<title ID>_device`), unless you picked another save folder in the game's ⋯ button › **Folders**. Save backups go
to `save-backups/<title ID>/` next to the game folder, for example `Switch2/save-backups/040074A01BF12000/`, which the
app never deletes.

Stop the game before you back up, restore or import a save (its in-game panel › **Stop the game**): the app refuses
while the game runs, because the game may be writing its save.

None of this needs **All files access**. Without it, backups are exported as a `.zip` and saves are imported through the
system picker, as below. Only a save folder you picked yourself on the phone's storage (in **Folders**) still needs it.

## Back up saves

The game's ⋯ button › **Back up saves** copies the save (with the device save) into a new folder named by date and
time under `save-backups/<title ID>/`, and checks every file against the save. The save itself is not changed.

When the app cannot write the shared `save-backups` folder (no All files access), or the game is in the app's own
storage (a game copied into the app, or a `.nup` used where it is), the line under **Back up saves** says **As a .zip,
to a place you pick**: the app asks where the backup goes, writes it as one `.zip`, reads it back and checks it. A copy
also stays in the app's own storage, where **Restore saves** lists it; that copy is deleted with the app's data.

## Restore saves

The game's ⋯ button › **Restore saves** lists the backups the app can see, newest first, with their date, size and
where they came from (the shared `save-backups` folder needs All files access; the app's own backups are always
listed), and **Import save… (a .zip or a folder)** at the end. Pick one and confirm **Restore**. Before replacing
anything, the app backs up the current save (a backup ending in `-before-restore`), so a restore can be undone with
**Restore saves** again.

## Import save

The game's ⋯ button › **Import save** asks for:

- **A .zip (from Back up saves, or a zipped save folder)**, or
- **A save folder**: the folder that holds the save's files (like `saves/<title ID>`, or one backup folder).

Both are picked with the system picker, from anywhere the picker reaches. As with a restore, the current save is
backed up first. A file or folder that cannot be read as a save changes nothing.

## A save folder that is gone

If you picked a save folder in **Folders** and it was deleted or moved, the app does not make a new empty save there.
The game's card says **Save folder gone** ("The save folder <name> is gone. Use the app's save, or pick the folder
again.") and its button reads **Fix the save folder**: choose **Use the app's save** or **Pick the folder**.

## Moving saves

- **To another phone:** **Back up saves**, copy the backup (the `save-backups/<title ID>` folder, or the `.zip`) to
  the new phone, and use **Import save** there (or, with All files access, put the `save-backups/<title ID>` folder next
  to the game folder and use **Restore saves**).
- **To a new version of the app:** an update keeps the app's storage, so the save stays where it is. Only a save from
  version 0.1.0 or older, in `Switch2/saves/<title ID>/`, is copied into the app at the game's next start; that folder
  is left as it was.
- **Before uninstalling:** Android asks whether to keep the app's data. To be safe, **Back up saves** first: backups in
  the shared `save-backups` folder and exported `.zip` files stay on the phone's storage; the app's own copies do not
  ([What uninstalling deletes](storage-access.md#what-uninstalling-deletes)).
- **Between two apps on one phone** (each app has its own storage): **Back up saves** in one, **Import save** of that
  backup (its folder or `.zip`) in the other.
