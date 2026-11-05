package dev.nxnative.android;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A game's own settings (its Manage sheet › Game settings…): resolution, frame rate cap, GPU driver, GPU timing and extra environment lines, stored per title ID (Prefs.gameKey,
 * GpuDriver.choice). Each starts as "Default", which follows the main screen's Settings (global); a value set here wins for this game only. The in-game panel's frame
 * rate cap is this game's too. GameActivity reads them at start.
 */
final class GameSettings {
    private GameSettings() {}

    static void dialog(MainActivity a, Game g) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        Runnable again = () -> { d[0].dismiss(); dialog(a, g); };
        String id = g.titleId;
        box.addView(Ui.small(a, "For this game only, from its next start. Default follows Settings."));
        Profile prof = Profile.of(id);
        if (prof != null && prof.hasResolution())   // only where it does something: a profile with window_fn or graphics_scale rows
            Ui.add(box, new Ui.Setting(a, "🖼️", "Game resolution", v -> resolution(a, g, again)).value(windowLabel(a, id)).chevron(a).view, 8);
        if (Prefs.ownRate(a, id)) {   // the host found no limiter it can hold for this game: choices would do nothing
            Ui.Setting fixed = new Ui.Setting(a, "⏱️", "Frame rate cap", null).value("This game picks its own frame rate");
            fixed.view.setClickable(false);
            fixed.view.setAlpha(0.6f);
            Ui.add(box, fixed.view, 0);
        } else Ui.add(box, new Ui.Setting(a, "⏱️", "Frame rate cap", v -> cap(a, g, again)).value(capLabel(a, id)).chevron(a).view, 0);
        Ui.add(box, new Ui.Setting(a, "🎮", "GPU driver", v -> driver(a, g, again)).value(driverLabel(a, id)).chevron(a).view, 0);
        Ui.add(box, new Ui.Setting(a, "⏲️", "GPU timing", v -> gpuTiming(a, g, again)).value(gpuTsLabel(a, id)).chevron(a).view, 0);
        Ui.Setting ret = new Ui.Setting(a, "⏸️", "Pause on return", null).toggle(a, Prefs.pauseOnReturn(a, id), (b, on) -> Prefs.pauseOnReturn(a, id, on));
        ret.value("Coming back from the background, the game waits paused for a tap");
        Ui.add(box, ret.view, 0);
        String env = Prefs.gameEnv(a, id);
        Ui.add(box, new Ui.Setting(a, "🧪", "Extra environment", v -> env(a, g, again))
                .value(env.isEmpty() ? "None (KEY=VALUE lines for the host, after env.txt)" : env.split("\n").length + " line(s): " + env.replace('\n', ' ')).chevron(a).view, 0);
        d[0] = Ui.sheet(a, "Game settings · " + g.name, box).setPositiveButton("Done", null).show();
    }

    static String windowLabel(MainActivity a, String id) {
        String w = Prefs.gameWindow(a, id);
        String l = Prefs.windowName(w, false);
        return Prefs.ownWindow(a, id) ? l : "Default · " + l;
    }

    static String capLabel(MainActivity a, String id) {
        String l = MainActivity.capLabel(Prefs.fpsCap(a, id));
        return Prefs.ownFpsCap(a, id) ? l : "Default · " + l;
    }

    static String driverLabel(MainActivity a, String id) {
        GpuDriver.Info i = GpuDriver.selected(a, id);
        String l = i == null ? "System (the phone's own driver)" : i.label();
        return GpuDriver.choice(a, id) != null ? l : "Default · " + l;
    }

    static final String[] GPU_TS = {"segment", "submission"};

    private static String gpuTsName(String mode) { return mode.equals("segment") ? "Per segment" : "Per submission"; }

    static String gpuTsLabel(MainActivity a, String id) {
        String m = Prefs.gpuTs(a, id);
        Profile p = Profile.of(id);
        return m != null ? gpuTsName(m) : "Game default · " + gpuTsName(p == null ? "submission" : p.gpuTs);
    }

    /**
     * GPU timing: what the game's GPU timestamps measure (DM_NVN_GPU_TS; game.tsv gpu_timestamps is the default). Per segment: each timestamp gets the GPU time of
     * its place in the submission, so a game's dynamic resolution and frame pacing see the real GPU time; per submission: every timestamp of a submission gets
     * its completion time (the older behaviour)
     */
    private static void gpuTiming(MainActivity a, Game g, Runnable done) {
        Profile p = Profile.of(g.titleId);
        String[] labels = {"Game default (" + gpuTsName(p == null ? "submission" : p.gpuTs) + ")", "Per segment", "Per submission"};
        String m = Prefs.gpuTs(a, g.titleId);
        int checked = m == null ? 0 : 1 + Math.max(0, Arrays.asList(GPU_TS).indexOf(m));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        box.addView(Ui.small(a, "Per segment: the game's timestamps see the real GPU time, so its dynamic resolution can adapt. Per submission: one time for the whole "
                + "submission (the older behaviour). A DM_NVN_GPU_TS line in env.txt wins."));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k < labels.length; k++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setText(labels[k]);
            rb.setId(k + 1);
            rg.addView(rb);
        }
        rg.check(checked + 1);
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.gpuTs(a, g.titleId, w <= 1 ? null : GPU_TS[w - 2]);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, "GPU timing · " + g.name, box).setNegativeButton("Cancel", null).show();
    }

    private static void resolution(MainActivity a, Game g, Runnable done) {
        String[] labels = new String[Prefs.WINDOWS.length + 1];
        labels[0] = "Default (" + Prefs.windowName(Prefs.defaultWindow(a, g.titleId), false) + ")";
        System.arraycopy(Prefs.WINDOW_NAMES, 0, labels, 1, Prefs.WINDOW_NAMES.length);
        int checked = Prefs.ownWindow(a, g.titleId) ? 1 + Math.max(0, Arrays.asList(Prefs.WINDOWS).indexOf(Prefs.gameWindow(a, g.titleId))) : 0;
        new AlertDialog.Builder(a).setTitle("Game resolution · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.gameWindow(a, g.titleId, w == 0 ? null : Prefs.WINDOWS[w - 1]);
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    private static void cap(MainActivity a, Game g, Runnable done) {
        int n = Prefs.FPS_CAPS.length;
        String[] labels = new String[n + 2];
        labels[0] = "Default (Settings: " + MainActivity.capLabel(Prefs.fpsCap(a)) + ")";
        labels[1] = MainActivity.capLabel(-1);
        int checked = 0;
        boolean own = Prefs.ownFpsCap(a, g.titleId);
        int cur = Prefs.fpsCap(a, g.titleId);
        if (own && cur < 0) checked = 1;
        for (int k = 0; k < n; k++) {
            labels[k + 2] = MainActivity.capLabel(Prefs.FPS_CAPS[k]);
            if (own && Prefs.FPS_CAPS[k] == cur) checked = k + 2;
        }
        new AlertDialog.Builder(a).setTitle("Frame rate cap · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.fpsCap(a, g.titleId, w == 0 ? null : w == 1 ? -1 : Prefs.FPS_CAPS[w - 2]);
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    private static void driver(MainActivity a, Game g, Runnable done) {
        List<GpuDriver.Info> inst = GpuDriver.installed(a);
        GpuDriver.Info def = GpuDriver.selected(a);
        List<String> labels = new ArrayList<>();
        labels.add("Default (the main screen's: " + (def == null ? "System" : def.label()) + ")");
        labels.add("System (the phone's own driver)");
        String c = GpuDriver.choice(a, g.titleId);
        int checked = c == null ? 0 : c.equals(GpuDriver.SYSTEM) ? 1 : 0;
        for (int k = 0; k < inst.size(); k++) {
            labels.add(inst.get(k).label());
            if (c != null && inst.get(k).dir.getName().equals(c)) checked = k + 2;
        }
        new AlertDialog.Builder(a).setTitle("GPU driver · " + g.name).setSingleChoiceItems(labels.toArray(new String[0]), checked, (x, w) -> {
            GpuDriver.choice(a, g.titleId, w == 0 ? null : w == 1 ? GpuDriver.SYSTEM : inst.get(w - 2).dir.getName());
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    private static void env(MainActivity a, Game g, Runnable done) {
        EditText t = new EditText(a);
        t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        t.setMinLines(4);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(13);
        t.setHint("DM_WATCH_STALL=25");
        t.setText(Prefs.gameEnv(a, g.titleId));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.small(a, "KEY=VALUE lines for the host, one a line (# starts a comment). They come after env.txt and win over it, for " + g.name + " only."));
        Ui.add(box, t, 8);
        Ui.sheet(a, "Extra environment · " + g.name, box).setPositiveButton("Save", (x, w) -> { Prefs.gameEnv(a, g.titleId, t.getText().toString()); done.run(); })
                .setNeutralButton("Clear", (x, w) -> { Prefs.gameEnv(a, g.titleId, null); done.run(); }).setNegativeButton("Cancel", null).show();
    }
}
