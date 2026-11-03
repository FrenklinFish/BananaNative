package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The launcher's pure-Java helpers on the JVM (no Android): SaveStore (save backup, restore, the roll-forward journal, the lock) and Profile (the APK's game table).
 * Run by dm/tools/tests/test_android_saves.py, which compiles this with SaveStore.java, Profile.java and a stub BuildConfig.
 * <ul>
 * <li>{@code <scratch folder>}: every case; prints "ok <n>" and exits 0, or the first failure and exits 1</li>
 * <li>{@code recover <save>}: SaveStore.recover on that save, printing "recovered", "nothing" or "refused: …" (the journal cross-check against plat_swap_recover)</li>
 * <li>{@code hold <save>}: takes the save's lock, prints "held" and keeps it until stdin closes (another process holding it); {@code holdtwice <save>} asks for it
 *     again and checks inUse while holding it first (the one-channel rule: neither may drop the lock)</li>
 * <li>{@code locked <save>}: expects backup, restore and recover of that save to be refused while another process holds its lock; prints "refused 3"</li>
 * </ul>
 */
public final class LauncherJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    /** A save like the host's: the files the game writes, in DM_SAVE */
    static File save(File root, String tag) throws IOException {
        File s = new File(root, "saves/040074A01BF12000");
        write(new File(s, "slot0.bin"), "slot0 " + tag);
        write(new File(s, "common/common.bin"), "common " + tag);
        new File(s, "empty").mkdirs();
        return s;
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("recover")) {
            try { System.out.println(SaveStore.recover(new File(args[1])) ? "recovered" : "nothing"); } catch (IOException e) { System.out.println("refused: " + e.getMessage()); }
            return;
        }
        if (args[0].equals("hold") || args[0].equals("holdtwice")) {
            try (SaveStore.Lock l = SaveStore.lock(new File(args[1]))) {
                if (args[0].equals("holdtwice") && (SaveStore.tryLock(new File(args[1])) != null || !SaveStore.inUse(new File(args[1])))) throw new AssertionError("held twice");
                System.out.println("held");
                System.out.flush();
                while (System.in.read() >= 0) { /* until stdin closes */ }
            }
            return;
        }
        if (args[0].equals("locked")) { locked(new File(args[1])); return; }
        File t = new File(args[0]);
        profiles();
        backup(new File(t, "backup"));
        restore(new File(t, "restore"));
        device(new File(t, "device"));
        damaged(new File(t, "damaged"));
        crash(new File(t, "crash"));
        markers(new File(t, "markers"));
        lockSameProcess(new File(t, "lock"));
        edges(new File(t, "edges"));
        review3(new File(t, "review3"));
        System.out.println("ok " + n);
    }

    static void profiles() {
        List<Profile> l = Profile.parse("040074A01BF12000\tDonkey Kong Bananza\t22852\tabcdef012345\t458752 main=aa sdk=BB;0 main=cc\nbad line\n"
                + "0400C3F00006E000\t\t0\tunknown\t");
        check(l.size() == 2, "two profiles, the bad line skipped");
        check(l.get(0).name.equals("Donkey Kong Bananza") && l.get(0).shaderTotal == 22852 && l.get(0).shaderInputs.equals("abcdef012345"), "the first profile's fields");
        check(l.get(1).name.equals("0400C3F00006E000"), "a profile without a name is named by its title ID");
        Map<Long, Map<String, String>> b = l.get(0).buildMap();
        check(b.size() == 2 && b.get(458752L).get("sdk").equals("bb") && b.get(0L).get("main").equals("cc"), "the builds, lower case");
        check(l.get(1).buildMap().isEmpty(), "no builds");
        check(Profile.of("040074A01BF12000") != null && Profile.of("0100000000010000") == null, "Profile.of over BuildConfig.NX_PROFILES");
        check(Profile.missing("0100000000010000", "Mario").contains("has no game profile") && Profile.missing("040074A01BF12000", null) == null, "Profile.missing");
    }

    static void backup(File t) throws Exception {
        File s = save(t, "v1"), bk = new File(t, "save-backups/040074A01BF12000");
        SaveStore.Manifest before = SaveStore.scan(s);
        SaveStore.Backup b = SaveStore.backup(s, bk, "20261103-140000");
        check(b.dir.equals(new File(bk, "20261103-140000")) && b.files == 2 && !b.device, "the backup's folder and file count, no device save");
        check(SaveStore.scan(new File(b.dir, "main")).diff(before) == null, "the backup holds the save in main/");
        check(SaveStore.scan(s).diff(before) == null, "the save is untouched");
        check(new File(bk, "20261103-140000" + SaveStore.MANIFEST).isFile() && new File(b.dir, "main/empty").isDirectory(), "the manifest beside it; empty folders kept");
        SaveStore.Backup b2 = SaveStore.backup(s, bk, "20261103-140000");
        check(b2.name.equals("20261103-140000-2"), "a second backup in the same second gets -2");
        new File(bk, SaveStore.PARTIAL + "20261103-150000").mkdirs();   // a backup stopped while it copied (fresh: kept)
        new File(bk, "loose").mkdirs();                                 // a folder without a manifest
        List<SaveStore.Backup> l = SaveStore.list(bk);
        check(l.size() == 2 && l.get(0).name.equals("20261103-140000-2"), "list: the two backups, newest first, nothing else");
        check(l.get(0).source.equals(s.getAbsolutePath()) && l.get(0).bytes == before.bytes, "list: source and size from the manifest");
        try { SaveStore.backup(s, new File(s, "inside"), "x"); check(false, "a backups folder inside the save is refused"); } catch (IOException e) { n++; }
        try { SaveStore.backup(new File(t, "nothing"), bk, "x"); check(false, "no save folder is refused"); } catch (IOException e) { n++; }
    }

    static void restore(File t) throws Exception {
        File s = save(t, "old"), bk = new File(t, "save-backups/040074A01BF12000");
        write(new File(s, "extra.bin"), "only in the old save");
        SaveStore.Manifest old = SaveStore.scan(s);
        File other = save(new File(t, "other"), "new");
        new File(other, "empty").delete();
        write(new File(other, "device-ish/settings.bin"), "settings");
        SaveStore.Backup b = SaveStore.backup(other, bk, "20261101-090000");
        SaveStore.Manifest want = SaveStore.scan(other);
        SaveStore.Restored r = SaveStore.restore(b.dir, s, bk, "20261103-160000");
        check(SaveStore.scan(s).diff(want) == null, "the save is the backup's");
        check(!new File(s, "extra.bin").exists() && !new File(s, "empty").exists(), "what the backup lacks is gone");
        check(r.before != null && r.before.name.equals("20261103-160000" + SaveStore.BEFORE_RESTORE), "the automatic backup first");
        check(SaveStore.scan(new File(r.before.dir, "main")).diff(old) == null, "the automatic backup holds the old save");
        check(!SaveStore.pending(s) && !new File(s.getPath() + SaveStore.RESTORE_SUFFIX).exists(), "no marker, set file or copy left");
        // import from a folder without a manifest, into a save folder that does not exist yet: no automatic backup (nothing to keep)
        File fresh = new File(t, "saves/0400C3F00006E000");
        SaveStore.Restored r2 = SaveStore.restore(other, fresh, new File(t, "save-backups/0400C3F00006E000"), "20261103-170000");
        check(r2.before == null && SaveStore.scan(fresh).diff(SaveStore.scan(other)) == null, "import into a new save folder");
        try { SaveStore.restore(new File(s, "common"), s, bk, "x"); check(false, "a source inside the save is refused"); } catch (IOException e) { n++; }
    }

    /** A game with a device save (<DM_SAVE>_device, hle_fs.c): one backup holds both, a restore swaps both */
    static void device(File t) throws Exception {
        File s = save(t, "a"), d = SaveStore.device(s), bk = new File(t, "save-backups/040074A01BF12000");
        write(new File(d, "island.dat"), "device a");
        SaveStore.Backup b = SaveStore.backup(s, bk, "20261103-100000");
        check(b.device && new File(b.dir, "device/island.dat").isFile() && new File(b.dir, "main/slot0.bin").isFile(), "the backup holds main/ and device/");
        SaveStore.Manifest wantMain = SaveStore.scan(s), wantDev = SaveStore.scan(d);
        write(new File(s, "slot0.bin"), "slot0 b");
        write(new File(d, "island.dat"), "device b");
        write(new File(d, "new.dat"), "only in b");
        SaveStore.Restored r = SaveStore.restore(b.dir, s, bk, "20261103-110000");
        check(r.parts.contains(SaveStore.DEVICE) && SaveStore.scan(s).diff(wantMain) == null && SaveStore.scan(d).diff(wantDev) == null, "both parts restored");
        check(r.before.device && new File(r.before.dir, "device/new.dat").isFile(), "the automatic backup holds the device save too");
        check(!new File(d.getPath() + SaveStore.RESTORE_SUFFIX).exists() && !SaveStore.pending(s), "nothing left beside the device save");
        // stopped after the set file, before the device part got its marker: recover gives it one and finishes both
        write(new File(s, "slot0.bin"), "slot0 c");
        write(new File(d, "island.dat"), "device c");
        File am = new File(s.getPath() + SaveStore.RESTORE_SUFFIX), ad = new File(d.getPath() + SaveStore.RESTORE_SUFFIX);
        SaveStore.copyTree(new File(b.dir, "main"), am);
        SaveStore.copyTree(new File(b.dir, "device"), ad);
        write(SaveStore.setFile(s), s.getAbsolutePath() + "\t" + am.getAbsolutePath() + "\n" + d.getAbsolutePath() + "\t" + ad.getAbsolutePath() + "\n");
        write(SaveStore.marker(s, 1), am.getAbsolutePath());
        check(SaveStore.recover(s), "recover finds the set file");
        check(SaveStore.scan(s).diff(wantMain) == null && SaveStore.scan(d).diff(wantDev) == null && !SaveStore.pending(s) && !am.exists() && !ad.exists(), "both parts finished");
        // a set file naming another folder is refused
        write(SaveStore.setFile(s), new File(t, "elsewhere").getAbsolutePath() + "\t" + am.getAbsolutePath() + "\n");
        try { SaveStore.recover(s); check(false, "a set file naming another folder is refused"); } catch (IOException e) { n++; }
        SaveStore.setFile(s).delete();
    }

    static void damaged(File t) throws Exception {
        File s = save(t, "cur"), bk = new File(t, "save-backups/040074A01BF12000");
        SaveStore.Backup b = SaveStore.backup(s, bk, "20261101-000000");
        write(new File(b.dir, "main/slot0.bin"), "bit rot");
        write(new File(s, "slot0.bin"), "progress since");
        SaveStore.Manifest cur = SaveStore.scan(s);
        try { SaveStore.restore(b.dir, s, bk, "20261103-180000"); check(false, "a damaged backup is refused"); } catch (IOException e) { check(e.getMessage().contains("damaged"), "says damaged"); }
        check(SaveStore.scan(s).diff(cur) == null, "the save is untouched");
        check(SaveStore.list(bk).size() == 1, "no automatic backup was made for a refused restore");
    }

    static void crash(File t) throws Exception {
        // stopped after the marker, before stage 1: recover finishes it
        File s = save(t, "old");
        write(new File(s, "stale.bin"), "stale");
        File a = new File(s.getPath() + SaveStore.RESTORE_SUFFIX);
        write(new File(a, "slot0.bin"), "slot0 new");
        write(new File(a, "x/x.bin"), "x");
        SaveStore.Manifest want = SaveStore.scan(a);
        write(SaveStore.marker(s, 1), a.getAbsolutePath());
        check(SaveStore.recover(s), "recover finds the marker");
        check(SaveStore.scan(s).diff(want) == null && !SaveStore.pending(s) && !a.exists(), "the swap was finished");
        check(!SaveStore.recover(s), "nothing left to recover");
        // stopped in stage 2 of a host commit (b.new): pend2 there, some files moved already
        File nw = new File(s.getPath() + ".new");
        write(new File(nw, "slot0.bin"), "slot0 newer");
        write(new File(nw, "common.bin"), "common newer");
        want = SaveStore.scan(nw);
        SaveStore.deleteTree(new File(s, "x"));
        write(SaveStore.marker(s, 2), nw.getAbsolutePath());
        check(new File(nw, "common.bin").renameTo(new File(s, "common.bin")), "a file moved before the stop");
        SaveStore.recover(s);
        check(SaveStore.scan(s).diff(want) == null && !SaveStore.pending(s), "stage 2 finished");
    }

    /** The journal's rules (the same as plat_swap_recover's): which markers run, which are refused */
    static void markers(File t) throws Exception {
        File s = save(t, "cur"), foreign = new File(t, "foreign");
        write(new File(foreign, "keep.bin"), "not a save");
        SaveStore.Manifest cur = SaveStore.scan(s), fk = SaveStore.scan(foreign);
        // a marker naming anything but <save>.new / .re / .restore
        write(SaveStore.marker(s, 1), foreign.getAbsolutePath());
        try { SaveStore.recover(s); check(false, "a marker naming a foreign folder is refused"); } catch (IOException e) { n++; }
        check(SaveStore.scan(s).diff(cur) == null && SaveStore.scan(foreign).diff(fk) == null, "the save and the foreign folder are untouched");
        write(SaveStore.marker(s, 1), new File(foreign, s.getName() + ".new").getAbsolutePath());   // the right name in another folder
        try { SaveStore.recover(s); check(false, "a sibling's name in another folder is refused"); } catch (IOException e) { n++; }
        // .pend1 whose folder is gone
        write(SaveStore.marker(s, 1), new File(s.getPath() + ".new").getAbsolutePath());
        try { SaveStore.recover(s); check(false, ".pend1 with its folder gone is refused"); } catch (IOException e) { n++; }
        check(SaveStore.scan(s).diff(cur) == null, "and the save is untouched");
        try { SaveStore.backup(s, new File(t, "bk"), "x"); check(false, "no backup while the journal cannot be finished"); } catch (IOException e) { n++; }
        // an empty .pend2 is dropped and .pend1 is still looked at
        File a = new File(s.getPath() + SaveStore.RESTORE_SUFFIX);
        write(new File(a, "only.bin"), "only");
        SaveStore.Manifest want = SaveStore.scan(a);
        write(SaveStore.marker(s, 1), a.getAbsolutePath());
        write(SaveStore.marker(s, 2), "");
        check(SaveStore.recover(s) && SaveStore.scan(s).diff(want) == null && !SaveStore.pending(s), "empty .pend2 dropped, .pend1 finished");
        // .pend2 with its folder gone: stage 2 has nothing left to move, the markers go
        write(SaveStore.marker(s, 2), a.getAbsolutePath());
        check(SaveStore.recover(s) && !SaveStore.pending(s) && SaveStore.scan(s).diff(want) == null, ".pend2 with its folder gone is finished");
        // a legacy <save>.old with no save: it comes back (the host's rule), and then backs up like any save
        File s2 = new File(t, "saves2/0100000000010000"), old = new File(s2.getPath() + ".old");
        write(new File(old, "slot.bin"), "legacy");
        SaveStore.Backup b = SaveStore.backup(s2, new File(t, "bk2"), "20261103-120000");
        check(new File(s2, "slot.bin").isFile() && !old.exists() && new File(b.dir, "main/slot.bin").isFile(), "<save>.old with no save came back and was backed up");
        write(new File(old, "slot.bin"), "stale old");
        SaveStore.recover(s2);
        check(!old.exists() && SaveStore.sha256(new File(s2, "slot.bin")).equals(SaveStore.sha256(new File(b.dir, "main/slot.bin"))), "<save>.old beside a save goes");
    }

    /** Within one process the lock is taken once */
    static void lockSameProcess(File t) throws Exception {
        File s = save(t, "x");
        try (SaveStore.Lock l = SaveStore.lock(s)) {
            check(SaveStore.inUse(s), "inUse while held");
            try { SaveStore.backup(s, new File(t, "bk"), "x"); check(false, "a backup while the lock is held is refused"); } catch (IOException e) { check(e.getMessage().contains("in use"), "says in use"); }
        }
        check(!SaveStore.inUse(s), "free after close");
        SaveStore.backup(s, new File(t, "bk"), "20261103-130000");
        n++;
    }

    /** Another process holds the lock (dm/tools/tests/test_android_saves.py runs "hold" first) */
    static void locked(File s) throws Exception {
        int k = 0;
        try { SaveStore.backup(s, new File(s.getParentFile(), "bk-locked"), "x"); } catch (IOException e) { if (e.getMessage().contains("in use")) k++; }
        try { SaveStore.restore(new File(s.getParentFile(), "src"), s, new File(s.getParentFile(), "bk-locked"), "x"); } catch (IOException e) { if (e.getMessage().contains("in use")) k++; }
        try { SaveStore.recover(s); } catch (IOException e) { if (e.getMessage().contains("in use")) k++; }
        System.out.println("refused " + k + (SaveStore.inUse(s) ? " inUse" : ""));
    }

    /** The re-review's cases: the crash between .pend2 and .pend1's delete, the set phase's empty marker, a restore that would mix device saves, a .partial finished */
    static void review3(File t) throws Exception {
        // both markers (a stop after .pend2 was written, before .pend1 went): .pend1 goes first, then stage 2
        File s = save(t, "old"), a = new File(s.getPath() + ".new");
        write(new File(a, "slot0.bin"), "new");
        SaveStore.Manifest want = SaveStore.scan(a);
        SaveStore.deleteTree(new File(s, "common"));
        SaveStore.deleteTree(new File(s, "empty"));   // stage 1 had run: what a lacks is gone
        write(SaveStore.marker(s, 1), a.getAbsolutePath());
        write(SaveStore.marker(s, 2), a.getAbsolutePath());
        check(SaveStore.recover(s) && SaveStore.scan(s).diff(want) == null && !SaveStore.pending(s) && !a.exists(), "both markers: finished from .pend2");
        // the state an older recovery left by stopping after its stage 2 and before deleting .pend1: .pend1 naming an a of empty folders. Stage 1 would empty the save
        write(new File(s, "more.bin"), "more");
        want = SaveStore.scan(s);
        new File(a, "x/y").mkdirs();
        write(SaveStore.marker(s, 1), a.getAbsolutePath());
        check(SaveStore.recover(s) && SaveStore.scan(s).diff(want) == null && !SaveStore.pending(s) && !a.exists(), ".pend1 naming an emptied copy: the save kept, the marker gone");
        // the set phase: the set file there, main's .pend1 written but empty, the device part without one
        File s2 = save(new File(t, "set"), "old"), d2 = SaveStore.device(s2);
        write(new File(d2, "dev.bin"), "dev old");
        File am = new File(s2.getPath() + SaveStore.RESTORE_SUFFIX), ad = new File(d2.getPath() + SaveStore.RESTORE_SUFFIX);
        write(new File(am, "slot0.bin"), "main new");
        write(new File(ad, "dev.bin"), "dev new");
        SaveStore.Manifest wm = SaveStore.scan(am), wd = SaveStore.scan(ad);
        write(SaveStore.setFile(s2), s2.getAbsolutePath() + "\t" + am.getAbsolutePath() + "\n" + d2.getAbsolutePath() + "\t" + ad.getAbsolutePath() + "\n");
        write(SaveStore.marker(s2, 1), "");
        check(SaveStore.recover(s2) && SaveStore.scan(s2).diff(wm) == null && SaveStore.scan(d2).diff(wd) == null && !SaveStore.pending(s2), "set phase with an empty .pend1: both parts restored");
        // a backup without a device save into a save that has one: refused
        File s3 = save(new File(t, "mix"), "cur"), bk = new File(t, "mix/bk");
        SaveStore.Backup noDev = SaveStore.backup(s3, bk, "20261101-000000");
        write(new File(SaveStore.device(s3), "dev.bin"), "dev");
        SaveStore.Manifest cur = SaveStore.scanParts(SaveStore.parts(s3));
        try { SaveStore.restore(noDev.dir, s3, bk, "20261103-000000"); check(false, "a restore that would mix device saves is refused"); } catch (IOException e) { check(e.getMessage().contains("device save"), "says device save"); }
        check(SaveStore.scanParts(SaveStore.parts(s3)).diff(cur) == null, "and nothing changed");
        // a .partial whose manifest is beside it (stopped before the rename) gets its name; one without goes
        SaveStore.Backup b = SaveStore.backup(s3, bk, "20250101-000000");
        File partial = new File(bk, SaveStore.PARTIAL + b.name);
        check(b.dir.renameTo(partial), "made a stopped rename");
        File broken = new File(bk, SaveStore.PARTIAL + "20250102-000000");
        write(new File(broken, "main/x"), "x");
        long old = System.currentTimeMillis() - 2 * SaveStore.STALE_MS;
        partial.setLastModified(old); broken.setLastModified(old);
        SaveStore.cleanStale(bk, System.currentTimeMillis());
        check(b.dir.isDirectory() && !partial.exists() && !broken.exists() && SaveStore.list(bk).stream().anyMatch(x -> x.name.equals(b.name)), "the verified .partial finished, the other removed");
    }

    static void edges(File t) throws Exception {
        File s = save(t, "cur"), bk = new File(t, "save-backups/040074A01BF12000");
        File a = new File(s.getPath() + SaveStore.RESTORE_SUFFIX);
        write(new File(a, "x.bin"), "x");
        try { SaveStore.restore(a, s, bk, "x"); check(false, "import from <save>.restore is refused"); } catch (IOException e) { n++; }
        try { SaveStore.restore(new File(s.getPath() + ".work"), s, bk, "x"); check(false, "import from <save>.work is refused"); } catch (IOException e) { n++; }
        // a path through a link to the save is the save (canonical paths)
        File link = new File(t, "link");
        Files.createSymbolicLink(link.toPath(), s.getParentFile().toPath());
        check(SaveStore.inside(new File(link, s.getName() + "/common"), s), "inside() by canonical paths");
        check(SaveStore.refuseSource(new File(link, s.getName()), s) != null, "the save through a link is refused as a source");
        // stale leftovers: old .partial-* and orphan manifests go, a finished backup (however old) and fresh leftovers stay
        SaveStore.Backup b = SaveStore.backup(s, bk, "20250101-000000");
        File partial = new File(bk, SaveStore.PARTIAL + "20250102-000000"), orphan = new File(bk, "20250103-000000" + SaveStore.MANIFEST), fresh = new File(bk, SaveStore.PARTIAL + "now");
        write(new File(partial, "main/x"), "x");
        write(orphan, "# nxnative save backup 1\n");
        fresh.mkdirs();
        long old = System.currentTimeMillis() - 2 * SaveStore.STALE_MS;
        for (File f : new File[] {partial, orphan, b.dir, new File(bk, b.name + SaveStore.MANIFEST)}) f.setLastModified(old);
        List<File> gone = SaveStore.cleanStale(bk, System.currentTimeMillis());
        check(gone.size() == 2 && !partial.exists() && !orphan.exists(), "stale .partial and orphan manifest removed");
        check(b.dir.isDirectory() && new File(bk, b.name + SaveStore.MANIFEST).isFile() && fresh.isDirectory(), "the finished backup and fresh leftovers stay");
        // the post-restore comparison ignores case (shared storage keeps a file's old case when a rename only changes it)
        SaveStore.Manifest x = new SaveStore.Manifest(), y = new SaveStore.Manifest();
        x.entries.put("Slot.bin", "f aa 1");
        y.entries.put("slot.bin", "f aa 1");
        check(x.diff(y) != null && x.diff(y, true) == null, "case-insensitive comparison");
    }
}
