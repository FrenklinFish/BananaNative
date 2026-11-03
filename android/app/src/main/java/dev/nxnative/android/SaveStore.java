package dev.nxnative.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A game's save backups and restores (the game card's Manage sheet: Back up saves, Restore saves…, Import save…). No Android classes: the JVM test
 * (android/app/src/jvmtest, run by dm/tools/tests/test_android_saves.py) runs it on the Mac, and checks its journal against the host's (plat_android.c).
 * <p>
 * <b>The save</b> is the folder the app gives the host as DM_SAVE (play saves/&lt;id&gt;, test saves-test/&lt;id&gt;, or the folder picked in Folders) and, for a game
 * whose main imports both MountSaveData and MountDeviceSaveData, its device save &lt;DM_SAVE&gt;_device beside it (hle_fs.c h_fs_MountDeviceSave). Both are one save
 * here: a backup holds both (main/ and device/), a restore swaps both.
 * <ul>
 * <li><b>Lock</b>: every backup, restore and recover holds &lt;save&gt;.lock (FileChannel.lock), which a game start takes too and keeps while its process lives
 *     (GameActivity), so a backup or restore never meets a running game of that save, and two never meet each other.</li>
 * <li><b>Back up</b> copies into &lt;Switch2&gt;/save-backups/&lt;id&gt;/.partial-&lt;name&gt;/ (main/, device/), checks the copy file by file against the save (path,
 *     size, SHA-256) and scans the save again (a save that changed during the copy fails), writes the manifest &lt;name&gt;.manifest.tsv beside it, and only then gives
 *     the folder its name &lt;yyyyMMdd-HHmmss&gt;. The save is only read. Nothing ever deletes a finished backup.</li>
 * <li><b>Restore</b> (or Import from a folder): the source is checked against its manifest (a damaged backup is refused); the current save, when it has anything, is
 *     backed up first as &lt;stamp&gt;-before-restore (verified as above; when that fails nothing else happens); each part of the source is copied into
 *     &lt;part&gt;.restore beside its target (the same filesystem) and checked; the set file &lt;save&gt;.restore-set names the pairs (fsynced), each target gets its
 *     marker &lt;part&gt;.pend1 (holding the .restore path, fsynced), the set file goes, then each part is swapped in under the host's roll-forward journal
 *     (plat_android.c plat_rename_swap: shared storage is FUSE, with no RENAME_EXCHANGE and no xattrs): stage 1 deletes what the target has and the copy lacks; .pend2
 *     replaces .pend1; stage 2 renames the copy's files over the target's (each rename atomic); the markers go. A crash before the set file leaves the save untouched;
 *     after it, {@link #recover} (before every backup, restore and game start) gives each part the set names its marker (no swap has started while the set file is
 *     there, so every copy is complete) and finishes each swap, so the save (both parts) is the old version or the restored one, never a mix.</li>
 * <li><b>Journal rules</b>, the same as plat_swap_recover's: a legacy &lt;b&gt;.old comes back when b is missing and goes when b is there; an empty marker is dropped
 *     and the next looked at; a marker naming anything but b's own b.new / b.re / b.restore is refused, and so is .pend1 whose folder is gone (stage 1 would empty
 *     b): b and the marker are then left as they are.</li>
 * </ul>
 */
final class SaveStore {
    static final String MANIFEST = ".manifest.tsv", PARTIAL = ".partial-", BEFORE_RESTORE = "-before-restore", RESTORE_SUFFIX = ".restore", DEVICE_SUFFIX = "_device",
            SET_SUFFIX = ".restore-set", LOCK_SUFFIX = ".lock";
    static final String MAIN = "main", DEVICE = "device";
    /** b's siblings a marker may name (plat_android.c swap_marker_ok) */
    static final String[] SWAP_SOURCES = {".new", ".re", RESTORE_SUFFIX};
    /** The working folders beside a save (the host's transaction, the journal, the launcher's copy): never a source to restore from */
    private static final String[] SIBLINGS = {".new", ".re", ".work", ".old", RESTORE_SUFFIX};
    private static final String HEAD = "# nxnative save backup 1";
    /** A .partial-* folder or a manifest without its folder this old is left over from a stopped backup */
    static final long STALE_MS = 24L * 3600 * 1000;

