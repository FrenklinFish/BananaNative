package dev.nxnative.android;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What runs a step's tool: the native engine (PackNative, libnxpack.so: the embedded CPython running the repository's tools, which load the translator and SPIRV-Tools from the APK through ctypes)
 * or FakeEngine (-PnxPackEngine=fake). The service calls run() on its worker thread, one tool at a time, and interrupt() from any thread.
 */
interface PackEngine {
    /** PackNative.packRun's contract (see there) */
    int run(String tool, String[] argv, String logPath);

    /** PackNative.packInterrupt's contract (see there) */
    void interrupt();

    /** A simulated engine: its pack is a placeholder that is never played */
    boolean simulated();

    /** Exit code of a tool stopped by interrupt() (128 + SIGINT, as a shell reports it) */
    int INTERRUPTED = 130;

    /** The tools' environment as the service set it: the native engine passes it with each run (Python reads the process environment only once, at its start) */
    Map<String, String> ENV = new LinkedHashMap<>();

    static PackEngine create(Context c) {
        if ("fake".equals(BuildConfig.NX_PACK_ENGINE)) return new FakeEngine();
        PackNative.context(c);
        return new PackEngine() {
            public int run(String tool, String[] argv, String logPath) { return PackNative.packRun(tool, argv, logPath); }
            public void interrupt() { PackNative.packInterrupt(); }
            public boolean simulated() { return false; }
        };
    }

    /** Environment the tools read, set before a run: in ENV (the native engine's runs) and in the process (Os.setenv: native code's getenv) */
    static void setenv(String key, String value) {
        synchronized (ENV) { ENV.put(key, value); }
        try {
            Os.setenv(key, value, true);
        } catch (ErrnoException e) {
            throw new IllegalStateException(e);
        }
    }
}

/**
 * The Java ↔ native seam (libnxpack.so with libpython3.14.so, loaded in the :pack process only, never with libnxhost.so; android/ondevice/dm_pack.h). Contract:
 * <ul>
 * <li>{@code packRun(tool, argv, logPath)} runs one tool to its end on the calling thread and returns its exit code. {@code tool} is a repository path
 *     ({@code scripts/pack_android.py}, {@code dm/gfx/tools/shader_split.py}, ...) resolved against the tools unpacked from the APK; a Python tool runs in the
 *     embedded CPython by {@code runpy.run_path(tool)} with {@code sys.argv = [tool] + argv}, {@code SystemExit}'s code is the exit code, an uncaught exception
 *     is 1 (its traceback in the log). Its {@code sys.stdout} and {@code sys.stderr} are appended to {@code logPath}, which the caller creates. The environment
 *     is {@link PackEngine#ENV} for that run. The tool reports progress as lines {@code progress <done> <total> <stage>} (or shader_split.py's
 *     {@code progress: <done>/<total>}); the service reads them from the log as it grows. One call at a time per process.</li>
 * <li>The first run in the process unpacks the APK's {@code assets/pack/python.zip} and {@code tools.zip} to {@code filesDir/pack/<version>/} (python/, repo/;
 *     {@code version.txt} names the version, a new APK's zips go to a new folder by rename and the old ones are deleted), then starts Python with that home.
 *     A failure there is written to the log and the run returns 1.</li>
 * <li>{@code packInterrupt()} asks the running tool to stop: a {@code KeyboardInterrupt} raised in its thread at its next bytecode
 *     ({@code PyThreadState_SetAsyncExc}; {@code PyErr_SetInterrupt} would act only on Python's main thread with Python's SIGINT handler, and the packer has
 *     neither). {@code packRun} then returns {@link PackEngine#INTERRUPTED}. Any thread (it hands the call to a thread of its own, since taking the GIL can wait
 *     for a long C call); nothing when no tool runs. A tool that does not return within 20 s is ended with its process by the service: every step is
 *     restartable.</li>
 * </ul>
 */
final class PackNative {
    private static final String TAG = "nxpack";
    private static Context app;
    private static File root;          // filesDir/pack/<version>, once unpacked and Python started
    private static volatile boolean loaded;

    private PackNative() {}

    static synchronized void context(Context c) { if (app == null) app = c.getApplicationContext(); }

    static native int init0(String pythonHome);

    static native int run0(String tool, String[] args, String[] env, String logPath);

    static native int interrupt0();

    static int packRun(String tool, String[] argv, String logPath) {
        File r;
        try {
            r = start();
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            Log.e(TAG, "the packer did not start", e);
            log(logPath, "[RED] the packer did not start: " + e + "\n");
            return 1;
        }
        String[] env;
        synchronized (PackEngine.ENV) {
            env = PackEngine.ENV.entrySet().stream().map(x -> x.getKey() + "=" + x.getValue()).toArray(String[]::new);
        }
        return run0(new File(new File(r, "repo"), tool).getPath(), argv, env, logPath);
    }

    /** The repository's tools as unpacked from the APK (starts the packer when it has not started in this process) */
    static File repo() throws IOException { return new File(start(), "repo"); }

    static void packInterrupt() {
        if (!loaded) return;
        Thread t = new Thread(() -> Log.i(TAG, "interrupt: " + (interrupt0() > 0 ? "sent" : "no tool running")), "nxpack-interrupt");
        t.setDaemon(true);
        t.start();
    }

    /** Unpacks the Python home and the tools when this APK's are not there yet, loads the library and starts Python (once per process) */
    private static synchronized File start() throws IOException {
        if (root != null) return root;
        if (app == null) throw new IllegalStateException("PackNative.context not set");
        String version;
        try (BufferedReader b = new BufferedReader(new InputStreamReader(app.getAssets().open("pack/version.txt"), StandardCharsets.UTF_8))) {
            version = b.readLine().trim();
        }
        if (!version.matches("[0-9a-f]{16}")) throw new IOException("assets/pack/version.txt: " + version);
        File base = new File(app.getFilesDir(), "pack"), dir = new File(base, version);
        if (!dir.isDirectory()) {
            long t0 = System.nanoTime();
            File tmp = new File(base, version + ".tmp");
            Job.deleteTree(tmp);
            unzip("pack/python.zip", new File(tmp, "python"));
            unzip("pack/tools.zip", new File(tmp, "repo"));
            if (!tmp.renameTo(dir)) throw new IOException("cannot rename " + tmp + " to " + dir);
            Log.i(TAG, "unpacked the packer " + version + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
        File[] old = base.listFiles();
        if (old != null) for (File f : old) if (!f.getName().equals(version)) Job.deleteTree(f);
        if (!loaded) {
            System.loadLibrary("nxpack");   // its dependency libpython3.14.so comes from the APK too
            loaded = true;
        }
        int rc = init0(new File(dir, "python").getPath());
        if (rc != 0) throw new IOException("Python did not start (home " + new File(dir, "python") + ", dm_pack_init " + rc + "; logcat has its reason)");
        root = dir;
        return root;
    }

    private static void unzip(String asset, File to) throws IOException {
        String top = to.getCanonicalPath() + File.separator;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = app.getAssets().open(asset); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File f = new File(to, e.getName());
                if (!f.getCanonicalPath().startsWith(top)) throw new IOException(asset + ": entry outside its folder: " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
    }

    private static void log(String path, String text) {
        try {
            Files.write(new File(path).toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.w(TAG, "log " + path + ": " + e);
        }
    }
}
