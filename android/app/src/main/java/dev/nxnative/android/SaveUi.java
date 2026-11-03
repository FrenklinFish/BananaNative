package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A game's Back up saves / Restore saves… / Import save… (its Manage sheet), over SaveStore. The save folder is the one the game's next start uses (Folders.save:
 * play saves/&lt;id&gt;, test saves-test/&lt;id&gt;, or the picked one), the backups go to &lt;Switch2&gt;/save-backups/&lt;id&gt;/. Each refuses while a game runs (the
 * :game process is alive): the host may be writing the save. While one works, Play waits for it (busy). Nothing here deletes a backup.
 */
final class SaveUi {
    static final int PICK_IMPORT = 30;
    private static final Set<String> busy = new HashSet<>();
    private static Game importFor;

    private SaveUi() {}

    /** Whether a save operation of this game is running (MainActivity.launch waits) */
    static synchronized boolean busy(String titleId) { return busy.contains(titleId); }

    private static synchronized boolean take(String titleId) { return busy.add(titleId); }

    private static synchronized void give(String titleId) { busy.remove(titleId); }

    /**
     * Whether the game of this save runs now: its process holds the save's lock (GameActivity takes &lt;save&gt;.lock before the save is used and keeps it while the
     * process lives, and the kernel lets it go when the process ends, also after a crash). A :game process left idle, or another game's, does not count
     */
    static boolean gameRunning(Context c, Game g) { return SaveStore.inUse(Folders.save(c, g)); }

    static String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()); }

    static File backups(Game g) { return Prefs.backupsDir(new File(g.gameDir), g.titleId); }

    private static boolean ready(MainActivity a, Game g, String what) {
        if (a.needAccess()) return false;
        if (busy(g.titleId)) { a.error("A save backup or restore of " + g.name + " is still working."); return false; }
        if (gameRunning(a, g)) { a.error(g.name + " is running (it holds its save): stop it first (its in-game panel › Stop the game), then " + what + ". The game may be writing its save."); return false; }
        return true;
    }

    /** Runs work off the main thread with a wait dialog; done gets null or the error */
    private static void run(MainActivity a, Game g, String wait, Work work, Done done) {
        if (!take(g.titleId)) return;
        AlertDialog w = new AlertDialog.Builder(a).setMessage(wait).setCancelable(false).show();
        new Thread(() -> {
            String err = null;
            Object r = null;
            try { r = work.run(); } catch (IOException | RuntimeException e) { err = e.getMessage(); }
            String fe = err;
            Object fr = r;
            a.runOnUiThread(() -> {
                give(g.titleId);
                w.dismiss();
                done.done(fr, fe);
            });
        }, "nx-save").start();
    }

    interface Work { Object run() throws IOException; }
    interface Done { void done(Object result, String error); }

    static void backup(MainActivity a, Game g) {
        if (!ready(a, g, "back up")) return;
        File save = Folders.save(a, g);
        boolean any = false;
        for (File p : SaveStore.parts(save).values()) { String[] l = p.list(); if (l != null && l.length > 0) any = true; }
        if (!any) { a.error(g.name + " has no save yet in " + save + ": nothing to back up."); return; }
        File to = backups(g);
        run(a, g, "Backing up " + g.name + "'s save…", () -> SaveStore.backup(save, to, stamp()), (r, err) -> {
            if (err != null) { a.error("No backup was made: " + err + "\n\nThe save in " + save + " was only read, never changed."); return; }
            SaveStore.Backup b = (SaveStore.Backup) r;
            new AlertDialog.Builder(a).setTitle("Saves backed up").setMessage(b.files + " files, " + SaveStore.size(b.bytes) + (b.device ? ", with the device save" : "")
                    + ", checked file by file (SHA-256) against the save.\n\n"
                    + b.dir + "\n\nThe save itself was not changed. Backups are never deleted by the app.").setPositiveButton("OK", null).show();
        });
    }

    static void restoreList(MainActivity a, Game g) {
        if (!ready(a, g, "restore")) return;
        File to = backups(g);
        List<SaveStore.Backup> l = SaveStore.list(to);
        if (l.isEmpty()) { a.error("No backups of " + g.name + " yet in " + to + ". Back up saves makes one."); return; }
        String[] labels = new String[l.size()];
        for (int i = 0; i < labels.length; i++) {
            SaveStore.Backup b = l.get(i);
            String from = b.source.isEmpty() ? "" : "\nfrom " + new File(b.source).getParentFile().getName() + "/" + new File(b.source).getName();
            labels[i] = SaveStore.when(b.name) + "\n" + b.files + " files, " + SaveStore.size(b.bytes) + (b.device ? " (with the device save)" : "") + from;
        }
        new AlertDialog.Builder(a).setTitle("Restore " + g.name + "'s save from").setItems(labels, (d, w) -> confirm(a, g, l.get(w).dir, SaveStore.when(l.get(w).name)))
                .setNegativeButton("Cancel", null).show();
    }

    static void importPick(MainActivity a, Game g) {
        if (!ready(a, g, "import")) return;
        importFor = g;
        a.pickFolder(PICK_IMPORT, "Pick the save folder to import (the folder that holds the save's files, like saves/" + g.titleId + ")");
    }

    /** MainActivity.onActivityResult: the folder picked for Import save… */
    static boolean activityResult(MainActivity a, int request, File dir) {
        if (request != PICK_IMPORT) return false;
        Game g = importFor;
        importFor = null;
        if (g != null) confirm(a, g, dir, "the folder " + dir);
        return true;
    }

    private static void confirm(MainActivity a, Game g, File from, String what) {
        File save = Folders.save(a, g);
        String why = SaveStore.refuseSource(from, save);
        if (why != null) { a.error("Not restored: " + why + "."); return; }
        String st = stamp();
        Ui.confirmDanger(a, "Restore " + g.name + "'s save?", "Replaced: the save in " + save + ", with " + what + ".\n\nFirst, the current save is backed up to "
                + new File(backups(g), st + SaveStore.BEFORE_RESTORE) + " (checked), so this can be undone with Restore saves…. Nothing is deleted from the backups.",
                "Restore", () -> {
                    if (!ready(a, g, "restore")) return;
                    run(a, g, "Restoring " + g.name + "'s save…", () -> SaveStore.restore(from, save, backups(g), st), (r, err) -> {
                        if (err != null) {
                            a.error("Not restored: " + err + "\n\nThe save in " + save + " is as it was" + (SaveStore.pending(save) ? ", except for an unfinished swap that is finished at the next "
                                    + "backup, restore or game start" : "") + ".");
                            return;
                        }
                        SaveStore.Restored x = (SaveStore.Restored) r;
                        Toast.makeText(a, "Restored " + x.now.files + " files (" + SaveStore.size(x.now.bytes) + (x.parts.contains(SaveStore.DEVICE) ? ", with the device save" : "") + ")"
                                + (x.before != null ? "; the old save is in " + x.before.dir : ""),
                                Toast.LENGTH_LONG).show();
                    });
                });
    }
}