    private SaveStore() {}

    /** The device save beside a save (hle_fs.c: <DM_SAVE>_device) */
    static File device(File save) { return new File(save.getPath() + DEVICE_SUFFIX); }

    /** The parts of a save: main, and the device save when it exists */
    static Map<String, File> parts(File save) {
        Map<String, File> m = new LinkedHashMap<>();
        m.put(MAIN, save);
        if (device(save).isDirectory()) m.put(DEVICE, device(save));
        return m;
    }

    // ---- The lock ----

    /**
     * &lt;save&gt;.lock, held: close() releases it (the process ending does too). One FileChannel per lock file and process, kept open for the process's life: a POSIX
     * record lock belongs to the process and goes when the process closes ANY descriptor of that file, so a second channel opened and closed beside a held lock (a check
     * that the save is in use) would drop it. Within the process the held set answers instead
     */
    static final class Lock implements AutoCloseable {
        private final String key;
        private final FileLock lock;

        private Lock(String key, FileLock l) { this.key = key; lock = l; }

        @Override public void close() {
            synchronized (CHANNELS) {
                try { lock.release(); } catch (IOException e) { /* the process ending releases it */ }
                HELD.remove(key);
            }
        }
    }

    private static final Map<String, FileChannel> CHANNELS = new java.util.HashMap<>();
    private static final java.util.Set<String> HELD = new java.util.HashSet<>();

    static File lockFile(File save) { return new File(save.getAbsoluteFile().getPath() + LOCK_SUFFIX); }

    /** Takes the save's lock; null when another process (a running game, a backup or restore) or this one holds it. Never closes a channel (see Lock) */
    static Lock tryLock(File save) throws IOException {
        File f = lockFile(save);
        String key = canon(f);
        synchronized (CHANNELS) {
            if (HELD.contains(key)) return null;   // this process holds it: asking the channel again would only throw OverlappingFileLockException
            FileChannel c = CHANNELS.get(key);
            if (c == null || !c.isOpen()) {
                File p = f.getParentFile();
                if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("cannot create " + p);
                c = new RandomAccessFile(f, "rw").getChannel();
                CHANNELS.put(key, c);
            }
            FileLock l;
            try {
                l = c.tryLock();
            } catch (OverlappingFileLockException e) {
                return null;   // held through this channel already: the channel stays open
            }
            if (l == null) return null;   // another process holds it
            HELD.add(key);
            return new Lock(key, l);
        }
    }

    /** The lock, or IOException with a sentence when the save is in use */
    static Lock lock(File save) throws IOException {
        Lock l = tryLock(save);
        if (l == null) throw new IOException("the save " + save + " is in use: its game is running (stop it first) or a backup or restore of it is working");
        return l;
    }

    /** Whether the save is in use now (its game runs, or a backup or restore of it works): takes the lock and lets it go at once, through the process's one channel */
    static boolean inUse(File save) {
        try (Lock l = tryLock(save)) {
            return l == null;
        } catch (IOException e) {
            return false;   // no lock file can be made: nothing holds one either
        }
    }

    // ---- Manifests: every file (SHA-256, size) and directory under a folder, by relative path ----

    static final class Manifest {
        final TreeMap<String, String> entries = new TreeMap<>();   // relative path → "f <sha> <size>" or "d"
        long bytes;
        int files;
        String source = "";
        int layout = 2;   // 2: main/ and device/ inside; 1 (the first backups): the main save's files themselves

        /** null when the two hold the same paths, kinds, sizes and hashes; else the first difference, in a sentence */
        String diff(Manifest o) { return diff(o, false); }

        /** ignoreCase: paths compared without case (shared storage is case-insensitive: a rename onto another case may keep the old name) */
        String diff(Manifest o, boolean ignoreCase) {
            Map<String, String> a = keyed(ignoreCase), b = o.keyed(ignoreCase);
            for (Map.Entry<String, String> e : a.entrySet()) {
                String v = b.get(e.getKey());
                if (v == null) return e.getKey() + " is missing";
                if (!v.equals(e.getValue())) return e.getKey() + " differs";
            }
            for (String k : b.keySet()) if (!a.containsKey(k)) return k + " is extra";
            return null;
        }

