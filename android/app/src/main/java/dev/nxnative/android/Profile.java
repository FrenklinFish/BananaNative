package dev.nxnative.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A game this APK runs: its profile (dm/games/&lt;title ID&gt;) was compiled into a host of its own, libnxhost_&lt;title ID&gt;.so (build.gradle nxGames,
 * src/main/cpp/CMakeLists.txt), and what the launcher needs of it comes from build.gradle as BuildConfig.NX_PROFILES: one line per game, tab-separated: title ID,
 * name, the shader count Prepare translates, the shader inputs digest, the builds (game.tsv build rows, "version module=build ID …", ';' between builds), and, when the APK has them, the readiness rows (the profile's readiness.tsv: "level|fact|min|what", ';' between rows: Readiness.parseReqs), its status row (playable or bring-up; dm/games/README.md "Readiness rows"), the shader delta rows (vk_delta.tsv's "from" rows: "version=count", ',' between them), its "stamp" rows ("version:old SHA-256:new SHA-256", ';' between them), what Game resolution sets (resolutionEnv), the RomFS checks (romfsChecks), the GPU timestamp mode (gpuTs), the default Game resolution (window), whether the game is paced (decoupled), dynamic resolution (drs), the default frame limit (fpsLimit) and whether the game's DLSS runs on the host's stand-in (dlss), and, after the vegetation flag and the option rows, the 3D scene overlays (scenes), the Resolution presets (presets), the Speedhack's layers (skipLayers) and the recommended settings (recommend).
 * A game without a profile here cannot run in this APK (its host has no profile for it): the launcher says so instead of starting it.
 * No Android classes: the JVM test (android/app/src/jvmtest) runs it on the Mac.
 */
final class Profile {
    final String titleId, name, shaderInputs, builds, reqs, status;
    final int shaderTotal;
    String delta = "";   // the shader delta manifest's "from" rows: "version=shaders to translate", ',' between them (ShaderLibs.toTranslate)
    String stamp = "";   // its "stamp" rows: "version:old SHA-256:new SHA-256", ';' between them (ShaderLibs.restamp)
    String resolution = "";   // what Game resolution sets (build.gradle resolutionOf): "window", "scale:<choice>=<scale>/<console|handheld>,…" or "" (nothing: not offered)
    String gpuTs = "submission";   // game.tsv gpu_timestamps (build.gradle gpuTsOf): "segment" or "submission", what the host does without DM_NVN_GPU_TS (Game settings › GPU timing)
    String window = "";   // game.tsv android_window (build.gradle windowOf): the default Game resolution when the player chose none (Prefs.gameWindow), "" for the main screen's
    boolean vegetationDetail;
    List<Option> options = Collections.emptyList();
    String scenes = "";   // game.tsv scene_scale rows with a scene_scale_palette (build.gradle sceneOf): "name|label|scale|mode", ';' between them; Game settings › Resolution › Advanced › 3D scene (SceneRes.presets)
    String presets = "";   // game.tsv res_preset rows with their windows' scene_output rows (build.gradle presetsOf): "id|label|window|mode|height|outW|outH|measured", ';' between them; Game settings › Resolution (ResPresets)
    boolean steps;   // game.tsv has fps_steps rows (build.gradle stepsOf): a framework of whole fixed steps a frame, which Hold 60 fps keeps at one (Prefs.steps)
    boolean presentOnly;   // game.tsv fps_limit_present 1 (build.gradle presentOnlyOf): the frame limit only paces the presents, the game's own logic is not known to follow it (Prefs.presentOnly)
    int fpsLimit;   // game.tsv fps_limit (build.gradle fpsLimitOf): the host's frame limit by default, frames a second (0 = none); the launcher offers a frame limit for it (Prefs.limited)
    boolean drs;   // game.tsv has drs_ctl rows (build.gradle drsOf): the host can steer the game's dynamic resolution (Prefs.drsGame)
    String recommend = "";   // game.tsv recommend rows (build.gradle recommendOf): "<match>|<setting>=<value> …", ';' between them; the recommended settings per phone (Recommend, Prefs.recommended)
    String skipLayers = "";   // game.tsv skip_layers (build.gradle skipLayersOf): pixel shader keys whose draws the Speedhack setting drops (Prefs.skipLayersGame), "" = not offered
    String variants = "";   // game.tsv variant rows (build.gradle variantsOf): "<name>=<label>", ',' between them; a native 4:3 mod is "43=4:3" (Game settings › Aspect ratio)

