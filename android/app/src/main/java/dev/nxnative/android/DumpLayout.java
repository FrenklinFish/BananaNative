package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a dump keeps each version's code and RomFS. Dumps come in three layouts: the base alone (base/, or a .nup's base package: a game whose version is its
 * base, such as Mario Kart World 1.8.0), an update alone that carries the whole ExeFS and RomFS (updates/&lt;v&gt;/, or a .nup with only update/&lt;v&gt;: a
 * "merged" dump), or both. A version is one that has an exefs/main of its own (the base is version 0); its ExeFS and its RomFS are its own when it has them, else
 * the base's (an update overrides the base, part by part). No Android classes: GameListJvmTest runs it on the Mac.
 */
final class DumpLayout {
    private DumpLayout() {}

    /** The versions a dump holds (each with its own exefs/main), lowest first; the base is version 0. A one-version folder (exefs/ at its top) has none: it needs a pack made elsewhere */
    static List<Long> versions(File dump) {
        List<Long> out = new ArrayList<>();
        try {
            if (Nup.isNup(dump)) {
                Nup n = Nup.open(dump);
                for (Nup.Pkg p : n.packages)
                    if ((p.kind == Nup.BASE || p.kind == Nup.UPDATE) && isFile(n, p, "exefs/main")) out.add(p.kind == Nup.BASE ? 0L : p.version);
            } else {
                if (new File(dump, "base/exefs/main").isFile()) out.add(0L);   // a one-version folder (exefs/ at its top) has no version number: not here
                File[] ups = new File(dump, "updates").listFiles(File::isDirectory);
                if (ups != null) for (File u : ups) if (u.getName().matches("\\d+") && new File(u, "exefs/main").isFile()) out.add(Long.parseLong(u.getName()));
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable dump holds no version here (DumpCheck says why)
        }
        out.sort(null);
        return out;
    }

    private static boolean isFile(Nup n, Nup.Pkg p, String path) throws IOException {
        Nup.Node x = n.lookup(p, path);
        return x != null && !x.dir;
    }

    /** The newest version a dump holds, -1 when none */
    static long newest(File dump) {
        List<Long> v = versions(dump);
        return v.isEmpty() ? -1 : v.get(v.size() - 1);
    }

    /** A folder dump's folder of a version: updates/&lt;v&gt;, base, or the dump itself for a one-version folder */
    private static File dir(File dump, long v) {
        if (v != 0) return new File(dump, "updates/" + v);
        File b = new File(dump, "base");
        return b.isDirectory() || !new File(dump, "exefs").isDirectory() ? b : dump;
    }

    private static boolean nonEmpty(File d) {
        String[] l = d.list();
        return l != null && l.length > 0;
    }

    /**
     * Where a part of a version is, as the tools take it: "exefs[/…]" or "romfs[/…]" of the version itself, else of the base. A folder's path, or a .nup's
     * &lt;file&gt;.nup:&lt;selector&gt;/&lt;sub&gt; (NUP.md "Naming a package"). For a part neither has, the version's own path (whose absence the caller reports)
     */
    static String path(File dump, long v, String sub) {
        boolean romfs = sub.equals("romfs") || sub.startsWith("romfs/");
        if (Nup.isNup(dump)) return Nup.spec(dump, selector(dump, v, romfs), sub);
        File own = dir(dump, v), base = dir(dump, 0);
        if (v != 0 && own.isDirectory() && !has(own, romfs, sub) && has(base, romfs, sub)) return new File(base, sub).getPath();   // a version the dump does not have: its own path (missing)
        return new File(own, sub).getPath();
    }

    private static boolean has(File verDir, boolean romfs, String sub) {
        return romfs ? nonEmpty(new File(verDir, "romfs")) : new File(verDir, sub).exists();
    }

    /** The .nup package of a version's code (romfs false) or RomFS: the version's own when it has that part, else the base's */
    static String selector(File nup, long v, boolean romfs) {
        String own = Nup.versionSelector(v);
        if (v == 0) return own;
        try {
            Nup n = Nup.open(nup);
            Nup.Pkg p = n.find(own), b = n.base();
            if (p != null && (romfs ? hasRomfs(n, p) : p.exefsDir != Nup.NONE)) return own;
            if (p != null && b != null && (romfs ? hasRomfs(n, b) : b.exefsDir != Nup.NONE)) return "base";   // only for a version the .nup has
        } catch (IOException e) {
            // the version's own: reading it reports the error
        }
        return own;
    }

    private static boolean hasRomfs(Nup n, Nup.Pkg p) throws IOException {
        Nup.Node d = n.dir(p.romfsDir);
        return d != null && d.childCount > 0;
    }

    /** Whether a version has a RomFS (its own or the base's) with files */
    static boolean hasRomfs(File dump, long v) {
        try {
            if (Nup.isNup(dump)) {
                Nup n = Nup.open(dump);
                Nup.Pkg p = n.find(selector(dump, v, true));
                return p != null && hasRomfs(n, p);
            }
            return nonEmpty(new File(path(dump, v, "romfs")));
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether a dump is one Prepare can start from: some version with exefs/main, exefs/main.npdm and a RomFS (its own or the base's) */
    static boolean preparable(File dump) {
        for (long v : versions(dump)) if (exists(dump, v, "exefs/main.npdm") && hasRomfs(dump, v)) return true;
        return false;
    }

    /** Whether a file of a version (its own, else the base's) exists */
    static boolean exists(File dump, long v, String sub) {
        if (!Nup.isNup(dump)) return new File(path(dump, v, sub)).isFile();
        try {
            Nup n = Nup.open(dump);
            Nup.Pkg p = n.find(selector(dump, v, sub.startsWith("romfs")));
            return p != null && isFile(n, p, sub);
        } catch (IOException e) {
            return false;
        }
    }

    /** "the base", "update 458752" */
    static String name(long v) { return v == 0 ? "the base" : "update " + v; }

    /** What a dump holds, for a message: "the base (main 9e244438c3d8ef1c…) and update 65536 (main 1a2b…)" */
    static String describe(File dump) {
        List<Long> vs = versions(dump);
        if (vs.isEmpty()) return "no game code (no exefs/main in the base or an update)";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < vs.size(); i++) {
            long v = vs.get(i);
            s.append(i == 0 ? "" : i == vs.size() - 1 ? " and " : ", ").append(name(v));
            String id = mainBuild(dump, v);
            if (id != null) s.append(" (main ").append(id, 0, 16).append("…)");
        }
        return s.toString();
    }

    /** What {@link #find} found: the game and the version Prepare starts from with its modules' build IDs, or why there is none (error, a sentence) */
    static final class Found {
        String titleId, error;
        long version = -1;
        long build = -1;   // the profile's build row (its version key) whose build IDs the version's modules have: another number than version when the dump keeps that code under another version
        final Map<String, String> buildIds = new LinkedHashMap<>();   // module → its NSO build ID (64 hex digits)
        int dlc;
        boolean otherBuild;   // refused only because no version is one of the profile's builds: Try anyway (find with anyBuild) can prepare it untested
        boolean untested;     // found with anyBuild: the newest version, of a build the profile does not list
        String detail = "";   // the technical part of error (build IDs, what this app supports), for Details
    }

    /**
     * The version of a dump Prepare starts from, in any of the three layouts: its title ID (the base's main.npdm, else the newest update's), then, newest first,
     * the version whose modules are one of the profile's builds (by their NSO build IDs, wherever the version's code is), with its main.npdm of that title and a
     * RomFS with files (its own or the base's). A dump of another build is refused with what it holds and what this app supports.
     */
    static Found find(File dump) { return find(dump, false); }

    /**
     * anyBuild (Try anyway, the player's choice after a warning): a dump none of whose versions is one of the profile's builds is not refused; its newest version with
     * main.npdm and a RomFS is taken (untested), with its modules' own build IDs. The rewriter then applies only what holds for any build (build-gated cuts and the
     * host's fixed addresses check the build ID), and the shaders are found by key
     */
    static Found find(File dump, boolean anyBuild) {
        Found f = new Found();
        f.error = find(f, dump, anyBuild);
        return f;
    }

    private static String find(Found f, File dump, boolean anyBuild) {
        String fn = dump.getName();
        boolean nup = Nup.isNup(dump);
        if (Nup.wrongCase(dump)) return fn + ": rename it so that its name ends in \".nup\" (lower case); the tools and the game read only that name.";
        if (nup) {
            if (!Nup.picked(dump) && (!dump.isFile() || !dump.canRead())) return dump + " is not a file this app can read. Check that All files access is on.";
            try { Nup.open(dump); } catch (IOException e) { return fn + " cannot be used: " + e.getMessage(); }
        } else if (!dump.isDirectory()) return dump + " is not a folder this app can read. Check that All files access is on.";
        List<Long> have = versions(dump);
        if (have.isEmpty()) {
            if (!nup && new File(dump, "exefs/main.npdm").isFile())
                return fn + " is one version's folder. Pick the dump folder above it, the one with base/ (and updates/).";
            return nup ? fn + " holds no game code: no exefs/main in its base or an update package."
                    : dump + " is not a dump folder: it has no base/exefs/main and no updates/<version>/exefs/main. Pick the folder with base/ or updates/ (and dlc/).";
        }
        try {
            f.titleId = Pack.titleIdOf(dump);
        } catch (IOException e) {
            return e.getMessage();
        }
        Profile prof = Profile.of(f.titleId);
        if (prof == null) return Profile.missing(f.titleId, null);
        Map<Long, Map<String, String>> builds = prof.buildMap();
        for (int i = have.size() - 1; i >= 0 && f.version < 0; i--) {
            long v = have.get(i);
            for (Map.Entry<Long, Map<String, String>> row : builds.entrySet()) {
                Map<String, String> ids = ids(dump, v, row.getValue());
                if (ids != null) { f.version = v; f.build = row.getKey(); f.buildIds.putAll(ids); break; }
            }
        }
        if (f.version < 0 && anyBuild) {   // Try anyway: the newest version that has its code, its main.npdm and a RomFS, with its modules' own build IDs
            Map<String, String> first = builds.isEmpty() ? Map.of() : builds.values().iterator().next();
            for (int i = have.size() - 1; i >= 0 && f.version < 0; i--) {
                long v = have.get(i);
                if (!exists(dump, v, "exefs/main.npdm") || !hasRomfs(dump, v)) continue;
                Map<String, String> ids = new LinkedHashMap<>();
                for (String m : first.keySet()) {
                    try {
                        BuildCheck.Mod mod = BuildCheck.mod(dump, String.valueOf(v), m);
                        if (mod.exists()) ids.put(m, BuildCheck.nsoBuildId(mod.head(0x60), mod.label));
                    } catch (IOException e) {
                        // an unreadable module: the rewrite says so
                    }
                }
                if (!ids.containsKey("main")) continue;
                f.version = v;
                f.untested = true;
                f.buildIds.putAll(ids);
            }
        }
        if (f.version < 0) {
            f.otherBuild = true;
            f.detail = "The dump holds " + describe(dump) + ".\n" + prof.supported();
            return "This dump holds " + prof.name + " (" + Profile.versionName(have.get(have.size() - 1)) + "), not a build this app supports (it supports " + prof.supportedShort()
                    + "). Dump that version, or Try anyway: it may not start or may crash.";
        }
        long v = f.version;
        String where = nup ? fn + ":" + selector(dump, v, false) : name(v);
        if (!exists(dump, v, "exefs/main.npdm")) return where + " has no exefs/main.npdm: the dump is incomplete.";
        if (!hasRomfs(dump, v)) return name(v) + " of " + fn + " has no RomFS with files (neither its own nor the base's): the dump is incomplete.";
        try {
            String id = npdmTitle(dump, v);
            if (!id.equals(f.titleId)) return where + " is of " + id + ", the game is " + f.titleId + ".";
            if (nup) f.dlc = Nup.open(dump).of(Nup.ADDON).size();
        } catch (IOException e) {
            return where + ": " + e.getMessage();
        }
        if (!nup) {
            File[] dlc = new File(dump, "dlc").listFiles(File::isDirectory);
            f.dlc = dlc == null ? 0 : dlc.length;
        }
        // the RomFS is this version's data (game.tsv romfs_check: a file line that differs between versions, where romfs_marker cannot tell)
        // (Try anyway: not checked, the rows are of the builds the profile lists)
        return f.untested ? null : RomfsCheck.check(prof, path(dump, v, "romfs"), f.buildIds.get("main"), v);
    }

    /**
     * The newest version of a dump whose modules are this build (module → build ID, by prefix: game.tsv keeps 16 bytes, pack.tsv 32), -1 when none. A build is
     * identified by its code, not by its version number: identical build IDs are identical code (the rewriter's cuts and the host's build gates are keyed by
     * build ID), so a dump that keeps a supported build's code under another version number (Mario Kart World "1.8.1" with 1.8.0's modules) is that build
     */
    static long versionWith(File dump, Map<String, String> build) {
        if (build == null || build.isEmpty()) return -1;
        List<Long> vs = versions(dump);
        for (int i = vs.size() - 1; i >= 0; i--) if (ids(dump, vs.get(i), build) != null) return vs.get(i);
        return -1;
    }

    /** A version's modules' build IDs when they are all of this profile build row's, else null */
    private static Map<String, String> ids(File dump, long v, Map<String, String> row) {
        Map<String, String> out = new LinkedHashMap<>();
        if (row.isEmpty()) return null;
        for (Map.Entry<String, String> m : row.entrySet()) {
            try {
                BuildCheck.Mod mod = BuildCheck.mod(dump, String.valueOf(v), m.getKey());
                if (!mod.exists()) return null;
                String id = BuildCheck.nsoBuildId(mod.head(0x60), mod.label);
                if (!id.startsWith(m.getValue())) return null;
                out.put(m.getKey(), id);
            } catch (IOException e) {
                return null;
            }
        }
        return out;
    }

    /** The program ID of a version's main.npdm (its own, else the base's) */
    static String npdmTitle(File dump, long v) throws IOException {
        if (!Nup.isNup(dump)) return Pack.programId(new File(path(dump, v, "exefs/main.npdm")));
        Nup n = Nup.open(dump);
        String sel = selector(dump, v, false);
        Nup.Pkg p = n.find(sel);
        Nup.Node x = p == null ? null : n.lookup(p, "exefs/main.npdm");
        if (x == null || x.dir) throw new IOException("no exefs/main.npdm");
        return Pack.programId(n.read(x, -1), dump.getName() + ":" + sel + "/exefs/main.npdm");
    }

    /** A version's main build ID (32 hex digits, lower case), null when it cannot be read */
    static String mainBuild(File dump, long v) {
        try {
            BuildCheck.Mod m = BuildCheck.mod(dump, String.valueOf(v), "main");
            if (!m.exists()) return null;
            return BuildCheck.nsoBuildId(m.head(0x60), m.label).substring(0, 32).toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
