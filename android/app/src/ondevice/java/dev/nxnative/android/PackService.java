package dev.nxnative.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-device job (ONDEVICE.md "The service and the screen"): a foreground service of type dataSync in its own process (:pack), started by the launcher's
 * "Prepare game". It runs the job's steps in order on one worker thread with a partial wake lock, posts an ongoing notification with the step's progress and a
 * Pause action, and goes on when the app is swiped away. Every change goes to job.json, so a killed process loses at most the step in hand (the translation not even
 * that): START_STICKY brings the service back and it resumes a job that was running; the launcher's Resume does the same after a reboot or a force stop.
 * <ul>
 * <li>Pause (the notification or the launcher): the tool is interrupted (the translation stops after the shader in hand), the job is written paused, the service
 *     stops and leaves a notification with Resume.</li>
 * <li>Battery below 20 % and not charging, or thermal status SEVERE or worse: the tool is interrupted, the job waits (paused, wake lock released) and goes on by
 *     itself when charging / cooled (thermal MODERATE or better).</li>
 * <li>Android 14+'s 6 hours a day for dataSync services (onTimeout): paused like Pause, reason "timeout"; the launcher's Resume, once the app is open, goes on.</li>
 * </ul>
 * The launcher binds with a Messenger (MSG_REGISTER with replyTo) and gets MSG_STATE messages, data "job" = job.json's text, as the job changes.
 */
public final class PackService extends Service {
    static final String ACTION_START = "dev.nxnative.android.pack.START", ACTION_PAUSE = "dev.nxnative.android.pack.PAUSE";
    static final String EXTRA_JOB = "job";
    /** ACTION_PAUSE's sender, for the logs (a job paused with reason "user"): notification, launcher or adb; missing = an intent from elsewhere */
    static final String EXTRA_FROM = "from";
    static final int MSG_REGISTER = 1, MSG_UNREGISTER = 2, MSG_STATE = 3;
    private static final String TAG = "nxpack", CHANNEL = "pack";
    private static final int NOTE = 1;
    /** Starts of one step the process may not live through before the job fails (Job.start) */
    private static final int MAX_STARTS = 2;
    private static final Pattern PROGRESS = Pattern.compile("^progress:? (\\d+)(?: |/)(\\d+)(?: (\\S+))?");

