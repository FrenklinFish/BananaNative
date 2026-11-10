package dev.nxnative.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Signer on the JVM, driven by dm/tools/tests/test_android_signer.py. Commands, one per run:
 * <pre>
 * sha256 &lt;file&gt;                          → "sha256 &lt;hex&gt;" (the file's bytes as a certificate's DER)
 * tag &lt;tag&gt; &lt;play 0|1&gt; [&lt;signer hex&gt;...]  → "tag &lt;the build tag with its mark&gt;"
 * id [&lt;signer hex&gt;...]                   → "id &lt;the short signer id&gt;"
 * official                               → "official &lt;Signer.OFFICIAL&gt;"
 * </pre>
 */
public final class SignerJvmTest {
    public static void main(String[] a) throws IOException {
        String[] rest = a.length > 3 ? java.util.Arrays.copyOfRange(a, 3, a.length) : new String[0];
        switch (a[0]) {
            case "sha256": System.out.println("sha256 " + Signer.sha256(Files.readAllBytes(Paths.get(a[1])))); break;
            case "tag": System.out.println("tag " + Signer.tag(a[1], a[2].equals("1"), rest)); break;
            case "build": {   // build <play 0|1> <versionName> <commit> <dirty|-> <branch>: "build <buildId>|<buildTag>"
                String id = Signer.buildId(a[1].equals("1"), a[2], a[3], a[4].equals("-") ? "" : a[4], a[5]);
                System.out.println("build " + id + "|" + Signer.buildTag(a[1].equals("1"), id));
                break;
            }
            case "id":System.out.println("id " + Signer.shortId(java.util.Arrays.copyOfRange(a, 1, a.length))); break;
            case "official": System.out.println("official " + Signer.OFFICIAL); break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}
