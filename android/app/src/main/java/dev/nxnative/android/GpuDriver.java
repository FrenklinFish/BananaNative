package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Custom GPU drivers (android/README.md "Custom GPU drivers"): a Vulkan driver zip in the layout Eden and other Android emulators use (the driver .so, anything it loads, and a
 * meta.json whose "libraryName" names the .so) is unpacked into the app's private files, gpu_drivers/<name>/, the only place a library can be loaded from. The one picked
 * here goes to the host as DM_VK_DRIVER=<that folder> (vka_driver.c loads it through libadrenotools); "System" passes nothing. A DM_VK_DRIVER line in env.txt wins.
 * The choice takes effect when the game starts. Driver binaries are never part of the APK or the repository.
 */
final class GpuDriver {
    private GpuDriver() {}

    /** The driver the picker marks " (recommended)": an installed one whose meta.json name starts with this; empty = none marked. Empty on purpose: on the PNM-N49 System
     *  stays fastest (44 fps vs 20-22 on Turnip, android/README.md "Custom GPU drivers"); the best Turnip measured is T30 (Mr_Purple, PurpleVK 26.3) */
    static final String RECOMMENDED_NAME_PREFIX = "";
    static boolean recommended(Info i) { return !RECOMMENDED_NAME_PREFIX.isEmpty() && i.name.startsWith(RECOMMENDED_NAME_PREFIX); }

    static final class Info {
        final File dir; final String name, version, vendor, library;
        Info(File dir, JSONObject m) {
            this.dir = dir;
            name = m.optString("name", dir.getName()); version = m.optString("driverVersion", ""); vendor = m.optString("vendor", ""); library = m.optString("libraryName", "");
        }
        String label() { return name + (version.isEmpty() ? "" : " (" + version + ")"); }
    }

    static File root(Context c) { return new File(c.getFilesDir(), "gpu_drivers"); }

    /** The installed drivers, by folder name */
    static List<Info> installed(Context c) {
        List<Info> out = new ArrayList<>();
        File[] l = root(c).listFiles(File::isDirectory);
        if (l == null) return out;
        Arrays.sort(l);
        for (File d : l) {
            JSONObject m = meta(new File(d, "meta.json"));
            if (m != null && new File(d, m.optString("libraryName", "-")).isFile()) out.add(new Info(d, m));
        }
        return out;
    }

    /** The picked driver, null = the system's (also when the picked one was removed) */
    static Info selected(Context c) {
        String s = Prefs.of(c).getString("gpu_driver", "");
        if (s.isEmpty()) return null;
        for (Info i : installed(c)) if (i.dir.getName().equals(s)) return i;
        return null;
    }

    static void select(Context c, Info i) { Prefs.of(c).edit().putString("gpu_driver", i == null ? "" : i.dir.getName()).commit(); }   // commit: the game runs in its own process (:game), which reads the file at start

    /** A game's own pick (its Game settings), stored per title ID: absent = the default above, SYSTEM = the phone's own driver, else a driver's folder name
     *  (":" is never in one, safe()) */
    static final String SYSTEM = ":system";
    static String choice(Context c, String titleId) { return Prefs.of(c).getString(Prefs.gameKey("gpu_driver", titleId), null); }
    /** null: back to the default */
    static void choice(Context c, String titleId, String v) {
        String k = Prefs.gameKey("gpu_driver", titleId);
        if (v == null) Prefs.of(c).edit().remove(k).commit();
        else Prefs.of(c).edit().putString(k, v).commit();
    }

    /** The driver a game starts with: its own pick, else the default; null = the system's (also when the picked one was removed) */
    static Info selected(Context c, String titleId) {
        String s = choice(c, titleId);
        if (s == null) return selected(c);
        if (s.equals(SYSTEM)) return null;
        for (Info i : installed(c)) if (i.dir.getName().equals(s)) return i;
        return null;
    }

    /** The host's switch for a run of a game (before env.txt, whose lines win) and a line for the log */
    static String env(Context c, String titleId, List<String> env) {
        Info i = selected(c, titleId);
        if (i == null) return "GPU driver: system";
        env.add("DM_VK_DRIVER=" + i.dir.getPath());
        return "GPU driver: " + i.label() + " (" + i.dir + ")";
    }

    private static JSONObject meta(File f) {
        if (!f.isFile() || f.length() > 64 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) { return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
        catch (IOException | JSONException | SecurityException e) { return null; }
    }

    private static String safe(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+", "");
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    private static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        f.delete();
    }

    /** Unpacks a driver zip into gpu_drivers/<name from its meta.json>/ (replacing an earlier one of that name). Returns the driver, or throws with the reason. */
    static Info install(Context c, InputStream zin, String fallbackName) throws IOException {
        File tmp = new File(root(c), ".installing");
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        try {
            try (ZipInputStream z = new ZipInputStream(zin)) {
                ZipEntry e; byte[] b = new byte[1 << 16]; int files = 0;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String n = new File(e.getName()).getName();   // drivers are flat; a folder in the zip is dropped, and no entry can leave the target folder
                    if (n.isEmpty() || n.startsWith(".")) continue;
                    if (++files > 256) throw new IOException("more than 256 files: not a driver zip");
                    try (OutputStream out = new FileOutputStream(new File(tmp, n))) { int k; while ((k = z.read(b)) > 0) out.write(b, 0, k); }
                }
            }
            JSONObject m = meta(new File(tmp, "meta.json"));
            if (m == null) throw new IOException("no meta.json in the zip: not a driver package");
            String lib = m.optString("libraryName", "");
            if (lib.isEmpty() || lib.contains("/") || !new File(tmp, lib).isFile()) throw new IOException("meta.json names " + (lib.isEmpty() ? "no library" : lib + ", which is not in the zip"));
            String name = safe(m.optString("name", fallbackName));
            if (name.isEmpty()) name = safe(fallbackName);
            if (name.isEmpty()) name = "driver";
            File dir = new File(root(c), name);
            deleteTree(dir);
            if (!tmp.renameTo(dir)) throw new IOException("cannot move the driver to " + dir);
            return new Info(dir, m);
        } finally { deleteTree(tmp); }
    }

