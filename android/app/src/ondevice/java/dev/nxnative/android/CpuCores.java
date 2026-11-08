package dev.nxnative.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The shaders step's worker interpreters (vk_android_build.py DMSH_VK_PROCS): one for each big and mid core, at most {@link #MAX_WORKERS}. A core is little when its
 * highest clock (cpufreq cpuinfo_max_freq) is under {@link #LITTLE} of the fastest core's (a Cortex-A520 class core at about 2 GHz beside 3.2-3.3 GHz cores: 0.6-0.7;
 * a phone of only big and mid cores, as the Snapdragon 8 Elite's 6 + 2 at 3.5 / 4.5 GHz, 0.79, has none). Without clock data it is the cores less two, as the tools' J.
 */
final class CpuCores {
    static final double LITTLE = 0.75;
    static final int MAX_WORKERS = 4;   // more workers each hold a copy of the profile's tables (memory) for little more speed

    private CpuCores() {}

    /** The workers for cores whose highest clocks are maxKhz (0 or less: not known), with available cores in all */
    static int shaderWorkers(long[] maxKhz, int available) {
        long high = 0;
        for (long f : maxKhz) high = Math.max(high, f);
        int n;
        if (high == 0) n = available - 2;
        else { n = 0; for (long f : maxKhz) if (f >= LITTLE * high) n++; }
        return Math.max(1, Math.min(MAX_WORKERS, n));
    }

    /** The highest clock of each core of this device, from /sys/devices/system/cpu/cpu<n>/cpufreq/cpuinfo_max_freq (0 where it cannot be read) */
    static long[] maxClocks(File cpuDir, int available) {
        long[] f = new long[available];
        for (int i = 0; i < available; i++) {
            try { f[i] = Long.parseLong(new String(Files.readAllBytes(new File(cpuDir, "cpu" + i + "/cpufreq/cpuinfo_max_freq").toPath()), StandardCharsets.US_ASCII).trim()); }
            catch (Exception e) { f[i] = 0; }
        }
        return f;
    }

    static int shaderWorkers() {
        int n = Runtime.getRuntime().availableProcessors();
        return shaderWorkers(maxClocks(new File("/sys/devices/system/cpu"), n), n);
    }
}
