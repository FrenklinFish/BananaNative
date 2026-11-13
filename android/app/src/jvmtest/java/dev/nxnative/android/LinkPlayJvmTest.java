package dev.nxnative.android;

import java.util.Arrays;
import java.util.List;

/** The persisted choice to launch-environment boundary. Values are synthetic, never private peers. */
public final class LinkPlayJvmTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void ok(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("condition failed");
    }
    public static void main(String[] args) {
        String[] fields = new String[28];
        Arrays.fill(fields, "");
        fields[0] = "0000000000000001"; fields[1] = "Test"; fields[2] = "0";
        equal(false, Profile.parse(String.join("\t", fields)).get(0).linkPlay);
        fields[27] = "1";
        equal(true, Profile.parse(String.join("\t", fields)).get(0).linkPlay);
        fields[27] = "0";
        equal(false, Profile.parse(String.join("\t", fields)).get(0).linkPlay);
        equal(false, Profile.parse(String.join("\t", Arrays.copyOf(fields, 27))).get(0).linkPlay);
        String code = "0123456789ABCDEF";
        List<String> off = Arrays.asList("DM_COOP=off", "DM_COOP_INTERNET=0", "DM_COOP_ROOM=");
        equal(off, LinkPlayConfig.env(false, "localhost", false, ""));
        equal(off, LinkPlayConfig.env(false, "203.0.113.8", true, code));
        equal(Arrays.asList("DM_COOP=join:localhost:47600", "DM_COOP_INTERNET=0", "DM_COOP_ROOM="),
                LinkPlayConfig.env(true, "localhost", false, ""));
        equal(Arrays.asList("DM_COOP=join:203.0.113.8:65535", "DM_COOP_INTERNET=1", "DM_COOP_ROOM=" + code),
                LinkPlayConfig.env(true, " 203.000.113.008:65535 ", true, "0123-4567-89ab-cdef"));
        equal("localhost:1", LinkPlayConfig.address("localhost:00001"));
        equal("127.0.0.1:47600", LinkPlayConfig.address("127.0.0.1"));
        equal("00113456789ABCDE", LinkPlayConfig.room("OoIl-3456-789A-BCDE"));
        for (String invalid : new String[] {"", "localhost:0", "localhost:65536", "localhost:", "localhost:1:2", "localhost:-1",
                "host.example", "::1", "256.0.0.1", "127.0.0", "127.0..1", "127.0.0.0001", "127.0.0.1\nDM_COOP=auto",
                "127.0.0.1:1\nDM_COOP=auto"}) {
            ok(LinkPlayConfig.addressError(invalid, true) != null);
            equal(off, LinkPlayConfig.env(true, invalid, true, code));
        }
        for (String invalid : new String[] {"0.0.0.0", "0.1.2.3", "255.255.255.255", "224.0.0.1", "239.255.255.254"})
            ok(LinkPlayConfig.addressError(invalid, true) != null);
        for (String local : new String[] {"127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1", "169.254.1.1"})
            equal(null, LinkPlayConfig.addressError(local, false));
        for (String outside : new String[] {"172.15.255.254", "172.32.0.1", "100.64.0.1", "100.127.255.254", "203.0.113.8"}) {
            ok(LinkPlayConfig.addressError(outside, false) != null);
            equal(null, LinkPlayConfig.addressError(outside, true));
        }
        equal(off, LinkPlayConfig.env(true, "203.0.113.8", true, ""));
        equal(off, LinkPlayConfig.env(true, "203.0.113.8", false, code));
        for (String invalid : new String[] {"", "0123456789ABCDE", "0123456789ABCDEFG", "0123456789ABCDEU", "0123456789ABCDE\n", "0123456789ABCDE\0"})
            equal(null, LinkPlayConfig.room(invalid));
        equal(code, LinkPlayConfig.room(" 0123 4567 89ab cdef "));
        equal(Arrays.asList("DM_COOP=join:localhost:47600", "DM_COOP_INTERNET=0", "DM_COOP_ROOM="),
                LinkPlayConfig.env(true, "localhost", false, code));
        System.out.println(checks + " Link Play configuration checks passed");
    }
}
