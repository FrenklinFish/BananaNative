package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home's list on the JVM (no Android): one card per game (GameList: the same file by any spelling or link, the same title and build, collapsing an older list's
 * duplicates) and the build check of a pack against a dump (BuildCheck: a folder and the .nup made from it, the build IDs that differ, a missing update, a build
 * the app does not support). Compiled with BuildCheck, GameList, Frontend, Nup, Zstd, Pack, Profile, ShaderLibs and a stub BuildConfig by
 * dm/tools/tests/test_android_gamelist.py, which also gives the stub's profile row of the fixture's title (FIX_TID, version 65536, main and sdk of seeds 2 and 3).
 * <ul>
 * <li>{@code <repo> <scratch folder> <python>}: makes two dump folders and .nup files of them (nup_pack.py --raw), then every case; prints "ok &lt;n&gt;" and exits 0,
 *     or the first failure and exits 1</li>
 * <li>{@code real <file.nup> <scratch folder> <title ID> <version> <module=build ID>...}: BuildCheck.build of a pack with those build rows against a real .nup
 *     (read only); prints "real ok" when the check passes, else its detail and exits 1</li>
 * </ul>
 */
public final class GameListJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static final String FIX_TID = "0100000000AB0000";

    static void write(File f, byte[] b) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b);
    }

    static void write(File f, String s) throws IOException { write(f, s.getBytes(StandardCharsets.UTF_8)); }

    /** An NSO-like file: NSO0 and a build ID at 0x40 (byte i = seed * 31 + i), then a body */
    static byte[] nso(int seed, int size) {
        byte[] b = new byte[size];
        b[0] = 'N'; b[1] = 'S'; b[2] = 'O'; b[3] = '0';
        for (int i = 0; i < 32; i++) b[0x40 + i] = (byte) (seed * 31 + i);
        for (int i = 0x100; i < size; i++) b[i] = (byte) (i * 7 + seed);
        return b;
    }

    static String id(int seed) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 16; i++) s.append(String.format("%02x", (seed * 31 + i) & 0xff));
        return s.toString();
    }

    static byte[] npdm(String tid) {
        long id = Long.parseUnsignedLong(tid, 16);
        byte[] b = new byte[0x100];
        b[0] = 'M'; b[1] = 'E'; b[2] = 'T'; b[3] = 'A';
        b[0x70] = (byte) 0x80;
        b[0x80] = 'A'; b[0x81] = 'C'; b[0x82] = 'I'; b[0x83] = '0';
        for (int i = 0; i < 8; i++) b[0x90 + i] = (byte) (id >>> (8 * i));
        return b;
    }

    /** A dump folder: base (main seed 1, sdk seed 3) and update 65536 (main updMain, sdk seed 3), each with a RomFS file */
    static File dump(File d, int updMain) throws IOException {
        write(new File(d, "base/exefs/main"), nso(1, 4096));
        write(new File(d, "base/exefs/sdk"), nso(3, 4096));
        write(new File(d, "base/exefs/main.npdm"), npdm(FIX_TID));
        write(new File(d, "base/romfs/a.txt"), "base\n");
        write(new File(d, "updates/65536/exefs/main"), nso(updMain, 5000));
        write(new File(d, "updates/65536/exefs/sdk"), nso(3, 4096));
        write(new File(d, "updates/65536/exefs/main.npdm"), npdm(FIX_TID));
        write(new File(d, "updates/65536/romfs/b.txt"), "update\n");
        return d;
    }

    static final String FIX2 = "0100000000AC0000";   // a game whose one supported build is its base, "1.8.0" (main seed 11), as Mario Kart World

    /** One version's files of a dump: exefs (main of this seed, sdk seed 3, main.npdm of tid) when main >= 0, a RomFS file when romfs */
    static void version(File dir, String tid, int main, boolean romfs) throws IOException {
        if (main >= 0) {
            write(new File(dir, "exefs/main"), nso(main, 4096 + main));
            write(new File(dir, "exefs/sdk"), nso(3, 4096));
            write(new File(dir, "exefs/main.npdm"), npdm(tid));
        }
        if (romfs) write(new File(dir, "romfs/Shader/x.txt"), "romfs of " + dir.getName() + "\n");
    }

    /**
     * The three layouts (DumpLayout), each as a folder and as a .nup of it: the base alone, an update alone that carries the whole game, both; an update with its
     * own code and the base's RomFS; the build Prepare starts from found by its build IDs wherever it is; another version refused with what it holds and the
     * supported build's display version
     */
    static void layouts(File repo, File run, String python) throws Exception {
        File root = new File(run, "layouts");
        // FIX2 1.8.0 as the base alone (the supported build is version 0)
        File baseOnly = new File(root, "base-only");
        version(new File(baseOnly, "base"), FIX2, 11, true);
        // FIX2 1.8.1 as an update alone that carries the whole game (no base), and as base + update
        File updOnly = new File(root, "update-only");
        version(new File(updOnly, "updates/65537"), FIX2, 12, true);
        File both = new File(root, "both");
        version(new File(both, "base"), FIX2, 10, true);
        version(new File(both, "updates/65537"), FIX2, 12, true);
        // FIX2 1.8.0's code in an update package with the base's RomFS: found by its build, not by its version number
        File codeInUpd = new File(root, "code-in-update");
        version(new File(codeInUpd, "base"), FIX2, 10, true);
        version(new File(codeInUpd, "updates/65537"), FIX2, 11, false);
        // FIX_TID's supported update 65536 alone
        File fixUpd = new File(root, "fix-update-only");
        write(new File(fixUpd, "updates/65536/exefs/main"), nso(2, 5000));
        write(new File(fixUpd, "updates/65536/exefs/sdk"), nso(3, 4096));
        write(new File(fixUpd, "updates/65536/exefs/main.npdm"), npdm(FIX_TID));
        write(new File(fixUpd, "updates/65536/romfs/b.txt"), "update\n");

        for (File folder : new File[] {baseOnly, updOnly, both, codeInUpd, fixUpd}) {
            File nupFile = nup(repo, folder, new File(root, folder.getName() + ".nup"), python);
            for (File d : new File[] {folder, nupFile}) {
                String tag = d.getName();
                check(Pack.titleIdOf(d).equals(folder == fixUpd ? FIX_TID : FIX2), tag + ": title ID " + Pack.titleIdOf(d));
                DumpLayout.Found f = DumpLayout.find(d);
                List<Long> vs = DumpLayout.versions(d);
                if (folder == baseOnly) {
                    check(vs.equals(List.of(0L)) && f.error == null && f.version == 0 && f.buildIds.get("main").startsWith(id(11)), tag + ": base only " + vs + " " + f.error);
                    check(DumpLayout.path(d, 0, "romfs").endsWith(Nup.isNup(d) ? ".nup:base/romfs" : "base/romfs"), tag + ": base romfs " + DumpLayout.path(d, 0, "romfs"));
                    check(Pack.isDump(d), tag + ": base only is a dump");
                } else if (folder == updOnly) {
                    check(vs.equals(List.of(65537L)) && Pack.isDump(d), tag + ": update only " + vs);
                    // said plainly (no build IDs in the main text: they are in detail, for Details), and Try anyway can take it
                    check(f.error != null && f.version < 0 && f.otherBuild && f.error.contains("not a build this app supports (it supports 1.8.0)") && f.error.contains("update 65537")
                            && !f.error.contains(id(12).substring(0, 16)) && f.detail.contains("update 65537 (main " + id(12).substring(0, 16))
                            && f.detail.contains("1.8.0 (the base game, version 0, main " + id(11).substring(0, 16)), tag + ": another version, said plainly: " + f.error + " | " + f.detail);
                    DumpLayout.Found any = DumpLayout.find(d, true);
                    check(any.error == null && any.untested && any.version == 65537 && any.build < 0 && any.buildIds.get("main").startsWith(id(12)),
                            tag + ": Try anyway takes the newest version, untested: " + any.error);
                    check(DumpLayout.path(d, 65537, "romfs").endsWith(Nup.isNup(d) ? ".nup:update/65537/romfs" : "updates/65537/romfs"), tag + ": its own romfs");
                } else if (folder == both) {
                    check(vs.equals(List.of(0L, 65537L)) && f.error != null && f.detail.contains("the base (main " + id(10).substring(0, 16)) && f.error.contains("update 65537"), tag + ": " + f.error);
                } else if (folder == codeInUpd) {
                    check(f.error == null && f.version == 65537 && f.buildIds.get("main").startsWith(id(11)), tag + ": the build in an update package: " + f.error);
                    check(DumpLayout.path(d, 65537, "romfs").endsWith(Nup.isNup(d) ? ".nup:base/romfs" : "base/romfs") && DumpLayout.hasRomfs(d, 65537),
                            tag + ": no RomFS of its own: the base's " + DumpLayout.path(d, 65537, "romfs"));
                    check(DumpLayout.path(d, 65537, "exefs/main").endsWith(Nup.isNup(d) ? ".nup:update/65537/exefs/main" : "updates/65537/exefs/main"), tag + ": its own code");
                    // a pack of that build, version 65537, fits it and is supported (the profile's row is version 0: same build IDs)
                    Pack p = pack(new File(run, "pack-" + tag), FIX2, "65537", "main=" + id(11), "sdk=" + id(3));
                    BuildCheck.Result r = BuildCheck.build(p, d);
                    check(r.ok(), tag + ": pack of the build in an update: " + r.kind + " " + r.detail);
                } else {
                    check(vs.equals(List.of(65536L)) && f.error == null && f.version == 65536, tag + ": supported update alone: " + f.error);
                    Pack p = pack(new File(run, "pack-" + tag), FIX_TID, "65536", "main=" + id(2), "sdk=" + id(3));
                    check(BuildCheck.build(p, d).ok(), tag + ": its pack fits");
                    check((FIX_TID + ":65536:" + id(2)).equals(BuildCheck.gameKey(d, FIX_TID)), tag + ": key " + BuildCheck.gameKey(d, FIX_TID));
                }
            }
        }
        sameBuildOtherVersion(repo, run, python, root, updOnly, both);
        // not a dump: an empty folder, a one-version folder
        File empty = new File(root, "empty");
        empty.mkdirs();
        check(DumpLayout.find(empty).error.contains("not a dump folder") && !Pack.isDump(empty), "empty folder");
        File one = new File(root, "one-version");
        version(one, FIX2, 11, true);
        check(DumpLayout.find(one).error.contains("one version's folder") && !Pack.isDump(one), "one-version folder: " + DumpLayout.find(one).error);
        // OunceCake's unflattened layout: each version's package at program/0/ (code-in-update's case); a Switch 2 Edition's program/1 is not read
        File ounce = new File(root, "ouncecake");
        version(new File(ounce, "base/program/0"), FIX2, 10, true);
        version(new File(ounce, "updates/65537/program/0"), FIX2, 11, false);
        version(new File(ounce, "updates/65537/program/1"), "0400000000AC0001", 13, true);
        DumpLayout.Found of = DumpLayout.find(ounce);
        check(Pack.isDump(ounce) && Pack.titleIdOf(ounce).equals(FIX2) && DumpLayout.versions(ounce).equals(List.of(0L, 65537L)), "ouncecake: a dump " + DumpLayout.versions(ounce));
        check(of.error == null && of.version == 65537 && of.buildIds.get("main").startsWith(id(11)), "ouncecake: the build in program/0: " + of.error);
        check(DumpLayout.path(ounce, 65537, "exefs/main").endsWith("updates/65537/program/0/exefs/main".replace('/', File.separatorChar)), "ouncecake: its code " + DumpLayout.path(ounce, 65537, "exefs/main"));
        check(DumpLayout.path(ounce, 65537, "romfs").endsWith("base/program/0/romfs".replace('/', File.separatorChar)), "ouncecake: the base's RomFS " + DumpLayout.path(ounce, 65537, "romfs"));
        Pack op = pack(new File(run, "pack-ouncecake"), FIX2, "65537", "main=" + id(11), "sdk=" + id(3));
        check(BuildCheck.build(op, ounce).ok(), "ouncecake: its pack fits");
        check(op.romfs(ounce) != null && op.romfs(ounce).getPath().endsWith("base/program/0/romfs".replace('/', File.separatorChar)), "ouncecake: the RomFS without pack.tsv's " + op.romfs(ounce));
    }

    /**
     * A build is its code, not its version number (Mario Kart World "1.8.1" that is 1.8.0's code): a dump whose modules have the supported build's IDs under
     * another version number is that build. Prepare starts from it (DumpLayout.find, with the profile build it is), and the 1.8.0 pack (version 0) plays it as it
     * is: the card's build check passes and the RomFS is that version's (its own, else the base's). Another build ID under any number is still refused
     */
    static void sameBuildOtherVersion(File repo, File run, String python, File root, File otherCode, File otherBoth) throws Exception {
        Pack v0 = pack(new File(run, "pack-v0"), FIX2, "0", "main=" + id(11), "sdk=" + id(3));
        // "1.8.1" alone: an update package with 1.8.0's modules and its own RomFS
        File alone = new File(root, "same-code-update");
        version(new File(alone, "updates/65538"), FIX2, 11, true);
        // "1.8.1" over a base: 1.8.0's base and an update with the same modules and its own RomFS
        File over = new File(root, "same-code-over-base");
        version(new File(over, "base"), FIX2, 11, true);
        version(new File(over, "updates/65538"), FIX2, 11, true);
        // the same, with no RomFS of its own: the base's
        File noRomfs = new File(root, "same-code-no-romfs");
        version(new File(noRomfs, "base"), FIX2, 11, true);
        version(new File(noRomfs, "updates/65538"), FIX2, 11, false);
        for (File folder : new File[] {alone, over, noRomfs}) {
            File nupFile = nup(repo, folder, new File(root, folder.getName() + ".nup"), python);
            for (File d : new File[] {folder, nupFile}) {
                String tag = d.getName();
                DumpLayout.Found f = DumpLayout.find(d);
                check(f.error == null && f.version == 65538 && f.build == 0 && f.buildIds.get("main").startsWith(id(11)), tag + ": Prepare takes it as 1.8.0: " + f.error + " v" + f.version + " b" + f.build);
                check(DumpLayout.versionWith(d, BuildCheck.builds(v0)) == 65538 && v0.gameVersion(d).equals("65538"), tag + ": the pack's build is version " + v0.gameVersion(d));
                BuildCheck.Result r = BuildCheck.build(v0, d);
                check(r.ok(), tag + ": the 1.8.0 pack plays it, no rebuild: " + r.kind + " " + r.detail);
                String romfs = v0.romfsPath(d), want = folder == noRomfs ? (Nup.isNup(d) ? ".nup:base/romfs" : "base/romfs") : (Nup.isNup(d) ? ".nup:update/65538/romfs" : "updates/65538/romfs");
                check(romfs != null && romfs.endsWith(want), tag + ": its RomFS " + romfs + ", want …" + want);
                check(BuildCheck.mod(d, v0.gameVersion(d), "main").label.contains("65538"), tag + ": the code read is the update's");
            }
        }
        // another build ID: refused under any version number (fixed-address cuts never run on another build)
        for (File folder : new File[] {otherCode, otherBoth}) {
            for (File d : new File[] {folder, new File(root, folder.getName() + ".nup")}) {
                String tag = d.getName();
                check(DumpLayout.versionWith(d, BuildCheck.builds(v0)) < 0 && v0.gameVersion(d).equals("0"), tag + ": another build is not the pack's");
                BuildCheck.Result r = BuildCheck.build(v0, d);
                check(!r.ok() && (r.kind == BuildCheck.Kind.OTHER_BUILD || r.kind == BuildCheck.Kind.MISSING), tag + ": refused: " + r.kind + " " + r.detail);
                DumpLayout.Found f = DumpLayout.find(d);
                check(f.error != null && f.error.contains("not a build this app supports") && f.version < 0, tag + ": Prepare refuses: " + f.error);
            }
        }
        // the same main under another number but another sdk: another build, refused
        File mixed = new File(root, "same-main-other-sdk");
        version(new File(mixed, "updates/65538"), FIX2, 11, true);
        write(new File(mixed, "updates/65538/exefs/sdk"), nso(5, 4096));
        File mixedNup = nup(repo, mixed, new File(root, "same-main-other-sdk.nup"), python);
        for (File d : new File[] {mixed, mixedNup}) {
            check(DumpLayout.find(d).error != null && !BuildCheck.build(v0, d).ok() && v0.gameVersion(d).equals("0"), d.getName() + ": one module differs: refused");
        }
    }

    static final String FIX3 = "0100000000AD0000";   // a game with a romfs_check row (System/RegionLangMask.txt 4 main), update 65536 (main seed 2) supported

    /** RegionLangMask.txt as Donkey Kong Bananza has it: line 4 is the first 20 bytes of its main's build ID */
    static String mask(int seed, String level) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 20; i++) s.append(String.format("%02x", (seed * 31 + i) & 0xff));
        return "JPja USen\nNormal\n" + level + "\n" + s;
    }

    /** A FIX3 dump: base (main seed 1, its mask) and update 65536 (main seed 2) whose RomFS mask is updMask (null: no such file; "" : no RomFS of its own) */
    static File fix3(File d, String updMask) throws IOException {
        write(new File(d, "base/exefs/main"), nso(1, 4096));
        write(new File(d, "base/exefs/sdk"), nso(3, 4096));
        write(new File(d, "base/exefs/main.npdm"), npdm(FIX3));
        write(new File(d, "base/romfs/System/RegionLangMask.txt"), mask(1, "100"));
        write(new File(d, "updates/65536/exefs/main"), nso(2, 5000));
        write(new File(d, "updates/65536/exefs/sdk"), nso(3, 4096));
        write(new File(d, "updates/65536/exefs/main.npdm"), npdm(FIX3));
        if (updMask == null) write(new File(d, "updates/65536/romfs/other.txt"), "x");
        else if (!updMask.isEmpty()) write(new File(d, "updates/65536/romfs/System/RegionLangMask.txt"), updMask);
        return d;
    }

    /** The RomFS check (game.tsv romfs_check, RomfsCheck): the patched update's data passes; the base's copy, a missing file, or the base's RomFS under an update's code are refused by Prepare (find) and the card (build), in folders and .nup files */
    static void romfsChecks(File repo, File run, String python) throws Exception {
        File root = new File(run, "romfs");
        Pack p = null;
        String[][] cases = {{"good", mask(2, "320")}, {"base-copy", mask(1, "100")}, {"missing", null}, {"no-own-romfs", ""}};
        for (String[] c : cases) {
            File folder = fix3(new File(root, c[0]), c[1]);
            File nupFile = nup(repo, folder, new File(root, c[0] + ".nup"), python);
            if (p == null) p = pack(new File(run, "pack-fix3"), FIX3, "65536", "main=" + id(2), "sdk=" + id(3));
            for (File d : new File[] {folder, nupFile}) {
                String tag = d.getName();
                DumpLayout.Found f = DumpLayout.find(d);
                BuildCheck.Result r = BuildCheck.build(p, d);
                if (c[0].equals("good")) {
                    check(f.error == null && f.version == 65536, tag + ": patched data passes: " + f.error);
                    check(r.ok(), tag + ": card: " + r.kind + " " + r.detail);
                } else {
                    String want = (Nup.isNup(d) ? "This game .nup's update data" : "This game folder's update data") + " is not version 65536's full update RomFS (System/RegionLangMask.txt: ";
                    check(f.error != null && f.error.startsWith(want) && f.error.contains("expected main's build ID " + id(2).substring(0, 16)), tag + ": Prepare: " + f.error);
                    check(c[0].equals("missing") ? f.error.contains(": missing,") : f.error.contains("line 4 is \"" + mask(1, "").substring(mask(1, "").lastIndexOf('\n') + 1) + "\""),
                            tag + ": says what it found: " + f.error);
                    check(f.error.endsWith(Nup.isNup(d) ? "again as the patched RomFS." : "again as the patched RomFS, or use the .nup."), tag + ": what to do: " + f.error);
                    check(r.kind == BuildCheck.Kind.ROMFS && "Game data of another version".equals(r.label()) && r.detail.startsWith("System/RegionLangMask.txt"), tag + ": card: " + r.kind + " " + r.detail);
                }
            }
        }
        check(RomfsCheck.sameBuild("E25C90C1C136638885659FD55BF8A03B3403489D", "e25c90c1c136638885659fd55bf8a03b") && !RomfsCheck.sameBuild("e25c90c1", "e25c90c1c1366388")
                && !RomfsCheck.sameBuild("100", "e25c90c1c1366388"), "sameBuild");
    }

    static File nup(File repo, File dump, File out, String python) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(python, "-I", new File(repo, "dm/tools/nup_pack.py").getPath(), dump.getPath(), out.getPath(), "--raw").redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0 || !out.isFile()) throw new AssertionError("nup_pack.py failed: " + o);
        return out;
    }

    /** A pack folder (pack.tsv with these build rows, empty patched.bin files) */
    static Pack pack(File dir, String tid, String version, String... builds) throws IOException {
        StringBuilder t = new StringBuilder("format\t1\ntitle_id\t" + tid + "\nname\tFixture\nversion\t" + version + "\ntarget\tandroid\n");
        List<String> mods = new ArrayList<>();
        for (String b : builds) { t.append("build\t").append(b).append('\n'); mods.add(b.substring(0, b.indexOf('='))); }
        t.append("modules\t").append(String.join(" ", mods)).append("\nromfs\tupdates/").append(version).append("/romfs\n");
        write(new File(dir, "pack.tsv"), t.toString());
        for (String m : mods) write(new File(dir, m + ".patched.bin"), new byte[0]);
        return Pack.read(dir);
    }

    /** A list entry */
    static final class E implements GameList.View {
        final String tid, dir;
        final long used;
        final boolean ready;

        E(String tid, String dir, long used, boolean ready) { this.tid = tid; this.dir = dir; this.used = used; this.ready = ready; }

        @Override public String titleId() { return tid; }
        @Override public String gameDir() { return dir; }
        @Override public long used() { return used; }
        @Override public String toString() { return dir; }
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("real")) { real(a); return; }
        File repo = new File(a[0]), run = new File(a[1]);
        String python = a[2];
        buildCheck(repo, run, python);
        layouts(repo, run, python);
        romfsChecks(repo, run, python);
        gameList(run);
        System.out.println("ok " + n);
    }

    static void buildCheck(File repo, File run, String python) throws Exception {
        File d1 = dump(new File(run, "Game"), 2), d2 = dump(new File(run, "GameOther"), 9);
        File n1 = nup(repo, d1, new File(run, "Game.nup"), python), n2 = nup(repo, d2, new File(run, "GameOther.nup"), python);
        Pack p = pack(new File(run, "pack"), FIX_TID, "65536", "main=" + id(2), "sdk=" + id(3));

        // The pack fits the folder and the .nup made from it (the card's "Pack is for another build" for a .nup game)
        check(BuildCheck.build(p, d1).ok(), "folder: " + BuildCheck.build(p, d1).detail);
        BuildCheck.Result r = BuildCheck.build(p, n1);
        check(r.ok(), ".nup: " + r.kind + " " + r.detail);
        check(PackCheck_buildEquivalent(p, n1) == null, "PackCheck.build's message for a fitting .nup is null");

        // Another build: the kind, the label, and every module that differs with both IDs in the detail
        r = BuildCheck.build(p, n2);
        check(r.kind == BuildCheck.Kind.OTHER_BUILD, "other build: " + r.kind);
        check("Pack is for another build".equals(r.label()), r.label());
        check(r.detail.contains("main: pack " + id(2).substring(0, 16)) && r.detail.contains("game " + id(9).substring(0, 16)) && !r.detail.contains("sdk"), r.detail);
        check(r.message.contains("Build IDs that differ: main"), r.message);
        Pack both = pack(new File(run, "pack2"), FIX_TID, "65536", "main=" + id(4), "sdk=" + id(5));
        r = BuildCheck.build(both, d1);
        check(r.kind == BuildCheck.Kind.OTHER_BUILD && r.detail.contains("main: pack " + id(4).substring(0, 16)) && r.detail.contains("sdk: pack " + id(5).substring(0, 16)) && r.detail.contains("updates/65536/exefs/sdk"), r.detail);

        // A version the dump does not have: another kind and label, not "another build"
        Pack later = pack(new File(run, "pack3"), FIX_TID, "131072", "main=" + id(7));   // (a build the dump has under another number would be found there: sameBuildOtherVersion)
        r = BuildCheck.build(later, n1);
        check(r.kind == BuildCheck.Kind.MISSING && r.detail.contains("update/131072/exefs/main") && !r.label().contains("another build"), r.kind + " " + r.detail);
        r = BuildCheck.build(later, d1);
        check(r.kind == BuildCheck.Kind.MISSING, "folder missing: " + r.kind);

        // The base game's build (version 0: base/exefs, a .nup's base package), which the profile does not support: unsupported, said with the versions
        Pack base = pack(new File(run, "pack4"), FIX_TID, "0", "main=" + id(1));
        r = BuildCheck.build(base, n1);
        check(r.kind == BuildCheck.Kind.UNSUPPORTED && r.detail.contains("app supports 65536"), r.kind + " " + r.detail);

        // A damaged .nup: unreadable, not "another build"
        File bad = new File(run, "Bad.nup");
        byte[] b = Files.readAllBytes(n1.toPath());
        b[0x20] ^= 1;   // the TOC's SHA-256
        Files.write(bad.toPath(), b);
        r = BuildCheck.build(p, bad);
        check(r.kind == BuildCheck.Kind.UNREADABLE && r.detail.contains("SHA-256"), r.kind + " " + r.detail);

        // The game key: a folder and its .nup are one game; another update build is another
        String k1 = BuildCheck.gameKey(d1, FIX_TID), kn = BuildCheck.gameKey(n1, FIX_TID), k2 = BuildCheck.gameKey(d2, FIX_TID);
        check(k1 != null && k1.equals(FIX_TID + ":65536:" + id(2)), "folder key " + k1);
        check(k1.equals(kn), "nup key " + kn + " vs " + k1);
        check(!k1.equals(k2) && k2.equals(BuildCheck.gameKey(n2, FIX_TID)), "other key " + k2);
        check(BuildCheck.gameKey(new File(run, "none"), FIX_TID) == null, "no dump: no key");
        File baseOnly = new File(run, "BaseOnly");
        write(new File(baseOnly, "base/exefs/main"), nso(1, 4096));
        check((FIX_TID + ":0:" + id(1)).equals(BuildCheck.gameKey(baseOnly, FIX_TID)), "base-only key " + BuildCheck.gameKey(baseOnly, FIX_TID));
    }

    /** PackCheck.build is BuildCheck.build's message (PackCheck itself needs Android: its one line is repeated here) */
    static String PackCheck_buildEquivalent(Pack p, File game) { return BuildCheck.build(p, game).message; }

    static void gameList(File run) throws Exception {
        // One name per file: the shared storage's aliases, "//", a trailing '/', a symbolic link
        check(GameList.samePath("/sdcard/Switch2/nup-test/DKB.nup", "/storage/emulated/0/Switch2/nup-test/DKB.nup"), "alias");
        check(GameList.samePath("/storage/emulated/0/Switch2//DonkeyKongBananza/", "/storage/self/primary/Switch2/DonkeyKongBananza"), "slashes");
        check(!GameList.samePath("/storage/emulated/0/Switch2/A", "/storage/emulated/0/Switch2/B"), "two files");
        File real = new File(run, "linked/real"), link = new File(run, "link");
        real.mkdirs();
        Files.createSymbolicLink(link.toPath(), real.toPath());
        check(GameList.samePath(link.getPath(), real.getPath() + "/"), "link");

        // matches: the same file, or the same title and build; another title with the same key, or an unknown key, is not
        Map<String, String> keys = new HashMap<>();
        keys.put("/storage/emulated/0/Switch2/DonkeyKongBananza", "DKB:458752:e25c");
        keys.put("/storage/emulated/0/Switch2/nup-test/DKB.nup", "DKB:458752:e25c");
        keys.put("/storage/emulated/0/Switch2/old/DKB", "DKB:0:a1dd");
        keys.put("/storage/emulated/0/Switch2/MKW.nup", "MKW:0:d00e");
        List<E> list = new ArrayList<>(List.of(
                new E("040074A01BF12000", "/storage/emulated/0/Switch2/DonkeyKongBananza", 0, false),
                new E("0400C3F00006E000", "/sdcard/Switch2/MKW.nup", 0, false)));
        java.util.function.Function<E, String> keyOf = e -> keys.get(Frontend.normal(e.dir));
        check(GameList.matches(list, "040074A01BF12000", "/sdcard/Switch2/nup-test/DKB.nup", "DKB:458752:e25c", keyOf).equals(List.of(0)), "same build");
        check(GameList.matches(list, "0400C3F00006E000", "/storage/emulated/0/Switch2/MKW.nup/", null, keyOf).equals(List.of(1)), "same file, key unknown");
        check(GameList.matches(list, "040074A01BF12000", "/storage/emulated/0/Switch2/old/DKB", "DKB:0:a1dd", keyOf).isEmpty(), "another build is not matched");
        check(GameList.matches(list, "0400C3F00006E000", "/x", "DKB:458752:e25c", keyOf).isEmpty(), "another title with the same key");
        check(GameList.others(list, "040074A01BF12000", "/storage/emulated/0/Switch2/old/DKB", "DKB:0:a1dd", keyOf).equals(List.of(0)), "another dump: asked");
        check(GameList.others(list, "040074A01BF12000", "/sdcard/Switch2/nup-test/DKB.nup", "DKB:458752:e25c", keyOf).isEmpty(), "the same build: not asked");

        // collapse: the four cards of DKB, MKW, DKB, MKW (a folder and its .nup, two spellings of one .nup) become two; the ready one is kept
        List<E> four = List.of(
                new E("040074A01BF12000", "/storage/emulated/0/Switch2/DonkeyKongBananza", 5, false),
                new E("0400C3F00006E000", "/storage/emulated/0/Switch2/MKW.nup", 0, false),
                new E("040074A01BF12000", "/storage/emulated/0/Switch2/nup-test/DKB.nup", 1, true),
                new E("0400C3F00006E000", "/sdcard/Switch2/MKW.nup", 0, true));
        List<E> two = GameList.collapse(four, keyOf, e -> e.ready);
        check(two.size() == 2 && two.get(0) == four.get(2) && two.get(1) == four.get(3), "ready kept: " + two);
        // none ready: the one played last; same time: the one added last
        List<E> none = List.of(four.get(0), new E("040074A01BF12000", "/sdcard/Switch2/nup-test/DKB.nup", 1, false));
        check(GameList.collapse(none, keyOf, e -> e.ready).equals(List.of(none.get(0))), "used kept");
        List<E> tie = List.of(new E("0400C3F00006E000", "/storage/emulated/0/Switch2/MKW.nup", 0, false), new E("0400C3F00006E000", "/sdcard/Switch2/MKW.nup", 0, false));
        check(GameList.collapse(tie, keyOf, e -> e.ready).equals(List.of(tie.get(1))), "latest kept");
        // another build of one title, and entries whose build cannot be told, stay
        List<E> keep = List.of(four.get(0), new E("040074A01BF12000", "/storage/emulated/0/Switch2/old/DKB", 0, false), new E("040074A01BF12000", "/gone/a", 0, false),
                new E("040074A01BF12000", "/gone/b", 0, false));
        check(GameList.collapse(keep, keyOf, e -> e.ready).size() == 4, "nothing merged");
        // transitive: a folder, its .nup by one spelling and by another
        List<E> chain = List.of(four.get(0), four.get(2), new E("040074A01BF12000", "/sdcard/Switch2/nup-test//DKB.nup", 9, false));
        check(GameList.collapse(chain, keyOf, e -> e.ready).equals(List.of(chain.get(1))), "transitive");
    }

    static void real(String[] a) throws Exception {
        File game = new File(a[1]), run = new File(a[2]);
        String[] builds = java.util.Arrays.copyOfRange(a, 5, a.length);
        Pack p = pack(new File(run, "realpack"), a[3], a[4], builds);
        BuildCheck.Result r = BuildCheck.build(p, game);
        String k = BuildCheck.gameKey(game, a[3]);
        if (!r.ok()) { System.out.println("real " + r.kind + ": " + r.detail + " | " + r.message); System.exit(1); }
        DumpLayout.Found f = DumpLayout.find(game);   // what Prepare checks: the layout, the build, the RomFS (romfs_check)
        if (f.error != null || f.version != Long.parseLong(a[4])) { System.out.println("real find: " + f.version + " " + f.error); System.exit(1); }
        System.out.println("real ok key=" + k);
    }
}
