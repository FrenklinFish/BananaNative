package dev.nxnative.android;

import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;

/** A Storage Access Framework tree URI → the /storage path the host can open (All files access lets it read there). */
final class PathUtil {
    private PathUtil() {}

    /** null when the tree is not on a local volume (a cloud provider, say). */
    static String treeToPath(Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (id == null) return null;
        if (id.startsWith("raw:")) return id.substring(4);   // the Downloads provider's direct paths
        if (id.startsWith("msf:")) {   // MediaStore ids: some devices put a path after the prefix
            String rest = id.substring(4);
            return rest.startsWith("/") ? rest : null;
        }
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon), rel = id.substring(colon + 1);
        String root;
        if (volume.equals("primary")) root = Environment.getExternalStorageDirectory().getAbsolutePath();
        else {   // an SD card or USB drive: its volume ID names its mount
            root = null;
            for (String p : new String[] {"/storage/" + volume, "/mnt/media_rw/" + volume}) if (new File(p).isDirectory()) { root = p; break; }
            if (root == null) return null;
        }
        return rel.isEmpty() ? root : root + "/" + rel;
    }
}
