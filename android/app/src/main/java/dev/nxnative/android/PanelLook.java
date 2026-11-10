package dev.nxnative.android;

/**
 * The in-game panel's size and text size (SidePanel; Prefs.panelSize / panelText, for every game): Small, Normal (the panel as it was) or Large. The size sets the
 * panel's width (dp, at most a share of the screen's width) and the least height of each row a finger or the D-pad lands on; the text size scales every line in it.
 * Pure Java: checked on the JVM (PanelLookJvmTest).
 */
final class PanelLook {
    private PanelLook() {}

    static final String[] NAMES = {"Small", "Normal", "Large"};
    static final int NORMAL = 1;
    private static final int[] WIDTH_DP = {340, 420, 520}, ROW_DP = {40, 48, 60};
    private static final float[] WIDTH_SHARE = {0.45f, 0.55f, 0.7f}, TEXT = {0.85f, 1f, 1.2f};

    /** A stored choice as an index of NAMES: anything else reads as Normal */
    static int of(int v) { return v >= 0 && v < NAMES.length ? v : NORMAL; }

    /** The panel's width in pixels on a screen widthPx wide at density dp */
    static int widthPx(int size, float dp, int screenPx) { int s = of(size); return Math.round(Math.min(WIDTH_DP[s] * dp, screenPx * WIDTH_SHARE[s])); }
    /** The least height of a row (a button, a box, a choice, a slider) in pixels */
    static int rowPx(int size, float dp) { return Math.round(ROW_DP[of(size)] * dp); }
    /** The factor every text size in the panel is multiplied by */
    static float textScale(int text) { return TEXT[of(text)]; }
}
