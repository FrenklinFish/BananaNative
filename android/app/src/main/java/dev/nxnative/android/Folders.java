package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Folders… of one game (its Manage sheet, or Settings › Folders, which asks which game when there are several): the game folder, the pack folder (pack.tsv and
 * shaders/) and the save folder, each picked with the system folder picker and kept per title ID in the app's preferences (Prefs.folder), so an APK update keeps them
 * and the user never has to pick or pack again. Unset = the defaults: the best pack PackFind finds (the app's storage, packs/<id>, AppStorage, first, then
 * beside the game, then <id>-work/<any name> in the game's folder and up to three folders above it); the save in the app's storage (saves/<id>), into which an older version's save beside the game
 * (<Switch2 root>/saves(-test)/<id>) is copied at the game's next start (SaveMigrate; until then that folder is the save). A picked save folder is given to the
 * host as it is (GameActivity.saveDir); when it is empty and the current one has a save, the user is asked before anything is copied, and nothing is ever deleted
 * or moved.
 */
final class Folders {
    static final int PICK_PACK = 20, PICK_SAVE = 21;

    /** The game whose folders the dialog shows: the pickers' results (activityResult) are for it */
    private static Game current;

    private Folders() {}

    /** The pack folder the user picked for a title (null: none). The older single-game key "pack" still counts for the title whose pack it holds */
    static String pickedPack(Context a, String id) {
        String p = Prefs.folder(a, "pack_" + id);
        if (p != null) return p;
        String old = Prefs.folder(a, "pack");
        try {
            if (old != null && Pack.read(new File(old)).titleId.equals(id)) return old;
        } catch (IOException e) {
            // no longer a pack
        }
        return null;
    }

    static void pickedPack(Context a, String id, String path) { Prefs.folder(a, "pack_" + id, path); }

    /**
     * The pack folder for a game folder: the best pack PackFind finds (the picked folder, the app's packs/&lt;id&gt;, beside and inside the game, then &lt;id&gt;-work/&lt;any&gt;
     * in the game's folder and its ancestors) that plays now or after the automatic shader update; else, as before, the picked one when it is a pack of this title,
     * else the app's, else Pack.find's (Prepare then redoes what does not fit there). null when there is no pack of this title at all. Reads pack.tsv files and the
     * game's module headers: call it off the main thread
     */
    static File packFor(Context a, File game, String id) {
        String p = pickedPack(a, id);
        File picked = p != null && isPackOf(new File(p), id) ? new File(p) : null;   // a picked folder that lost its pack is not looked at
        PackFind.Found f = find(a, picked, game, id);
        if (f != null) return f.dir;
        return picked != null ? picked : oldDefault(a, game, id);
    }

    /**
     * A card whose remembered pack folder is gone (GameFile.packFix): the pack the usual search finds instead (app storage first), else the app's packs/&lt;id&gt;; a
     * picked pack folder that is gone is forgotten. The list entry is updated; returns the card as it is now. Reads packs: off the main thread
     */
    static Game repack(Context a, Game g) {
        File pd = new File(g.packDir);
        boolean there = new File(pd, "pack.tsv").isFile();
        if (there || !android.os.Environment.isExternalStorageManager() && !CopyPlan.inApp(AppStorage.root(a), g.packDir)) return g;   // there, or not to be told without access
        String picked = Prefs.folder(a, "pack_" + g.titleId);
        if (picked != null && !new File(picked).exists()) pickedPack(a, g.titleId, null);   // a picked pack folder that is gone: the search below takes over
        File found = packFor(a, new File(g.gameDir), g.titleId);
        String fix = GameFile.packFix(g.packDir, false, found != null && isPackOf(found, g.titleId) ? found.getPath() : null, AppStorage.pack(a, g.titleId).getPath());
        if (fix == null) return g;
        android.util.Log.i("nxnative", g.titleId + ": the pack folder " + g.packDir + " is gone; using " + fix);
        Game n = new Game(g.titleId, g.name, g.gameDir, fix, g.used);
        synchronized (Game.class) {
            List<Game> list = Game.load(a);
            int i = GameFile.index(list, g.gameDir);
            if (i >= 0 && list.get(i).packDir.equals(g.packDir)) { list.set(i, new Game(g.titleId, g.name, g.gameDir, fix, list.get(i).used)); Game.save(a, list); }
        }
        return n;
    }

    /** A picked save folder that is gone (GameFile.saveGone), else null */
    static File goneSave(Context a, Game g) {
        String s = Prefs.folder(a, "save_" + g.titleId);
        boolean readable = android.os.Environment.isExternalStorageManager() || (s != null && CopyPlan.inApp(AppStorage.root(a), s));
        return GameFile.saveGone(s, s != null && new File(s).isDirectory(), readable) ? new File(s) : null;
    }

    /** The picked save folder that is gone: the app's own save from now on (the picked one is forgotten), or the folder picker */
    static void saveGoneDialog(MainActivity a, Game g, File gone) {
        Ui.explain(a, "Save folder gone", GameFile.saveGoneText(gone.getName()), gone.getPath())
                .setPositiveButton("Use the app's save", (x, w) -> { Prefs.folder(a, "save_" + g.titleId, null); a.refresh(); })
                .setNeutralButton("Pick the folder", (x, w) -> { current = g; a.pickFolder(PICK_SAVE, "Pick the folder for " + g.name + "'s save"); })
                .setNegativeButton("Cancel", null).show();
    }

    /** The default pack folder (the Folders "Default" button): packFor without the picked folder */
    static File defaultPack(Context a, File game, String id) {
        PackFind.Found f = find(a, null, game, id);
        return f != null ? f.dir : oldDefault(a, game, id);
    }

    /** PackFind over this game's candidates, with PackCheck.blocking as the judge of the code */
    static PackFind.Found find(Context a, File picked, File game, String id) {
        List<File> c = PackFind.candidates(picked, AppStorage.pack(a, id), game, id, PackFind.volumeRoot(game));
        return PackFind.best(c, id, Pack.isDump(game), p -> PackCheck.blocking(a, p, game));
    }

    /** The app's packs/&lt;id&gt; when it holds this title's pack, else the older places beside the game (Pack.find) */
    private static File oldDefault(Context a, File game, String id) {
        File app = AppStorage.pack(a, id);
        return isPackOf(app, id) ? app : Pack.find(game, id);
    }

    private static boolean isPackOf(File dir, String id) {
        try {
            return Pack.read(dir).titleId.equals(id);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The save folder the next start of the game uses (the host's DM_SAVE: GameActivity.saveDir): the picked one, else the app's saves/<id>, except while an older
     * version's save beside the game waits to be copied in (SaveMigrate.needed): then that one, which the next start copies, and which Back up / Restore use until then
     */
    static File save(Context a, Game g) {
        if (g == null) return null;
        String s = Prefs.folder(a, "save_" + g.titleId);
        if (s != null) return new File(s);
        File app = AppStorage.save(a, g.titleId), old = Prefs.oldSaveDefault(new File(g.gameDir), g.titleId);
        return Prefs.migratedFrom(a, g.titleId) == null && SaveMigrate.needed(old, app) ? old : app;
    }

    /** The default save folder's state in words: in app storage (and copied there from where), or still beside the game until the next start copies it */
    static String saveState(Context a, Game g, File save) {
        File app = AppStorage.save(a, g.titleId);
        if (!SaveStore.same(save, app)) return " (default: your save from an earlier version, beside the game. The game's next start copies it into the app's storage, "
                + app + ", and plays it from there; this folder is never changed)";
        String from = Prefs.migratedFrom(a, g.titleId);
        return " (default: in the app's storage" + (from != null ? "; copied from " + from + ", which is kept as it was" : "")
                + ")." + (SaveUi.exports(a, g) ? " Back up saves makes a .zip in a place you pick, so it stays when the app is uninstalled."
                : " Back up saves puts copies in " + SaveUi.backups(g) + ", on shared storage: they stay when the app is uninstalled.");
    }

    /** Settings › Folders: the only game's folders, else the user picks the game first */
    static void dialog(MainActivity a) {
        List<Game> list = Game.load(a);
        if (list.isEmpty()) { a.error("No games yet: + Add game first. Each game has its own folders (its card's ⋯ › Folders…)."); return; }
        if (list.size() == 1) { dialog(a, list.get(0)); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).name + "  (" + list.get(i).titleId + ")";
        new AlertDialog.Builder(a).setTitle("Folders of which game?").setItems(names, (d, w) -> dialog(a, list.get(w))).setNegativeButton("Cancel", null).show();
    }

    static void dialog(MainActivity a, Game g) {
        current = g;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];

        String pack = pickedPack(a, g.titleId);
        String lp = Pack.libraryProblem(new File(g.packDir), g.titleId);
        String packState = "\n" + (lp == null ? "Shader library " + BuildConfig.NX_DMSH_VK_CHAIN + ": present." : lp);
        File save = save(a, g);
        String saveKey = "save_" + g.titleId;

        row(a, box, Nup.isNup(new File(g.gameDir)) ? "Game (.nup file)" : "Game folder", g.gameDir, "Pick…", v -> { d[0].dismiss(); a.addGame(); }, null, null);
        boolean inApp = SaveStore.inside(new File(g.packDir), AppStorage.packs(a));
        row(a, box, "Pack and shader folder (pack.tsv, shaders/)", g.packDir + (pack != null ? " (picked)" : inApp ? " (default: in the app's storage)" : " (default: beside the game)") + packState,
                "Pick…", v -> { d[0].dismiss(); a.pickFolder(PICK_PACK, "Pick the pack folder (the one with pack.tsv and shaders/)"); },
                pack == null ? null : "Default", v -> {
                    d[0].dismiss();
                    pickedPack(a, g.titleId, null);
                    if (pack.equals(Prefs.folder(a, "pack"))) Prefs.folder(a, "pack", null);   // the older single-game key, when it was this game's
                    File f = defaultPack(a, new File(g.gameDir), g.titleId);
                    if (f != null) setPack(a, g.titleId, f);
                    dialog(a, g);
                });
        boolean savePicked = Prefs.folder(a, saveKey) != null;
        row(a, box, "Save folder", save.getPath() + (savePicked ? " (picked)" : saveState(a, g, save)),
                "Pick…", v -> { d[0].dismiss(); a.pickFolder(PICK_SAVE, "Pick the folder for " + g.name + "'s save"); },
                savePicked ? "Default" : null, v -> { d[0].dismiss(); Prefs.folder(a, saveKey, null); dialog(a, g); });
        Ui.add(box, Ui.small(a, "These are kept when the app is updated. Picking a folder never moves, copies or deletes anything without asking."), 16);
        d[0] = Ui.sheet(a, "Folders · " + g.name, box).setPositiveButton("Done", null).show();
    }

    /** One folder: a soft card with its name, its path (selectable) and its buttons */
    private static void row(MainActivity a, LinearLayout box, String title, String value, String b1, View.OnClickListener l1, String b2, View.OnClickListener l2) {
        LinearLayout c = Ui.inner(a, R.color.bn_tint);
        c.addView(Ui.text(a, title, 16, R.color.bn_text, 700));
        TextView v = Ui.small(a, value);
        v.setTextIsSelectable(true);
        Ui.add(c, v, 4);
        LinearLayout r = Ui.row(a);
        r.addView(Ui.button(a, b1, Ui.Kind.PRIMARY, l1));
        if (b2 != null) {
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.leftMargin = Ui.dp(a, 10);
            r.addView(Ui.button(a, b2, Ui.Kind.QUIET, l2), lp);
        }
        Ui.add(c, r, 10);
        Ui.add(box, c, 12);
    }

    /** Every list entry of this title uses the pack folder from now on */
    static void setPack(MainActivity a, String id, File pack) {
        List<Game> list = Game.load(a);
        for (int i = 0; i < list.size(); i++) {
            Game x = list.get(i);
            if (x.titleId.equals(id)) list.set(i, new Game(x.titleId, x.name, x.gameDir, pack.getPath()));
        }
        Game.save(a, list);
        a.refresh();
    }

    /** Pick a pack folder for whichever game it holds (Prepare's "Pick another pack folder"), not for the game the dialog showed last */
    static void pickPack(MainActivity a) {
        current = null;
        a.pickFolder(PICK_PACK, "Pick the pack folder (the one with pack.tsv and shaders/)");
    }

    static boolean activityResult(MainActivity a, int request, File dir) {
        if (request == PICK_PACK) { packPicked(a, dir); return true; }
        if (request == PICK_SAVE) { savePicked(a, dir); return true; }
        return false;
    }

    private static void packPicked(MainActivity a, File dir) {
        Pack p;
        try {
            p = Pack.read(dir);
        } catch (IOException e) {
            a.error("Not a pack folder: " + e.getMessage());
            return;
        }
        String why = Profile.missing(p.titleId, p.name);
        if (why != null) { a.error(why); return; }
        Game g = current;
        if (g != null && !p.titleId.equals(g.titleId)) { a.error("That pack is for " + p.titleId + ", not " + g.name + " (" + g.titleId + ")."); return; }
        pickedPack(a, p.titleId, dir.getPath());
        setPack(a, p.titleId, dir);
        String lp = Pack.libraryProblem(dir, p.titleId);
        new AlertDialog.Builder(a).setTitle("Pack folder set").setMessage(dir + "\n\n" + (lp == null ? "It has this app's shader library (" + BuildConfig.NX_DMSH_VK_CHAIN + "): ready to play." : lp))
                .setPositiveButton("OK", (x, w) -> { if (g != null) dialog(a, g); }).show();
    }

    private static void savePicked(MainActivity a, File dir) {
        Game g = current;
        if (g == null) return;
        String key = "save_" + g.titleId;
        File old = save(a, g);
        if (old != null && old.getAbsolutePath().equals(dir.getAbsolutePath())) { dialog(a, g); return; }
        if (isEmpty(dir) && old != null && !isEmpty(old)) {
            new AlertDialog.Builder(a).setTitle("Copy the save?")
                    .setMessage("The folder you picked is empty. Your current save is in " + old + ".\n\nCopy it into " + dir + "? The current folder stays as it is: nothing is deleted.")
                    .setPositiveButton("Copy and use", (x, w) -> new Thread(() -> {
                        String err = null;
                        try {
                            copyTree(old, dir);
                        } catch (IOException | RuntimeException e) {
                            err = e.getMessage();
                        }
                        String fe = err;
                        a.runOnUiThread(() -> {
                            if (fe != null) { a.error("The copy failed (" + fe + "); the save folder was not changed."); return; }
                            Prefs.folder(a, key, dir.getPath());
                            dialog(a, g);
                        });
                    }, "nx-save-copy").start())
                    .setNeutralButton("Use it empty", (x, w) -> { Prefs.folder(a, key, dir.getPath()); dialog(a, g); })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        Prefs.folder(a, key, dir.getPath());
        dialog(a, g);
    }

    private static boolean isEmpty(File d) { String[] l = d.list(); return l == null || l.length == 0; }

    /** Copies, never overwrites: a file already in the target stays */
    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            File[] l = from.listFiles();
            if (l != null) for (File f : l) copyTree(f, new File(to, f.getName()));
        } else if (!to.exists()) Files.copy(from.toPath(), to.toPath());   // no attributes: FUSE /sdcard has no xattrs
    }
}
