package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The dynamic resolution as a slider: the lowest render size from 30 % (left) to Off (right, the native size), in Prefs.DRS's steps (index 0 Off .. the last, 20 %).
 * The in-game panel applies each step at once; Settings and Game settings pick it in a dialog (Game settings with "Default (Settings: ...)")
 */
final class DrsSlider {
    private DrsSlider() {}

    private static int pos(int index) { return Prefs.DRS.length - 1 - index; }   // slider position: 0 = the smallest size
    /** The title with the range appended: "Dynamic resolution: 40% to 100%", "Dynamic resolution: Off (native size)" */
    static String title(int index) { return "Dynamic resolution: " + Prefs.DRS_NAMES[index] + (index == 0 ? " (native size)" : " to 100%"); }

    /** A slider set to index; title(i) goes to setTitle at each step, onChange(index) at each step the user makes */
    static LinearLayout view(Context c, int index, Consumer<String> setTitle, IntConsumer onChange) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        setTitle.accept(title(index));
        SeekBar sb = new SeekBar(c);
        sb.setMax(Prefs.DRS.length - 1);
        sb.setProgress(pos(index));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) { int i = pos(v); setTitle.accept(title(i)); if (user) onChange.accept(i); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        box.addView(sb);
        return box;
    }

    /** A dialog with the slider; with dfltLabel non-null a switch of that label first ("Default (Settings: 40%)", "Recommended (50%)"; on: set.accept(null)). done after OK */
    static void dialog(Context c, String suffix, int index, String dfltLabel, boolean own, Consumer<Integer> set, Runnable done) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 20); box.setPadding(pad, pad / 2, pad, 0);
        int[] cur = {index};
        AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout slider = view(c, index, t -> { if (dlg[0] != null) dlg[0].setTitle(t + suffix); }, i -> cur[0] = i);
        CheckBox dflt = null;
        if (dfltLabel != null) {
            dflt = new CheckBox(c);
            dflt.setText(dfltLabel);
            dflt.setChecked(!own);
            slider.setAlpha(own ? 1f : 0.4f);
            dflt.setOnCheckedChangeListener((b, on) -> slider.setAlpha(on ? 0.4f : 1f));
            box.addView(dflt);
        }
        box.addView(slider);
        CheckBox d = dflt;
        dlg[0] = new PadFocus.Builder(c).setTitle(title(index) + suffix).setView(box)
                .setPositiveButton("OK", (x, w) -> { set.accept(d != null && d.isChecked() ? null : cur[0]); done.run(); })
                .setNegativeButton("Cancel", null).show();
    }
}
