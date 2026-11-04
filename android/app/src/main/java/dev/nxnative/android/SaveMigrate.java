package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * The one-time copy of a game's save from its old default folder on shared storage (&lt;Switch2 root&gt;/saves/&lt;id&gt; for the play app, saves-test/&lt;id&gt; for
 * the test app, the root being the folder that holds the game folder or the .nup: Prefs.oldSaveDefault) into the app's own storage (AppStorage.save:
 * Android/data/&lt;package&gt;/files/saves/&lt;id&gt;), with its device save &lt;save&gt;_device. No Android classes: the JVM test (LauncherJvmTest, run by
 * dm/tools/tests/test_android_saves.py) runs it on the Mac.
 * <ul>
 * <li>The old folder is only read: never written, moved or deleted (a lock file &lt;old&gt;.lock beside it, as every game start and backup of it took before, is all
 *     that is made there). An old save with an unfinished commit or restore (the host's or SaveStore's journal) is not copied: the start uses the old folder, whose
 *     recovery finishes it, and the next start copies.</li>
 * <li>Never over a save: an app save whose main folder holds a file is left as it is (APP_HAS_SAVE), and so is one with a journal of its own.</li>
 * <li>Under both saves' locks (SaveStore.Lock), so no backup, restore or game of either runs beside the copy.</li>
 * <li>The copy goes into a staging folder beside the app save (&lt;saves&gt;/.&lt;id&gt;.migrate/main, device), is checked file by file (path, size, SHA-256) against
 *     the old save, the old save is scanned again (a save that changed during the copy fails), and only then is it renamed into place: the device save first, the
 *     main save last. The main save appearing is the commit: until then the app save is empty and the next start copies again (a device save left by a copy that
 *     stopped between the two renames is the old one's exact copy, checked, and is made again). Any failure leaves no app save to play from (FAILED: the caller
 *     uses the old folder for that start and says so).</li>
 * </ul>
 */
final class SaveMigrate {
    enum State {
        /** the old save was copied in and checked: play the app save */
        COPIED,
        /** the app save has a save already (or a journal): nothing copied, play it */
        APP_HAS_SAVE,
        /** the old folder has no save: nothing copied, play the app save (empty: a new save) */
        NO_OLD_SAVE,
        /** nothing usable was copied: play the old folder for this start (message says why) */
        FAILED
    }

    static final class Result {
        final State state;
        final String message;
        final SaveStore.Manifest copied;   // COPIED: what was copied (main/…, device/…)

        Result(State s, String m, SaveStore.Manifest c) { state = s; message = m; copied = c; }

        @Override public String toString() { return state + (message != null ? ": " + message : ""); }
    }

    static final String STAGING = ".migrate";

    private SaveMigrate() {}

    /** Whether a save folder (main or its device save) holds any file */
    static boolean hasSave(File save) { return SaveStore.hasFiles(save) || SaveStore.hasFiles(SaveStore.device(save)); }

    /** Whether the old save would be copied at the next start: the app save holds no file and the old one does (Folders.save points at the old one until then) */
    static boolean needed(File old, File app) {
        return !SaveStore.same(old, app) && !SaveStore.hasFiles(app) && !SaveStore.pending(app) && hasSave(old);
    }

    /** The staging folder beside the app save: &lt;parent&gt;/.&lt;id&gt;.migrate */
    static File staging(File app) { return new File(app.getAbsoluteFile().getParentFile(), "." + app.getName() + STAGING); }

    /** Whether the old save has an unfinished commit or restore (plat_android.c's markers, SaveStore's set file, the legacy .old) */
    static boolean oldPending(File old) {
        if (SaveStore.pending(old)) return true;
        for (File b : new File[] {old, SaveStore.device(old)}) {
            if (new File(b.getPath() + ".old").exists()) return true;
            for (int k = 1; k <= 2; k++) if (SaveStore.marker(b, k).exists()) return true;
        }
        return false;
    }

