package dev.nxnative.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The graphics selftest, in a process of its own (":selftest"), with no game and no pack: a full-screen SurfaceView whose surface goes to nxhost_vk_selftest(window, 600)
 * on a thread of its own. The replay clears a texture in placed guest memory to a colour with a white 128x128 square, reads two texels back and presents it for 600 frames
 * (about 10 s) while the colour goes round the hues. The result is shown as PASS or FAIL with the return code, and the log (logs/selftest-*.log) is what Share log sends next.
 * The game's on-screen controls are drawn over it, so their layout and settings can be tried without a game.
 */
public final class SelftestActivity extends Activity implements SurfaceHolder.Callback {
    private static final int FRAMES = 600;

    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean running;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Native.load(Profile.first().titleId);   // any game's host has the selftest: the APK's first game's
        FrameLayout root = new FrameLayout(this);
        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setShadowLayer(4, 0, 0, Color.BLACK);
        status.setTextSize(20);
        status.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        status.setPadding(24, 48, 24, 24);
        root.addView(status);
        // The game's on-screen controls over the test picture, to try their layout, size and opacity without a game (their presses go nowhere)
        root.addView(new TouchControls(this, Native.hasInput()), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        File log = new File(LogProvider.dir(this), "selftest-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".log");
        if (Native.openLog(log.getPath()) != 0) { status.setText("FAIL: cannot open the log " + log); return; }
        Native.note("graphics selftest: nxnative " + BuildConfig.VERSION_NAME + " (" + BuildConfig.NX_COMMIT + "); device " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.SUPPORTED_ABIS[0]);
        status.setText(Native.hasSelftest() ? "Graphics self-test: waiting for the surface" : "FAIL: this host has no nxhost_vk_selftest");
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        if (running || !Native.hasSelftest()) { Native.setSurface(h.getSurface()); return; }
        running = true;
        status.setText("Graphics self-test running (" + FRAMES + " frames, about " + FRAMES / 60 + " s)");
        Thread t = new Thread(null, () -> {
            int rc = Native.selftest(h.getSurface(), FRAMES);
            ui.post(() -> {
                status.setTextColor(rc == 0 ? Color.GREEN : Color.RED);
                status.setText((rc == 0 ? "PASS" : "FAIL") + " (return code " + rc + ")\nBack to return; Share log sends this run's log.");
            });
        }, "nxselftest", 1 << 20);
        t.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) { if (running) Native.setSurface(h.getSurface()); }

    @Override public void surfaceDestroyed(SurfaceHolder h) { Native.setSurface(null); }

    /** The Vulkan device and the selftest's state live in this process: leaving ends it, so the next run starts fresh */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        Native.note("graphics selftest: left by the user");
        finish();
        ui.postDelayed(() -> Process.killProcess(Process.myPid()), 300);
    }
}
