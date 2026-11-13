package dev.nxnative.android;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Home's game tiles: square tiles side by side, as many per row as fit the width (each at least minTile wide, so two to a row on a phone), wrapping to the next row.
 * A tile is as tall as it is wide, or as tall as its content when that is more (all tiles of a row get the row's height). A child tagged {@link #SPAN} (a game's
 * progress card) takes a row of its own at its content's height.
 */
final class TileGrid extends ViewGroup {
    static final String SPAN = "span";

    private final int minTile, gap;
    private int[] left = new int[0], top = new int[0];

    TileGrid(Context c, int minTileDp, int gapDp) {
        super(c);
        minTile = Ui.dp(c, minTileDp);
        gap = Ui.dp(c, gapDp);
    }

    /** How many tiles of at least minTile fit a row of width px (1 when even two do not) */
    static int columns(int width, int minTile, int gap) { return Math.max(1, (width + gap) / (minTile + gap)); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int cols = columns(w, minTile, gap);
        int tile = (w - (cols - 1) * gap) / cols;
        int n = getChildCount();
        if (left.length != n) { left = new int[n]; top = new int[n]; }
        int y = 0, col = 0, rowStart = 0, rowH = 0;
        for (int i = 0; i < n; i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            boolean span = SPAN.equals(v.getTag());
            if (span || col == cols) {   // the row so far is done
                if (col > 0) { fixRow(rowStart, i, tile, rowH); y += rowH + gap; }
                col = 0; rowH = 0; rowStart = i;
            }
            if (span) {
                v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                left[i] = 0; top[i] = y;
                y += v.getMeasuredHeight() + gap;
                rowStart = i + 1;
                continue;
            }
            v.measure(MeasureSpec.makeMeasureSpec(tile, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            rowH = Math.max(rowH, Math.max(tile, v.getMeasuredHeight()));
            left[i] = col * (tile + gap); top[i] = y;
            col++;
        }
        if (col > 0) { fixRow(rowStart, n, tile, rowH); y += rowH + gap; }
        setMeasuredDimension(w, Math.max(0, y - gap));
    }

    /** Every tile of the row [from, to) gets the row's height */
    private void fixRow(int from, int to, int tile, int rowH) {
        for (int i = from; i < to; i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE || SPAN.equals(v.getTag())) continue;
            v.measure(MeasureSpec.makeMeasureSpec(tile, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowH, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.layout(left[i], top[i], left[i] + v.getMeasuredWidth(), top[i] + v.getMeasuredHeight());
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); }
}
