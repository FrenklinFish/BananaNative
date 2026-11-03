package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A pack folder made by scripts/pack_android.py (its pack.tsv) and what it says about a game folder. */
final class Pack {
    static final int FORMAT = 1;

    final File dir;
    final String titleId, name, version;
    final String romfsRel;
    final String shadersRel;   // the shader library's path in the pack (its "shaders" row), null when the pack has none
    final boolean simulated;   // made by the on-device flow's simulated engine (FakeEngine): a placeholder, never started
    final Map<Integer, String> aoc = new LinkedHashMap<>();   // DLC index → its RomFS folder inside the game folder
    final String[] modules;   // the profile's modules, rewritten in the pack
    final List<String> builds;   // "main=<build ID>" rows: the NSO builds the code was rewritten from (PackCheck.build)
    final String packer;   // the commit of the tools that made it ("" when not recorded)

    private Pack(File dir, Map<String, List<String>> rows) throws IOException {
        this.dir = dir;
        String format = one(rows, "format");
        if (!String.valueOf(FORMAT).equals(format)) throw new IOException("pack format " + format + ", this launcher reads " + FORMAT + ": pack again with this commit's scripts/pack_android.py");
        if (!"android".equals(one(rows, "target"))) throw new IOException("not an Android pack (target " + one(rows, "target") + ")");
        titleId = one(rows, "title_id").toUpperCase(Locale.ROOT);
        name = one(rows, "name");
        version = one(rows, "version");
        romfsRel = one(rows, "romfs");
        List<String> sh = rows.get("shaders");
        shadersRel = sh == null || sh.isEmpty() || sh.get(0).isEmpty() ? null : sh.get(0);
        simulated = rows.containsKey("simulated");
        for (String v : rows.getOrDefault("aoc", List.of())) {
            String[] c = v.split("\t", 2);
            if (c.length == 2) aoc.put(Integer.parseInt(c[0]), c[1]);
        }
        modules = one(rows, "modules").split(" ");
        for (String m : modules)
            if (!new File(dir, m + ".patched.bin").isFile()) throw new IOException("the pack has no " + m + ".patched.bin");
        builds = rows.getOrDefault("build", List.of());
        List<String> pk = rows.get("packer");
        packer = pk == null || pk.isEmpty() ? "" : pk.get(0);
    }

    /** The shader library this APK's host loads from the pack: shaders/v-<DMSH_VK_CHAIN>/dmsh_vk.lib (vka_shader.c makes the path itself; pack.tsv's row is not read), null when missing */
    File library() { return libraryOf(dir); }

    static File libraryOf(File dir) {
        File f = new File(dir, "shaders/v-" + BuildConfig.NX_DMSH_VK_CHAIN + "/dmsh_vk.lib");
        return f.isFile() ? f : null;
    }

    /**
     * The first module whose code is unfinished: its &lt;module&gt;.android.key is missing (scripts/pack_android.py removes it before a rewrite and writes it last,
     * after the module's files went in by rename), so its patched.bin and tables may be of two rewrites. null when every module has its key
     */
    String unfinishedModule() {
        for (String m : modules) if (!new File(dir, m + ".android.key").isFile()) return m;
        return null;
    }

    /** The library versions a pack folder holds (shaders/v-<V>/dmsh_vk.lib) */
    static List<String> libraryVersions(File dir) {
        List<String> out = new ArrayList<>();
        File[] l = new File(dir, "shaders").listFiles(f -> f.getName().startsWith("v-") && new File(f, "dmsh_vk.lib").isFile());
        if (l != null) for (File f : l) out.add(f.getName().substring(2));
        return out;
    }

