package dev.nxnative.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recommended settings per game and phone (game.tsv recommend rows, dm/games/README.md; android/README.md "Recommended settings"): rows of "&lt;match&gt; &lt;setting&gt;=&lt;value&gt; …"
 * in the profile's order, a later matching row winning for the settings it names. A match is "any" or conditions on what the GPU driver reports (the readiness check's
 * facts, vka_readiness.c: gpu.vendor, gpu.device, driver.id, vk.api, feature.*, ext.*, limit.*), never on a name. A row that sets the GPU driver sets only that and is
 * matched against the phone's own driver's facts (the system driver: the baseline every phone has); every other row against the facts of the driver the game starts with.
 * A recommended value is computed, never stored: the game's own value (its Game settings, the in-game panel) wins, then the recommendation, then the main screen's
 * default (Settings), then the built-in default. No Android classes: the JVM test runs it on the Mac.
 */
final class Recommend {
    private Recommend() {}

    /** The settings a row can set, in the order the log and the summary list them; "option.&lt;ENV&gt;" (a profile option row) comes after them */
    static final String RES_PRESET = "res_preset", DRIVER = "driver", UPSCALER = "upscaler", HOLD60 = "hold60", FPS_CAP = "fps_cap", DRS = "drs",
            VEGETATION = "vegetation", SKIP_LAYERS = "skip_layers", OPTION = "option.";
    static final List<String> SETTINGS = Collections.unmodifiableList(Arrays.asList(RES_PRESET, DRIVER, UPSCALER, HOLD60, FPS_CAP, DRS, VEGETATION, SKIP_LAYERS));

    /** The values, each list in the order of the launcher's own (GameUpscaler.MODES, DlssTune.MODE_NAMES, Prefs.DRS_NAMES, Prefs.FPS_CAPS, Prefs.PACE_CAPS): the index is the stored value */
    static final String[] UPSCALERS = {"nearest", "bilinear", "fsr1", "native", "sgsr1"};
    static final String[] DLSS_MODES = {"gsr2", "fsr1", "bilinear", "fsr3"};
    static final String[] DRS_VALUES = {"off", "80", "70", "60", "50", "40", "30"};
    static final String[] CAPS = {"own", "60", "30", "20", "15"};   // Prefs.FPS_CAPS {0, 60, 30, 20, 15}
    static final String[] PACE = {"60", "45", "40", "30", "20"};   // Prefs.PACE_CAPS {0, 45, 40, 30, 20}: 60 is stored as 0

    /** The phone's own driver as a driver choice (GpuDriver.SYSTEM) */
    static final String SYSTEM = ":system";

    /** The facts a match may read: exact keys, and prefixes. The names (gpu.name, driver.name, driver.info, driver.custom) are not among them */
    static final List<String> FACT_KEYS = Collections.unmodifiableList(Arrays.asList("gpu.vendor", "gpu.device", "driver.id", "driver.version", "vk.api"));
    static final List<String> FACT_PREFIXES = Collections.unmodifiableList(Arrays.asList("feature.", "ext.", "limit."));

    static boolean factAllowed(String k) {
        if (FACT_KEYS.contains(k)) return true;
        for (String p : FACT_PREFIXES) if (k.startsWith(p) && k.length() > p.length()) return true;
        return false;
    }

    static final class Row {
        final String match;
        final Map<String, String> set;
        Row(String match, Map<String, String> set) { this.match = match; this.set = set; }
        boolean driverRow() { return set.containsKey(DRIVER); }
        @Override public String toString() { return match + "|" + set; }
    }

    /** build.gradle recommendOf's column (Profile.recommend): "&lt;match&gt;|&lt;setting&gt;=&lt;value&gt; …", ';' between rows; a malformed row is skipped (the build refuses one) */
    static List<Row> parse(String col) {
        List<Row> out = new ArrayList<>();
        if (col == null) return out;
        for (String r : col.split(";")) {
            int bar = r.indexOf('|');
            if (bar <= 0) continue;
            String match = r.substring(0, bar).trim();
            Map<String, String> set = new LinkedHashMap<>();
            for (String kv : r.substring(bar + 1).trim().split("\\s+")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && eq < kv.length() - 1) set.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
            if (!set.isEmpty() && (match.equals("any") || conditions(match) != null)) out.add(new Row(match, Collections.unmodifiableMap(set)));
        }
        return Collections.unmodifiableList(out);
    }

