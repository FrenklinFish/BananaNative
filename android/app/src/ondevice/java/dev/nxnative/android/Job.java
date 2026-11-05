package dev.nxnative.android;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * One on-device job (ONDEVICE.md "The job model"): <work>/ondevice/job.json, the manifest the service writes as it goes and the launcher reads when the service is
 * not running. Every write goes to job.json.tmp, is synced, and is renamed over job.json (FUSE has no RENAME_EXCHANGE; a plain rename of a file is atomic), so a
 * reader or a killed process sees the old or the new file, never half of one.
 *
 * <pre>
 * { "format": 1, "title_id", "name", "version", "dump", "work", "pack": the pack folder the job writes,
 *   "apk": { "engine", "dmsh_chain", "dmsh_vk_chain", "xlate_source", "version_code" }: what the outputs depend on (a change restarts the translation),
 *   "state": "pending" | "running" | "paused" | "done" | "failed", "reason": why paused (user, battery, thermal, timeout) or "", "error": why failed,
 *   "step": the current step, "steps": { "&lt;step&gt;": { "state": "pending" | "running" | "done", "done", "total", "stage" } }, "updated": ms }
 * </pre>
 * A "running" job whose service is gone (the process was killed, the phone rebooted) is resumed by the service (START_STICKY) or the launcher's Resume.
 */
final class Job {
    static final int FORMAT = 2;   // 2: the shaders step writes the library too (vk_android_build.py's result cache); 1 had a library step of its own
    static final String[] STEPS = {"identify", "rewrite", "split", "shaders", "pack"};
    static final String[] STEP_TEXT = {"Checking the dump", "Rewriting the game code", "Finding the shaders", "Translating shaders", "Finishing the pack"};
    /** Each step's share of the progress bar, roughly its time on the phone (ONDEVICE.md "Time and power"); the shaders' share is counted by shader */
    private static final int[] WEIGHT = {1, 5, 12, 78, 4};
    static final int SHADERS = 3;
    /** The steps whose outputs depend on the APK's shader chain: a job made by another APK starts them over */
    static final int FIRST_CHAIN_STEP = SHADERS;

    final File file;
    private JSONObject o;

    private Job(File file, JSONObject o) {
        this.file = file;
        this.o = o;
    }

    static File fileOf(File work) { return new File(new File(work, "ondevice"), "job.json"); }

    /**
     * A new job for a checked dump; not written yet. pack: where its pack goes, the app's packs/&lt;title ID&gt; (AppStorage.pack); the working files stay in the
     * dump's work folder (&lt;dump&gt;/../&lt;id&gt;-work/ondevice)
     */
    static Job create(DumpCheck.Result r, File appPack) throws JSONException {
        JSONObject o = new JSONObject().put("format", FORMAT).put("title_id", r.titleId).put("name", Profile.of(r.titleId) != null ? Profile.of(r.titleId).name : r.titleId).put("version", r.version)
                .put("dump", r.dump.getPath()).put("work", r.work.getPath()).put("apk", apk()).put("state", "pending").put("reason", "").put("error", "")
                .put("step", STEPS[0]).put("created", System.currentTimeMillis());
        // the fake engine never writes over a real pack: its pack is a placeholder in ondevice/
        File pack = "fake".equals(BuildConfig.NX_PACK_ENGINE) ? new File(r.work, "ondevice/fake-pack") : appPack;
        o.put("pack", pack.getPath());
        o.put("own_pack", isNew(pack));
        JSONObject steps = new JSONObject();
        for (String s : STEPS) steps.put(s, new JSONObject().put("state", "pending").put("done", 0).put("total", 0).put("stage", ""));
        o.put("steps", steps);
        return new Job(fileOf(r.work), o);
    }

