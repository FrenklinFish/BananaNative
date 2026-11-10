package dev.nxnative.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * MemText on the JVM, driven by dm/tools/tests/test_android_memtext.py. Commands, one per run:
 * <pre>
 * rss &lt;file&gt;                       → "rss &lt;VmRSS in bytes, or -1&gt;"
 * app &lt;bytes&gt;                      → "app &lt;the overlay's game line&gt;"
 * system &lt;total&gt; &lt;avail&gt;          → "system &lt;the overlay's phone line&gt;"
 * </pre>
 */
public final class MemTextJvmTest {
    public static void main(String[] a) throws IOException {
        switch (a[0]) {
            case "rss": System.out.println("rss " + MemText.vmRss(new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.US_ASCII))); break;
            case "app": System.out.println("app " + MemText.app(Long.parseLong(a[1]))); break;
            case "system": System.out.println("system " + MemText.system(Long.parseLong(a[1]), Long.parseLong(a[2]))); break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}
