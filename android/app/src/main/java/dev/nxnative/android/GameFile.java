package dev.nxnative.android;

import java.util.List;

/**
 * A game whose file or folder is gone (deleted, moved, renamed; for a .nup used where it is, also its permission taken back): what the card and the readiness check
 * say, and picking it again, which links the card to the new file when it holds the same game, so its pack, settings and saves (all kept by title ID or by the
 * card) stay. No Android classes: GameFileJvmTest checks it.
 */
final class GameFile {
    private GameFile() {}

    /** The card's and the readiness check's sentence */
    static String goneText(String name) { return "The game file is gone: " + name + ". Pick it again, or remove the game."; }

    /**
     * Whether a game read by its path is gone: nothing there, while the app may read where it was (All files access, or the game in app storage). Without access
     * nothing can be told (the card asks for access instead)
     */
    static boolean gone(boolean exists, boolean readable) { return readable && !exists; }

    /** Why the picked file cannot take the card's place, in a sentence; null when it can (the same title ID) */
    static String relinkProblem(String cardTitle, String cardName, String pickedTitle, String pickedName) {
        if (pickedTitle == null) return pickedName + " is not a game this app can read.";
        if (!pickedTitle.equalsIgnoreCase(cardTitle))
            return pickedName + " holds another game (" + pickedTitle + "), not " + cardName + " (" + cardTitle + "). Pick " + cardName + "'s file, or add it with Add game.";
        return null;
    }

    /**
     * The pack folder a card should use when the one it remembers is gone (deleted, moved): null while it is there (nothing changes), else the pack the usual search
     * found (the app's packs/&lt;id&gt; first, Folders.packFor), else the app's packs/&lt;id&gt;, where Prepare and Import pack… put one. Never the folder that is gone
     */
    static String packFix(String remembered, boolean there, String found, String appPack) {
        if (there) return null;
        if (found != null && !found.equals(remembered)) return found;
        return appPack.equals(remembered) ? null : appPack;
    }

    /** The card's and Play's sentence for a picked save folder that is gone */
    static String saveGoneText(String name) { return "The save folder " + name + " is gone. Use the app's save, or pick the folder again."; }

    /**
     * Whether a picked save folder (Folders, folder_save_&lt;id&gt;) is gone: picked, not there, where the app may look (All files access, or app storage). A gone
     * folder is never made again by itself: the next start would play a new empty save in it
     */
    static boolean saveGone(String picked, boolean exists, boolean readable) { return picked != null && readable && !exists; }

    /** The index of the card with this game path, -1 when none */
    static <T extends GameList.View> int index(List<T> list, String gameDir) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).gameDir().equals(gameDir)) return i;
        return -1;
    }
}
