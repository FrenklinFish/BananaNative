package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * The shared-font stand-ins (dm/third_party/fonts, the APK's assets/fonts/): the host serves games the shared fonts from $DM_FONT_DIR (dm/host/hle_sdk16.c), so that text does not depend on
 * the phone's own fonts. The standard font, Noto Sans Regular (SIL OFL 1.1), is copied to <files>/fonts/standard.ttf when that file is not the same bytes (an update of the APK replaces it);
 * the button icons' stand-in (build.gradle nxExtFont: dm/tools/make_ext_font.py, its letters from the same Noto Sans) likewise to nintendo_ext.ttf when the APK has it (a build without
 * fontTools has none). Other files in the folder (cjk_sc.ttf, cjk_tc.ttf, cjk_kr.ttf: fonts the player or a later download put there) are left alone. A DM_FONT_DIR line of the player's comes later and wins.
 * No Android classes: GameFontsJvmTest runs it.
 */
final class GameFonts {
    private GameFonts() {}

    /** The asset the APK carries (build.gradle nxFontAssets copies it from dm/third_party/fonts) and the name the host looks for in $DM_FONT_DIR */
    static final String ASSET = "fonts/NotoSans-Regular-subset.ttf", STANDARD = "standard.ttf";
    /** The button icons' asset (build.gradle nxExtFont) and the host's name for it in $DM_FONT_DIR (shared-font type 5) */
    static final String EXT_ASSET = "fonts/nintendo_ext.ttf", EXT = "nintendo_ext.ttf";

    /** Writes data to dir/name unless the file already holds exactly these bytes; through a temporary file, so a game started meanwhile never reads half a font. Returns whether it wrote */
    static boolean place(File dir, String name, byte[] data) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        File f = new File(dir, name);
        if (f.isFile() && f.length() == data.length && Arrays.equals(Files.readAllBytes(f.toPath()), data)) return false;
        File tmp = new File(dir, name + ".part");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /** The env line that points the host at the folder */
    static String envLine(File dir) { return "DM_FONT_DIR=" + dir.getPath(); }
}
