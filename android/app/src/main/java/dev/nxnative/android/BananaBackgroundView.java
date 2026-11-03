package dev.nxnative.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.Choreographer;
import android.view.View;

/**
 * The main screen's background: the icon's banana (res/drawable/ic_banana.xml) tiled in a diagonal grid, faint, drifting slowly.
 * The drift wraps by whole grid periods, so it is seamless. It animates only between {@link #setRunning} true and false (the
 * activity's onResume / onPause, so never while a game runs over it) and only while the system's animations are on; otherwise
 * it is a still picture. One banana bitmap is drawn per tile: no allocation per frame.
 */
public final class BananaBackgroundView extends View implements Choreographer.FrameCallback {
    private static final float GRID_DEG = -14f;        // the whole grid's tilt
    private static final float TILE_DP = 76, STEP_DP = 124, ROW_DP = 96;
    private static final float SPEED_X_DP = 9f, SPEED_Y_DP = 6f;   // dp per second
    private static final long MIN_FRAME_NS = 15_000_000L;          // at most ~60 redraws a second on 90 / 120 Hz panels

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Bitmap banana;
    private boolean wanted, attached, posted;
    private long lastNs;
    private double phase;   // seconds of drift so far

    public BananaBackgroundView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        paint.setAlpha(night ? 40 : 72);   // low contrast: text and buttons stay readable
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Animates while true and the system's animations are on (Settings: remove animations / animator duration scale 0 = still). */
    public void setRunning(boolean on) {
        wanted = on;
        update();
    }

    private boolean running() { return wanted && attached && ValueAnimator.areAnimatorsEnabled(); }

    private void update() {
        boolean running = running();
        if (running && !posted) {
            lastNs = 0;
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        } else if (!running && posted) {
            posted = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override public void doFrame(long nowNs) {
        posted = false;
        if (!running()) return;
        if (lastNs == 0) lastNs = nowNs;
        long dt = nowNs - lastNs;
        if (dt >= MIN_FRAME_NS) {
            phase += Math.min(dt, 100_000_000L) / 1e9;   // a long stall does not jump
            lastNs = nowNs;
            invalidate();
        }
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        update();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        update();
        super.onDetachedFromWindow();
    }

    private Bitmap banana() {
        if (banana == null) {
            int px = Math.max(1, Math.round(TILE_DP * density));
            Drawable d = getContext().getDrawable(R.drawable.ic_banana);
            banana = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, px, px);
            d.draw(new Canvas(banana));
        }
        return banana;
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        Bitmap b = banana();
        float step = STEP_DP * density, row = ROW_DP * density, half = b.getWidth() / 2f;
        // Rows alternate a half step sideways and a mirrored tilt, so the pattern repeats every step across and every two rows down
        float ox = (float) ((phase * SPEED_X_DP * density) % step);
        float oy = (float) ((phase * SPEED_Y_DP * density) % (2 * row));
        float cx = w / 2f, cy = h / 2f, reach = (float) Math.hypot(w, h) / 2f + step;
        int cols = (int) Math.ceil(reach / step) + 1, rows = 2 * ((int) Math.ceil(reach / row / 2) + 1);
        canvas.save();
        canvas.rotate(GRID_DEG, cx, cy);
        for (int r = -rows; r <= rows; r++) {
            boolean odd = (r & 1) != 0;
            float y = cy + r * row + oy - row;
            for (int c = -cols; c <= cols; c++) {
                float x = cx + c * step + (odd ? step / 2 : 0) + ox - step;
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(odd ? 28f : -8f);
                canvas.drawBitmap(b, -half, -half, paint);
                canvas.restore();
            }
        }
        canvas.restore();
    }
}
