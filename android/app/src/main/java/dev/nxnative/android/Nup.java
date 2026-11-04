package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A .nup game dump (android/NUP.md; the format's SPEC.md, 1.0-draft), read only, never written. {@link #open} checks the superblock and the TOC's SHA-256 (the TOC
 * is streamed through the hash, not kept) and keeps the package records; a package's tree (exefs/main, main.npdm, whether romfs/ has files) is then read from the
 * TOC by position. Files are read whole or by their first bytes, raw or zstd (Zstd); a whole read is checked against the file's SHA-256.
 * <p>
 * Naming (NUP.md "Naming a package"): {@code <file>.nup:<selector>[/<path>]}, split at the last ".nup:", with the selector {@code base}, {@code update/<version>}
 * or {@code addon/<16 hex digits>}. {@link #parse} reads one, {@link #spec} writes one. No Android classes: the JVM test runs it on the Mac.
 */
final class Nup {
    static final int BASE = 0, UPDATE = 1, ADDON = 2;
    static final long NONE = 0xFFFFFFFFL;
    static final String EXT = ".nup";

    /** One package record */
    static final class Pkg {
        final int index, kind, flags;
        final long titleId, version, rootDir, exefsDir, romfsDir;

        Pkg(int index, long titleId, long rootDir, long exefsDir, long romfsDir, long version, int kind, int flags) {
            this.index = index;
            this.titleId = titleId;
            this.rootDir = rootDir;
            this.exefsDir = exefsDir;
            this.romfsDir = romfsDir;
            this.version = version;
            this.kind = kind;
            this.flags = flags;
        }

        /** This package's selector: base, update/&lt;version&gt;, addon/&lt;title ID, lower case&gt; (null for another kind) */
        String selector() {
            switch (kind) {
                case BASE: return "base";
                case UPDATE: return "update/" + version;
                case ADDON: return "addon/" + String.format(Locale.ROOT, "%016x", titleId);
                default: return null;
            }
        }
    }

    /** A directory or a file of the TOC */
    static final class Node {
        final boolean dir;
        final long index;   // into dirs[] or files[]
        final String name;
        final long size, extentOffset, extentSize, firstChild, childCount;
        final int codec;
        final byte[] sha256;

        Node(boolean dir, long index, String name, long size, long extentOffset, long extentSize, int codec, byte[] sha256, long firstChild, long childCount) {
            this.dir = dir;
            this.index = index;
            this.name = name;
            this.size = size;
            this.extentOffset = extentOffset;
            this.extentSize = extentSize;
            this.codec = codec;
            this.sha256 = sha256;
            this.firstChild = firstChild;
            this.childCount = childCount;
        }
    }

    final File file;
    final long fileSize, tocOffset, tocSize, blobOffset, dirCount, fileCount, poolOff, poolSize;
    final List<Pkg> packages;
    private final long offDir, offFile, offKids;

    private Nup(File file, long fileSize, long tocOffset, long tocSize, long blobOffset, long dirCount, long fileCount, long poolOff, long poolSize, List<Pkg> packages) {
        this.file = file;
        this.fileSize = fileSize;
        this.tocOffset = tocOffset;
        this.tocSize = tocSize;
        this.blobOffset = blobOffset;
        this.dirCount = dirCount;
        this.fileCount = fileCount;
        this.poolOff = poolOff;
        this.poolSize = poolSize;
        this.packages = Collections.unmodifiableList(packages);
        offDir = 0x30 + 0x40L * packages.size();
        offFile = offDir + 0x18 * dirCount;
        offKids = offFile + 0x50 * fileCount;
    }

    /** Whether a path is a .nup file by its name (ends with ".nup", lower case, as the host's and the tools' split at ".nup:" needs) */
    static boolean isNup(File f) { return f != null && f.getName().endsWith(EXT) && f.getName().length() > EXT.length() && !f.isDirectory(); }

    /** A .nup named with another case of the extension: the tools and the host would not take it */
    static boolean wrongCase(File f) { return f != null && !f.getName().endsWith(EXT) && f.getName().toLowerCase(Locale.ROOT).endsWith(EXT) && f.isFile(); }

    // ---- Naming ----

    private static final Pattern SEL = Pattern.compile("(base|update/[0-9]+|addon/[0-9a-fA-F]{16})(/.*)?");

    /** A parsed name: the .nup's path, its selector (null: none, the default package) and the path after it ("" when none; no leading '/') */
    static final class Ref {
        final String path, selector, sub;

        Ref(String path, String selector, String sub) { this.path = path; this.selector = selector; this.sub = sub; }

        @Override public String toString() { return selector == null ? path : path + ":" + selector + (sub.isEmpty() ? "" : "/" + sub); }
    }

    /** null when the name has no ".nup:" and does not end with ".nup"; IllegalArgumentException for a bad selector */
    static Ref parse(String spec) {
        if (spec == null) return null;
        int k = spec.lastIndexOf(EXT + ":");
        if (k < 0) return spec.endsWith(EXT) && spec.length() > EXT.length() ? new Ref(spec, null, "") : null;
        String path = spec.substring(0, k + EXT.length()), rest = spec.substring(k + EXT.length() + 1);
        if (rest.isEmpty()) return new Ref(path, null, "");
        Matcher m = SEL.matcher(rest);
        if (!m.matches()) throw new IllegalArgumentException("not a package of a .nup: \"" + rest + "\" (base, update/<version> or addon/<16 hex digits>)");
        String sel = m.group(1);
        if (sel.startsWith("addon/")) sel = sel.toLowerCase(Locale.ROOT);
        if (sel.startsWith("update/")) sel = "update/" + Long.parseLong(sel.substring(7));   // no leading zeros
        String sub = m.group(2) == null ? "" : m.group(2).substring(1);
        while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
        return new Ref(path, sel, sub);
    }

    /** The selector of a game version as the tools number it: 0 is the base game, else its update */
    static String versionSelector(long version) { return version == 0 ? "base" : "update/" + version; }

    /** &lt;file&gt;.nup:&lt;selector&gt;[/&lt;sub&gt;] */
    static String spec(File nup, String selector, String sub) {
        return nup.getAbsolutePath() + ":" + selector + (sub == null || sub.isEmpty() ? "" : "/" + sub);
    }

    // ---- Opening ----

    private static final Map<String, Nup> cache = new LinkedHashMap<>();

    /** The .nup checked (superblock, TOC SHA-256); cached by path, size and time, so a card's checks do not hash the TOC again */
    static Nup open(File f) throws IOException {
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        synchronized (cache) {
            Nup n = cache.get(key);
            if (n != null) return n;
        }
        Nup n = read(f, true);
        synchronized (cache) {
            cache.put(key, n);
            while (cache.size() > 4) cache.remove(cache.keySet().iterator().next());
        }
        return n;
    }

    /** Only the superblock and the package records, TOC not hashed: quick, for the UI's "is it a dump" (never for what is read from it) */
    static Nup peek(File f) throws IOException { return read(f, false); }

    private static Nup read(File f, boolean verify) throws IOException {
        if (!f.isFile()) throw new IOException(f + " is not a file");
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            if (len < 0x80) throw new IOException(f.getName() + " is too short for a .nup");
            byte[] sb = new byte[0x80];
            r.readFully(sb);
            if (sb[0] != 'N' || sb[1] != 'U' || sb[2] != 'P') throw new IOException(f.getName() + " is not a .nup (no NUP magic)");
            if (sb[3] != 1) throw new IOException(f.getName() + " is .nup format " + (sb[3] & 0xff) + "; this app reads format 1");
            int headerSize = u16(sb, 4);
            long tocOff = u64(sb, 8), tocSize = u64(sb, 0x10), blob = u64(sb, 0x38), size = u64(sb, 0x40);
            long pkgCount = u32(sb, 0x48), flags = u32(sb, 0x4C);
            if (headerSize < 0x80) throw new IOException(f.getName() + ": header size " + headerSize);
            if (tocOff != headerSize) throw new IOException(f.getName() + ": the TOC is not right after the header");
            if ((blob & 15) != 0 || tocSize < 0x30 || blob < tocOff + tocSize) throw new IOException(f.getName() + ": the TOC and blob offsets do not fit");
            if (size != len) throw new IOException(f.getName() + " is " + len + " bytes, its header says " + size + ": it is incomplete (still being copied?) or damaged");
            if (flags != 0) throw new IOException(f.getName() + " has flags " + flags + " this app does not know");
            if (pkgCount < 1 || pkgCount > 65536) throw new IOException(f.getName() + " has " + pkgCount + " packages");
            if (0x30 + 0x40 * pkgCount > tocSize) throw new IOException(f.getName() + ": the package records do not fit in the TOC");
            byte[] head = new byte[(int) (0x30 + 0x40 * pkgCount)];
            r.seek(tocOff);
            r.readFully(head);
            if (verify) {
                MessageDigest h = sha256();
                h.update(head);
                byte[] buf = new byte[1 << 16];
                long left = tocSize - head.length;
                while (left > 0) {
                    int n = r.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException(f.getName() + ": the TOC ends early");
                    h.update(buf, 0, n);
                    left -= n;
                }
                if (!Arrays.equals(h.digest(), Arrays.copyOfRange(sb, 0x18, 0x38)))
                    throw new IOException(f.getName() + ": its table of contents does not match its SHA-256: the file is damaged");
            }
            if (head[0] != 'T' || head[1] != 'O' || head[2] != 'C' || head[3] != 1) throw new IOException(f.getName() + ": no TOC magic");
            if (u32(head, 4) != pkgCount) throw new IOException(f.getName() + ": the TOC and the header count different packages");
            long dirs = u64(head, 8), files = u64(head, 0x10), poolOff = u64(head, 0x18), poolSize = u64(head, 0x20);
            long kidsEnd = 0x30 + 0x40 * pkgCount + 0x18 * dirs + 0x50 * files;
            if (dirs > tocSize / 0x18 || files > tocSize / 0x50 || kidsEnd > tocSize || poolOff < kidsEnd || poolOff + poolSize > tocSize)
                throw new IOException(f.getName() + ": the TOC's tables do not fit in it");
            List<Pkg> pk = new ArrayList<>();
            int bases = 0;
            for (int i = 0; i < pkgCount; i++) {
                int o = 0x30 + 0x40 * i;
                int kind = head[o + 0x18] & 0xff;
                if (kind >= 5) throw new IOException(f.getName() + ": package " + i + " is of kind " + kind + ", which this app does not know");
                Pkg p = new Pkg(i, u64(head, o), u32(head, o + 8), u32(head, o + 0xC), u32(head, o + 0x10), u32(head, o + 0x14), kind, head[o + 0x19] & 0xff);
                for (long d : new long[] {p.rootDir, p.exefsDir, p.romfsDir})
                    if (d != NONE && d >= dirs) throw new IOException(f.getName() + ": package " + i + " names a directory past the table");
                if (p.rootDir == NONE) throw new IOException(f.getName() + ": package " + i + " has no root");
                if (kind == BASE) bases++;
                pk.add(p);
            }
            if (bases > 1) throw new IOException(f.getName() + " has " + bases + " base packages");
            return new Nup(f, len, tocOff, tocSize, blob, dirs, files, poolOff, poolSize, pk);
        }
    }

    // ---- Packages ----

    Pkg base() {
        for (Pkg p : packages) if (p.kind == BASE) return p;
        return null;
    }

    List<Pkg> of(int kind) {
        List<Pkg> out = new ArrayList<>();
        for (Pkg p : packages) if (p.kind == kind) out.add(p);
        return out;
    }

    /** The package a selector names, null when none (NUP.md: an error for the caller to say) */
    Pkg find(String selector) {
        for (Pkg p : packages) if (selector.equals(p.selector())) return p;
        return null;
    }

    /** No selector: the highest-version update, else the base */
    Pkg newest() {
        Pkg best = null;
        for (Pkg p : packages) if (p.kind == UPDATE && (best == null || p.version > best.version)) best = p;
        return best != null ? best : base();
    }

    /** The package a name's selector picks (null selector: newest) */
    Pkg resolve(String selector) { return selector == null ? newest() : find(selector); }

    /**
     * The game's program ID (16 upper-case hex digits): the base package's main.npdm (its ACI0, as for a folder), else the base's record, else the first
     * update's (an update-only .nup). The NPDM is what the folder path reads; the record is the dump tool's verbatim ID
     */
    String programId() throws IOException {
        Pkg p = base();
        if (p == null) p = newest();
        if (p == null) throw new IOException(file.getName() + " has neither a base package nor an update");
        Node npdm = lookup(p, "exefs/main.npdm");
        if (npdm != null && !npdm.dir) return Pack.programId(read(npdm, -1), file.getName() + ":" + p.selector() + "/exefs/main.npdm");
        return String.format(Locale.ROOT, "%016X", p.titleId);
    }

    // ---- The tree ----

    private Node node(RandomAccessFile r, long id) throws IOException {
        if (id < dirCount) {
            byte[] b = toc(r, offDir + 0x18 * id, 0x18);
            return new Node(true, id, name(r, u32(b, 0), u32(b, 4)), 0, 0, 0, 0, null, u32(b, 8), u32(b, 12));
        }
        long fi = id - dirCount;
        if (fi >= fileCount) throw new IOException(file.getName() + ": node " + id + " past the tables");
        byte[] b = toc(r, offFile + 0x50 * fi, 0x50);
        long size = u64(b, 8), eo = u64(b, 0x10), es = u64(b, 0x18);
        int codec = b[0x40] & 0xff;
        if (codec > 1) throw new IOException(file.getName() + ": a file of codec " + codec + ", which this app does not read");
        if (es > 0 && (eo < blobOffset || eo + es > fileSize || eo + es < eo)) throw new IOException(file.getName() + ": a file's extent is outside the blob region");
        return new Node(false, fi, name(r, u32(b, 0), u32(b, 4)), size, eo, es, codec, Arrays.copyOfRange(b, 0x20, 0x40), 0, 0);
    }

    private String name(RandomAccessFile r, long off, long len) throws IOException {
        if (off + len > poolSize || len > 4096) throw new IOException(file.getName() + ": a name outside the string pool");
        return new String(toc(r, poolOff + off, (int) len), StandardCharsets.UTF_8);
    }

    private byte[] toc(RandomAccessFile r, long off, int n) throws IOException {
        if (off + n > tocSize) throw new IOException(file.getName() + ": a TOC read past its end");
        byte[] b = new byte[n];
        r.seek(tocOffset + off);
        r.readFully(b);
        return b;
    }

    /** A directory's entries */
    List<Node> children(Node dir) throws IOException {
        List<Node> out = new ArrayList<>();
        if (!dir.dir) return out;
        try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
            long kids = (offKids + 4 * (dir.firstChild + dir.childCount));
            if (kids > poolOff) throw new IOException(file.getName() + ": a directory's children past the table");
            byte[] ids = toc(r, offKids + 4 * dir.firstChild, (int) (4 * dir.childCount));
            for (int i = 0; i < dir.childCount; i++) out.add(node(r, u32(ids, 4 * i)));
        }
        return out;
    }

    /** A directory by its index (a package's root, exefs or romfs) */
    Node dir(long index) throws IOException {
        if (index == NONE) return null;
        try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
            return node(r, index);
        }
    }

    /** The node at a path inside a package ("exefs/main", "romfs"), null when there is none. exefs and romfs go through the package record (SPEC §3.1.3) */
    Node lookup(Pkg p, String path) throws IOException {
        String[] parts = path.isEmpty() ? new String[0] : path.split("/");
        Node at;
        int i = 0;
        if (parts.length > 0 && parts[0].equals("exefs")) { at = dir(p.exefsDir); i = 1; }
        else if (parts.length > 0 && parts[0].equals("romfs")) { at = dir(p.romfsDir); i = 1; }
        else at = dir(p.rootDir);
        for (; at != null && i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            Node next = null;
            for (Node c : children(at)) if (c.name.equals(parts[i])) { next = c; break; }
            at = next;
        }
        return at;
    }

    /** A file's bytes: max &lt; 0 all of them (checked against its SHA-256), else at least its first max bytes (fewer when it is shorter) */
    byte[] read(Node f, long max) throws IOException {
        if (f.dir) throw new IOException(f.name + " is a directory");
        if (f.size == 0) return new byte[0];
        if (f.extentSize > Integer.MAX_VALUE - 16 || (max < 0 && f.size > Integer.MAX_VALUE - 16)) throw new IOException(f.name + " is too large to read into memory");
        byte[] out;
        try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
            if (f.codec == 0) {
                if (f.extentSize != f.size) throw new IOException(f.name + ": a raw extent of another size than the file");
                int n = (int) (max < 0 ? f.size : Math.min(f.size, max));
                out = new byte[n];
                r.seek(f.extentOffset);
                r.readFully(out);
                if (max >= 0) return out;
            } else {
                byte[] z = new byte[(int) f.extentSize];
                r.seek(f.extentOffset);
                r.readFully(z);
                out = Zstd.decode(z, 0, z.length, max);
                if (max >= 0) return out;
                if (out.length != f.size) throw new IOException(f.name + ": decompressed to " + out.length + " bytes, the TOC says " + f.size);
            }
        } catch (IOException e) {
            throw new IOException(file.getName() + ": " + f.name + ": " + e.getMessage(), e);
        }
        if (!Arrays.equals(sha256().digest(out), f.sha256)) throw new IOException(file.getName() + ": " + f.name + " does not match its SHA-256: the file is damaged");
        return out;
    }

    // ---- Little-endian fields ----

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }

    static long u32(byte[] b, int o) { return Zstd.le32(b, o) & 0xffffffffL; }

    static long u64(byte[] b, int o) { return u32(b, o) | u32(b, o + 4) << 32; }
}
