package dev.nxnative.android;

/**
 * Game settings › Aspect ratio › Screen shape: the picture takes the shape of the game window, so it fills the whole screen instead of a fixed ratio. The launcher passes
 * DM_VK_PICTURE_ASPECT=<w>:<h> of the window the game will have (the same switch the fixed shapes use: the present fits the game's 16:9 frame into that shape, the game's
 * cameras and 2D layer follow it). The window's width and height are reduced by their greatest common divisor, so the fit is exact (no bars, no crop). Read once at the
 * start: a fold or a turn afterwards leaves the picture at the old shape (fitted, with bars) until the next start. Pure Java: checked on the JVM (ScreenShapeJvmTest).
 */
final class ScreenShape {
    private ScreenShape() {}

    /** The stored Aspect ratio choice that means "the window's own shape" (Prefs.aspect) */
    static final String CHOICE = "screen";

    // Within this share of 16:9 the game's own picture is already the screen's shape: nothing is passed, the game and every code path stay as shipped
    static final double NEAR_169 = 0.04;
    // The host's cameras (oot_wide.c) follow shapes from 0.4 to 4.0 only, and a portrait phone's 9:20 (0.45) gives a Vert+ camera that shows mostly sky (cover screen,
    // 2026-11-13): the narrow end is clamped to 4:5 (bars above and below the picture on a taller window), the wide end to 3.99
    static final double MIN = 0.8, MAX = 3.99;

    /**
     * The "w:h" for a window of widthPx x heightPx, or null when it is within NEAR_169 of 16:9 (or unknown). landscapeLocked: the window will be turned to landscape
     * (a phone with the Landscape rotation setting), whichever way the display is reported at this moment.
     */
    static String ratio(int widthPx, int heightPx, boolean landscapeLocked) {
        if (widthPx <= 0 || heightPx <= 0) return null;
        int w = widthPx, h = heightPx;
        if (landscapeLocked && w < h) { w = heightPx; h = widthPx; }
        double a = (double) w / h;
        if (Math.abs(a / (16.0 / 9.0) - 1) < NEAR_169) return null;
        if (a < MIN) return "4:5";
        if (a > MAX) return "399:100";
        int g = gcd(w, h);
        return (w / g) + ":" + (h / g);
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }
}
