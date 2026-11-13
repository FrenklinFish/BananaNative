package dev.nxnative.android;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The in-app updater's decisions (UpdateCheck) on the JVM (no Android): version numbers and their order, the release it picks (drafts, pre-releases, tags that are
 * not vX.Y.Z, releases without an APK, the list's order), the asset digest, the cache-busting of the list's URL and the popup's notes. Run by dm/tools/tests/test_android_update.py; prints "ok &lt;n&gt;"
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
    static String images(String m) {
        StringBuilder b = new StringBuilder();
        for (ReleaseNotes.Block x : ReleaseNotes.parse(m)) if (x.kind == ReleaseNotes.Kind.IMAGE) b.append(b.length() > 0 ? ";" : "").append(x.url).append('|').append(x.text);
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
        eq("before after\n[image: shot]", md("before ![shot](https://a.b/c.png)after"), "an image after its block");
        eq("PARAGRAPH0 IMAGE0", kinds("before ![shot](https://a.b/c.png)after"), "image block kinds");
        eq("https://a.b/c.png|shot", images("![shot](https://a.b/c.png)"), "image url and alt");
        eq("IMAGE0", kinds("![shot](https://a.b/c.png)"), "an image alone has no empty paragraph");
        eq("https://a.b/c.png|", images("![](https://a.b/c.png)"), "no alt");
        eq("https://a.b/c.png|a b", images("![a **b**](https://a.b/c.png \"title\")"), "alt text plain, title goes");
        eq("https://a.b/x.png|one;https://a.b/y.png|two", images("![one](https://a.b/x.png) text ![two](https://a.b/y.png)"), "two images in order");
        eq("", images("![x](http://a.b/c.png) ![y](/rel.png) ![z](javascript:alert(1)) ![w](file:///x.png)"), "only https images");
        eq("https://a.b/c.png|shot", images("<img src=\"https://a.b/c.png\" alt=\"shot\">"), "HTML img");
        eq("https://a.b/c.png?x=1&y=2|", images("<img width='300' src='https://a.b/c.png?x=1&amp;y=2'/>"), "HTML img, single quotes, entity, no alt");
        eq("", images("<img alt=\"no source\">"), "HTML img without a src");
        eq("IMAGE0", kinds("[![shot](https://a.b/c.png)](https://a.b/page)"), "an image inside a link");
        eq("BULLET0 IMAGE0 BULLET0", kinds("- one ![p](https://a.b/c.png)\n- two"), "an image after its list item");
        eq("HEADING2 PARAGRAPH0 IMAGE0 PARAGRAPH0", kinds("## H\ntext\n\n![p](https://a.b/c.png)\n\nmore"), "an image between paragraphs");
        eq("a\nb", md("a\n<!-- hidden\nlines -->\nb"), "HTML comment left out");
        eq("a", md("a <!-- unclosed"), "unclosed comment left out");
        eq("a\nb", md("a\n\n---\n\nb"), "rule left out");
        eq("https://github.com/x/y stays", md("https://github.com/x/y stays"), "bare URL as is");
        eq("**open", md("**open"), "an unclosed marker stays");
        eq("[x](", md("[x]("), "a broken link stays");
    }

    static final UpdateCheck.Source GH = UpdateCheck.SOURCES.get(0), FJ = UpdateCheck.SOURCES.get(1);

    static UpdateCheck.Asset fjApk(String tag) {
        return new UpdateCheck.Asset("banana-native-" + tag + ".apk", "https://git.eden-emu.dev/moceure/BananaNative/releases/download/" + tag + "/banana-native-" + tag + ".apk", 44791088, null);
    }
    static UpdateCheck.Release ghRel(String tag) { return new UpdateCheck.Release(tag, false, true, "", Arrays.asList(apk(tag)), GH); }
    static UpdateCheck.Release fjRel(String tag) { return new UpdateCheck.Release(tag, false, false, "", Arrays.asList(fjApk(tag)), FJ); }
    static UpdateCheck.Update all(int[] cur, List<UpdateCheck.Release> gh, List<UpdateCheck.Release> fj) { return UpdateCheck.pickAll(Arrays.asList(gh, fj), cur); }
    static String where(UpdateCheck.Update u) { return u == null ? null : u.tag() + "@" + u.release.source.name; }

    /** The release sources: their fixed APIs, parsing both APIs' JSON (fixture: a recorded, trimmed Forgejo answer), the newest across sources, the asset hosts */
    static void sources(String fixture) throws java.io.IOException {
        eq(2, UpdateCheck.SOURCES.size(), "two sources");
        eq("GitHub", GH.name, "GitHub first");
        eq("https://api.github.com/repos/moceure/BananaNative/releases?per_page=10", GH.api, "GitHub's API");
        eq("git.eden-emu.dev", FJ.name, "then the Forgejo mirror");
        eq("https://git.eden-emu.dev/api/v1/repos/moceure/BananaNative/releases?limit=10", FJ.api, "Forgejo's API");
        eq("https://git.eden-emu.dev/moceure/BananaNative/releases", FJ.page, "Forgejo's release page");

        // Forgejo's real answer (v0.2.0)
        String json = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(fixture)), java.nio.charset.StandardCharsets.UTF_8);
        List<UpdateCheck.Release> fj = UpdateCheck.parse(json, FJ);
        eq(1, fj.size(), "one release");
        UpdateCheck.Release r = fj.get(0);
        eq("v0.2.0", r.tag, "tag_name");
        eq(false, r.draft, "draft");
        eq(false, r.prerelease, "prerelease");
        eq(true, r.body.startsWith("> This is the temporary home of Banana Native"), "body");
        eq(FJ, r.source, "its source");
        eq(1, r.assets.size(), "one asset");
        eq("banana-native-v0.2.0.apk", r.assets.get(0).name, "asset name");
        eq("https://git.eden-emu.dev/moceure/BananaNative/releases/download/v0.2.0/banana-native-v0.2.0.apk", r.assets.get(0).url, "browser_download_url");
        eq(44791088L, r.assets.get(0).size, "size");
        eq(null, r.assets.get(0).digest, "Forgejo gives no digest");
        UpdateCheck.Update u = UpdateCheck.pickAll(Arrays.asList(null, fj), UpdateCheck.appVersion("0.1.5"));
        eq("v0.2.0@git.eden-emu.dev", where(u), "GitHub down: the Forgejo release");
        eq(null, u == null ? "x" : u.sha256, "no SHA-256 to compare: the size is checked");
        eq(null, where(UpdateCheck.pickAll(Arrays.asList(null, fj), UpdateCheck.appVersion("0.2.0"))), "v0.2.0 is up to date");
        eq(true, UpdateCheck.allowedAsset(u == null ? null : u.apk.url, FJ), "its asset is on git.eden-emu.dev");

        // GitHub's shape (the fields the updater reads, digest included; escapes, nulls, numbers and unknown fields)
        String gh = "[{\"tag_name\":\"v0.2.1\",\"draft\":false,\"prerelease\":true,\"body\":\"a\\nb \\u00e9 \\\"q\\\"\",\"author\":{\"login\":\"x\",\"id\":1},\"reactions\":null,"
                + "\"assets\":[{\"name\":\"banana-native-v0.2.1.apk\",\"size\":45000000,\"digest\":\"sha256:" + HEX + "\",\"browser_download_url\":\"https://github.com/moceure/BananaNative/releases/download/v0.2.1/banana-native-v0.2.1.apk\",\"x\":1.5e3}]},"
                + "{\"tag_name\":\"v0.2.2\",\"draft\":true,\"body\":null,\"assets\":[]}]";
        List<UpdateCheck.Release> g = UpdateCheck.parse(gh, GH);
        eq(2, g.size(), "two GitHub releases");
        eq("a\nb é \"q\"", g.get(0).body, "escapes");
        eq("", g.get(1).body, "a null body");
        eq(true, g.get(1).draft, "a draft");
        eq(HEX, g.get(0).assets.get(0).digest.substring(7), "digest");
        eq("v0.2.1@GitHub", where(UpdateCheck.pickAll(Arrays.asList(g, fj), UpdateCheck.appVersion("0.2.0"))), "the newer one wins, draft v0.2.2 skipped");
        boolean threw = false;
        try { UpdateCheck.parse("{\"message\":\"Not Found\"}", GH); } catch (IllegalArgumentException e) { threw = true; }
        eq(true, threw, "an object (an error answer) is no list");
        threw = false;
        try { UpdateCheck.parse("[{\"tag_name\":\"v1\"", FJ); } catch (IllegalArgumentException e) { threw = true; }
        eq(true, threw, "a cut answer");
        threw = false;
        try { UpdateCheck.parse("<html>", FJ); } catch (IllegalArgumentException e) { threw = true; }
        eq(true, threw, "an HTML page");

        // the newest across sources
        int[] cur = UpdateCheck.appVersion("0.2.0");
        eq("v0.2.2@git.eden-emu.dev", where(all(cur, Arrays.asList(ghRel("v0.2.1")), Arrays.asList(fjRel("v0.2.2"), fjRel("v0.2.1")))), "Forgejo ahead");
        eq("v0.2.2@GitHub", where(all(cur, Arrays.asList(ghRel("v0.2.2")), Arrays.asList(fjRel("v0.2.1")))), "GitHub ahead");
        eq("v0.2.1@GitHub", where(all(cur, Arrays.asList(ghRel("v0.2.1")), Arrays.asList(fjRel("v0.2.1")))), "a tie: the earlier source");
        eq("v0.2.1@git.eden-emu.dev", where(all(cur, null, Arrays.asList(fjRel("v0.2.1")))), "GitHub failed");
        eq("v0.2.1@GitHub", where(all(cur, Arrays.asList(ghRel("v0.2.1")), null)), "Forgejo failed");
        eq("v0.2.1@git.eden-emu.dev", where(all(cur, Collections.emptyList(), Arrays.asList(fjRel("v0.2.1")))), "GitHub lists nothing");
        eq(null, where(all(cur, null, null)), "both failed");
        eq(null, where(UpdateCheck.pickAll(null, cur)), "no lists");
        eq("v0.10.0@git.eden-emu.dev", where(all(cur, Arrays.asList(ghRel("v0.9.0")), Arrays.asList(fjRel("v0.10.0")))), "numeric across sources");

        // asset hosts: only the source's own, https, port 443
        String fjUrl = "https://git.eden-emu.dev/moceure/BananaNative/releases/download/v0.2.1/x.apk", ghUrl = "https://github.com/moceure/BananaNative/releases/download/v0.2.1/x.apk";
        eq(true, UpdateCheck.allowedAsset(ghUrl, GH), "github.com");
        eq(true, UpdateCheck.allowedAsset("https://objects.githubusercontent.com/x/y.apk", GH), "GitHub's object storage");
        eq(true, UpdateCheck.allowedAsset("https://GitHub.com/a/b.apk", GH), "host case");
        eq(true, UpdateCheck.allowedAsset("https://git.eden-emu.dev:443/a/b.apk", FJ), "port 443");
        eq(false, UpdateCheck.allowedAsset(fjUrl, GH), "a GitHub release linking git.eden-emu.dev");
        eq(false, UpdateCheck.allowedAsset(ghUrl, FJ), "a Forgejo release linking GitHub");
        eq(false, UpdateCheck.allowedAsset("http://git.eden-emu.dev/a/b.apk", FJ), "http");
        eq(false, UpdateCheck.allowedAsset("https://git.eden-emu.dev.evil.example/a/b.apk", FJ), "a longer host");
        eq(false, UpdateCheck.allowedAsset("https://evil.example/git.eden-emu.dev/b.apk", FJ), "the host in the path");
        eq(false, UpdateCheck.allowedAsset("https://git.eden-emu.dev@evil.example/b.apk", FJ), "user part");
        eq(false, UpdateCheck.allowedAsset("https://x@git.eden-emu.dev/b.apk", FJ), "user part on the host");
        eq(false, UpdateCheck.allowedAsset("https://git.eden-emu.dev:8443/b.apk", FJ), "another port");
        eq(false, UpdateCheck.allowedAsset("https://sub.github.com/b.apk", GH), "a subdomain");
        eq(false, UpdateCheck.allowedAsset("not a url", FJ), "no URL");
        eq(false, UpdateCheck.allowedAsset(fjUrl, null), "no source");
        UpdateCheck.Release foreign = new UpdateCheck.Release("v0.2.3", false, false, "", Arrays.asList(
                new UpdateCheck.Asset("banana-native-v0.2.3.apk", "https://evil.example/banana-native-v0.2.3.apk", 10, null)), FJ);
        eq("v0.2.2@git.eden-emu.dev", where(all(cur, null, Arrays.asList(foreign, fjRel("v0.2.2")))), "a release whose APK is on another host is skipped");
    }

    public static void main(String[] args) throws java.io.IOException {
        if (args.length == 2 && args[0].equals("sources")) {   // sources <Forgejo fixture>
            sources(args[1]);
            System.out.println("ok " + n);
            return;
        }
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

        // cache-busting of the releases list's URL (Updater.releases asks UpdateCheck.busted(source.api, <now>))
        String fj = "https://git.eden-emu.dev/api/v1/repos/moceure/BananaNative/releases?limit=10", gh = "https://api.github.com/repos/moceure/BananaNative/releases?per_page=10";
        eq(fj + "&_=1762600000000", UpdateCheck.busted(fj, "1762600000000"), "a URL with a query: & and the token");
        eq(gh + "&_=t", UpdateCheck.busted(gh, "t"), "GitHub's too");
        eq("https://example.test/releases?_=t", UpdateCheck.busted("https://example.test/releases", "t"), "a URL without a query: ?");
        eq("https://example.test/releases?_=t", UpdateCheck.busted("https://example.test/releases?", "t"), "a bare ? gets no &");
        eq("https://example.test/r?a=1&_=t", UpdateCheck.busted("https://example.test/r?a=1&", "t"), "a trailing & gets no second one");
        eq("https://example.test/r?a=1&_=t#x", UpdateCheck.busted("https://example.test/r?a=1#x", "t"), "before a fragment");
        eq(true, UpdateCheck.busted(FJ.api, "1").startsWith(FJ.api), "the source's URL is a prefix");
        eq(true, UpdateCheck.busted(GH.api, "1").startsWith(GH.api), "GitHub's as well");
        eq(FJ.api, UpdateCheck.SOURCES.get(1).api, "the source's own URL is unchanged");
        eq(null, UpdateCheck.busted(null, "t"), "null");

        // release notes (ReleaseNotes)
        notes();

        // the hosts a release body's pictures may come from
        UpdateCheck.Source ighub = UpdateCheck.github("a/b"), iforge = UpdateCheck.forgejo("a/b");
        eq(true, UpdateCheck.allowedImage("https://github.com/user-attachments/assets/1234", ighub), "GitHub attachment");
        eq(true, UpdateCheck.allowedImage("https://private-user-images.githubusercontent.com/1/x.png?jwt=abc", ighub), "GitHub private image host");
        eq(true, UpdateCheck.allowedImage("https://raw.githubusercontent.com/a/b/main/x.png", ighub), "GitHub raw");
        eq(true, UpdateCheck.allowedImage("https://objects.githubusercontent.com/x", ighub), "GitHub storage");
        eq(false, UpdateCheck.allowedImage("https://raw.githubusercontent.com/a/b/main/x.png", iforge), "GitHub raw is not Forgejo's");
        eq(true, UpdateCheck.allowedImage("https://git.eden-emu.dev/attachments/uuid", iforge), "Forgejo attachment");
        eq(false, UpdateCheck.allowedImage("https://git.eden-emu.dev/attachments/uuid", ighub), "Forgejo host is not GitHub's");
        eq(false, UpdateCheck.allowedImage("http://github.com/x.png", ighub), "no cleartext");
        eq(false, UpdateCheck.allowedImage("https://example.org/x.png", ighub), "another host");
        eq(false, UpdateCheck.allowedImage("https://github.com.evil.org/x.png", ighub), "a host that only starts with an allowed one");
        eq(false, UpdateCheck.allowedImage("https://user@raw.githubusercontent.com/x.png", ighub), "no user part");
        eq(false, UpdateCheck.allowedImage("https://raw.githubusercontent.com:8443/x.png", ighub), "no other port");
        eq(false, UpdateCheck.allowedImage("ftp://github.com/x.png", ighub), "no other scheme");
        eq(false, UpdateCheck.allowedImage(null, ighub), "null url");
        eq(false, UpdateCheck.allowedImage("https://github.com/x.png", null), "null source");
        eq("117.7 MB", UpdateCheck.mb(123456789), "size text");

        System.out.println("ok " + n);
    }
}
