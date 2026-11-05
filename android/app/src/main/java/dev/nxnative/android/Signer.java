package dev.nxnative.android;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The official-build stamp: which certificate signed this APK, and whether it is the release key's. The build tag's version, commit and date are text anyone can
 * rebuild with, so the play and release apps add "official" only when the APK's own signing certificate is the release one, and "unofficial build" otherwise;
 * the test app makes no claim. The first 16 hex digits of the signer's SHA-256 go into the first line of every game log and the readiness report, so a bug report
 * shows which key signed the build. It is a convenience, not a proof: a modified app can print any text it likes; what a user verifies is the certificate itself
 * (apksigner verify --print-certs). Pure Java (no Android): Prefs reads the certificates (PackageManager) and hands them here; SignerJvmTest drives it on the JVM.
 */
final class Signer {
    /** SHA-256 of the release signing certificate (DER), lower-case hex: read from the published v0.1.0 release APK (apksigner verify --print-certs), public data */
    static final String OFFICIAL = "ad232b1c519e8f14d1f6dbeaa2569847002714306bc93f18924dd3a7e8abe23e";

    private Signer() {}

    /** The SHA-256 of a certificate's DER bytes, lower-case hex, as apksigner prints it */
    static String sha256(byte[] der) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder s = new StringBuilder(64);
            for (byte b : h) s.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JVM and Android has SHA-256
        }
    }

    /** Official only with exactly one signer and that signer the release certificate (an APK signed by several keys is not ours, whatever else signed it) */
    static boolean official(String[] signers) {
        return signers != null && signers.length == 1 && OFFICIAL.equalsIgnoreCase(signers[0]);
    }

    /** The build tag's mark: "official" or "unofficial build" for the play and release apps, null (no claim) for the test app */
    static String mark(boolean play, String[] signers) {
        if (!play) return null;
        return official(signers) ? "official" : "unofficial build";
    }

    /** For logs and reports: the first signer's first 16 hex digits ("+<n>" when more keys signed it), "unknown" when none could be read */
    static String shortId(String[] signers) {
        if (signers == null || signers.length == 0 || signers[0] == null || signers[0].length() < 16) return "unknown";
        return signers[0].substring(0, 16).toLowerCase(java.util.Locale.ROOT) + (signers.length > 1 ? "+" + (signers.length - 1) : "");
    }

    /**
     * Which build this is, without the app's name: play app "0.1 · 6a6448d · 2026-11-02" (the version up to its "-", the commit, the build date); test app
     * "int@6a6448d · 11-02 19:51" (the branch without "android-", the commit, the build time). "+dirty" follows the commit when the tree had changes. Home shows
     * it under the title; {@link #buildTag} is it with the app's name, so the two never disagree
     */
    static String buildId(boolean play, String versionName, String commit, String dirty, String branch, String time) {
        String c = commit;
        if (c.length() > 7 && !c.equals("unknown")) c = c.substring(0, 7);
        c += dirty;
        if (play) {
            int d = versionName.indexOf('-');
            return (d > 0 ? versionName.substring(0, d) : versionName) + " · " + c + " · " + (time.length() >= 10 ? time.substring(0, 10) : time);
        }
        String b = branch.startsWith("android-") ? branch.substring(8) : branch;
        return b + "@" + c + " · " + (time.length() == 16 ? time.substring(5) : time);
    }

    /** The build tag without its mark: "Banana Native &lt;buildId&gt;" (play app), "Banana Native test · &lt;buildId&gt;" (test app) */
    static String buildTag(boolean play, String buildId) { return "Banana Native " + (play ? "" : "test · ") + buildId; }

    /** The build tag with its mark:"<tag> · official", "<tag> · unofficial build", or the tag alone (test app) */
    static String tag(String tag, boolean play, String[] signers) {
        String m = mark(play, signers);
        return m == null ? tag : tag + " · " + m;
    }
}
