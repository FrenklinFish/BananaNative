package dev.nxnative.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Saves without All files access (SaveZip) and a game whose file is gone (GameFile), on the JVM: where a backup goes, a backup written as a zip and read back, a
 * changed zip caught, Import's unpacking (zip slip and limits refused) and what it restores from, then a restore from an imported zip through SaveStore. Compiled
 * with SaveZip, SaveStore, PackZip, CopyPlan, GameFile, GameList and their dependencies. Argument: a scratch folder. Prints "ok &lt;n&gt;".
 */
public final class SaveZipJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    static byte[] zip(String[][] entries) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (String[] e : entries) {
                z.putNextEntry(new ZipEntry(e[0]));
                if (e.length > 1) z.write(e[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    static String refused(byte[] zip, File stage) {
        try {
            SaveZip.unpack(new ByteArrayInputStream(zip), stage);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        File t = new File(args[0]);
        where(t);
        roundTrip(t);
        unpacking(t);
        gone();
        System.out.println("ok " + n);
    }

    static void where(File t) {
        File app = new File(t, "Android/data/dev.nxnative.android/files"), shared = new File(t, "Switch2/save-backups/040074A01BF12000");
        check(SaveZip.appBackups(app, "040074A01BF12000").equals(new File(app, "save-backups/040074A01BF12000")), "the app's own backups folder");
        check(!SaveZip.export(shared, app, true), "a writable shared folder: backups go there");
        check(SaveZip.export(shared, app, false), "not writable (no All files access): export a zip");
        check(SaveZip.export(new File(app, "picked/0123456789abcdef/save-backups/040074A01BF12000"), app, true), "a folder inside app storage: export (it goes with the app)");
        new File(t, "Switch2").mkdirs();
        check(SaveZip.writable(shared) && !SaveZip.writable(new File("/nonexistent-root-xyz/a")), "writable: the nearest existing folder decides");
        check(SaveZip.zipName("040074A01BF12000", "20261108-101010").equals("save-040074A01BF12000-20261108-101010.zip"), "zip name");
    }

    static void roundTrip(File t) throws IOException {
        File save = new File(t, "rt/saves/040074A01BF12000"), backups = new File(t, "rt/backups");
        write(new File(save, "user/slot0.bin"), "slot zero");
        write(new File(save, "system.dat"), "system");
        new File(save, "empty").mkdirs();
        write(new File(SaveStore.device(save), "device.bin"), "device");
        SaveStore.Backup b = SaveStore.backup(save, backups, "20261108-101010");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SaveZip.write(b.dir, out);
        byte[] zip = out.toByteArray();
        check(SaveZip.check(new ByteArrayInputStream(zip), b.dir) == null, "the zip reads back as the backup: " + SaveZip.check(new ByteArrayInputStream(zip), b.dir));
        // the zip's entries: the manifest and the backup folder
        List<String> names = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) { for (ZipEntry e; (e = z.getNextEntry()) != null; ) names.add(e.getName()); }
        check(names.contains("20261108-101010.manifest.tsv") && names.contains("20261108-101010/main/user/slot0.bin") && names.contains("20261108-101010/device/device.bin")
                && names.contains("20261108-101010/main/empty/"), "zip entries " + names);
        // a changed byte, a missing file
        byte[] bad = zip(new String[][] {{"20261108-101010.manifest.tsv", read(new File(backups, "20261108-101010.manifest.tsv"))}, {"20261108-101010/main/user/slot0.bin", "slot zerO"}});
        String d = SaveZip.check(new ByteArrayInputStream(bad), b.dir);
        check(d != null, "a changed zip is caught: " + d);
        check(SaveZip.check(new ByteArrayInputStream(zip(new String[][] {{"x/y", "z"}})), b.dir).contains("manifest"), "a zip without the manifest is caught");
        // import it into another save: unpacked, its manifest checked by SaveStore.source, restored
        File stage = new File(t, "rt/stage");
        SaveZip.unpack(new ByteArrayInputStream(zip), stage);
        File from = SaveZip.importRoot(stage);
        check(from.equals(new File(stage, "20261108-101010")), "a backup zip: its folder, beside its manifest " + from);
        File other = new File(t, "rt/other/040074A01BF12000");
        write(new File(other, "old.bin"), "old");
        SaveStore.Restored r = SaveStore.restore(from, other, new File(t, "rt/other-backups"), "20261108-111111");
        check(read(new File(other, "user/slot0.bin")).equals("slot zero") && !new File(other, "old.bin").exists() && read(new File(SaveStore.device(other), "device.bin")).equals("device"),
                "restored from the imported zip, with the device save");
        check(r.before != null && new File(r.before.dir, "main/old.bin").isFile(), "the save it replaced was backed up first");
        // a damaged backup zip (the file changed, the manifest not): unpacked, then refused by SaveStore.source
        File stage2 = new File(t, "rt/stage2");
        SaveZip.unpack(new ByteArrayInputStream(bad), stage2);
        try {
            SaveStore.restore(SaveZip.importRoot(stage2), other, new File(t, "rt/other-backups"), "20261108-121212");
            check(false, "a damaged backup is restored");
        } catch (IOException e) {
            check(e.getMessage().contains("does not match its manifest") && read(new File(other, "user/slot0.bin")).equals("slot zero"), "a damaged backup is refused, the save unchanged: " + e.getMessage());
        }
    }

    static void unpacking(File t) throws IOException {
        int k = 0;
        for (String bad : new String[] {"../evil", "a/../../evil", "/abs", "a\\b", "C:/x", "a//b", "./a"}) {
            File st = new File(t, "slip" + k++);
            String why = refused(zip(new String[][] {{"ok.txt", "1"}, {bad, "x"}}), st);
            check(why != null && why.contains("outside"), "zip slip refused: " + bad + " → " + why);
            check(!new File(t, "evil").exists(), "nothing written outside");
        }
        check(refused(zip(new String[][] {}), new File(t, "empty")) != null, "an empty zip is refused");
        // plain save zips
        File s1 = new File(t, "plain1");
        SaveZip.unpack(new ByteArrayInputStream(zip(new String[][] {{"slot0.bin", "a"}, {"sub/x", "b"}})), s1);
        check(SaveZip.importRoot(s1).equals(s1), "files at the top: the zip is the save");
        File s2 = new File(t, "plain2");
        SaveZip.unpack(new ByteArrayInputStream(zip(new String[][] {{"040074A01BF12000/slot0.bin", "a"}})), s2);
        check(SaveZip.importRoot(s2).equals(new File(s2, "040074A01BF12000")), "one top folder: that folder");
        File s3 = new File(t, "plain3");
        SaveZip.unpack(new ByteArrayInputStream(zip(new String[][] {{"040074A01BF12000/slot0.bin", "a"}, {"040074A01BF12000_device/d.bin", "d"}})), s3);
        File r3 = SaveZip.importRoot(s3);
        check(r3.equals(new File(s3, "040074A01BF12000")) && SaveStore.device(r3).isDirectory(), "a save and its device save: the save, the device save beside it");
        File s4 = new File(t, "plain4");
        SaveZip.unpack(new ByteArrayInputStream(zip(new String[][] {{"b/main/slot0.bin", "a"}, {"b/device/d.bin", "d"}})), s4);
        File r4 = SaveZip.importRoot(s4);
        check(read(new File(r4, "slot0.bin")).equals("a") && read(new File(SaveStore.device(r4), "d.bin")).equals("d"), "a backup folder without its manifest: main/ and device/ laid out as a save " + r4);
    }

    static final class G implements GameList.View {
        final String id, dir;
        G(String id, String dir) { this.id = id; this.dir = dir; }
        public String titleId() { return id; }
        public String gameDir() { return dir; }
        public long used() { return 0; }
    }

    static void gone() {
        check(GameFile.goneText("Game.nup").equals("The game file is gone: Game.nup. Pick it again, or remove the game."), "the gone sentence");
        check(GameFile.gone(false, true) && !GameFile.gone(true, true) && !GameFile.gone(false, false), "gone: not there where the app may read; unknown without access");
        check(GameFile.relinkProblem("040074A01BF12000", "DKB", "040074A01BF12000", "New.nup") == null, "the same title: relinked");
        check(GameFile.relinkProblem("040074A01BF12000", "DKB", "040074a01bf12000", "New.nup") == null, "title IDs in either case");
        check(GameFile.relinkProblem("040074A01BF12000", "DKB", "0400C3F00006E000", "Other.nup").contains("another game"), "another title: refused");
        check(GameFile.relinkProblem("040074A01BF12000", "DKB", null, "x").contains("not a game"), "no title: refused");
        List<G> l = List.of(new G("A", "/a/one.nup"), new G("A", "/a/two.nup"));
        check(GameFile.index(l, "/a/two.nup") == 1 && GameFile.index(l, "/a/three.nup") == -1, "the card to relink");
        // a remembered pack folder that is gone: the search's pack, else the app's packs/<id>; never the gone one
        String app = "/data/files/packs/A";
        check(GameFile.packFix("/sd/old-pack", true, null, app) == null, "a pack that is there: unchanged");
        check(GameFile.packFix("/sd/old-pack", false, app, app).equals(app), "gone: the app's pack the search found");
        check(GameFile.packFix("/sd/old-pack", false, "/sd/A-work/android-pack", app).equals("/sd/A-work/android-pack"), "gone: another pack the search found");
        check(GameFile.packFix("/sd/old-pack", false, null, app).equals(app), "gone, none found: the app's packs/<id>, where Prepare puts one");
        check(GameFile.packFix(app, false, null, app) == null && GameFile.packFix("/sd/old-pack", false, "/sd/old-pack", app).equals(app), "never the gone folder itself");
        // a picked save folder that is gone
        check(GameFile.saveGone("/sd/saves/A", false, true), "picked, not there: gone");
        check(!GameFile.saveGone("/sd/saves/A", true, true) && !GameFile.saveGone(null, false, true) && !GameFile.saveGone("/sd/saves/A", false, false),
                "there, not picked, or not to be told without access: not gone");
        check(GameFile.saveGoneText("A").equals("The save folder A is gone. Use the app's save, or pick the folder again."), "the save sentence");
    }
}
