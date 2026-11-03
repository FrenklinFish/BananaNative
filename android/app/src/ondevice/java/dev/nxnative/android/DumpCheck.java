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
 * update's modules. Reads a few headers only; a refusal says why in a sentence the user can act on.
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
        if (!dump.isDirectory()) return dump + " is not a folder this app can read. Check that All files access is on.";
        File npdm = new File(dump, "base/exefs/main.npdm");
        if (!npdm.isFile()) {
            if (new File(dump, "exefs/main.npdm").isFile())
                return dump.getName() + " is one version's folder. Pick the dump folder above it, the one with base/ and updates/.";
            return dump + " is not a dump folder: it has no base/exefs/main.npdm. Pick the folder with base/, updates/ and dlc/.";
        }
        try {
            r.titleId = Pack.programId(npdm);
        } catch (IOException e) {
            return "base/exefs/main.npdm cannot be read: " + e.getMessage();
        }
        Profile prof = Profile.of(r.titleId);
        if (prof == null) return Profile.missing(r.titleId, "This dump");
        String game = prof.name + " (" + prof.titleId + ")";

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
            String supported = String.join(", ", builds.keySet().stream().map(String::valueOf).toArray(String[]::new));
            return (have.isEmpty() ? "This dump has no update (updates/<version>/)." : "This dump has update " + join(have) + ".") + " This app supports " + game
                    + " with update " + supported + " only: dump the game with that update installed.";
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
                            + "… of " + prof.name + " " + want + ". The dump is of another version or was modified.";
            }
        } catch (IOException e) {
            return upName + ": " + e.getMessage();
        }
        File[] dlc = new File(dump, "dlc").listFiles(File::isDirectory);
        r.dlc = dlc == null ? 0 : dlc.length;

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
            if (h[0] != 'N' || h[1] != 'S' || h[2] != 'O' || h[3] != '0') throw new IOException(nso.getName() + " is not an NSO");
            StringBuilder b = new StringBuilder();
            for (int i = 0x40; i < 0x60; i++) b.append(String.format(Locale.ROOT, "%02x", h[i] & 0xff));
            return b.toString();
        }
    }
}
