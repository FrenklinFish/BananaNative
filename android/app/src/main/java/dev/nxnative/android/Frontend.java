package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * What an emulator frontend (ES-DE, Daijisho, Beacon, Pegasus…) asks FrontendLaunch for, as plain Java (no Android, checked on the JVM by FrontendJvmTest): the
 * game's path from a file:// URI or from a content:// URI that carries a /storage path, the phone's aliases of its shared storage made one, the card that holds a
 * path, and a title ID. FRONTENDS.md says what each frontend sends.
 */
final class Frontend {
    private Frontend() {}

    /** The shared storage's other names (the same files by every one of them) → the name the host's paths use */
    private static final String PRIMARY = "/storage/emulated/0";
    private static final String[] ALIASES = {"/sdcard", "/mnt/sdcard", "/storage/self/primary", "/mnt/user/0/primary", "/storage/emulated/legacy"};

    /** %XX escapes (UTF-8) decoded; '+' stays (a URI path, not a form). A bad escape is kept as it is */
    static String decode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() &&hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                b.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 3;
            } else {
                byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c) && i + 1 < s.length()) { u = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8); i++; }
                b.write(u, 0, u.length);
                i++;
            }
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** file:///storage/… (or file:/storage/…) → its path, decoded; null for any other URI or a relative path */
    static String fileUri(String uri) {
        if (uri == null || !uri.regionMatches(true, 0, "file:", 0, 5)) return null;
        String rest = uri.substring(5);
        if (rest.startsWith("//")) {   // file://<authority>/path: only an empty authority or localhost names this phone
            int slash = rest.indexOf('/', 2);
            if (slash < 0) return null;
            String host = rest.substring(2, slash);
            if (!host.isEmpty() && !host.equalsIgnoreCase("localhost")) return null;
            rest = rest.substring(slash);
        }
        int q = indexOfAny(rest, "?#");
        if (q >= 0) rest = rest.substring(0, q);
        String p = decode(rest);
        return p.startsWith("/") ? p : null;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
        return -1;
    }

    /** A content:// URI's decoded path that carries the file's own path (a FileProvider "root" path: …/root/storage/emulated/0/Games/x.nup) → that path; else null */
    static String embeddedPath(String uriPath) {
        if (uriPath == null) return null;
        for (String root : new String[] {"/storage/", "/sdcard/", "/mnt/media_rw/"}) {
            int at = uriPath.indexOf(root);
            if (at >= 0) return uriPath.substring(at);
        }
        return null;
    }

    /**
     * A SAF document ID's path within its volume (the part after "primary:" or "<volume ID>:") → the file's path under the volume's root. Most providers give
     * it relative to the volume ("Switch2/x.nup"); some (the HyperOS picker, issue #19) give the file's whole path ("/storage/emulated/0/Switch2/x.nup" or
     * "storage/emulated/0/…"), which joined to the root named the storage twice: a path that is already under the root, or under another name of the shared
     * storage, is the file's own path
     */
    static String volumePath(String root, String rel) {
        if (rel == null || rel.isEmpty()) return root;
        String abs = "/" + rel.replaceFirst("^/+", "");
        if (abs.equals(root) || abs.startsWith(root + "/")) return abs;
        String n = normal(abs);
        if (rel.startsWith("/") && normal(root).equals(PRIMARY) && n.startsWith(PRIMARY + "/")) return abs;   // under another name of the shared storage (/sdcard/…)
        return root + abs;
    }

    /** One name per file: the shared storage's aliases become /storage/emulated/0, "//", "/./" and a trailing '/' go */
    static String normal(String p) {
        if (p == null || p.isEmpty()) return p;
        String s = p.replaceAll("/+", "/").replace("/./", "/");
        if (s.endsWith("/.")) s = s.substring(0, s.length() - 2);
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        for (String a : ALIASES) if (s.equals(a) || s.startsWith(a + "/")) return PRIMARY + s.substring(a.length());
        return s;
    }

    /** Whether two paths name the same game (by normal; the cards keep the path the user picked, the frontend may send another alias of it) */
    static boolean same(String a, String b) { return a != null && b != null && normal(a).equals(normal(b)); }

    /** The first of the cards' game paths that names path, or -1 */
    static int card(List<String> gameDirs, String path) {
        for (int i = 0; i < gameDirs.size(); i++) if (same(gameDirs.get(i), path)) return i;
        return -1;
    }

    /** A title ID as the cards keep it (16 hex digits, upper case), or null */
    static String titleId(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        return t.matches("[0-9A-Fa-f]{16}") ? t.toUpperCase(Locale.ROOT) : null;
    }
}
