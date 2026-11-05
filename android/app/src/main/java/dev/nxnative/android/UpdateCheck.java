package dev.nxnative.android;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The in-app updater's decisions, plain Java (no Android; checked on the JVM by dm/tools/tests/test_android_update.py with UpdateCheckJvmTest): which of the
 * public releases is newer than this app (Updater reads them from GitHub's releases API into Release objects), the version numbers, the asset's SHA-256 digest
 * (the notes: ReleaseNotes).
 */
final class UpdateCheck {
    private UpdateCheck() {}

    /** One asset of a release, as the releases API lists it: name, browser_download_url, size, digest (null when the API gives none) */
    static final class Asset {
        final String name, url, digest; final long size;
        Asset(String name, String url, long size, String digest) { this.name = name; this.url = url; this.size = size; this.digest = digest; }
    }

    /** One release: tag_name, draft, prerelease, body and its assets */
    static final class Release {
        final String tag, body; final boolean draft, prerelease; final List<Asset> assets;
        Release(String tag, boolean draft, boolean prerelease, String body, List<Asset> assets) {
            this.tag = tag; this.draft = draft; this.prerelease = prerelease; this.body = body; this.assets = assets;
        }
    }

    /** The update the popup offers: the release, its version and its APK */
    static final class Update {
        final Release release; final int[] version; final Asset apk; final String sha256;   // sha256: lower-case hex, null when the asset has no digest
        Update(Release r, int[] v, Asset a, String sha256) { release = r; version = v; apk = a; this.sha256 = sha256; }
        String tag() { return release.tag; }
    }

    private static final Pattern TAG = Pattern.compile("[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");
    private static final Pattern APP = Pattern.compile("[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(?:[^0-9].*)?");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{64}");

    /** A release tag's version: exactly vX.Y.Z or X.Y.Z, else null (rc tags, names, dates are not releases the updater offers) */
    static int[] tagVersion(String tag) { return match(TAG, tag); }

    /** The app's own version (versionName): X.Y.Z, optionally followed by a suffix ("0.1.3", "0.1.1-test"), else null ("0.1-bringup": no update check) */
    static int[] appVersion(String name) { return match(APP, name); }

    private static int[] match(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s.trim());
        if (!m.matches()) return null;
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /** Numeric comparison, part by part (0.1.10 is above 0.1.9) */
    static int compare(int[] a, int[] b) {
        for (int k = 0; k < 3; k++) if (a[k] != b[k]) return Integer.compare(a[k], b[k]);
        return 0;
    }

    static String text(int[] v) { return v[0] + "." + v[1] + "." + v[2]; }

    /** Marks an asset whose digest names SHA-256 but is not 64 hex digits: it cannot be verified, so it is never installed */
    static final String BAD_DIGEST = "";

    /**
     * The asset's digest field as a SHA-256: "sha256:&lt;64 hex&gt;" gives the hex in lower case; null when there is no digest or it names another algorithm
     * (nothing to check against: the size is still checked); BAD_DIGEST when it says sha256 but the value is malformed.
     */
    static String sha256(String digest) {
        if (digest == null) return null;
        String d = digest.trim();
        if (d.isEmpty()) return null;
        int c = d.indexOf(':');
        if (c < 0 || !d.substring(0, c).trim().equalsIgnoreCase("sha256")) return null;
        String hex = d.substring(c + 1).trim();
        return HEX.matcher(hex).matches() ? hex.toLowerCase(Locale.ROOT) : BAD_DIGEST;
    }

    /** A release's APK: the first asset whose name ends in .apk with an https download link and a usable digest; null when it has none */
    static Asset apk(Release r) {
        if (r.assets == null) return null;
        for (Asset a : r.assets) {
            if (a == null || a.name == null || a.url == null) continue;
            if (!a.name.toLowerCase(Locale.ROOT).endsWith(".apk") || !a.url.startsWith("https://")) continue;
            if (BAD_DIGEST.equals(sha256(a.digest))) continue;
            return a;
        }
        return null;
    }

    /**
     * The newest release above the app's version (current), or null: drafts are skipped, pre-releases count (every release of this app is one), tags that are not
     * vX.Y.Z are skipped, and so is a release without an APK. The highest version wins whatever the list's order.
     */
    static Update pick(List<Release> releases, int[] current) {
        if (releases == null || current == null) return null;
        Update best = null;
        for (Release r : releases) {
            if (r == null || r.draft) continue;
            int[] v = tagVersion(r.tag);
            if (v == null || compare(v, current) <= 0) continue;
            if (best != null && compare(v, best.version) <= 0) continue;
            Asset a = apk(r);
            if (a == null) continue;
            best = new Update(r, v, a, sha256(a.digest));
        }
        return best;
    }

    /** "12.3 MB" */
    static String mb(long bytes) { return String.format(Locale.US, "%.1f MB", bytes / 1048576.0); }
}
