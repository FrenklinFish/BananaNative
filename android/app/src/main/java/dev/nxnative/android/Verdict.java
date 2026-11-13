package dev.nxnative.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * PackCheck.code's verdict kept across launches (Forgejo #214: every start hashed every module of every game again). The stored value is
 * {@code <stamp>:1} or {@code <stamp>:0}, where the stamp is the SHA-256 of what the verdict was made from (the files' sizes and times, the .nup's identity, the APK's
 * install time): a changed file or a new APK gives another stamp, so the value is no longer read and the check runs again. Anything that is not exactly that form
 * (a damaged or foreign value, null) reads as "not stored". Unknown is never stored. No Android classes: the JVM test runs it on the Mac.
 */
final class Verdict {
    private Verdict() {}

    /** What a verdict is valid for: id (the files it was made from) and apk (the APK that judged it) */
    static String stamp(String id, String apk) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((id + "\0" + apk).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : d) s.append(String.format("%02x", b & 0xff));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String format(String stamp, boolean ok) { return stamp + (ok ? ":1" : ":0"); }

    /** TRUE / FALSE when value is exactly stamp + ":1" / ":0", else null */
    static Boolean parse(String value, String stamp) {
        if (value == null || stamp == null || value.length() != stamp.length() + 2 || !value.startsWith(stamp)) return null;
        String t = value.substring(stamp.length());
        return t.equals(":1") ? Boolean.TRUE : t.equals(":0") ? Boolean.FALSE : null;
    }

    /** The stored verdict in slot (get = the store's getString), or null: nothing stored, another stamp, a damaged value, or a store that throws */
    static Boolean read(java.util.function.Function<String, String> get, String slot, String stamp) {
        try { return parse(get.apply(slot), stamp); } catch (RuntimeException e) { return null; }
    }

    /** Keeps r in slot (put = the store's putString); unknown (null) is never kept, and a store that throws is ignored. True when kept */
    static boolean keep(java.util.function.BiConsumer<String, String> put, String slot, String stamp, Boolean r) {
        if (r == null) return false;
        try { put.accept(slot, format(stamp, r)); return true; } catch (RuntimeException e) { return false; }
    }
}
