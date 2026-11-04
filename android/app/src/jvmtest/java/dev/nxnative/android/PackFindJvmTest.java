package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * PackFind on the JVM, driven by dm/tools/tests/test_android_packfind.py (a stub BuildConfig: DKB with shader inputs abcdef012345, a delta row for library
 * version 0ld000000001 and a stamp row for 57a000000001; MKW; chain c0ffee000001). Arguments: the repository and an empty work folder. Checks:
 * <ul>
 * <li>the finder: a pack in an ancestor's &lt;id&gt;-work/&lt;any name&gt; is found; one with this APK's library beats one that needs a shader update; one whose
 *     code the judge refuses (another build) is never chosen; another title's pack is ignored; the app's pack is preferred when it fits, but a fitting pack beats
 *     an app pack that needs an update; nothing above the volume root or past LEVELS; a simulated pack is never chosen; a delta update counts only for a dump,
 *     a restamp for any game</li>
 * <li>the folder scan (PackFind.gamesIn and what Add game makes of it): several .nups (made by dm/tools/nup_pack.py), dump subfolders, a title without a
 *     profile, and the folders that are not games (work, pack, empty, two levels deep)</li>
 * </ul>
 * Prints "ok &lt;checks&gt;"; exits non-zero on the first failure.
 */
public final class PackFindJvmTest {
    static final String DKB = "040074A01BF12000", MKW = "0400C3F00006E000", OTHER = "0100ABCD00010000";
    private static int n;

    static void check(boolean ok, String what) {
        n++;
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
    }

