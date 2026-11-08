# Packs and app updates

A game's **pack** is what preparing makes: the game's code rewritten for Android and its shader library. The app keeps
it in its own storage, `Android/data/<app>/files/packs/<title ID>`. Back to the [guides](README.md).

## Export pack…

The game's ⋯ button › **Export pack…** writes the pack as one `.zip` (`banana-pack-<title ID>-<version>.zip`) into a
folder you pick, and checks it file by file. Use it to keep a pack before you uninstall, or to move it to another
phone without preparing again.

## Import pack…

The game's ⋯ button › **Import pack…** asks for:

- **A .zip file (made by Export pack…)**, or
- **A pack folder (with pack.tsv and shaders/)**, for example `android-pack` made on a PC or a Mac
  ([Preparing a game on a PC or a Mac](pc-mac-packing.md)).

The app checks that the pack is this game's and fits the game version in your game folder, then copies it into its own
storage. The pack that was there stays until the new one is in, so a failed import changes nothing. A game that is not
on the list yet gets **Import pack…** in the question **Add game** asks when it finds no pack.

Export and import need **All files access** ([Storage access](storage-access.md)); without it, the app asks for it
first. Backing up and importing saves does not ([Saves](saves.md)).

## After an app update

A new version of the app may come with new shaders. What happens depends on what changed:

- **No shader changes:** the pack's shaders are taken over at once. Nothing to do.
- **Some shaders changed:** the game's card says **Ready · updating shaders …** with the count and the time left. Only
  the changed shaders are translated, in the background, from your dump on the phone, and you can keep playing with the
  old shaders meanwhile. When the update is done, the game uses the new ones.
- **The app cannot update them by itself:** the card says so (for example **Ready · new shaders: ⋯ › Prepare to update
  them**) or its button reads **Prepare shaders**. Tap it, or the game's ⋯ button › **Prepare again**. A pack made on a
  computer can be made again there and imported instead.

The app's in-app updates (Settings › **Check for updates**) download new versions from the project's
releases only: [GitHub](https://github.com/moceure/BananaNative/releases) or the mirror on
[git.eden-emu.dev](https://git.eden-emu.dev/moceure/BananaNative/releases), whichever has the newest one.
