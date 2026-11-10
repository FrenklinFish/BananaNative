package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A game's Back up saves / Restore saves… / Import save… (its Manage sheet), over SaveStore. The save folder is the one the game's next start uses (Folders.save:
 * the app's saves/&lt;id&gt;, the old default beside the game until it is copied in, or the picked one). Each refuses while a game runs (the :game process is
 * alive): the host may be writing the save. While one works, Play waits for it (busy). Nothing here deletes a backup.
 * <ul>
 * <li><b>Back up</b>: to &lt;Switch2&gt;/save-backups/&lt;id&gt;/ on shared storage when the app may write there (All files access), so backups outlive an uninstall.
 *     Otherwise, or when that folder is in app storage (a game copied into the app, a .nup used where it is), the verified backup is made in the app's own
 *     save-backups/&lt;id&gt; and exported as one .zip to a place the user picks (ACTION_CREATE_DOCUMENT), read back and checked (SaveZip).</li>
 * <li><b>Restore</b> lists the backups the app can see: the shared folder's (with All files access) and the app's own; Import save… is offered there too.</li>
 * <li><b>Import</b> takes a .zip (from Back up saves, or a zip of a save folder) or a folder, both through the provider (ContentResolver streams, no /storage path),
 *     unpacked or copied into a staging folder in app storage (zip slip refused), then restored from as a backup is (SaveStore.restore: its manifest checked when
 *     it has one, the current save backed up first, the swap); the staging folder is removed afterwards.</li>
 * </ul>
 */
final class SaveUi {
    static final int PICK_IMPORT_ZIP = 30, PICK_IMPORT_DIR = 31, CREATE_EXPORT = 32;
    private static final Set<String> busy = new HashSet<>();
    private static Game pendingFor;

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

    /** The shared backups folder: &lt;Switch2 root&gt;/save-backups/&lt;id&gt; (Prefs.backupsDir) */
    static File backups(Game g) { return Prefs.backupsDir(new File(g.gameDir), g.titleId); }

    /** The app's own backups folder (SaveZip.appBackups) */
    static File appBackups(Context c, Game g) { return SaveZip.appBackups(AppStorage.root(c), g.titleId); }

    /** Whether Back up saves exports a zip (SaveZip.export: no shared folder the app may write, or it is inside app storage) */
    static boolean exports(Context c, Game g) {
        File s = backups(g);
        return SaveZip.export(s, AppStorage.root(c), Environment.isExternalStorageManager() && SaveZip.writable(s));
    }

    /** Where backups go and where a restore's automatic backup goes: the shared folder, or the app's own when the backup is exported */
    static File target(Context c, Game g) { return exports(c, g) ? appBackups(c, g) : backups(g); }

    /** The Manage sheet's line under Back up saves */
    static String backupLine(Context c, Game g) { return exports(c, g) ? "As a .zip, to a place you pick" : "To " + backups(g); }

    private static boolean ready(MainActivity a, Game g, String what) {
        File save = Folders.save(a, g);
        if (save != null && !CopyPlan.inApp(AppStorage.root(a), save.getPath()) && a.needAccess()) return false;   // a save folder on shared storage (picked in Folders)
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

    private static boolean hasSave(File save) {
        for (File p : SaveStore.parts(save).values()) { String[] l = p.list(); if (l != null && l.length > 0) return true; }
        return false;
    }

    static void backup(MainActivity a, Game g) {
        if (!ready(a, g, "back up")) return;
        File save = Folders.save(a, g);
        if (!hasSave(save)) { a.error(g.name + " has no save yet: nothing to back up."); return; }
        if (exports(a, g)) {   // the picker first: the backup is made when the user has said where its zip goes
            pendingFor = g;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, SaveZip.zipName(g.titleId, stamp()));
            Toast.makeText(a, "Pick where the backup goes (a .zip)", Toast.LENGTH_LONG).show();
            a.startActivityForResult(i, CREATE_EXPORT);
            return;
        }
        File to = backups(g);
        run(a, g, "Backing up " + g.name + "'s save…", () -> SaveStore.backup(save, to, stamp()), (r, err) -> {
            if (err != null) { Ui.explain(a, "No backup made", "The save could not be backed up. It was only read, never changed.", err + "\nSave: " + save).setPositiveButton("OK", null).show(); return; }
            SaveStore.Backup b = (SaveStore.Backup) r;
            Ui.explain(a, "Saves backed up", b.files + " files, " + SaveStore.size(b.bytes) + (b.device ? ", with the device save" : "") + ", checked file by file. "
                    + "The app never deletes a backup.", b.dir.getPath()).setPositiveButton("OK", null).show();
        });
    }

