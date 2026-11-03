package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The in-game panel: slides in from the right over the running game, which goes on behind it. It holds the frame rate cap (Native.fpsCapSet), frame generation
 * (Lsfg.rows, Native.frameGenSet), the overlay's items (Overlay.rows), the on-screen controls' rows (TouchControls.controlRows) and Stop. Every change applies at once,
 * without going back to the launcher, and is kept for the next start. Opened by Back (the phone's button or its edge swipe), a long press on the on-screen controls'
 * show / hide button, or a gamepad's Home / guide button (KEYCODE_BUTTON_MODE); closed the same ways, by a tap beside it, or by B on a gamepad, whose D-pad and A move
 * through it meanwhile (GameActivity.dispatchKeyEvent).
 */
final class SidePanel extends FrameLayout {
    private final Activity act;
    private final LinearLayout box;
    private final View sheet;
    private final java.util.function.Consumer<LinearLayout> fill;
    private TextView fpsNow, lsfgNow;
    private RadioGroup caps;
    private boolean open;
    private final Runnable tick = this::refresh;

    SidePanel(Activity a, java.util.function.Consumer<LinearLayout> fill) {
        super(a);
        act = a;
        this.fill = fill;
        float dp = getResources().getDisplayMetrics().density;
        View scrim = new View(a);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(16 * dp);
        box.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(0xF0202124);
        sv.addView(box);
        int w = Math.round(Math.min(420 * dp, a.getResources().getDisplayMetrics().widthPixels * 0.55f));
        addView(sv, new LayoutParams(w, LayoutParams.MATCH_PARENT, Gravity.END));
        sheet = sv;
        setVisibility(GONE);
    }

    boolean isOpen() { return open; }
    void toggle() { if (open) close(); else open(); }

    void open() {
        if (open) return;
        open = true;
        box.removeAllViews();
        build();
        setVisibility(VISIBLE);
        sheet.setTranslationX(sheet.getLayoutParams().width);
        sheet.animate().translationX(0).setDuration(180).start();
        box.post(() -> { View f = box.focusSearch(View.FOCUS_DOWN); if (f != null) f.requestFocus(); else box.requestFocus(); });
        removeCallbacks(tick);
        refresh();
    }

    void close() {
        if (!open) return;
        open = false;
        sheet.animate().translationX(sheet.getLayoutParams().width).setDuration(150).withEndAction(() -> { if (!open) setVisibility(GONE); }).start();
    }

    /** A gamepad key while the panel is open: B closes it, A presses what has focus, the D-pad moves the focus (the view system's own handling) */
    boolean padKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BUTTON_B) { if (e.getAction() == KeyEvent.ACTION_UP) close(); return true; }
        if (k == KeyEvent.KEYCODE_BUTTON_A) {
            View f = findFocus();
            if (e.getAction() == KeyEvent.ACTION_UP && f != null) f.performClick();
            return true;
        }
        return false;
    }

    private TextView head(String t) {
        TextView h = new TextView(act);
        h.setText(t);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(15);
        h.setPadding(0, Math.round(18 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(h);
        return h;
    }

    private TextView note(String t) {
        TextView n = new TextView(act);
        n.setText(t);
        n.setTextSize(12);
        n.setTextColor(Color.LTGRAY);
        box.addView(n);
        return n;
    }

    /** The running game's title ID (GameActivity's intent): the panel's settings are that game's own */
    private String game() { return String.valueOf(act.getIntent().getStringExtra("title_id")); }

    private void build() {
        TextView title = new TextView(act);
        title.setText("Banana Native");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(20);
        box.addView(title);
        note("The game runs on behind this panel; every change applies now and is kept.");

        head("Frame rate cap");   // the game's own frame rate; frame generation (below, its own heading) adds frames on top
        caps = new RadioGroup(act);
        int live = Integer.parseInt(Native.fpsCapStatus().split(" ")[0]), cur = live >= 0 ? live : Prefs.fpsCap(act, game());   // the cap in effect (env.txt or DM_CTL may have set another)
        for (int i = 0; i < Prefs.FPS_CAPS.length; i++) {
            int v = Prefs.FPS_CAPS[i];
            RadioButton b = new RadioButton(act);
            b.setId(2000 + i);
            b.setText(v == 0 ? "The game's own" : v + " fps");
            if (v == cur || v == 0 && cur < 0) b.setChecked(true);   // a cap the launcher does not offer (40, 45 from env.txt or DM_CTL): none checked, the line below says it
            caps.addView(b);
        }
        caps.setOnCheckedChangeListener((g, id) -> {
            int v = Prefs.FPS_CAPS[id - 2000];
            Prefs.fpsCap(act, game(), v);   // the running game's own cap from now on (the main screen's stays the default of the others)
            Native.fpsCapSet(v);
            refresh();
        });
        box.addView(caps);
        fpsNow = note("");

        lsfgNow = Lsfg.rows(act, box, "", () -> { Lsfg.push(act); refresh(); });

        head("Overlay");
        Overlay.rows(act, box, null);

        head("On-screen controls");
        fill.accept(box);   // GameActivity: TouchControls.controlRows

        Button pause = new Button(act);   // the game suspended (clock, graphics, threads, audio) until a tap on the screen, or A / Start: GameActivity.pause
        pause.setText("Pause the game");
        pause.setOnClickListener(v -> { close(); if (act instanceof GameActivity) ((GameActivity) act).pause(); });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = Math.round(24 * getResources().getDisplayMetrics().density);
        box.addView(pause, pl);
        android.widget.CheckBox ret = new android.widget.CheckBox(act);
        ret.setText("Pause when the game comes back from the background");
        ret.setChecked(Prefs.pauseOnReturn(act, game()));
        ret.setOnCheckedChangeListener((b, on) -> Prefs.pauseOnReturn(act, game(), on));
        box.addView(ret);
        Button stop = new Button(act);
        stop.setText("Stop the game");
        stop.setOnClickListener(v -> new AlertDialog.Builder(act).setTitle("Stop the game?").setMessage("The game ends; unsaved progress is lost. The log stays for Share log.")
                .setPositiveButton("Stop", (d, w) -> { Native.note("stopped by the user"); android.os.Process.killProcess(android.os.Process.myPid()); })
                .setNegativeButton("Keep running", null).show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(24 * getResources().getDisplayMetrics().density);
        box.addView(stop, lp);
        Button done = new Button(act);
        done.setText("Back to the game");
        done.setOnClickListener(v -> close());
        box.addView(done, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Twice a second while open: what the game runs at, which caps it can hold, and the frame generation's state */
    private void refresh() {
        if (!open) return;
        String[] f = Native.fpsCapStatus().split(" ");
        int cap = Integer.parseInt(f[0]), rates = Integer.parseInt(f[1]), now = Integer.parseInt(f[2]);
        for (int i = 0; i < Prefs.FPS_CAPS.length; i++) {
            int v = Prefs.FPS_CAPS[i], bit = v == 15 ? 0 : v == 20 ? 1 : v == 30 ? 2 : 5;   // the host's bits: 15, 20, 30, (40, 45,) 60
            caps.getChildAt(i).setEnabled(v == 0 || cap >= 0 && (rates & 1 << bit) != 0);
        }
        // No cap in this build, or a game that picks its own frame rate: no choices (they would do nothing), one line instead
        // (rates is 0 too until the game's main is loaded: only a game that presents, now > 0, is known to pick its own)
        boolean own = cap >= 0 && rates == 0 && now > 0, none = cap < 0 || own;
        caps.setVisibility(none ? GONE : VISIBLE);
        if (cap >= 0 && now > 0) Prefs.ownRate(act, game(), own);   // its Game settings hide the cap too
        String s = cap < 0 ? "This build has no frame rate cap."
                : now <= 0 ? "Starting…"
                : own ? "This game picks its own frame rate."
                : "Now " + now + " fps" + (cap > 0 ? ", capped at " + cap : ", the game's own") + ".";
        fpsNow.setText(s);
        if (lsfgNow != null) { String st = Native.frameGenStatus(); int sp = st.indexOf(' '); lsfgNow.setText("Now: " + (sp > 0 ? st.substring(sp + 1) : st)); }
        removeCallbacks(tick);   // a change calls refresh too: one loop, not one more per change
        postDelayed(tick, 500);
    }
}