    /**
     * A job for a pack that is already there (PackCheck): ready (every step done, its pack that folder), or with what does not fit redone in that folder: the code
     * (rewrite and pack; the shader library in the pack is kept), the shaders (split, shaders, pack; the code is kept), or both. The simulated engine never writes
     * into a real pack: its rebuild goes to ondevice/fake-pack.
     */
    static Job adopt(DumpCheck.Result r, File packDir, boolean code, boolean shaders) throws JSONException {
        Job j = create(r, packDir);
        boolean fake = "fake".equals(BuildConfig.NX_PACK_ENGINE);
        boolean ready = !code && !shaders;
        if (ready || !fake) j.o.put("pack", packDir.getPath());
        for (String s : STEPS) {
            boolean shaderStep = s.equals("split") || s.equals("shaders");
            if (ready || s.equals("identify") || !code && s.equals("rewrite") || !shaders && shaderStep) j.stepState(s, "done");
        }
        // the code only: the pack step takes the library already in the pack (pack_android.py --shader-cache <pack>/shaders: current there, so it stays as it is)
        if (!shaders && !ready && !fake) j.o.put("shader_cache", new File(packDir, "shaders").getPath());
        if (ready) j.o.put("state", "done");
        j.o.put("adopted", packDir.getPath());
        return j;
    }

