package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * A game's Export pack… and Import pack… (its Manage sheet), over PackZip. Export writes the game's pack as one stored zip (banana-pack-&lt;id&gt;-&lt;version&gt;.zip)
 * into a folder the user picks; Import takes such a zip, or a pack folder, checks it (its title is this game's, its code fits the game build in the game folder:
 * PackCheck.blocking, a shader library is in it) and puts it into the app's packs/&lt;id&gt; (AppStorage), keeping the pack that was there until the new one is in.
 * The game's card then uses it (a picked pack folder is let go). An older library goes through the usual restamp or shader update at Play. Both refuse while the
 * game runs; Play waits while one works (busy).
 */
final class PackUi {
    static final int PICK_EXPORT = 40, PICK_IMPORT_ZIP = 41, PICK_IMPORT_DIR = 42;
    private static final Set<String> busy = new HashSet<>();
    private static Game pending;

    private PackUi() {}

    /** Whether an export or import of this game's pack is working (MainActivity.launch waits) */
    static synchronized boolean busy(String titleId) { return busy.contains(titleId); }

    private static synchronized boolean take(String titleId) { return busy.add(titleId); }

    private static synchronized void give(String titleId) { busy.remove(titleId); }

    private static boolean ready(MainActivity a, Game g) {
        if (a.needAccess()) return false;
        if (busy(g.titleId)) { a.error("An export or import of " + g.name + "'s pack is still working."); return false; }
        if (SaveUi.gameRunning(a, g)) { a.error(g.name + " is running: stop it first (its in-game panel › Stop the game)."); return false; }
        return true;
    }

    static void exportPick(MainActivity a, Game g) {
        if (!ready(a, g)) return;
        try {
            Pack.read(new File(g.packDir));
        } catch (IOException e) {
            a.error("There is no pack to export: " + e.getMessage());
            return;
        }
        pending = g;
        a.pickFolder(PICK_EXPORT, "Pick the folder for the pack's .zip");
    }

    static void importPick(MainActivity a, Game g) {
        if (!ready(a, g)) return;
        new PadFocus.Builder(a).setTitle("Import " + g.name + "'s pack from…")
                .setItems(new String[] {"A .zip file (made by Export pack…)", "A pack folder (with pack.tsv and shaders/)"}, (d, w) -> {
                    pending = g;
                    if (w == 1) { a.pickFolder(PICK_IMPORT_DIR, "Pick the pack folder (the one with pack.tsv and shaders/)"); return; }
                    Toast.makeText(a, "Pick the pack's .zip file", Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
                    a.startActivityForResult(i, PICK_IMPORT_ZIP);
                })
                .setNegativeButton("Cancel", null).show();
    }

    /**
     * adb, test app only (android/TESTING.md "Packs in app storage"): am start -n dev.nxnative.android/.MainActivity --es pack_title &lt;title ID&gt;
     * [--es pack_game &lt;game folder or .nup&gt;] (--es pack_export &lt;folder&gt; | --es pack_import &lt;.zip or pack folder&gt;) runs Export pack… / Import pack…
     * of the listed game of that title (the entry of that game path, else the first) without the pickers, with the same checks and dialogs
     */
    static void fromIntent(MainActivity a, Intent i) {
        String id = i.getStringExtra("pack_title"), gp = i.getStringExtra("pack_game"), ex = i.getStringExtra("pack_export"), im = i.getStringExtra("pack_import");
        if (BuildConfig.NX_PLAY || id == null || ex == null && im == null) return;
        for (Game g : Game.load(a)) {
            if (!g.titleId.equals(id) || gp != null && !g.gameDir.equals(gp)) continue;
            if (ex != null) exportRun(a, g, new File(ex));
            else importRun(a, g, new File(im));
            return;
        }
        a.error("No game of " + id + " in the list.");
    }

    /** MainActivity.onActivityResult, before a folder's path is read: the .zip picked for Import pack… */
    static boolean documentResult(MainActivity a, int request, Uri doc) {
        if (request != PICK_IMPORT_ZIP) return false;
        Game g = pending;
        pending = null;
        if (g == null) return true;
        String p = PathUtil.documentToPath(doc);
        if (p == null) { a.error("That file is not on this phone's storage by a path the app can read (" + doc + "). Pick it from the phone's storage (☰ › the phone's name), not from Recent or Downloads."); return true; }
        importRun(a, g, new File(p));
        return true;
    }

    /** MainActivity.onActivityResult: the folder picked for Export pack… or Import pack… */
    static boolean activityResult(MainActivity a, int request, File dir) {
        if (request != PICK_EXPORT && request != PICK_IMPORT_DIR) return false;
        Game g = pending;
        pending = null;
        if (g == null) return true;
        if (request == PICK_EXPORT) exportRun(a, g, dir);
        else importRun(a, g, dir);
        return true;
    }

    private static long size(File f) {
        if (!f.isDirectory()) return f.length();
        long n = 0;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) n += size(x);
        return n;
    }

