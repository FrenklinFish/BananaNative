package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;

/** Opt-in phone-to-PC Link Play. Applied at the next start, before the game's typed environment lines (which win). */
final class LinkPlay {
    private LinkPlay() {}
    static boolean has(String titleId) { Profile p = Profile.of(titleId); return p != null && p.linkPlay; }

    private static SharedPreferences p(Context c) { return Prefs.of(c); }
    private static String k(String key, String id) { return Prefs.gameKey("lp_" + key, id); }
    private static String get(Context c, String key, String id) { return p(c).getString(k(key, id), ""); }

    static String mode(Context c, String id) { return "visit".equals(get(c, "mode", id)) ? "visit" : "off"; }

    // Preserve the join details from earlier test builds without shipping a list of private peers.
    private static String room(Context c, String id) {
        if (p(c).contains(k("room", id))) return get(c, "room", id);
        return get(c, "code_" + get(c, "friend", id), id);
    }
    private static boolean internet(Context c, String id) {
        return p(c).getBoolean(k("internet", id), !room(c, id).isEmpty());
    }

    static List<String> env(Context c, String id) {
        if (!has(id)) return Collections.emptyList();
        return LinkPlayConfig.env("visit".equals(mode(c, id)), get(c, "addr", id), internet(c, id), room(c, id));
    }

    static String label(Context c, String id) {
        if (!"visit".equals(mode(c, id))) return "Off";
        if (!LinkPlayConfig.valid(get(c, "addr", id), internet(c, id), room(c, id))) return "Join PC host - needs setup";
        return internet(c, id) ? "Join PC host - Internet / Tailscale" : "Join PC host - same Wi-Fi";
    }

    static void dialog(MainActivity a, Game g, Runnable done) {
        String id = g.titleId;
        new PadFocus.Builder(a).setTitle("Link Play")
                .setSingleChoiceItems(new String[] {"Off", "Join PC host"}, "visit".equals(mode(a, id)) ? 1 : 0, (x, w) -> {
                    x.dismiss();
                    if (w == 0) {
                        if (p(a).edit().putString(k("mode", id), "off").commit()) done.run();
                        else Toast.makeText(a, "Could not save Link Play settings.", Toast.LENGTH_LONG).show();
                    } else visit(a, g, done);
                }).setNegativeButton("Cancel", null).show();
    }

    private static void visit(MainActivity a, Game g, Runnable done) {
        String id = g.titleId;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.small(a, "Join a world hosted in Recompia on a PC. Enter the host's IPv4 address and, for Internet / Tailscale, its room code."));
        EditText addr = new EditText(a);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addr.setSingleLine(true);
        addr.setHint("Host IPv4 address (optional :port)");
        addr.setText(get(a, "addr", id));
        Ui.add(box, addr, 8);

        CheckBox inet = new CheckBox(a);
        inet.setText("Internet / Tailscale");
        inet.setChecked(internet(a, id));
        Ui.add(box, inet, 8);
        box.addView(Ui.small(a, "Use the same Internet setting as the PC host. A room code is required when this is on."));
        EditText code = new EditText(a);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setSingleLine(true);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setHint("Host room code: XXXX-XXXX-XXXX-XXXX");
        code.setText(room(a, id));
        code.setEnabled(inet.isChecked());
        inet.setOnCheckedChangeListener((v, on) -> { code.setEnabled(on); code.setError(null); addr.setError(null); });
        Ui.add(box, code, 8);
        box.addView(Ui.small(a, "Saved for next time, including when Link Play is off. Applies at the game's next start. In the game: PC > Link Play > Local connection > Visit."));
        AlertDialog d = Ui.sheet(a, "Join PC host", box).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(ignored -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = addr.getText().toString(), rawCode = code.getText().toString();
            String error = LinkPlayConfig.addressError(address, inet.isChecked());
            if (error != null) { addr.setError(error); addr.requestFocus(); return; }
            String normalizedCode = LinkPlayConfig.room(rawCode);
            if (inet.isChecked() && normalizedCode == null) {
                code.setError("Enter the host's 16-letter/digit room code."); code.requestFocus(); return;
            }
            boolean saved = p(a).edit().putString(k("addr", id), LinkPlayConfig.address(address))
                    .putString(k("room", id), normalizedCode != null ? normalizedCode : rawCode.trim())
                    .putBoolean(k("internet", id), inet.isChecked()).putString(k("mode", id), "visit").commit();
            if (!saved) { Toast.makeText(a, "Could not save Link Play settings.", Toast.LENGTH_LONG).show(); return; }
            d.dismiss();
            done.run();
        }));
        d.show();
    }
}
