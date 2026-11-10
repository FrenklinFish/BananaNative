package dev.nxnative.android;

import android.content.Context;

import java.io.File;

/**
 * The app's own data root: getExternalFilesDir(null) (/storage/emulated/0/Android/data/&lt;package&gt;/files; the app's private folder when shared storage is not
 * there), as Eden keeps its user folder. Each app (test, play, release) has its own. It holds:
 * <ul>
 * <li>{@code saves/<title ID>} (and its device save {@code saves/<title ID>_device}): a game's save when no save folder was picked in Folders (Folders.save,
 *     GameActivity.saveDir; an older version's save beside the game is copied in once: SaveMigrate)</li>
 * <li>{@code packs/<title ID>/}: the pack Prepare makes (pack.tsv, the modules, shaders/v-&lt;V&gt;/), and the one Import pack… puts in (PackZip); Folders.packFor
 *     looks here after a picked pack folder and before the older places beside the game (Pack.find), which keep working</li>
 * </ul>
 * The host gets these as plain paths (DM_SAVE, the pack folder), as before. Android removes the folder with the app; the manifest's hasFragileUserData lets the user
 * keep it on uninstall. Save backups stay on shared storage (&lt;Switch2&gt;/save-backups/&lt;id&gt;: Prefs.backupsDir), so they outlive an uninstall either way.
 */
final class AppStorage {
    private AppStorage() {}

    static File root(Context c) {
        File f = c.getExternalFilesDir(null);
        return f != null ? f : c.getFilesDir();
    }

    /** A game's save in app storage (the default save folder) */
    static File save(Context c, String titleId) { return new File(root(c), "saves/" + titleId); }

    /** The folder that holds the packs (packs/&lt;title ID&gt;) */
    static File packs(Context c) { return new File(root(c), "packs"); }

    /** A game's pack folder in app storage; a swap that stopped part way (PackZip.swapIn) is put back first */
    static File pack(Context c, String titleId) {
        File p = new File(packs(c), titleId);
        PackZip.recover(p);
        return p;
    }
}
