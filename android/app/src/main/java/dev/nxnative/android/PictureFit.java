package dev.nxnative.android;

/**
 * Where the game's picture sits in the game view, normalized: the launcher's twin of the present's vka_fit_rect (dm/gfx/vk_android/vka_present_fit.h), so the
 * touch mapping (TouchControls, Native.touchLayout) and the drawn picture agree. Fit keeps the game's 16:9 at the largest size that fits; Stretch fills the view
 * (DM_VK_STRETCH); Picture size (DM_VK_PICTURE_SIZE, SIZE_MIN..100 %) shrinks either about its own aspect; the result sits at (px, py) of the free space
 * (0 left / top, 0.5 centred, 1 right / bottom; nxhost_picture_place). Pure Java: checked on the JVM (PictureFitJvmTest).
 */
final class PictureFit {
    private PictureFit() {}

    static final int SIZE_MIN = 50, SIZE_MAX = 100, SIZE_STEP = 5;

    /** A stored or typed size as the host reads it: 0 or above 100 = 100 (unset), below SIZE_MIN = SIZE_MIN (vka_fit_size) */
    static int size(int pct) { return pct <= 0 || pct > SIZE_MAX ? SIZE_MAX : Math.max(SIZE_MIN, pct); }

    /** The panel's slider (0..steps()) and the size it stands for, in SIZE_STEP % steps from SIZE_MIN */
    static int steps() { return (SIZE_MAX - SIZE_MIN) / SIZE_STEP; }
    static int sizeOfStep(int step) { return size(SIZE_MIN + Math.max(0, Math.min(steps(), step)) * SIZE_STEP); }
    static int stepOfSize(int pct) { return Math.round((size(pct) - SIZE_MIN) / (float) SIZE_STEP); }

    /** {x, y, w, h} of the picture in a w x h view, normalized to it */
    static float[] rect(int w, int h, float px, float py, boolean stretch, int sizePct) {
        float pw = 1, ph = 1;
        if (!stretch && w > 0 && h > 0) { if (w * 9f > h * 16f) pw = h * 16f / 9f / w; else ph = w * 9f / 16f / h; }
        float s = size(sizePct) / 100f;
        pw *= s; ph *= s;
        px = Math.max(0, Math.min(1, px)); py = Math.max(0, Math.min(1, py));
        return new float[] {(1 - pw) * px, (1 - ph) * py, pw, ph};
    }
}
