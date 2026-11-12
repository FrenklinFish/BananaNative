package dev.nxnative.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Readiness check's screen (Settings › Readiness check), in a process of its own (":readiness") because it loads a host (the APK's first game's: any host reports the
 * GPU) to ask Vulkan what this phone has (Native.readiness), on the GPU driver picked for every game. Cards: the phone (Android, memory, files access, storage, battery
 * restrictions, the last game exit and memory kills of the last 7 days), the GPU and driver, then for each game of this APK the host's needs and the game's own (Readiness),
 * each pass / warning / fail with a fix button where there is one. Run again; Copy report and Share report hand the whole report on for a compatibility report.
 * The cards, Run again, the fix buttons, the last exit and the shareable report are adapted from the author's earlier readiness check. The same check runs before every
 * game starts (GameActivity), on that game's own driver, and stops a start that would fail with a sentence and Try anyway.
 */
public final class ReadinessActivity extends Activity {
    /** MainActivity's request: a result of FIX_DRIVER asks it to open the GPU driver dialog (it lives in the launcher's process) */
    static final int REQUEST = 7, FIX_DRIVER = RESULT_FIRST_USER;
    private static final long KILL_WINDOW_MS = 7 * DateUtils.DAY_IN_MILLIS;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView summary, verdict, mark;
    private String report = "";
    // This process's Vulkan facts and the driver they were read on: the driver is opened once per process (vka_driver_open), so they are static, kept by a screen opened
    // again in the same process (left by Home, not Back), which then sees a changed pick as stale instead of reading the old driver as the new one
    private static String vulkan;
    private static String probedDriver;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.col(this, R.color.bn_bg));
        setContentView(frame);
        LinearLayout content = Ui.page(this, frame, 640);   // the same gutter, width and header as the launcher's pages
        content.addView(Ui.header(this, Ui.back(this, v -> leave()), "Readiness check", null));
        // The verdict: a mark, one line, the counts; then Run again and the report. Each check below shows its details on a tap (failures and warnings open)
        LinearLayout top = Ui.add(content, Ui.card(this), 16);
        LinearLayout vr = Ui.row(this);
        mark = Ui.text(this, "…", 26, R.color.bn_on_banana, 900);
        mark.setGravity(android.view.Gravity.CENTER);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        vr.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout vc = new LinearLayout(this);
        vc.setOrientation(LinearLayout.VERTICAL);
        vc.setPadding(Ui.dp(this, 14), 0, 0, 0);
        verdict = Ui.heading(this, "Checking…");
        vc.addView(verdict);
        summary = Ui.small(this, "");
        vc.addView(summary);
        vr.addView(vc, Ui.weight());
        top.addView(vr);
        setMark(-1);
        Button share = Ui.button(this, "Share", Ui.Kind.TONAL, v -> share());
        share.setOnLongClickListener(v -> { copy(this, report); return true; });
        Ui.add(top, Ui.pair(this, Ui.button(this, "Run again", Ui.Kind.TONAL, v -> run()), share), 16);
        Ui.add(top, Ui.button(this, "Copy report", Ui.Kind.QUIET, v -> copy(this, report)), 6, Ui.wrap());
        list = Ui.add(content, new LinearLayout(this), 0);
        list.setOrientation(LinearLayout.VERTICAL);
        try {
            Native.load(Profile.first().titleId);   // any game's host reports the GPU: the APK's first game's
        } catch (RuntimeException e) {   // (IllegalStateException: no host; an APK always has a game)
            vulkan = "unchecked=" + e.getMessage();
        }
        run();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null && list.getChildCount() > 0) run();   // back from a fix (the app's settings, files access)
    }

    /** The checks on a thread of their own (the Vulkan query loads the driver), then the cards */
    private boolean running;
    private void run() {
        if (running) return;
        running = true;
        verdict.setText("Checking…");
        summary.setText("");
        setMark(-1);
        Context app = getApplicationContext();
        new Thread(() -> {
            GpuDriver.Info d = GpuDriver.selected(app);
            String drv = d == null ? "" : d.dir.getPath();
            if (vulkan == null || !vulkan.startsWith("unchecked=")) {
                if (probedDriver == null || probedDriver.equals(drv)) {
                    List<String> env = new ArrayList<>();
                    GpuDriver.env(app, null, env);
                    String v = Native.readiness(Readiness.driverEnv(env));
                    Prefs.rememberFacts(app, d == null ? GpuDriver.SYSTEM : d.dir.getName(), v);   // what the recommended settings match on (Recommend)
                    vulkan = v;
                    probedDriver = drv;
                }
            }
            boolean stale = probedDriver != null && !probedDriver.equals(drv);
            Result r = collect(app, vulkan, stale);
            ui.post(() -> { running = false; show(r); });
        }, "nxreadiness").start();
    }

    private static final class Section {
        final String heading;
        final List<Readiness.Check> checks;

        Section(String heading, List<Readiness.Check> checks) { this.heading = heading; this.checks = checks; }
    }

    private static final class Result {
        final List<Section> sections = new ArrayList<>();
        String report, verdict, summary;
        int level;   // 0 ready, 1 ready with less, 2 not ready
    }

    private static Result collect(Context c, String vulkan, boolean stale) {
        Result r = new Result();
        Map<String, String> f = Readiness.merge(vulkan, ramGib(c), Build.VERSION.SDK_INT);
        List<Readiness.Check> phone = phoneChecks(c);
        GpuDriver.Info d = GpuDriver.selected(c);
        List<Readiness.Check> gpu = new ArrayList<>();
        gpu.add(new Readiness.Check("GPU and driver", stale ? Readiness.Status.WARNING : f.containsKey("gpu.name") ? Readiness.Status.INFO : f.containsKey("error") ? Readiness.Status.FAIL : Readiness.Status.WARNING, Readiness.gpu(f)
                + (stale ? ". The GPU driver was changed while this screen was open: leave it and open it again to check the new one." : ""), "driver"));
        gpu.add(new Readiness.Check("GPU driver picked", Readiness.Status.INFO, (d == null ? "System" : d.label()) + " (Settings › GPU driver).", "driver"));
        r.sections.add(new Section("This phone", phone));
        r.sections.add(new Section("GPU", gpu));
        StringBuilder rep = new StringBuilder(header(c, null)).append('\n');
        int fails = 0, warns = 0;
        for (Readiness.Check x : phone) rep.append(x).append('\n');
        for (Readiness.Check x : gpu) rep.append(x).append('\n');
        List<Game> added = Game.load(c);
        for (Profile p : Profile.all()) {
            List<Readiness.Check> l = new ArrayList<>();
            Readiness.Check st = Readiness.status(p.status);
            if (st != null) l.add(st);
            l.addAll(Readiness.evaluate(f, Readiness.parseReqs(p.reqs)));
            String own = GpuDriver.choice(c, p.titleId);
            if (own != null) {
                // This screen probes the main screen's driver only (the driver opens once a process, vka_driver_open): a game with another driver of its own is
                // checked on that one when it starts, and that check can differ from this screen's
                GpuDriver.Info g = GpuDriver.selected(c, p.titleId);
                String gl = g == null ? "System" : g.label(), ml = d == null ? "System" : d.label();
                boolean same = g == null ? d == null : d != null && g.dir.equals(d.dir);
                l.add(same ? new Readiness.Check("Its own GPU driver", Readiness.Status.INFO, gl + ", the one this screen checked.", "driver")
                        : new Readiness.Check("Its own GPU driver", Readiness.Status.WARNING, "This game uses its own GPU driver, " + gl + "; this screen checked " + ml
                                + ". The check before the game starts uses the game's driver, and its result can differ from this one.", "driver"));
            }
            String name = p.titleId;   // a game is named as the user's dump names it (its card), else by its title ID
            for (Game g : added) if (g.titleId.equals(p.titleId)) { name = g.name; break; }
            for (Game g : added) if (g.titleId.equals(p.titleId)) l.add(gameFiles(c, g));
            r.sections.add(new Section(name, l));
            boolean fail = Readiness.any(l, Readiness.Status.FAIL), warn = Readiness.any(l, Readiness.Status.WARNING);
            if (fail) fails++; else if (warn) warns++;
            rep.append("-- game ").append(p.titleId).append(" --\n");
            for (Readiness.Check x : l) rep.append(x).append('\n');
        }
        String last = lastReports(c);
        if (!last.isEmpty()) rep.append(last);
        rep.append("-- facts --\n");
        for (Map.Entry<String, String> e : f.entrySet()) rep.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        r.report = rep.toString();
        int phoneFails = Readiness.of(phone, Readiness.Status.FAIL).size();
        int phoneWarns = Readiness.of(phone, Readiness.Status.WARNING).size();
        r.level = fails > 0 || phoneFails > 0 ? 2 : warns > 0 ? 1 : 0;
        r.verdict = r.level == 2 ? "Not ready" : r.level == 1 ? "Ready, with limits" : "Ready";
        r.summary = r.level == 2 ? (fails > 0 ? fails + (fails == 1 ? " game lacks" : " games lack") + " what it needs" : "") + (fails > 0 && phoneFails > 0 ? "; " : "")
                + (phoneFails > 0 ? phoneFails + " phone problem" + (phoneFails == 1 ? "" : "s") : "") + "."
                : r.level == 1 ? warns + (warns == 1 ? " game runs" : " games run") + " with less here." : phoneWarns > 0 ? "Every game's needs are met; see the warnings below." : "Every game's needs are met.";
        return r;
    }

    private void show(Result r) {
        if (isFinishing() || isDestroyed()) return;
        report = r.report;
        verdict.setText(r.verdict);
        summary.setText(r.summary);
        setMark(r.level);
        list.removeAllViews();
        for (Section s : r.sections) {
            Ui.add(list, Ui.section(this, s.heading), Ui.SECTION_GAP);
            LinearLayout card = Ui.add(list, Ui.listCard(this), 8);
            boolean gpu = s == r.sections.get(1);   // the GPU section's facts are what a report needs most: open
            for (Readiness.Check x : s.checks) Ui.add(card, row(x, gpu), 2);
        }
    }

    /** The verdict's mark: ✓ on leaf green, ! on banana, ✕ on red-orange; … while checking */
    private void setMark(int level) {
        mark.setText(level == 0 ? "✓" : level == 1 ? "!" : level == 2 ? "✕" : "…");
        int fill = level == 0 ? R.color.bn_leaf : level == 2 ? R.color.bn_danger : R.color.bn_banana;
        mark.setTextColor(Ui.col(this, level == 0 ? R.color.bn_on_leaf : level == 2 ? R.color.bn_card : R.color.bn_on_banana));
        mark.setBackground(Ui.round(Ui.col(this, fill), Ui.dp(this, 999), 0, 0));
    }

    /** One check: its mark and title; the details show on a tap, and open from the start for a failure or a warning (with its fix button) */
    private View row(Readiness.Check x, boolean openInfo) {
        boolean bad = x.status == Readiness.Status.FAIL || x.status == Readiness.Status.WARNING, open0 = bad || openInfo && x.status == Readiness.Status.INFO;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        box.setBackground(Ui.ripple(this, Ui.round(bad ? Ui.col(this, x.status == Readiness.Status.FAIL ? R.color.bn_danger_tint : R.color.bn_warn_tint) : android.graphics.Color.TRANSPARENT,
                Ui.dp(this, 16), 0, 0)));
        LinearLayout top = Ui.row(this);
        String m = x.status == Readiness.Status.PASS ? "✓" : x.status == Readiness.Status.WARNING ? "!" : x.status == Readiness.Status.FAIL ? "✕" : "i";
        TextView st = Ui.text(this, m, 15, x.status == Readiness.Status.FAIL ? R.color.bn_danger : x.status == Readiness.Status.PASS ? R.color.bn_leaf : R.color.bn_text_2, 900);
        st.setGravity(android.view.Gravity.CENTER);
        top.addView(st, new LinearLayout.LayoutParams(Ui.dp(this, 24), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.text(this, x.title, 15, R.color.bn_text, 600);
        t.setPadding(Ui.dp(this, 8), 0, 0, 0);
        top.addView(t, Ui.weight());
        android.widget.ImageView chev = new android.widget.ImageView(this);   // turned down when closed, up when open
        chev.setImageDrawable(Ui.icon(this, R.drawable.ic_chevron, R.color.bn_text_2));
        chev.setRotation(open0 ? -90 : 90);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(chev, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        box.addView(top);
        LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setPadding(Ui.dp(this, 32), 0, 0, 0);
        TextView msg = Ui.small(this, x.message);
        msg.setTextIsSelectable(false);
        more.addView(msg);
        View.OnClickListener fix = fix(x);
        if (fix != null && bad) Ui.add(more, Ui.button(this, fixLabel(x.fix), Ui.Kind.SOFT, fix), 8, Ui.wrap());
        Ui.add(box, more, 4);
        more.setVisibility(open0 ? View.VISIBLE : View.GONE);
        box.setOnClickListener(v -> {
            boolean open = more.getVisibility() != View.VISIBLE;
            more.setVisibility(open ? View.VISIBLE : View.GONE);
            chev.setRotation(open ? -90 : 90);
        });
        return box;
    }

    private static String fixLabel(String fix) {
        switch (fix) {
            case "driver": return "Pick a GPU driver";
            case "access": return "Allow file access";
            default: return "Open the app's settings";
        }
    }

    private View.OnClickListener fix(Readiness.Check x) {
        if (x.fix == null) return null;
        switch (x.fix) {
            case "driver": return v -> { setResult(FIX_DRIVER); leave(); };
            case "access": return v -> settings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            case "app": return v -> settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            default: return null;   // memory: nothing on the phone changes it
        }
    }

    private void settings(String action) {
        try { startActivity(new Intent(action, Uri.parse("package:" + getPackageName()))); }
        catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "nxnative readiness report").putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(send, "Share the readiness report"));
    }

    static void copy(Context c, String text) {
        ClipboardManager cm = c.getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Readiness report", text));
        Toast.makeText(c, "Readiness report copied", Toast.LENGTH_SHORT).show();
    }

    // ---- What the launcher knows without Vulkan (shared with GameActivity) ----

    /** The phone's memory as Android reports it (ActivityManager totalMem, GiB): less than the label, by what the firmware and kernel keep (a 16 GB phone reports about
     *  14.5-15.5 GiB, a 12 GB one about 10.5-11.5) */
    static double ramGib(Context c) {
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(m);
        return m.totalMem / (double) (1L << 30);
    }

    /**
     * Whether the dump of an added game can be read: a folder (readable, with base/exefs/main.npdm or exefs/main.npdm), or a .nup (Nup.open: its header and the
     * SHA-256 of its table of contents; its packages listed)
     */
    static Readiness.Check gameFiles(Context c, Game g) {
        File f = new File(g.gameDir);
        if (PickedNup.is(c, g.gameDir)) {   // a .nup used where it is: read through the picker's permission, no All files access needed
            try {
                Nup n = Nup.open(f);
                return new Readiness.Check("Game files", Readiness.Status.PASS, f.getName() + ": a .nup used where it is (picked with the file picker; " + n.packages.size()
                        + " packages, its table of contents checked). It needs no All files access.", null);
            } catch (IOException e) {
                return new Readiness.Check("Game files", Readiness.Status.FAIL, e instanceof PickedNup.Lost ? GameFile.goneText(f.getName()) + " (its card's button picks it.)"
                        : f.getName() + " cannot be used: " + e.getMessage(), null);
            }
        }
        if (GameFile.gone(f.exists(), Environment.isExternalStorageManager() || CopyPlan.inApp(AppStorage.root(c), g.gameDir)))
            return new Readiness.Check("Game files", Readiness.Status.FAIL, GameFile.goneText(f.getName()) + " (" + f + "; its card's button picks it.)", null);
        if (Nup.isNup(f)) {
            try {
                Nup n = Nup.open(f);
                StringBuilder s = new StringBuilder();
                for (Nup.Pkg p : n.packages) s.append(s.length() == 0 ? "" : ", ").append(p.selector() != null ? p.selector() : "kind " + p.kind);
                return new Readiness.Check("Game files", Readiness.Status.PASS, f + ": a .nup that reads and checks (" + s + ").", null);
            } catch (IOException e) {
                return new Readiness.Check("Game files", Readiness.Status.FAIL, f + " cannot be used: " + e.getMessage(), f.canRead() ? null : "access");
            }
        }
        if (!f.isDirectory() || !f.canRead()) return new Readiness.Check("Game files", Readiness.Status.FAIL, f + " is not a folder this app can read.", "access");
        if (Pack.npdmOf(f) == null) return new Readiness.Check("Game files", Readiness.Status.FAIL, f + " has no main.npdm (base/exefs/, updates/<version>/exefs/ or exefs/).", null);
        return new Readiness.Check("Game files", Readiness.Status.PASS, f + ": a folder that reads.", null);
    }

    /** The report's first lines: the build, the phone, Android, and the game when there is one */
    static String header(Context c, String game) {
        return "nxnative readiness report: " + Prefs.buildTag(c) + "\nSigner: " + Prefs.signerId(c) + " (SHA-256 of the signing certificate, first 16 hex digits)\nPhone: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL + "), Android "
                + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.SUPPORTED_ABIS[0] + (game == null ? "" : "\nGame: " + game);
    }

    /** The newest start-up check of each game (GameActivity writes files/readiness-&lt;title ID&gt;.txt), for the report */
    private static String lastReports(Context c) {
        StringBuilder s = new StringBuilder();
        File files = c.getExternalFilesDir(null);
        for (Profile p : Profile.all()) {
            File f = new File(files, "readiness-" + p.titleId + ".txt");
            if (!f.isFile()) continue;
            try {
                String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String first = t.contains("\n[") ? t.substring(t.indexOf("\n[") + 1) : t;
                int end = first.indexOf("-- facts --");
                s.append("-- last start of ").append(p.titleId).append(" (").append(DateUtils.getRelativeTimeSpanString(f.lastModified())).append(") --\n")
                        .append(end >= 0 ? first.substring(0, end) : first);
            } catch (IOException ignored) { /* left out */ }
        }
        return s.toString();
    }

    private static List<Readiness.Check> phoneChecks(Context c) {
        List<Readiness.Check> l = new ArrayList<>();
        boolean arm64 = java.util.Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
        l.add(new Readiness.Check("Phone", arm64 ? Readiness.Status.PASS : Readiness.Status.FAIL,
                Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + (arm64 ? ", 64-bit ARM" : ": not a 64-bit ARM phone, which the app needs"), null));
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(m);
        l.add(new Readiness.Check("Memory", Readiness.Status.INFO, String.format(Locale.ROOT, "%.1f GiB as Android reports it (less than the label), %.1f GiB free now%s.",
                m.totalMem / (double) (1L << 30), m.availMem / (double) (1L << 30), m.lowMemory ? ", which Android counts as low" : ""), null));
        boolean access = Environment.isExternalStorageManager();
        l.add(new Readiness.Check("All files access", access ? Readiness.Status.PASS : Readiness.Status.WARNING,   // games copied into the app (GameCopy) run without it
                access ? "Granted." : "Not granted: the app reads .nup files picked with the file picker and dump folders copied into it, not a dump folder elsewhere "
                        + "on the phone.", "access"));
        try {
            File ext = c.getExternalFilesDir(null);
            long free = new StatFs((ext != null ? ext : c.getFilesDir()).getPath()).getAvailableBytes();
            double gb = free / 1e9;
            l.add(new Readiness.Check("Free storage", gb >= 10 ? Readiness.Status.PASS : Readiness.Status.WARNING, String.format(Locale.ROOT, "%.1f GB free%s", gb,
                    gb >= 10 ? "." : ": a game's pack takes several GB."), null));
        } catch (IllegalArgumentException e) { /* no such folder: left out */ }
        UsageStatsManager us = c.getSystemService(UsageStatsManager.class);
        if (am.isBackgroundRestricted()) l.add(new Readiness.Check("Battery", Readiness.Status.WARNING, "Battery use is Restricted: Android may stop the app in the background.", "app"));
        else if (us != null && us.getAppStandbyBucket() == UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
            l.add(new Readiness.Check("Battery", Readiness.Status.WARNING, "The app is in Android's restricted standby group: it may be stopped in the background.", "app"));
        else l.add(new Readiness.Check("Battery", Readiness.Status.PASS, "No restriction.", null));
        // The game's process (:game) as Android remembers its ends: the newest, and kills for memory in the last 7 days
        try {
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 0);
            ApplicationExitInfo lastGame = null; int kills = 0; long lastKill = 0, since = System.currentTimeMillis() - KILL_WINDOW_MS;
            for (ApplicationExitInfo e : exits) {
                if (e.getProcessName() == null || !e.getProcessName().endsWith(":game")) continue;
                if (lastGame == null || e.getTimestamp() > lastGame.getTimestamp()) lastGame = e;
                if (e.getTimestamp() >= since && memoryKill(e)) { kills++; lastKill = Math.max(lastKill, e.getTimestamp()); }
            }
            if (lastGame == null) l.add(new Readiness.Check("Last game exit", Readiness.Status.INFO, "No game has run yet.", null));
            else {
                CharSequence when = DateUtils.getRelativeTimeSpanString(lastGame.getTimestamp(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
                int why = lastGame.getReason();
                boolean normal = why == ApplicationExitInfo.REASON_EXIT_SELF || why == ApplicationExitInfo.REASON_USER_REQUESTED || why == ApplicationExitInfo.REASON_USER_STOPPED;
                String what = memoryKill(lastGame) ? "stopped by Android for memory" : why == ApplicationExitInfo.REASON_CRASH || why == ApplicationExitInfo.REASON_CRASH_NATIVE ? "crashed"
                        : why == ApplicationExitInfo.REASON_ANR ? "stopped responding" : normal ? "ended normally" : "ended (" + exitName(why) + ")";
                String desc = lastGame.getDescription();
                l.add(new Readiness.Check("Last game exit", normal ? Readiness.Status.PASS : Readiness.Status.WARNING, "The last game " + what + ", " + when + (desc != null && !desc.isEmpty() && !normal ? " (" + desc + ")" : "")
                        + (normal ? "." : ". Settings › Share log sends its log."), null));
            }
            if (kills > 0) l.add(new Readiness.Check("Memory kills", Readiness.Status.WARNING, kills + (kills == 1 ? " game was" : " games were") + " stopped for memory in the last 7 days, the last "
                    + DateUtils.getRelativeTimeSpanString(lastKill, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + ". Close other apps before playing.", null));
            else l.add(new Readiness.Check("Memory kills", Readiness.Status.PASS, "None in the last 7 days.", null));
        } catch (RuntimeException e) { /* not available on this phone: left out */ }
        if (GameActivity.testApp()) {   // the test app's switches (env.txt): a report says which were on
            File env = new File(c.getExternalFilesDir(null), "env.txt");
            List<String> keys = new ArrayList<>();
            try {
                if (env.isFile()) for (String kv : GameActivity.envLines(new String(Files.readAllBytes(env.toPath()), StandardCharsets.UTF_8))) keys.add(kv.substring(0, kv.indexOf('=')).trim());
            } catch (IOException | SecurityException e) { keys.add("(env.txt unreadable)"); }
            l.add(new Readiness.Check("Diagnostic switches", Readiness.Status.INFO, keys.isEmpty() ? "None in env.txt." : "On in env.txt: " + String.join(", ", keys) + ".", null));
        }
        return l;
    }

    /** An end for memory: Android's low-memory reason, or a kill whose description names the low-memory killer (phones without the reason report a signal) */
    private static boolean memoryKill(ApplicationExitInfo e) {
        if (e.getReason() == ApplicationExitInfo.REASON_LOW_MEMORY) return true;
        String d = e.getDescription();
        return d != null && (d.contains("lmk") || d.toLowerCase(Locale.ROOT).contains("low memory"));
    }

    private static String exitName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_SIGNALED: return "a signal";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "too many resources";
            case ApplicationExitInfo.REASON_FREEZER: return "frozen in the background";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "a permission change";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "a dependency ended";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "start failed";
            default: return "reason " + reason;
        }
    }

    /** The host and the driver live in this process: leaving ends it, so the next check opens the driver picked then */
    private void leave() {
        finish();
        ui.postDelayed(() -> Process.killProcess(Process.myPid()), 300);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { leave(); }
}
