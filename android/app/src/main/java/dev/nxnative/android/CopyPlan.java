package dev.nxnative.android;

import java.io.File;

/**
 * The pure part of Copy into the app (GameCopy): where a picked dump or .nup goes in app storage, which names may be written there, and whether it fits.
 * No Android classes, so the JVM test (CopyPlanJvmTest) checks it.
 */
final class CopyPlan {
    private CopyPlan() {}

    /** Space kept free on the volume after a copy (the packer's work files, the pack, the saves and the system need room too) */
    static final long RESERVE = 2L << 30;

    /** The folder copies go to: &lt;app storage&gt;/games */
    static File games(File appRoot) { return new File(appRoot, "games"); }

    /** Whether path lies inside app storage's games folder (a copy: no All files access needed to read it, and Remove can delete it) */
    static boolean isCopy(File appRoot, String path) {
        if (path == null) return false;
        String g = games(appRoot).getAbsolutePath() + File.separator, p = new File(path).getAbsolutePath();
        return p.startsWith(g) && p.length() > g.length();
    }

    /** Whether path lies inside app storage at all (the app reads it without All files access) */
    static boolean inApp(File appRoot, String path) {
        if (path == null) return false;
        String r = appRoot.getAbsolutePath() + File.separator, p = new File(path).getAbsolutePath();
        return p.startsWith(r);
    }

    /** A provider's display name as one safe file name: null for none, ".", "..", or one with a path separator or a NUL in it (refused, not repaired) */
    static String safeName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..") || n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0) return null;
        return n;
    }

    /** The copy's own name while it is written: &lt;name&gt;.part beside where it goes, renamed when every byte is in, so a cut copy never looks like a game */
    static File partOf(File dest) { return new File(dest.getParentFile(), dest.getName() + ".part"); }

    /** null when total bytes fit in usable with RESERVE left, else what to tell the user */
    static String fits(long total, long usable) {
        if (total + RESERVE <= usable) return null;
        return "Not enough free space: the copy needs " + gib(total) + " GiB and " + gib(RESERVE) + " GiB stay free for Prepare and the saves; " + gib(usable) + " GiB are free.";
    }

    static String gib(long b) { return String.format(java.util.Locale.ROOT, "%.1f", b / (double) (1L << 30)); }
}
