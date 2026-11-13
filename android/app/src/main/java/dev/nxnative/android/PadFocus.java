package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.EditText;
import android.widget.ListView;

import java.util.ArrayList;

/**
 * A controller in the launcher, in the style of the in-game panel (SidePanel): the D-pad moves the View focus (Android's own focus search, so the tile grid moves in
 * two dimensions), A presses what has the focus (Android's confirm), B goes back one level (a dialog cancels, a page returns to the list: MainActivity.dispatchKeyEvent,
 * the dialogs through {@link Builder}), L1 / R1 switch a game page's tabs. The focus is drawn as a frame in the app's accent colour (not touch mode, where nothing has the
 * focus): {@link #watch} for a window, the list of a choice dialog through {@link Builder}. Touch is unchanged.
 */
final class PadFocus {
    private PadFocus() {}

    /** The focus frame: a two-tone ring (white outside, dark inside) over a faint accent fill, rounded. Two tones so it shows on anything, the yellow primary buttons
     *  and the dark and cream pages alike: a single accent ring vanished on a button of the same yellow. The same look in a row, a tile, a tab, a button and a dialog's list */
    static Drawable frame(Context c) {
        GradientDrawable outer = new GradientDrawable();
        outer.setColor((c.getColor(R.color.bn_accent) & 0x00FFFFFF) | 0x26000000);
        outer.setStroke(Ui.dp(c, 3), 0xFFFFFFFF);
        outer.setCornerRadius(Ui.dp(c, 14));
        GradientDrawable inner = new GradientDrawable();
        inner.setStroke(Ui.dp(c, 2), 0xFF1B1208);
        inner.setCornerRadius(Ui.dp(c, 12));
        android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(new Drawable[] {outer, inner});
        d.setLayerInset(1, Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
        return d;
    }

    /** Whether a key is the controller's back button */
    static boolean isBack(int keyCode) { return keyCode == KeyEvent.KEYCODE_BUTTON_B; }

    /** The step of a tab switch for a shoulder button: -1 (L1), +1 (R1), else 0 */
    static int tabStep(int keyCode) { return keyCode == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : keyCode == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : 0; }

    /** Puts the frame on whatever has the focus in this window, and takes it off the one that lost it */
    static void watch(Window w) {
        final View[] lit = new View[1];
        final Drawable[] old = new Drawable[1];
        w.getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (lit[0] != null) { lit[0].setForeground(old[0]); lit[0] = null; old[0] = null; }
            if (now == null || now.isInTouchMode() || now instanceof EditText || now instanceof ListView) return;
            lit[0] = now;
            old[0] = now.getForeground();
            now.setForeground(frame(now.getContext()));
        });
    }

    /** Every focusable view under root, in focus order */
    static ArrayList<View> focusables(View root) { return root.getFocusables(View.FOCUS_FORWARD); }

    /** The view a page opens its focus on: the selected one (a tab), else the first */
    static View first(ArrayList<View> l) {
        for (View v : l) if (v.isSelected()) return v;
        return l.isEmpty() ? null : l.get(0);
    }

    /** An AlertDialog builder whose dialogs work with a controller: B cancels, the focus frame shows, a choice list shows its selection */
    static class Builder extends AlertDialog.Builder {
        Builder(Context c) { super(c); }
        Builder(Context c, int theme) { super(c, theme); }

        @Override public AlertDialog create() {
            AlertDialog d = super.create();
            d.setOnKeyListener((di, k, e) -> {
                if (!isBack(k)) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) di.cancel();
                return true;
            });
            return d;
        }

        @Override public AlertDialog show() {
            AlertDialog d = super.show();
            Window w = d.getWindow();
            if (w != null) watch(w);
            ListView l = d.getListView();
            if (l != null) l.setSelector(frame(d.getContext()));
            View f = d.getCurrentFocus();
            if (f != null && !f.isInTouchMode() && w != null) f.requestFocus();   // the frame shows on what the dialog focused first
            return d;
        }
    }
}
