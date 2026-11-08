package dev.nxnative.android;

/**
 * GameLanguage on the JVM: each argument "<choice or -> <phone tag>" prints the DM_LANG line (or "-") and the label, tab-separated. Run by
 * dm/tools/tests/test_android_gamelanguage.py, which also compares the mapping with the host's (t_oe.c).
 */
public final class GameLanguageJvmTest {
    public static void main(String[] a) {
        for (String arg : a) {
            String[] f = arg.split(" ", 2);
            String choice = f[0].equals("-") ? null : f[0], tag = f.length > 1 ? f[1] : "";
            String e = GameLanguage.env(choice, tag);
            System.out.println((e == null ? "-" : e) + "\t" + GameLanguage.label(choice, tag));
        }
    }
}
