package dev.nxnative.android;

/**
 * Button mapping (Game settings › Button mapping, Settings › Button mapping): which physical gamepad button presses which Switch button, as the host's DM_PAD_MAP
 * (dm/host/android/input_map.c, docs/env_switches.tsv). Pure Java, no Android classes, so the JVM test (PadMapJvmTest) checks it.
 * <p>
 * A source is an Android button by its key name without KEYCODE_ (SOURCES; Android names face buttons by position, as on an Xbox pad); the analog triggers count as
 * BUTTON_L2 / BUTTON_R2 and the hat as DPAD_*, as in the host. A target is a Switch button (SWITCH, by its DM_BTN_* bit) or NONE (-1). The spec lists every source
 * that has a target ("BUTTON_A=B,BUTTON_B=A,…"); a source left out keeps the host's default. An edited map names all sixteen standard sources, so it holds whatever
 * Xbox button label swap says about which button is A; BUTTON_C and BUTTON_Z are listed only when they press something.
 */
final class PadMap {
    static final String[] SOURCES = {
        "BUTTON_A", "BUTTON_B", "BUTTON_X", "BUTTON_Y", "BUTTON_L1", "BUTTON_R1", "BUTTON_L2", "BUTTON_R2", "BUTTON_THUMBL", "BUTTON_THUMBR",
        "BUTTON_START", "BUTTON_SELECT", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "BUTTON_C", "BUTTON_Z",
    };
    /** android.view.KeyEvent's KEYCODE_ numbers of SOURCES */
    static final int[] KEYCODES = {96, 97, 99, 100, 102, 103, 104, 105, 106, 107, 108, 109, 19, 20, 21, 22, 98, 101};
    /** The standard sources (all but C and Z): an edited map names each of them */
    static final int STANDARD = 16;
    static final int NONE = -1;
    /** Switch buttons by DM_BTN_* bit (dm_input.h) */
    static final String[] SWITCH = {"A", "B", "X", "Y", "LS", "RS", "L", "R", "ZL", "ZR", "PLUS", "MINUS", "LEFT", "UP", "RIGHT", "DOWN"};
    /** The order the editor lists them in, and their names there */
    static final int[] EDIT_ORDER = {0, 1, 2, 3, 6, 7, 8, 9, 4, 5, 10, 11, 13, 15, 12, 14};
    static final String[] SWITCH_NAMES = {"A", "B", "X", "Y", "L stick click", "R stick click", "L", "R", "ZL", "ZR", "+", "−", "D-pad left", "D-pad up", "D-pad right", "D-pad down"};

    /** The host's default by position (labelled 0) for the standard sources: Android's bottom / right / left / top are the Switch's B / A / Y / X */
    private static final int[] BY_POSITION = {1, 0, 3, 2, 6, 7, 8, 9, 4, 5, 10, 11, 13, 15, 12, 14, NONE, NONE};

    final int[] target = new int[SOURCES.length];
    final boolean[] set = new boolean[SOURCES.length];

    /** The default the host applies without a map: by position, or with labelled 2 (Xbox button label swap's "swap buttons A/B") A and B by label */
    static PadMap defaults(int labelled) {
        PadMap m = new PadMap();
        for (int i = 0; i < SOURCES.length; i++) m.target[i] = BY_POSITION[i];
        if (labelled == 1 || labelled == 2) { m.target[0] = 0; m.target[1] = 1; }
        if (labelled == 1) { m.target[2] = 2; m.target[3] = 3; }
        return m;
    }

    static int source(String name) { for (int i = 0; i < SOURCES.length; i++) if (SOURCES[i].equals(name)) return i; return -1; }
    static int sourceOfKey(int keycode) { for (int i = 0; i < KEYCODES.length; i++) if (KEYCODES[i] == keycode) return i; return -1; }
    static int switchButton(String name) { if (name.equals("NONE")) return NONE; for (int i = 0; i < SWITCH.length; i++) if (SWITCH[i].equals(name)) return i; return -2; }

    /** A stored spec over the default for labelled: entries it cannot read are left out (the host says the same in its log) */
    static PadMap parse(String spec, int labelled) {
        PadMap m = defaults(labelled);
        if (spec == null) return m;
        for (String e : spec.split(",")) {
            int eq = e.indexOf('=');
            if (eq < 0) continue;
            int s = source(e.substring(0, eq).trim()), t = switchButton(e.substring(eq + 1).trim());
            if (s < 0 || t < -1) continue;
            m.target[s] = t;
            m.set[s] = true;
        }
        return m;
    }

    /** Whether any source has an entry: an empty map is no map (the default) */
    boolean edited() { for (boolean b : set) if (b) return true; return false; }

    /** DM_PAD_MAP's value, "" for none */
    String spec() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SOURCES.length; i++) {
            if (!set[i]) continue;
            if (b.length() > 0) b.append(',');
            b.append(SOURCES[i]).append('=').append(target[i] < 0 ? "NONE" : SWITCH[target[i]]);
        }
        return b.toString();
    }

    /**
     * The editor's "press a button" for Switch button sw: source src now presses it. The standard source that pressed sw before takes src's old target (a swap), so
     * every Switch button stays on one button; C and Z give theirs up instead. Every standard source then has an entry
     */
    void assign(int src, int sw) {
        int old = target[src];
        for (int i = 0; i < SOURCES.length; i++) {
            if (i == src || target[i] != sw) continue;
            target[i] = i < STANDARD ? old : NONE;
            set[i] = i < STANDARD || target[i] != NONE;
        }
        target[src] = sw;
        set[src] = src < STANDARD || sw != NONE;
        for (int i = 0; i < STANDARD; i++) set[i] = true;
    }

    /** The sources that press Switch button sw, their names joined ("BUTTON_B"), "nothing" when none */
    String pressedBy(int sw) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SOURCES.length; i++) if (target[i] == sw) b.append(b.length() > 0 ? " + " : "").append(label(i));
        return b.length() > 0 ? b.toString() : "nothing";
    }

    /** A source as the editor names it: Android's name with BUTTON_ dropped ("A", "L1", "DPAD_UP") */
    static String label(int src) { String n = SOURCES[src]; return n.startsWith("BUTTON_") ? n.substring(7) : n; }

    /** A spec as the settings row shows it */
    static String summary(String spec) {
        if (spec == null || spec.isEmpty()) return "Default";
        PadMap m = parse(spec, 0), d = defaults(0);
        int changed = 0;
        for (int i = 0; i < SOURCES.length; i++) if (m.target[i] != d.target[i]) changed++;
        return changed == 0 ? "Custom (as the default)" : "Custom (" + changed + " button" + (changed == 1 ? "" : "s") + " moved)";
    }
}
