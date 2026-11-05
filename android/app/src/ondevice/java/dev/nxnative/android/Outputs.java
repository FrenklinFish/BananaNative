package dev.nxnative.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * A folder of a step's outputs that the tool writes in place, on shared storage, and that counts as complete only once its marker file is there
 * (ONDEVICE.md "The job model"). The marker is written last, after every output is synced; it is deleted first, before anything else in the folder is
 * deleted or written again. So a kill or a reboot at any point leaves either a folder without the marker (redone) or every output on the disk with it.
 *
 * Why not a temporary folder renamed into place: on Android 11 and later a folder renamed under /storage/emulated goes through MediaProvider
 * (renameForFuse, renameDirectoryUncheckedForFuse), which moves the database row of every indexed file in it in one transaction and answers EPERM when
 * one of them does not move exactly one row; a file's rename falls back for an app with all files access. A user's device refused the split's
 * split.tmp -> split with EPERM (BananaNative issue #1; which row did not move is not known). Only files are renamed here (the marker), as job.json is.
 */
final class Outputs {
    static final String MARKER = "complete";

    private Outputs() {}

    /** Whether the folder's outputs were all written and synced (its marker is there) */
    static boolean complete(File dir) { return new File(dir, MARKER).isFile(); }

    /** Empties the folder for a new run of its tool (the marker goes first), and makes it */
    static void begin(File dir) throws IOException {
        delete(dir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
    }

    /** Syncs every output to the disk, then writes the marker: to a temporary name, synced, then renamed (a file's rename) */
    static void finish(File dir) throws IOException {
        syncTree(dir);
        File m = new File(dir, MARKER), tmp = new File(dir, MARKER + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write("complete\n".getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(tmp.toPath(), m.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Deletes the folder: its marker first (an error when it stays), then the rest */
    static void delete(File dir) throws IOException {
        File m = new File(dir, MARKER);
        if (m.exists() && !m.delete()) throw new IOException("cannot delete " + m);
        deleteTree(dir);
    }

    /** Syncs every file under f to the disk */
    static void syncTree(File f) throws IOException {
        File[] kids = Files.isSymbolicLink(f.toPath()) ? null : f.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) syncTree(k);
            else try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(k.toPath(), java.nio.file.StandardOpenOption.WRITE)) { ch.force(true); }
        }
    }

    /** Deletes a folder and what is in it (a link goes, never what it points to); a marker of these outputs in a folder goes before the rest of it */
    static void deleteTree(File f) {
        if (!Files.isSymbolicLink(f.toPath())) new File(f, MARKER).delete();
        File[] kids = Files.isSymbolicLink(f.toPath()) ? null : f.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) deleteTree(k);
            else k.delete();
        }
        f.delete();
    }
}
