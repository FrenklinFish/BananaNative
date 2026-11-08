package dev.nxnative.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The pure part of a .nup used in place without All files access (PickedNup): its stand-in path, the name it must have, and the descriptor form the host and the
 * packer take in its place. No Android classes, so the JVM test (PickedPathJvmTest) checks it.
 * <p>
 * A picked document has no path the app may open, but the launcher, the packer and the host name games by path. So each picked .nup gets a stand-in path in app
 * storage, {@code <app storage>/picked/<key>/<its name>}, where key is the first 16 hex digits of the SHA-256 of its document URI: the same file picked again gets
 * the same path. Nothing is at that path; Nup reads it through the opener (Nup.Opener, PickedNup), and the folder holding it is the game's own (its work folder for
 * Prepare goes there, as &lt;title ID&gt;-work does beside a dump).
 */
final class PickedPath {
    private PickedPath() {}

    static final String DIR = "picked";

    /** The folder of every stand-in path */
    static File root(File appRoot) { return new File(appRoot, DIR); }

    /** The key of a document URI: 16 lower-case hex digits of its SHA-256 */
    static String key(String uri) {
        byte[] h = Nup.sha256().digest(uri.getBytes(StandardCharsets.UTF_8));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) s.append(String.format(Locale.ROOT, "%02x", h[i] & 0xff));
        return s.toString();
    }

    /** &lt;app storage&gt;/picked/&lt;key&gt;/&lt;name&gt; */
    static File standIn(File appRoot, String key, String name) { return new File(new File(root(appRoot), key), name); }

    /** The key of a stand-in path, null when f is not one (not in picked/&lt;16 hex digits&gt;/, or not a .nup name) */
    static String keyOf(File appRoot, File f) {
        if (f == null || !f.getName().endsWith(Nup.EXT)) return null;
        File k = f.getAbsoluteFile().getParentFile();
        if (k == null || k.getParentFile() == null || !k.getParentFile().equals(root(appRoot).getAbsoluteFile())) return null;
        return k.getName().matches("[0-9a-f]{16}") ? k.getName() : null;
    }

    /**
     * Why a picked document's name cannot be used in place, in a sentence; null when it can. The host and the tools find a .nup by its lower-case extension (they
     * split at ".nup:"), and the name becomes a file name in app storage
     */
    static String nameProblem(String name) {
        if (name == null) return "The file picker gave no name for that file. Pick it from the phone's storage.";
        if (CopyPlan.safeName(name) == null) return "That file's name cannot be used (" + name + ").";
        if (!name.endsWith(Nup.EXT) && name.toLowerCase(Locale.ROOT).endsWith(Nup.EXT)) return name + ": rename it so that its name ends in \".nup\" (lower case), then pick it again.";
        if (!name.endsWith(Nup.EXT) || name.length() <= Nup.EXT.length()) return name + " is not a .nup file. Pick a .nup, or copy a dump folder into the app.";
        if (name.contains(".nup:")) return name + ": a name with \".nup:\" inside it cannot be told from a package name. Rename it.";
        return null;
    }

    /** The host's and the packer's name for an open descriptor of a picked .nup: fd:&lt;n&gt;/&lt;its name&gt; (nup_image.h; the name is for messages) */
    static String fdSpec(int fd, String name) { return "fd:" + fd + "/" + name; }

    /**
     * A path the launcher made from the stand-in path (Nup.spec: &lt;stand-in&gt;:&lt;selector&gt;/romfs, or the stand-in itself) with the stand-in replaced by the
     * descriptor's form; anything else as it is
     */
    static String forHost(String spec, File standIn, int fd) {
        String p = standIn.getAbsolutePath();
        if (spec.equals(p)) return fdSpec(fd, standIn.getName());
        if (spec.startsWith(p + ":")) return fdSpec(fd, standIn.getName()) + spec.substring(p.length());
        return spec;
    }
}
