package dev.nxnative.android;

import java.io.IOException;
import java.util.Arrays;

/**
 * A small Zstandard frame decoder (RFC 8878), pure Java, for the files of a .nup (Nup: codec 1 is one zstd frame per file with its content size). The launcher
 * reads only small things from a .nup this way (NPDMs, NSO headers for the build IDs, the modules for the rewriter's key), so it decodes into memory and favours
 * being short and checked over speed. No dictionaries (a .nup has none). {@code max} stops after the block that reaches that many bytes: an NSO's header needs
 * only the frame's first block. No Android classes: the JVM test runs it on the Mac.
 */
final class Zstd {
    private Zstd() {}

    private static final int MAGIC = 0xFD2FB528;
    private static final int BLOCK_MAX = 128 << 10;

    /** The frame's content size from its header, or -1 when the header does not say */
    static long contentSize(byte[] src) throws IOException {
        return new Frame(src, 0, src.length).fcs;
    }

    /** Decodes the frame in src[off, off+len); max &lt; 0: all of it, else at least the first max bytes (fewer when the content is shorter) */
    static byte[] decode(byte[] src, int off, int len, long max) throws IOException {
        Frame f = new Frame(src, off, len);
        return f.run(max);
    }

    private static IOException bad(String why) { return new IOException("zstd: " + why); }

    // ---- Backward bit stream (FSE, Huffman, sequences): read from the end; bits below the stream's start read as zeros ----

    private static final class BackBits {
        final byte[] b;
        final int start, end;   // the stream is b[start, end)
        long p;                 // bits [0, p) of the little-endian bit array are still to be read

        BackBits(byte[] b, int start, int end) throws IOException {
            this.b = b;
            this.start = start;
            this.end = end;
            if (end <= start) throw bad("empty bit stream");
            int last = b[end - 1] & 0xff;
            if (last == 0) throw bad("bit stream without its end mark");
            p = (long) (end - start - 1) * 8 + (31 - Integer.numberOfLeadingZeros(last));
        }

        private long word(long byteIdx) {   // 8 bytes little-endian at start + byteIdx; bytes past the end read as zeros
            long w = 0;
            for (int i = 7; i >= 0; i--) {
                long k = start + byteIdx + i;
                w = w << 8 | (k < end ? b[(int) k] & 0xffL : 0);
            }
            return w;
        }

        int peek(int n) {
            if (n == 0) return 0;
            long s = p - n;
            if (s >= 0) return (int) ((word(s >> 3) >>> (s & 7)) & ((1L << n) - 1));
            if (p <= 0) return 0;
            int have = (int) ((word(0)) & ((1L << p) - 1));
            return have << (int) -s;
        }

        int read(int n) { int v = peek(n); p -= n; return v; }

        boolean overflowed() { return p < 0; }
    }

    // ---- FSE tables ----

    private static final class Fse {
        final int log;
        final int[] sym, bits, base;

        Fse(int log, int[] sym, int[] bits, int[] base) { this.log = log; this.sym = sym; this.bits = bits; this.base = base; }

        static Fse build(short[] norm, int nsym, int log) throws IOException {
            int size = 1 << log, high = size - 1;
            int[] sym = new int[size], bits = new int[size], base = new int[size], next = new int[nsym];
            for (int s = 0; s < nsym; s++) {
                if (norm[s] == -1) { sym[high--] = s; next[s] = 1; }
                else next[s] = norm[s];
            }
            int pos = 0, step = (size >> 1) + (size >> 3) + 3, mask = size - 1;
            for (int s = 0; s < nsym; s++) {
                for (int i = 0; i < norm[s]; i++) {
                    sym[pos] = s;
                    do pos = (pos + step) & mask; while (pos > high);
                }
            }
            if (pos != 0) throw bad("FSE table does not fill");
            for (int u = 0; u < size; u++) {
                int s = sym[u], ns = next[s]++;
                int nb = log - (31 - Integer.numberOfLeadingZeros(ns));
                bits[u] = nb;
                base[u] = (ns << nb) - size;
            }
            return new Fse(log, sym, bits, base);
        }

        static Fse rle(int s) { return new Fse(0, new int[] {s}, new int[] {0}, new int[] {0}); }

