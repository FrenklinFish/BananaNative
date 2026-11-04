package dev.nxnative.android;

import java.util.Arrays;
import java.util.List;

/**
 * What an emulator frontend sends FrontendLaunch, read by Frontend on the JVM (no Android): file:// URIs, provider URIs that carry a /storage path, the shared
 * storage's aliases, the card a path names, title IDs. Run by dm/tools/tests/test_android_frontend.py; prints "ok <n>" and exits 0, or the first failure and exits 1.
 */
public final class FrontendJvmTest {
    private static int n;

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": want " + want + ", got " + got);
        n++;
    }

    public static void main(String[] args) {
        // file:// URIs: ES-DE's %ROM% as a URI, Daijisho's {file.uri}, escapes, an authority that is not this phone
        eq("/storage/emulated/0/Games/Banana Game.nup", Frontend.fileUri("file:///storage/emulated/0/Games/Banana%20Game.nup"), "escaped space");
        eq("/storage/emulated/0/a+b.nup", Frontend.fileUri("file:///storage/emulated/0/a+b.nup"), "plus stays");
        eq("/storage/emulated/0/é.nup", Frontend.fileUri("file:///storage/emulated/0/%C3%A9.nup"), "UTF-8 escape");
        eq("/storage/1234-ABCD/x.nup", Frontend.fileUri("file:/storage/1234-ABCD/x.nup"), "file: with one slash");
        eq("/sdcard/x.nup", Frontend.fileUri("FILE://localhost/sdcard/x.nup"), "localhost authority, upper-case scheme");
        eq("/sdcard/x.nup", Frontend.fileUri("file:///sdcard/x.nup?y=1#z"), "query and fragment go");
        eq("/sdcard/100%.nup", Frontend.fileUri("file:///sdcard/100%.nup"), "a bare % stays");
        eq(null, Frontend.fileUri("file://otherhost/sdcard/x.nup"), "another host");
        eq(null, Frontend.fileUri("content://x/y"), "not a file URI");
        eq(null, Frontend.fileUri("file:relative.nup"), "relative path");
        eq(null, Frontend.fileUri(null), "null");
        // provider URIs (their decoded path): a FileProvider root path carries the file's path; SAF ids do not
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.embeddedPath("/root/storage/emulated/0/ROMs/x.nup"), "FileProvider root");
        eq("/sdcard/ROMs/x.nup", Frontend.embeddedPath("/files/sdcard/ROMs/x.nup"), "sdcard root");
        eq("/mnt/media_rw/1234-ABCD/x.nup", Frontend.embeddedPath("/ext/mnt/media_rw/1234-ABCD/x.nup"), "media_rw");
        eq(null, Frontend.embeddedPath("/document/primary:ROMs/x.nup"), "SAF document id");
        eq(null, Frontend.embeddedPath(null), "null path");
        // one name per file
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.normal("/sdcard/ROMs/x.nup"), "/sdcard");
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.normal("/storage/self/primary/ROMs/x.nup"), "self/primary");
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.normal("/mnt/sdcard//ROMs/./x.nup"), "/mnt/sdcard, // and /./");
        eq("/storage/emulated/0", Frontend.normal("/sdcard/"), "the alias itself");
        eq("/storage/emulated/0/Games/dump", Frontend.normal("/storage/emulated/0/Games/dump/"), "trailing slash");
        eq("/sdcardx/x.nup", Frontend.normal("/sdcardx/x.nup"), "a name that only starts like an alias");
        eq("/storage/1234-ABCD/x.nup", Frontend.normal("/storage/1234-ABCD/x.nup"), "an SD card stays");
        // the card a path names
        List<String> cards = Arrays.asList("/storage/emulated/0/Games/a.nup", "/storage/emulated/0/Games/b-dump", "/storage/1234-ABCD/c.nup");
        eq(0, Frontend.card(cards, "/sdcard/Games/a.nup"), "alias of the first card");
        eq(1, Frontend.card(cards, "/storage/emulated/0/Games/b-dump/"), "folder with a slash");
        eq(2, Frontend.card(cards, "/storage/1234-ABCD/c.nup"), "SD card");
        eq(-1, Frontend.card(cards, "/storage/emulated/0/Games/A.nup"), "case matters");
        eq(true, Frontend.same("/sdcard/x", "/storage/self/primary/x"), "two aliases");
        eq(false, Frontend.same(null, "/x"), "null");
        // title IDs
        eq("040074A01BF12000", Frontend.titleId("040074a01bf12000"), "lower case");
        eq("040074A01BF12000", Frontend.titleId(" 0x040074A01BF12000 "), "0x and spaces");
        eq(null, Frontend.titleId("040074A01BF1200"), "15 digits");
        eq(null, Frontend.titleId("040074A01BF1200G"), "not hex");
        eq(null, Frontend.titleId(null), "null");
        System.out.println("ok " + n);
    }
}