        private Map<String, String> keyed(boolean ignoreCase) {
            if (!ignoreCase) return entries;
            Map<String, String> m = new TreeMap<>();
            for (Map.Entry<String, String> e : entries.entrySet()) m.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            return m;
        }

        /** The entries under prefix/ (prefix removed); the whole manifest for prefix "" */
        Manifest sub(String prefix) {
            Manifest m = new Manifest();
            String p = prefix.isEmpty() ? "" : prefix + "/";
            for (Map.Entry<String, String> e : entries.entrySet()) {
                if (!e.getKey().startsWith(p) || e.getKey().length() == p.length()) continue;
                m.entries.put(e.getKey().substring(p.length()), e.getValue());
                if (e.getValue().startsWith("f ")) { m.files++; m.bytes += Long.parseLong(e.getValue().substring(e.getValue().lastIndexOf(' ') + 1)); }
            }
            return m;
        }

        void add(String prefix, Manifest m) {
            entries.put(prefix, "d");
            for (Map.Entry<String, String> e : m.entries.entrySet()) entries.put(prefix + "/" + e.getKey(), e.getValue());
            files += m.files;
            bytes += m.bytes;
        }
    }

    static Manifest scan(File root) throws IOException {
        Manifest m = new Manifest();
        if (!root.isDirectory()) throw new IOException(root + " is not a folder");
        scan(root, "", m);
        return m;
    }

    /** A save's parts as one manifest: main/… and device/… */
    static Manifest scanParts(Map<String, File> parts) throws IOException {
        Manifest m = new Manifest();
        for (Map.Entry<String, File> e : parts.entrySet()) m.add(e.getKey(), e.getValue().isDirectory() ? scan(e.getValue()) : new Manifest());
        return m;
    }

    private static void scan(File dir, String rel, Manifest m) throws IOException {
        String[] names = dir.list();
        if (names == null) throw new IOException("cannot list " + dir);
        Arrays.sort(names);
        for (String n : names) {
            File f = new File(dir, n);
            String r = rel.isEmpty() ? n : rel + "/" + n;
            if (Files.isSymbolicLink(f.toPath())) throw new IOException(r + " is a link: a save folder holds files and folders only");
            if (Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                m.entries.put(r, "d");
                scan(f, r, m);
            } else if (Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                long size = f.length();
                m.entries.put(r, "f " + sha256(f) + " " + size);
                m.bytes += size;
                m.files++;
            } else throw new IOException(r + " is neither a file nor a folder");
        }
    }

    static String sha256(File f) throws IOException {
        MessageDigest h;
        try { h = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) h.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : h.digest()) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    static void writeManifest(Manifest m, File to) throws IOException {
        StringBuilder s = new StringBuilder(HEAD + "\n");
        s.append("layout\t").append(m.layout).append('\n').append("source\t").append(m.source).append('\n').append("files\t").append(m.files).append('\n')
                .append("bytes\t").append(m.bytes).append('\n');
        for (Map.Entry<String, String> e : m.entries.entrySet()) s.append(e.getValue().replace(' ', '\t')).append('\t').append(e.getKey()).append('\n');
        File tmp = new File(to.getPath() + ".tmp");
        writeSynced(tmp, s.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(to)) throw new IOException("cannot write " + to);
    }