    private static void exportRun(MainActivity a, Game g, File dir) {
        if (!ready(a, g)) return;
        File pack = new File(g.packDir);
        run(a, g, "Exporting " + g.name + "'s pack…", () -> {
            long need = size(pack);
            if (dir.isDirectory() && dir.getUsableSpace() < need + (64L << 20))
                throw new IOException("the zip needs " + SaveStore.size(need) + ", and " + dir + " has " + SaveStore.size(dir.getUsableSpace()) + " free");
            return PackZip.export(pack, dir);
        }, (r, err) -> {
            if (err != null) { a.error("No pack was exported: " + err + "\n\nThe pack in " + pack + " was only read."); return; }
            File z = (File) r;
            new PadFocus.Builder(a).setTitle("Pack exported").setMessage(z + "\n\n" + SaveStore.size(z.length()) + ", checked against the pack file by file. Import pack… on another "
                    + "phone (or after a new install) puts it back.").setPositiveButton("OK", null).show();
        });
    }

    private static void importRun(MainActivity a, Game g, File source) {
        if (!ready(a, g)) return;
        File packs = AppStorage.packs(a), target = AppStorage.pack(a, g.titleId);
        run(a, g, "Importing " + g.name + "'s pack…", () -> {
            long need = size(source);
            if (packs.getParentFile() != null && packs.getParentFile().getUsableSpace() < need + (256L << 20))
                throw new IOException("the pack needs " + SaveStore.size(need) + ", and the app's storage has " + SaveStore.size(packs.getParentFile().getUsableSpace()) + " free");
            File remembered = Job.remembered(a);
            if (remembered != null && remembered.isFile()) {
                Job j = Job.read(remembered);
                if (SaveStore.same(j.pack(), target) && !"done".equals(j.state()))
                    throw new IOException("a Prepare job of this game writes to " + target + " (" + j.statusText(false) + "): let it finish, or forget it in its ⋯ Manage, first");
            }
            Pack p = PackZip.stage(source, packs, g.titleId);
            try {
                String bad = PackCheck.blocking(a, p, new File(g.gameDir));
                if (bad != null) throw new IOException(bad);
                if (SaveUi.gameRunning(a, g)) throw new IOException(g.name + " was started meanwhile");
                PackZip.swapIn(p.dir, target);
            } finally {
                if (p.dir.exists()) SaveStore.deleteTree(p.dir);
            }
            return target;
        }, (r, err) -> {
            if (err != null) {
                int cut = err.indexOf("\n\n");   // a build check's sentence, then the build IDs
                Ui.explain(a, "Pack not imported", PrepareText.capital(cut > 0 ? err.substring(0, cut) : err) + (err.endsWith(".") ? "" : ".") + " Nothing was changed.",
                        (cut > 0 ? err.substring(cut + 2) + "\n" : "") + "Pack folder: " + target).setPositiveButton("OK", null).show();
                return;
            }
            String old = Folders.pickedPack(a, g.titleId);
            Folders.pickedPack(a, g.titleId, null);   // the card plays the imported pack; a picked folder would win over it at the next Add game
            if (old != null && old.equals(Prefs.folder(a, "pack"))) Prefs.folder(a, "pack", null);
            Folders.setPack(a, g.titleId, target);
            String lp = Pack.libraryProblem(target, g.titleId);
            String shaders = lp == null ? "It has this app's shader library (" + BuildConfig.NX_DMSH_VK_CHAIN + "): ready to play."
                    : Pack.playableLibrary(target, g.titleId) != null || Pack.legacyLibrary(target, g.titleId) != null
                    ? "Its shaders are of an older version: the game plays with them while the app updates them, as after an app update." : lp + " The game's card offers what fixes it.";
            Ui.explain(a, "Pack imported", shaders, "Pack folder: " + target + (old != null ? "\nThe pack folder picked before (" + old + ") is kept as it was; the game now uses this one." : ""))
                    .setPositiveButton("OK", null).show();
        });
    }

    interface Work { Object run() throws IOException; }
    interface Done { void done(Object result, String error); }

    private static void run(MainActivity a, Game g, String wait, Work work, Done done) {
        if (!take(g.titleId)) return;
        AlertDialog w = new PadFocus.Builder(a).setMessage(wait).setCancelable(false).show();
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
        }, "nx-pack-zip").start();
    }
}
