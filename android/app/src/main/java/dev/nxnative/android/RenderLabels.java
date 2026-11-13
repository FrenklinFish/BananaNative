package dev.nxnative.android;

import android.content.Context;

/**
 * The labels of the Resolution, Upscaling quality and Graphics quality rows (Game settings, the in-game panel), from the app's string resources (res/values/strings.xml
 * render_*), so they change in one place; Profile builds each choice's label from them (renderOutLabel, renderPctLabel)
 */
final class RenderLabels {
    final String resTitle, upscaleTitle, gfxTitle, height, original, heavy, restartNote;
    final String[] upscale, gfx;

    RenderLabels(Context c) {
        resTitle = c.getString(R.string.render_res_title);
        upscaleTitle = c.getString(R.string.render_upscale_title);
        gfxTitle = c.getString(R.string.render_gfx_title);
        height = c.getString(R.string.render_res_height);
        original = c.getString(R.string.render_res_original);
        heavy = c.getString(R.string.render_heavy);
        restartNote = c.getString(R.string.render_restart_note);
        upscale = c.getResources().getStringArray(R.array.render_upscale);
        gfx = c.getResources().getStringArray(R.array.render_gfx);
    }

    String out(Profile p, String out) { return p.renderOutLabel(out, height, original, heavy); }
    String pct(int pct) { return Profile.renderPctLabel(pct, upscale, heavy); }
}
