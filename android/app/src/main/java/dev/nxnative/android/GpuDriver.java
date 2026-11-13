package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Custom GPU drivers (android/README.md "Custom GPU drivers"): a Vulkan driver zip in the layout Eden and other Android emulators use (the driver .so, anything it loads, and a
 * meta.json whose "libraryName" names the .so) is unpacked into the app's private files, gpu_drivers/<name>/, the only place a library can be loaded from (DriverZip). The one
 * picked here goes to the host as DM_VK_DRIVER=<that folder> (vka_driver.c loads it through libadrenotools); "System" passes nothing. A DM_VK_DRIVER line in env.txt wins.
 * The choice takes effect when the game starts. Driver binaries are never part of the APK or the repository. A zip comes from the system picker, Download… (DriverFetch),
 * the app's own Android/data/<app>/files/gpu_drivers/ or adb; the launcher never opens files in other folders (Download, …) by itself. Installs run off the UI thread
 * and a failed one is a message, never a crash.
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
        File[] l = root(c).listFiles(d -> d.isDirectory() && !d.getName().startsWith("."));   // never .installing or .replaced-<name> (DriverZip)
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
     *  (":" is never in one, DriverZip.safe()) */
    static final String SYSTEM = Recommend.SYSTEM;
    static String choice(Context c, String titleId) { return Prefs.of(c).getString(Prefs.gameKey("gpu_driver", titleId), null); }
    /** null: back to the default */
    static void choice(Context c, String titleId, String v) {
        String k = Prefs.gameKey("gpu_driver", titleId);
        if (v == null) Prefs.of(c).edit().remove(k).commit();
        else Prefs.of(c).edit().putString(k, v).commit();
    }

    /** The driver a game starts with: its own pick, else the recommended one for this phone (Prefs.recommendedDriver: only one installed here), else the default; null = the
     *  system's (also when the picked one was removed) */
    static Info selected(Context c, String titleId) {
        String s = choice(c, titleId);
        if (s == null) s = Prefs.recommendedDriver(c, titleId);
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
        byte[] b = DriverZip.readMeta(f);
        if (b == null) return null;
        try { return new JSONObject(new String(b, StandardCharsets.UTF_8)); } catch (JSONException e) { return null; }
    }

    /** meta.json's { name, libraryName } for DriverZip, null when it is not a JSON object */
    private static String[] metaFields(byte[] json) {
        try { JSONObject m = new JSONObject(new String(json, StandardCharsets.UTF_8)); return new String[] {m.optString("name", ""), m.optString("libraryName", "")}; }
        catch (JSONException e) { return null; }
    }

    /** Unpacks a driver zip into gpu_drivers/<name from its meta.json>/ (replacing an earlier one of that name only once the new one is checked; DriverZip). Returns the
     *  driver, or throws with the reason, leaving the installed drivers and the pick as they were. Reads files: call off the UI thread. */
    static Info install(Context c, InputStream zin, String fallbackName) throws IOException {
        File dir = DriverZip.install(root(c), zin, fallbackName, GpuDriver::metaFields);
        JSONObject m = meta(new File(dir, "meta.json"));
        if (m == null) throw new IOException("cannot read the installed " + dir + "/meta.json");
        return new Info(dir, m);
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

    /** This app's own folder for driver zips, Android/data/<app>/files/gpu_drivers/ (null without external storage). The launcher never looks into other folders
     *  (Download, …): a zip from elsewhere comes through the system picker, which the user points at it. */
    static File zipFolder(Context c) {
        File ext = c.getExternalFilesDir(null);
        return ext == null ? null : new File(ext, "gpu_drivers");
    }

    /** The .zip files in zipFolder, by name only: none is opened here (install checks the one tapped) */
    static List<File> found(Context c) {
        List<File> out = new ArrayList<>();
        File d = zipFolder(c);
        File[] l = d == null ? null : d.listFiles((x, n) -> n.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (l == null) return out;
        Arrays.sort(l);
        for (File f : l) if (f.isFile()) out.add(f);
        return out;
    }

    interface Job { Info run() throws Exception; }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** A reason for the user, without a stack trace */
    static String why(Throwable e) {
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }

    /** Runs an install off the UI thread behind a wait dialog; picks the driver only when it is installed, then says so (or why not) and runs after (on the UI
     *  thread, also after a failure; may be null). Nothing it throws reaches the app: a failed install is a message and changes no pick. */
    static void installInBackground(Activity a, String what, Job job, Runnable after) {
        Context app = a.getApplicationContext();
        AlertDialog wait = new PadFocus.Builder(a).setMessage("Installing " + what + "…").setCancelable(false).create();
        wait.show();
        new Thread(() -> {
            Info got = null; String fail = null;
            try { got = job.run(); select(app, got); }
            catch (Throwable e) { fail = why(e); }   // never let an install end the app (malformed zips throw unchecked exceptions; a huge one may run out of memory)
            final Info i = got; final String f = fail;
            UI.post(() -> {
                if (!a.isFinishing() && !a.isDestroyed()) { try { wait.dismiss(); } catch (RuntimeException ignored) { /* its window is gone */ } }
                Toast.makeText(app, f != null ? "GPU driver not installed: " + f : "GPU driver installed and picked: " + i.label(), Toast.LENGTH_LONG).show();
                if (after != null) after.run();
            });
        }, "driver-install").start();
    }

    /** adb (android/TESTING.md): am start … --es gpu_driver_zip <zip on the phone> installs and picks it (in the background); --es gpu_driver <folder name | system>
     *  picks an installed one (after the install when both are given) */
    static void fromIntent(Activity a, Intent i) {
        String zip = i.getStringExtra("gpu_driver_zip"), pick = i.getStringExtra("gpu_driver");
        Runnable doPick = pick == null ? null : () -> {
            Info sel = null;
            for (Info d : installed(a)) if (d.dir.getName().equals(pick)) sel = d;
            select(a, sel);
            Toast.makeText(a, "GPU driver: " + (sel == null ? "system" : sel.label()), Toast.LENGTH_SHORT).show();
        };
        if (zip != null) installInBackground(a, new File(zip).getName(), () -> install(a, new File(zip)), doPick);
        else if (doPick != null) doPick.run();
    }

    /** The main screen's "GPU driver…" dialog: System or an installed driver; install from a zip in the app's own folder, picked or downloaded (request = the picker's request code) */
    static void dialog(Activity a, int request) {
        List<Info> inst = installed(a);
        Info sel = selected(a);
        List<String> labels = new ArrayList<>();
        labels.add("System (the phone's own driver)");
        int checked = 0;
        for (int k = 0; k < inst.size(); k++) { labels.add(inst.get(k).label() + (recommended(inst.get(k)) ? " (recommended)" : "")); if (sel != null && inst.get(k).dir.equals(sel.dir)) checked = k + 1; }
        new PadFocus.Builder(a).setTitle("GPU driver (takes effect at the next game start)")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, w) -> { select(a, w == 0 ? null : inst.get(w - 1)); d.dismiss(); })
                .setPositiveButton("Install…", (d, w) -> installDialog(a, request))
                .setNeutralButton(sel != null ? "Remove picked…" : "Close", (d, w) -> {
                    if (sel == null) return;
                    Ui.confirmDanger(a, "Remove " + sel.label() + "?", "Deleted: this driver's copy inside the app (" + sel.dir + ").\n\nKept: the zip you installed it from, "
                            + "your games, packs and saves. The game uses the phone's own driver until you pick another.", "Remove driver", () -> {
                        DriverZip.deleteTree(sel.dir); select(a, null);
                        Toast.makeText(a, "Removed " + sel.label() + "; the system driver is used", Toast.LENGTH_LONG).show();
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static void installDialog(Activity a, int request) {
        List<File> zips = found(a);   // names only, in the app's own folder
        List<String> labels = new ArrayList<>();
        for (File f : zips) labels.add(f.getName() + "  (the app's gpu_drivers folder)");
        labels.add("Pick a zip…");
        labels.add("Download…");   // DriverFetch: GitHub releases, only on a tap
        new PadFocus.Builder(a).setTitle("Install a driver zip (with meta.json)")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w == zips.size() + 1) { DriverFetch.dialog(a, request); return; }
                    if (w == zips.size()) {
                        a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")   // some providers label zips otherwise
                                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"}), request);
                        return;
                    }
                    File z = zips.get(w);
                    installInBackground(a, z.getName(), () -> install(a, z), null);
                }).setNegativeButton("Cancel", null).show();
    }

    /** The picker's result (MainActivity.onActivityResult) */
    static void picked(Activity a, Uri uri) {
        String last = uri.getLastPathSegment();
        installInBackground(a, last == null ? "the driver" : last.replaceAll(".*[/:]", ""), () -> install(a, uri), null);
    }
}