    /** The variant whose label is w:h (a native aspect mod: "43" for "4:3"), or null when the profile has none */
    String aspectVariant(String label) {
        for (String v : variants.split(",")) { int eq = v.indexOf('='); if (eq > 0 && v.substring(eq + 1).equals(label)) return v.substring(0, eq); }
        return null;
    }
    boolean dlss;   // game.tsv has dlss_standin rows (build.gradle dlssOf): the game's DLSS runs on the host's GSR2 stand-in, with its Upscaler modes and sliders (Prefs.dlssGame)
    boolean decoupled;   // game.tsv has fps_decouple rows (build.gradle decoupleOf): the game's speed follows its measured frame rate, so it gets a frame limit (Prefs.paced)
    String romfsChecks = "";   // game.tsv romfs_check rows (build.gradle romfsChecksOf): "<path>|<line>|<expected or main>", ';' between them (RomfsCheck)

    /** Whether Game resolution does something for this game (its profile has window_fn, res_levels or graphics_scale rows) */
    boolean hasResolution() { return resolution.equals("window") || resolution.startsWith("scale:"); }

    /**
     * The host's environment lines for a Game resolution choice (Prefs.WINDOWS: "original", "2560x1440", "1920x1080", "1280x720", "960x540", "848x480"): DM_WINDOW_SIZE=&lt;choice&gt; for a game with
     * window_fn rows (its window size) or res_levels rows (a cap on its dynamic resolution); DM_GRAPHICS_SCALE and DM_OPMODE from the choice's graphics_scale row for a game that sizes its scenes by the graphics scale. Such a
     * game has only the few sizes its scales give, so a size choice without a row of its own takes the row of the largest size that fits in it, or the smallest row when none fits (Phone 540p on a game whose
     * smallest is 1280x720); Original without a row sets nothing (the game's own). None for a game with neither.
     */
    List<String> resolutionEnv(String choice) {
        List<String> env = new ArrayList<>();
        if (resolution.equals("window")) env.add("DM_WINDOW_SIZE=" + choice);
        else {
            int[] m = scaleRow(choice);
            if (m != null) { env.add("DM_GRAPHICS_SCALE=" + m[0]); env.add("DM_OPMODE=" + m[1]); }
        }
        return env;
    }

    public static final class Option {
        public final String env;
        public final String label;
        public final String description;
        public final boolean live;   // false = restart, true = live
        public final String implies; // env var of option implied by this option, or ""
        public final String needs;   // fixup marker required by this option (e.g. "OOT_PATCHES"), or ""

        public Option(String env, String label, String description, boolean live, String implies, String needs) {
            this.env = env != null ? env : "";
            this.label = label != null ? label : "";
            this.description = description != null ? description : "";
            this.live = live;
            this.implies = implies != null ? implies : "";
            this.needs = needs != null ? needs : "";
        }

        public Option(String env, String label, String description, boolean live) {
            this(env, label, description, live, "", "");
        }

        public boolean hasImplies() { return !implies.isEmpty(); }
        public boolean hasNeeds() { return !needs.isEmpty(); }

        public String envLine() { return env + "=1"; }

