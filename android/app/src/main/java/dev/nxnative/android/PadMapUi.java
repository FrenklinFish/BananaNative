package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * The Button mapping editor (PadMap): a row per Switch button with the controller button that presses it; a tap waits for a press on the controller ("press a
 * button") and puts that button there, swapping with the one it replaces. Save stores the map (DM_PAD_MAP for the next start), Reset to default drops it.
 * Settings has the default for every game; a game's own (Game settings) wins. The on-screen controls are not changed.
 */
final class PadMapUi {
    private PadMapUi() {}

    /** Settings › Button mapping: the default of every game */
    static void forDefault(Activity a, Runnable done) {
        edit(a, "Button mapping", "For every game without its own mapping, from its next start.", Prefs.padMap(a), Prefs.xboxButtons(a) == 2 ? 2 : 0,
                spec -> { Prefs.padMapDefault(a, spec); done.run(); }, "Reset to default", () -> { Prefs.padMapDefault(a, ""); done.run(); });
    }

    /** Game settings › Button mapping: this game's own; Reset follows Settings again */
    static void forGame(Activity a, Game g, Runnable done) {
        String id = g.titleId;
        edit(a, "Button mapping · " + g.name, "For " + g.name + " only, from its next start. Reset: back to Settings' mapping.", Prefs.padMap(a, id),
                Prefs.xboxButtons(a, id) == 2 ? 2 : 0, spec -> { Prefs.padMap(a, id, spec); done.run(); }, "Reset to default", () -> { Prefs.padMap(a, id, null); done.run(); });
    }

    /** The value a settings row shows */
    static String label(Activity a, String titleId) {
        if (titleId == null) return PadMap.summary(Prefs.padMap(a));
        return PadMap.gameLabel(Prefs.ownPadMap(a, titleId), Prefs.padMap(a, titleId));
    }

    private static void edit(Activity a, String title, String note, String spec, int labelled, Consumer<String> save, String resetName, Runnable reset) {
        PadMap m = PadMap.parse(spec, labelled);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.small(a, note + " Tap a Switch button, then press the controller button for it."));
        Ui.Setting[] rows = new Ui.Setting[PadMap.SWITCH.length];
        Runnable refresh = () -> { for (int sw : PadMap.EDIT_ORDER) rows[sw].value("Controller: " + m.pressedBy(sw)); };
        for (int sw : PadMap.EDIT_ORDER) {
            rows[sw] = new Ui.Setting(a, "🎮", "Switch " + PadMap.SWITCH_NAMES[sw], v -> capture(a, sw, src -> { m.assign(src, sw); refresh.run(); })).chevron(a);
            Ui.add(box, rows[sw].view, 0);
        }
        refresh.run();
        Ui.sheet(a, title, box).setPositiveButton("Save", (x, w) -> save.accept(m.edited() ? m.spec() : ""))
                .setNeutralButton(resetName, (x, w) -> reset.run()).setNegativeButton("Cancel", null).show();
    }

    /** Waits for one controller button (a key, an analog trigger past half way, or the hat) and hands its source on; Back or Cancel ends it */
    private static void capture(Activity a, int sw, java.util.function.IntConsumer picked) {
        FrameLayout f = new FrameLayout(a);
        TextView t = Ui.small(a, "Press the controller button for Switch " + PadMap.SWITCH_NAMES[sw] + ".");
        t.setTextSize(16);
        f.addView(t);
        f.setFocusable(true);
        f.setFocusableInTouchMode(true);
        AlertDialog d = Ui.sheet(a, "Switch " + PadMap.SWITCH_NAMES[sw], f).setNegativeButton("Cancel", null).create();
        boolean[] done = {false};
        java.util.function.IntConsumer take = src -> {
            if (done[0] || src < 0) return;
            done[0] = true;
            picked.accept(src);
            d.dismiss();
        };
        d.setOnKeyListener((x, code, e) -> {   // before the dialog's views: the D-pad would move the focus, B would close it
            int src = PadMap.sourceOfKey(code);
            if (src < 0) return false;   // Back, a keyboard key: the dialog's own
            if (e.getAction() == KeyEvent.ACTION_UP) take.accept(src);
            return true;
        });
        f.setOnGenericMotionListener((v, e) -> {
            if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE) return false;
            if (Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f) take.accept(PadMap.source("BUTTON_L2"));
            else if (Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f) take.accept(PadMap.source("BUTTON_R2"));
            else if (e.getAxisValue(MotionEvent.AXIS_HAT_X) < -0.5f) take.accept(PadMap.source("DPAD_LEFT"));
            else if (e.getAxisValue(MotionEvent.AXIS_HAT_X) > 0.5f) take.accept(PadMap.source("DPAD_RIGHT"));
            else if (e.getAxisValue(MotionEvent.AXIS_HAT_Y) < -0.5f) take.accept(PadMap.source("DPAD_UP"));
            else if (e.getAxisValue(MotionEvent.AXIS_HAT_Y) > 0.5f) take.accept(PadMap.source("DPAD_DOWN"));
            return true;
        });
        d.setOnShowListener(x -> f.requestFocus());
        d.show();
    }
}
