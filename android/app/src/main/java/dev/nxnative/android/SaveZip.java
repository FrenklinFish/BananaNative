package dev.nxnative.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Save backups without All files access (SaveUi): where a backup goes, a backup as one .zip written through the system picker, and a picked .zip or folder
 * unpacked for Import save…. No Android classes: SaveZipJvmTest checks it.
 * <ul>
 * <li><b>Where</b> ({@link #export}): backups go to &lt;Switch2 root&gt;/save-backups/&lt;id&gt; on shared storage (they outlive an uninstall) when the app may
 *     write there (All files access); otherwise, or when that folder is inside app storage (a game copied into the app or a .nup used where it is), Back up saves
 *     makes the verified backup in the app's own save-backups/&lt;id&gt; and exports it as a .zip to a place the user picks.</li>
 * <li><b>The zip</b>: {@code <name>.manifest.tsv} and {@code <name>/main/…}, {@code <name>/device/…}, as the backup folder and its manifest lie side by side.
 *     It is read back after it is written and every file checked against the manifest (SHA-256, size).</li>
 * <li><b>Import</b> unpacks a zip (or a folder copied from the provider) into a staging folder in app storage: an entry whose name would land outside it (an
 *     absolute path, "..", a backslash, a drive letter: zip slip) refuses the whole zip, as do more than {@link #MAX_FILES} files or {@link #MAX_BYTES} bytes.
 *     {@link #importRoot} then names what to restore from: the backup folder beside its manifest (checked against it by SaveStore.source), else the save folder
 *     (its top folder when the zip has only that one, and &lt;folder&gt;_device beside it), and a backup folder copied without its manifest (main/, device/) is laid
 *     out as a save and its device save. The restore itself is SaveStore.restore: the lock, the current save backed up first, the swap.</li>
 * </ul>
 */
final class SaveZip {
    private SaveZip() {}

    static final int MAX_FILES = 100_000;
    static final long MAX_BYTES = 4L << 30;

    // ---- Where backups go ----

    /** The app's own backups folder of a game (app storage: removed with the app) */
    static File appBackups(File appRoot, String titleId) { return new File(appRoot, "save-backups/" + titleId); }

    /** Whether Back up saves exports a zip through the picker instead of writing to the shared backups folder (see the class comment) */
    static boolean export(File shared, File appRoot, boolean writable) { return !writable || CopyPlan.inApp(appRoot, shared.getPath()); }

    /** Whether the app may write dir (or make it): dir, else its nearest existing folder above, is writable */
    static boolean writable(File dir) {
        for (File f = dir.getAbsoluteFile(); f != null; f = f.getParentFile()) if (f.exists()) return f.isDirectory() && f.canWrite();
        return false;
    }

    /** The zip's file name: save-&lt;title ID&gt;-&lt;backup name&gt;.zip */
    static String zipName(String titleId, String backupName) { return "save-" + titleId + "-" + backupName.replaceAll("[^0-9A-Za-z._-]", "_") + ".zip"; }

    // ---- Out ----

    /** The backup folder dir (with its manifest beside it, dir.getName() + MANIFEST) as one zip into out; out is not closed */
    static void write(File dir, OutputStream out) throws IOException {
        File mf = new File(dir.getParentFile(), dir.getName() + SaveStore.MANIFEST);
        if (!mf.isFile()) throw new IOException("the backup " + dir + " has no manifest");
        ZipOutputStream z = new ZipOutputStream(out);
        byte[] buf = new byte[1 << 16];
        put(z, mf.getName(), mf, buf);
        z.putNextEntry(new ZipEntry(dir.getName() + "/"));
        z.closeEntry();
        for (Map.Entry<String, File> e : PackZip.files(dir).entrySet()) put(z, dir.getName() + "/" + e.getKey(), e.getValue(), buf);
        // the folders too, so an empty folder of the save comes back
        List<String> dirs = new ArrayList<>();
        dirs(dir, "", dirs);
        for (String d : dirs) { z.putNextEntry(new ZipEntry(dir.getName() + "/" + d + "/")); z.closeEntry(); }
        z.finish();
        z.flush();
    }

    private static void dirs(File d, String rel, List<String> out) {
        File[] l = d.listFiles(File::isDirectory);
        if (l == null) return;
        for (File f : l) { String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName(); out.add(r); dirs(f, r, out); }
    }

    private static void put(ZipOutputStream z, String name, File f, byte[] buf) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            for (int n; (n = in.read(buf)) > 0; ) z.write(buf, 0, n);
        }
        z.closeEntry();
    }

    /** A written zip read back: null when it holds the backup dir's manifest and every file it lists (SHA-256, size), else the first difference */
    static String check(InputStream zip, File dir) throws IOException {
        SaveStore.Manifest want = SaveStore.readManifest(new File(dir.getParentFile(), dir.getName() + SaveStore.MANIFEST));
        TreeMap<String, String> got = new TreeMap<>();
        String prefix = dir.getName() + "/";
        boolean manifest = false;
        ZipInputStream z = new ZipInputStream(zip);
        byte[] buf = new byte[1 << 16];
        for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
            String n = e.getName();
            if (n.equals(dir.getName() + SaveStore.MANIFEST)) { manifest = true; continue; }
            if (!n.startsWith(prefix) || n.length() == prefix.length()) continue;
            String rel = n.substring(prefix.length());
            if (e.isDirectory()) { got.put(rel.substring(0, rel.length() - 1), "d"); continue; }
            MessageDigest h = Nup.sha256();
            long size = 0;
            for (int k; (k = z.read(buf)) > 0; ) { h.update(buf, 0, k); size += k; }
            got.put(rel, "f " + hex(h.digest()) + " " + size);
        }
        if (!manifest) return "the manifest is missing";
        for (Map.Entry<String, String> e : want.entries.entrySet()) {
            String v = got.get(e.getKey());
            if (v == null) return e.getKey() + " is missing";
            if (!v.equals(e.getValue())) return e.getKey() + " differs";
        }
        for (String k : got.keySet()) if (!want.entries.containsKey(k)) return k + " is extra";
        return null;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    // ---- In ----

    /** A zip entry's name as a relative path inside the staging folder (no trailing '/'); IOException for one that would land outside it (zip slip) */
    static String safeName(String name) throws IOException {
        String bad = "the zip has an entry outside its folder (" + name + "), so nothing of it is imported";
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.startsWith("/") || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")) throw new IOException(bad);
        String n = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String s : n.split("/", -1)) if (s.isEmpty() || s.equals("..") || s.equals(".")) throw new IOException(bad);
        return n;
    }

    /** Unpacks a zip into stage (made, empty); refuses zip slip and more than MAX_FILES files or MAX_BYTES bytes. Returns the number of files */
    static int unpack(InputStream zip, File stage) throws IOException {
        if (!stage.isDirectory() && !stage.mkdirs()) throw new IOException("cannot make " + stage);
        String root = stage.getCanonicalPath() + File.separator;
        ZipInputStream z = new ZipInputStream(zip);
        byte[] buf = new byte[1 << 16];
        int files = 0;
        long bytes = 0;
        for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
            String rel = safeName(e.getName());
            File f = new File(stage, rel);
            if (!f.getCanonicalPath().startsWith(root)) throw new IOException("the zip has an entry outside its folder (" + e.getName() + "), so nothing of it is imported");
            if (e.isDirectory()) {
                if (!f.isDirectory() && !f.mkdirs()) throw new IOException("cannot make " + f);
                continue;
            }
            if (++files > MAX_FILES) throw new IOException("the zip has more than " + MAX_FILES + " files: it is not a save");
            File p = f.getParentFile();
            if (!p.isDirectory() && !p.mkdirs()) throw new IOException("cannot make " + p);
            if (f.exists()) throw new IOException("the zip has " + rel + " twice");
            try (OutputStream o = new FileOutputStream(f)) {
                for (int n; (n = z.read(buf)) > 0; ) {
                    bytes += n;
                    if (bytes > MAX_BYTES) throw new IOException("the zip unpacks to more than " + (MAX_BYTES >> 30) + " GiB: it is not a save");
                    o.write(buf, 0, n);
                }
            }
        }
        if (files == 0 && list(stage).isEmpty()) throw new IOException("the zip is empty");
        return files;
    }

    private static List<File> list(File d) {
        List<File> out = new ArrayList<>();
        File[] l = d.listFiles();
        if (l != null) for (File f : l) out.add(f);
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /**
     * What Import restores from in an unpacked staging folder (see the class comment): a backup folder beside its manifest; else the one save folder at its top
     * (with &lt;folder&gt;_device beside it); else the staging folder itself. A folder holding only main/ (and device/) is a backup copied without its manifest: it
     * is laid out as &lt;save&gt; and &lt;save&gt;_device (renames inside stage)
     */
    static File importRoot(File stage) throws IOException {
        List<File> top = list(stage);
        List<File> manifests = new ArrayList<>(), dirs = new ArrayList<>();
        for (File f : top) {
            if (f.isFile() && f.getName().endsWith(SaveStore.MANIFEST)) manifests.add(f);
            if (f.isDirectory()) dirs.add(f);
        }
        if (manifests.size() == 1) {
            String n = manifests.get(0).getName();
            File d = new File(stage, n.substring(0, n.length() - SaveStore.MANIFEST.length()));
            if (d.isDirectory()) return d;
        }
        File from = stage;
        if (top.size() == 1 && dirs.size() == 1) from = dirs.get(0);
        else if (top.size() == 2 && dirs.size() == 2 && dirs.get(1).getName().equals(dirs.get(0).getName() + SaveStore.DEVICE_SUFFIX)) from = dirs.get(0);
        return backupLayout(from);
    }

    /** A folder with only main/ (and device/): its parts moved out beside it as a save and its device save; anything else as it is */
    private static File backupLayout(File from) throws IOException {
        List<File> l = list(from);
        boolean main = false, other = false;
        for (File f : l) {
            if (f.isDirectory() && f.getName().equals(SaveStore.MAIN)) main = true;
            else if (!(f.isDirectory() && f.getName().equals(SaveStore.DEVICE))) other = true;
        }
        if (!main || other) return from;
        File save = new File(from.getParentFile(), from.getName() + ".save"), dev = SaveStore.device(save);
        if (save.exists() || dev.exists()) throw new IOException("cannot lay out " + from);
        if (!new File(from, SaveStore.MAIN).renameTo(save)) throw new IOException("cannot move " + new File(from, SaveStore.MAIN));
        File d = new File(from, SaveStore.DEVICE);
        if (d.isDirectory() && !d.renameTo(dev)) throw new IOException("cannot move " + d);
        return save;
    }
}