    /** Copies the old save into the app save when the app save is empty (see the class comment); never throws */
    static Result migrate(File old, File app) {
        if (SaveStore.same(old, app)) return new Result(State.APP_HAS_SAVE, null, null);
        if (SaveStore.hasFiles(app) || SaveStore.pending(app)) return new Result(State.APP_HAS_SAVE, null, null);
        if (!hasSave(old)) return new Result(State.NO_OLD_SAVE, null, null);
        File stage = staging(app);
        try (SaveStore.Lock la = SaveStore.lock(app); SaveStore.Lock lo = SaveStore.lock(old)) {
            // again under the locks: a restore or a game may have written either before they were taken
            if (SaveStore.hasFiles(app) || SaveStore.pending(app)) return new Result(State.APP_HAS_SAVE, null, null);
            if (!hasSave(old)) return new Result(State.NO_OLD_SAVE, null, null);
            if (oldPending(old)) return new Result(State.FAILED, "the save in " + old + " has an unfinished commit or restore: it is finished at this start, and copied at the next", null);
            Map<String, File> parts = SaveStore.parts(old);
            if (!old.isDirectory()) parts.remove(SaveStore.MAIN);
            SaveStore.Manifest want = SaveStore.scanParts(parts);
            want.source = old.getAbsolutePath();
            File appDevice = SaveStore.device(app);
            if (SaveStore.hasFiles(appDevice)) {   // left by a copy that stopped between its two renames: the old device save's exact copy, made again below
                String d = parts.containsKey(SaveStore.DEVICE) ? want.sub(SaveStore.DEVICE).diff(SaveStore.scan(appDevice)) : "the old save has none";
                if (d != null) return new Result(State.FAILED, appDevice + " holds a device save but " + app + " has no save, and it is not a copy of " + SaveStore.device(old) + " (" + d + ")", null);
            }
            if (stage.exists()) SaveStore.deleteTree(stage);   // a copy that stopped before its renames: only the launcher writes here
            if (stage.exists() || !stage.mkdirs()) throw new IOException("cannot create " + stage);
            for (Map.Entry<String, File> e : parts.entrySet()) SaveStore.copyTree(e.getValue(), new File(stage, e.getKey()));
            String d = want.diff(SaveStore.scan(stage));
            if (d != null) throw new IOException("the copy does not match the save (" + d + ")");
            String changed = want.diff(SaveStore.scanParts(parts));
            if (changed != null) throw new IOException("the save changed while it was copied (" + changed + ")");
            // device first, main last: the main save appearing is the commit
            File sd = new File(stage, SaveStore.DEVICE), sm = new File(stage, SaveStore.MAIN);
            if (appDevice.exists()) SaveStore.deleteTree(appDevice);   // empty folders, or the checked copy above
            if (sd.isDirectory() && !sd.renameTo(appDevice)) throw new IOException("cannot move " + sd + " to " + appDevice);
            if (app.exists()) SaveStore.deleteTree(app);   // no file in it (checked under the lock)
            if (app.exists()) throw new IOException("cannot clear the empty " + app);
            if (sm.isDirectory()) { if (!sm.renameTo(app)) throw new IOException("cannot move " + sm + " to " + app); }
            else if (!app.mkdirs()) throw new IOException("cannot create " + app);
            SaveStore.dirSync(app);
            Map<String, File> now = new java.util.LinkedHashMap<>();
            for (String k : parts.keySet()) now.put(k, k.equals(SaveStore.MAIN) ? app : appDevice);
            String after = want.diff(SaveStore.scanParts(now));
            if (after != null) {   // never seen: a rename is atomic. The app copy goes (the old save is untouched), so nothing half is played
                SaveStore.deleteTree(app);
                SaveStore.deleteTree(appDevice);
                throw new IOException("the app's copy does not match the save after the move (" + after + ")");
            }
            return new Result(State.COPIED, null, want);
        } catch (IOException | RuntimeException e) {
            return new Result(State.FAILED, e.getMessage(), null);
        } finally {
            if (stage.exists()) SaveStore.deleteTree(stage);
        }
    }
}