    /** The backup made in app storage, then written as a zip to the picked document, read back and checked */
    private static void exportTo(MainActivity a, Game g, Uri doc) {
        if (!ready(a, g, "back up")) return;
        File save = Folders.save(a, g);
        ContentResolver cr = a.getContentResolver();
        run(a, g, "Backing up " + g.name + "'s save…", () -> {
            SaveStore.Backup b = SaveStore.backup(save, appBackups(a, g), stamp());
            try (OutputStream o = cr.openOutputStream(doc, "wt")) {
                if (o == null) throw new IOException("the picked place gave nothing to write to");
                SaveZip.write(b.dir, o);
            }
            String bad;
            try (InputStream in = cr.openInputStream(doc)) {
                if (in == null) throw new IOException("the zip cannot be read back");
                bad = SaveZip.check(in, b.dir);
            }
            if (bad != null) throw new IOException("the zip does not match the backup (" + bad + ")");
            return b;
        }, (r, err) -> {
            if (err != null) {
                Ui.explain(a, "Backup not exported", "The zip could not be written or did not check out. The save was only read, never changed.", err + "\nZip: " + doc)
                        .setPositiveButton("OK", null).show();
                return;
            }
            SaveStore.Backup b = (SaveStore.Backup) r;
            Ui.explain(a, "Saves backed up", b.files + " files, " + SaveStore.size(b.bytes) + (b.device ? ", with the device save" : "") + ", in the zip you picked, checked "
                    + "file by file. Import save… brings it back. A copy stays in the app (Restore saves lists it).", "Zip: " + doc + "\nIn the app: " + b.dir)
                    .setPositiveButton("OK", null).show();
        });
    }