    static Job read(File f) throws IOException {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (o.optInt("format") != FORMAT) throw new IOException(f + ": job format " + o.optInt("format") + ", this app writes " + FORMAT);
            return new Job(f, o);
        } catch (JSONException e) {
            throw new IOException(f + ": " + e.getMessage());
        }
    }

    /** A job as the service sends it (the same JSON) */
    static Job of(File f, String json) {
        try {
            return new Job(f, new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    /** What this APK's outputs depend on (ONDEVICE.md "Versions") */
    static JSONObject apk() {
        try {
            return new JSONObject().put("engine", BuildConfig.NX_PACK_ENGINE).put("dmsh_chain", BuildConfig.NX_DMSH_CHAIN).put("dmsh_vk_chain", BuildConfig.NX_DMSH_VK_CHAIN)
                    .put("xlate_source", BuildConfig.NX_XLATE_SOURCE).put("version_code", BuildConfig.VERSION_CODE);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the job's translated shaders came from another chain (or engine) than this APK's: then they are translated again */
    synchronized boolean apkChanged() {
        JSONObject a = o.optJSONObject("apk"), b = apk();
        if (a == null) return true;
        for (String k : new String[] {"engine", "dmsh_chain", "dmsh_vk_chain", "xlate_source"}) if (!b.optString(k).equals(a.optString(k))) return true;
        return false;
    }

    /** Starts the chain's steps over with this APK's versions; the caller deletes their outputs */
    synchronized void resetChainSteps() throws JSONException {
        // the split is the dump's and stays valid, but a finished job deleted it: then it is made again too
        int from = Outputs.complete(new File(dir(), "split")) ? FIRST_CHAIN_STEP : FIRST_CHAIN_STEP - 1;
        for (int i = from; i < STEPS.length; i++) o.getJSONObject("steps").put(STEPS[i], new JSONObject().put("state", "pending").put("done", 0).put("total", 0).put("stage", ""));
        o.remove("shader_cache");
        o.put("apk", apk());
        if ("done".equals(state())) o.put("state", "paused").put("reason", "apk");
    }

    synchronized void save() throws IOException {
        try {
            o.put("updated", System.currentTimeMillis());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    synchronized String json() { return o.toString(); }

    synchronized String state() { return o.optString("state"); }
    synchronized String reason() { return o.optString("reason"); }
    synchronized String error() { return o.optString("error"); }
    synchronized String name() { return o.optString("name"); }
    synchronized String titleId() { return o.optString("title_id"); }
    synchronized long version() { return o.optLong("version"); }
    synchronized long updated() { return o.optLong("updated"); }
    synchronized long created() { return o.optLong("created"); }
    synchronized File dump() { return new File(o.optString("dump")); }
    synchronized File work() { return new File(o.optString("work")); }
    synchronized File pack() { return new File(o.optString("pack")); }
    /** The folder of shader libraries (v-<V>/) the pack step copies from: the job's own build (ondevice/lib), or the pack's shaders/ when only the code is redone */
    synchronized File shaderCache() { String s = o.optString("shader_cache", ""); return s.isEmpty() ? new File(dir(), "lib") : new File(s); }

    /** A folder the job may call its own (and so offer to delete): one that does not exist yet, or is empty, when the job is made */
    static boolean isNew(File dir) {
        String[] l = dir.list();
        return !dir.exists() || l != null && l.length == 0;
    }

    /** Whether the job made its pack folder (own_pack): a folder that was there before, a Mac pack say, is the user's and is never deleted by the app */
    synchronized boolean ownsPack() { return o.optBoolean("own_pack", false) && adopted().isEmpty(); }

    /** Another pack folder for a new job (Ondevice's ondevice_pack: agents' test runs, never the app's packs/&lt;id&gt; or a pack beside the dump) */
    synchronized void pack(File dir) {
        try {
            o.put("own_pack", isNew(dir));
            o.put("pack", dir.getPath());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
    File dir() { return file.getParentFile(); }
    /** The pack folder this job took over as it was (Job.adopt), "" when the job makes its own */
    synchronized String adopted() { return o.optString("adopted", ""); }

    synchronized void state(String state, String reason, String error) {
        try {
            o.put("state", state).put("reason", reason == null ? "" : reason).put("error", error == null ? "" : error);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONObject step(String s) {
        JSONObject steps = o.optJSONObject("steps");
        JSONObject j = steps == null ? null : steps.optJSONObject(s);
        return j != null ? j : new JSONObject();
    }

    synchronized String stepState(String s) { return step(s).optString("state", "pending"); }

    synchronized void stepState(String s, String state) {
        try {
            step0(s).put("state", state);
            if (state.equals("running")) o.put("step", s);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One more start of step s (its "starts" in job.json, saved with the step's running state): a start the process did not live through to the step's end
     * (Android ended it, most likely for memory: the rewrite peaks at 2.7 GB) stays counted, so a sticky restart cannot loop on it. → the starts so far
     */
    synchronized int start(String s) {
        try {
            JSONObject j = step0(s);
            int n = j.optInt("starts", 0) + 1;
            j.put("starts", n);
            return n;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The step ended in this process (done, failed, paused): its unfinished starts are forgotten. All steps' when the user starts the job again (Retry, Resume) */
    synchronized void ended(String s) { try { step0(s).put("starts", 0); } catch (JSONException e) { throw new IllegalStateException(e); } }

    synchronized void endedAll() { for (String s : STEPS) ended(s); }

    /** A step's count from the tool's progress lines */
    synchronized void stepProgress(String s, long done, long total, String stage) {
        try {
            step0(s).put("done", done).put("total", total).put("stage", stage == null ? "" : stage);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONObject step0(String s) throws JSONException {
        JSONObject steps = o.optJSONObject("steps");
        if (steps == null) o.put("steps", steps = new JSONObject());
        JSONObject j = steps.optJSONObject(s);
        if (j == null) steps.put(s, j = new JSONObject().put("state", "pending").put("done", 0).put("total", 0).put("stage", ""));
        return j;
    }

    /** The first step not done, -1 when all are */
    synchronized int next() {
        for (int i = 0; i < STEPS.length; i++) if (!"done".equals(stepState(STEPS[i]))) return i;
        return -1;
    }

    /** The step to show: the running one, else the next to run, else the last */
    synchronized int current() {
        int i = java.util.Arrays.asList(STEPS).indexOf(o.optString("step"));
        int n = next();
        if (n < 0) return STEPS.length - 1;
        return i >= 0 && "running".equals(stepState(STEPS[i])) ? i : n;
    }

    /** 0..1 over all steps, each by its weight and its own count */
    synchronized double overall() {
        double sum = 0, all = 0;
        for (int i = 0; i < STEPS.length; i++) {
            all += WEIGHT[i];
            JSONObject j = step(STEPS[i]);
            if ("done".equals(j.optString("state"))) sum += WEIGHT[i];
            else if (j.optLong("total") > 0) sum += WEIGHT[i] * Math.min(1.0, (double) j.optLong("done") / j.optLong("total"));
        }
        return sum / all;
    }

    synchronized long stepDone(int i) { return step(STEPS[i]).optLong("done"); }
    synchronized long stepTotal(int i) { return step(STEPS[i]).optLong("total"); }

    /** The shaders translated so far and their total (the game's profile's shader map, Profile.shaderTotal, until the tool says its own) */
    synchronized long shadersDone() { return "done".equals(stepState(STEPS[SHADERS])) ? shadersTotal() : stepDone(SHADERS); }
    // the tool's total once it reported one: the shaders it translates (a delta update keeps the others, android/SHADERS.md#delta-updates), else the profile's count
    synchronized long shadersTotal() { long t = stepTotal(SHADERS); Profile p = Profile.of(titleId()); return t > 1 || !step(STEPS[SHADERS]).optString("stage").isEmpty() ? t : p != null ? p.shaderTotal : 0; }

    /** "Translating shaders: 12,345 of 22,852" during the shaders step ("Writing the shader library" at its end), else the step and its count when it has one */
    synchronized String stepText() {
        int i = current();
        if (i == SHADERS) {
            String stage = step(STEPS[i]).optString("stage");
            if (stage.equals("library") || stage.equals("done")) return "Writing the shader library";
            if (stage.equals("check")) return "Checking the shader library";
            return String.format(Locale.getDefault(), "%s: %,d of %,d", STEP_TEXT[i], shadersDone(), shadersTotal());
        }
        long d = stepDone(i), t = stepTotal(i);
        return STEP_TEXT[i] + (t > 1 ? String.format(Locale.getDefault(), " %,d / %,d", d, t) : t == 1 || d == 0 ? "" : String.format(Locale.getDefault(), " %,d", d));
    }

    /** One line for the screen and the notification: what the job is doing or why it stopped */
    synchronized String statusText(boolean serviceAlive) {
        String s = state();
        String step = "step " + (current() + 1) + " of " + STEPS.length;
        switch (s) {
            case "done": return "Ready to play.";
            case "failed": return "Stopped at " + STEP_TEXT[current()].toLowerCase(Locale.ROOT) + ": " + error().replaceAll("[.\\s]+$", "") + ". Tap Retry to go on.";
            case "pending": return "Not started yet.";
            case "paused": return "Paused: " + reasonText(reason()) + ". " + stepText() + ".";
            case "running": return serviceAlive ? stepText() + " (" + step + ")."
                    : "Interrupted at " + stepText() + ". Tap Resume to go on.";
            default: return s;
        }
    }

    static String reasonText(String r) {
        switch (r) {
            case "user": return "by you";
            case "battery": return "battery below 20 %: plug in the charger to go on";
            case "thermal": return "the phone is too hot: it goes on when it has cooled";
            case "timeout": return "Android's daily limit for background work: open the app and tap Resume";
            case "refused": return "Android did not let it start in the background: open the app and tap Resume";
            case "apk": return "the app was updated with other shader tools: the shaders are translated again";
            default: return r.isEmpty() ? "stopped" : r;
        }
    }

    /**
     * The split's kept scan results (shader_split.py DM_SPLIT_RESUME), kept across pauses, kills and reboots until the job records the split done. In the app's
     * own storage, not beside the job on shared storage: no other app can put files there for the split to read, and its syncs do not go through FUSE.
     * One folder a game; the files carry a digest of their inputs, so another job of the same game starts it over.
     */
    synchronized File splitScan(Context c) {
        return new File(c.getNoBackupFilesDir(), "split.scan/" + titleId());
    }

    /** Deletes a folder of the job's own outputs: ondevice/ paths, and the pack the job made itself (Ondevice's Manage, asked first; never the dump or the saves) */
    static void deleteTree(File f) { Outputs.deleteTree(f); }

    /** The job the launcher shows (the last started), remembered across processes */
    static File remembered(Context c) {
        String p = Prefs.open(c, "ondevice").getString("job", null);
        return p == null ? null : new File(p);
    }

    static void remember(Context c, File jobFile) {
        Prefs.open(c, "ondevice").edit().putString("job", jobFile == null ? null : jobFile.getPath()).commit();
    }
}
