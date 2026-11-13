package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The launcher's pack code check without Android (Forgejo #214: a whole module decoded into the heap per game card, at every start). Compiled with Nup, Zstd and Verdict.
 * Modes (the first argument):
 * <ul>
 * <li>{@code verdict}: Verdict, the verdict kept across launches</li>
 * <li>{@code equal <file.nup>}: every file of every package: Nup.hashTo gives the digest of Nup.read(node, -1) (and the file's SHA-256 is checked)</li>
 * <li>{@code damaged <file.nup> <package selector> <path>}: a copy with 100 KB of the file's extent flipped: hashTo refuses it with an IOException naming the file</li>
 * <li>{@code stream <file.nup> <package selector> <path> <sha256 hex>}: run under a small -Xmx: hashTo gives the digest, its peak heap is printed, and the
 *     whole-buffer Nup.read of the same file runs out of memory at that heap</li>
 * <li>{@code collapse <a.nup> <b.nup>}: under a small -Xmx, two list entries of one big game: BuildCheck.gameKey (what Game.key gives GameList.collapse) is told from the TOC and
 *     the first extent pieces, equal for both, the list collapses to one entry, and Nup.read(main, 0x60) fits where the whole extent (larger than the heap) does not</li>
 * <li>{@code frames <folder>}: every {@code <name>.zst} against {@code <name>.raw}: Zstd.decodeTo (frames with a window as small as 1 KiB slide many times) equals
 *     Zstd.decode; a window above the limit is refused before any output</li>
 * </ul>
 * Prints "ok" lines and exits 0, or the first failure and exits 1.
 */
public final class PackCheckJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "verdict": verdict(); break;
            case "equal": equal(new File(a[1])); break;
            case "damaged": damaged(new File(a[1]), a[2], a[3]); break;
            case "stream": stream(new File(a[1]), a[2], a[3], a[4]); break;
            case "collapse": collapse(new File(a[1]), new File(a[2])); break;
            case "frames": frames(new File(a[1])); break;
            default: throw new IllegalArgumentException(a[0]);
        }
        System.out.println("ok " + n);
    }

    // ---- The verdict across launches ----

    static void verdict() throws IOException {
        File f = File.createTempFile("verdict", ".key");
        try {
            Files.write(f.toPath(), new byte[100]);
            f.setLastModified(1_700_000_000_000L);
            String id = f.length() + ":" + f.lastModified();
            String stamp = Verdict.stamp(id, "0.3.14:1000");
            check(stamp.matches("[0-9a-f]{64}"), "a stamp is a SHA-256 in hex");
            check(Boolean.TRUE.equals(Verdict.parse(Verdict.format(stamp, true), stamp)) && Boolean.FALSE.equals(Verdict.parse(Verdict.format(stamp, false), stamp)), "a kept verdict reads back, true and false");
            // What invalidates it: the file's size, its time, the APK's version or install time
            Files.write(f.toPath(), new byte[101]);
            f.setLastModified(1_700_000_000_000L);
            check(Verdict.parse(Verdict.format(stamp, true), Verdict.stamp(f.length() + ":" + f.lastModified(), "0.3.14:1000")) == null, "another size: checked again");
            Files.write(f.toPath(), new byte[100]);
            f.setLastModified(1_700_000_000_000L);
            check(Boolean.TRUE.equals(Verdict.parse(Verdict.format(stamp, true), Verdict.stamp(f.length() + ":" + f.lastModified(), "0.3.14:1000"))), "the same size and time: still valid");
            f.setLastModified(1_700_000_001_000L);
            check(Verdict.parse(Verdict.format(stamp, true), Verdict.stamp(f.length() + ":" + f.lastModified(), "0.3.14:1000")) == null, "another time: checked again");
            check(Verdict.parse(Verdict.format(stamp, true), Verdict.stamp(id, "0.3.15:1000")) == null, "another APK version: checked again");
            check(Verdict.parse(Verdict.format(stamp, true), Verdict.stamp(id, "0.3.14:2000")) == null, "the APK installed again: checked again");
            check(!Verdict.stamp("ab", "c").equals(Verdict.stamp("a", "bc")), "the id and the APK are kept apart in the stamp");
        } finally { f.delete(); }
        // A damaged, foreign or empty store is "not stored", never an exception
        String s = Verdict.stamp("x", "y");
        for (String bad : new String[] {null, "", ":", ":1", s, s + ":", s + ":2", s + ":11", s + "1", s.substring(1) + ":1", "garbage", "\0\0\0", s + ":1\n", s.toUpperCase() + ":1"})
            check(Verdict.parse(bad, s) == null, "a damaged store value is ignored: " + bad);
        check(Verdict.parse(s + ":1", null) == null, "no stamp: nothing is read");
        // The store itself: a map of slots, a store that throws on read or write, and unknown is never kept
        Map<String, String> store = new HashMap<>();
        check(Verdict.read(store::get, "slot", s) == null, "an empty store");
        check(Verdict.keep(store::put, "slot", s, null) == false && store.isEmpty(), "unknown (null) is never kept");
        check(Verdict.keep(store::put, "slot", s, Boolean.TRUE) && Boolean.TRUE.equals(Verdict.read(store::get, "slot", s)), "kept and read back");
        check(Verdict.keep(store::put, "slot", s, Boolean.FALSE) && Boolean.FALSE.equals(Verdict.read(store::get, "slot", s)), "a later verdict replaces it");
        check(Verdict.read(store::get, "other", s) == null && Verdict.read(store::get, "slot", Verdict.stamp("x", "z")) == null, "another slot or stamp reads nothing");
        store.put("slot", "\u0000corrupt");
        check(Verdict.read(store::get, "slot", s) == null, "a corrupt value reads as nothing");
        check(Verdict.read(k -> { throw new ClassCastException("not a string"); }, "slot", s) == null, "a store that throws on read is ignored");
        check(!Verdict.keep((k, v) -> { throw new IllegalStateException("disk full"); }, "slot", s, Boolean.TRUE), "a store that throws on write is ignored");
    }

    // ---- Streaming the decode into the hash ----

    static void walk(Nup x, Nup.Node dir, List<Nup.Node> out) throws IOException {
        for (Nup.Node c : x.children(dir)) { if (c.dir) walk(x, c, out); else out.add(c); }
    }

    static List<Nup.Node> files(Nup x) throws IOException {
        List<Nup.Node> out = new ArrayList<>();
        for (Nup.Pkg p : x.packages) {
            if (p.exefsDir != Nup.NONE) walk(x, x.dir(p.exefsDir), out);
            if (p.romfsDir != Nup.NONE) walk(x, x.dir(p.romfsDir), out);
        }
        return out;
    }

    static MessageDigest sha() throws Exception { return MessageDigest.getInstance("SHA-256"); }

    static void equal(File nup) throws Exception {
        Nup x = Nup.open(nup);
        int zs = 0, raw = 0;
        for (Nup.Node f : files(x)) {
            MessageDigest h = sha();
            x.hashTo(f, h);
            check(Arrays.equals(h.digest(), sha().digest(x.read(f, -1))), f.name + ": hashTo gives the digest of read(f, -1)");
            if (f.codec == 0) raw++; else zs++;
        }
        check(zs > 0 && raw > 0, "both raw and zstd files were covered (" + raw + " raw, " + zs + " zstd)");
    }

    static Nup.Node find(Nup x, String sel, String path) throws IOException {
        Nup.Node f = x.lookup(x.find(sel), path);
        if (f == null) throw new AssertionError(sel + "/" + path + " is not in the .nup");
        return f;
    }

    static void damaged(File nup, String sel, String path) throws Exception {
        Nup x = Nup.open(nup);
        Nup.Node f = find(x, sel, path);
        check(f.codec != 0, path + " is a zstd file");
        File bad = new File(nup.getParentFile(), "damaged-" + nup.getName());
        Files.copy(nup.toPath(), bad.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile r = new RandomAccessFile(bad, "rw")) {   // 100 KB in the middle of the extent: zeros (an interrupted copy), as in Forgejo #203
            r.seek(f.extentOffset + f.extentSize / 2);
            r.write(new byte[100_000]);
        }
        Nup y = Nup.open(bad);
        try {
            y.hashTo(find(y, sel, path), sha());
            check(false, "a damaged extent was hashed without a complaint");
        } catch (IOException e) {
            check(e.getMessage().contains(bad.getName()) && e.getMessage().contains(f.name), "the error names the file: " + e.getMessage());
        }
    }

    static long usedHeap() {
        long u = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) if (p.getType() == MemoryType.HEAP) u += p.getUsage().getUsed();
        return u;
    }

    /** The sum of the heap pools' peaks: an upper bound of the peak (the pools peak at different times, and uncollected garbage counts) */
    static long peakHeap() {
        long u = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) if (p.getType() == MemoryType.HEAP) u += p.getPeakUsage().getUsed();
        return u;
    }

    static void stream(File nup, String sel, String path, String want) throws Exception {
        Nup x = Nup.open(nup);
        Nup.Node f = find(x, sel, path);
        check(f.codec != 0 && f.size > 3L * Runtime.getRuntime().maxMemory() / 2, "the module (" + (f.size >> 20) + " MiB, " + (f.extentSize >> 20) + " MiB stored) is larger than the heap ("
                + (Runtime.getRuntime().maxMemory() >> 20) + " MiB)");
        System.gc();
        long base = usedHeap();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) p.resetPeakUsage();
        MessageDigest h = sha();
        x.hashTo(f, h);
        long peak = peakHeap() - base;
        check(hex(h.digest()).equals(want), "the streamed digest is the file's SHA-256");
        System.out.println("peak heap above the idle heap (upper bound): " + (peak >> 20) + " MiB for a " + (f.size >> 20) + " MiB module (" + (f.extentSize >> 20) + " MiB stored), -Xmx"
                + (Runtime.getRuntime().maxMemory() >> 20) + "m");
        check(peak < f.extentSize + (24L << 20), "the peak is the stored extent plus the window and young-generation slack, not the module: " + (peak >> 20) + " MiB");
        try {
            x.read(f, -1);
            check(false, "the whole-buffer read fitted in this heap: the test proves nothing");
        } catch (OutOfMemoryError e) {
            check(true, "the old whole-buffer read runs out of memory at this heap");
        }
    }

    static final class Entry implements GameList.View {
        final String dir;
        Entry(String dir) { this.dir = dir; }
        public String titleId() { return "040074A01BF12000"; }
        public String gameDir() { return dir; }
        public long used() { return 0; }
    }

    static void collapse(File a, File b) throws Exception {
        Nup x = Nup.open(a);
        Nup.Pkg up = x.newest();
        Nup.Node main = find(x, up.selector(), "exefs/main");
        long heap = Runtime.getRuntime().maxMemory();
        check(main.codec != 0 && main.extentSize > heap, "the newest main (" + (main.extentSize >> 20) + " MiB stored) is larger than the heap (" + (heap >> 20) + " MiB)");
        try {
            byte[] whole = new byte[(int) main.extentSize];
            check(false, "the stored extent fitted in this heap: the test proves nothing " + whole.length);
        } catch (OutOfMemoryError e) {
            check(true, "the whole stored extent does not fit in this heap");
        }
        byte[] head = x.read(main, 0x60);
        check(head.length >= 0x60 && head[0] == 'N' && head[1] == 'S' && head[2] == 'O', "the head of the module is read");
        String ka = BuildCheck.gameKey(a, "040074A01BF12000"), kb = BuildCheck.gameKey(b, "040074A01BF12000");
        check(ka != null && ka.equals(kb), "the build key of both copies is told: " + ka + " / " + kb);
        List<Entry> list = new ArrayList<>(List.of(new Entry(a.getPath()), new Entry(b.getPath())));
        int[] asked = {0};
        List<Entry> out = GameList.collapse(list, e -> BuildCheck.gameKey(new File(e.dir), "040074A01BF12000"), e -> { asked[0]++; return false; });
        check(out.size() == 1, "two entries of one game collapse to one: " + out.size());
        // a key function that runs out of memory is the caller's to catch: BuildCheck.gameKey itself answers null (a card kept), never an Error
        check(BuildCheck.gameKey(new File(a.getParentFile(), "missing.nup"), "040074A01BF12000") == null, "an unreadable dump has no key");
    }

    static void frames(File dir) throws Exception {
        File[] zs = dir.listFiles((d, name) -> name.endsWith(".zst"));
        Arrays.sort(zs);
        int tiny = 0;
        for (File z : zs) {
            byte[] src = Files.readAllBytes(z.toPath()), raw = Files.readAllBytes(new File(dir, z.getName().replace(".zst", ".raw")).toPath());
            MessageDigest h = sha();
            long[] pieces = {0};
            long got = Zstd.decodeTo(src, 0, src.length, 1L << 30, (b, off, len) -> { h.update(b, off, len); pieces[0]++; });
            check(got == raw.length && Arrays.equals(h.digest(), sha().digest(raw)), z.getName() + ": decodeTo equals the source (" + got + " bytes in " + pieces[0] + " pieces)");
            check(Arrays.equals(Zstd.decode(src, 0, src.length, -1), raw), z.getName() + ": decode equals the source");
            if (z.getName().contains("wlog10")) { tiny++; check(pieces[0] > 4, z.getName() + ": a 1 KiB window slid many times"); }
            try {
                Zstd.decodeTo(src, 0, src.length, 100, (b, off, len) -> { throw new AssertionError("output before the window was refused"); });
                check(false, z.getName() + ": a window of 1 KiB or more passed a limit of 100");
            } catch (IOException e) {
                check(e.getMessage().contains(Zstd.WINDOW_TOO_LARGE), z.getName() + ": refused before any output: " + e.getMessage());
            }
        }
        check(tiny > 0, "frames with the smallest window were covered");
    }
}
