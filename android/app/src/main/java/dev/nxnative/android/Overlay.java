package dev.nxnative.android;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The overlay's top-right text over the game (GameActivity polls it twice a second while the overlay is on; nothing is read while it is off): the frame rate and frame
 * time (nxhost_fps), the throttle dot, the GPU's time a frame (nxhost_fps), the CPU clock caps, the battery and the memory (MemText: the game process's VmRSS and
 * the phone's RAM in use, read every 2 s on a thread of its own). Android text only, no GPU work. Each item has its own
 * switch (Prefs.overlayItem), all on by default, under the master switch (Prefs.overlay). The build tag top left is one of them too (GameActivity), on by default
 * like the rest, so screenshots and recordings name their build; the log's first line names it either way.
 *
 * <p>The throttle dot, worst of three readings:
 * <ul>
 * <li>red: thermal status MODERATE or worse (PowerManager.getCurrentThermalStatus() >= 2), or thermal headroom >= 1.0 (getThermalHeadroom(10): 1.0 is SEVERE, where the system
 *     throttles hard), or the main CPU cluster capped below 65 % of its top clock;</li>
 * <li>yellow: thermal status LIGHT (1), or headroom >= 0.85 (the PNM-N49 sits at 0.69-0.75 with full clocks), or the main cluster capped below 85 %;</li>
 * <li>green otherwise; grey when nothing could be read.</li>
 * </ul>
 * The main cluster is the cpufreq policy with the most cores (the one the game's threads run on), its cap scaling_max_freq / cpuinfo_max_freq. On the Honor PNM-N49
 * (cpu0-5 up to 3.63 GHz, cpu6-7 up to 4.61 GHz) the game runs 30 fps with the main cluster at 2.5 GHz or more and drops to 20 fps at the 2.0-2.23 GHz caps (55-61 %)
 * the phone sets once warm, while the thermal status still reads 1: that reads red. The 2.50 GHz cap (69 %) the phone keeps even when cool reads yellow.
 */
final class Overlay {
    /** The items: the Prefs key and the switch's label */
    static final String[][] ITEMS = {
            {"fps", "Frame rate and frame time"},
            {"throttle", "Throttle dot (green / yellow / red)"},
            {"gpu", "GPU time a frame"},
            {"cpu", "CPU clock caps"},
            {"battery", "Battery temperature and level"},
            {"tag", "Build tag"},
            {"memory", "Memory: the game's and the phone's"},
    };

    /** The master switch and, below it, one switch per item (main screen dialog, in-game settings) */
    static void rows(Context c, LinearLayout box, String masterNote) {
        CheckBox master = new CheckBox(c);
        master.setText("Show the overlay over the game" + (masterNote != null ? masterNote : ""));
        master.setChecked(Prefs.overlay(c));
        box.addView(master);
        List<CheckBox> items = new ArrayList<>();
        int indent = Math.round(24 * c.getResources().getDisplayMetrics().density);
        for (String[] it : ITEMS) {
            CheckBox b = new CheckBox(c);
            b.setText(it[1]);
            b.setChecked(Prefs.overlayItem(c, it[0]));
            b.setEnabled(master.isChecked());
            b.setOnCheckedChangeListener((v, on) -> Prefs.overlayItem(c, it[0], on));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = indent;
            box.addView(b, lp);
            items.add(b);
        }
        master.setOnCheckedChangeListener((v, on) -> { Prefs.overlay(c, on); for (CheckBox b : items) b.setEnabled(on); });
    }

    // ---- Readings ----
    private static final class Cluster { String dir; int cores; long maxKHz, capKHz; }
    private final Context ctx;
    private final PowerManager power;
    private List<Cluster> clusters;   // read once: /sys/devices/system/cpu/cpufreq/policy*, main cluster first
    private int ticks, thermal = -1, level = -1, noted = -2, batLevel = -1;
    private float headroom = Float.NaN, batTemp = Float.NaN;
    private boolean charging;
    private long notedAt;
    private final float[] rate = new float[6];

    // The memory, read every 2 s on a thread of its own (ActivityManager.getMemoryInfo is a call into the system server; the UI thread only formats what it last read)
    private static ExecutorService memThread;
    private final ActivityManager am;
    private final ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
    private final Runnable readMem = this::readMemory;
    private volatile long rss = -1, memTotal, memAvail;
    private volatile boolean memLow;

    Overlay(Context c) { ctx = c; power = c.getSystemService(PowerManager.class); am = c.getSystemService(ActivityManager.class); }

    /** On the memory thread: this process's resident set (the host and the game run in it) and the phone's memory */
    private void readMemory() {
        try { rss = MemText.vmRss(new String(Files.readAllBytes(new File("/proc/self/status").toPath()), StandardCharsets.US_ASCII)); }
        catch (IOException | RuntimeException e) { rss = -1; }
        if (am == null) return;
        am.getMemoryInfo(memInfo);
        memAvail = memInfo.availMem; memLow = memInfo.lowMemory; memTotal = memInfo.totalMem;
    }

    private void askMemory() {
        if (ticks % 4 != 1 && memTotal > 0) return;   // every 2 s
        synchronized (Overlay.class) {
            if (memThread == null) memThread = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "overlay-mem"); t.setDaemon(true); return t; });
        }
        memThread.execute(readMem);
    }

    private static long readLong(File f) {
        try { return Long.parseLong(new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim()); }
        catch (IOException | RuntimeException e) { return -1; }
    }

    private void findClusters() {
        clusters = new ArrayList<>();
        File[] ps = new File("/sys/devices/system/cpu/cpufreq").listFiles((d, n) -> n.startsWith("policy"));
        if (ps == null) return;
        for (File p : ps) {
            Cluster k = new Cluster();
            k.dir = p.getPath();
            k.maxKHz = readLong(new File(p, "cpuinfo_max_freq"));
            try { k.cores = new String(Files.readAllBytes(new File(p, "related_cpus").toPath()), StandardCharsets.US_ASCII).trim().split("\\s+").length; }
            catch (IOException | RuntimeException e) { k.cores = 1; }
            if (k.maxKHz > 0) clusters.add(k);
        }
        clusters.sort((a, b) -> a.cores != b.cores ? b.cores - a.cores : Long.compare(a.maxKHz, b.maxKHz));
    }

    private void readCpu() {
        if (clusters == null) findClusters();
        for (Cluster k : clusters) k.capKHz = readLong(new File(k.dir, "scaling_max_freq"));
    }

    private void readThermal() {
        thermal = power != null ? power.getCurrentThermalStatus() : -1;
        if (power != null && ticks % 4 == 1) headroom = power.getThermalHeadroom(10);   // NaN when asked more than about once a second: every 2 s
    }

    private void readBattery() {
        if (ticks % 4 != 1 && batLevel >= 0) return;   // every 2 s
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));   // the sticky broadcast: no receiver is registered
        if (b == null) return;
        int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100), t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        batLevel = l >= 0 && s > 0 ? l * 100 / s : -1;
        batTemp = t != Integer.MIN_VALUE ? t / 10f : Float.NaN;
        // plugged in counts: the PNM-N49 reports "discharging" on a 5 V charger that only holds the level
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL || b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private float mainCap() {
        if (clusters == null || clusters.isEmpty()) return Float.NaN;
        Cluster k = clusters.get(0);
        return k.capKHz > 0 ? (float) k.capKHz / k.maxKHz : Float.NaN;
    }

    /** 0 green, 1 yellow, 2 red, -1 nothing read (class comment) */
    private int level() {
        float cap = mainCap();
        if (thermal < 0 && Float.isNaN(headroom) && Float.isNaN(cap)) return -1;
        int lv = 0;
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE || headroom >= 1.0f || cap < 0.65f) lv = 2;
        else if (thermal >= PowerManager.THERMAL_STATUS_LIGHT || headroom >= 0.85f || cap < 0.85f) lv = 1;
        return lv;
    }

    private static void line(SpannableStringBuilder s, String t) { s.append(s.length() > 0 ? "\n" : "").append(t); }

    /** "CPU 2.7/3.6" and a line per further cluster ("3.1/4.6 GHz"): cap / top clock in GHz, main cluster first */
    private void caps(SpannableStringBuilder s) {
        int n = 0;
        for (Cluster k : clusters) {
            if (k.capKHz <= 0) continue;
            line(s, (n++ == 0 ? "CPU " : "") + String.format(Locale.ROOT, "%.1f/%.1f", k.capKHz / 1e6, k.maxKHz / 1e6));
        }
        if (n == 0) line(s, "CPU cap ?");
        else s.append(" GHz");
    }

    private static final int[] DOT = {0xFF4CAF50, 0xFFFFC107, 0xFFF44336};

    /** The text for the top-right view, or null when no item there is on */
    CharSequence tick() {
        ticks++;
        boolean fps = Prefs.overlayItem(ctx, "fps"), dot = Prefs.overlayItem(ctx, "throttle"), gpu = Prefs.overlayItem(ctx, "gpu"),
                cpu = Prefs.overlayItem(ctx, "cpu"), bat = Prefs.overlayItem(ctx, "battery"), mem = Prefs.overlayItem(ctx, "memory");
        if (!fps && !dot && !gpu && !cpu && !bat && !mem) return null;
        if (mem) askMemory();
        if (dot || cpu) readCpu();
        if (dot) { readThermal(); level = level(); note(); }
        if (bat) readBattery();
        boolean fresh = (fps || gpu) && Native.fps(rate);

        // One short item a line, so that the block fits in the black bar right of the game's 16:9 picture on a wide phone and stays off the game's own HUD
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (dot) {
            s.append("●");
            s.setSpan(new ForegroundColorSpan(level >= 0 ? DOT[level] : Color.GRAY), s.length() - 1, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new RelativeSizeSpan(1.3f), s.length() - 1, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (fps) s.append(" ");
        }
        if (fps) {
            if (!fresh) s.append("-- fps");
            else if (rate[3] == 1 || rate[3] == 2) s.append(String.format(Locale.ROOT, "%.0f→%.0f fps", rate[0], rate[1]));
            else s.append(String.format(Locale.ROOT, "%.0f fps", rate[0]));
        }
        if (fps && fresh) line(s, String.format(Locale.ROOT, "%.1f ms", rate[2]));
        if (fps && (rate[3] == 1 || rate[3] == 2)) line(s, "LSFG");
        if (fps && rate[3] < 0) line(s, "no LSFG");
        if (gpu && fresh && rate[5] >= 0) line(s, String.format(Locale.ROOT, "GPU %.1f ms", rate[5]));
        if (cpu) caps(s);
        if (bat && batLevel >= 0)
            line(s, (Float.isNaN(batTemp) ? "" : String.format(Locale.ROOT, "%.0f°C ", batTemp)) + batLevel + (charging ? "% ⚡\uFE0E" : "%"));
        if (mem && memTotal > 0) {   // two short lines, as the others: the game process's resident set, then the phone's RAM in use / total, "low" in red when Android says so
            line(s, MemText.app(rss));
            line(s, MemText.system(memTotal, memAvail));
            if (memLow) {
                s.append(" low");
                s.setSpan(new ForegroundColorSpan(DOT[2]), s.length() - 3, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return s;   // every line at the overlay's one size (GameActivity.OVERLAY_SP): at 8 sp a smaller second size would no longer read
    }

    /** The log gets the dot's colour and why when it changes (at most every 10 s), so a slow stretch in a log can be matched with throttling */
    private void note() {
        long now = SystemClock.elapsedRealtime();
        if (level == noted || now - notedAt < 10000) return;
        noted = level; notedAt = now;
        StringBuilder c = new StringBuilder();
        if (clusters != null) for (Cluster k : clusters) c.append(' ').append(k.capKHz / 1000).append('/').append(k.maxKHz / 1000);
        Native.note(String.format(Locale.ROOT, "[overlay] throttle %s: thermal status %d, headroom %.2f, CPU caps (MHz)%s",
                level < 0 ? "unknown" : new String[] {"green", "yellow", "red"}[level], thermal, headroom, c.length() > 0 ? c : " unreadable"));
    }
}
