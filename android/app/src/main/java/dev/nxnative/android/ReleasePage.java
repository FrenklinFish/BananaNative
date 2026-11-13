package dev.nxnative.android;

import android.app.Activity;
import android.graphics.Outline;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The release page (Updater.popup): a newer release's notes on a full page over Home, built as the Game settings page is (Ui.page: the same gutter, width limit and
 * insets; a header with a Back button), not a dialog. A card says which version is available and from where, a second card holds the notes (ReleaseNotes: styled
 * text, and the pictures of the body in place, below the text that held them), and a bar under the page keeps Later and Update in reach however long the notes
 * are. Back and Later close it; Update closes it and starts the download as before (Updater.download). Pictures load off the main thread and never delay the page
 * (ReleaseImages); offline the page is the text alone.
 * <p>
 * The page is a view added over the activity's content, one at a time; MainActivity.onBackPressed asks back() first. An activity made again (a fold, an unfold)
 * loses it, as it lost the dialog.
 */
final class ReleasePage {
    private ReleasePage() {}

    private static WeakReference<View> open = new WeakReference<>(null);

    /** Shows the page for u; onUpdate runs when Update is tapped (after the page is closed). Does nothing while a page is open */
    static void show(Activity a, UpdateCheck.Update u, String have, Runnable onUpdate) {
        if (open.get() != null && open.get().isAttachedToWindow()) return;
        ViewGroup host = a.findViewById(android.R.id.content);
        if (host == null) return;
        FrameLayout overlay = new FrameLayout(a);
        overlay.setClickable(true);   // the page under it gets no touches
        overlay.setBackgroundColor(Ui.col(a, R.color.bn_bg));
        overlay.addView(new BananaBackgroundView(a), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        overlay.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout pageFrame = new FrameLayout(a);
        root.addView(pageFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout content = Ui.page(a, pageFrame, 640, true);
        Runnable close = () -> host.removeView(overlay);

        content.addView(Ui.header(a, Ui.back(a, v -> close.run()), "Update available", "Banana Native " + UpdateCheck.text(u.version), null));
        LinearLayout head = Ui.add(content, Ui.card(a), 8);
        head.addView(Ui.heading(a, "Version " + UpdateCheck.text(u.version) + " is available"));
        Ui.add(head, Ui.body(a, "You have " + have + ". Install over it: saves, packs and settings are kept."), 4);
        Ui.add(head, Ui.small(a, u.apk.name + " · " + UpdateCheck.mb(u.apk.size) + " · from " + u.release.source.name), 8);

        List<ReleaseNotes.Block> blocks = ReleaseNotes.parse(u.release.body);
        Ui.add(content, Ui.section(a, "What's new"), Ui.SECTION_GAP);
        LinearLayout notes = Ui.add(content, Ui.card(a), 8);
        fill(a, notes, blocks, u.release.source);
        if (notes.getChildCount() == 0) notes.addView(Ui.small(a, "This release has no notes."));

        View line = new View(a);
        line.setBackgroundColor(Ui.col(a, R.color.bn_card_stroke));
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 1)));
        LinearLayout bar = Ui.row(a);
        bar.setBackgroundColor(Ui.col(a, R.color.bn_bg));
        bar.addView(Ui.button(a, "Later", Ui.Kind.TONAL, v -> close.run()), Ui.weight());
        View gap = new View(a);
        bar.addView(gap, new LinearLayout.LayoutParams(Ui.dp(a, 12), 1));
        bar.addView(Ui.button(a, "Update", Ui.Kind.PRIMARY, v -> { close.run(); onUpdate.run(); }), Ui.weight());
        bar.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int w = a.getResources().getDisplayMetrics().widthPixels;
            int gutter = Ui.dp(a, a.getResources().getConfiguration().screenWidthDp < 360 ? 16 : 20);
            int side = Math.max(gutter, (w - i.left - i.right - Ui.dp(a, 640)) / 2);
            v.setPadding(side + i.left, Ui.dp(a, 12), side + i.right, i.bottom + Ui.dp(a, 12));
            return in;
        });
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        host.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        open = new WeakReference<>(overlay);
        overlay.requestApplyInsets();
    }

    /** Closes the page when it is open (MainActivity.onBackPressed): true if there was one */
    static boolean back(Activity a) {
        View o = open.get();
        if (o == null || !o.isAttachedToWindow() || !(o.getParent() instanceof ViewGroup)) return false;
        ((ViewGroup) o.getParent()).removeView(o);
        return true;
    }

    /** The notes into a card: runs of text blocks as styled text, each picture in its place (a placeholder with its alt text until it loads) */
    private static void fill(Activity a, LinearLayout card, List<ReleaseNotes.Block> blocks, UpdateCheck.Source source) {
        List<ReleaseNotes.Block> run = new ArrayList<>();
        for (ReleaseNotes.Block b : blocks) {
            if (b.kind != ReleaseNotes.Kind.IMAGE) { run.add(b); continue; }
            text(a, card, run);
            run.clear();
            picture(a, card, b, source);
        }
        text(a, card, run);
    }

    private static void text(Activity a, LinearLayout card, List<ReleaseNotes.Block> run) {
        if (run.isEmpty()) return;
        CharSequence s = Updater.styled(a, new ArrayList<>(run));
        if (s.length() == 0) return;
        TextView t = Ui.text(a, s, 14, R.color.bn_text, 400);
        t.setLineSpacing(Ui.dp(a, 2), 1f);
        t.setMovementMethod(LinkMovementMethod.getInstance());   // links open in the browser
        t.setFocusable(false);
        Ui.add(card, t, 12);
    }

    private static void picture(Activity a, LinearLayout card, ReleaseNotes.Block b, UpdateCheck.Source source) {
        String alt = b.text.isEmpty() ? null : b.text;
        TextView hold = Ui.small(a, alt != null ? alt : "Loading picture…");
        hold.setGravity(Gravity.CENTER);
        hold.setMinHeight(Ui.dp(a, 96));
        hold.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        hold.setBackground(Ui.round(Ui.col(a, R.color.bn_tint), Ui.dp(a, 12), 0, 0));
        Ui.add(card, hold, 12);
        int edge = a.getResources().getDisplayMetrics().widthPixels;
        ReleaseImages.load(a, b.url, source, edge, bmp -> {
            ViewGroup p = (ViewGroup) hold.getParent();
            if (p == null) return;   // the page is closed
            int at = p.indexOfChild(hold);
            View v;
            if (bmp != null) v = image(a, bmp, alt);
            else if (alt != null) { TextView q = Ui.small(a, alt); q.setTypeface(q.getTypeface(), android.graphics.Typeface.ITALIC); v = q; }   // quiet: its alt text
            else v = null;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) hold.getLayoutParams();
            p.removeViewAt(at);
            if (v == null) return;
            if (bmp != null) { LinearLayout.LayoutParams il = (LinearLayout.LayoutParams) v.getLayoutParams(); il.topMargin = lp.topMargin; lp = il; }
            p.addView(v, at, lp);
        });
    }

    private static final int MAX_W_DP = 360, MAX_H_DP = 280;

    /**
     * A picture at its natural size in dp (the file's pixels over the screen density), never enlarged, at most MAX_W_DP wide and MAX_H_DP high (and within the
     * card), smaller by the same factor in both when it is bigger; centred, its aspect kept
     */
    private static ImageView image(Activity a, ReleaseImages.Pic pic, String alt) {
        float density = a.getResources().getDisplayMetrics().density;
        float wdp = pic.width / density, hdp = pic.height / density;
        float room = a.getResources().getConfiguration().screenWidthDp - 2 * (20 + 18);   // the page's gutter and the card's padding
        float scale = Math.min(1f, Math.min(Math.min(MAX_W_DP, room) / wdp, MAX_H_DP / hdp));
        ImageView v = new ImageView(a);
        v.setImageBitmap(pic.bitmap);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setContentDescription(alt != null ? alt : "Picture from the release notes");
        float r = Ui.dp(a, 12);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        v.setClipToOutline(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(Math.max(1, Math.round(wdp * scale * density)), Math.max(1, Math.round(hdp * scale * density)), Gravity.CENTER_HORIZONTAL));
        return v;
    }

    /** A sample body for trying the page (test builds only: adb --ez release_page_sample true, MainActivity.fromIntent): headings, a list, two pictures */
    static UpdateCheck.Update sample(Activity a) {
        String body = "## What's new in the sample\n\nA page for the notes, with **bold**, *italic*, `code` and a [link](https://example.org/).\n\n"
                + "### Highlights\n\n- A full page in place of the small dialog\n- Pictures from the notes, below their text\n  - loaded on their own, never blocking Update\n"
                + "- A bar with Later and Update that stays in reach\n\n![The first picture](https://raw.githubusercontent.com/github/explore/main/topics/android/android.png)\n\n"
                + "1. A numbered item\n2) Another one that is long enough to wrap onto a second line so that the page shows how a wrapped item looks under its number\n\n"
                + "<img src=\"https://raw.githubusercontent.com/github/explore/main/topics/java/java.png\" alt=\"The second picture (HTML)\">\n\n"
                + "![A picture that is not there](https://raw.githubusercontent.com/github/explore/main/does-not-exist.png)\n\n"
                + "![Refused host](https://example.org/x.png)\n\nThe end of the sample.";
        UpdateCheck.Source src = UpdateCheck.github(UpdateCheck.REPO);
        UpdateCheck.Asset apk = new UpdateCheck.Asset("banana-native-v9.9.9.apk", "https://github.com/" + UpdateCheck.REPO + "/releases/download/v9.9.9/banana-native-v9.9.9.apk", 118_000_000L, null);
        UpdateCheck.Release r = new UpdateCheck.Release("v9.9.9", false, true, body, Arrays.asList(apk), src);
        return new UpdateCheck.Update(r, new int[] {9, 9, 9}, apk, null);
    }
}
