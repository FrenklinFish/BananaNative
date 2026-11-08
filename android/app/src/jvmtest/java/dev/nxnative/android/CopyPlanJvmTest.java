package dev.nxnative.android;

import java.io.File;

/** CopyPlan on the JVM (dm/tools/tests/test_android_copyplan.py): where copies go, safe names, the space check. Prints "ok" or throws */
public final class CopyPlanJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) {
        File root = new File("/storage/emulated/0/Android/data/dev.nxnative.android/files");
        check(CopyPlan.games(root).getPath().equals(root.getPath() + "/games"), "games folder");
        check(CopyPlan.isCopy(root, root + "/games/DKB"), "a copy");
        check(CopyPlan.isCopy(root, root + "/games/game.nup"), "a copied .nup");
        check(!CopyPlan.isCopy(root, root + "/games"), "the games folder itself is no copy");
        check(!CopyPlan.isCopy(root, root + "/gamesX/a"), "a sibling with the same prefix is no copy");
        check(!CopyPlan.isCopy(root, "/storage/emulated/0/Switch2/DKB"), "shared storage is no copy");
        check(!CopyPlan.isCopy(root, null), "null");
        check(CopyPlan.inApp(root, root + "/packs/x") && !CopyPlan.inApp(root, root + "x/y") && !CopyPlan.inApp(root, "/sdcard/x"), "inApp");
        check("base".equals(CopyPlan.safeName(" base ")) && "a b.nup".equals(CopyPlan.safeName("a b.nup")), "plain names");
        for (String bad : new String[] {null, "", " ", ".", "..", "a/b", "a\\b", "a\0b"}) check(CopyPlan.safeName(bad) == null, "refused: " + bad);
        check(CopyPlan.partOf(new File("/x/games/DKB")).getPath().equals("/x/games/DKB.part"), "part");
        long g = 1L << 30;
        check(CopyPlan.fits(10 * g, 13 * g) == null, "fits");
        check(CopyPlan.fits(10 * g, 11 * g) != null && CopyPlan.fits(10 * g, 11 * g).contains("10.0 GiB"), "does not fit with the reserve: " + CopyPlan.fits(10 * g, 11 * g));
        System.out.println("ok");
    }
}