        /** Whether this option's required fixup marker (if any) is present in the pack */
        public boolean isSupported(File packDir) {
            return needs.isEmpty() || hasFixupMarker(packDir, needs);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Option)) return false;
            Option that = (Option) o;
            return live == that.live && env.equals(that.env) && label.equals(that.label)
                    && description.equals(that.description) && implies.equals(that.implies)
                    && needs.equals(that.needs);
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(env, label, description, live, implies, needs);
        }

        @Override public String toString() {
            return env + "|" + label + "|" + description + "|" + (live ? "live" : "restart")
                    + (hasImplies() ? "|implies=" + implies : "") + (hasNeeds() ? "|needs=" + needs : "");
        }
    }

    /** Parse option rows encoded as "<env>|<label>|<description>|<restart|live>[|<implies>[|<needs>]]", ';' between them */
    static List<Option> parseOptions(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<Option> out = new ArrayList<>();
        for (String row : text.split(";")) {
            String r = row.trim();
            if (r.isEmpty()) continue;
            String[] f = r.split("\\|", -1);
            if (f.length >= 3 && !f[0].trim().isEmpty()) {
                boolean live = f.length > 3 && "live".equalsIgnoreCase(f[3].trim());
                String implies = f.length > 4 ? f[4].trim() : "";
                String needs = f.length > 5 ? f[5].trim() : "";
                out.add(new Option(f[0].trim(), f[1].trim(), f[2].trim(), live, implies, needs));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Find an enabled option that implies targetOpt, or null */
    static Option findImpliedBy(List<Option> options, Option targetOpt, java.util.function.Predicate<String> isOptionOn) {
        if (options == null || targetOpt == null) return null;
        for (Option opt : options) {
            if (opt != targetOpt && !opt.implies.isEmpty() && opt.implies.equals(targetOpt.env) && isOptionOn.test(opt.env)) {
                return opt;
            }
        }
        return null;
    }

    /** Build environment lines ("<ENV>=1") for enabled options */
    static List<String> buildOptionsEnv(List<Option> options, java.util.function.Predicate<String> isOptionOn) {
        List<String> env = new ArrayList<>();
        if (options == null || options.isEmpty()) return env;
        for (Option opt : options) {
            boolean on = isOptionOn.test(opt.env) || findImpliedBy(options, opt, isOptionOn) != null;
            if (on) env.add(opt.envLine());
        }
        return env;
    }

    /** Environment lines ("<ENV>=1") for options enabled for this profile */
    List<String> optionsEnv(java.util.function.Predicate<String> isOptionOn) {
        return buildOptionsEnv(options, isOptionOn);
    }

    /** Whether a pack's main.fixups.tsv has "# <marker> 1" */
    static boolean hasFixupMarker(File packDir, String marker) {
        if (packDir == null || !packDir.isDirectory() || marker == null || marker.trim().isEmpty()) return false;
        File f = new File(packDir, "main.fixups.tsv");
        if (!f.isFile()) return false;
        String target = "# " + marker.trim();
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(target)) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3 && "1".equals(parts[2])) return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }


    /**
     * Whether Game resolution changes while the game runs (the in-game panel): only for a game with graphics_scale rows, whose host changes the graphics scale and operation mode
     * live and posts the console's notifications (Native.graphicsModeSet). A window_fn game's window size is written into its code at load, and a res_levels game's level lists
     * are rewritten in its read-only data at load (loader.c): both apply at the next start.
     */
    boolean resolutionLive() { return resolution.startsWith("scale:"); }

    /**
     * The graphics scale and operation mode for a choice while the game runs (Native.graphicsModeSet): the row resolutionEnv takes, or {-1, -1} where it sets nothing
     * (Original without a row: the game's own, as a start without the lines); null for a game without graphics_scale rows.
     */
    int[] resolutionLive(String choice) {
        if (!resolutionLive()) return null;
        int[] m = scaleRow(choice);
        return m != null ? m : new int[] {-1, -1};
    }

    /** The choice's graphics_scale row as {scale, operation mode (0 handheld, 1 console)}: its own row, else the largest that fits in it, else the smallest; null when none applies */
    private int[] scaleRow(String choice) {
        String r = scalePick(choice);
        if (r == null) return null;
        int eq = r.indexOf('='), sl = r.indexOf('/');
        try { return new int[] {Integer.parseInt(r.substring(eq + 1, sl)), r.substring(sl + 1).equals("handheld") ? 0 : 1}; } catch (NumberFormatException e) { return null; }
    }

    /**
     * A choice's label for this game: Prefs' name, plus the size the game really draws when its graphics_scale rows have no row of that size
     * ("Phone 540p (960x540) · draws 1280x720 here"), so a choice that falls back to another does not look like it did nothing
     */
    String windowLabel(String choice, String name) {
        String r = scalePick(choice);
        if (r == null || r.startsWith(choice + "=")) return name;
        String c = r.substring(0, r.indexOf('='));
        return size(c) == null ? name : name + " \u00b7 draws " + c + " here";
    }

    static final String[] MODE_NAMES = {"Docked", "Handheld"};

    /**
     * The two-choice form of Game resolution: a game whose graphics_scale rows are one console row and one handheld row, both sizes (the game's docked and handheld
     * outputs, nothing between them), gets the choices Docked and Handheld instead of the phone sizes (Game settings, the in-game panel). {the console row's size,
     * the handheld row's size}: the values the two choices store. Null for any other game
     */
    String[] modes() {
        if (!resolution.startsWith("scale:")) return null;
        String[] rs = resolution.substring(6).split(",");
        if (rs.length != 2) return null;
        String[] m = new String[2];
        for (String r : rs) {
            int eq = r.indexOf('='), sl = r.indexOf('/');
            if (eq <= 0 || sl < eq || size(r.substring(0, eq)) == null) return null;
            String mode = r.substring(sl + 1);
            int k = mode.equals("console") ? 0 : mode.equals("handheld") ? 1 : -1;
            if (k < 0 || m[k] != null) return null;
            m[k] = r.substring(0, eq);
        }
        return m;
    }

    /** A two-choice form's label (k: 0 docked, 1 handheld): "Docked (1152p)" for a console row of 2048x1152, "Handheld (1080p)" for a handheld row of 1920x1080; null without the form */
    String modeLabel(int k) {
        String[] m = modes();
        return m == null ? null : MODE_NAMES[k] + " (" + size(m[k])[1] + "p)";
    }

    /**
     * Which of the two choices a Game resolution value takes (the row resolutionEnv takes): 0 docked, 1 handheld; -1 without the form. Original takes no row and starts the
     * game as its profile's op_mode says; it shows as Docked (an older choice, or Settings' default), and picking Docked stores the console row's size
     */
    int modeOf(String choice) {
        String[] m = modes();
        if (m == null) return -1;
        String r = scalePick(choice);
        if (r == null) return choice.equals("original") ? 0 : -1;
        String c = r.substring(0, r.indexOf('='));
        return c.equals(m[0]) ? 0 : c.equals(m[1]) ? 1 : -1;
    }

    /**
     * The window a Game resolution value gives this game, as a comparable choice (ResPresets.current): the choice of the graphics_scale row it takes (Phone 540p → "1280x720"
     * on a game whose smallest row is 1280x720), null where it takes none; the value itself for a game without graphics_scale rows
     */
    String windowRow(String choice) {
        if (choice == null || !resolution.startsWith("scale:")) return choice;
        String r = scalePick(choice);
        return r == null ? null : r.substring(0, r.indexOf('='));
    }

    /** The graphics_scale row a choice takes ("WxH=scale/mode"): its own, else the largest that fits in it, else the smallest; null without rows */
    private String scalePick(String choice) {
        if (!resolution.startsWith("scale:")) return null;
        String hit = null, fit = null, least = null;
        long fitPx = -1, leastPx = Long.MAX_VALUE;
        int[] want = size(choice);
        for (String r : resolution.substring(6).split(",")) {
            int eq = r.indexOf('='), sl = r.indexOf('/');
            if (eq <= 0 || sl < eq) continue;
            String c = r.substring(0, eq);
            if (c.equals(choice)) { hit = r; break; }
            int[] s = size(c);
            if (s == null || want == null) continue;
            long px = (long) s[0] * s[1];
            if (s[0] <= want[0] && s[1] <= want[1] && px > fitPx) { fit = r; fitPx = px; }
            if (px < leastPx) { least = r; leastPx = px; }
        }
        return hit != null ? hit : fit != null ? fit : least;
    }

    /** "WxH" as {w, h}; null for "original" or anything else */
    private static int[] size(String c) {
        int x = c.indexOf('x');
        if (x <= 0) return null;
        try { return new int[] {Integer.parseInt(c.substring(0, x)), Integer.parseInt(c.substring(x + 1))}; } catch (NumberFormatException e) { return null; }
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
            p.romfsChecks = c.length > 10 ? c[10] : "";
            if (c.length > 11 && !c[11].isEmpty()) p.gpuTs = c[11];
            p.window = c.length > 12 ? c[12] : "";
            p.decoupled = c.length > 13 && c[13].equals("1");
            p.drs = c.length > 14 && c[14].equals("1");
            try { p.fpsLimit = c.length > 15 && !c[15].isEmpty() ? Math.max(0, Integer.parseInt(c[15])) : 0; } catch (NumberFormatException e) { p.fpsLimit = 0; }
            p.dlss = c.length > 16 && c[16].equals("1");
            p.presentOnly = c.length > 17 && c[17].equals("1");
            p.steps = c.length > 18 && c[18].equals("1");
            p.vegetationDetail = c.length > 19 && c[19].equals("1");
            p.options = parseOptions(c.length > 20 ? c[20] : "");
            p.scenes = c.length > 21 ? c[21] : "";
            p.presets = c.length > 22 ? c[22] : "";
            p.skipLayers = c.length > 23 ? c[23] : "";
            p.recommend = c.length > 24 ? c[24] : "";
            p.variants = c.length > 25 ? c[25] : "";
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

    /**
     * Why this APK cannot run a title, in one plain sentence (null when it has its profile). Nothing inside this app can fix it (the host has the profile compiled in),
     * so the sentence says what to get and no button offers Prepare or Retry. name: the game's name when known (a pack's, a job's), else null. The title IDs this app
     * runs are in Settings › About
     */
    static String missing(String titleId, String name) {
        if (of(titleId) != null) return null;
        return "This app version does not include " + (name == null || name.isEmpty() || name.equals(titleId) ? "this game" : name) + " (" + titleId + "). Install a build that includes it.";
    }

    /** A version of a dump in words: 0 is the base game itself (no update installed; Mario Kart World 1.8.0's supported build), any other an update */
    static String versionName(long version) { return version == 0 ? "the base game" : "update " + version; }

    /** Which versions of this game the APK supports, as a sentence for a dump it refuses (DumpCheck): its builds, by display version when game.tsv names one */
    String supported() {
        List<String> all = new ArrayList<>();
        for (Long v : buildMap().keySet()) all.add(buildName(v));
        return "This app supports " + name + " (" + titleId + ") " + String.join(" or ", all) + " only.";
    }

    /** The builds this APK supports, in the player's words only ("1.8.0", "update 458752 or the base game"), for a refusal's main text (supported() has the build IDs) */
    String supportedShort() {
        List<String> all = new ArrayList<>();
        for (Long v : buildMap().keySet()) { String d = display(v); all.add(d != null ? d : versionName(v)); }
        return String.join(" or ", all);
    }

    /** One of the profile's builds in words: "1.8.0 (the base game, version 0, main d00e4f399e411d06…)", "update 458752 (main e25c90c1c1366388…)" */
    String buildName(long v) {
        String d = display(v), main = buildMap().getOrDefault(v, Map.of()).get("main");
        String ids = main == null ? "" : "main " + main.substring(0, Math.min(16, main.length())) + "…";
        if (d != null) return d + " (" + (v == 0 ? "the base game, version 0" : "update " + v) + (ids.isEmpty() ? "" : ", " + ids) + ")";
        return versionName(v) + (ids.isEmpty() ? "" : " (" + ids + ")");
    }

    /** The display version game.tsv's build row gives a build ("1.8.0"), null when it gives none */
    String display(long v) {
        for (String b : builds.split(";")) {
            String[] c = b.trim().split(" ");
            if (c.length > 1 && c[0].equals(String.valueOf(v))) for (String t : c) if (t.startsWith("@")) return t.substring(1);
        }
        return null;
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
