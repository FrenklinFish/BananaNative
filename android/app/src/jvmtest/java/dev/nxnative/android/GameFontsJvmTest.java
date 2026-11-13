package dev.nxnative.android;

import java.io.File;
import java.util.Arrays;
import java.nio.file.Files;

/** GameFonts on the JVM (dm/tools/tests/test_android_gamefonts.py): the standard font is placed once, replaced when it differs, other fonts of the folder are left alone. Prints "ok" or throws */
public final class GameFontsJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("gamefonts").toFile(), dir = new File(root, "fonts");
        try {
            byte[] a = {0, 1, 0, 0, 7, 7}, b = {0, 1, 0, 0, 8, 8, 8};
            check(GameFonts.place(dir, GameFonts.STANDARD, a), "first start writes the font (the folder is made)");
            check(Arrays.equals(Files.readAllBytes(new File(dir, "standard.ttf").toPath()), a), "the file holds the font");
            check(!GameFonts.place(dir, GameFonts.STANDARD, a), "the same bytes: nothing written");
            File cjk = new File(dir, "cjk_kr.ttf"); Files.write(cjk.toPath(), new byte[] {9});
            check(GameFonts.place(dir, GameFonts.STANDARD, b), "an updated font replaces it");
            check(Arrays.equals(Files.readAllBytes(new File(dir, "standard.ttf").toPath()), b), "the new bytes");
            check(cjk.length() == 1, "another font of the folder is left alone");
            check(!new File(dir, "standard.ttf.part").exists(), "no temporary file is left");
            check(GameFonts.envLine(dir).equals("DM_FONT_DIR=" + dir.getPath()), "env line");
            check(GameFonts.ASSET.endsWith(".ttf") && GameFonts.STANDARD.equals("standard.ttf"), "names");
            File blocked = new File(root, "file"); Files.write(blocked.toPath(), new byte[] {1});
            boolean threw = false;
            try { GameFonts.place(new File(blocked, "fonts"), GameFonts.STANDARD, a); } catch (java.io.IOException e) { threw = true; }
            check(threw, "a folder that cannot be made is an error (the caller then leaves DM_FONT_DIR unset)");
        } finally {
            Files.walk(root.toPath()).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        System.out.println("ok");
    }
}