    private final List<Messenger> clients = new CopyOnWriteArrayList<>();
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handle));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private NotificationManager nm;
    private PowerManager pm;
    private PowerManager.WakeLock wake;
    private PackEngine engine;
    private volatile Job job;
    private volatile Thread worker;
    private volatile String stopReason;   // user, timeout: the job pauses and the service stops
    private volatile String autoReason;   // battery, thermal: the job waits and goes on by itself
    private volatile boolean inTool;
    private int batteryPct = 100, thermal;
    private boolean charging = true;
    private long lastNote, lastSave, lastSend;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = s -> { thermal = s; policy(); };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { battery(i); policy(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        pm = getSystemService(PowerManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Preparing a game", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Progress while the app prepares a game from your dump");
        nm.createNotificationChannel(ch);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nxnative:pack");
        wake.setReferenceCounted(false);
        engine = PackEngine.create(this);
        battery(registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED));
        thermal = pm.getCurrentThermalStatus();
        pm.addThermalStatusListener(getMainExecutor(), thermalListener);
        policy();
    }

    @Override public void onDestroy() {
        unregisterReceiver(batteryReceiver);
        pm.removeThermalStatusListener(thermalListener);
        if (wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return messenger.getBinder(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            String from = intent.getStringExtra(EXTRA_FROM);
            boolean running = worker != null && worker.isAlive();
            logPause(job, (from != null ? from : "an intent without \"from\"") + (running ? "" : ", nothing running"));
            if (running) requestStop("user");
            else if (worker == null) stopSelf(startId);   // a stale notification: nothing runs here
            return START_STICKY;
        }
        String path = intent == null ? null : intent.getStringExtra(EXTRA_JOB);
        File f = path != null ? new File(path) : Job.remembered(this);
        Job j = null;
        try {
            if (f != null) j = Job.read(f);
        } catch (IOException e) {
            Log.w(TAG, "job: " + e.getMessage());
        }
        // A sticky restart (no intent: the process was killed) resumes only a job that was running; a start resumes one that is not done
        boolean go = j != null && (intent == null ? "running".equals(j.state()) : !"done".equals(j.state()) || j.apkChanged());
        if (!go && (worker == null || !worker.isAlive())) {
            // startForegroundService must be answered by startForeground even when there is nothing to do
            if (intent != null) startForeground(j);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            startForeground(job);
            return START_STICKY;
        }
        job = j;
        if (!startForeground(j)) {
            // Android refused a foreground service here (a start from the background): the job stays as it is, the launcher's Resume starts it
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        stopReason = null;
        if (intent != null) j.endedAll();   // the user's Start / Resume / Retry: earlier deaths in a step count no more (only sticky restarts add up)
        Job.remember(this, j.file);
        worker = new Thread(this::work, "nxpack-job");
        worker.start();
        return START_STICKY;
    }

    /** Android 14+'s daily dataSync limit (6 h): pause cleanly and stop within the seconds Android gives (else it ends the app) */
    @Override public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "onTimeout: the daily dataSync limit is reached; pausing");
        Job j = job;
        if (j != null) {
            j.state("paused", "timeout", null);
            save(j, true);
        }
        requestStop("timeout");
        stopForeground(STOP_FOREGROUND_DETACH);
        if (j != null) nm.notify(NOTE, note(j));
        stopSelf();
    }

    private boolean startForeground(Job j) {
        try {
            startForeground(NOTE, note(j), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            return true;
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException, or the type's time limit used up
            Log.w(TAG, "startForeground refused: " + e);
            if (j != null && !"done".equals(j.state())) {
                j.state("paused", "refused", null);
                save(j, true);
            }
            return false;
        }
    }

    /** A pause the user asked for, in logcat (nxpack) and in the current step's log (ondevice/logs/<step>.log): who asked and when */
    private void logPause(Job j, String from) {
        String line = "[pack] " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " pause asked (from " + from + ")";
        Log.i(TAG, line);
        if (j == null) return;
        int i = j.current();
        if (i < 0) return;
        File logs = new File(j.dir(), "logs");
        try {
            logs.mkdirs();
            Files.write(new File(logs, Job.STEPS[i] + ".log").toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.w(TAG, "pause log: " + e);
        }
    }

    private void requestStop(String reason) {
        stopReason = reason;
        engine.interrupt();
        synchronized (lock) { lock.notifyAll(); }
        Thread w = worker;
        // a tool that does not stop within 20 s goes with the process: every step is restartable, and the job says paused
        main.postDelayed(() -> {
            if (w != null && w.isAlive() && worker == w) {
                Log.w(TAG, "the tool did not stop within 20 s: ending the process");
                Job j = job;
                if (j != null) {
                    j.state("paused", reason, null);
                    save(j, true);
                    stopForeground(STOP_FOREGROUND_DETACH);
                    nm.notify(NOTE, note(j));
                }
                Process.killProcess(Process.myPid());
            }
        }, 20_000);
    }

    // ---- Battery and heat ----

    private void battery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale;
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** The automatic pause: below 20 % on battery, or SEVERE heat (until MODERATE or better) */
    private void policy() {
        String r = null;
        if (batteryPct < 20 && !charging) r = "battery";
        else if (thermal >= PowerManager.THERMAL_STATUS_SEVERE || ("thermal".equals(autoReason) && thermal > PowerManager.THERMAL_STATUS_MODERATE)) r = "thermal";
        String was = autoReason;
        autoReason = r;
        if (r != null && was == null) {
            Log.i(TAG, "pausing: " + r + " (battery " + batteryPct + " %" + (charging ? ", charging" : "") + ", thermal " + thermal + ")");
            if (inTool) engine.interrupt();
        }
        if (r == null && was != null) {
            Log.i(TAG, "going on: battery " + batteryPct + " %" + (charging ? ", charging" : "") + ", thermal " + thermal);
            synchronized (lock) { lock.notifyAll(); }
        }
    }

    // ---- The job ----

    /**
     * Deletes the split's kept scan results (Job.splitScan, in the app's own storage) of every game but this job's, as the job starts: one job runs at a time,
     * so no other game's split can resume from them before a job of that game starts again (after Forget, or a job whose folder went: a few hundred MB).
     * Decided from the job that runs, read from its job.json, never from the remembered path: shared storage that is briefly unreadable deletes nothing.
     */
    private void dropOtherSplitScans(Job j) {
        String keep = j.titleId();
        if (keep.isEmpty()) return;
        File[] games = j.splitScan(this).getParentFile().listFiles();
        if (games != null) for (File g : games) if (!g.getName().equals(keep)) {
            Log.i(TAG, "deleting the kept split results of another game: " + g);
            Job.deleteTree(g);
        }
    }

    private void work() {
        Job j = job;
        wake.acquire(7 * 3600_000L);
        try {
            dropOtherSplitScans(j);
            if (j.apkChanged()) {
                Log.i(TAG, "the job's shaders are from another app version: translating them again");
                j.resetChainSteps();
                Job.deleteTree(new File(j.dir(), "xlate"));
                Job.deleteTree(new File(j.dir(), "lib"));
            }
            j.state("running", null, null);
            save(j, true);
            int i;
            while ((i = j.next()) >= 0) {
                if (!waitWhileAuto(j)) break;
                String s = Job.STEPS[i];
                j.stepState(s, "running");
                int starts = j.start(s);
                save(j, true);
                if (starts > MAX_STARTS) {   // twice ended by Android inside this step (START_STICKY brought it back): stop instead of looping
                    j.ended(s);
                    j.stepState(s, "pending");
                    j.state("failed", null, Job.STEP_TEXT[i].toLowerCase(Locale.ROOT) + " was stopped by Android " + (starts - 1) + " times before it finished, most likely "
                            + "for memory (it needs up to " + (s.equals("rewrite") ? "2.7" : "1.5") + " GB). Close other apps, then tap Retry");
                    save(j, true);
                    break;
                }
                String fail = step(j, i);
                j.ended(s);
                if (stopReason != null) { j.stepState(s, "pending"); break; }
                // interrupted by the policy (which may have cleared since): wait if it still holds, then run the step again
                if (fail != null && (autoReason != null || fail.equals("interrupted"))) { j.stepState(s, "pending"); continue; }
                if (fail != null) {
                    j.stepState(s, "pending");
                    j.state("failed", null, fail);
                    save(j, true);
                    break;
                }
                j.stepState(s, "done");
                save(j, true);
                if (s.equals("split")) Job.deleteTree(j.splitScan(this));   // recorded done: the scan results are no longer needed
            }
            if (j.next() < 0) {
                j.state("done", null, null);
                // the split and the translation results are not needed once the library is in the pack
                Job.deleteTree(new File(j.dir(), "split"));
                Job.deleteTree(j.splitScan(this));
                Job.deleteTree(new File(j.dir(), "xlate"));
                Job.deleteTree(new File(j.dir(), "lib"));
            } else if (stopReason != null) j.state("paused", stopReason, null);
            save(j, true);
        } catch (Exception e) {
            Log.e(TAG, "job", e);
            j.state("failed", null, String.valueOf(e));
            save(j, true);
        } finally {
            if (wake.isHeld()) wake.release();
            main.post(this::finished);
        }
    }

    private void finished() {
        Job j = job;
        worker = null;
        publish(j, true);
        stopForeground(STOP_FOREGROUND_DETACH);
        if (j != null) nm.notify(NOTE, note(j));
        stopSelf();
    }

    /** Waits while the policy pauses the job; false when the job is to stop */
    private boolean waitWhileAuto(Job j) throws InterruptedException {
        if (autoReason == null) return stopReason == null;
        j.state("paused", autoReason, null);
        save(j, true);
        if (wake.isHeld()) wake.release();
        synchronized (lock) {
            while (autoReason != null && stopReason == null) lock.wait(30_000);
        }
        if (stopReason != null) return false;
        wake.acquire(7 * 3600_000L);
        j.state("running", null, null);
        save(j, true);
        return true;
    }

    /** Runs step i; null when it is done, else why it failed */
    private String step(Job j, int i) throws IOException {
        String s = Job.STEPS[i];
        if (s.equals("identify")) {
            DumpCheck.Result r = DumpCheck.check(j.dump(), false);
            if (!r.ok()) return r.error;
            if (!r.titleId.equals(j.titleId()) || r.version != j.version()) return "the dump changed: it is now " + r.titleId + " " + r.version;
            j.stepProgress(s, 1, 1, "identify");
            return null;
        }
        File od = j.dir(), dump = j.dump(), pack = j.pack();
        String v = String.valueOf(j.version());
        // The update's RomFS and main: folders of the dump, or (a .nup) <file>.nup:update/<version>/romfs and …/exefs/main (base/… for version 0), which the
        // tools take where they take the folders (android/NUP.md "Naming a package"); the dump itself goes to pack_android.py as it is, folder or .nup
        String upRomfs = DumpCheck.versionPath(dump, j.version(), "romfs"), upMain = DumpCheck.versionPath(dump, j.version(), "exefs/main");
        File split = new File(od, "split"), splitTmp = new File(od, "split.tmp"), splitScan = j.splitScan(this), xlate = new File(od, "xlate"), lib = new File(od, "lib");
        String tool;
        String[] argv;
        // The tools' arguments: the in-process forms (pack_android.py --in-process: the rewriter in the same Python; shader_split.py with J=1 below: no process pool;
        // vk_android_build.py with the translator and the validator as libraries, XLATE / SPIRV_VAL below; the app's process must not fork, ONDEVICE.md "No subprocesses").
        // The split is resumable by RomFS file: its scan results are kept in the app's own storage (Job.splitScan, DM_SPLIT_RESUME, shader_split.py ScanCache) and a rerun scans only the files
        // that have none; it writes its outputs into split.tmp/ at its end, which then becomes split/.
        // The shaders step is resumable by shader: each result is a file in xlate/ (DMSH_VK_RESULTS) and a rerun skips the shaders that have one; it writes the
        // library (lib/v-<DMSH_VK_CHAIN>/) from the results at its end. The pack step copies the library into the pack (--shader-cache)
        switch (s) {
            case "rewrite":
                tool = "scripts/pack_android.py";
                argv = new String[] {dump.getPath(), "--version", v, "--out", pack.getPath(), "--code-only", "-j", "1", "--in-process"};
                break;
            case "split":
                Job.deleteTree(splitTmp);   // only outputs a stopped run may have half written: the split writes them all at its end; what it kept is in Job.splitScan
                splitTmp.mkdirs();
                tool = "dm/gfx/tools/shader_split.py";
                argv = new String[] {upRomfs, upMain, new File(splitTmp, "pkg").getPath(),
                        new File(splitTmp, "shader_map.tsv").getPath(), new File(splitTmp, "shader_facts.tsv").getPath(), new File(splitTmp, "shader_pairs.tsv").getPath(),
                        new File(splitTmp, "pso_pairs.tsv").getPath()};
                break;
            case "shaders": {
                if (!new File(split, "pkg").isDirectory()) {   // the split went (a finished job deletes it; an APK update then translates again): split first
                    j.stepState("split", "pending");
                    return "interrupted";
                }
                String bad = splitMatchesProfile(j, split);
                if (bad != null) return bad;
                tool = "dm/gfx/tools/vk_android_build.py";
                argv = new String[] {"--target", "vulkan", new File(split, "pkg").getPath(), lib.getPath()};
                break;
            }
            case "pack":
                tool = "scripts/pack_android.py";
                argv = new String[] {dump.getPath(), "--version", v, "--out", pack.getPath(), "--shader-cache", j.shaderCache().getPath(), "--keep-code", "-j", "1", "--in-process"};
                break;
            default:
                return "unknown step " + s;
        }
        File logs = new File(od, "logs");
        logs.mkdirs();
        File log = new File(logs, s + ".log");
        String head = "== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + Prefs.buildTag(this) + ", engine " + BuildConfig.NX_PACK_ENGINE + ": "
                + tool + " " + String.join(" ", argv) + "\n";
        Files.write(log.toPath(), head.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        if (s.equals("shaders")) {   // a failed shader's kept result would fail every Retry the same way: those shaders are translated again (ShaderResults)
            List<ShaderResults.Failed> again = ShaderResults.dropFailed(xlate);
            StringBuilder b = new StringBuilder();
            for (ShaderResults.Failed f : again) b.append("[retry] translating again, it failed before: ").append(f).append('\n');
            if (b.length() > 0) Files.write(log.toPath(), b.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        }
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        PackEngine.setenv("DM_GAME", j.titleId());
        PackEngine.setenv("DM_REWRITE_J", "1");
        PackEngine.setenv("NX_PACK_JOBS", String.valueOf(workers));
        PackEngine.setenv("J", s.equals("split") ? "1" : String.valueOf(workers));   // the split's J is its process pool: 1 = in this process
        PackEngine.setenv("DM_SPLIT_RESUME", splitScan.getPath());   // the split's kept scan results (only shader_split.py reads it)
        PackEngine.setenv("DM_PACKER_COMMIT", BuildConfig.NX_COMMIT);   // pack.tsv's packer: no repository on the phone to ask
        PackEngine.setenv("NX_PACK_WORK", od.getPath());
        PackEngine.setenv("NX_PACK_VERSION", v);
        PackEngine.setenv("TMPDIR", getCacheDir().getPath());
        // vk_android_build.py: the per-shader results (kept until the job is done: a rerun resumes from them), the translator and SPIRV-Tools from the APK's lib/
        // (bare names: the linker finds them in the APK, as System.loadLibrary does; ctypes loads them in this process)
        PackEngine.setenv("DMSH_VK_RESULTS", xlate.getPath());
        // the delta update: entries of the pack's older libraries that the profile's manifest has unchanged are kept, only the rest translated (android/SHADERS.md#delta-updates)
        PackEngine.setenv("DMSH_VK_DELTA_FROM", new File(pack, "shaders").getPath());
        PackEngine.setenv("XLATE", "libdmsh_xlate.so");
        PackEngine.setenv("SPIRV_VAL", "libSPIRV-Tools-shared.so");
        PackEngine.setenv("DMSH_VK_REQUIRE_ALL", "1");   // a library without every shader is a failed step, never a finished one
        PackEngine.setenv("DMSH_VK_XLATE_J", "1");       // one translator call at a time (one thread was faster on the phone); the env file below can change it
        // Tests: ondevice/env.txt, KEY=VALUE lines, overrides any of the above for the tools (e.g. DMSH_VK_XLATE_J=6 to compare the translator's threads)
        File envFile = new File(od, "env.txt");
        if (envFile.isFile()) for (String l : Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8)) {
            int eq = l.indexOf('=');
            if (eq > 0 && !l.startsWith("#")) { PackEngine.setenv(l.substring(0, eq).trim(), l.substring(eq + 1).trim()); Files.write(log.toPath(), ("[env.txt] " + l.trim() + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND); }
        }
        Tail tail = new Tail(j, s, log);
        tail.start();
        int code;
        inTool = true;
        try {
            if (autoReason != null || stopReason != null) return "interrupted";
            code = engine.run(tool, argv, log.getPath());
        } finally {
            inTool = false;
            tail.finish();
        }
        Log.i(TAG, s + ": exit " + code);
        if (code == PackEngine.INTERRUPTED) return "interrupted";
        if (code != 0) return s + " stopped with code " + code + (tail.lastRed != null ? ": " + tail.lastRed : "") + " (log: " + log + ")";
        if (s.equals("split")) {
            Job.syncTree(splitTmp);   // on the disk before the rename, so a reboot after the step is recorded done cannot leave cut tables in split/
            Job.deleteTree(split);
            Files.move(splitTmp.toPath(), split.toPath(), StandardCopyOption.ATOMIC_MOVE);
            // the scan results go only once job.json records the split done (work()): a kill before that resumes from them instead of scanning everything again
        }
        long t = j.stepTotal(i);
        j.stepProgress(s, t > 0 ? t : 1, t > 0 ? t : 1, "");
        return null;
    }

    /**
     * null when the split found the shaders the app's profile lists (its shader_map.tsv byte for byte, as a Mac split of the same dump does), else what to do.
     * vk_android_build.py builds the keys of the profile's map from the split's package: another map means a dump with other shaders (modified, or incomplete).
     */
    private String splitMatchesProfile(Job j, File split) {
        if (engine.simulated()) return null;
        File have = new File(split, "shader_map.tsv");
        try {
            File want = new File(PackNative.repo(), "dm/games/" + j.titleId() + "/shader_map.tsv");
            if (java.util.Arrays.equals(Files.readAllBytes(want.toPath()), Files.readAllBytes(have.toPath()))) return null;
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            return "cannot compare the dump's shaders with the app's list: " + e.getMessage();
        }
        Job.deleteTree(split);   // a Retry splits again, from the start
        Job.deleteTree(j.splitScan(this));
        j.stepState("split", "pending");
        return "the dump's shaders are not the ones this app knows for " + j.name() + " (" + Profile.versionName(j.version()) + ", " + have.getName()
                + " differs). The dump may be incomplete or modified: dump the game again, then Retry";
    }

    /** Reads the step's log as it grows: progress lines into the job, the last [RED] line for a failure's message */
    private final class Tail extends Thread {
        private final Job j;
        private final String step;
        private final File log;
        private volatile boolean done;
        private long pos;
        private final StringBuilder partial = new StringBuilder();
        volatile String lastRed;

        Tail(Job j, String step, File log) {
            super("nxpack-tail");
            this.j = j;
            this.step = step;
            this.log = log;
            pos = log.length();
        }

        @Override public void run() {
            while (!done) {
                read();
                SystemClock.sleep(300);
            }
            read();
        }

        void finish() {
            done = true;
            try {
                join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void read() {
            try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
                long len = f.length();
                if (len <= pos) return;
                f.seek(pos);
                byte[] b = new byte[(int) Math.min(len - pos, 1 << 20)];
                f.readFully(b);
                pos += b.length;
                partial.append(new String(b, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }
            int nl;
            boolean changed = false;
            while ((nl = partial.indexOf("\n")) >= 0) {
                String line = partial.substring(0, nl).trim();
                partial.delete(0, nl + 1);
                Matcher m = PROGRESS.matcher(line);
                if (m.find()) {
                    j.stepProgress(step, Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), m.group(3));
                    changed = true;
                } else if (line.startsWith("[RED] ")) lastRed = line.substring(6);
            }
            if (changed) save(j, false);
        }
    }

    // ---- State out: job.json, the notification, the launcher ----

    /** Writes job.json (at most every 2 s unless forced) and tells the notification and the launcher */
    private void save(Job j, boolean force) {
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (force || now - lastSave >= 2000) {
                lastSave = now;
                try {
                    j.save();
                } catch (IOException e) {
                    Log.e(TAG, "job.json: " + e);
                }
            }
        }
        main.post(() -> publish(j, force));
    }

    private void publish(Job j, boolean force) {
        if (j == null) return;
        long now = SystemClock.elapsedRealtime();
        if (force || now - lastSend >= 250) {
            lastSend = now;
            Message m = Message.obtain(null, MSG_STATE);
            Bundle b = new Bundle();
            b.putString("job", j.json());
            b.putString("file", j.file.getPath());
            b.putBoolean("alive", worker != null);
            m.setData(b);
            for (Messenger c : clients) {
                try {
                    c.send(Message.obtain(m));
                } catch (RemoteException e) {
                    clients.remove(c);
                }
            }
        }
        if (worker != null && (force || now - lastNote >= 1000)) {
            lastNote = now;
            nm.notify(NOTE, note(j));
        }
    }

    private boolean handle(Message m) {
        switch (m.what) {
            case MSG_REGISTER:
                if (m.replyTo != null) {
                    clients.add(m.replyTo);
                    if (job != null) publish(job, true);
                }
                return true;
            case MSG_UNREGISTER:
                clients.remove(m.replyTo);
                return true;
            default:
                return false;
        }
    }

    private Notification note(Job j) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_banana_mono).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        String name = j == null ? "a game" : j.name();
        String sim = engine.simulated() ? " (simulated)" : "";
        if (j == null) return b.setContentTitle("Preparing " + name + sim).setContentText("Starting…").build();
        String state = j.state();
        int pct = (int) Math.round(j.overall() * 100);
        int i = j.current();
        if (state.equals("done")) return b.setContentTitle(name + " is ready to play" + sim).setContentText("Open Banana Native and tap Play.").setAutoCancel(true).build();
        boolean running = state.equals("running") && worker != null;
        boolean waiting = state.equals("paused") && worker != null;   // the policy's pause: it goes on by itself
        b.setContentTitle((running ? "Preparing " : waiting ? "Waiting: " : state.equals("failed") ? "Could not prepare " : "Paused: ") + name + sim);
        Intent pause = new Intent(this, PackService.class).setAction(ACTION_PAUSE).putExtra(EXTRA_FROM, "notification");
        Notification.Action pauseAction = new Notification.Action.Builder(null, "Pause",
                PendingIntent.getService(this, 1, pause, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
        if (waiting) {
            return b.setContentText(Job.reasonText(j.reason())).setStyle(new Notification.BigTextStyle().bigText(j.stepText() + " · " + pct + " %\n" + Job.reasonText(j.reason())))
                    .setSubText("Step " + (i + 1) + " of " + Job.STEPS.length + " · " + pct + " %").setOngoing(true).addAction(pauseAction).build();
        }
        if (running) {
            long d = j.stepDone(i), t = j.stepTotal(i);
            if (t > 1) b.setProgress((int) Math.min(t, Integer.MAX_VALUE), (int) Math.min(d, t), false);
            else b.setProgress(100, pct, false);
            b.setContentText(j.stepText()).setSubText("Step " + (i + 1) + " of " + Job.STEPS.length + " · " + pct + " %").setOngoing(true).addAction(pauseAction);
        } else {
            String why = state.equals("failed") ? j.error() : Job.reasonText(j.reason());
            b.setContentText(j.stepText() + " · " + pct + " %").setStyle(new Notification.BigTextStyle().bigText(j.stepText() + " · " + pct + " %\n" + why)).setOngoing(false);
            Intent resume = new Intent(this, PackService.class).setAction(ACTION_START).putExtra(EXTRA_JOB, j.file.getPath());
            b.addAction(new Notification.Action.Builder(null, state.equals("failed") ? "Retry" : "Resume",
                    PendingIntent.getForegroundService(this, 2, resume, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        return b.build();
    }
}
