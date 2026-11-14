package dev.nxnative.android;

import android.content.Context;
import android.content.SharedPreferences;

/** The launcher's own settings (the on-screen controls keep theirs in TouchControls): the overlay over the game, frame generation. */
final class Prefs {
    private Prefs() {}

    /**
     * A settings file, read again whenever another process has written it. The launcher and the game (GameActivity, in its own process :game) each keep the file in memory:
     * with MODE_PRIVATE a change made in the game's panel stayed unseen by the launcher, whose next write put its old copy back over it, so in-game settings came back
     * as they were. MODE_MULTI_PROCESS reloads the file on each getSharedPreferences when it changed on disk (deprecated, still honoured), so every access goes through
     * here, and writes use commit(): written before the call returns, so Stop (which ends the game's process) cannot drop one still queued by apply().
     */
    @SuppressWarnings("deprecation")
    static SharedPreferences open(Context c, String name) { return c.getSharedPreferences(name, Context.MODE_MULTI_PROCESS); }
    static SharedPreferences of(Context c) { return open(c, "launcher"); }

    /** The overlay over the game (master switch: the frame rate and the other items top right, Overlay; the build tag top left is always shown); on by default. DM_OVERLAY=0 / 1 in env.txt overrides it for a run. */
    static boolean overlay(Context c) { return of(c).getBoolean("overlay", true); }
    static void overlay(Context c, boolean on) { of(c).edit().putBoolean("overlay", on).commit(); }

    /** One item of the overlay (Overlay.ITEMS); all on by default, shown only while the overlay is on */
    static boolean overlayItem(Context c, String key) { return of(c).getBoolean("overlay_" + key, true); }
    static void overlayItem(Context c, String key, boolean on) { of(c).edit().putBoolean("overlay_" + key, on).commit(); }

    /** The game's window size (Game resolution): DM_WINDOW_SIZE for the host (loader.c window_apply), "1920x1080" (Phone 1080p, the default), "original" (the game's own, 2880x1620 in Donkey Kong Bananza), "1280x720" (Phone 720p: less GPU time a frame), "2560x1440" (Phone 1440p: for QHD screens), "960x540" (Phone 540p: for weaker GPUs) or "848x480" (Phone 480p). DM_WINDOW_SIZE in env.txt overrides it for a run.
     *  A game whose profile has graphics_scale rows gets the graphics scale and operation mode that give the size instead (Profile.resolutionEnv).
     *  Listed from the largest. The stored value is the size itself, so the order can change; the default is WINDOW_DEFAULT. */
    static final String[] WINDOWS = {"original", "2560x1440", "1920x1080", "1280x720", "960x540", "848x480"},
            WINDOW_NAMES = {"Original (the game's own size)", "Phone 1440p (2560x1440)", "Phone 1080p (1920x1080)", "Phone 720p (1280x720)", "Phone 540p (960x540)", "Phone 480p (848x480)"},
            WINDOW_SHORT = {"Original", "1440p", "1080p", "720p", "540p", "480p"};
    static final String WINDOW_DEFAULT = "1920x1080";   // Phone 1080p
    /** A window size's name in the lists, its short name for a settings row (short); the value itself when it is none of WINDOWS (an env.txt-style size) */
    static String windowName(String v, boolean shortName) {
        int i = java.util.Arrays.asList(WINDOWS).indexOf(v);
        return i < 0 ? v : (shortName ? WINDOW_SHORT : WINDOW_NAMES)[i];
    }
    static String window(Context c) { return of(c).getString("window", WINDOW_DEFAULT); }
    static void window(Context c, String v) { of(c).edit().putString("window", v).commit(); }

