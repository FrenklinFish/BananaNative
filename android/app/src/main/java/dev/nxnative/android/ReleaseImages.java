package dev.nxnative.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The pictures of a release's notes (ReleasePage), loaded off the main thread and never needed: the page and its Update button show without them, and a picture that
 * cannot be had leaves its alt text. A picture is fetched only over https and only from a host UpdateCheck.allowedImage allows for the release's source, and the
 * same for every redirect (followed here, at most {@value #MAX_REDIRECTS}, so a redirect to another scheme or host is refused: HttpURLConnection's own following would
 * accept any https host). At most {@value #MAX_BYTES} bytes are read; the answer must decode as an image whose pixel count is sane. It is decoded down to
 * maxEdge pixels on its longer side (the page passes its width) so a large screenshot never costs more than the screen.
 * <p>
 * Caches: decoded bitmaps in memory (LruCache, 16 MB, for the life of the process), and the downloaded bytes on disk in the app's cache folder
 * (cache/release-images/&lt;sha-256 of the url&gt;; the system may clear it; the oldest files go beyond {@value #DISK_BYTES} bytes), so a page opened again, or
 * offline, shows what it showed before. Not unit-tested (network and Bitmap); the host rule is, in UpdateCheckJvmTest.
 */
final class ReleaseImages {
    private ReleaseImages() {}

    private static final String TAG = "nxupdate";
    private static final int MAX_BYTES = 8 << 20, MAX_REDIRECTS = 3, CONNECT_MS = 8000, READ_MS = 15000;
    private static final long DISK_BYTES = 40L << 20;
    private static final long MAX_PIXELS = 48_000_000L;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "nx-release-image"); t.setDaemon(true); return t; });
    private static final LruCache<String, Pic> MEMORY = new LruCache<String, Pic>(16 << 20) {
        @Override protected int sizeOf(String k, Pic p) { return p.bitmap.getByteCount(); }
    };

    /** A decoded picture and the size the file has (the bitmap may be a down-sampled copy of it) */
    static final class Pic {
        final Bitmap bitmap; final int width, height;
        Pic(Bitmap bitmap, int width, int height) { this.bitmap = bitmap; this.width = width; this.height = height; }
    }

    /** Called on the main thread with the picture, or null when it could not be had */
    interface Done { void got(Pic p); }

    /** Loads url (checked against source) for a page maxEdge pixels wide; done runs on the main thread, always, once */
    static void load(Context c, String url, UpdateCheck.Source source, int maxEdge, Done done) {
        Context app = c.getApplicationContext();
        String key = url + "@" + maxEdge;
        Pic hit = MEMORY.get(key);
        if (hit != null) { done.got(hit); return; }
        POOL.execute(() -> {
            Pic b = null;
            try { b = fetch(app, url, source, maxEdge); }
            catch (IOException | RuntimeException | OutOfMemoryError e) { Log.i(TAG, "release image not shown (" + e.getMessage() + "): " + shorten(url)); }
            if (b != null) MEMORY.put(key, b);
            final Pic got = b;
            UI.post(() -> done.got(got));
        });
    }

    private static String shorten(String u) { return u.length() > 90 ? u.substring(0, 90) + "…" : u; }

    private static File dir(Context c) { return new File(c.getCacheDir(), "release-images"); }

    private static Pic fetch(Context c, String url, UpdateCheck.Source source, int maxEdge) throws IOException {
        if (!UpdateCheck.allowedImage(url, source)) throw new IOException("host not allowed");
        File d = dir(c);
        File f = new File(d, name(url));
        byte[] data;
        if (f.isFile() && f.length() > 0 && f.length() <= MAX_BYTES) data = Files.readAllBytes(f.toPath());
        else {
            data = download(url, source);
            if (d.isDirectory() || d.mkdirs()) {
                File part = new File(d, f.getName() + ".part");
                try (FileOutputStream out = new FileOutputStream(part)) { out.write(data); }
                if (!part.renameTo(f)) part.delete();
                trim(d);
            }
        }
        Pic b = decode(data, maxEdge);
        if (b == null) { f.delete(); throw new IOException("not an image"); }
        return b;
    }

    /** The bytes of url, redirects followed by hand: every hop https and allowed, at most MAX_BYTES */
    private static byte[] download(String url, UpdateCheck.Source source) throws IOException {
        String cur = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!UpdateCheck.allowedImage(cur, source)) throw new IOException("host not allowed");
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            try {
                c.setRequestProperty("User-Agent", "BananaNative-updater");
                c.setRequestProperty("Accept", "image/*");
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without a location");
                    cur = URI.create(cur).resolve(loc.trim()).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                String type = c.getContentType();
                if (type != null && !type.toLowerCase(Locale.ROOT).startsWith("image/") && !type.toLowerCase(Locale.ROOT).startsWith("application/octet-stream"))
                    throw new IOException("not an image (" + type + ")");
                long len = c.getContentLengthLong();
                if (len > MAX_BYTES) throw new IOException("larger than " + MAX_BYTES + " bytes");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(len > 0 ? (int) len : 1 << 16);
                    byte[] buf = new byte[1 << 14]; int k; int n = 0;
                    while ((k = in.read(buf)) > 0) {
                        if ((n += k) > MAX_BYTES) throw new IOException("larger than " + MAX_BYTES + " bytes");
                        out.write(buf, 0, k);
                    }
                    return out.toByteArray();
                }
            } finally { c.disconnect(); }
        }
        throw new IOException("too many redirects");
    }

    /** data decoded to at most maxEdge on its longer side (a power-of-two sample size, the file's own size kept in the result), or null when it is not an image or has too many pixels */
    private static Pic decode(byte[] data, int maxEdge) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > MAX_PIXELS) return null;
        int sample = 1, edge = Math.max(o.outWidth, o.outHeight);
        while (edge / (sample * 2) >= Math.max(1, maxEdge)) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        return b == null ? null : new Pic(b, o.outWidth, o.outHeight);
    }

    private static String name(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format(Locale.ROOT, "%02x", h[i]));
            return b.append(".img").toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The oldest files go while the folder holds more than DISK_BYTES */
    private static void trim(File d) {
        File[] l = d.listFiles();
        if (l == null) return;
        long total = 0;
        for (File x : l) total += x.length();
        if (total <= DISK_BYTES) return;
        Arrays.sort(l, Comparator.comparingLong(File::lastModified));
        for (File x : l) {
            if (total <= DISK_BYTES) break;
            total -= x.length();
            if (!x.delete()) Log.w(TAG, "could not delete " + x);
        }
    }
}