    /** Why the game (titleId) would draw nothing with this pack folder, in one sentence; null when it has this APK's shader library built from that game's profile */
    static String libraryProblem(File dir, String titleId) {
        Profile prof = Profile.of(titleId);
        String inputs = prof == null ? "unknown" : prof.shaderInputs;
        File lib = libraryOf(dir);
        if (lib != null) {
            // Current only with the stamp of this APK's profile inputs, as pack_android.py's library_current asks: a library built from other shader tables
            // loads but lacks or misplaces shaders, and the pack step would refuse to take it
            if ("unknown".equals(inputs)) return null;
            Map<String, String> st = new LinkedHashMap<>();
            try {
                for (String l : Files.readAllLines(new File(lib.getParentFile(), "inputs.tsv").toPath(), StandardCharsets.UTF_8)) {
                    int t = l.indexOf('\t');
                    if (t > 0 && !l.startsWith("#")) st.put(l.substring(0, t), l.substring(t + 1).trim());
                }
            } catch (IOException e) {
                // no inputs.tsv (a library copied by hand): the library's own header carries the game and the inputs (dmsh_abi_vk.h: 16 bytes at 40 and 56)
                try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(lib, "r")) {
                    byte[] h = new byte[72];
                    r.readFully(h);
                    st.put("inputs", new String(h, 56, 16, StandardCharsets.US_ASCII).replace("\0", "").trim());
                } catch (IOException e2) {
                    return "The pack's shader library (" + lib + ") cannot be read: " + e2.getMessage();
                }
            }
            if (inputs.equals(st.get("inputs"))) return null;
            return "The pack's shader library was built from other shader tables (inputs " + st.get("inputs") + ") than this app's (" + inputs
                    + "): its shaders need making again.";
        }
        List<String> have = libraryVersions(dir);
        String need = BuildConfig.NX_DMSH_VK_CHAIN;
        if (have.isEmpty()) return "The pack folder " + dir + " has no shader library: this app needs shaders/v-" + need + "/dmsh_vk.lib in it.";
        return "The pack folder " + dir + " has the shader library version " + String.join(", ", have) + ", but this app needs version " + need + " (shaders/v-" + need + "/dmsh_vk.lib).";
    }

    private static String one(Map<String, List<String>> rows, String key) throws IOException {
        List<String> v = rows.get(key);
        if (v == null || v.isEmpty()) throw new IOException("pack.tsv has no " + key);
        return v.get(0);
    }

    static Pack read(File dir) throws IOException {
        File f = new File(dir, "pack.tsv");
        if (!f.isFile()) throw new IOException(dir + " has no pack.tsv");
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            int t = line.indexOf('\t');
            if (t > 0) rows.computeIfAbsent(line.substring(0, t), k -> new ArrayList<>()).add(line.substring(t + 1));
        }
        return new Pack(dir, rows);
    }

    /** The ACI0 program ID of a main.npdm, as 16 upper-case hex digits (scripts/pack_dump.py program_id). */
    static String programId(File npdm) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(npdm, "r")) {
            byte[] h = new byte[0x80];
            r.readFully(h);
            if (h[0] != 'M' || h[1] != 'E' || h[2] != 'T' || h[3] != 'A') throw new IOException(npdm + ": not an NPDM");
            long off = le32(h, 0x70);
            byte[] aci = new byte[0x18];
            r.seek(off);
            r.readFully(aci);
            if (aci[0] != 'A' || aci[1] != 'C' || aci[2] != 'I' || aci[3] != '0') throw new IOException(npdm + ": no ACI0 section");
            long id = le32(aci, 0x10) | (le32(aci, 0x14) << 32);
            return String.format(Locale.ROOT, "%016X", id);
        }
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    /** The game folder's main.npdm: a dump (base/exefs) or one version's files (exefs/). null when it is neither */
    static File npdmOf(File game) {
        for (String p : new String[] {"base/exefs/main.npdm", "exefs/main.npdm"}) {
            File f = new File(game, p);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Where a pack for this game folder usually is: <game>/../<id>-work/android-pack, or a pack folder inside the game folder. null when none is found */
    static File find(File game, String titleId) {
        List<File> c = new ArrayList<>();
        File parent = game.getParentFile();
        if (parent != null) c.add(new File(parent, titleId + "-work/android-pack"));
        c.add(new File(game, "android-pack"));
        c.add(new File(game, titleId + "-work/android-pack"));
        File[] subs = game.listFiles(File::isDirectory);
        if (subs != null) for (File s : subs) c.add(s);
        for (File d : c) if (new File(d, "pack.tsv").isFile()) return d;
        return null;
    }

    /** The RomFS folder the host mounts: the one pack.tsv names, else the game folder's romfs/, else the newest updates/<version>/romfs */
    File romfs(File game) {
        File f = new File(game, romfsRel);
        if (f.isDirectory()) return f;
        f = new File(game, "romfs");
        if (f.isDirectory()) return f;
        File[] ups = new File(game, "updates").listFiles(File::isDirectory);
        File best = null;
        if (ups != null) for (File u : ups) {
            if (!u.getName().matches("\\d+") || !new File(u, "romfs").isDirectory()) continue;
            if (best == null || Long.parseLong(u.getName()) > Long.parseLong(best.getName())) best = u;
        }
        return best != null ? new File(best, "romfs") : null;
    }
}
