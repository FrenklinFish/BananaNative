package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shaders step's per-shader results (vk_android_build.py write_result: {@code <xlate>/<key>.r}, "DMVKRES1", the JSON head's length as uint32 LE, the JSON head, the
 * modules). The tool keeps a failed shader's result as well as a good one, and a rerun skips every key that has a result, so a failure would come back on every Retry
 * (DMSH_VK_REQUIRE_ALL then writes no library). Before the step runs, the app removes the results whose status is not "ok", so Retry translates those shaders again and
 * only those. This is done here, not in the tool: a change to vk_android_build.py moves its tools digest, which every result is stamped with.
 * No Android classes: dm/tools/tests/test_android_shader_results.py runs it on the Mac's JVM.
 */
final class ShaderResults {
    static final byte[] MAGIC = "DMVKRES1".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ST = Pattern.compile("\"st\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern KEY = Pattern.compile("\"key\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ERR = Pattern.compile("\"err\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private ShaderResults() {}

    /** One removed result: the shader's key, its status and the tool's reason */
    static final class Failed {
        final String key, status, reason;
        Failed(String key, String status, String reason) { this.key = key; this.status = status; this.reason = reason; }
        @Override public String toString() { return key + ": " + status + (reason.isEmpty() ? "" : ": " + reason); }
    }

    /**
     * The status of a result file ("ok", "translation failed", "spirv-val failed", ...) and its key and reason, or null when it is not a whole result (the tool rebuilds
     * those anyway: a missing magic, a cut file, no status). The JSON head's "st" comes before its reflection, so only its start is read when it is found there.
     */
    static String[] head(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] m = new byte[12];
            if (r.read(m) != 12 || !Arrays.equals(Arrays.copyOf(m, 8), MAGIC)) return null;
            long len = (m[8] & 0xffL) | (m[9] & 0xffL) << 8 | (m[10] & 0xffL) << 16 | (m[11] & 0xffL) << 24;
            if (len <= 0 || len > r.length() - 12) return null;
            byte[] j = new byte[(int) Math.min(len, 4096)];
            r.readFully(j);
            String s = new String(j, StandardCharsets.UTF_8);
            Matcher st = ST.matcher(s);
            if (!st.find()) {
                if (len == j.length) return null;
                j = new byte[(int) len];   // a head longer than its start: the status was further on
                r.seek(12);
                r.readFully(j);
                s = new String(j, StandardCharsets.UTF_8);
                st = ST.matcher(s);
                if (!st.find()) return null;
            }
            Matcher k = KEY.matcher(s), e = ERR.matcher(s);
            String key = k.find() ? k.group(1) : f.getName().replaceFirst("\\.r$", "");
            return new String[] {st.group(1), key, e.find() ? e.group(1) : ""};
        } catch (IOException e) {
            return null;
        }
    }

    /** Removes every result in {@code dir} whose status is not "ok" and returns them (none when the folder is missing). Results that cannot be read stay: the tool decides on those */
    static List<Failed> dropFailed(File dir) throws IOException {
        List<Failed> out = new ArrayList<>();
        File[] l = dir.listFiles((d, n) -> n.endsWith(".r"));
        if (l == null) return out;
        Arrays.sort(l);
        for (File f : l) {
            String[] h = head(f);
            if (h == null || h[0].equals("ok")) continue;
            if (!f.delete() && f.exists()) throw new IOException("cannot delete " + f);
            out.add(new Failed(h[1], h[0], h[2]));
        }
        return out;
    }
}
