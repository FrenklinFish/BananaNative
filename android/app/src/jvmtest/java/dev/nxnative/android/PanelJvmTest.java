package dev.nxnative.android;

/**
 * PanelLook and PictureFit on the JVM (dm/tools/tests/test_android_panel.py): the panel's size presets, the picture size slider and the picture's rectangle.
 * Prints "ok" or throws; with the argument "grid", prints the rectangles in pixels for the test to compare with the present's vka_fit_rect
 */
public final class PanelJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }
    private static boolean near(float a, float b) { return Math.abs(a - b) < 1e-4f; }

    static final int[][] VIEWS = {{2400, 1080}, {1920, 1080}, {1200, 900}, {2208, 1840}, {1080, 2400}};
    static final int[] SIZES = {100, 95, 80, 50, 10, 0};
    static final float[] PLACES = {0, 0.5f, 1};

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("grid")) {
            for (int[] v : VIEWS) for (int st = 0; st < 2; st++) for (int s : SIZES) for (float p : PLACES) {
                float[] r = PictureFit.rect(v[0], v[1], p, p, st == 1, s);
                System.out.println(v[0] + " " + v[1] + " " + st + " " + s + " " + Math.round(p * 10000) + " "
                        + Math.round(r[0] * v[0]) + " " + Math.round(r[1] * v[1]) + " " + Math.round(r[2] * v[0]) + " " + Math.round(r[3] * v[1]));
            }
            return;
        }
        // The panel's presets: Normal is the panel as it was (420 dp, at most 55 % of the width), each larger one wider, taller rows and larger text
        check(PanelLook.widthPx(1, 3f, 2400) == 1260, "normal: 420 dp: " + PanelLook.widthPx(1, 3f, 2400));
        check(PanelLook.widthPx(1, 3f, 1600) == 880, "normal: 55 % of a narrow screen: " + PanelLook.widthPx(1, 3f, 1600));
        check(PanelLook.widthPx(0, 3f, 2400) < PanelLook.widthPx(1, 3f, 2400) && PanelLook.widthPx(1, 3f, 2400) < PanelLook.widthPx(2, 3f, 2400), "widths grow");
        check(PanelLook.widthPx(2, 3f, 1000) <= 700, "large: at most 70 % of the width");
        check(PanelLook.rowPx(0, 3f) < PanelLook.rowPx(1, 3f) && PanelLook.rowPx(1, 3f) < PanelLook.rowPx(2, 3f), "rows grow");
        check(PanelLook.textScale(1) == 1f && PanelLook.textScale(0) < 1f && PanelLook.textScale(2) > 1f, "text scale");
        check(PanelLook.of(-1) == 1 && PanelLook.of(7) == 1 && PanelLook.of(2) == 2, "a stored value out of range reads as Normal");
        check(PanelLook.widthPx(9, 3f, 2400) == PanelLook.widthPx(1, 3f, 2400), "an unknown size is Normal");

        // The picture size slider: 50..100 % in steps of 5
        check(PictureFit.steps() == 10, "ten steps");
        check(PictureFit.sizeOfStep(0) == 50 && PictureFit.sizeOfStep(10) == 100 && PictureFit.sizeOfStep(7) == 85, "step to size");
        check(PictureFit.sizeOfStep(-3) == 50 && PictureFit.sizeOfStep(99) == 100, "steps clamped");
        for (int k = 0; k <= PictureFit.steps(); k++) check(PictureFit.stepOfSize(PictureFit.sizeOfStep(k)) == k, "round trip " + k);
        check(PictureFit.size(0) == 100 && PictureFit.size(130) == 100 && PictureFit.size(20) == 50 && PictureFit.size(73) == 73, "size as the host reads it");

        // The rectangle: fit, stretch, a share of either, placed in the free space
        float[] r = PictureFit.rect(2400, 1080, 0.5f, 0.5f, false, 100);
        check(near(r[0], 0.1f) && near(r[1], 0) && near(r[2], 0.8f) && near(r[3], 1), "fit centred on 20:9");
        r = PictureFit.rect(2400, 1080, 0.5f, 0.5f, false, 80);
        check(near(r[2], 0.64f) && near(r[3], 0.8f) && near(r[0], 0.18f) && near(r[1], 0.1f), "80 % centred, bars around");
        r = PictureFit.rect(2400, 1080, 0.5f, 0f, false, 80);
        check(near(r[1], 0) && near(r[0], 0.18f), "80 % at the top");
        r = PictureFit.rect(1200, 900, 0.5f, 0.5f, true, 100);
        check(near(r[0], 0) && near(r[1], 0) && near(r[2], 1) && near(r[3], 1), "stretch fills");
        r = PictureFit.rect(1200, 900, 0.5f, 0.5f, true, 50);
        check(near(r[0], 0.25f) && near(r[1], 0.25f) && near(r[2], 0.5f) && near(r[3], 0.5f), "stretch at 50 %");
        r = PictureFit.rect(1200, 900, 3f, -1f, false, 100);
        check(near(r[0], 0) && near(r[1], 0), "the place clamped: " + r[0] + " " + r[1]);
        System.out.println("ok");
    }
}
