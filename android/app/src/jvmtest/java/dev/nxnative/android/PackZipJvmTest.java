package dev.nxnative.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * PackZip (Export pack… / Import pack…) on the JVM: run by dm/tools/tests/test_android_packzip.py, which compiles this with PackZip, Pack, Nup, Profile, ShaderLibs,
 * SaveStore and a stub BuildConfig (chain "c0ffee", profile 040074A01BF12000). {@code <scratch folder>}: every case; prints "ok <n>" and exits 0, or the first
 * failure and exits 1.
 */
public final class PackZipJvmTest {
    private static int n;
    static final String ID = "040074A01BF12000";

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** A pack folder like pack_android.py's: pack.tsv, a finished module, a library of this APK's version (or of lib) */
    static File pack(File dir, String id, String lib, String tag) throws IOException {
        write(new File(dir, "pack.tsv"), "format\t1\ntarget\tandroid\ntitle_id\t" + id + "\nname\tDonkey Kong Bananza\nversion\t458752\nromfs\tupdates/458752/romfs\n"
                + "modules\tmain\nshaders\tshaders\n");
        write(new File(dir, "main.patched.bin"), "code " + tag);
        write(new File(dir, "main.android.key"), "key");
        write(new File(dir, "shaders/v-" + lib + "/dmsh_vk.lib"), "library " + tag);
        write(new File(dir, "shaders/v-" + lib + "/inputs.tsv"), "inputs\tabcdef012345\n");
        return dir;
    }

    interface Io { void run() throws IOException; }

    static String error(Io r) {
        try { r.run(); return null; } catch (IOException e) { return e.getMessage(); }
    }

    static void zip(File f, Map<String, String> entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        File t = new File(args[0]);
        roundTrip(new File(t, "round"));
        slip(new File(t, "slip"));
        wrongTitle(new File(t, "title"));
        damaged(new File(t, "damaged"));
        swap(new File(t, "swap"));
        System.out.println("ok " + n);
    }

    static void roundTrip(File t) throws Exception {
        File src = pack(new File(t, "src/android-pack"), ID, "c0ffee", "a"), out = new File(t, "out"), packs = new File(t, "app/packs");
        File z = PackZip.export(src, out);
        check(z.getName().equals("banana-pack-" + ID + "-458752.zip"), "the zip's name: " + z.getName());
        check(!new File(z.getPath() + ".partial").exists(), "no .partial left");
        try (ZipFile zf = new ZipFile(z)) {
            check(zf.getEntry("pack.tsv") != null && zf.getEntry("shaders/v-c0ffee/dmsh_vk.lib") != null, "pack.tsv at the root, the library under shaders/");
            for (java.util.Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) check(e.nextElement().getMethod() == ZipEntry.STORED, "every entry stored");
        }
        File z2 = PackZip.export(src, out);
        check(z2.getName().equals("banana-pack-" + ID + "-458752-2.zip") && z.isFile(), "a second export gets a new name, the first stays");
        Pack p = PackZip.stage(z, packs, ID);
        check(p.dir.equals(new File(packs, ID + ".partial")) && p.titleId.equals(ID), "staged into <id>.partial");
        File target = new File(packs, ID);
        PackZip.swapIn(p.dir, target);
        check(SaveStore.scan(target).diff(SaveStore.scan(src)) == null, "the imported pack is the exported one, file by file");
        check(!new File(packs, ID + ".partial").exists() && !new File(packs, ID + ".replaced").exists(), "nothing left beside it");
        check(Pack.read(target).library() != null, "its library is this APK's");
        // a folder import replaces it; the old one stays until the new one checks out
        File src2 = pack(new File(t, "src2"), ID, "c0ffee", "b");
        String e = error(() -> PackZip.stage(new File(t, "src2-missing"), packs, ID));
        check(e != null && read(new File(target, "main.patched.bin")).equals("code a"), "a missing source changes nothing");
        Pack p2 = PackZip.stage(src2, packs, null);
        check(read(new File(target, "main.patched.bin")).equals("code a"), "staging keeps the current pack");
        PackZip.swapIn(p2.dir, target);
        check(read(new File(target, "main.patched.bin")).equals("code b"), "the folder's pack is in");
        check(read(new File(src2, "main.patched.bin")).equals("code b"), "the source folder is only read");
        // an older library is taken (the restamp / shader update at Play makes this APK's)
        File old = pack(new File(t, "older"), ID, "0123456789ab", "c");
        Pack p3 = PackZip.stage(old, packs, ID);
        check(Pack.libraryOf(p3.dir) == null && Pack.libraryVersions(p3.dir).contains("0123456789ab"), "a pack with an older library stages");
        SaveStore.deleteTree(p3.dir);
        check(error(() -> PackZip.stage(target, packs, ID)) != null, "the app's own pack is not imported onto itself");
    }