        /** A table description (forward bits at src[off..]); sets used[0] to its length in bytes */
        static Fse read(byte[] src, int off, int end, int maxSym, int maxLog, int[] used) throws IOException {
            long bit = 0;
            int log = fwd(src, off, end, bit, 4) + 5;
            bit += 4;
            if (log > maxLog) throw bad("FSE accuracy " + log + " over " + maxLog);
            int remaining = (1 << log) + 1, threshold = 1 << log, nb = log + 1, s = 0;
            short[] norm = new short[maxSym + 1];
            boolean prev0 = false;
            while (remaining > 1 && s <= maxSym) {
                if (prev0) {
                    int r;
                    do {
                        r = fwd(src, off, end, bit, 2);
                        bit += 2;
                        s += r;
                    } while (r == 3);
                    if (s > maxSym) throw bad("FSE zeros past the last symbol");
                }
                int max = 2 * threshold - 1 - remaining;
                int v = fwd(src, off, end, bit, nb);
                int count;
                if ((v & (threshold - 1)) < max) { count = v & (threshold - 1); bit += nb - 1; }
                else { count = v & (2 * threshold - 1); if (count >= threshold) count -= max; bit += nb; }
                count--;
                remaining -= Math.abs(count);
                norm[s++] = (short) count;
                prev0 = count == 0;
                while (remaining < threshold) { nb--; threshold >>= 1; }
            }
            if (remaining != 1) throw bad("FSE counts do not add up");
            used[0] = (int) ((bit + 7) >> 3);
            if (off + used[0] > end) throw bad("FSE description past its end");
            return build(norm, s, log);
        }

        private static int fwd(byte[] b, int off, int end, long bit, int n) {   // n bits at bit offset (little-endian), zeros past end
            long v = 0;
            int first = (int) (bit >> 3);
            for (int i = 0; i < 4; i++) {
                int k = off + first + i;
                v |= (k < end ? b[k] & 0xffL : 0) << (8 * i);
            }
            return (int) ((v >>> (bit & 7)) & ((1L << n) - 1));
        }
    }

    // ---- Huffman (literals) ----

    private static final class Huf {
        final int log;
        final int[] sym, bits;

        Huf(int log, int[] sym, int[] bits) { this.log = log; this.sym = sym; this.bits = bits; }

        /** A tree description at src[off..]; used[0]: its length */
        static Huf read(byte[] src, int off, int end, int[] used) throws IOException {
            if (off >= end) throw bad("no Huffman tree");
            int h = src[off] & 0xff;
            int[] w = new int[256];
            int n;
            if (h < 128) {
                if (off + 1 + h > end) throw bad("Huffman weights past the block");
                int[] u = new int[1];
                Fse t = Fse.read(src, off + 1, off + 1 + h, 255, 6, u);
                BackBits bb = new BackBits(src, off + 1 + u[0], off + 1 + h);
                int s1 = bb.read(t.log), s2 = bb.read(t.log);
                n = 0;
                while (true) {
                    if (n >= 255) throw bad("too many Huffman weights");
                    w[n++] = t.sym[s1];
                    s1 = t.base[s1] + bb.read(t.bits[s1]);
                    if (bb.overflowed()) { w[n++] = t.sym[s2]; break; }
                    if (n >= 255) throw bad("too many Huffman weights");
                    w[n++] = t.sym[s2];
                    s2 = t.base[s2] + bb.read(t.bits[s2]);
                    if (bb.overflowed()) { w[n++] = t.sym[s1]; break; }
                }
                used[0] = 1 + h;
            } else {
                n = h - 127;
                int bytes = (n + 1) / 2;
                if (off + 1 + bytes > end) throw bad("Huffman weights past the block");
                for (int i = 0; i < n; i++) {
                    int by = src[off + 1 + i / 2] & 0xff;
                    w[i] = (i & 1) == 0 ? by >> 4 : by & 15;
                }
                used[0] = 1 + bytes;
            }
            long total = 0;
            for (int i = 0; i < n; i++) {
                if (w[i] > 11) throw bad("Huffman weight " + w[i]);
                if (w[i] > 0) total += 1L << (w[i] - 1);
            }
            if (total == 0) throw bad("empty Huffman tree");
            int log = 64 - Long.numberOfLeadingZeros(total);   // highest bit + 1
            long rest = (1L << log) - total;
            if (Long.bitCount(rest) != 1) throw bad("Huffman weights do not complete a tree");
            w[n] = 63 - Long.numberOfLeadingZeros(rest) + 1;
            n++;
            if (log > 11) throw bad("Huffman tree deeper than 11");
            int[] rankCount = new int[log + 2];
            for (int i = 0; i < n; i++) rankCount[w[i]]++;
            int[] rankStart = new int[log + 2];
            int next = 0;
            for (int r = 1; r <= log; r++) { rankStart[r] = next; next += rankCount[r] << (r - 1); }
            int size = 1 << log;
            int[] sym = new int[size], bits = new int[size];
            for (int s = 0; s < n; s++) {
                int wt = w[s];
                if (wt == 0) continue;
                int len = 1 << (wt - 1), st = rankStart[wt];
                for (int k = st; k < st + len; k++) { sym[k] = s; bits[k] = log + 1 - wt; }
                rankStart[wt] += len;
            }
            return new Huf(log, sym, bits);
        }

