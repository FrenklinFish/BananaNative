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
 * and the user never has to pick or pack again. Unset = the defaults: the pack beside the game (<game>/../<id>-work/android-pack), the save in
 * <Switch2 root>/saves(-test)/<id>. A picked save folder is given to the host as it is (GameActivity.saveDir); when it is empty and the current one has a save, the
 * user is asked before anything is copied, and nothing is ever deleted or moved.
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

    /** The pack folder for a game folder: the picked one when it is a pack of this title, else the usual places (Pack.find) */
    static File packFor(Context a, File game, String id) {
        String p = pickedPack(a, id);
        if (p != null) {
            try {
                if (Pack.read(new File(p)).titleId.equals(id)) return new File(p);
            } catch (IOException e) {
                // the picked folder lost its pack: the usual places
            }
        }
        return Pack.find(game, id);
    }

    /** The save folder the next start of the game uses (the host's DM_SAVE: GameActivity.saveDir) */
    static File save(Context a, Game g) {
        if (g == null) return null;
        String s = Prefs.folder(a, "save_" + g.titleId);
        if (s != null) return new File(s);
        return Prefs.saveDefault(new File(g.gameDir), g.titleId);
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

        row(a, box, "Game folder", g.gameDir, "Pick…", v -> { d[0].dismiss(); a.addGame(); }, null, null);
        row(a, box, "Pack and shader folder (pack.tsv, shaders/)", g.packDir + (pack == null ? " (default: beside the game)" : " (picked)") + packState,
                "Pick…", v -> { d[0].dismiss(); a.pickFolder(PICK_PACK, "Pick the pack folder (the one with pack.tsv and shaders/)"); },
                pack == null ? null : "Default", v -> {
                    d[0].dismiss();
                    pickedPack(a, g.titleId, null);
                    if (pack.equals(Prefs.folder(a, "pack"))) Prefs.folder(a, "pack", null);   // the older single-game key, when it was this game's
                    File f = Pack.find(new File(g.gameDir), g.titleId);
                    if (f != null) setPack(a, g.titleId, f);
                    dialog(a, g);
                });
        boolean savePicked = Prefs.folder(a, saveKey) != null;
        row(a, box, "Save folder", save.getPath() + (savePicked ? " (picked)" : " (default)"),
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
        String why = Profile.missing(p.titleId, "That pack's game");
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
