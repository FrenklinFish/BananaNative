package dev.nxnative.android;

import java.util.Arrays;
import java.util.List;

/** Join settings accepted by the co-op host: IPv4[:port], with a room code for internet/VPN play. No network I/O. */
final class LinkPlayConfig {
    private LinkPlayConfig() {}
    static final int PORT = 47600;
    private static final String B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final class Address {
        final String endpoint;
        final long ip;
        Address(String endpoint, long ip) { this.endpoint = endpoint; this.ip = ip; }
    }

    private static int decimal(String s, int max) {
        if (s.isEmpty()) return -1;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            n = n * 10 + c - '0';
            if (n > max) return -1;
        }
        return n;
    }

    private static Address parseAddress(String value) {
        if (value == null) return null;
        String s = value.trim();
        int colon = s.indexOf(':');
        String host = colon < 0 ? s : s.substring(0, colon);
        int port = colon < 0 ? PORT : decimal(s.substring(colon + 1), 65535);
        if (port <= 0) return null;
        if ("localhost".equals(host)) return new Address("localhost:" + port, 0x7f000001L);
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        StringBuilder canonical = new StringBuilder();
        long ip = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 3) return null;
            int octet = decimal(parts[i], 255);
            if (octet < 0) return null;
            ip = (ip << 8) | octet;
            if (i != 0) canonical.append('.');
            canonical.append(octet);
        }
        return new Address(canonical + ":" + port, ip);
    }

    static String address(String value) {
        Address a = parseAddress(value);
        return a == null ? null : a.endpoint;
    }

    /** Match the native module's address scopes, including its localhost spelling. Tailscale needs internet mode. */
    static String addressError(String value, boolean internet) {
        Address a = parseAddress(value);
        if (a == null) return "Enter an IPv4 address, optionally followed by :port (1-65535).";
        long ip = a.ip;
        if (ip == 0 || ip == 0xffffffffL || (ip >>> 28) == 14 || (ip >>> 24) == 0)
            return "Enter a host address, not an unspecified, broadcast or multicast address.";
        boolean lan = (ip >>> 24) == 127 || (ip >>> 24) == 10 || (ip >>> 20) == 0xac1
                || (ip >>> 16) == 0xc0a8 || (ip >>> 16) == 0xa9fe;
        return internet || lan ? null : "Turn on Internet / Tailscale and enter the host's room code for this address.";
    }

    /** Crockford base32, as the host accepts it: spaces/hyphens and case do not matter; O/I/L are read as 0/1/1. */
    static String room(String value) {
        if (value == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-' || c == ' ') continue;
            if (c >= 'a' && c <= 'z') c = (char) (c - 'a' + 'A');
            if (c == 'O') c = '0';
            else if (c == 'I' || c == 'L') c = '1';
            if (B32.indexOf(c) < 0 || out.length() == 16) return null;
            out.append(c);
        }
        return out.length() == 16 ? out.toString() : null;
    }

    static boolean valid(String address, boolean internet, String room) {
        return addressError(address, internet) == null && (!internet || room(room) != null);
    }

    /** Off or invalid choices clear earlier global settings. The game's later typed environment lines can still override this choice. */
    static List<String> env(boolean enabled, String address, boolean internet, String room) {
        if (!enabled || !valid(address, internet, room))
            return Arrays.asList("DM_COOP=off", "DM_COOP_INTERNET=0", "DM_COOP_ROOM=");
        String code = room(room);
        return Arrays.asList("DM_COOP=join:" + address(address), "DM_COOP_INTERNET=" + (internet ? "1" : "0"),
                "DM_COOP_ROOM=" + (internet ? code : ""));
    }
}
