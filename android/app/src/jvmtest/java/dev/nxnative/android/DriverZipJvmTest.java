package dev.nxnative.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GpuDriver's zip install (DriverZip) on the JVM, run by dm/tools/tests/test_android_driverzip.py: a good driver zip installed (flat, folders dropped), a malformed zip
 * (an entry name that is not UTF-8, garbage bytes, a truncated zip) and a zip without meta.json or without its library refused with an IOException and a reason, never
 * an unchecked exception; after each failure the installed driver of the same name (the pick, by folder name) is unchanged and no .installing folder is left; a good
 * zip of the same name replaces it; a killed install's leftovers recovered. Prints "ok &lt;n&gt;" and exits 0, or the first failure and exits 1.
 */
public final class DriverZipJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    /** The app's parser is org.json; the test's reads the two string fields */
    static String[] meta(byte[] json) {
        String s = new String(json, StandardCharsets.UTF_8).trim();
        if (!s.startsWith("{")) return null;
        return new String[] {field(s, "name"), field(s, "libraryName")};
    }

    static String field(String s, String k) {
        Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    static byte[] zip(String[][] entries) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (String[] e : entries) { z.putNextEntry(new ZipEntry(e[0])); z.write(e[1].getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        return b.toByteArray();
    }

    static String metaJson(String name, String lib, String ver) {
        return "{\"name\": \"" + name + "\", \"driverVersion\": \"" + ver + "\", \"libraryName\": \"" + lib + "\"}";
    }

    static File install(File root, byte[] zip, String fallback) throws IOException {
        return DriverZip.install(root, new ByteArrayInputStream(zip), fallback, DriverZipJvmTest::meta);
    }

    /** The install must fail with an IOException (a reason for the user), not anything else */
    static String refused(File root, byte[] zip, String what) {
        try { install(root, zip, "fallback"); }
        catch (IOException e) { check(e.getMessage() != null && !e.getMessage().isEmpty(), what + ": a reason"); return e.getMessage(); }
        catch (RuntimeException e) { throw new AssertionError(what + ": unchecked " + e); }
        throw new AssertionError(what + ": installed");
    }

    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** The earlier driver as it was, and nothing half-installed beside it */
    static void unchanged(File root, String what) throws IOException {
        File d = new File(root, "Turnip_v32");
        check(read(new File(d, "libvulkan_freedreno.so")).equals("v32 lib"), what + ": the installed driver's library unchanged");
        check(read(new File(d, "meta.json")).contains("v32"), what + ": its meta.json unchanged");
        String[] l = root.list();
        check(l != null && l.length == 1 && l[0].equals("Turnip_v32"), what + ": nothing else in gpu_drivers (" + String.join(",", l == null ? new String[0] : l) + ")");
    }

    /** A zip whose entry name is not UTF-8 (general purpose flag bit 11 clear, a CP437 byte 0x81 in the name): Java's ZipInputStream throws IllegalArgumentException */
    static byte[] nonUtf8Name() throws IOException {
        byte[] z = zip(new String[][] {{"meta.json", metaJson("Bad", "lib_X.so", "1")}, {"lib_X.so", "x"}});
        byte[] name = "lib_X.so".getBytes(StandardCharsets.US_ASCII);
        int hits = 0;
        for (int k = 0; k + name.length <= z.length; k++) {
            boolean m = true;
            for (int j = 0; j < name.length && m; j++) m = z[k + j] == name[j];
            if (m) { z[k + 4] = (byte) 0x81; hits++; }   // lib_\x81.so, in the local header and the central directory alike
        }
        check(hits >= 2, "the test zip's name patched");
        return z;
    }

    public static void main(String[] args) throws Exception {
        File t = new File(args[0]), root = new File(t, "gpu_drivers");
        // a good zip, with a folder in it (dropped) and a dot file (skipped)
        File d = install(root, zip(new String[][] {{"Turnip/meta.json", metaJson("Turnip v32", "libvulkan_freedreno.so", "v32")},
                {"Turnip/libvulkan_freedreno.so", "v32 lib"}, {"__MACOSX/.hidden", "x"}}), "fallback");
        check(d.getName().equals("Turnip_v32") && d.getParentFile().equals(root), "named by meta.json's name, made safe");
        check(read(new File(d, "libvulkan_freedreno.so")).equals("v32 lib"), "flat: the library at the folder's top");
        check(!new File(d, ".hidden").exists(), "dot files skipped");
        unchanged(root, "after the install");

        String why = refused(root, nonUtf8Name(), "an entry name that is not UTF-8");
        check(why.contains("not UTF-8"), "the malformed zip (an IllegalArgumentException in the zip reader)'s reason: " + why);
        unchanged(root, "malformed name");

        byte[] good = zip(new String[][] {{"meta.json", metaJson("Turnip v32", "libvulkan_freedreno.so", "v33")}, {"libvulkan_freedreno.so", "v33 lib"}});
        byte[] cut = java.util.Arrays.copyOf(good, good.length / 2);
        refused(root, cut, "a truncated zip of the same name");
        unchanged(root, "truncated");

        byte[] junk = new byte[4096];
        for (int k = 0; k < junk.length; k++) junk[k] = (byte) (k * 31 + 7);
        why = refused(root, junk, "not a zip");
        check(why.contains("meta.json"), "not a zip: no meta.json: " + why);
        unchanged(root, "not a zip");

        why = refused(root, zip(new String[][] {{"libvulkan_freedreno.so", "x"}, {"readme.txt", "y"}}), "no meta.json");
        check(why.contains("no meta.json"), "no meta.json's reason: " + why);
        unchanged(root, "no meta.json");

        why = refused(root, zip(new String[][] {{"meta.json", metaJson("Turnip v32", "libvulkan_freedreno.so", "v34")}}), "meta.json naming a missing library");
        check(why.contains("not in the zip"), "missing library's reason: " + why);
        unchanged(root, "missing library");

        refused(root, zip(new String[][] {{"meta.json", "not json"}, {"lib.so", "x"}}), "meta.json that is not JSON");
        unchanged(root, "bad meta.json");

        String[][] many = new String[DriverZip.MAX_FILES + 2][];
        many[0] = new String[] {"meta.json", metaJson("Turnip v32", "l0.so", "v35")};
        for (int k = 1; k < many.length; k++) many[k] = new String[] {"l" + k + ".so", "x"};
        why = refused(root, zip(many), "too many files");
        check(why.contains("256"), "too many files' reason: " + why);
        unchanged(root, "too many files");

        // a killed install's leftovers: .installing goes, a .replaced-<name> whose driver is missing comes back
        File stale = new File(root, DriverZip.INSTALLING);
        stale.mkdirs();
        Files.write(new File(stale, "meta.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        File moved = new File(root, "Other");
        moved.mkdirs();
        Files.write(new File(moved, "meta.json").toPath(), metaJson("Other", "o.so", "1").getBytes(StandardCharsets.UTF_8));
        check(moved.renameTo(new File(root, DriverZip.REPLACED + "Other")), "a stopped replace staged");
        DriverZip.recover(root);
        check(!stale.exists(), "recover: .installing removed");
        check(new File(root, "Other/meta.json").isFile() && !new File(root, DriverZip.REPLACED + "Other").exists(), "recover: the replaced driver back");
        DriverZip.deleteTree(new File(root, "Other"));
        unchanged(root, "after recover");

        // a good zip of the same name replaces it, with nothing left beside it
        d = install(root, good, "fallback");
        check(d.getName().equals("Turnip_v32") && read(new File(d, "libvulkan_freedreno.so")).equals("v33 lib"), "replaced by the new version");
        String[] l = root.list();
        check(l != null && l.length == 1, "nothing beside the replaced driver");

        // no name in meta.json: the fallback, made safe
        d = install(root, zip(new String[][] {{"meta.json", "{\"libraryName\": \"a.so\"}"}, {"a.so", "a"}}), "../My driver.adpkg");
        check(d.getName().equals("My_driver.adpkg") && d.getParentFile().equals(root), "fallback name made safe: " + d.getName());
        System.out.println("ok " + n);
    }
}
