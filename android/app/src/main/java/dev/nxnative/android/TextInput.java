package dev.nxnative.android;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.nio.charset.StandardCharsets;

/**
 * The keyboard applet's input box (the host's nxhost_text_input_callback, through nxlaunch.c): a dialog over the game with the game's header as its title, its sub
 * text and guide text as the message and the guide as the field's hint, prefilled with the game's initial text and limited to its maximum length. OK or Enter, on
 * the soft keyboard or a physical one, confirms it, and only once the text has the game's minimum length. Cancel, Back (or B on a controller) cancels it, as the
 * console's keyboard always allows: the game decides what a cancel means (an optional box closes its menu, a box the game needs is simply shown again). A tap
 * outside does nothing, so a stray touch on the game behind never cancels. (Before the box, the host answered every request with an instant cancel: a game
 * that needs the text asked again forever.)
 * {@link #ask} runs on a host thread and blocks until then; the game's screen ({@link #screen}) shows the box, and a screen created again shows it again with the
 * text typed so far. When the screen is closed for good (the game stopped) the host gets a cancel, so it never waits for a box nobody can see.
 */
final class TextInput {
    private TextInput() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final class Request {
        final String title, sub, guide, initial;
        final int max, min;
        String typed;          // the text so far, kept when the screen goes and the box is shown again
        String result;         // the confirmed text; null = cancelled
        boolean done;
        AlertDialog dialog;

        Request(String title, String sub, String guide, String initial, int max, int min) {
            this.title = title; this.sub = sub; this.guide = guide; this.initial = initial; this.max = max; this.min = min;
        }
    }

    // UI thread only, except pending (also set by ask: guarded by the class)
    private static GameActivity screen;
    private static Request pending;

    private static String utf8(byte[] b) { return b == null ? "" : new String(b, StandardCharsets.UTF_8); }

    /** Called by nxlaunch.c on a thread of its own: shows the box and returns the confirmed text as UTF-8, or null for cancel */
    static byte[] ask(byte[] title, byte[] sub, byte[] guide, byte[] initial, int maxChars, int minChars) {
        Request r = new Request(utf8(title), utf8(sub), utf8(guide), utf8(initial), Math.max(0, maxChars), Math.max(0, minChars));
        if (r.max > 0 && r.min > r.max) Native.note("[RED] keyboard: minimum length " + r.min + " above the maximum " + r.max + ": the maximum is used as the minimum");
        synchronized (TextInput.class) { pending = r; }
        UI.post(TextInput::show);
        synchronized (r) {
            while (!r.done) {
                try { r.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        synchronized (TextInput.class) { if (pending == r) pending = null; }
        return r.result == null ? null : r.result.getBytes(StandardCharsets.UTF_8);
    }

    private static void finish(Request r, String result) {   // UI thread
        if (r.dialog != null) { r.dialog.setOnDismissListener(null); r.dialog.dismiss(); r.dialog = null; }
        synchronized (TextInput.class) { if (pending == r) pending = null; }
        synchronized (r) { r.result = result; r.done = true; r.notifyAll(); }
    }

    /** The game's screen is up (onCreate): a box the game is waiting on shows on it */
    static void screen(GameActivity a) {
        screen = a;
        show();
    }

    /** The game's screen goes (onDestroy). Recreated (a configuration change, or Android's "Don't keep activities"): the box comes back on the next screen with its
     *  text. Closed for good (the game stopped): the host gets a cancel */
    static void gone(GameActivity a) {
        if (screen != a) return;
        screen = null;
        Request r;
        synchronized (TextInput.class) { r = pending; }
        if (r == null) return;
        if (r.dialog != null) {
            r.typed = ((EditText) r.dialog.findViewById(android.R.id.edit)).getText().toString();
            r.dialog.setOnDismissListener(null);
            r.dialog.dismiss();
            r.dialog = null;
        }
        if (a.isFinishing()) {
            Native.note("[RED] keyboard: the game's screen closed while its input box was up: the game gets a cancel");
            finish(r, null);
        }
    }

    private static int chars(CharSequence s) { return s.length(); }   // UTF-16 units, as the applet counts (the host cuts the text to the maximum in the same units)

    private static void show() {   // UI thread
        Request r;
        synchronized (TextInput.class) { r = pending; }
        GameActivity a = screen;
        if (r == null || r.done || r.dialog != null || a == null || a.isFinishing() || a.isDestroyed()) return;
        int min = r.max > 0 ? Math.min(r.min, r.max) : r.min;

        EditText field = new EditText(a);
        field.setId(android.R.id.edit);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Done on the soft keyboard; no full-screen extract editor in landscape, so the box, its title and the game stay in view
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        if (r.max > 0) field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(r.max)});
        String text = r.typed != null ? r.typed : r.initial;
        if (r.max > 0 && text.length() > r.max) text = text.substring(0, r.max);
        field.setText(text);
        field.setSelection(field.getText().length());
        if (!r.guide.isEmpty()) field.setHint(r.guide);
        float dp = a.getResources().getDisplayMetrics().density;
        FrameLayout box = new FrameLayout(a);
        box.setPadding(Math.round(20 * dp), Math.round(8 * dp), Math.round(20 * dp), 0);
        box.addView(field);

        StringBuilder msg = new StringBuilder(r.sub);
        if (!r.guide.isEmpty()) msg.append(msg.length() > 0 ? "\n" : "").append(r.guide);
        if (min > 0) msg.append(msg.length() > 0 ? "\n" : "").append("At least ").append(min).append(min == 1 ? " character." : " characters.");
        AlertDialog.Builder b = new PadFocus.Builder(a).setTitle(r.title.isEmpty() ? "Enter text" : r.title).setView(box)
                .setPositiveButton("OK", null).setNegativeButton("Cancel", (x, w) -> finish(r, null)).setCancelable(true);
        if (msg.length() > 0) b.setMessage(msg);
        AlertDialog d = b.create();
        d.setCanceledOnTouchOutside(false);
        d.setOnCancelListener(x -> finish(r, null));   // Back
        Runnable confirm = () -> { if (chars(field.getText()) >= min) finish(r, field.getText().toString()); };
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> confirm.run());
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(chars(field.getText()) >= min);
            field.requestFocus();
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) {}
            @Override public void onTextChanged(CharSequence s, int st, int c, int n) {}
            @Override public void afterTextChanged(Editable s) {
                if (d.getButton(AlertDialog.BUTTON_POSITIVE) != null) d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(chars(s) >= min);
            }
        });
        // Done on the soft keyboard, Enter on a physical one (IME_NULL with its key-down; the key-up is taken too, so it never reaches anything else)
        field.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE || e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (e == null || e.getAction() == KeyEvent.ACTION_DOWN) confirm.run();
                return true;
            }
            return false;
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        r.dialog = d;
        d.show();
    }
}
