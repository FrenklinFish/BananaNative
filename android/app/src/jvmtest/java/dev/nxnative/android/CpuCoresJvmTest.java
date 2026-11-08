package dev.nxnative.android;

import java.io.File;

/**
 * CpuCores on the JVM: {@code <cpu folder> <cores>} prints "clocks <kHz>,..." and "workers <n>" for a folder laid out as /sys/devices/system/cpu, and
 * {@code - <cores> <kHz>...} prints "workers <n>" for the clocks given. Run by dm/tools/tests/test_android_cpucores.py.
 */
public final class CpuCoresJvmTest {
    public static void main(String[] a) {
        int n = Integer.parseInt(a[1]);
        long[] f;
        if (a[0].equals("-")) { f = new long[a.length - 2]; for (int i = 2; i < a.length; i++) f[i - 2] = Long.parseLong(a[i]); }
        else {
            f = CpuCores.maxClocks(new File(a[0]), n);
            StringBuilder b = new StringBuilder("clocks ");
            for (int i = 0; i < f.length; i++) b.append(i > 0 ? "," : "").append(f[i]);
            System.out.println(b);
        }
        System.out.println("workers " + CpuCores.shaderWorkers(f, n));
    }
}