    /** Restore saves…: the backups the app can see (the shared folder's with All files access, the app's own), newest first, and Import save… */
    static void restoreList(MainActivity a, Game g) {
        if (!ready(a, g, "restore")) return;
        List<SaveStore.Backup> l = new ArrayList<>();
        if (Environment.isExternalStorageManager()) l.addAll(SaveStore.list(backups(g)));
        if (!SaveStore.same(appBackups(a, g), backups(g))) l.addAll(SaveStore.list(appBackups(a, g)));
        l.sort((x, y) -> y.name.compareTo(x.name));
        String[] labels = new String[l.size() + 1];
        for (int i = 0; i < l.size(); i++) {
            SaveStore.Backup b = l.get(i);
            String from = b.source.isEmpty() ? "" : "\nfrom " + new File(b.source).getParentFile().getName() + "/" + new File(b.source).getName();
            labels[i] = SaveStore.when(b.name) + "\n" + b.files + " files, " + SaveStore.size(b.bytes) + (b.device ? " (with the device save)" : "") + from;
        }
        labels[l.size()] = "Import save… (a .zip or a folder)";
        new AlertDialog.Builder(a).setTitle(l.isEmpty() ? "No backups of " + g.name + " here yet" : "Restore " + g.name + "'s save from")
                .setItems(labels, (d, w) -> {
                    if (w == l.size()) importPick(a, g);
                    else confirm(a, g, l.get(w).dir, SaveStore.when(l.get(w).name), null);
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Import save…: a .zip or a folder, through the system picker */
    static void importPick(MainActivity a, Game g) {
        if (!ready(a, g, "import")) return;
        new AlertDialog.Builder(a).setTitle("Import " + g.name + "'s save from")
                .setItems(new String[] {"A .zip (from Back up saves, or a zipped save folder)", "A save folder"}, (d, w) -> {
                    pendingFor = g;
                    if (w == 0) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                        a.startActivityForResult(i, PICK_IMPORT_ZIP);
                    } else {
                        Toast.makeText(a, "Pick the save folder (the one that holds the save's files)", Toast.LENGTH_LONG).show();
                        a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_IMPORT_DIR);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** MainActivity.onActivityResult: the documents of Back up saves (export) and Import save… */
    static boolean documentResult(MainActivity a, int request, Uri doc) {
        if (request != PICK_IMPORT_ZIP && request != PICK_IMPORT_DIR && request != CREATE_EXPORT) return false;
        Game g = pendingFor;
        pendingFor = null;
        if (g == null) return true;
        if (request == CREATE_EXPORT) exportTo(a, g, doc);
        else stage(a, g, doc, request == PICK_IMPORT_DIR);
        return true;
    }

    /** The picked zip unpacked (or folder copied) into a staging folder in app storage, then restored from (confirm); the staging folder goes afterwards */
    private static void stage(MainActivity a, Game g, Uri doc, boolean tree) {
        if (!ready(a, g, "import")) return;
        ContentResolver cr = a.getContentResolver();
        File base = new File(AppStorage.root(a), "save-import"), st = new File(base, stamp());
        run(a, g, "Reading the save…", () -> {
            SaveStore.deleteTree(base);   // an import left there when its question was cancelled
            if (tree) copyTree(cr, doc, new File(st, name(cr, DocumentsContract.buildDocumentUriUsingTree(doc, DocumentsContract.getTreeDocumentId(doc)), "save")));
            else try (InputStream in = cr.openInputStream(doc)) {
                if (in == null) throw new IOException("the zip cannot be opened");
                SaveZip.unpack(in, st);
            }
            return SaveZip.importRoot(st);
        }, (r, err) -> {
            if (err != null) {
                SaveStore.deleteTree(st);
                Ui.explain(a, "Not imported", "That " + (tree ? "folder" : "zip") + " could not be read as a save. Nothing was changed.", err + "\n" + doc).setPositiveButton("OK", null).show();
                return;
            }
            confirm(a, g, (File) r, tree ? "the picked folder" : "the picked zip", () -> SaveStore.deleteTree(st));
        });
    }

    private static String name(ContentResolver cr, Uri u, String or) {
        try (Cursor c = cr.query(u, new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            String n = c != null && c.moveToFirst() ? CopyPlan.safeName(c.getString(0)) : null;
            return n != null ? n : or;
        } catch (RuntimeException e) {
            return or;
        }
    }

    /** A picked folder's files copied through the provider into to (names checked: a name with a path separator is refused); at most SaveZip's limits */
    private static void copyTree(ContentResolver cr, Uri tree, File to) throws IOException {
        long[] left = {SaveZip.MAX_FILES, SaveZip.MAX_BYTES};
        copyTree(cr, tree, DocumentsContract.getTreeDocumentId(tree), to, left);
    }

    private static void copyTree(ContentResolver cr, Uri tree, String doc, File to, long[] left) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot make " + to);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc), cols, null, null, null)) {
            if (c == null) throw new IOException("cannot list " + to.getName());
            byte[] buf = new byte[1 << 16];
            while (c.moveToNext()) {
                String n = CopyPlan.safeName(c.getString(1));
                if (n == null) throw new IOException("a file in " + to.getName() + " has a name the app cannot use: " + c.getString(1));
                File f = new File(to, n);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) { copyTree(cr, tree, c.getString(0), f, left); continue; }
                if (--left[0] < 0) throw new IOException("the folder has more than " + SaveZip.MAX_FILES + " files: it is not a save");
                try (InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))); OutputStream o = new FileOutputStream(f)) {
                    if (in == null) throw new IOException("cannot open " + n);
                    for (int k; (k = in.read(buf)) > 0; ) {
                        if ((left[1] -= k) < 0) throw new IOException("the folder holds more than " + (SaveZip.MAX_BYTES >> 30) + " GiB: it is not a save");
                        o.write(buf, 0, k);
                    }
                }
            }
        }
    }

    private static void confirm(MainActivity a, Game g, File from, String what, Runnable after) {
        File save = Folders.save(a, g);
        String why = SaveStore.refuseSource(from, save);
        if (why != null) { if (after != null) after.run(); a.error("Not restored: " + why + "."); return; }
        String st = stamp();
        File to = target(a, g);
        Ui.confirmDanger(a, "Restore " + g.name + "'s save?", "Your current save is replaced with " + what + ". It is backed up first (checked), so Restore "
                + "saves… can undo this. No backup is deleted.\n\nSave: " + save + "\nBackup of it: " + new File(to, st + SaveStore.BEFORE_RESTORE),
                "Restore", () -> {
                    if (!ready(a, g, "restore")) { if (after != null) after.run(); return; }
                    run(a, g, "Restoring " + g.name + "'s save…", () -> SaveStore.restore(from, save, to, st), (r, err) -> {
                        if (after != null) after.run();
                        if (err != null) {
                            Ui.explain(a, "Not restored", "The save is as it was" + (SaveStore.pending(save) ? ", but for a swap that finishes at the next backup, restore or game start" : "")
                                    + ".", err + "\nSave: " + save).setPositiveButton("OK", null).show();
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
