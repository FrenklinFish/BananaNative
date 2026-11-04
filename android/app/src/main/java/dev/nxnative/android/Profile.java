package dev.nxnative.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A game this APK runs: its profile (dm/games/&lt;title ID&gt;) was compiled into a host of its own, libnxhost_&lt;title ID&gt;.so (build.gradle nxGames,
 * src/main/cpp/CMakeLists.txt), and what the launcher needs of it comes from build.gradle as BuildConfig.NX_PROFILES: one line per game, tab-separated: title ID,
 * name, the shader count Prepare translates, the shader inputs digest, the builds (game.tsv build rows, "version module=build ID …", ';' between builds), and, when the APK has them, the readiness rows (the profile's readiness.tsv: "level|fact|min|what", ';' between rows: Readiness.parseReqs), its status row (playable or bring-up; dm/games/README.md "Readiness rows"), the shader delta rows (vk_delta.tsv's "from" rows: "version=count", ',' between them), its "stamp" rows ("version:old SHA-256:new SHA-256", ';' between them) and what Game resolution sets (resolutionEnv).
 * A game without a profile here cannot run in this APK (its host has no profile for it): the launcher says so instead of starting it.
 * No Android classes: the JVM test (android/app/src/jvmtest) runs it on the Mac.
 */
final class Profile {
    final String titleId, name, shaderInputs, builds, reqs, status;
    final int shaderTotal;
    String delta = "";   // the shader delta manifest's "from" rows: "version=shaders to translate", ',' between them (ShaderLibs.toTranslate)
    String stamp = "";   // its "stamp" rows: "version:old SHA-256:new SHA-256", ';' between them (ShaderLibs.restamp)
    String resolution = "";   // what Game resolution sets (build.gradle resolutionOf): "window", "scale:<choice>=<scale>/<console|handheld>,…" or "" (nothing: not offered)

    /** Whether Game resolution does something for this game (its profile has window_fn or graphics_scale rows) */
    boolean hasResolution() { return resolution.equals("window") || resolution.startsWith("scale:"); }

    /**
     * The host's environment lines for a Game resolution choice (Prefs.WINDOWS: "1920x1080", "original", "1280x720"): DM_WINDOW_SIZE=&lt;choice&gt; for a game with
     * window_fn rows; DM_GRAPHICS_SCALE and DM_OPMODE from the choice's graphics_scale row for a game that sizes its scenes by the graphics scale (none for a choice
     * without a row: the game's own); none for a game with neither.
     */
    List<String> resolutionEnv(String choice) {
        List<String> env = new ArrayList<>();
        if (resolution.equals("window")) env.add("DM_WINDOW_SIZE=" + choice);
        else if (resolution.startsWith("scale:")) {
            for (String r : resolution.substring(6).split(",")) {
                int eq = r.indexOf('='), sl = r.indexOf('/');
                if (eq <= 0 || sl < eq || !r.substring(0, eq).equals(choice)) continue;
                env.add("DM_GRAPHICS_SCALE=" + r.substring(eq + 1, sl));
                env.add("DM_OPMODE=" + (r.substring(sl + 1).equals("handheld") ? 0 : 1));
                break;
            }
        }
        return env;
    }

    /** Whether any game of this APK has Game resolution (Settings shows its default only then) */
    static boolean anyResolution() { for (Profile p : all()) if (p.hasResolution()) return true; return false; }

    Profile(String titleId, String name, int shaderTotal, String shaderInputs, String builds, String reqs, String status) {
        this.reqs = reqs;
        this.status = status;
        this.titleId = titleId;
        this.name = name;
        this.shaderTotal = shaderTotal;
        this.shaderInputs = shaderInputs;
        this.builds = builds;
    }

    /** The profiles of a BuildConfig.NX_PROFILES table; a malformed line is skipped */
    static List<Profile> parse(String table) {
        List<Profile> out = new ArrayList<>();
        for (String line : table.split("\n")) {
            String[] c = line.split("\t", -1);
            if (c.length < 5 || !c[0].matches("[0-9A-F]{16}")) continue;
            int total;
            try { total = Integer.parseInt(c[2]); } catch (NumberFormatException e) { total = 0; }
            Profile p = new Profile(c[0], c[1].isEmpty() ? c[0] : c[1], total, c[3], c[4], c.length > 5 ? c[5] : "", c.length > 6 ? c[6] : "");
            p.delta = c.length > 7 ? c[7] : "";
            p.stamp = c.length > 8 ? c[8] : "";
            p.resolution = c.length > 9 ? c[9] : "";
            out.add(p);
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Profile> all;

    /** The games this APK runs, in build.gradle's order (the first is the default) */
    static synchronized List<Profile> all() {
        if (all == null) all = parse(BuildConfig.NX_PROFILES);
        return all;
    }

    /** This APK's profile of a title ID, null when it has none */
    static Profile of(String titleId) {
        for (Profile p : all()) if (p.titleId.equals(titleId)) return p;
        return null;
    }

    /** The default game (the first): the graphics self-test runs its host */
    static Profile first() { return all().get(0); }

    /** Why this APK cannot run a title, in a sentence (null when it has its profile) */
    static String missing(String titleId, String what) {
        if (of(titleId) != null) return null;
        // The title IDs this APK runs, so a user with another game sees it is not supported (rather than a broken dump); IDs only, no game names
        List<String> ids = new ArrayList<>();
        for (Profile p : all()) ids.add(p.titleId);
        return (what == null ? titleId : what + " (" + titleId + ")") + " has no game profile in this app, so it cannot run here. This app runs: " + String.join(", ", ids) + ".";
    }

    /** A version of a dump in words: 0 is the base game itself (no update installed; Mario Kart World 1.8.0's supported build), any other an update */
    static String versionName(long version) { return version == 0 ? "the base game" : "update " + version; }

    /** Which versions of this game the APK supports, as a sentence for a dump it refuses (DumpCheck): its builds' versions (buildMap) */
    String supported() {
        List<String> all = new ArrayList<>();
        int ups = 0;
        for (Long v : buildMap().keySet()) {
            if (v == 0) all.add(0, "the base game (no update)");
            else { all.add("update " + v); ups++; }
        }
        return "This app supports " + name + " (" + titleId + ") as " + String.join(" or ", all) + " only: "
                + (ups == 0 ? "dump the game without an update." : "dump the game with " + (ups == 1 ? "that update" : "one of those updates") + " installed.");
    }

    /** The profile's builds: version → module → build ID (the first 16 bytes, lower case, as game.tsv writes them) */
    Map<Long, Map<String, String>> buildMap() {
        Map<Long, Map<String, String>> out = new LinkedHashMap<>();
        for (String b : builds.split(";")) {
            String[] c = b.trim().split(" ");
            if (c.length < 2 || !c[0].matches("\\d+")) continue;
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 1; i < c.length; i++) {
                int eq = c[i].indexOf('=');
                if (eq > 0) m.put(c[i].substring(0, eq), c[i].substring(eq + 1).toLowerCase(Locale.ROOT));
            }
            out.put(Long.parseLong(c[0]), m);
        }
        return out;
    }
}
