package dev.nxnative.android;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader, plain Java (no Android), so the updater's parsing of the releases APIs (UpdateCheck.parse) runs on the JVM tests as on the phone. Objects
 * become Map&lt;String, Object&gt;, arrays List&lt;Object&gt;, strings String, numbers Long (or Double with a fraction or exponent), true/false Boolean, null null.
 * Malformed input throws IllegalArgumentException.
 */
final class Json {
    private final String s;
    private int i;
    private static final int MAX_DEPTH = 64;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("no JSON");
        Json j = new Json(text);
        j.ws();
        Object v = j.value(0);
        j.ws();
        if (j.i != j.s.length()) throw j.err("text after the value");
        return v;
    }

    private IllegalArgumentException err(String what) { return new IllegalArgumentException("JSON: " + what + " at " + i); }

    private void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw err("too deep");
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw err("unexpected '" + c + "'");
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected a key");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
            i++; ws();
            m.put(k, value(depth + 1));
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;   // the opening quote
        while (true) {
            if (i >= s.length()) throw err("unclosed string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("unclosed string");
            char e = s.charAt(i++);
            switch (e) {
                case '"': case '\\': case '/': b.append(e); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("short \\u escape");
                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (NumberFormatException x) { throw err("bad \\u escape"); }
                    i += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Object number() {
        int st = i;
        boolean real = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9' || c == '-' || c == '+') i++;
            else if (c == '.' || c == 'e' || c == 'E') { real = true; i++; }
            else break;
        }
        String n = s.substring(st, i);
        try { return real ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n); }
        catch (NumberFormatException x) { throw err("bad number " + n); }
    }

    // ---- Typed reads with defaults (a field of another type counts as absent) ----

    static String str(Map<?, ?> m, String k) { Object v = m.get(k); return v instanceof String ? (String) v : null; }
    static boolean bool(Map<?, ?> m, String k) { Object v = m.get(k); return v instanceof Boolean && (Boolean) v; }
    static long num(Map<?, ?> m, String k) { Object v = m.get(k); return v instanceof Number ? ((Number) v).longValue() : 0; }
}