    private static final Pattern COND = Pattern.compile("([A-Za-z0-9_.]+)(?:&(0[xX][0-9a-fA-F]+|[0-9]+))?(!=|>=|<=|=|>|<)([0-9A-Za-z_.]+)");

    /** A match's conditions as {fact, mask or "", operator, value}; null when one is malformed or reads a fact that is not allowed */
    static List<String[]> conditions(String match) {
        List<String[]> out = new ArrayList<>();
        for (String c : match.split(",")) {
            Matcher m = COND.matcher(c.trim());
            if (!m.matches() || !factAllowed(m.group(1))) return null;
            out.add(new String[] {m.group(1), m.group(2) == null ? "" : m.group(2), m.group(3), m.group(4)});
        }
        return out.isEmpty() ? null : out;
    }

    /** Whether the facts are a driver's real answer (a check that failed or did not run has no gpu.vendor) */
    static boolean known(Map<String, String> f) { return f != null && f.containsKey("gpu.vendor"); }

    /**
     * Whether a match holds on these facts. "any" always; anything else only on known facts, where a fact the driver does not report (an extension or feature it lacks)
     * reads as 0. Values compare as numbers, 0x hex or dotted versions (Readiness.compare); a mask (gpu.device&amp;0xff000000) takes those bits of the reported number first
     */
    static boolean matches(String match, Map<String, String> f) {
        if ("any".equals(match)) return true;
        List<String[]> cs = conditions(match);
        if (cs == null || !known(f)) return false;
        for (String[] c : cs) {
            String have = f.getOrDefault(c[0], "0");
            if (!c[1].isEmpty()) have = String.valueOf(num(have) & num(c[1]));
            int k = Readiness.compare(have, c[3]);
            boolean ok;
            switch (c[2]) {
                case "=": ok = k == 0; break;
                case "!=": ok = k != 0; break;
                case ">=": ok = k >= 0; break;
                case "<=": ok = k <= 0; break;
                case ">": ok = k > 0; break;
                default: ok = k < 0; break;
            }
            if (!ok) return false;
        }
        return true;
    }

    private static long num(String s) {
        s = s.trim();
        try { return s.startsWith("0x") || s.startsWith("0X") ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    /** The recommended values but the driver: every matching row on the facts of the driver the game starts with, a later one winning for what it names */
    static Map<String, String> values(List<Row> rows, Map<String, String> facts) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Row r : rows) if (!r.driverRow() && matches(r.match, facts)) out.putAll(r.set);
        return out;
    }

    /** The recommended driver value ("system" or a VkDriverId) of the last driver row matching the phone's own driver's facts; null when none matches */
    static String driver(List<Row> rows, Map<String, String> systemFacts) {
        String out = null;
        for (Row r : rows) if (r.driverRow() && matches(r.match, systemFacts)) out = r.set.get(DRIVER);
        return out;
    }

    /**
     * The driver choice a driver value asks for, among what this phone has: "system" is the system driver (SYSTEM); a VkDriverId is the system driver when it reports that
     * ID, else an installed driver known to report it (its facts from an earlier readiness check on it), the main screen's pick (prefer) first, then by folder name; null
     * when no driver the phone has is known to report it (the row is then left out)
     */
    static String resolveDriver(String want, Map<String, String> systemFacts, Map<String, Map<String, String>> installed, String prefer) {
        if (want == null) return null;
        if (want.equals("system")) return SYSTEM;
        if (known(systemFacts) && want.equals(systemFacts.get("driver.id"))) return SYSTEM;
        if (prefer != null && installed.containsKey(prefer) && want.equals(idOf(installed.get(prefer)))) return prefer;
        for (Map.Entry<String, Map<String, String>> e : installed.entrySet()) if (want.equals(idOf(e.getValue()))) return e.getKey();
        return null;
    }

    private static String idOf(Map<String, String> f) { return known(f) ? f.get("driver.id") : null; }

