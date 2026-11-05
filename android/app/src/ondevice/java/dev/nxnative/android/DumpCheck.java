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
 * Whether a picked folder is a dump this APK can prepare: a Switch 2 dump folder (scripts/pack_dump.py's layout: base/, updates/&lt;version&gt;/, dlc/) or a .nup
 * (NUP.md), with the base alone, an update alone or both (DumpLayout.find), of one of the APK's games (Profile) with one of its profile's builds (game.tsv build
 * rows, Profile.buildMap), checked by the NSO build IDs of the version's modules; then the free space beside it. Reads a few headers only; a refusal says why in
 * a sentence the user can act on.
 */
final class DumpCheck {
    /** Free space the job needs beyond the dump (ONDEVICE.md "Storage": a peak of about 1.5 GB, with the library briefly twice) */
    static final long NEED_BYTES = 2048L << 20;

    static final class Result {
        File dump, work;
        String titleId, error;
        long version;               // the update's version (updates/<version>)
        long build = -1;            // the profile's build (its build row's version) the version's code is: another number when the dump keeps that build under another version
        Map<String, String> buildIds = new LinkedHashMap<>();   // module → its NSO build ID (32 hex digits)
        int dlc;
        long free;

        boolean ok() { return error == null; }
    }

    private DumpCheck() {}

    /**
     * A path inside one version of a dump, as the tools take it: a folder's &lt;dump&gt;/updates/&lt;version&gt;/&lt;sub&gt; (base/ for 0), a .nup's
     * &lt;dump&gt;.nup:update/&lt;version&gt;/&lt;sub&gt; (base for 0; NUP.md "Naming a package"); the base's when the version has no such part of its own (DumpLayout)
     */
    static String versionPath(File dump, long version, String sub) { return DumpLayout.path(dump, version, sub); }

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

    /** The dump's layout and builds (DumpLayout.find: the base, an update, or both; the version whose modules are a profile build), then the free space */
    private static String check(Result r, boolean needFree) {
        DumpLayout.Found f = DumpLayout.find(r.dump);
        r.titleId = f.titleId;
        if (f.error != null) return f.error;
        r.version = f.version;
        r.build = f.build;
        for (Map.Entry<String, String> e : f.buildIds.entrySet()) r.buildIds.put(e.getKey(), e.getValue());
        r.dlc = f.dlc;
        return space(r, needFree);
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

    /** An NSO's module (build) ID, 32 bytes at 0x40, as 64 lower-case hex digits */
    static String nsoBuildId(File nso) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(nso, "r")) {
            byte[] h = new byte[0x60];
            f.readFully(h);
            return nsoBuildId(h, nso.getName());
        }
    }

    /** The same, of an NSO's first 0x60 bytes (a .nup's file: Nup.read) */
    static String nsoBuildId(byte[] h, String name) throws IOException { return BuildCheck.nsoBuildId(h, name); }
}