    static Info install(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open " + uri);
            String last = uri.getLastPathSegment();
            return install(c, in, last == null ? "driver" : last.replaceAll(".*[/:]", "").replaceAll("\\.zip$", ""));
        }
    }

    static Info install(Context c, File zip) throws IOException {
        try (InputStream in = new FileInputStream(zip)) { return install(c, in, zip.getName().replaceAll("\\.zip$", "")); }
    }

    /** Driver zips the launcher can see without a picker: Download/ and this app's own Android/data/<app>/files/gpu_drivers/ */
    static List<File> found(Context c) {
        List<File> out = new ArrayList<>();
        File ext = c.getExternalFilesDir(null);
        for (File d : new File[] {new File("/storage/emulated/0/Download"), ext == null ? null : new File(ext, "gpu_drivers")}) {
            File[] l = d == null ? null : d.listFiles((x, n) -> n.toLowerCase().endsWith(".zip"));
            if (l == null) continue;
            Arrays.sort(l);
            for (File f : l) if (hasMeta(f)) out.add(f);
        }
        return out;
    }

    private static boolean hasMeta(File zip) {
        if (zip.length() > 512L << 20) return false;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (new File(e.getName()).getName().equals("meta.json")) return true;
        } catch (IOException | SecurityException e) { return false; }
        return false;
    }

    /** adb (android/TESTING.md): am start … --es gpu_driver_zip <zip on the phone> installs and picks it; --es gpu_driver <folder name | system> picks an installed one */
    static void fromIntent(Context c, Intent i) {
        String zip = i.getStringExtra("gpu_driver_zip"), pick = i.getStringExtra("gpu_driver");
        try {
            if (zip != null) { Info d = install(c, new File(zip)); select(c, d); Toast.makeText(c, "GPU driver installed: " + d.label(), Toast.LENGTH_LONG).show(); }
        } catch (IOException | SecurityException e) { Toast.makeText(c, "GPU driver not installed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        if (pick != null) {
            Info sel = null;
            for (Info d : installed(c)) if (d.dir.getName().equals(pick)) sel = d;
            select(c, sel);
            Toast.makeText(c, "GPU driver: " + (sel == null ? "system" : sel.label()), Toast.LENGTH_SHORT).show();
        }
    }

    /** The main screen's "GPU driver…" dialog: System or an installed driver; install from a zip found on the phone or picked (request = the picker's request code) */
    static void dialog(Activity a, int request) {
        List<Info> inst = installed(a);
        Info sel = selected(a);
        List<String> labels = new ArrayList<>();
        labels.add("System (the phone's own driver)");
        int checked = 0;
        for (int k = 0; k < inst.size(); k++) { labels.add(inst.get(k).label() + (recommended(inst.get(k)) ? " (recommended)" : "")); if (sel != null && inst.get(k).dir.equals(sel.dir)) checked = k + 1; }
        new AlertDialog.Builder(a).setTitle("GPU driver (takes effect at the next game start)")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, w) -> { select(a, w == 0 ? null : inst.get(w - 1)); d.dismiss(); })
                .setPositiveButton("Install…", (d, w) -> installDialog(a, request))
                .setNeutralButton(sel != null ? "Remove picked…" : "Close", (d, w) -> {
                    if (sel == null) return;
                    Ui.confirmDanger(a, "Remove " + sel.label() + "?", "Deleted: this driver's copy inside the app (" + sel.dir + ").\n\nKept: the zip you installed it from, "
                            + "your games, packs and saves. The game uses the phone's own driver until you pick another.", "Remove driver", () -> {
                        deleteTree(sel.dir); select(a, null);
                        Toast.makeText(a, "Removed " + sel.label() + "; the system driver is used", Toast.LENGTH_LONG).show();
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static void installDialog(Activity a, int request) {
        List<File> zips = found(a);
        List<String> labels = new ArrayList<>();
        for (File f : zips) labels.add(f.getName() + "  (" + f.getParentFile().getName() + ")");
        labels.add("Pick a zip…");
        labels.add("Download…");   // DriverFetch: GitHub releases, only on a tap
        new AlertDialog.Builder(a).setTitle("Install a driver zip (with meta.json)")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w == zips.size() + 1) { DriverFetch.dialog(a, request); return; }
                    if (w == zips.size()) {
                        a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"), request);
                        return;
                    }
                    try { Info i = install(a, zips.get(w)); select(a, i); Toast.makeText(a, "Installed and picked " + i.label(), Toast.LENGTH_LONG).show(); }
                    catch (IOException | SecurityException e) { Toast.makeText(a, "Not installed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                }).setNegativeButton("Cancel", null).show();
    }

    /** The picker's result (MainActivity.onActivityResult) */
    static void picked(Activity a, Uri uri) {
        try { Info i = install(a, uri); select(a, i); Toast.makeText(a, "Installed and picked " + i.label(), Toast.LENGTH_LONG).show(); }
        catch (IOException | SecurityException e) { Toast.makeText(a, "Not installed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
}
