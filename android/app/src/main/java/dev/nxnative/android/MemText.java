package dev.nxnative.android;

import java.util.Locale;

/**
 * The memory figures the in-game overlay shows (top right, Overlay), as text (pure Java: dm/tools/tests/test_android_memtext.py runs it on the JVM). The game's own memory is its process's
 * resident set, VmRSS in /proc/self/status (what is in RAM now, shared pages counted whole: cheap to read, unlike Debug.getPss, which walks every mapping); the
 * phone's is ActivityManager.MemoryInfo: totalMem - availMem in use of totalMem, and lowMemory. All in GiB, as the readiness check's ram_gib (a 16 GB phone reports
 * about 14.5-15.5 GiB).
 */
final class MemText {
    private MemText() {}

    private static final double GIB = 1L << 30;
    private static final String KEY = "VmRSS:";

    /** VmRSS of a /proc/<pid>/status text in bytes, or -1 when it has none */
    static long vmRss(CharSequence status) {
        int n = status.length();
        for (int i = 0; i + KEY.length() <= n; i++) {
            if (i > 0 && status.charAt(i - 1) != '\n') continue;
            boolean match = true;
            for (int j = 0; j < KEY.length() && match; j++) match = status.charAt(i + j) == KEY.charAt(j);
            if (!match) continue;
            int k = i + KEY.length();
            while (k < n && (status.charAt(k) == ' ' || status.charAt(k) == '\t')) k++;
            long v = 0;
            int digits = 0;
            for (; k < n && status.charAt(k) >= '0' && status.charAt(k) <= '9'; k++, digits++) v = v * 10 + (status.charAt(k) - '0');
            return digits > 0 ? v * 1024 : -1;   // the kernel writes it in kB
        }
        return -1;
    }

    private static String gib(long bytes) { return String.format(Locale.ROOT, "%.1f", bytes / GIB); }

    /** The overlay's game line: "App 4.3 GiB" (the game process's resident set) */
    static String app(long rssBytes) { return rssBytes < 0 ? "App ? GiB" : "App " + gib(rssBytes) + " GiB"; }

    /** The overlay's phone line: "RAM 9.8/14.7 GiB" (in use / total); the caller marks lowMemory */
    static String system(long totalBytes, long availBytes) {
        if (totalBytes <= 0) return "RAM ?";
        return "RAM " + gib(Math.max(0, totalBytes - Math.max(0, availBytes))) + "/" + gib(totalBytes) + " GiB";
    }
}