    static void write(File f, byte[] b) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b);
    }

    static void write(File f, String s) throws IOException { write(f, s.getBytes(StandardCharsets.UTF_8)); }

    /** A library header (ShaderLibs.header: magic, format 1, game at 40, inputs at 56, abi at 116) */
    static byte[] lib(String game, String inputs, String abi) {
        byte[] h = new byte[256];
        int m = ShaderLibs.MAGIC;
        for (int i = 0; i < 4; i++) h[i] = (byte) (m >>> (8 * i));
        h[4] = 1;
        put(h, 40, game);
        put(h, 56, inputs);
        put(h, 116, abi);
        return h;
    }

    static void put(byte[] h, int at, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, at, b.length);
    }

    /** A pack folder: pack.tsv, main's code and key, and a library of the given version (inputs.tsv with inputs); build: its main= row */
    static File pack(File dir, String id, String build, String version, String inputs, boolean simulated) throws IOException {
        String tsv = "format\t1\ntarget\tandroid\ntitle_id\t" + id + "\nname\tGame " + id.substring(12) + "\nversion\t458752\nromfs\tromfs\nmodules\tmain\nbuild\tmain=" + build + "\n"
                + (simulated ? "simulated\t1\n" : "");
        write(new File(dir, "pack.tsv"), tsv);
        write(new File(dir, "main.patched.bin"), "code");
        write(new File(dir, "main.android.key"), "key");
        if (version != null) {
            write(new File(dir, "shaders/v-" + version + "/dmsh_vk.lib"), lib(id, inputs, "abc123abc123"));
            write(new File(dir, "shaders/v-" + version + "/inputs.tsv"), "inputs\t" + inputs + "\n");
        }
        return dir;
    }

    static File pack(File dir, String id, String build, String version) throws IOException { return pack(dir, id, build, version, "abcdef012345", false); }

    /** The judge of the phone, PackCheck.blocking, stands in as: the game's build is "aa"; any other build row is code of another game build */
    static final PackFind.Judge BUILD_AA = p -> p.builds.contains("main=aa") ? null : "another build";

    static File best(File picked, File app, File game, File root, boolean dump) {
        PackFind.Found f = PackFind.best(PackFind.candidates(picked, app, game, DKB, root), DKB, dump, BUILD_AA);
        return f == null ? null : f.dir;
    }

    static boolean same(File a, File b) { return a != null && b != null && a.getAbsoluteFile().equals(b.getAbsoluteFile()); }

    public static void main(String[] args) throws Exception {
        File repo = new File(args[0]), t = new File(args[1]);
        finder(new File(t, "finder"));
        scan(repo, new File(t, "scan"));
        System.out.println("ok " + n);
    }

    static void finder(File t) throws IOException {
        String cur = BuildConfig.NX_DMSH_VK_CHAIN;
        File root = new File(t, "storage/emulated/0"), sw = new File(root, "Switch2"), nupTest = new File(sw, "nup-test");
        File game = new File(nupTest, "DonkeyKongBananza.nup");
        write(game, "not read by the finder");
        File work = new File(sw, DKB + "-work");

        // An older library the delta manifest knows, in android-pack (sorts first), and a current one in xlate2-pack: the current one wins
        File older = pack(new File(work, "android-pack"), DKB, "aa", "0ld000000001");
        check(PackFind.shaderTier(older, DKB, true) == PackFind.UPDATE, "delta-known older library: update tier");
        check(PackFind.shaderTier(older, DKB, false) == -1, "a delta update needs a dump");
        check(same(best(null, null, game, root, true), older), "alone, the older pack is found in the ancestor's <id>-work/android-pack");
        check(best(null, null, game, root, false) == null, "not a dump: the older pack is not usable");
        File xlate = pack(new File(work, "xlate2-pack"), DKB, "aa", cur);
        check(same(best(null, null, game, root, true), xlate), "the ancestor's <id>-work/xlate2-pack (current library) beats the older android-pack");
        check(PackFind.best(PackFind.candidates(null, null, game, DKB, root), DKB, true, BUILD_AA).tier == PackFind.READY, "ready tier");

        // Another game build's pack, current and nearest (the .nup's own folder): never chosen
        File other = pack(new File(nupTest, DKB + "-work/android-pack"), DKB, "bb", cur);
        check(PackFind.candidates(null, null, game, DKB, root).get(0).equals(other.getAbsoluteFile()), "the nearest (Pack.find's place) is the first candidate");
        check(same(best(null, null, game, root, true), xlate), "another build's pack is refused, the fitting one chosen");

        // Another title's pack in this title's work folder, and a simulated pack: ignored
        pack(new File(work, "aaa-mkw"), MKW, "aa", cur);
        pack(new File(work, "aab-sim"), DKB, "aa", cur, "abcdef012345", true);
        check(same(best(null, null, game, root, true), xlate), "another title's and a simulated pack are ignored");

        // A library of other shader inputs: not current
        File inputs = pack(new File(t, "inputs-pack"), DKB, "aa", cur, "000000000000", false);
        check(PackFind.shaderTier(inputs, DKB, true) == -1, "a library of other shader tables is not current");

        // The app's pack: preferred when it fits; a fitting ancestor pack beats an app pack that needs an update
        File app = pack(new File(t, "app/packs/" + DKB), DKB, "aa", cur);
        check(same(best(null, app, game, root, true), app), "the app's fitting pack is preferred");
        File app2 = pack(new File(t, "app2/packs/" + DKB), DKB, "aa", "0ld000000001");
        check(same(best(null, app2, game, root, true), xlate), "a fitting pack beats the app's pack that needs an update");
        File picked = pack(new File(t, "picked"), DKB, "aa", cur);
        check(same(best(picked, app, game, root, true), picked), "the picked folder first when it fits");
        File pickedOld = pack(new File(t, "picked-bb"), DKB, "bb", cur);
        check(same(best(pickedOld, app, game, root, true), app), "a picked folder of another build is passed over");

        // A restamp row's older version: update tier without a dump
        File st = pack(new File(t, "stamp-pack"), DKB, "aa", "57a000000001");
        check(PackFind.shaderTier(st, DKB, false) == PackFind.UPDATE, "a library a stamp row names: update tier, dump or not");
        check(PackFind.shaderTier(new File(t, "nothing"), DKB, true) == -1, "no pack: no tier");

        // Never above the volume root, never past LEVELS
        File deep = new File(root, "a/b/c/d/e");
        File g2 = new File(deep, "Game.nup");
        write(g2, "x");
        pack(new File(root.getParentFile(), DKB + "-work/above-root"), DKB, "aa", cur);
        pack(new File(root, "a/" + DKB + "-work/too-far"), DKB, "aa", cur);   // e, d, c, b are searched; a is four levels above e
        File near = new File(root, "a/b/" + DKB + "-work/near");
        check(best(null, null, g2, root, true) == null, "nothing past LEVELS or above the volume root");
        pack(near, DKB, "aa", cur);
        check(same(best(null, null, g2, root, true), near), "three levels above the .nup's folder is searched");
        File g3 = new File(root, "Game.nup");
        write(g3, "x");
        check(best(null, null, g3, root, true) == null, "a game at the volume root: nothing above it");
        check(PackFind.volumeRoot(new File("/storage/emulated/0/Switch2/nup-test/x.nup")).getPath().equals("/storage/emulated/0"), "volume root of the shared storage");
        check(PackFind.volumeRoot(new File("/storage/1234-ABCD/Games/x.nup")).getPath().equals("/storage/1234-ABCD"), "volume root of an SD card");
        check(PackFind.volumeRoot(new File("/data/local/tmp/x.nup")) == null, "no volume root elsewhere");

        // A dump folder game: its own <id>-work/<any> and the folder above it
        File dumpGame = new File(root, "Games/DKB");
        write(new File(dumpGame, "base/exefs/main.npdm"), "x");
        File inside = pack(new File(dumpGame, DKB + "-work/mac-pack"), DKB, "aa", cur);
        check(same(best(null, null, dumpGame, root, true), inside), "a dump folder's own <id>-work/<any> pack");
    }

    // ---- The folder scan ----

    static byte[] npdm(long id) {
        byte[] b = new byte[0x100];
        b[0] = 'M'; b[1] = 'E'; b[2] = 'T'; b[3] = 'A';
        b[0x70] = (byte) 0x80;
        b[0x80] = 'A'; b[0x81] = 'C'; b[0x82] = 'I'; b[0x83] = '0';
        for (int i = 0; i < 8; i++) b[0x90 + i] = (byte) (id >>> (8 * i));
        return b;
    }

    static void dump(File d, String id) throws IOException {
        write(new File(d, "base/exefs/main.npdm"), npdm(Long.parseUnsignedLong(id, 16)));
        byte[] nso = new byte[0x200];
        nso[0] = 'N'; nso[1] = 'S'; nso[2] = 'O'; nso[3] = '0';
        write(new File(d, "base/exefs/main"), nso);
        write(new File(d, "base/romfs/data.bin"), "romfs of " + id);
    }

    static void nup(File repo, File src, File out) throws IOException, InterruptedException {
        out.getParentFile().mkdirs();
        Process p = new ProcessBuilder("python3", "-I", new File(repo, "dm/tools/nup_pack.py").getPath(), src.getPath(), out.getPath(), "--raw").redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0 || !out.isFile()) throw new AssertionError("nup_pack.py failed: " + o);
    }

    static void scan(File repo, File t) throws IOException, InterruptedException {
        File src = new File(t, "src"), dir = new File(t, "Switch2/nup-test");
        dump(new File(src, "dkb"), DKB);
        dump(new File(src, "mkw"), MKW);
        dump(new File(src, "other"), OTHER);
        nup(repo, new File(src, "dkb"), new File(dir, "DonkeyKongBananza.nup"));
        nup(repo, new File(src, "mkw"), new File(dir, "MarioKartWorld.nup"));
        nup(repo, new File(src, "other"), new File(dir, "Other-v1.nup"));
        write(new File(dir, "Shout.NUP"), "wrong case");
        dump(new File(dir, "DkbDump"), DKB);
        write(new File(dir, "OneVersion/exefs/main.npdm"), npdm(Long.parseUnsignedLong(MKW, 16)));
        dump(new File(dir, DKB + "-work"), DKB);   // a work folder holding an extraction: not a game
        dump(new File(dir, "android-pack"), DKB);
        write(new File(dir, "android-pack/pack.tsv"), "format\t1\n");   // a pack folder: not a game
        dump(new File(dir, "Nested/Inner"), DKB);   // two levels deep: not looked at
        new File(dir, "Empty").mkdirs();
        write(new File(dir, "readme.txt"), "x");

        List<File> games = PackFind.gamesIn(dir);
        List<String> names = new ArrayList<>();
        for (File g : games) names.add(g.getName());
        check(names.equals(List.of("DkbDump", "DonkeyKongBananza.nup", "MarioKartWorld.nup", "OneVersion", "Other-v1.nup", "Shout.NUP")), "the folder's games " + names);

        // What Add game makes of them (MainActivity.addAll): the title, whether this APK runs it, and whether it is a dump Prepare can start from
        List<String> ok = new ArrayList<>(), unsupported = new ArrayList<>(), bad = new ArrayList<>();
        for (File g : games) {
            if (Nup.wrongCase(g)) { bad.add(g.getName()); continue; }
            String id = Pack.titleIdOf(g);
            if (Profile.missing(id, g.getName()) != null) unsupported.add(g.getName() + " " + id);
            else ok.add(g.getName() + " " + id + (Pack.isDump(g) ? " dump" : ""));
        }
        check(ok.equals(List.of("DkbDump " + DKB + " dump", "DonkeyKongBananza.nup " + DKB + " dump", "MarioKartWorld.nup " + MKW + " dump", "OneVersion " + MKW)), "supported " + ok);
        check(unsupported.equals(List.of("Other-v1.nup " + OTHER)), "unsupported " + unsupported);
        check(bad.equals(List.of("Shout.NUP")), "wrong case " + bad);
        check(PackFind.gamesIn(new File(t, "missing")).isEmpty(), "a missing folder: no games");
    }
}