    static void slip(File t) throws Exception {
        File packs = new File(t, "app/packs");
        String tsv = read(new File(pack(new File(t, "tpl"), ID, "c0ffee", "a"), "pack.tsv"));
        for (String bad : new String[] {"../evil.bin", "shaders/../../evil.bin", "/abs/evil.bin", "a\\..\\evil.bin", "C:/evil.bin"}) {
            File z = new File(t, "slip.zip");
            Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("pack.tsv", tsv);
            m.put("main.patched.bin", "code");
            m.put("main.android.key", "k");
            m.put("shaders/v-c0ffee/dmsh_vk.lib", "lib");
            m.put(bad, "evil");
            zip(z, m);
            String e = error(() -> PackZip.stage(z, packs, ID));
            check(e != null && e.contains("outside the pack"), bad + " refused: " + e);
            check(!new File(t, "evil.bin").exists() && !new File(t, "app/evil.bin").exists() && !new File("/abs/evil.bin").exists(), bad + ": nothing written outside");
            check(!new File(packs, ID + ".partial").exists() && !new File(packs, ID).exists(), bad + ": nothing staged");
        }
        // a zip with one top folder is taken
        File z = new File(t, "top.zip");
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("android-pack/pack.tsv", tsv);
        m.put("android-pack/main.patched.bin", "code");
        m.put("android-pack/main.android.key", "k");
        m.put("android-pack/shaders/v-c0ffee/dmsh_vk.lib", "lib");
        zip(z, m);
        Pack p = PackZip.stage(z, packs, ID);
        check(new File(p.dir, "shaders/v-c0ffee/dmsh_vk.lib").isFile(), "a zip with one top folder stages without it");
        SaveStore.deleteTree(p.dir);
    }

    static void wrongTitle(File t) throws Exception {
        File packs = new File(t, "app/packs");
        File other = pack(new File(t, "other"), "0100000000010000", "c0ffee", "a");
        String e = error(() -> PackZip.stage(other, packs, null));
        check(e != null && e.contains("This app version does not include"), "a title without a profile of this APK is refused: " + e);
        File z = PackZip.export(other, new File(t, "out"));
        e = error(() -> PackZip.stage(z, packs, null));
        check(e != null && e.contains("This app version does not include"), "the same as a zip: " + e);
        File dkb = pack(new File(t, "dkb"), ID, "c0ffee", "a");
        e = error(() -> PackZip.stage(dkb, packs, "0400C3F00006E000"));
        check(e != null && e.contains("not 0400C3F00006E000"), "a pack of another game than the sheet's is refused: " + e);
        check(packs.list() == null || packs.list().length == 0, "nothing staged");
    }

    static void damaged(File t) throws Exception {
        File packs = new File(t, "app/packs");
        File src = pack(new File(t, "src"), ID, "c0ffee", "a");
        new File(src, "main.android.key").delete();
        String e = error(() -> PackZip.stage(src, packs, ID));
        check(e != null && e.contains("unfinished"), "unfinished code refused: " + e);
        new File(src, "main.android.key").createNewFile();
        SaveStore.deleteTree(new File(src, "shaders"));
        e = error(() -> PackZip.stage(src, packs, ID));
        check(e != null && e.contains("no shader library"), "no library refused: " + e);
        File junk = new File(t, "junk.zip");
        write(junk, "not a zip");
        e = error(() -> PackZip.stage(junk, packs, ID));
        check(e != null && e.contains("not a zip"), "not a zip refused: " + e);
        check(!new File(packs, ID + ".partial").exists(), "nothing staged");
    }

    static void swap(File t) throws Exception {
        File packs = new File(t, "packs"), target = new File(packs, ID);
        pack(target, ID, "c0ffee", "old");
        // a swap stopped after the old pack went aside: recover puts it back
        check(target.renameTo(new File(packs, ID + ".replaced")), "set up");
        PackZip.recover(target);
        check(read(new File(target, "main.patched.bin")).equals("code old"), "the old pack came back");
        // a stale .partial from a stopped import is cleared by the next one
        write(new File(packs, ID + ".partial/stale.bin"), "stale");
        Pack p = PackZip.stage(pack(new File(t, "new"), ID, "c0ffee", "new"), packs, ID);
        check(!new File(p.dir, "stale.bin").exists(), "the stale .partial was cleared");
        PackZip.swapIn(p.dir, target);
        check(read(new File(target, "main.patched.bin")).equals("code new") && !new File(packs, ID + ".replaced").exists(), "swapped in");
    }
}
