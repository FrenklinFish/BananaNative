package dev.nxnative.android;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Whether a pack folder fits the picked game folder and this APK, in three parts, each with a sentence the user can act on:
 * <ul>
 * <li>{@link #build}: the pack is of the game build in the folder. pack.tsv's version and its build rows (main=…, sdk=…: the NSO build IDs the code was rewritten
 *     from) against the folder's update of that version, and that build against the builds this APK's host of that game supports (game.tsv build rows, Profile.buildMap).
 *     A mismatch means the rewritten code is of another game version: the game would crash, so the launcher does not start it and offers Prepare again.</li>
 * <li>{@link #shaders}: the pack has this APK's shader library (Pack.libraryProblem).</li>
 * <li>{@link #code} (slower: hashes the modules, so it runs off the main thread): the pack's code was rewritten by this APK's rewriter. The key
 *     scripts/pack_android.py writes as &lt;module&gt;.android.key (rewrite_key: the rewriter's files, the module, the profile's patches.py and game.tsv) is
 *     computed here from the tools in the APK (assets/pack/tools.zip) and the folder's module. Another key is not an error: a pack made by an earlier commit
 *     usually runs; the card says so and Prepare again brings the code up to date (the shaders are kept).</li>
 * </ul>
 */
final class PackCheck {
    private PackCheck() {}

    /** The folder's modules of the pack's version: updates/&lt;version&gt;/exefs/&lt;module&gt; (version 0: base/exefs; a one-version folder: exefs/) */
    static File module(File game, String version, String m) {
        File f = "0".equals(version) ? new File(game, "base/exefs/" + m) : new File(game, "updates/" + version + "/exefs/" + m);
        if (f.isFile()) return f;
        File g = new File(game, "exefs/" + m);
        return g.isFile() ? g : f;
    }

    /**
     * A module of the pack's version in a game: a file of a folder (module), or a file of a .nup's package (update/&lt;version&gt;, base for 0).
     * label names it in messages as the folder's relative path does ("updates/458752/exefs/main", "update/458752/exefs/main" in a .nup)
     */
    static final class Mod {
        final File file;        // a folder's file
        final Nup nup;          // or a .nup's
        final Nup.Node node;
        final String label;
        final String error;     // the .nup could not be read

        private Mod(File file, Nup nup, Nup.Node node, String label, String error) { this.file = file; this.nup = nup; this.node = node; this.label = label; this.error = error; }

        boolean exists() { return file != null ? file.isFile() : node != null && !node.dir; }

        /** The first n bytes (fewer when it is shorter) */
        byte[] head(int n) throws IOException {
            if (error != null) throw new IOException(error);
            if (nup != null) return nup.read(node, n);
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(file, "r")) {
                byte[] b = new byte[(int) Math.min(n, r.length())];
                r.readFully(b);
                return b;
            }
        }

        /** Its bytes into the hash */
        void hash(MessageDigest h) throws IOException {
            if (error != null) throw new IOException(error);
            if (nup != null) { h.update(nup.read(node, -1)); return; }
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int k;
                while ((k = in.read(buf)) > 0) h.update(buf, 0, k);
            }
        }

        /** What changes when it changes (the code check's cache) */
        String stamp() {
            if (file != null) return file.length() + ":" + file.lastModified();
            return nup == null ? "none" : nup.file.length() + ":" + nup.file.lastModified() + ":" + label;
        }
    }

    static Mod mod(File game, String version, String m) {
        if (!Nup.isNup(game)) {
            File f = module(game, version, m);
            return new Mod(f, null, null, rel(game, f), null);
        }
        long v;
        try { v = Long.parseLong(version); } catch (NumberFormatException e) { v = -1; }
        String sel = v < 0 ? "update/" + version : Nup.versionSelector(v), label = sel + "/exefs/" + m;
        try {
            Nup n = Nup.open(game);
            Nup.Pkg p = n.find(sel);
            return new Mod(null, n, p == null ? null : n.lookup(p, "exefs/" + m), label, null);
        } catch (IOException e) {
            return new Mod(null, null, null, label, e.getMessage());
        }
    }

    /** The pack's build rows: module → build ID (32 hex digits, as pack_android.py writes them) */
    static Map<String, String> builds(Pack p) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String b : p.builds) {
            int eq = b.indexOf('=');
            if (eq > 0) out.put(b.substring(0, eq), b.substring(eq + 1).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * null when the pack's code can be started with the game folder, else why not: a module whose rewrite did not finish (no key file: Pack.unfinishedModule),
     * a job rewriting this pack's code now or stopped while it did (the job beside the dump, or the one the launcher shows), or code of another game build (build)
     */
    static String blocking(Context c, Pack p, File game) {
        String m = p.unfinishedModule();
        if (m != null) return "The pack's game code is unfinished (" + m + ".android.key is missing: an update of the pack was stopped part way). Prepare again finishes it.";
        Job j = rewriting(c, p.dir, game, p.titleId);
        if (j != null) return "The pack's game code is being rewritten (" + j.name() + ": " + j.statusText(false) + "). Let preparing finish first.";
        return build(p, game);
    }

    /** The job that is rewriting the code of this pack folder or stopped while it did (its rewrite step is running in job.json), else null */
    static Job rewriting(Context c, File packDir, File game, String titleId) {
        File[] cands = {Job.remembered(c), Job.fileOf(DumpCheck.workOf(game, titleId))};
        for (File f : cands) {
            if (f == null || !f.isFile()) continue;
            try {
                Job j = Job.read(f);
                if (j.pack().getAbsolutePath().equals(packDir.getAbsolutePath()) && !"done".equals(j.state()) && "running".equals(j.stepState("rewrite"))) return j;
            } catch (IOException e) {
                // not a job of this app's format: it rewrites nothing
            }
        }
        return null;
    }

    /** null when the pack's code is of the game build in the folder and of a build this APK supports, else why not (one or two sentences) */
    static String build(Pack p, File game) {
        Map<String, String> want = builds(p);
        if (want.isEmpty()) return null;   // a pack from before the build rows: nothing to compare
        String name = p.name.isEmpty() ? p.titleId : p.name;
        for (Map.Entry<String, String> e : want.entrySet()) {
            Mod nso = mod(game, p.version, e.getKey());
            if (nso.error != null) return nso.error;
            if (!nso.exists())
                return "The pack is for " + name + " update " + p.version + ", but the game " + (nso.nup != null ? ".nup" : "folder") + " has no " + nso.label + ". Pick the dump that has that update, or Prepare again from this one.";
            String id;
            try {
                id = DumpCheck.nsoBuildId(nso.head(0x60), e.getKey());
            } catch (IOException ex) {
                return nso.label + " cannot be read: " + ex.getMessage();
            }
            if (!id.startsWith(e.getValue()))
                return "The pack was made from another build of " + name + " (" + e.getKey() + " " + e.getValue().substring(0, 12) + "…) than the one in the game folder ("
                        + id.substring(0, 12) + "…, update " + p.version + "). Its code would crash with this game: Prepare again makes a pack from this folder.";
        }
        Profile prof = Profile.of(p.titleId);
        Map<Long, Map<String, String>> sup = prof == null ? Map.of() : prof.buildMap();
        if (!sup.isEmpty()) {
            Map<String, String> row = null;
            try { row = sup.get(Long.parseLong(p.version)); } catch (NumberFormatException e) { /* not a number: unsupported */ }
            boolean ok = row != null;
            if (ok) for (Map.Entry<String, String> e : row.entrySet()) {
                String have = want.get(e.getKey());
                if (have != null && !have.startsWith(e.getValue()) && !e.getValue().startsWith(have)) ok = false;
            }
            if (!ok) {
                StringBuilder s = new StringBuilder();
                for (Long v : sup.keySet()) s.append(s.length() == 0 ? "" : ", ").append(v);
                return "The pack is for " + name + " update " + p.version + "; this app supports update " + s + " only. Dump the game with that update and Prepare it.";
            }
        }
        return null;
    }

    private static String rel(File game, File f) {
        String g = game.getPath() + "/", p = f.getPath();
        return p.startsWith(g) ? p.substring(g.length()) : p;
    }

    /** null when the pack has this APK's shader library, else why not */
    static String shaders(File packDir, String titleId) { return Pack.libraryProblem(packDir, titleId); }

    // ---- The rewriter's key (slow: off the main thread) ----

    private static final Map<String, Boolean> codeCache = new HashMap<>();
    private static Map<String, byte[]> tools;   // the rewriter's files from the APK's tools.zip, by repository path

    /**
     * True when every module's &lt;module&gt;.android.key in the pack is the key this APK's rewriter makes from the folder's module, false when one differs,
     * null when it cannot be told (no tools in this APK, or no key file). Cached by the files' sizes and times.
     */
    static Boolean code(Context c, Pack p, File game) {
        String[] mods = p.modules;
        StringBuilder id = new StringBuilder(p.dir.getPath()).append('|').append(game.getPath());
        for (String m : mods) {
            File k = new File(p.dir, m + ".android.key");
            id.append('|').append(k.length()).append(':').append(k.lastModified()).append(':').append(mod(game, p.version, m).stamp());
        }
        synchronized (codeCache) {
            if (codeCache.containsKey(id.toString())) return codeCache.get(id.toString());
        }
        Boolean r = codeNow(c, p, game);
        synchronized (codeCache) { codeCache.put(id.toString(), r); }
        return r;
    }

    private static Boolean codeNow(Context c, Pack p, File game) {
        Map<String, byte[]> t = tools(c);
        if (t == null) return null;
        String gd = "dm/games/" + p.titleId + "/";
        if (!t.containsKey(gd + "game.tsv")) return null;   // the APK carries the profiles of its own games only: another game's pack is not this APK's to judge
        for (String m : p.modules) {
            File kf = new File(p.dir, m + ".android.key");
            Mod nso = mod(game, p.version, m);
            if (!kf.isFile() || !nso.exists()) return null;
            try {
                String have = new String(Files.readAllBytes(kf.toPath()), StandardCharsets.UTF_8).trim();
                if (!have.equals(rewriteKey(t, gd, m, nso))) return false;
            } catch (IOException e) {
                return null;
            }
        }
        return true;
    }

    /** scripts/pack_android.py rewrite_key: SHA-256 over name\0bytes of the REWRITER files, the module, the profile's patches.py (when there is one) and game.tsv */
    static String rewriteKey(Map<String, byte[]> t, String gameDir, String module, Mod nso) throws IOException {
        MessageDigest h;
        try {
            h = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        for (String f : BuildConfig.NX_REWRITER.split(" ")) {
            byte[] b = t.get("dm/tools/" + f);
            if (b == null) throw new IOException("the APK's tools have no dm/tools/" + f);
            part(h, f, b);
        }
        h.update((module + "\0").getBytes(StandardCharsets.UTF_8));
        nso.hash(h);
        byte[] pp = t.get(gameDir + "patches.py");
        if (pp != null) part(h, "patches.py", pp);
        part(h, "game.tsv", t.get(gameDir + "game.tsv"));
        StringBuilder s = new StringBuilder();
        for (byte b : h.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s + " target=android";
    }

    private static void part(MessageDigest h, String name, byte[] b) {
        h.update((name + "\0").getBytes(StandardCharsets.UTF_8));
        h.update(b);
    }

    /** dm/tools/*.py and dm/games/&lt;id&gt;/{patches.py,game.tsv} from assets/pack/tools.zip; null without it (-PnxPackEngine=fake) */
    private static synchronized Map<String, byte[]> tools(Context c) {
        if (tools != null) return tools;
        Map<String, byte[]> out = new HashMap<>();
        try (InputStream in = c.getAssets().open("pack/tools.zip"); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                boolean want = n.startsWith("dm/tools/") && n.indexOf('/', 9) < 0 || n.startsWith("dm/games/") && (n.endsWith("/patches.py") || n.endsWith("/game.tsv"));
                if (want && !e.isDirectory()) out.put(n, z.readAllBytes());
            }
        } catch (IOException e) {
            return null;
        }
        tools = out;
        return tools;
    }
}