        void stream(byte[] src, int a, int b, byte[] out, int o, int count) throws IOException {
            BackBits bb = new BackBits(src, a, b);
            for (int i = 0; i < count; i++) {
                int k = bb.peek(log);
                out[o + i] = (byte) sym[k];
                bb.p -= bits[k];
            }
            if (bb.p != 0) throw bad("Huffman stream not used up");
        }
    }

    // ---- Sequences ----

    private static final int[] LL_BASE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048,
            4096, 8192, 16384, 32768, 65536};
    private static final int[] LL_BITS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final int[] ML_BASE = new int[53], ML_BITS = new int[53];
    static {
        for (int i = 0; i < 32; i++) ML_BASE[i] = i + 3;
        int[] b = {35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539};
        int[] n = {1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < b.length; i++) { ML_BASE[32 + i] = b[i]; ML_BITS[32 + i] = n[i]; }
    }
    private static final short[] LL_DEF = {4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1};
    private static final short[] ML_DEF = {1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1};
    private static final short[] OF_DEF = {1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1};

    // ---- A frame ----

    private static final class Frame {
        final byte[] src;
        final int end;
        int pos;
        final long fcs;
        final boolean checksum;
        byte[] out;
        int op;
        Huf huf;
        Fse ll, of, ml;
        final int[] rep = {1, 4, 8};
        byte[] lit = new byte[BLOCK_MAX];
        int litLen;

        Frame(byte[] src, int off, int len) throws IOException {
            this.src = src;
            this.end = off + len;
            pos = off;
            if (len < 6 || le32(src, pos) != MAGIC) throw bad("not a zstd frame");
            pos += 4;
            int fhd = src[pos++] & 0xff;
            int fcsFlag = fhd >> 6, dictFlag = fhd & 3;
            boolean single = (fhd & 0x20) != 0;
            if ((fhd & 0x08) != 0) throw bad("reserved bit set");
            checksum = (fhd & 0x04) != 0;
            if (!single) pos++;   // window descriptor: the whole output is kept, so the window does not matter
            int dictSize = dictFlag == 0 ? 0 : dictFlag == 1 ? 1 : dictFlag == 2 ? 2 : 4;
            long dict = 0;
            for (int i = 0; i < dictSize; i++) dict |= (src[pos + i] & 0xffL) << (8 * i);
            pos += dictSize;
            if (dict != 0) throw bad("needs a dictionary");
            int fcsSize = fcsFlag == 0 ? (single ? 1 : 0) : fcsFlag == 1 ? 2 : fcsFlag == 2 ? 4 : 8;
            long v = 0;
            for (int i = 0; i < fcsSize; i++) v |= (src[pos + i] & 0xffL) << (8 * i);
            pos += fcsSize;
            if (pos > end) throw bad("frame header past the end");
            fcs = fcsSize == 0 ? -1 : fcsSize == 2 ? v + 256 : v;
        }

        byte[] run(long max) throws IOException {
            long want = fcs >= 0 ? fcs : -1;
            if (max >= 0 && (want < 0 || max + BLOCK_MAX < want)) want = max + BLOCK_MAX;
            if (want < 0) want = 1 << 20;
            if (want > Integer.MAX_VALUE - 16) throw bad("content too large to read into memory");
            out = new byte[(int) want];
            while (true) {
                if (pos + 3 > end) throw bad("block header past the end");
                int h = (src[pos] & 0xff) | (src[pos + 1] & 0xff) << 8 | (src[pos + 2] & 0xff) << 16;
                pos += 3;
                boolean last = (h & 1) != 0;
                int type = (h >> 1) & 3, size = h >>> 3;
                if (type == 0) {
                    if (pos + size > end) throw bad("raw block past the end");
                    room(size);
                    System.arraycopy(src, pos, out, op, size);
                    op += size;
                    pos += size;
                } else if (type == 1) {
                    if (pos + 1 > end) throw bad("RLE block past the end");
                    room(size);
                    Arrays.fill(out, op, op + size, src[pos]);
                    op += size;
                    pos += 1;
                } else if (type == 2) {
                    if (size > BLOCK_MAX || pos + size > end) throw bad("compressed block too large or past the end");
                    block(pos, pos + size);
                    pos += size;
                } else throw bad("reserved block type");
                if (last) break;
                if (max >= 0 && op >= max) return Arrays.copyOf(out, op);
            }
            if (fcs >= 0 && op != fcs) throw bad("content is " + op + " bytes, the header says " + fcs);
            return op == out.length ? out : Arrays.copyOf(out, op);
        }

        private void room(int n) throws IOException {
            if ((long) op + n <= out.length) return;
            if (fcs >= 0 && (long) op + n > fcs) throw bad("content larger than the header says");
            long size = Math.max((long) out.length * 2, (long) op + n);
            if (fcs >= 0) size = Math.min(size, fcs);
            if (size > Integer.MAX_VALUE - 16) throw bad("content too large to read into memory");
            out = Arrays.copyOf(out, (int) size);
        }

        private void block(int a, int b) throws IOException {
            int p = literals(a, b);
            sequences(p, b);
        }

        /** The literals section at a: fills lit[0, litLen); returns where the sequences section starts */
        private int literals(int a, int b) throws IOException {
            if (a >= b) throw bad("empty block");
            int b0 = src[a] & 0xff, type = b0 & 3, sf = (b0 >> 2) & 3;
            if (type == 0 || type == 1) {
                int hl, size;
                if ((sf & 1) == 0) { hl = 1; size = b0 >> 3; }
                else if (sf == 1) { hl = 2; size = (b0 >> 4) + ((src[a + 1] & 0xff) << 4); }
                else { hl = 3; size = (b0 >> 4) + ((src[a + 1] & 0xff) << 4) + ((src[a + 2] & 0xff) << 12); }
                if (size > BLOCK_MAX) throw bad("literals too large");
                a += hl;
                if (type == 0) {
                    if (a + size > b) throw bad("raw literals past the block");
                    System.arraycopy(src, a, lit, 0, size);
                    litLen = size;
                    return a + size;
                }
                if (a + 1 > b) throw bad("RLE literals past the block");
                Arrays.fill(lit, 0, size, src[a]);
                litLen = size;
                return a + 1;
            }
            int hl, regen, comp;
            boolean four = sf != 0;
            if (sf <= 1) {
                hl = 3;
                int v = b0 | (src[a + 1] & 0xff) << 8 | (src[a + 2] & 0xff) << 16;
                regen = (v >> 4) & 0x3FF;
                comp = (v >> 14) & 0x3FF;
            } else if (sf == 2) {
                hl = 4;
                long v = le32(src, a) & 0xffffffffL;
                regen = (int) ((v >> 4) & 0x3FFF);
                comp = (int) ((v >> 18) & 0x3FFF);
            } else {
                hl = 5;
                long v = (le32(src, a) & 0xffffffffL) | (src[a + 4] & 0xffL) << 32;
                regen = (int) ((v >> 4) & 0x3FFFF);
                comp = (int) ((v >> 22) & 0x3FFFF);
            }
            if (regen > BLOCK_MAX) throw bad("literals too large");
            a += hl;
            int e = a + comp;
            if (e > b) throw bad("compressed literals past the block");
            if (type == 2) {
                int[] u = new int[1];
                huf = Huf.read(src, a, e, u);
                a += u[0];
            } else if (huf == null) throw bad("treeless literals without an earlier tree");
            if (!four) huf.stream(src, a, e, lit, 0, regen);
            else {
                if (a + 6 > e) throw bad("no jump table");
                int s1 = u16(a), s2 = u16(a + 2), s3 = u16(a + 4);
                int q = a + 6, seg = (regen + 3) / 4;
                int last = regen - 3 * seg;
                if (last < 0 || q + s1 + s2 + s3 > e) throw bad("bad jump table");
                huf.stream(src, q, q + s1, lit, 0, seg);
                huf.stream(src, q + s1, q + s1 + s2, lit, seg, seg);
                huf.stream(src, q + s1 + s2, q + s1 + s2 + s3, lit, 2 * seg, seg);
                huf.stream(src, q + s1 + s2 + s3, e, lit, 3 * seg, last);
            }
            litLen = regen;
            return e;
        }

        private int u16(int a) { return (src[a] & 0xff) | (src[a + 1] & 0xff) << 8; }

        private Fse table(int mode, int[] p, int b, short[] def, int defLog, int maxSym, int maxLog, Fse prev, String what) throws IOException {
            switch (mode) {
                case 0: return Fse.build(def, def.length, defLog);
                case 1: {
                    if (p[0] >= b) throw bad(what + " RLE past the block");
                    int s = src[p[0]++] & 0xff;
                    if (s > maxSym) throw bad(what + " RLE symbol " + s);
                    return Fse.rle(s);
                }
                case 2: {
                    int[] u = new int[1];
                    Fse t = Fse.read(src, p[0], b, maxSym, maxLog, u);
                    p[0] += u[0];
                    return t;
                }
                default:
                    if (prev == null) throw bad(what + " repeats a table there is not");
                    return prev;
            }
        }

        private void sequences(int a, int b) throws IOException {
            if (a >= b) throw bad("no sequences section");
            int b0 = src[a++] & 0xff, nseq;
            if (b0 < 128) nseq = b0;
            else if (b0 < 255) { nseq = ((b0 - 128) << 8) + (src[a++] & 0xff); }
            else { nseq = (src[a] & 0xff) + ((src[a + 1] & 0xff) << 8) + 0x7F00; a += 2; }
            if (nseq == 0) {
                room(litLen);
                System.arraycopy(lit, 0, out, op, litLen);
                op += litLen;
                return;
            }
            if (a >= b) throw bad("no compression modes");
            int modes = src[a++] & 0xff;
            if ((modes & 3) != 0) throw bad("reserved sequence mode bits");
            int[] p = {a};
            ll = table(modes >> 6, p, b, LL_DEF, 6, 35, 9, ll, "literal lengths");
            of = table((modes >> 4) & 3, p, b, OF_DEF, 5, 31, 8, of, "offsets");
            ml = table((modes >> 2) & 3, p, b, ML_DEF, 6, 52, 9, ml, "match lengths");
            BackBits bb = new BackBits(src, p[0], b);
            int sl = bb.read(ll.log), so = bb.read(of.log), sm = bb.read(ml.log);
            int lp = 0;
            for (int i = 0; i < nseq; i++) {
                int llc = ll.sym[sl], ofc = of.sym[so], mlc = ml.sym[sm];
                if (ofc > 31) throw bad("offset code " + ofc);
                long ofv = (1L << ofc) + bb.read(ofc);
                int mlen = ML_BASE[mlc] + bb.read(ML_BITS[mlc]);
                int llen = LL_BASE[llc] + bb.read(LL_BITS[llc]);
                long off;
                if (ofv > 3) { off = ofv - 3; rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = (int) off; }
                else {
                    int idx = (int) ofv - 1 + (llen == 0 ? 1 : 0);
                    if (idx == 0) off = rep[0];
                    else {
                        off = idx == 3 ? rep[0] - 1L : rep[idx];
                        if (idx != 1) rep[2] = rep[1];
                        rep[1] = rep[0];
                        rep[0] = (int) off;
                    }
                }
                if (i + 1 < nseq) {
                    sl = ll.base[sl] + bb.read(ll.bits[sl]);
                    sm = ml.base[sm] + bb.read(ml.bits[sm]);
                    so = of.base[so] + bb.read(of.bits[so]);
                }
                if (lp + llen > litLen) throw bad("sequence takes more literals than the block has");
                room(llen + mlen);
                System.arraycopy(lit, lp, out, op, llen);
                op += llen;
                lp += llen;
                if (off <= 0 || off > op) throw bad("match offset " + off + " before the start");
                int from = (int) (op - off);
                if (off >= mlen) { System.arraycopy(out, from, out, op, mlen); op += mlen; }
                else for (int k = 0; k < mlen; k++) out[op++] = out[from + k];
            }
            if (bb.p != 0) throw bad("sequence stream not used up");
            int rest = litLen - lp;
            room(rest);
            System.arraycopy(lit, lp, out, op, rest);
            op += rest;
        }
    }

    static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
}
