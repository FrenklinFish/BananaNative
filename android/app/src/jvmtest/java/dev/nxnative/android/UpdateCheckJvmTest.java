package dev.nxnative.android;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The in-app updater's decisions (UpdateCheck) on the JVM (no Android): version numbers and their order, the release it picks (drafts, pre-releases, tags that are
 * not vX.Y.Z, releases without an APK, the list's order), the asset digest and the popup's notes. Run by dm/tools/tests/test_android_update.py; prints "ok &lt;n&gt;"
 * and exits 0, or the first failure and exits 1.
 */
public final class UpdateCheckJvmTest {
    private static int n;

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": want " + want + ", got " + got);
        n++;
    }

    static String v(int[] x) { return x == null ? null : UpdateCheck.text(x); }

    static final String HEX = "ad232b1c519e8f14d1f6dbeaa2569847002714306bc93f18924dd3a7e8abe23e";

    static UpdateCheck.Asset apk(String tag) {
        return new UpdateCheck.Asset("banana-native-" + tag + ".apk", "https://github.com/moceure/BananaNative/releases/download/" + tag + "/banana-native-" + tag + ".apk", 123456789, "sha256:" + HEX);
    }
    static UpdateCheck.Release rel(String tag, boolean draft, boolean pre, UpdateCheck.Asset... as) { return new UpdateCheck.Release(tag, draft, pre, "notes of " + tag, Arrays.asList(as)); }
    static String pick(int[] cur, UpdateCheck.Release... rs) { UpdateCheck.Update u = UpdateCheck.pick(Arrays.asList(rs), cur); return u == null ? null : u.tag(); }

    static String md(String m) { return ReleaseNotes.plain(ReleaseNotes.parse(m)); }
    static String spans(String m) {
        StringBuilder b = new StringBuilder();
        for (ReleaseNotes.Block x : ReleaseNotes.parse(m)) for (ReleaseNotes.Span sp : x.spans) b.append(sp.style).append(':').append(x.text, sp.start, sp.end).append(sp.url != null ? "->" + sp.url : "").append(';');
        return b.toString();
    }
    static String kinds(String m) {
        StringBuilder b = new StringBuilder();
        for (ReleaseNotes.Block x : ReleaseNotes.parse(m)) b.append(x.kind).append(x.level).append(' ');
        return b.toString().trim();
    }

    static void notes() {
        eq("", md(null), "no body");
        eq("", md("  \r\n "), "blank body");
        eq("HEADING1 HEADING2 HEADING3 PARAGRAPH0", kinds("# One\n## Two ##\n### Three\nText"), "headings, closing hashes");
        eq("Two", md("## Two ##"), "closing hashes go");
        eq("a b\nc", md("a\nb\n\nc"), "lines joined, blank lines split paragraphs");
        eq("a\nb", md("a\r\n\r\nb"), "CRLF");
        eq("• one\n• two\n  • nested\n• three", md("- one\n* two\n  - nested\n+ three"), "bullets and nesting");
        eq("BULLET0 BULLET0 BULLET1 BULLET0", kinds("- one\n* two\n  - nested\n+ three"), "bullet levels");
        eq("1. first line and its next\n2) second", md("1. first line\n   and its next\n2) second"), "numbers, an item's next line");
        eq("PARAGRAPH0 NUMBER0 NUMBER1", kinds("Intro\n1. a\n   1. b"), "numbered nesting");
        eq("bold italic code link", md("**bold** *italic* `code` [link](https://x.y/z)"), "inline markers go");
        eq("BOLD:bold;ITALIC:italic;CODE:code;LINK:link->https://x.y/z;", spans("**bold** *italic* `code` [link](https://x.y/z)"), "inline spans");
        eq("BOLD:b;ITALIC:i;", spans("__b__ _i_"), "underscores");
        eq("snake_case_name stays", md("snake_case_name stays"), "underscores inside a word");
        eq("", spans("snake_case_name stays"), "no italic inside a word");
        eq("2 * 3 * 4", md("2 * 3 * 4"), "spaced stars are no emphasis");
        eq("CODE:040074A01BF12000;BOLD:040074A01BF12000: 30 fps;", spans("**`040074A01BF12000`: 30 fps** more"), "code inside bold");
        eq("CODE:a*b*c;", spans("`a*b*c`"), "no emphasis inside code");
        eq("*not italic* [x]", md("\\*not italic\\* \\[x\\]"), "escapes");
        eq("Setup: frontend guide.", md("Setup: [frontend guide](https://github.com/a/b/blob/v0.1.1/x.md)."), "link text");
        eq("LINK:guide->https://a.b/c;", spans("[guide](https://a.b/c \"title\")"), "link title goes");
        eq("before after", md("before ![shot](https://a.b/c.png)after"), "image left out");
        eq("a\nb", md("a\n<!-- hidden\nlines -->\nb"), "HTML comment left out");
        eq("a", md("a <!-- unclosed"), "unclosed comment left out");
        eq("a\nb", md("a\n\n---\n\nb"), "rule left out");
        eq("https://github.com/x/y stays", md("https://github.com/x/y stays"), "bare URL as is");
        eq("**open", md("**open"), "an unclosed marker stays");
        eq("[x](", md("[x]("), "a broken link stays");
    }

    public static void main(String[] args) throws java.io.IOException {
        if (args.length == 2 && args[0].equals("notes")) {   // notes <file>: the parsed notes, then the spans, for the Python side
            String m = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1])), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println(md(m));
            System.out.println("--");
            System.out.println(kinds(m));
            System.out.println(spans(m));
            return;
        }
        // versions
        eq("0.1.4", v(UpdateCheck.tagVersion("v0.1.4")), "v tag");
        eq("0.1.4", v(UpdateCheck.tagVersion("0.1.4")), "bare tag");
        eq("1.20.300", v(UpdateCheck.tagVersion("V1.20.300")), "upper-case V, several digits");
        eq(null, v(UpdateCheck.tagVersion("v0.1.4-rc1")), "rc tag");
        eq(null, v(UpdateCheck.tagVersion("v0.1")), "two parts");
        eq(null, v(UpdateCheck.tagVersion("nightly")), "name");
        eq(null, v(UpdateCheck.tagVersion("v0.1.4.1")), "four parts");
        eq(null, v(UpdateCheck.tagVersion("")), "empty");
        eq(null, v(UpdateCheck.tagVersion(null)), "null");
        eq("0.1.3", v(UpdateCheck.appVersion("0.1.3")), "app version");
        eq("0.1.1", v(UpdateCheck.appVersion("0.1.1-test")), "app version with a suffix");
        eq(null, v(UpdateCheck.appVersion("0.1-bringup")), "the test app's default");
        eq(null, v(UpdateCheck.appVersion("0.1.1234567")), "a part of more than six digits");
        eq(1, Integer.signum(UpdateCheck.compare(UpdateCheck.tagVersion("v0.1.10"), UpdateCheck.tagVersion("v0.1.9"))), "numeric, not text");
        eq(1, Integer.signum(UpdateCheck.compare(UpdateCheck.tagVersion("v1.0.0"), UpdateCheck.tagVersion("v0.99.99"))), "major first");
        eq(-1, Integer.signum(UpdateCheck.compare(UpdateCheck.tagVersion("v0.2.0"), UpdateCheck.tagVersion("v0.10.0"))), "minor numeric");
        eq(0, UpdateCheck.compare(UpdateCheck.tagVersion("v0.1.3"), UpdateCheck.appVersion("0.1.3")), "equal");

        // picking
        int[] cur = UpdateCheck.appVersion("0.1.3");
        eq("v0.1.4", pick(cur, rel("v0.1.4", false, true, apk("v0.1.4")), rel("v0.1.3", false, true, apk("v0.1.3"))), "a newer pre-release counts");
        eq("v0.1.4", pick(cur, rel("v0.1.4", false, false, apk("v0.1.4"))), "a full release too");
        eq(null, pick(cur, rel("v0.1.3", false, true, apk("v0.1.3")), rel("v0.1.2", false, true, apk("v0.1.2"))), "nothing newer");
        eq(null, pick(cur, rel("v0.1.5", true, true, apk("v0.1.5"))), "a draft is skipped");
        eq("v0.1.4", pick(cur, rel("v0.1.5", true, true, apk("v0.1.5")), rel("v0.1.4", false, true, apk("v0.1.4"))), "the newest that is no draft");
        eq("v0.1.4", pick(cur, rel("v0.2.0-rc1", false, true, apk("v0.2.0-rc1")), rel("nightly", false, true, apk("nightly")), rel("v0.1.4", false, true, apk("v0.1.4"))), "non-semver tags skipped");
        eq("v0.1.10", pick(cur, rel("v0.1.9", false, true, apk("v0.1.9")), rel("v0.1.10", false, true, apk("v0.1.10")), rel("v0.1.4", false, true, apk("v0.1.4"))), "the highest whatever the order");
        UpdateCheck.Asset zip = new UpdateCheck.Asset("source.zip", "https://github.com/x/y/releases/download/v0.1.6/source.zip", 10, null);
        eq("v0.1.4", pick(cur, rel("v0.1.6", false, true, zip), rel("v0.1.5", false, true), rel("v0.1.4", false, true, apk("v0.1.4"))), "a release without an APK is skipped");
        UpdateCheck.Asset http = new UpdateCheck.Asset("x.apk", "http://example.com/x.apk", 10, null);
        eq(null, pick(cur, rel("v0.1.6", false, true, http)), "an APK without an https link is skipped");
        UpdateCheck.Asset bad = new UpdateCheck.Asset("x.apk", "https://github.com/x/y/releases/download/v0.1.6/x.apk", 10, "sha256:1234");
        eq(null, pick(cur, rel("v0.1.6", false, true, bad)), "an APK whose SHA-256 is malformed is skipped");
        UpdateCheck.Asset upper = new UpdateCheck.Asset("Banana-Native.APK", "https://github.com/x/y/releases/download/v0.1.6/Banana-Native.APK", 10, null);
        UpdateCheck.Update u = UpdateCheck.pick(Collections.singletonList(rel("v0.1.6", false, true, zip, upper)), cur);
        eq("Banana-Native.APK", u == null ? null : u.apk.name, "the .apk asset, any case, beside other assets");
        eq(null, u == null ? "x" : u.sha256, "no digest: nothing to compare");
        u = UpdateCheck.pick(Collections.singletonList(rel("v0.1.4", false, true, apk("v0.1.4"))), cur);
        eq(HEX, u == null ? null : u.sha256, "the digest's hex");
        eq(123456789L, u == null ? null : u.apk.size, "size");
        eq(null, UpdateCheck.pick(Collections.singletonList(rel("v0.1.4", false, true, apk("v0.1.4"))), null), "no app version: no update");
        eq(null, UpdateCheck.pick(null, cur), "no list");
        List<UpdateCheck.Release> withNull = Arrays.asList(null, new UpdateCheck.Release("v0.1.4", false, true, null, null));
        eq(null, UpdateCheck.pick(withNull, cur), "null entries and null assets");

        // digests
        eq(HEX, UpdateCheck.sha256("sha256:" + HEX), "sha256");
        eq(HEX, UpdateCheck.sha256("SHA256:" + HEX.toUpperCase()), "upper case");
        eq(HEX, UpdateCheck.sha256("  sha256:" + HEX + " \n"), "spaces");
        eq(null, UpdateCheck.sha256(null), "absent");
        eq(null, UpdateCheck.sha256(""), "empty");
        eq(null, UpdateCheck.sha256("sha512:" + HEX + HEX), "another algorithm: not checked");
        eq(null, UpdateCheck.sha256(HEX), "no algorithm: not checked");
        eq(UpdateCheck.BAD_DIGEST, UpdateCheck.sha256("sha256:" + HEX.substring(1)), "63 digits");
        eq(UpdateCheck.BAD_DIGEST, UpdateCheck.sha256("sha256:" + HEX.substring(1) + "g"), "not hex");
        eq(UpdateCheck.BAD_DIGEST, UpdateCheck.sha256("sha256:"), "no value");

        // release notes (ReleaseNotes)
        notes();
        eq("117.7 MB", UpdateCheck.mb(123456789), "size text");

        System.out.println("ok " + n);
    }
}