    /** What the launcher keeps of a readiness answer for matching (the facts a match may read), "" when it is no real answer */
    static String keep(String vulkan) {
        Map<String, String> f = Readiness.facts(vulkan);
        if (!known(f)) return "";
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, String> e : f.entrySet()) if (factAllowed(e.getKey())) s.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return s.toString();
    }

    /**
     * Facts of the phone's own driver from GLES, for matching before any readiness check has run on it (a fresh install, or every start with a DM_VK_DRIVER line):
     * the GPU vendor's Vulkan ID from GL_VENDOR, and on Qualcomm's own driver, whose GL_VERSION carries "V@&lt;minor&gt;.&lt;patch&gt;" as its Vulkan driverVersion does
     * (512.&lt;minor&gt;.&lt;patch&gt;), driver.id 8 and that version; Mesa on an Adreno (Freedreno, Turnip's sibling) driver.id 13; Arm's own driver.id 9. "" for a vendor not
     * known here. The readiness check's own answer replaces them (Prefs.rememberFacts)
     */
    static String glFacts(String vendor, String version) {
        String v = vendor == null ? "" : vendor.trim().toLowerCase(java.util.Locale.ROOT), ver = version == null ? "" : version;
        long id = v.contains("qualcomm") ? 0x5143 : v.equals("arm") || v.startsWith("arm ") ? 0x13B5 : v.contains("imagination") ? 0x1010 : v.contains("samsung") ? 0x144D : 0;
        if (id == 0) return "";
        StringBuilder s = new StringBuilder("gpu.vendor=0x").append(Long.toHexString(id)).append('\n');
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("V@0*(\\d+)\\.(\\d+)").matcher(ver);
        if (ver.contains("Mesa")) { if (id == 0x5143) s.append("driver.id=13\n"); }
        else if (id == 0x5143 && m.find()) {
            long minor = Long.parseLong(m.group(1)), patch = Long.parseLong(m.group(2));
            if (minor < 1024 && patch < 4096) s.append("driver.id=8\ndriver.version=0x").append(Long.toHexString(0x80000000L | minor << 12 | patch)).append('\n');
        } else if (id == 0x13B5) s.append("driver.id=9\n");
        return s.toString();
    }

    /**
     * The driver choice a start-up check probed, from its DM_VK_DRIVER line (null: none) and the one the launcher's choice adds (own; null for the system driver): the
     * launcher's choice when they agree, the system driver for an explicit "system" (or empty) line, else null (another driver picked by a user line: its facts are not kept)
     */
    static String probedChoice(String line, String own, String ownChoice) {
        if (java.util.Objects.equals(line, own)) return ownChoice;
        if (line != null && line.startsWith("DM_VK_DRIVER=")) {
            String v = line.substring("DM_VK_DRIVER=".length()).trim();
            if (v.isEmpty() || v.equalsIgnoreCase("system")) return SYSTEM;
        }
        return null;
    }

    // ---- Values in the launcher's own terms ----

    /** The index of a value in its list, -1 when it is not there */
    static int index(String[] values, String v) { return v == null ? -1 : Arrays.asList(values).indexOf(v); }

    /** on / off as a boolean, dflt for anything else (no recommendation) */
    static boolean bool(String v, boolean dflt) { return "on".equals(v) ? true : "off".equals(v) ? false : dflt; }
    static String onOff(boolean on) { return on ? "on" : "off"; }

    /** A frame rate cap value as the launcher stores it: a limited game's PACE_CAPS value (60 is 0), else FPS_CAPS'; null for none */
    static Integer cap(String v, boolean limited) {
        if (limited) { int i = index(PACE, v); return i < 0 ? null : i == 0 ? 0 : Integer.parseInt(v); }
        int i = index(CAPS, v);
        return i < 0 ? null : i == 0 ? 0 : Integer.parseInt(v);
    }
    /** A stored cap in a row's words */
    static String capName(int stored, boolean limited) {
        if (limited) return stored <= 0 ? "60" : String.valueOf(stored);
        return stored == 0 ? "own" : stored < 0 ? "unset" : String.valueOf(stored);
    }

    /** The launcher's per-game keys (Prefs.gameKey) that hold the player's own value of a setting: what "Use recommended" clears. Resolution's include the window and
     *  3D scene chosen before the presets, which ResPresets.current still reads as a choice */
    static List<String> prefKeys(String setting, boolean dlss) {
        switch (setting) {
            case RES_PRESET: return Arrays.asList("res_preset", "window", "scene_res");
            case DRIVER: return Collections.singletonList("gpu_driver");
            case UPSCALER: return Collections.singletonList(dlss ? "dlss_mode" : "ndsr_choice2");
            case HOLD60: return Collections.singletonList("hold60");
            case FPS_CAP: return Collections.singletonList("fps_cap");
            case DRS: return Collections.singletonList("drs2");
            case VEGETATION: return Collections.singletonList("full_vegetation");
            case SKIP_LAYERS: return Collections.singletonList("vk_skip_layers");
            default: return setting.startsWith(OPTION) ? Collections.singletonList("opt_" + setting.substring(OPTION.length())) : Collections.emptyList();
        }
    }

    /** A setting's name as Game settings shows it (an option's is its label, from the profile) */
    static String label(String setting) {
        switch (setting) {
            case RES_PRESET: return "Resolution";
            case DRIVER: return "GPU driver";
            case UPSCALER: return "Upscaler";
            case HOLD60: return "Hold 60 fps";
            case FPS_CAP: return "Frame rate cap";
            case DRS: return "Dynamic resolution";
            case VEGETATION: return "Full vegetation";
            case SKIP_LAYERS: return "Speedhack";
            default: return setting;
        }
    }

    /** One setting of a game as it stands: its value in a row's words, whether the player set it for this game, and the recommendation (null for none) */
    static final class State {
        final String setting, label, value, recommended;
        final boolean own;
        State(String setting, String label, String value, boolean own, String recommended) {
            this.setting = setting; this.label = label; this.value = value; this.own = own; this.recommended = recommended;
        }
        String source() { return Recommend.source(own, recommended); }
        boolean changed() { return Recommend.changed(own, value, recommended); }
    }

    /** The log's line at a start: every recommended setting the game offers, with its value and where it comes from */
    static String logLine(List<State> states) {
        StringBuilder s = new StringBuilder("[nxlaunch] settings:");
        for (int i = 0; i < states.size(); i++) { State st = states.get(i); s.append(i == 0 ? " " : ", ").append(entry(st.label, st.value, st.source())); }
        return s.toString();
    }

    /** The line of Game settings' "Recommended for this phone" card: each recommended value's short name (name: setting, value → "Performance", "Hold 60 off"), joined by " · "; null when nothing is recommended */
    static String line(List<State> states, java.util.function.BiFunction<String, String, String> name) {
        StringBuilder s = new StringBuilder();
        for (State st : states) if (st.recommended != null) s.append(s.length() == 0 ? "" : " · ").append(name.apply(st.setting, st.recommended));
        return s.length() == 0 ? null : s.toString();
    }

    /** How many recommended settings the player set to another value for this game (the card's "You changed N of these.") */
    static int changedCount(List<State> states) { int n = 0; for (State st : states) if (st.changed()) n++; return n; }

    /** The card's muted line, null when nothing was changed (no line, no button) */
    static String changedLine(int n) { return n <= 0 ? null : "You changed " + n + " of these."; }

    /** A row's tag: "Changed" (warning tint) where the player's value is not the recommended one, else "Recommended" (the value is the recommended one); none without a recommendation */
    static String tag(State st) { return st == null || st.recommended == null ? null : st.changed() ? "Changed" : "Recommended"; }

    /** Where a value comes from, for the log: the player's own for this game, the recommendation, or the default (Settings or built-in) */
    static String source(boolean own, String recommended) { return own ? "yours" : recommended != null ? "recommended" : "default"; }

    /** The game's own value, else the recommendation, else the default: the order every recommended setting takes */
    static <T> T effective(T own, T recommended, T dflt) { return own != null ? own : recommended != null ? recommended : dflt; }

    /** Whether a setting's row shows the changed marker: the player set a value of their own for this game, and it is not the recommended one */
    static boolean changed(boolean own, String current, String recommended) { return own && recommended != null && !recommended.equals(current); }

    /** One setting's line for the log: "Upscaler sgsr1 (recommended)" */
    static String entry(String label, String value, String source) { return label + " " + value + " (" + source + ")"; }
}
