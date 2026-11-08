package dev.nxnative.android;

/**
 * PackPolicy on the JVM: each argument line "<battery %> <charging 0|1> <thermal status> <was: - | battery | thermal> <override 0|1>" prints the reason
 * (or "-"), then whether Go on anyway applies at that status, then the readings for headroom 0.5. Run by dm/tools/tests/test_android_packpolicy.py.
 */
public final class PackPolicyJvmTest {
    public static void main(String[] a) {
        for (String line : a) {
            String[] f = line.split(" ");
            int pct = Integer.parseInt(f[0]), th = Integer.parseInt(f[2]);
            boolean ch = f[1].equals("1"), ov = f[4].equals("1");
            String r = PackPolicy.reason(pct, ch, th, f[3].equals("-") ? null : f[3], ov);
            System.out.println((r == null ? "-" : r) + "\t" + PackPolicy.canOverride(r, th) + "\t" + PackPolicy.readings(pct, ch, th, 0.5f));
        }
        System.out.println(PackPolicy.readings(50, false, 1, Float.NaN));
    }
}
