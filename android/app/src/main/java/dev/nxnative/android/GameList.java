package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One card per game on Home. Two list entries are the same game when their dump paths name the same file (canon: the shared storage's aliases, "//", a trailing
 * '/', symbolic links), or when they are of the same title and the same game build (BuildCheck.gameKey: a folder and the .nup made from it). Entries are added
 * through {@link #matches} (an add replaces the entries it matches) and a list that already has duplicates is collapsed by {@link #collapse}, which keeps the
 * entry that is ready to play, else the one played last, else the one added last; no file is touched. No Android classes: the JVM test (GameListJvmTest) runs it.
 */
final class GameList {
    private GameList() {}

    /** What the list logic reads of an entry (Game) */
    interface View {
        String titleId();
        String gameDir();
        long used();   // when it was last started (ms), 0: never
    }

    /** One name per file: Frontend.normal (aliases of the shared storage, "//", "/./", a trailing '/'), then the file system's canonical path when it resolves */
    static String canon(String path) {
        if (path == null || path.isEmpty()) return path;
        String n = Frontend.normal(path);
        try {
            File f = new File(n);
            if (f.exists()) n = Frontend.normal(f.getCanonicalPath());
        } catch (IOException | SecurityException e) {
            // the plain normal form
        }
        return n;
    }

    static boolean samePath(String a, String b) { return a != null && b != null && canon(a).equals(canon(b)); }

    /** Whether two entries are the same game: the same file, or the same title with the same known build key */
    static boolean same(View a, String keyA, View b, String keyB) {
        if (samePath(a.gameDir(), b.gameDir())) return true;
        return a.titleId().equalsIgnoreCase(b.titleId()) && keyA != null && keyA.equals(keyB);
    }

    /** The indexes of the list's entries that an entry for (titleId, gameDir, key) replaces: the same file, or the same title and build */
    static <T extends View> List<Integer> matches(List<T> list, String titleId, String gameDir, String key, Function<T, String> keyOf) {
        List<Integer> out = new ArrayList<>();
        String c = canon(gameDir);
        for (int i = 0; i < list.size(); i++) {
            T g = list.get(i);
            if (canon(g.gameDir()).equals(c)) { out.add(i); continue; }
            if (key != null && g.titleId().equalsIgnoreCase(titleId) && key.equals(keyOf.apply(g))) out.add(i);
        }
        return out;
    }

    /** Entries of the same title that are another dump (another path, and another build when both are known, or a build that cannot be told): the user decides */
    static <T extends View> List<Integer> others(List<T> list, String titleId, String gameDir, String key, Function<T, String> keyOf) {
        List<Integer> same = matches(list, titleId, gameDir, key, keyOf), out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) if (!same.contains(i) && list.get(i).titleId().equalsIgnoreCase(titleId)) out.add(i);
        return out;
    }

    /**
     * The list with one entry per game, in the order of the entries kept. Of the entries that are one game (same, transitively), the one kept is: ready (ready),
     * else the one with the latest used, else the latest in the list. The list itself is not changed.
     */
    static <T extends View> List<T> collapse(List<T> list, Function<T, String> keyOf, Predicate<T> ready) {
        int n = list.size();
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) keys[i] = keyOf.apply(list.get(i));
        int[] group = new int[n];
        for (int i = 0; i < n; i++) group[i] = i;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (same(list.get(i), keys[i], list.get(j), keys[j])) union(group, i, j);
        Boolean[] isReady = new Boolean[n];
        int[] best = new int[n];
        java.util.Arrays.fill(best, -1);
        for (int i = 0; i < n; i++) {
            int r = find(group, i);
            if (best[r] < 0) { best[r] = i; continue; }
            if (better(list, ready, isReady, i, best[r])) best[r] = i;
        }
        List<T> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (best[find(group, i)] == i) out.add(list.get(i));
        return out;
    }

    private static <T extends View> boolean better(List<T> list, Predicate<T> ready, Boolean[] isReady, int a, int b) {
        boolean ra = readyAt(list, ready, isReady, a), rb = readyAt(list, ready, isReady, b);
        if (ra != rb) return ra;
        long ua = list.get(a).used(), ub = list.get(b).used();
        if (ua != ub) return ua > ub;
        return a > b;
    }

    private static <T extends View> boolean readyAt(List<T> list, Predicate<T> ready, Boolean[] isReady, int i) {
        if (isReady[i] == null) isReady[i] = ready.test(list.get(i));
        return isReady[i];
    }

    private static int find(int[] g, int i) {
        while (g[i] != i) i = g[i] = g[g[i]];
        return i;
    }

    private static void union(int[] g, int a, int b) {
        int ra = find(g, a), rb = find(g, b);
        if (ra != rb) g[Math.max(ra, rb)] = Math.min(ra, rb);
    }
}
