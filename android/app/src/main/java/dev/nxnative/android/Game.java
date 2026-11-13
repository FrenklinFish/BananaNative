package dev.nxnative.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A game the user added: its folder and its pack folder, as /storage paths, and when it was last started. Remembered in the app's preferences, one entry per
 * game (GameList: an add through {@link #put} replaces the entries of the same file or the same title and build; {@link #collapse} merges duplicates an older
 * version left).
 */
final class Game implements GameList.View {
    final String titleId, name, gameDir, packDir;
    final long used;   // when Play last started it (ms), 0: never

    Game(String titleId, String name, String gameDir, String packDir) { this(titleId, name, gameDir, packDir, 0); }

    Game(String titleId, String name, String gameDir, String packDir, long used) {
        this.titleId = titleId;
        this.name = name;
        this.gameDir = gameDir;
        this.packDir = packDir;
        this.used = used;
    }

    @Override public String titleId() { return titleId; }
    @Override public String gameDir() { return gameDir; }
    @Override public long used() { return used; }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject().put("title_id", titleId).put("name", name).put("game", gameDir).put("pack", packDir);
        if (used > 0) o.put("used", used);
        return o;
    }

    static Game fromJson(JSONObject o) throws JSONException {
        return new Game(o.getString("title_id"), o.getString("name"), o.getString("game"), o.getString("pack"), o.optLong("used", 0));
    }

    private static SharedPreferences prefs(Context c) { return Prefs.open(c, "games"); }

    static List<Game> load(Context c) {
        List<Game> out = new ArrayList<>();
        synchronized (Game.class) {
            try {
                JSONArray a = new JSONArray(prefs(c).getString("list", "[]"));
                for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
            } catch (JSONException e) {
                // a damaged list starts empty
            }
        }
        return out;
    }

    static void save(Context c, List<Game> games) {
        JSONArray a = new JSONArray();
        try {
            for (Game g : games) a.put(g.toJson());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        synchronized (Game.class) {
            prefs(c).edit().putString("list", a.toString()).commit();
        }
    }

    // ---- One entry per game ----

    private static final Map<String, String> keys = new HashMap<>();

    /** BuildCheck.gameKey of an entry's dump, cached by its path, size and time (a .nup's TOC and main header are read once) */
    static String key(String gameDir, String titleId) {
        File f = new File(gameDir);
        String id = GameList.canon(gameDir) + "|" + titleId + "|" + f.length() + "|" + f.lastModified();
        synchronized (keys) {
            if (keys.containsKey(id)) return keys.get(id);
        }
        String k = BuildCheck.gameKey(f, titleId);
        synchronized (keys) { keys.put(id, k); }
        return k;
    }

    static String key(Game g) { return key(g.gameDir, g.titleId); }

    /**
     * The game into the list: it replaces the entries of the same file or of the same title and build (GameList.matches; the latest used time is kept), and the
     * entries in replace (the user chose to replace another dump of the title). Reads the dumps' build keys: call it off the main thread when it can be.
     */
    static Game put(Context c, Game g, List<String> replace) {
        String k = key(g);
        for (Game x : load(c)) if (x.titleId.equalsIgnoreCase(g.titleId)) key(x);   // read outside the lock: inside it the keys come from the cache
        synchronized (Game.class) {
            List<Game> list = load(c);
            List<Integer> same = GameList.matches(list, g.titleId, g.gameDir, k, Game::key);
            long used = g.used;
            for (int i : same) used = Math.max(used, list.get(i).used);
            List<Game> out = new ArrayList<>();
            int at = -1;
            for (int i = 0; i < list.size(); i++) {
                Game x = list.get(i);
                boolean drop = same.contains(i) || replace != null && replace.contains(x.gameDir);
                if (drop && at < 0) at = out.size();   // the card keeps its place
                if (!drop) out.add(x);
            }
            Game n = new Game(g.titleId, g.name, g.gameDir, g.packDir, used);
            if (at < 0) out.add(n);
            else out.add(at, n);
            save(c, out);
            return n;
        }
    }

    static Game put(Context c, Game g) { return put(c, g, null); }

    /** The entry was just started: it is the one played last (the entry kept when duplicates are merged) */
    static void touch(Context c, Game g) {
        synchronized (Game.class) {
            List<Game> list = load(c);
            for (int i = 0; i < list.size(); i++) {
                Game x = list.get(i);
                if (x.gameDir.equals(g.gameDir) && x.packDir.equals(g.packDir)) list.set(i, new Game(x.titleId, x.name, x.gameDir, x.packDir, System.currentTimeMillis()));
            }
            save(c, list);
        }
    }

    /**
     * Merges the list's duplicates (GameList.collapse with the build keys and {@link #ready}); true when it changed the list. Reads the dumps and packs: off the
     * main thread. Saves only when nothing changed the list while it read (else the next call merges).
     */
    static boolean collapse(Context c) {
        List<Game> before = load(c);
        List<Game> after;
        try {
            after = GameList.collapse(before, Game::key, Game::ready);
        } catch (OutOfMemoryError e) {   // never the app's end: the list stays as it is, the next start tries again (Forgejo #214)
            android.util.Log.w("nxnative", "Home: duplicate merge skipped, out of memory: " + e.getMessage());
            return false;
        }
        if (after.size() == before.size()) return false;
        synchronized (Game.class) {
            List<Game> now = load(c);
            if (!sameList(now, before)) return false;
            save(c, after);
        }
        for (Game g : before)
            if (!after.contains(g)) android.util.Log.i("nxnative", "Home: duplicate entry merged: " + g.titleId + " " + g.gameDir + " (pack " + g.packDir + "); nothing deleted");
        return true;
    }

    private static boolean sameList(List<Game> a, List<Game> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Game x = a.get(i), y = b.get(i);
            if (!x.gameDir.equals(y.gameDir) || !x.packDir.equals(y.packDir) || !x.titleId.equals(y.titleId)) return false;
        }
        return true;
    }

    /** Whether the entry would start as it is (pack of this title, finished, of the dump's build, with a shader library the host plays): the entry a merge keeps */
    static boolean ready(Game g) {
        try {
            Pack p = Pack.read(new File(g.packDir));
            if (!p.titleId.equalsIgnoreCase(g.titleId) || p.unfinishedModule() != null) return false;
            if (!BuildCheck.build(p, new File(g.gameDir)).ok()) return false;
            File pd = new File(g.packDir);
            return Pack.libraryProblem(pd, g.titleId) == null || Pack.playableLibrary(pd, g.titleId) != null || Pack.legacyLibrary(pd, g.titleId) != null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return false;
        }
    }
}
