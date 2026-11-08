package dev.nxnative.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The icon, version and publisher a .nup carries for its game (Nup.gameMeta), for the game cards. Folder dumps don't have these. It's read in the background,
 * once per file (until the file changes), and handed to the card on the main thread.
 */
final class GameMeta {
    final Bitmap icon;
    final String version, publisher;

    private GameMeta(Bitmap icon, String version, String publisher) { this.icon = icon; this.version = version; this.publisher = publisher; }

    /** For example "1.8.0 · Nintendo", with whatever parts it has; null if it has neither */
    String line() {
        String s = (version != null ? version : "") + (version != null && publisher != null ? " · " : "") + (publisher != null ? publisher : "");
        return s.isEmpty() ? null : s;
    }

    private static final Map<String, GameMeta> cache = new LinkedHashMap<String, GameMeta>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, GameMeta> e) { return size() > 16; }
    };
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Reads the game's details and passes them to done on the main thread. Does nothing for a folder dump, a .nup without them, or one that can't be read */
    static void load(File dump, Consumer<GameMeta> done) {
        if (!Nup.isNup(dump) && !Nup.picked(dump)) return;
        new Thread(() -> {
            GameMeta m = read(dump);
            if (m != null) main.post(() -> done.accept(m));
        }, "nx-game-meta").start();
    }

    private static GameMeta read(File dump) {
        try {
            String key;
            try (Nup.Source s = Nup.source(dump)) { key = s.key; }
            synchronized (cache) { if (cache.containsKey(key)) return cache.get(key); }
            Nup.Meta m = Nup.peek(dump).gameMeta();
            Bitmap icon = m.icon == null ? null : BitmapFactory.decodeByteArray(m.icon, 0, m.icon.length);
            GameMeta g = icon == null && m.version == null && m.publisher == null ? null : new GameMeta(icon, m.version, m.publisher);
            synchronized (cache) { cache.put(key, g); }
            return g;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