    // ---- A game's own settings (its Manage sheet › Game settings, the in-game panel), stored per title ID as "<key>@<title ID>"; a game without its own value
    // takes the default (the main screen's Settings, global). DKB with nothing of its own set runs exactly as before ----
    static String gameKey(String key, String titleId) { return key + "@" + titleId; }
    /** Rumble (Rumble.java): the main screen's default (on), then each game's own */
    static boolean rumble(Context c) { return of(c).getBoolean("rumble", true); }
    static void rumbleDefault(Context c, boolean on) { of(c).edit().putBoolean("rumble", on).commit(); }
    static boolean rumble(Context c, String titleId) { return of(c).getBoolean(gameKey("rumble", titleId), rumble(c)); }
    static void rumble(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("rumble", titleId), on).commit(); }
    /** Use fast cores (Native.pinThreads): the main screen's default (off: not measured yet, and it may work against the ADPF hint session of vka_perf.c), then each game's own */
    static boolean pinThreads(Context c) { return of(c).getBoolean("pin_threads", false); }
    static void pinThreadsDefault(Context c, boolean on) { of(c).edit().putBoolean("pin_threads", on).commit(); }
    static boolean pinThreads(Context c, String titleId) { return of(c).getBoolean(gameKey("pin_threads", titleId), pinThreads(c)); }
    static void pinThreads(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("pin_threads", titleId), on).commit(); }
    /** Xbox button label swap: 0 off; 1 labels (the prompts' A/B and X/Y drawn the Xbox way: DM_XBOX_LABELS, hle_fs.c); 2 labels + confirm with A (A and B by label,
     *  DM_PAD_LABELLED=2, so only X/Y are redrawn). The main screen's default (off), then each game's own */
    static final String[] XBOX_NAMES = {"Off", "Swap labels for X/Y and A/B", "Swap labels for X/Y, swap buttons A/B"};
    static int xboxButtons(Context c) { int v = of(c).getInt("xbox_buttons", 0); return v >= 0 && v < XBOX_NAMES.length ? v : 0; }
    static void xboxButtonsDefault(Context c, int v) { of(c).edit().putInt("xbox_buttons", v).commit(); }
    static int xboxButtons(Context c, String titleId) { int v = of(c).getInt(gameKey("xbox_buttons", titleId), xboxButtons(c)); return v >= 0 && v < XBOX_NAMES.length ? v : 0; }
    static boolean ownXboxButtons(Context c, String titleId) { return of(c).contains(gameKey("xbox_buttons", titleId)); }
    /** null: back to Default (follows Settings) */
    static void xboxButtons(Context c, String titleId, Integer v) {
        if (v == null) of(c).edit().remove(gameKey("xbox_buttons", titleId)).commit(); else of(c).edit().putInt(gameKey("xbox_buttons", titleId), v).commit();
    }
    static String xboxLabel(int v) { return XBOX_NAMES[v]; }
    /** The in-game panel's Player 1 (PadOrder): the chosen gamepad's InputDevice descriptor, for every game; "" = automatic */
    static String padFirst(Context c) { return of(c).getString("pad_first", ""); }
    static void padFirst(Context c, String key) { of(c).edit().putString("pad_first", key == null ? "" : key).commit(); }

    /** Button mapping (PadMap, DM_PAD_MAP): Settings' default ("" = none: the host's default), then each game's own (unset = Settings'; "" = none for this game) */
    static String padMap(Context c) { return of(c).getString("pad_map", ""); }
    static void padMapDefault(Context c, String spec) { of(c).edit().putString("pad_map", spec == null ? "" : spec).commit(); }
    static String padMap(Context c, String titleId) { return of(c).getString(gameKey("pad_map", titleId), padMap(c)); }
    static boolean ownPadMap(Context c, String titleId) { return of(c).contains(gameKey("pad_map", titleId)); }
    /** null: back to Default (follows Settings) */
    static void padMap(Context c, String titleId, String spec) { put(c, gameKey("pad_map", titleId), spec); }

    /** Game settings › Display (per game): false = Fit (keep aspect, letterboxed; the default), true = Stretch to screen (DM_VK_STRETCH=1: the picture fills the display) */
    /** Settings › Screen rotation: false = Landscape (the default: the game window is locked to landscape on a phone), true = Automatic (no orientation request, the window follows the display, portrait too) */
    static final String[] ROTATION_NAMES = {"Landscape", "Automatic"};
    static boolean rotationAuto(Context c) { return of(c).getBoolean("rotation_auto", false); }
    static void rotationAuto(Context c, boolean on) { of(c).edit().putBoolean("rotation_auto", on).commit(); }
    static final String[] DISPLAY_NAMES = {"Fit (keep aspect)", "Stretch to screen"};
    static boolean stretch(Context c, String titleId) { return of(c).getBoolean(gameKey("stretch", titleId), false); }
    static void stretch(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("stretch", titleId), on).commit(); }
    /** The in-game panel's Picture size (per game): the picture's share of its fitted or stretched size, PictureFit.SIZE_MIN..100 % (DM_VK_PICTURE_SIZE; 100, the default, is not passed) */
    static int pictureSize(Context c, String titleId) { return PictureFit.size(of(c).getInt(gameKey("picture_size", titleId), PictureFit.SIZE_MAX)); }
    static void pictureSize(Context c, String titleId, int pct) { of(c).edit().putInt(gameKey("picture_size", titleId), PictureFit.size(pct)).commit(); }

    /** The in-game panel's own size and text size (PanelLook: 0 Small, 1 Normal, 2 Large), for every game */
    static int panelSize(Context c) { return PanelLook.of(of(c).getInt("panel_size", PanelLook.NORMAL)); }
    static void panelSize(Context c, int v) { of(c).edit().putInt("panel_size", PanelLook.of(v)).commit(); }
    /** Settings › Controller: in-game panel (PadPanel.OPEN_*): how a controller opens and closes the in-game panel, for every game; Home, or − and + held, by default */
    static int panelPad(Context c) { return PadPanel.open(of(c).getInt("panel_pad", PadPanel.OPEN_BOTH)); }
    static void panelPad(Context c, int v) { of(c).edit().putInt("panel_pad", PadPanel.open(v)).commit(); }
    /** The one-time hint over the game that says how the panel opens (GameActivity.panelHint), once it has been shown */
    static boolean panelHinted(Context c) { return of(c).getBoolean("panel_hint", false); }
    static void panelHinted(Context c, boolean v) { of(c).edit().putBoolean("panel_hint", v).commit(); }
    static int panelText(Context c) { return PanelLook.of(of(c).getInt("panel_text", PanelLook.NORMAL)); }
    static void panelText(Context c, int v) { of(c).edit().putInt("panel_text", PanelLook.of(v)).commit(); }
    /** Games whose speed follows the measured frame rate (their profile has fps_decouple sead: the host's sead_rate) get a frame limit instead of 60 / 30 / 20 locking.
     *  The limit is one of PACE_CAPS: 0 = 60 fps (the game's fastest rate, where the measured rate is clamped), or at most that many frames a second (paceOf) */
    static final int[] PACE_CAPS = {0, 45, 40, 30, 20};   // saved as the rate itself (fps_cap), not an index: 20 was added last, the others keep their meaning
    static boolean paced(String titleId) { Profile p = Profile.of(titleId); return p != null && p.decoupled; }
    /** Games the host's frame limiter caps (hle_fpslock.c fps_limit_wait, DM_FPS_LIMIT): the paced ones, and those whose profile sets a default limit (game.tsv fps_limit, a game
     *  with no limiter of its own the host can write). Both get a frame limit (PACE_CAPS) in place of 60 / 30 / 20 locking */
    /** Full game speed at low frame rates (per game, default on), for paced games: sead's controller hands the game the measured frame rate (hle_fpslock.c sead_rate).
     *  Off: the game's own 60 / interval, as on the console (DM_FPS_DECOUPLE=0 at start, Native.decoupleSet while it runs). Game settings and the in-game panel share it */
    static boolean decouple(Context c, String titleId) { return of(c).getBoolean(gameKey("decouple", titleId), true); }
    static void decouple(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("decouple", titleId), on).commit(); }
    /** Games whose framework runs whole fixed steps a frame that the host holds (game.tsv fps_steps: hle_fpslock.c steps_tick), as Mario Kart World's */
    static boolean steps(String titleId) { Profile p = Profile.of(titleId); return p != null && p.steps; }
    /** Hold 60 fps (per game, default off), for steps games at the cap of 60: one step a frame always, where the game's own mode runs two (30 fps) after a frame its work
     *  overran. A frame that runs long then plays a little slower instead of dropping to 30 (DM_FPS_HOLD60=1 at start, Native.hold60Set while it runs) */
    static boolean fullVegetation(Context c, String titleId) { return of(c).getBoolean(gameKey("full_vegetation", titleId), Recommend.bool(rec(c, titleId, Recommend.VEGETATION), true)); }
    static void fullVegetation(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("full_vegetation", titleId), on).commit(); }
    /** Advanced › 3D scene (Game settings, a game with scene_scale rows: SceneRes; before the Resolution presets, Scene resolution): the row's name, "" for Game default (the game's own palette).
     *  Applies under Advanced only (resolutionChoice); a value from before the presets picks the preset it matches (ResPresets.current) */
    static String sceneRes(Context c, String titleId) { return of(c).getString(gameKey("scene_res", titleId), ""); }
    static void sceneRes(Context c, String titleId, String name) {
        if (name == null || name.isEmpty()) of(c).edit().remove(gameKey("scene_res", titleId)).commit(); else of(c).edit().putString(gameKey("scene_res", titleId), name).commit();
    }
    static boolean gameOption(Context c, String titleId, String env) { return of(c).getBoolean(gameKey("opt_" + env, titleId), Recommend.bool(rec(c, titleId, Recommend.OPTION + env), false)); }
    static void gameOption(Context c, String titleId, String env, boolean on) { of(c).edit().putBoolean(gameKey("opt_" + env, titleId), on).commit(); }
    static boolean hold60(Context c, String titleId) { return of(c).getBoolean(gameKey("hold60", titleId), Recommend.bool(rec(c, titleId, Recommend.HOLD60), false)); }
    static void hold60(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("hold60", titleId), on).commit(); }
    static final String HOLD60_TEXT = "Off: the game drops to 30 fps when it cannot keep 60, as on the console. On: the game always runs its 60 fps mode and Banana Native does not hold frames, so it never drops to 30; the frame rate is less even and the game runs a little slower while under 60. Only with the 60 fps cap";
    static boolean limited(String titleId) { Profile p = Profile.of(titleId); return p != null && (p.decoupled || p.fpsLimit > 0); }
    /** The profile's default frame limit (game.tsv fps_limit), 0 without one */
    static int profileLimit(String titleId) { Profile p = Profile.of(titleId); return p == null ? 0 : p.fpsLimit; }
    /** A saved cap as one of PACE_CAPS: the highest at or below it, 20 below that (the main screen's 15 for the other games). Since 20 was added a saved 20 or 15 (only the main
     *  screen's default can be one) is 20 fps here, where it was 30 */
    static int paceOf(int v) { return v <= 0 || v >= 60 ? 0 : v >= 45 ? 45 : v >= 40 ? 40 : v >= 30 ? 30 : 20; }
    static String paceLabel(int v) { return (v <= 0 ? 60 : v) + " fps"; }   // 0 is 60: the game's own fastest rate, where the decoupling's rate is clamped
    /** A limited game's cap as saved, without capOf (which turns 45 and 40 into 30 for the other games): its own, else its profile's fps_limit, else the main screen's default, as one of PACE_CAPS */
    static int paceCap(Context c, String titleId) {
        int pl = profileLimit(titleId);
        Integer r = recCap(c, titleId);   // the recommended limit comes before the profile's and the main screen's (Recommend.effective)
        return paceOf(of(c).getInt(gameKey("fps_cap", titleId), r != null ? r : pl > 0 ? pl : of(c).getInt("fps_cap", -1)));
    }
    /** The recommended frame rate cap as the launcher stores it (Recommend.cap), null for none */
    static Integer recCap(Context c, String titleId) { return Recommend.cap(rec(c, titleId, Recommend.FPS_CAP), limited(titleId)); }
    /** Game settings › Reset to defaults: every choice of the game's own back to the main screen's default (Extra environment's lines stay) */
    static final String[] GAME_SETTINGS = {"window", "render_pct", "gfx_quality", "fps_cap", "gpu_driver", "gpu_ts", "pause_return", "ndsr_choice2", "ndsr_sharp", "drs2", "ram_saver", "decouple", "hold60", "full_vegetation", "gsr2_clarity", "dlss_sharp", "dlss_mode", "scene_res", "res_preset", "aspect"};
    static void resetGame(Context c, String titleId) {
        SharedPreferences.Editor e = of(c).edit();
        for (String k : GAME_SETTINGS) e.remove(gameKey(k, titleId));
        e.remove(gameKey("lp_mode", titleId));   // Turn Link Play off, retaining the saved host and room for next time.
        Profile p = Profile.of(titleId);
        if (p != null) {
            for (Profile.Option opt : p.options) e.remove(gameKey("opt_" + opt.env, titleId));
        }
        e.commit();
    }

    // ---- Recommended settings per phone (game.tsv recommend rows: Recommend). Computed at each read, never stored: a release that changes a recommendation reaches every
    // player who did not set that setting for the game. The game's own value wins, then the recommendation, then the main screen's default, then the built-in one ----

    /** What the readiness check found on a driver (Recommend.keep of its answer), kept per driver choice (Recommend.SYSTEM or an installed driver's folder name):
     *  GameActivity's start-up check and the Readiness check screen write it; the matches read it */
    static void rememberFacts(Context c, String driverChoice, String vulkan) {
        String k = Recommend.keep(vulkan);
        if (!k.isEmpty() && !k.equals(of(c).getString("vk_facts@" + driverChoice, ""))) of(c).edit().putString("vk_facts@" + driverChoice, k).commit();
    }
    /** The phone's own driver's facts from GLES (Recommend.glFacts; GpuGl at the launcher's start), kept only while no readiness check has answered on that driver, so
     *  the recommendations show from a fresh install on; the check's own answer replaces them */
    static void rememberGlFacts(Context c, String glFacts) {
        String k = Recommend.keep(glFacts);
        if (!k.isEmpty() && of(c).getString("vk_facts@" + Recommend.SYSTEM, "").isEmpty()) of(c).edit().putString("vk_facts@" + Recommend.SYSTEM, k).commit();
    }
    static java.util.Map<String, String> facts(Context c, String driverChoice) { return Readiness.facts(of(c).getString("vk_facts@" + driverChoice, "")); }

    /** The game's process pins the recommended driver when its readiness check starts (GameActivity.readinessHold): the driver opens once a process, so facts the check
     *  writes cannot move the start to another driver than the one checked */
    private static String pinnedFor, pinnedDriver;
    static void pinDriver(Context c, String titleId) { pinnedDriver = recommendedDriver(c, titleId); pinnedFor = titleId; }

    /** The recommended driver choice (Recommend.SYSTEM or an installed driver's folder) of the rows matching the phone's own driver, null for none (or none installed) */
    static String recommendedDriver(Context c, String titleId) {
        if (titleId == null) return null;
        if (titleId.equals(pinnedFor)) return pinnedDriver;
        Profile p = Profile.of(titleId);
        if (p == null || p.recommend.isEmpty()) return null;
        java.util.Map<String, String> sys = facts(c, Recommend.SYSTEM);
        String want = Recommend.driver(Recommend.parse(p.recommend), sys);
        if (want == null) return null;
        java.util.Map<String, java.util.Map<String, String>> inst = new java.util.LinkedHashMap<>();
        for (GpuDriver.Info i : GpuDriver.installed(c)) inst.put(i.dir.getName(), facts(c, i.dir.getName()));
        return Recommend.resolveDriver(want, sys, inst, of(c).getString("gpu_driver", ""));
    }

    /** The driver choice a game starts with (GpuDriver.selected's): Recommend.SYSTEM or a folder name */
    static String driverChoice(Context c, String titleId) {
        GpuDriver.Info i = GpuDriver.selected(c, titleId);
        return i == null ? Recommend.SYSTEM : i.dir.getName();
    }

    /** The recommended values for a game on this phone, setting → value in a row's words (the driver as a driver choice): the rows on the facts of the driver it starts
     *  with, and the driver rows on the phone's own driver's. Empty without rows, or before the readiness check has run on that driver (only "any" rows then) */
    static java.util.Map<String, String> recommended(Context c, String titleId) {
        Profile p = titleId == null ? null : Profile.of(titleId);
        if (p == null || p.recommend.isEmpty()) return java.util.Collections.emptyMap();
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>(Recommend.values(Recommend.parse(p.recommend), facts(c, driverChoice(c, titleId))));
        String d = recommendedDriver(c, titleId);
        if (d != null) out.put(Recommend.DRIVER, d);
        return out;
    }
    static String rec(Context c, String titleId, String setting) { return recommended(c, titleId).get(setting); }

    /** Every recommended setting the game offers (Game settings shows each), as it stands: its value, the player's own or not, the recommendation */
    static java.util.List<Recommend.State> recStates(Context c, String titleId) {
        java.util.List<Recommend.State> out = new java.util.ArrayList<>();
        Profile p = Profile.of(titleId);
        if (p == null) return out;
        java.util.Map<String, String> r = recommended(c, titleId);
        if (!ResPresets.presets(p).isEmpty()) out.add(state(Recommend.RES_PRESET, resolutionChoice(c, titleId), ownResolution(c, titleId), r));
        out.add(state(Recommend.DRIVER, driverChoice(c, titleId), GpuDriver.choice(c, titleId) != null, r));
        boolean dlss = dlssGame(titleId);
        out.add(state(Recommend.UPSCALER, dlss ? Recommend.DLSS_MODES[GameUpscaler.dlssMode(c, titleId)] : Recommend.UPSCALERS[GameUpscaler.mode(c, titleId)],
                      dlss ? GameUpscaler.ownDlssMode(c, titleId) : GameUpscaler.ownMode(c, titleId), r));
        if (steps(titleId)) out.add(state(Recommend.HOLD60, Recommend.onOff(hold60(c, titleId)), own(c, "hold60", titleId), r));
        boolean lim = limited(titleId);
        out.add(state(Recommend.FPS_CAP, Recommend.capName(lim ? paceCap(c, titleId) : fpsCap(c, titleId), lim), ownFpsCap(c, titleId), r));
        if (drsGame(titleId)) out.add(state(Recommend.DRS, Recommend.DRS_VALUES[drs(c, titleId)], ownDrs(c, titleId), r));
        if (p.vegetationDetail) out.add(state(Recommend.VEGETATION, Recommend.onOff(fullVegetation(c, titleId)), own(c, "full_vegetation", titleId), r));
        if (skipLayersGame(titleId)) out.add(state(Recommend.SKIP_LAYERS, Recommend.onOff(skipLayers(c, titleId)), own(c, "vk_skip_layers", titleId), r));
        for (Profile.Option o : p.options) {
            String k = Recommend.OPTION + o.env;
            out.add(new Recommend.State(k, o.label, Recommend.onOff(gameOption(c, titleId, o.env)), own(c, "opt_" + o.env, titleId), r.get(k)));
        }
        return out;
    }
    private static Recommend.State state(String setting, String value, boolean own, java.util.Map<String, String> r) {
        String v = setting.equals(Recommend.DRIVER) && Recommend.SYSTEM.equals(value) ? "system" : value, rv = r.get(setting);
        if (setting.equals(Recommend.DRIVER) && Recommend.SYSTEM.equals(rv)) rv = "system";
        return new Recommend.State(setting, Recommend.label(setting), v, own, rv);
    }
    private static boolean own(Context c, String key, String titleId) { return of(c).contains(gameKey(key, titleId)); }

    /** Game settings › Use recommended (the "Recommended for this phone" card): the game's own values of the settings that have a recommendation on this phone are removed, so they take it again; the rest stays */
    static void backToRecommended(Context c, String titleId) {
        SharedPreferences.Editor e = of(c).edit();
        for (Recommend.State s : recStates(c, titleId))
            if (s.recommended != null) for (String k : Recommend.prefKeys(s.setting, dlssGame(titleId))) e.remove(gameKey(k, titleId));
        e.commit();
    }

    /** A recommended value's short name, as the player reads it (a changed row's "Recommended: …" line, a list's "Recommended (…)"): the preset's label, the driver's name
     *  without its version, the Upscaler's or Dynamic resolution's name as its list shows it, a cap in fps, On / Off */
    static String recName(Context c, String titleId, String setting, String v) {
        if (setting.equals(Recommend.RES_PRESET)) { ResPresets.Preset p = ResPresets.of(ResPresets.presets(Profile.of(titleId)), v); return p == null ? v : p.label; }
        if (setting.equals(Recommend.DRIVER)) {
            if (v.equals("system")) return "System";
            for (GpuDriver.Info i : GpuDriver.installed(c)) if (i.dir.getName().equals(v)) return i.name;
        }
        if (v.equals("on") || v.equals("off")) return v.equals("on") ? "On" : "Off";
        if (setting.equals(Recommend.UPSCALER)) {
            boolean dl = dlssGame(titleId);
            int i = Recommend.index(dl ? Recommend.DLSS_MODES : Recommend.UPSCALERS, v);
            String n = i < 0 ? v : (dl ? DlssTune.MODE_NAMES : GameUpscaler.MODES)[i];
            return n.contains(" (") ? n.substring(0, n.indexOf(" (")) : n;
        }
        if (setting.equals(Recommend.DRS)) { int i = Recommend.index(Recommend.DRS_VALUES, v); return i < 0 ? v : DRS_NAMES[i]; }
        if (setting.equals(Recommend.FPS_CAP)) return v.equals("own") ? "the game's own" : v + " fps";
        return v;
    }

    /** A recommended value's name in the "Recommended for this phone" card, where it stands without its row: on / off and numbers say what they are ("Hold 60 off", "30 fps cap") */
    static String recCardName(Context c, String titleId, String setting, String v) {
        String n = recName(c, titleId, setting, v);
        if (v.equals("on") || v.equals("off")) {
            String what = setting.equals(Recommend.HOLD60) ? "Hold 60" : setting.startsWith(Recommend.OPTION) ? optionLabel(titleId, setting) : Recommend.label(setting);
            return what + " " + v;
        }
        if (setting.equals(Recommend.FPS_CAP)) return n + " cap";
        if (setting.equals(Recommend.DRS)) return "Dynamic resolution " + n;
        return n;
    }
    private static String optionLabel(String titleId, String setting) {
        Profile p = Profile.of(titleId);
        if (p != null) for (Profile.Option o : p.options) if (setting.equals(Recommend.OPTION + o.env)) return o.label;
        return setting;
    }

    /** Dynamic resolution, for games whose profile has drs_ctl rows (the host steers them: probe.c drs_install): the range the render size moves in, as % of the window,
     *  always up to 100 %; {100, 100} is off: the full size and no upscaling. Stored as an index into DRS, the main screen's default then each game's own */
    static boolean drsGame(String titleId) { Profile p = Profile.of(titleId); return p != null && p.drs; }
    /** A game whose DLSS runs on the host's GSR2 stand-in (game.tsv dlss_standin): its Upscaler row in Game settings and the in-game panel offers the stand-in's modes, with Motion clarity and Temporal sharpness (GameUpscaler, DlssTune) */
    /** Whether this game's frame limit only paces the presents (game.tsv fps_limit_present): the cap screens add PRESENT_ONLY_NOTE */
    static boolean presentOnly(String titleId) { Profile p = Profile.of(titleId); return p != null && p.presentOnly; }
    static final String PRESENT_ONLY_NOTE = "Experimental for this game: the cap only limits how often the picture is shown, not the game itself. Expect bugs (for example the game running slower or faster).";
    static boolean dlssGame(String titleId) { Profile p = Profile.of(titleId); return p != null && p.dlss; }
    static final int[][] DRS = {{100, 100}, {80, 100}, {70, 100}, {60, 100}, {50, 100}, {40, 100}, {30, 100}};   // 30 %: the game's rate table stops at 3x (640x360 at 1080p)
    static final String[] DRS_NAMES = {"Off", "80%", "70%", "60%", "50%", "40%", "30%"};   // the lowest render size, as % of the window (always up to 100 %)
    static final int DRS_DEFAULT = 5;   // 40-100 %
    static int drs(Context c) { int v = of(c).getInt("drs2", DRS_DEFAULT); return v >= 0 && v < DRS.length ? v : DRS_DEFAULT; }
    static void drsDefault(Context c, int v) { of(c).edit().putInt("drs2", v).commit(); }
    static boolean ownDrs(Context c, String titleId) { return of(c).contains(gameKey("drs2", titleId)); }
    static int drs(Context c, String titleId) {
        int r = Recommend.index(Recommend.DRS_VALUES, rec(c, titleId, Recommend.DRS)), d = r >= 0 ? r : drs(c);   // the recommended range before the main screen's
        int v = of(c).getInt(gameKey("drs2", titleId), d);
        return v >= 0 && v < DRS.length ? v : d;
    }
    /** null removes the game's own choice */
    static void drs(Context c, String titleId, Integer v) {
        if (v == null) of(c).edit().remove(gameKey("drs2", titleId)).commit(); else of(c).edit().putInt(gameKey("drs2", titleId), v).commit();
    }

    /** The game's window size: its own choice, else the default (defaultWindow) */
    static String gameWindow(Context c, String titleId) { return of(c).getString(gameKey("window", titleId), defaultWindow(c, titleId)); }
    /** A game's default window size: the main screen's when the player chose one there, else its profile's android_window (a workaround for one game, game.tsv), else Phone 1080p */
    static String defaultWindow(Context c, String titleId) {
        if (of(c).contains("window")) return window(c);
        Profile p = Profile.of(titleId);
        return p != null && java.util.Arrays.asList(WINDOWS).contains(p.window) ? p.window : WINDOW_DEFAULT;
    }
    /** null: back to the default */
    static void gameWindow(Context c, String titleId, String v) { put(c, gameKey("window", titleId), v); }
    static boolean ownWindow(Context c, String titleId) { return of(c).contains(gameKey("window", titleId)); }

    /** Resolution (a game whose profile has res_preset rows: ResPresets): the stored preset id or ResPresets.ADVANCED, "" when the player chose neither. null: back to the default */
    static String resPreset(Context c, String titleId) { return of(c).getString(gameKey("res_preset", titleId), ""); }
    static void resPreset(Context c, String titleId, String v) { put(c, gameKey("res_preset", titleId), v); }
    /**
     * The Resolution choice in force for a game with presets: a preset id or ResPresets.ADVANCED; "" for a game without presets. Nothing stored: the preset that the choices
     * from before the presets match (the game's own Game resolution or the main screen's, and Scene resolution: ResPresets.current), the profile's default without them
     */
    static String resolutionChoice(Context c, String titleId) {
        Profile p = Profile.of(titleId);
        java.util.List<ResPresets.Preset> ps = ResPresets.presets(p);
        if (ps.isEmpty()) return "";
        boolean legacy = ownWindow(c, titleId) || of(c).contains("window") || !sceneRes(c, titleId).isEmpty();
        String stored = ResPresets.mapStored(ps, p.presetFrom, resPreset(c, titleId)), r = rec(c, titleId, Recommend.RES_PRESET);
        if (stored.isEmpty() && !ownResolution(c, titleId) && ResPresets.of(ps, r) != null) stored = r;   // nothing of the game's own: the recommended preset, before the main screen's window
        return ResPresets.current(ps, p, SceneRes.presets(p), stored, legacy, gameWindow(c, titleId), sceneRes(c, titleId));
    }
    /**
     * Tester rows (Settings › Help › Tester settings, off by default): the launcher rows that only testers need. For a game with Resolution presets they are the Advanced choice with
     * its Window and 3D scene rows, the Upscaler and the Dynamic resolution slider (simpleRes); a player sees Resolution's presets alone. What is stored keeps applying either way
     */
    static boolean testerRows(Context c) { return of(c).getBoolean("tester_rows", false); }
    static void testerRows(Context c, boolean on) { of(c).edit().putBoolean("tester_rows", on).commit(); }
    /** Whether a game shows the simple Resolution only: its profile has Resolution presets and the tester rows are off */
    static boolean simpleRes(Context c, String titleId) { return !testerRows(c) && !ResPresets.presets(Profile.of(titleId)).isEmpty(); }
    /** Whether the player chose this game's Resolution: a preset or Advanced, or (from before the presets) its own window or 3D scene */
    static boolean ownResolution(Context c, String titleId) {
        return !resPreset(c, titleId).isEmpty() || ownWindow(c, titleId) || !sceneRes(c, titleId).isEmpty();
    }
    /** The window the game starts with: its Resolution preset's, else (Advanced, or a game without presets) Game resolution's (gameWindow) */
    static String startWindow(Context c, String titleId) {
        return ResPresets.window(ResPresets.presets(Profile.of(titleId)), resolutionChoice(c, titleId), gameWindow(c, titleId));
    }
    /** The 3D scene overlay the game starts with, by its name in the pack's scene_res rows ("" for the game's own palette): its preset's, else (Advanced, or a game without presets) Advanced › 3D scene's */
    static String startScene(Context c, String titleId) {
        java.util.List<ResPresets.Preset> ps = ResPresets.presets(Profile.of(titleId));
        return ps.isEmpty() ? sceneRes(c, titleId) : ResPresets.scene(ps, resolutionChoice(c, titleId), sceneRes(c, titleId));
    }

    /**
     * Resolution, Upscaling quality and Graphics quality of a game whose profile has a render_cfg row (Profile.renderCfg): the output in "window" (the Game resolution key, so
     * an older choice carries over), the render scale in "render_pct" and the graphics quality in "gfx_quality" (1 quality, 0 performance), each per game. Read through
     * Profile.renderSetting (the lazy migration of an older Game resolution value); a pick writes all three
     */
    static Profile.Render render(Context c, Profile p, String titleId) {
        return p.renderSetting(gameWindow(c, titleId), of(c).getInt(gameKey("render_pct", titleId), -1), of(c).getInt(gameKey("gfx_quality", titleId), -1));
    }
    static void render(Context c, String titleId, Profile.Render r) {
        of(c).edit().putString(gameKey("window", titleId), r.out).putInt(gameKey("render_pct", titleId), r.pct).putInt(gameKey("gfx_quality", titleId), r.quality ? 1 : 0).commit();
    }
    /** The device class of the host's render target pool choice (vka_low_ram: MemTotal 12 GiB or less; ActivityManager's totalMem is MemTotal) */
    static boolean lowRam(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return true;
        android.app.ActivityManager.MemoryInfo m = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(m);
        return m.totalMem <= (12L << 30);
    }

    /**
     * The frame rate cap (the in-game panel): -1 = not set (the game profile's fps_target), 0 = the game's own, else 15, 20, 30 or 60. DM_FPS_TARGET for the host at
     * start, and Native.fpsCapSet while the game runs. A DM_FPS_TARGET line in env.txt overrides it for a run. 40 and 45 are no longer offered (they did not hold):
     * a stored 40 or 45 reads as the next lower cap (capOf), so an older install keeps a working choice.
     */
    static final int[] FPS_CAPS = {0, 60, 30, 20, 15};
    /** A stored cap as one of FPS_CAPS: -1 and 0 as they are, else the highest offered cap at or below it (45 → 30), the game's own when none is */
    static int capOf(int v) {
        if (v <= 0) return v;
        int best = 0;
        for (int k : FPS_CAPS) if (k > 0 && k <= v && k > best) best = k;
        return best;
    }
    static int fpsCap(Context c) { return capOf(of(c).getInt("fps_cap", -1)); }
    static void fpsCap(Context c, int v) { of(c).edit().putInt("fps_cap", v).commit(); }
    /** The game's cap: its own (set in its Game settings or its in-game panel), else the recommended one, else the default */
    static int fpsCap(Context c, String titleId) { Integer r = limited(titleId) ? null : recCap(c, titleId); return capOf(of(c).getInt(gameKey("fps_cap", titleId), r != null ? r : fpsCap(c))); }
    /** Whether the game starts with a cap chosen for it, its own or the recommended one (a limited game passes DM_FPS_LIMIT only then: GameActivity) */
    static boolean chosenFpsCap(Context c, String titleId) { return ownFpsCap(c, titleId) || recCap(c, titleId) != null; }

    /**
     * Whether the host found that this game picks its own frame rate (its cap status reports no rate it can hold: Native.fpsCapStatus rates 0), as the last run
     * saw it: its Game settings and its in-game panel then show no cap choices, which would do nothing. Written by GameActivity and the panel while the game runs.
     */
    static boolean ownRate(Context c, String titleId) { return of(c).getBoolean(gameKey("own_rate", titleId), false); }
    static void ownRate(Context c, String titleId, boolean v) { if (ownRate(c, titleId) != v) of(c).edit().putBoolean(gameKey("own_rate", titleId), v).commit(); }
    /** null: back to the default */
    static void fpsCap(Context c, String titleId, Integer v) {
        if (v == null) of(c).edit().remove(gameKey("fps_cap", titleId)).commit();
        else of(c).edit().putInt(gameKey("fps_cap", titleId), v).commit();
    }
    static boolean ownFpsCap(Context c, String titleId) { return of(c).contains(gameKey("fps_cap", titleId)); }

    /** Pause on return (per game, on by default): the game's screen coming back from the background shows it paused, suspended until a tap (GameActivity.onStart) */
    static boolean pauseOnReturn(Context c, String titleId) { return of(c).getBoolean(gameKey("pause_return", titleId), true); }
    static void pauseOnReturn(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("pause_return", titleId), on).commit(); }
    /** Game settings › Speedhack: Turn off hidden layers (experimental), for a game whose profile names such layers (game.tsv skip_layers): draws of those pixel shaders are
     *  dropped (DM_VK_SKIP_FS). Off by default: the layers were judged hidden by eye in a few places only */
    static boolean skipLayersGame(String titleId) { Profile p = Profile.of(titleId); return p != null && !p.skipLayers.isEmpty(); }
    static String skipLayersKeys(String titleId) { Profile p = Profile.of(titleId); return p == null ? "" : p.skipLayers; }
    static boolean skipLayers(Context c, String titleId) { return of(c).getBoolean(gameKey("vk_skip_layers", titleId), Recommend.bool(rec(c, titleId, Recommend.SKIP_LAYERS), false)); }
    static void skipLayers(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("vk_skip_layers", titleId), on).commit(); }
    /** Game settings › Instanced draws (DM_VK_INSTANCE): runs of draws that repeat one mesh drawn as one instanced draw. Off by default (a GPU loss on Adreno 830 with Turnip, open) */
    static boolean instancedDraws(Context c, String titleId) { return of(c).getBoolean(gameKey("vk_instance", titleId), false); }
    static void instancedDraws(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("vk_instance", titleId), on).commit(); }
    /** Game settings › Background pipelines (DM_VK_PSO_ASYNC): a pipeline the cache doesn't have yet is made on two background threads instead of on the frame,
     *  so a cold cache gives a short pop-in instead of a freeze (Bananza's heaviest area with an empty cache: worst frame 8.1 s → 3.7 s). On by default */
    static boolean psoAsync(Context c, String titleId) { return of(c).getBoolean(gameKey("vk_pso_async", titleId), true); }
    static void psoAsync(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("vk_pso_async", titleId), on).commit(); }
    /**
     * RAM saver (per game, off by default; from Anka Native, NOTICE.md): DM_MEM_FILEBACK=1 with DM_MEM_FILEBACK_KINDS=shader,texture for the host (shader-code pools are
     * file-backed for every game by default; this adds the texture kind), so the game's large write-once texture pools live in a file in the app's cache folder instead of anonymous memory: clean file pages the kernel drops instead of compressing them into
     * zram. For phones short of RAM with free storage; it writes the pools' data to storage at each area load. Lines in env.txt or the game's own lines win
     */
    static boolean ramSaver(Context c, String titleId) { return of(c).getBoolean(gameKey("ram_saver", titleId), false); }
    static void ramSaver(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("ram_saver", titleId), on).commit(); }
    /** Game settings › Classic render passes (DM_VK_CLASSIC_RP): VkRenderPasses with imageless framebuffers instead of dynamic rendering. Off by default (a GPU loss on Adreno 830 with Turnip, open) */
    static boolean classicPasses(Context c, String titleId) { return of(c).getBoolean(gameKey("vk_classic_rp", titleId), false); }
    static void classicPasses(Context c, String titleId, boolean on) { of(c).edit().putBoolean(gameKey("vk_classic_rp", titleId), on).commit(); }

    /**
     * The game's own environment lines (KEY=VALUE, one a line; its Game settings), given to the host after the launcher's settings and env.txt, so they win for
     * this game; env-&lt;title ID&gt;.txt in the app's folder (adb) comes after them. "" = none
     */
    /** GPU timing (Game settings): "segment" or "submission" passed to the host as DM_NVN_GPU_TS, null for the game's default (its profile's gpu_timestamps) */
    static String gpuTs(Context c, String titleId) { return of(c).getString(gameKey("gpu_ts", titleId), null); }
    static void gpuTs(Context c, String titleId, String mode) { put(c, gameKey("gpu_ts", titleId), mode); }

    /** Whether the launcher takes the adb control commands of the intents that start it (AdbControl: add a game, start Prepare, install or pick a GPU driver, export or
     *  import a pack; android/TESTING.md): a test build always; a play or release build only while Settings › Help › ADB control (debug) is on, off by default */
    static boolean adbControl(Context c) { return AdbControl.allowed(BuildConfig.NX_PLAY, of(c).getBoolean("adb_control", false)); }
    static void adbControl(Context c, boolean on) { of(c).edit().putBoolean("adb_control", on).commit(); }

    /** Keep the screen on while a game is prepared (Settings › System, on by default): Home keeps it on while the job runs and its card shows (Ondevice) */
    static boolean keepScreenPreparing(Context c) { return of(c).getBoolean("keep_screen_preparing", true); }
    static void keepScreenPreparing(Context c, boolean on) { of(c).edit().putBoolean("keep_screen_preparing", on).commit(); }

    /** Game language (Game settings): a console language code (GameLanguage.CODES) passed as DM_LANG, null for the phone's language (the default) */
    static String gameLang(Context c, String titleId) { return of(c).getString(gameKey("lang", titleId), null); }
    static void gameLang(Context c, String titleId, String code) { put(c, gameKey("lang", titleId), code); }

    static String gameEnv(Context c, String titleId) { return of(c).getString(gameKey("env", titleId), ""); }
    static void gameEnv(Context c, String titleId, String text) { put(c, gameKey("env", titleId), text == null || text.trim().isEmpty() ? null : text.trim()); }

    private static void put(Context c, String key, String v) {
        if (v == null) of(c).edit().remove(key).commit();
        else of(c).edit().putString(key, v).commit();
    }

    /**
     * The user clock the next game start gets: "YYYY-MM-DDTHH:MM[:SS]" (the host's DM_TIME), "" = the phone's clock. A DM_TIME or
     * DM_TIME_OFFSET line in env.txt still wins for a run; DM_FIXED_TIME= overrides both (deterministic replays).
     */
    static String timeOverride(Context c) { return of(c).getString("time_override", ""); }
    static void timeOverride(Context c, String v) { put(c, "time_override", v == null || v.isEmpty() ? null : v); }

    /**
     * Folders the user picked (main screen, Folders…), as /storage paths; null = the default. Kept in the app's preferences, so an APK update keeps them (an
     * uninstall does not). "pack_<title ID>": the game's pack folder (pack.tsv and shaders/; default the app's packs/<id> when it holds one, else
     * <game>/../<id>-work/android-pack, Pack.find; the older single-game "pack" key is still read when it holds a pack of that title, Folders.packFor);
     * "save_<title ID>": the game's save folder, given to the host as DM_SAVE (default the app's saves/<id>, AppStorage.save).
     */
    static String folder(Context c, String key) { return of(c).getString("folder_" + key, null); }
    static void folder(Context c, String key, String path) { of(c).edit().putString("folder_" + key, path).commit(); }

    /**
     * The default save folder of versions up to 0.1.0, on shared storage: <Switch2 root>/saves/<id> for the play app, saves-test/<id> for the others, the root
     * being the folder that holds the game folder or the .nup. The default is now the app's own saves/<id> (AppStorage.save); a save found here is copied in once
     * (SaveMigrate, GameActivity.saveDir), and this folder is never changed
     */
    static java.io.File oldSaveDefault(java.io.File game, String id) {
        return new java.io.File(game.getAbsoluteFile().getParentFile(), (BuildConfig.NX_PLAY ? "saves/" : "saves-test/") + id);
    }

    /** The old default save folder whose save was copied into app storage (SaveMigrate), null when none was. Written by the game's process (GameActivity) */
    static String migratedFrom(Context c, String titleId) { return of(c).getString(gameKey("save_migrated", titleId), null); }
    static void migratedFrom(Context c, String titleId, String path) { put(c, gameKey("save_migrated", titleId), path); }

    /** Where a game's save backups go (SaveStore): <Switch2 root>/save-backups/<id>, the root being the game folder's parent, for the play and the test app alike
     *  (each backup's manifest says which save folder it came from) */
    static java.io.File backupsDir(java.io.File game, String id) {
        return new java.io.File(game.getAbsoluteFile().getParentFile(), "save-backups/" + id);
    }

    /**
     * Which build a log or a screenshot came from. Play app: "Banana Native 0.1 · 6a6448d" (the version up to its "-", the commit);
     * test app: "Banana Native test · int@6a6448d" (the branch without "android-", the commit). "+dirty" follows the commit when the
     * tree had changes. The play app adds " · official" when the release key signed the APK, else " · unofficial build" (Signer). The main screen shows it and it
     * is the first line of every game log ("[nxlaunch] build ...").
     */
    static String buildTag(Context ctx) { return Signer.tag(buildTag(), BuildConfig.NX_PLAY, signers(ctx)); }

    private static String[] signers;

    /** The SHA-256 of each certificate that signed this APK (its current signers, not past ones of a rotated key), read once; empty when it cannot be read */
    static synchronized String[] signers(Context ctx) {
        if (signers != null) return signers;
        try {
            android.content.pm.SigningInfo si = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
            android.content.pm.Signature[] sig = si == null ? new android.content.pm.Signature[0] : si.getApkContentsSigners();
            String[] h = new String[sig.length];
            for (int i = 0; i < sig.length; i++) h[i] = Signer.sha256(sig[i].toByteArray());
            signers = h;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            signers = new String[0];
        }
        return signers;
    }

    /** Which key signed this APK, for logs and reports: the signer's SHA-256, first 16 hex digits */
    static String signerId(Context ctx) { return Signer.shortId(signers(ctx)); }

    /** The build identifier alone (Signer.buildId): "launcher-2@4a15067", "0.1 · 6a6448d". Home shows it under its title (the build tag would not fit a phone's width) */
    static String buildId() {
        return Signer.buildId(BuildConfig.NX_PLAY, BuildConfig.VERSION_NAME, BuildConfig.NX_COMMIT, BuildConfig.NX_DIRTY, BuildConfig.NX_BRANCH);
    }

    private static String buildTag() { return Signer.buildTag(BuildConfig.NX_PLAY, buildId()); }

    /** Game settings › Aspect ratio, for a game whose profile has a variant labelled w:h (Profile.aspectVariant; a native 4:3 mod): the label chosen ("4:3"), null = as
     *  shipped (16:9). GameActivity passes DM_PATCH_VARIANT=<variant> and DM_VK_PICTURE_ASPECT=<label>; the pack holds both versions (pack_android.py rewrite_variants) */
    static String aspect(Context c, String titleId) { return of(c).getString(gameKey("aspect", titleId), null); }
    static void aspect(Context c, String titleId, String label) { put(c, gameKey("aspect", titleId), label); }
}
