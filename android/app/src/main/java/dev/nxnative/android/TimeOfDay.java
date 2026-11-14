package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

/**
 * The game's clock (Prefs.timeOverride → DM_TIME): Settings › System › Time of day for every game, and the same row on the Game tab of a game whose
 * profile has time_of_day 1 (a day/night cycle that follows the user clock). One setting; the next game start hands the host the chosen time, which
 * the game's user clock then runs on from. The phone's own clock is never changed.
 */
final class TimeOfDay {
    private TimeOfDay() {}

    /** The row's value: the chosen time, or the phone's clock */
    static String label(Context c) {
        String t = Prefs.timeOverride(c);
        return t.isEmpty() ? "The phone's clock" : t.replace('T', ' ');
    }

    /** The choice dialog (a date and time, or the phone's clock); done runs after a change so the caller redraws its row */
    static void dialog(Activity a, Runnable done) {
        String[] items = {"Pick a date and time…", "The phone's clock"};
        new AlertDialog.Builder(a).setTitle("Time of day")
                .setItems(items, (d, w) -> { if (w == 0) pickDate(a, done); else { Prefs.timeOverride(a, null); done.run(); } })
                .setNegativeButton("Cancel", null).show();
    }

    /** The system date picker, then the system time picker; the result goes through valid() before it is stored (DM_TIME's format) */
    private static void pickDate(Activity a, Runnable done) {
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        String cur = Prefs.timeOverride(a);
        java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
        if (!cur.isEmpty()) { try { cal.setTime(in.parse(cur.length() == 16 ? cur + ":00" : cur)); } catch (java.text.ParseException ignored) { } }
        new android.app.DatePickerDialog(a, (dp, y, m, d) -> {
            cal.set(y, m, d);
            new android.app.TimePickerDialog(a, (tv, h, min) -> {
                cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, min); cal.set(java.util.Calendar.SECOND, 0);
                String v = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.ROOT).format(cal.getTime());
                if (!valid(v)) { Toast.makeText(a, "Not a valid date and time: " + v, Toast.LENGTH_LONG).show(); return; }
                Prefs.timeOverride(a, v); done.run();
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show();
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    /** The host's DM_TIME: YYYY-MM-DDTHH:MM[:SS], and a date that exists (2026-02-30 is refused) */
    static boolean valid(String v) {
        if (v == null || !v.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?")) return false;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
        f.setLenient(false);
        try { f.parse(v.length() == 16 ? v + ":00" : v); return true; } catch (java.text.ParseException e) { return false; }
    }
}