    static Manifest readManifest(File f) throws IOException {
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(HEAD)) throw new IOException(f + " is not a save backup manifest");
        Manifest m = new Manifest();
        m.layout = 1;
        for (String l : lines.subList(1, lines.size())) {
            String[] c = l.split("\t", -1);
            if (c[0].equals("source") && c.length == 2) m.source = c[1];
            else if (c[0].equals("layout") && c.length == 2) m.layout = Integer.parseInt(c[1]);
            else if (c[0].equals("f") && c.length == 4) { m.entries.put(c[3], "f " + c[1] + " " + c[2]); m.files++; m.bytes += Long.parseLong(c[2]); }
            else if (c[0].equals("d") && c.length == 2) m.entries.put(c[1], "d");
        }
        return m;
    }

    // ---- Backups ----

    static final class Backup {
        final File dir;
        final String name, source;
        final long bytes;
        final int files;
        final boolean device;

        Backup(File dir, Manifest m) {
            this.dir = dir;
            name = dir.getName();
            source = m.source;
            bytes = m.bytes;
            files = m.files;
            device = m.layout >= 2 && m.entries.containsKey(DEVICE);
        }

        boolean beforeRestore() { return name.contains(BEFORE_RESTORE); }
    }

    /** The backups in a game's backups folder, newest first (their names start with the time; a folder without its manifest, or one still being made, is not one) */
    static List<Backup> list(File backups) {
        List<Backup> out = new ArrayList<>();
        File[] l = backups.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (l == null) return out;
        Arrays.sort(l, (a, b) -> b.getName().compareTo(a.getName()));
        for (File d : l) {
            try { out.add(new Backup(d, readManifest(new File(backups, d.getName() + MANIFEST)))); } catch (IOException | RuntimeException e) { /* not a backup */ }
        }
        return out;
    }

    /** A name not taken in the backups folder: name, else name-2, name-3, … */
    static String freeName(File backups, String name) {
        String n = name;
        for (int k = 2; new File(backups, n).exists() || new File(backups, n + MANIFEST).exists() || new File(backups, PARTIAL + n).exists(); k++) n = name + "-" + k;
        return n;
    }

    /**
     * What a stopped backup left in the backups folder, older than STALE_MS: a .partial-* folder that matches its manifest gets its name (the backup finished but for the
     * rename); other .partial-* folders, and manifests (and their .tmp) without their folder, go. Never a finished backup (a folder with its manifest). Called under the
     * save's lock, so no backup of that save is being made; returns what was renamed or removed
     */
    static List<File> cleanStale(File backups, long now) {
        List<File> gone = new ArrayList<>();
        File[] l = backups.listFiles();
        if (l == null) return gone;
        // a .partial-<name> whose manifest is beside it was checked and stopped before its rename: it matches its manifest, so it gets its name (a finished backup)
        for (File f : l) {
            String n = f.getName();
            if (!n.startsWith(PARTIAL) || !f.isDirectory() || now - f.lastModified() < STALE_MS) continue;
            String name = n.substring(PARTIAL.length());
            File mf = new File(backups, name + MANIFEST), dir = new File(backups, name);
            try {
                if (mf.isFile() && !dir.exists() && readManifest(mf).diff(scan(f)) == null && f.renameTo(dir)) gone.add(f);
            } catch (IOException | RuntimeException e) {
                // not verifiable: removed below as a stopped copy
            }
        }
        l = backups.listFiles();
        if (l == null) return gone;
        for (File f : l) {
            String n = f.getName();
            if (now - f.lastModified() < STALE_MS) continue;
            boolean partial = n.startsWith(PARTIAL) && f.isDirectory();
            boolean orphan = f.isFile() && (n.endsWith(MANIFEST) && !new File(backups, n.substring(0, n.length() - MANIFEST.length())).isDirectory()
                    || n.endsWith(MANIFEST + ".tmp"));
            if (partial || orphan) { deleteTree(f); gone.add(f); }
        }
        return gone;
    }

    /** Copies the save (main and device) into backups/<name> (or name-2…), verified; the save is only read (after recover). Takes the save's lock */
    static Backup backup(File save, File backups, String name) throws IOException {
        try (Lock l = lock(save)) {
            return backupLocked(save, backups, name);
        }
    }

    private static Backup backupLocked(File save, File backups, String name) throws IOException {
        recoverLocked(save);
        Map<String, File> parts = parts(save);
        if (!save.isDirectory() && !parts.containsKey(DEVICE)) throw new IOException("there is no save folder at " + save);
        if (!save.isDirectory()) parts.remove(MAIN);
        if (!backups.isDirectory() && !backups.mkdirs()) throw new IOException("cannot create " + backups);
        for (File p : parts.values()) if (inside(backups, p) || inside(p, backups)) throw new IOException("the backups folder " + backups + " and the save folder " + p + " overlap");
        cleanStale(backups, System.currentTimeMillis());
        String n = freeName(backups, name);
        File tmp = new File(backups, PARTIAL + n), dir = new File(backups, n);
        Manifest want = scanParts(parts);
        want.source = save.getAbsolutePath();
        try {
            if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
            for (Map.Entry<String, File> e : parts.entrySet()) copyTree(e.getValue(), new File(tmp, e.getKey()));
            String d = want.diff(scan(tmp));
            if (d != null) throw new IOException("the copy does not match the save (" + d + ")");
            Manifest again = scanParts(parts);
            String changed = want.diff(again);
            if (changed != null) throw new IOException("the save changed while it was copied (" + changed + "): is the game running?");
            writeManifest(want, new File(backups, n + MANIFEST));
            if (!tmp.renameTo(dir)) { new File(backups, n + MANIFEST).delete(); throw new IOException("cannot name the backup " + dir); }
        } finally {
            if (tmp.exists()) deleteTree(tmp);   // a partial copy (never a finished backup: it has its own name by now)
        }
        return new Backup(dir, want);
    }

    /** A source's parts and their manifest: a backup's own (its files checked against it: a damaged backup is refused), else a folder to import (with a <folder>_device beside it, if any) */
    static final class Source {
        final Map<String, File> parts = new LinkedHashMap<>();
        Manifest manifest;   // main/…, device/…
    }

    static Source source(File from) throws IOException {
        Source s = new Source();
        File mf = new File(from.getParentFile(), from.getName() + MANIFEST);
        if (mf.isFile()) {
            Manifest want = readManifest(mf);
            String d = want.diff(scan(from));
            if (d != null) throw new IOException("the backup " + from + " does not match its manifest (" + d + "): it is damaged, so it is not restored");
            if (want.layout >= 2) {
                for (String p : new String[] {MAIN, DEVICE}) if (want.entries.containsKey(p)) s.parts.put(p, new File(from, p));
                s.manifest = want;
            } else {   // a first-layout backup: the main save's files themselves
                s.parts.put(MAIN, from);
                s.manifest = new Manifest();
                s.manifest.add(MAIN, want);
            }
            return s;
        }
        s.parts.put(MAIN, from);
        if (device(from).isDirectory()) s.parts.put(DEVICE, device(from));
        s.manifest = scanParts(s.parts);
        return s;
    }

    /** What restore did: the automatic backup of the save it replaced (null when the save was empty), the parts restored, and the save now */
    static final class Restored {
        Backup before;
        List<String> parts = new ArrayList<>();
        Manifest now;
    }

    /** Why a folder may not be restored from into this save (it is the save, inside it, holds it, or is one of the working folders beside it); null when it may */
    static String refuseSource(File from, File save) {
        for (File t : new File[] {save, device(save)}) {
            if (inside(from, t) || inside(t, from)) return "the folder to restore from (" + from + ") and the save folder (" + t + ") overlap";
            for (String x : SIBLINGS) if (same(from, new File(t.getPath() + x))) return from + " is a working folder of the save (" + x + "), not a save to restore";
        }
        return null;
    }

    /**
     * Replaces the save (main, and device when the source has one) with a backup's (or a folder's: Import), after an automatic backup of the current save
     * (backups/<stamp>-before-restore). Verified at each step; see the class comment for the journal. Takes the save's lock: refused while its game runs
     */
    static Restored restore(File from, File save, File backups, String stamp) throws IOException {
        String why = refuseSource(from, save);
        if (why != null) throw new IOException(why);
        try (Lock l = lock(save)) {
            recoverLocked(save);
            Source src = source(from);
            if (!src.parts.containsKey(DEVICE) && hasFiles(device(save)))
                throw new IOException(from + " has no device save, but " + device(save) + " has one: restoring only the main save would pair it with another device save, so it is not "
                        + "restored. A backup made by this app holds both");
            Restored r = new Restored();
            boolean any = false;
            for (File p : parts(save).values()) { String[] c = p.list(); if (c != null && c.length > 0) any = true; }
            if (any) r.before = backupLocked(save, backups, stamp + BEFORE_RESTORE);
            Map<File, File> pairs = new LinkedHashMap<>();   // target → its copy
            try {
                for (Map.Entry<String, File> e : src.parts.entrySet()) {
                    File target = e.getKey().equals(MAIN) ? save : device(save);
                    File a = new File(target.getAbsoluteFile().getParentFile(), target.getName() + RESTORE_SUFFIX);
                    if (a.exists()) deleteTree(a);   // left by a restore stopped before its set file: nothing names it (recover ran)
                    if (a.exists()) throw new IOException("cannot clear " + a);
                    copyTree(e.getValue(), a);
                    pairs.put(target, a);
                    String d = src.manifest.sub(e.getKey()).diff(scan(a));
                    if (d != null) throw new IOException("the copy beside the save does not match the backup (" + d + "): the save was not changed");
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
                    r.parts.add(e.getKey());
                }
            } catch (IOException | RuntimeException e) {
                for (File a : pairs.values()) deleteTree(a);
                throw e;
            }
            // From the set file on, the restore is rolled forward, also after a crash (recover). The set file goes once every part has its marker, before any swap
            // starts: while it is there every copy is still complete, so recover may give a part without a marker its .pend1
            StringBuilder set = new StringBuilder();
            for (Map.Entry<File, File> e : pairs.entrySet()) set.append(e.getKey().getAbsolutePath()).append('\t').append(e.getValue().getAbsolutePath()).append('\n');
            writeSynced(setFile(save), set.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<File, File> e : pairs.entrySet()) writeSynced(marker(e.getKey(), 1), e.getValue().getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            if (!setFile(save).delete()) throw new IOException("cannot remove " + setFile(save));
            for (Map.Entry<File, File> e : pairs.entrySet()) { apply(e.getValue(), e.getKey(), true); deleteTree(e.getValue()); }
            Map<String, File> now = new LinkedHashMap<>();
            for (String p : r.parts) now.put(p, p.equals(MAIN) ? save : device(save));
            r.now = scanParts(now);
            Manifest want = new Manifest();
            for (String p : r.parts) want.add(p, src.manifest.sub(p));
            String d = want.diff(r.now, true);
            if (d != null) throw new IOException("the save does not match the backup after the restore (" + d + ")");
            return r;
        }
    }

    // ---- The roll-forward journal (plat_android.c plat_rename_swap / plat_swap_recover, in Java) ----

    static File marker(File b, int k) { return new File(b.getPath() + ".pend" + k); }

    static File setFile(File save) { return new File(save.getPath() + SET_SUFFIX); }

    /** Whether a swap into the save is unfinished (a marker of a part, or the set file, is there) */
    static boolean pending(File save) {
        for (File b : new File[] {save, device(save)}) if (marker(b, 1).exists() || marker(b, 2).exists()) return true;
        return setFile(save).exists();
    }

    /** b gets a's content: a must be complete. a is removed afterwards. The caller holds the save's lock */
    static void swap(File a, File b) throws IOException {
        writeSynced(marker(b, 1), a.getAbsolutePath().getBytes(StandardCharsets.UTF_8));   // from here on the swap is rolled forward, also after a crash
        apply(a, b, true);
        deleteTree(a);
    }

    /**
     * Stage 2 runs only with .pend1 gone from the storage (plat_android.c swap_apply): a .pend1 left beside a stage 2 that has emptied a would run stage 1 against the empty
     * a at the next recovery and delete everything in b. So .pend2 is written, .pend1 deleted and the folder synced, then stage 2; recovering from .pend2, a .pend1 beside
     * it (a stop between the two) goes first
     */
    private static void apply(File a, File b, boolean stage1) throws IOException {
        if (stage1) {
            stage1(a, b);
            writeSynced(marker(b, 2), a.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        }
        if (!marker(b, 1).delete() && marker(b, 1).exists()) throw new IOException("cannot remove " + marker(b, 1));
        dirSync(b);
        stage2(a, b);
        if (!marker(b, 2).delete() && marker(b, 2).exists()) throw new IOException("cannot remove " + marker(b, 2));
    }

    /** Whether a marker of b may name a: one of b's own siblings b.new / b.re / b.restore (plat_android.c swap_marker_ok) */
    static boolean markerOk(File a, File b) {
        boolean name = false;
        for (String x : SWAP_SOURCES) if (a.getName().equals(b.getName() + x)) name = true;
        File pa = a.getAbsoluteFile().getParentFile(), pb = b.getAbsoluteFile().getParentFile();
        return name && pa != null && pb != null && same(pa, pb);
    }

    /** Finishes what a stop left of a swap into the save (both parts) and of a restore (its set file). Takes the save's lock. True when there was something */
    static boolean recover(File save) throws IOException {
        try (Lock l = lock(save)) {
            return recoverLocked(save);
        }
    }

    /** recover, the caller holding the save's lock (a game start keeps it while the game runs) */
    static boolean recoverLocked(File save) throws IOException {
        boolean did = false;
        File set = setFile(save);
        if (set.isFile()) {   // a restore stopped after its set file: every pair it names gets its marker (a copy without one is complete, or the set file would not be there)
            for (String line : Files.readAllLines(set.toPath(), StandardCharsets.UTF_8)) {
                String[] c = line.split("\t");
                if (c.length != 2) continue;
                File b = new File(c[0]), a = new File(c[1]);
                if (!(same(b, save) || same(b, device(save))) || !a.getName().equals(b.getName() + RESTORE_SUFFIX) || !markerOk(a, b))
                    throw new IOException(set + " names " + line.replace('\t', ' ') + ", not this save's parts: the save is left as it is; move that file away to use the save");
                // no swap has started while the set file is there: a part whose .pend1 is missing, empty or names anything but this pair's copy gets it written again
                if (a.isDirectory() && !marker(b, 2).exists() && !names(marker(b, 1), a, b)) writeSynced(marker(b, 1), a.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            }
            if (!set.delete()) throw new IOException("cannot remove " + set);
            did = true;
        }
        for (File b : new File[] {save, device(save)}) did |= recoverPart(b);
        return did;
    }

    /** Whether marker m names a, a valid marker of b */
    private static boolean names(File m, File a, File b) {
        try {
            if (!m.isFile()) return false;
            File x = new File(new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8).trim());
            return markerOk(x, b) && same(x, a);
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether a folder holds a file anywhere below it (folders alone do not count: stage 2 leaves a as empty folders) */
    static boolean hasFiles(File d) {
        String[] l = d.list();
        if (l == null) return false;
        for (String n : l) {
            File f = new File(d, n);
            if (!Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS) || hasFiles(f)) return true;
        }
        return false;
    }

    /** The folder holding f synced (Linux: a directory opens for reading and syncs), so a marker's delete is on the storage before the stage it guards */
    static void dirSync(File f) {
        File d = f.getAbsoluteFile().getParentFile();
        if (d == null) return;
        try (FileChannel c = FileChannel.open(d.toPath(), java.nio.file.StandardOpenOption.READ)) { c.force(true); } catch (IOException | RuntimeException e) { /* not every filesystem takes it */ }
    }

    private static boolean recoverPart(File b) throws IOException {
        boolean did = false;
        File old = new File(b.getPath() + ".old");   // the host's earlier three-directory-rename scheme (b -> b.old, a -> b), as plat_swap_recover handles it
        if (Files.exists(old.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.exists(b.toPath(), LinkOption.NOFOLLOW_LINKS)) { if (!old.renameTo(b)) throw new IOException("cannot move " + old + " back to " + b); }
            else deleteTree(old);
            did = true;
        }
        for (int k = 2; k >= 1; k--) {
            File m = marker(b, k);
            if (!m.isFile()) continue;
            String a = new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8).trim();
            if (a.isEmpty()) { m.delete(); did = true; continue; }   // written but empty: that stage had not started; the other marker may hold the swap
            File af = new File(a);
            if (!markerOk(af, b)) throw new IOException(m + " names " + a + ", not " + b.getName() + ".new / .re / .restore: the save is left as it is; move that marker away to use the save");
            // stage 1 deletes from b whatever a lacks: with a gone it would empty b, so it is never run then
            if (k == 1 && !af.isDirectory()) throw new IOException(m + " names " + a + ", which is gone: the save is left as it is; move that marker away to use the save");
            if (k == 1 && !hasFiles(af) && hasFiles(b)) {   // a .pend1 left by an older recovery after its stage 2 emptied a: the swap was done, b is the new version
                if (!m.delete()) throw new IOException("cannot remove " + m);
                dirSync(b);
                deleteTree(af);
                return true;
            }
            if (!b.isDirectory() && !b.mkdirs()) throw new IOException("cannot create " + b);
            apply(af, b, k == 1);
            if (af.exists()) deleteTree(af);
            return true;
        }
        return did;
    }

    private static void stage1(File a, File b) throws IOException {   // remove from b what a does not have
        String[] names = b.list();
        if (names == null) return;
        for (String n : names) {
            File pa = new File(a, n), pb = new File(b, n);
            boolean da = Files.isDirectory(pa.toPath(), LinkOption.NOFOLLOW_LINKS), db = Files.isDirectory(pb.toPath(), LinkOption.NOFOLLOW_LINKS);
            if (!Files.exists(pa.toPath(), LinkOption.NOFOLLOW_LINKS) || da != db) deleteTree(pb);
            else if (db) stage1(pa, pb);
        }
    }

    private static void stage2(File a, File b) throws IOException {   // move a's files over b's
        String[] names = a.list();
        if (names == null) return;
        for (String n : names) {
            File pa = new File(a, n), pb = new File(b, n);
            if (Files.isDirectory(pa.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (!pb.isDirectory() && !pb.mkdir()) throw new IOException("cannot create " + pb);
                stage2(pa, pb);
                pa.delete();
            } else if (!pa.renameTo(pb)) throw new IOException("cannot move " + pa + " to " + pb);
        }
    }

    // ---- Files ----

    /** Copies a folder (files and folders only; no attributes: shared storage keeps none), each file synced */
    static void copyTree(File from, File to) throws IOException {
        if (Files.isSymbolicLink(from.toPath())) throw new IOException(from + " is a link");
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            String[] l = from.list();
            if (l == null) throw new IOException("cannot list " + from);
            for (String n : l) copyTree(new File(from, n), new File(to, n));
            return;
        }
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.getFD().sync();
        }
    }

    private static void writeSynced(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            out.getFD().sync();
        }
    }

    static void deleteTree(File f) {
        if (Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] l = f.listFiles();
            if (l != null) for (File x : l) deleteTree(x);
        }
        f.delete();
    }

    /** The canonical path (links and /sdcard vs /storage/emulated/0 resolved), else the absolute one */
    static String canon(File f) {
        try { return f.getCanonicalPath(); } catch (IOException e) { return f.getAbsolutePath(); }
    }

    static boolean same(File a, File b) { return canon(a).equals(canon(b)); }

    /** Whether f is dir or inside it, by canonical paths */
    static boolean inside(File f, File dir) {
        String fp = canon(f) + "/", dp = canon(dir) + "/";
        return fp.startsWith(dp);
    }

    /** "1.2 MB" */
    static String size(long b) {
        if (b < 1024) return b + " bytes";
        if (b < 1 << 20) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1L << 30) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    /** "2026-11-03 14:05:09" from a backup's name (yyyyMMdd-HHmmss…), else the name */
    static String when(String name) {
        if (!name.matches("\\d{8}-\\d{6}.*")) return name;
        String d = name.substring(0, 4) + "-" + name.substring(4, 6) + "-" + name.substring(6, 8) + " " + name.substring(9, 11) + ":" + name.substring(11, 13) + ":" + name.substring(13, 15);
        return d + (name.contains(BEFORE_RESTORE) ? " (automatic, before a restore)" : "");
    }
}
