package dev.nxnative.android;

import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether a picked folder is a dump this APK can prepare: a Switch 2 dump folder (scripts/pack_dump.py's layout: base/exefs, updates/&lt;version&gt;/exefs + romfs, dlc/)
 * of one of the APK's games (Profile) with one of its profile's builds (game.tsv build rows, Profile.buildMap), checked by the NSO build IDs of the
 * update's modules. Reads a few headers only; a refusal says why in a sentence the user can act on. A .nup (NUP.md) is checked the same way on its packages
 * (checkNup).
 */
final class DumpCheck {
    /** Free space the job needs beyond the dump (ONDEVICE.md "Storage": a peak of about 1.5 GB, with the library briefly twice) */
    static final long NEED_BYTES = 2048L << 20;

    static final class Result {
        File dump, work;
        String titleId, error;
        long version;               // the update's version (updates/<version>)
        Map<String, String> buildIds = new LinkedHashMap<>();   // module → its NSO build ID (32 hex digits)
        int dlc;
        long free;

        boolean ok() { return error == null; }
    }

    private DumpCheck() {}

    /** The folder of one version of a dump: base/ for version 0 (the base game), else updates/<version>/ */
    static File versionDir(File dump, long version) { return new File(dump, version == 0 ? "base" : "updates/" + version); }

    /**
     * A path inside one version of a dump, as the tools take it: a folder's &lt;dump&gt;/updates/&lt;version&gt;/&lt;sub&gt; (base/ for 0), a .nup's
     * &lt;dump&gt;.nup:update/&lt;version&gt;/&lt;sub&gt; (base for 0; NUP.md "Naming a package")
     */
    static String versionPath(File dump, long version, String sub) {
        if (Nup.isNup(dump)) return Nup.spec(dump, Nup.versionSelector(version), sub);
        return new File(versionDir(dump, version), sub).getPath();
    }

    /** The work folder a dump's job uses: <dump>/../<title ID>-work (the Mac's layout; the pack goes to its android-pack) */
    static File workOf(File dump, String titleId) {
        File parent = dump.getAbsoluteFile().getParentFile();
        return new File(parent != null ? parent : dump, titleId + "-work");
    }

    /** needFree: check the free space too (a fresh job; a resumed one has written most of what it needs already) */
    static Result check(File dump, boolean needFree) {
        Result r = new Result();
        r.dump = dump;
        r.error = check(r, needFree);
        return r;
    }

    private static String check(Result r, boolean needFree) {
        File dump = r.dump;
        if (Nup.isNup(dump) || Nup.wrongCase(dump)) {
            String e = checkNup(r);
            return e != null ? e : space(r, needFree);
        }
        if (!dump.isDirectory()) return dump + " is not a folder this app can read. Check that All files access is on.";
        File npdm = new File(dump, "base/exefs/main.npdm");
        if (!npdm.isFile()) {
            if (new File(dump, "exefs/main.npdm").isFile())
                return dump.getName() + " is one version's folder. Pick the dump folder above it, the one with base/ (and updates/).";
            return dump + " is not a dump folder: it has no base/exefs/main.npdm. Pick the folder with base/, updates/ and dlc/.";
        }
        try {
            r.titleId = Pack.programId(npdm);
        } catch (IOException e) {
            return "base/exefs/main.npdm cannot be read: " + e.getMessage();
        }
        Profile prof = Profile.of(r.titleId);
        if (prof == null) return Profile.missing(r.titleId, "This dump");

        Map<Long, Map<String, String>> builds = prof.buildMap();
        File[] ups = new File(dump, "updates").listFiles(f -> f.isDirectory() && f.getName().matches("\\d+"));
        List<Long> have = new ArrayList<>();
        if (ups != null) for (File u : ups) have.add(Long.parseLong(u.getName()));
        have.sort(null);
        if (new File(dump, "base/exefs/main").isFile()) have.add(0, 0L);   // version 0 is the base game itself (a game whose supported build is its base, no update)
        Long want = null;
        for (Long v : builds.keySet()) if (have.contains(v)) want = v;   // the newest of the profile's builds this dump has
        if (want == null) {
            have.remove(Long.valueOf(0));
            return (have.isEmpty() ? "This dump has no update (updates/<version>/)." : "This dump has update " + join(have) + ".") + " " + prof.supported();
        }
        r.version = want;
        File up = versionDir(dump, want);
        String upName = want == 0 ? "base" : "updates/" + want;
        for (String f : new String[] {"exefs/main", "exefs/main.npdm"})
            if (!new File(up, f).isFile()) return upName + " has no " + f + ": the dump is incomplete.";
        String[] romfs = new File(up, "romfs").list();
        if (romfs == null || romfs.length == 0) return upName + "/romfs is missing or empty: the dump is incomplete.";
        try {
            String upId = Pack.programId(new File(up, "exefs/main.npdm"));
            if (!upId.equals(r.titleId)) return upName + " is of " + upId + ", the base is " + r.titleId + ".";
            for (Map.Entry<String, String> m : builds.get(want).entrySet()) {
                File nso = new File(up, "exefs/" + m.getKey());
                if (!nso.isFile()) return upName + " has no exefs/" + m.getKey() + ": the dump is incomplete.";
                String id = nsoBuildId(nso);
                r.buildIds.put(m.getKey(), id);
                if (!id.startsWith(m.getValue()))
                    return upName + "/exefs/" + m.getKey() + " is build " + id.substring(0, 16) + "…, not the " + m.getValue().substring(0, 16)
                            + "… of " + prof.name + " (" + Profile.versionName(want) + "). The dump is of another version or was modified.";
            }
        } catch (IOException e) {
            return upName + ": " + e.getMessage();
        }
        File[] dlc = new File(dump, "dlc").listFiles(File::isDirectory);
        r.dlc = dlc == null ? 0 : dlc.length;
        return space(r, needFree);
    }

    /**
     * A .nup (NUP.md): the same checks as for a folder, on its packages. It opens (superblock, the TOC's SHA-256: Nup.open), has a base package, its title ID
     * (the base's main.npdm) is one of the APK's games, it has the update of a build the profile supports (update/&lt;version&gt;, or the base for version 0) with
     * exefs/main, exefs/main.npdm of that title and a RomFS with files, and that update's modules are the profile's builds (NSO build IDs). Add-ons are counted
     */
    private static String checkNup(Result r) {
        File dump = r.dump;
        String fn = dump.getName();
        if (Nup.wrongCase(dump)) return fn + ": rename it so that its name ends in \".nup\" (lower case); the tools and the game read only that name.";
        if (!dump.isFile() || !dump.canRead()) return dump + " is not a file this app can read. Check that All files access is on.";
        Nup n;
        try {
            n = Nup.open(dump);
        } catch (IOException e) {
            return fn + " cannot be used: " + e.getMessage();
        }
        Nup.Pkg base = n.base();
        if (base == null) return fn + " has no base game (an update-only .nup). Preparing needs a .nup with the base game (and its update, when the game has one).";
        try {
            r.titleId = n.programId();
        } catch (IOException e) {
            return fn + ": the base's main.npdm cannot be read: " + e.getMessage();
        }
        Profile prof = Profile.of(r.titleId);
        if (prof == null) return Profile.missing(r.titleId, "This dump");

        Map<Long, Map<String, String>> builds = prof.buildMap();
        List<Long> have = new ArrayList<>();
        for (Nup.Pkg p : n.of(Nup.UPDATE)) have.add(p.version);
        have.sort(null);
        try {
            Nup.Node bm = n.lookup(base, "exefs/main");
            if (bm != null && !bm.dir) have.add(0, 0L);
        } catch (IOException e) {
            return fn + ": " + e.getMessage();
        }
        Long want = null;
        for (Long v : builds.keySet()) if (have.contains(v)) want = v;
        if (want == null) {
            have.remove(Long.valueOf(0));
            return (have.isEmpty() ? fn + " has no update." : fn + " has update " + join(have) + ".") + " " + prof.supported();
        }
        r.version = want;
        String sel = Nup.versionSelector(want);
        Nup.Pkg up = n.find(sel);
        if (up == null) return fn + " has no " + sel + ".";
        try {
            for (String f : new String[] {"exefs/main", "exefs/main.npdm"}) {
                Nup.Node x = n.lookup(up, f);
                if (x == null || x.dir) return sel + " in " + fn + " has no " + f + ": the .nup is incomplete.";
            }
            Nup.Node rd = n.dir(up.romfsDir);
            if (rd == null || rd.childCount == 0) return sel + "/romfs in " + fn + " is missing or empty: the .nup is incomplete.";
            String upId = Pack.programId(n.read(n.lookup(up, "exefs/main.npdm"), -1), sel + "/exefs/main.npdm");
            if (!upId.equals(r.titleId)) return sel + " in " + fn + " is of " + upId + ", the base is " + r.titleId + ".";
            for (Map.Entry<String, String> m : builds.get(want).entrySet()) {
                Nup.Node nso = n.lookup(up, "exefs/" + m.getKey());
                if (nso == null || nso.dir) return sel + " in " + fn + " has no exefs/" + m.getKey() + ": the .nup is incomplete.";
                String id = nsoBuildId(n.read(nso, 0x60), m.getKey());
                r.buildIds.put(m.getKey(), id);
                if (!id.startsWith(m.getValue()))
                    return sel + "/exefs/" + m.getKey() + " in " + fn + " is build " + id.substring(0, 16) + "…, not the " + m.getValue().substring(0, 16)
                            + "… of " + prof.name + " (" + Profile.versionName(want) + "). The dump is of another version or was modified.";
            }
        } catch (IOException e) {
            return sel + " in " + fn + ": " + e.getMessage();
        }
        r.dlc = n.of(Nup.ADDON).size();
        return null;
    }

    /** The work folder beside the dump, its free space and its ondevice/ folder (a folder dump and a .nup alike) */
    private static String space(Result r, boolean needFree) {
        File dump = r.dump;
        r.work = workOf(dump, r.titleId);
        File probe = r.work.isDirectory() ? r.work : r.work.getParentFile();
        try {
            r.free = new StatFs(probe.getPath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return "Cannot read the free space of " + probe + ".";
        }
        if (needFree && r.free < NEED_BYTES)
            return String.format(Locale.ROOT, "Preparing needs %.1f GB free beside the dump; %s has %.2f GB free.", NEED_BYTES / 1073741824.0, probe, r.free / 1073741824.0);
        File od = new File(r.work, "ondevice");
        if (!od.isDirectory() && !od.mkdirs()) return "Cannot make " + od + ": the folder beside the dump is not writable.";
        return null;
    }

    private static String join(List<Long> v) {
        StringBuilder b = new StringBuilder();
        for (Long x : v) b.append(b.length() == 0 ? "" : ", ").append(x);
        return b.toString();
    }

    /** An NSO's module (build) ID, 32 bytes at 0x40, as 64 lower-case hex digits */
    static String nsoBuildId(File nso) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(nso, "r")) {
            byte[] h = new byte[0x60];
            f.readFully(h);
            return nsoBuildId(h, nso.getName());
        }
    }

    /** The same, of an NSO's first 0x60 bytes (a .nup's file: Nup.read) */
    static String nsoBuildId(byte[] h, String name) throws IOException {
        if (h.length < 0x60 || h[0] != 'N' || h[1] != 'S' || h[2] != 'O' || h[3] != '0') throw new IOException(name + " is not an NSO");
        StringBuilder b = new StringBuilder();
        for (int i = 0x40; i < 0x60; i++) b.append(String.format(Locale.ROOT, "%02x", h[i] & 0xff));
        return b.toString();
    }
}
