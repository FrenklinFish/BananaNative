package dev.nxnative.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.widget.Toast;

import java.util.Collections;
import java.util.Locale;

/**
 * Home screen shortcuts that start one game. A shortcut opens {@link FrontendLaunch} with the game's title ID, the path emulator frontends take (FRONTENDS.md):
 * a game that is ready starts at once, anything that needs the user (no pack, shaders to make, All files access) goes to Home with the game. The shortcut
 * holds only the title ID, so it follows the game's card when the game or pack is moved; if the card is gone the launch says so.
 * <p>
 * The icon is the card's tile (Ui.tile): the name's first letter on banana yellow, drawn as an adaptive icon so the launcher's shape applies.
 */
final class Shortcuts {
    private Shortcuts() { }

    static String id(Game g) { return "game-" + g.titleId.toUpperCase(Locale.ROOT); }

    /** The intent a shortcut for the game runs (a pinned shortcut needs an action) */
    static Intent intent(Context c, Game g) {
        return new Intent(Intent.ACTION_MAIN).setClassName(c, FrontendLaunch.class.getName()).putExtra("title_id", g.titleId.toUpperCase(Locale.ROOT));
    }

    static boolean supported(Context c) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        return sm != null && sm.isRequestPinShortcutSupported();
    }

    /** Asks the launcher to pin the game's shortcut (the launcher shows its own confirmation); a shortcut already pinned is updated in place */
    static void pin(Context c, Game g) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(c, "This launcher cannot pin shortcuts.", Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutInfo info = new ShortcutInfo.Builder(c, id(g))
                .setShortLabel(g.name.length() > 25 ? g.name.substring(0, 24) + "…" : g.name)
                .setLongLabel(g.name)
                .setIcon(icon(c, g.name))
                .setIntent(intent(c, g))
                .build();
        try {
            if (!sm.requestPinShortcut(info, null)) Toast.makeText(c, "The launcher did not accept the shortcut.", Toast.LENGTH_LONG).show();
            else sm.updateShortcuts(Collections.singletonList(info));   // already pinned: a renamed game's label follows
        } catch (RuntimeException e) {
            android.util.Log.w("nxnative", "Shortcuts: pin " + g.titleId + " failed: " + e);
            Toast.makeText(c, "Could not add the shortcut: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** The card's tile as an adaptive icon: the letter inside the 66 dp circle every launcher shape keeps, on a full-bleed 108 dp yellow square */
    static Icon icon(Context c, String name) {
        int size = 432;   // 108 dp at 4x
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(c.getColor(R.color.bn_banana));
        String n = name == null ? "" : name.trim();
        String letter = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(c.getColor(R.color.bn_on_banana));
        p.setTypeface(Typeface.create(Typeface.DEFAULT, 800, false));
        p.setTextSize(size * 0.36f);
        p.setTextAlign(Paint.Align.CENTER);
        Rect r = new Rect();
        p.getTextBounds(letter, 0, letter.length(), r);
        cv.drawText(letter, size / 2f, size / 2f - r.exactCenterY(), p);
        return Icon.createWithAdaptiveBitmap(b);
    }
}
