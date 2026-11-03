package dev.nxnative.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Lossless Scaling frame generation (LSFG): the user's own Lossless.dll and the settings. The DLL is never part of the APK: the launcher looks for it in a few known places on
 * the phone's storage (it has All files access), takes the first it finds, and keeps a copy in the app's private files; the user can also pick it by hand. The host reads
 * the copy (DM_LSFG_DLL) and the settings (DM_LSFG*), and takes changes made while the game runs through Native.frameGenSet.
 */
final class Lsfg {
    private Lsfg() {}

    /** The places looked in, in order; then the app's own folders */
    static final String[] KNOWN = {"/storage/emulated/0/Documents/Emulation/bios/Lossless.dll", "/storage/emulated/0/Switch2/Lossless.dll", "/storage/emulated/0/Download/Lossless.dll"};
    static final int[] MULTS = {2, 3, 4}, TARGETS = {0, 60, 90, 120}, FLOWS = {0, 25, 50, 75, 100};

    static File privateCopy(Context c) { return new File(c.getFilesDir(), "lsfg/Lossless.dll"); }

    private static SharedPreferences prefs(Context c) { return Prefs.of(c); }
    static boolean on(Context c) { return prefs(c).getBoolean("lsfg", false); }
    static int multiplier(Context c) { return prefs(c).getInt("lsfg_mult", 2); }
    static int target(Context c) { return prefs(c).getInt("lsfg_target", 0); }
    static int flow(Context c) { return prefs(c).getInt("lsfg_flow", 75); }

    /** Where the DLL in use came from (for the main screen and the log) */
    static String note = "";

    private static boolean looksLikeDll(File f) {
        if (!f.isFile() || f.length() < 100_000) return false;
        try (InputStream in = new FileInputStream(f)) { return in.read() == 'M' && in.read() == 'Z'; } catch (IOException | SecurityException e) { return false; }
    }

    private static void copy(InputStream in, File to) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(to.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        if (!tmp.renameTo(to)) throw new IOException("cannot replace " + to);
    }

    /** The DLL to use: the first known place that has one (copied into private files when the copy differs from it), else an earlier copy. Null = none. */
    static File find(Context c) {
        File copy = privateCopy(c);
        File ext = c.getExternalFilesDir(null);
        String[] where = ext != null ? new String[KNOWN.length + 1] : KNOWN;
        if (ext != null) { System.arraycopy(KNOWN, 0, where, 0, KNOWN.length); where[KNOWN.length] = new File(ext, "Lossless.dll").getPath(); }
        for (String p : where) {
            File f = new File(p);
            if (!looksLikeDll(f)) continue;
            try {
                if (!copy.isFile() || copy.length() != f.length() || copy.lastModified() != f.lastModified()) {
                    try (InputStream in = new FileInputStream(f)) { copy(in, copy); }
                    copy.setLastModified(f.lastModified());
                }
                note = "Lossless.dll from " + p;
                return copy;
            } catch (IOException | SecurityException e) {
                note = "Lossless.dll at " + p + " could not be copied: " + e.getMessage();
            }
        }
        if (looksLikeDll(copy)) { note = "Lossless.dll: the copy picked earlier"; return copy; }
        if (note.isEmpty() || !note.contains("could not")) note = "Lossless.dll not found (looked in Documents/Emulation/bios, Switch2, Download and the app's files)";
        return null;
    }

    /** The manual pick (the fallback when it is in none of the known places): copied into private files. Null = done, else the error. */
    static String install(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return "cannot open " + uri;
            copy(in, privateCopy(c));
        } catch (IOException | SecurityException e) { return e.getMessage(); }
        if (!looksLikeDll(privateCopy(c))) { privateCopy(c).delete(); return "that file is not a Windows DLL"; }
        note = "Lossless.dll: picked by hand";
        return null;
    }

    /** The host's switches for a run (env.txt lines come after these and win) */
    static void env(Context c, List<String> env) {
        File dll = find(c);
        if (dll != null) env.add("DM_LSFG_DLL=" + dll.getPath());
        env.add("DM_LSFG=" + (on(c) ? 1 : 0));
        env.add("DM_LSFG_MULT=" + multiplier(c));
        env.add("DM_LSFG_TARGET=" + target(c));
        env.add("DM_LSFG_FLOW=" + flow(c));
    }

    private static RadioGroup choice(Context c, int[] values, int current, String[] labels, java.util.function.IntConsumer pick) {
        RadioGroup g = new RadioGroup(c);
        g.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            RadioButton b = new RadioButton(c);
            b.setText(labels[i]);
            b.setId(1000 + i);
            g.addView(b);
            if (values[i] == current) b.setChecked(true);
        }
        g.setOnCheckedChangeListener((gr, id) -> pick.accept(values[id - 1000]));
        return g;
    }

    /** The settings rows (main screen dialog and the in-game panel); apply runs after each change (in game: Native.frameGenSet). Returns the status line (null without LSFG in this build) */
    static TextView rows(Context c, LinearLayout box, String status, Runnable apply) {
        TextView head = new TextView(c);
        head.setText("Frame generation (Lossless Scaling, your own Lossless.dll)");
        head.setPadding(0, 24, 0, 0);
        box.addView(head);
        if (!BuildConfig.NX_LSFG) { TextView t = new TextView(c); t.setText("Not in this build (built with -PnxLsfg=false)."); box.addView(t); return null; }
        CheckBox on = new CheckBox(c);
        on.setText("Frame generation (LSFG)");
        on.setChecked(on(c));
        on.setOnCheckedChangeListener((b, v) -> { prefs(c).edit().putBoolean("lsfg", v).commit(); apply.run(); });
        box.addView(on);
        TextView st = new TextView(c);
        st.setText(status);
        st.setTextSize(12);
        box.addView(st);
        TextView l1 = new TextView(c); l1.setText("Frames per game frame"); box.addView(l1);
        box.addView(choice(c, MULTS, multiplier(c), new String[]{"2x", "3x", "4x"}, v -> { prefs(c).edit().putInt("lsfg_mult", v).commit(); apply.run(); }));
        TextView l2 = new TextView(c); l2.setText("Target frame rate (the multiplier follows it)"); box.addView(l2);
        box.addView(choice(c, TARGETS, target(c), new String[]{"Fixed", "60", "90", "120"}, v -> { prefs(c).edit().putInt("lsfg_target", v).commit(); apply.run(); }));
        TextView l3 = new TextView(c); l3.setText("Motion estimation resolution (lower is faster; Auto = the game's own)"); box.addView(l3);
        box.addView(choice(c, FLOWS, flow(c), new String[]{"Auto", "25%", "50%", "75%", "100%"}, v -> { prefs(c).edit().putInt("lsfg_flow", v).commit(); apply.run(); }));
        TextView mode = new TextView(c);   // Eden ports only the performance shader set (lossless_dll.h PerformanceShader); the quality set would need its own chain
        mode.setText("Mode: performance (the only shader set Eden's LSFG uses)");
        mode.setTextSize(12);
        box.addView(mode);
        return st;
    }

    static void push(Context c) { Native.frameGenSet(on(c), multiplier(c), target(c), flow(c)); }
}
