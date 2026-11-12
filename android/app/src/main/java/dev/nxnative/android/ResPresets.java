package dev.nxnative.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolution presets (Game settings › Resolution, the in-game panel): one choice that sets the window (a graphics_scale row of the profile: DM_GRAPHICS_SCALE, DM_OPMODE)
 * and the 3D scene inside it (the game's own palette, or a RomFS overlay of it that the pack step made: DM_ROMFS_OVERLAY, SceneRes) together, labelled by what the player
 * gets. From the profile's res_preset rows (build.gradle presetsOf, Profile.presets; the first row is the default, today's start) with each window's scene_output row (the
 * upscaler output the palette scale multiplies: the scene is about that output times the scale, the scale being the target height over the output's height,
 * dm/tools/scene_res.py). Advanced (testers) keeps the two settings apart as before: Game resolution (Prefs.gameWindow) and 3D scene (Prefs.sceneRes).
 * A player who chose neither a preset nor Advanced gets the preset their older choices match (current), so an existing Game resolution or Scene resolution keeps working.
 * No Android classes: the JVM test runs it on the Mac.
 */
final class ResPresets {
    private ResPresets() {}

    /** The stored choice that keeps Game resolution and 3D scene apart (Advanced) */
    static final String ADVANCED = "advanced";
    /** How far a Scene resolution scale chosen before the presets may lie from a preset's to take it (same window, same mode) */
    static final double MATCH = 0.1;

    /** One res_preset row with its window's output */
    static final class Preset {
        final String id, label, window, mode;   // mode: "default" (the game's own palette), "floor" or "fixed"
        final int height, outW, outH;   // the scene's target height; the window's upscaler output (0 without a scene_output row)
        final boolean measured;   // the output (and so the scene size) was measured on a phone; else "about"

        Preset(String id, String label, String window, String mode, int height, int outW, int outH, boolean measured) {
            this.id = id; this.label = label; this.window = window; this.mode = mode; this.height = height; this.outW = outW; this.outH = outH; this.measured = measured;
        }

        /** Whether the preset draws its own scene (a floor or fixed overlay, named by its id in the pack's scene_res rows) rather than the game's own palette */
        boolean overlay() { return !mode.equals("default"); }

        /** The palette scale: the target height over the window's output height; 0 for the game's own palette */
        double scale() { return overlay() && outH > 0 ? (double) height / outH : 0; }

        /** The scene's size as the second line says it: "about 960x540" (open) or "960x540" (measured) */
        String sceneSize() { return (measured ? "" : "about ") + Math.round(outW * scale()) + "x" + height; }

        /** The second line: "window 1280x720 · 3D about 960x540" */
        String detail() {
            String w = window.equals("original") ? "the game's own" : window;
            String scene = !overlay() ? "the game's own dynamic resolution" : mode.equals("floor") ? "dynamic, at least " + sceneSize() : sceneSize();
            return "window " + w + " · 3D " + scene;
        }

        @Override public String toString() { return id + "|" + label + "|" + window + "|" + mode + "|" + height + "|" + outW + "|" + outH + "|" + (measured ? "measured" : "open"); }
    }

    /**
     * The profile's presets as build.gradle presetsOf writes them: "id|label|window|mode|height|outW|outH|measured or open", ';' between them (empty: the game has none).
     * A malformed row is skipped, and so is a floor or fixed row without an output (no scale to give it)
     */
    static List<Preset> parse(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<Preset> out = new ArrayList<>();
        for (String r : text.split(";")) {
            String[] f = r.trim().split("\\|", -1);
            if (f.length < 8 || !f[0].trim().matches("[A-Za-z0-9_-]+") || f[2].trim().isEmpty()) continue;
            String mode = f[3].trim();
            if (!mode.equals("default") && !mode.equals("floor") && !mode.equals("fixed")) continue;
            int h, w, oh;
            try { h = Integer.parseInt(f[4].trim()); w = Integer.parseInt(f[5].trim()); oh = Integer.parseInt(f[6].trim()); } catch (NumberFormatException e) { continue; }
            if (!mode.equals("default") && (h <= 0 || w <= 0 || oh <= 0 || h > oh)) continue;
            String id = f[0].trim();
            out.add(new Preset(id, f[1].trim().isEmpty() ? id : f[1].trim(), f[2].trim(), mode, h, w, oh, f[7].trim().equals("measured")));
        }
        return Collections.unmodifiableList(out);
    }

    /** A profile's presets (Profile.presets); empty without the profile */
    static List<Preset> presets(Profile p) { return p == null ? Collections.emptyList() : parse(p.presets); }

    /** The preset of an id; null for Advanced, "" or an id the profile no longer has */
    static Preset of(List<Preset> ps, String id) {
        if (ps == null || id == null) return null;
        for (Preset p : ps) if (p.id.equals(id)) return p;
        return null;
    }

    /**
     * The choice in force: a stored preset id or Advanced as stored; else (nothing stored, or an id the profile no longer has) the default preset when the player made no
     * older choice (legacy false), or the preset that the older choices match: the same window row (Profile.windowRow) and the game's own palette, or a Scene resolution
     * choice of the same mode whose scale lies within MATCH of the preset's (the nearest); Advanced when none matches, so the older choices apply as they are.
     * legacyWindow: Prefs.gameWindow (the game's own choice, else the default); legacyScene: Prefs.sceneRes ("" for the game's own palette); scenes: the profile's
     * scene_scale rows (SceneRes.presets). → a preset id or ADVANCED; "" for a game without presets
     */
    static String current(List<Preset> ps, Profile prof, List<SceneRes.Preset> scenes, String stored, boolean legacy, String legacyWindow, String legacyScene) {
        if (ps == null || ps.isEmpty()) return "";
        if (ADVANCED.equals(stored)) return ADVANCED;
        if (of(ps, stored) != null) return stored;
        if (!legacy) return ps.get(0).id;
        String row = prof != null ? prof.windowRow(legacyWindow) : legacyWindow;
        SceneRes.Preset sc = legacyScene == null || legacyScene.isEmpty() ? null : SceneRes.of(scenes, legacyScene);
        if (sc == null && legacyScene != null && !legacyScene.isEmpty()) return ADVANCED;   // a scene choice the profile no longer has: as before (SceneRes.note says so at start)
        Preset best = null;
        double bestD = Double.MAX_VALUE;
        for (Preset p : ps) {
            String pr = prof != null ? prof.windowRow(p.window) : p.window;
            if (row == null || !row.equals(pr)) continue;
            if (sc == null) { if (!p.overlay()) return p.id; continue; }
            if (!p.overlay() || !p.mode.equals(sc.mode) || Double.isNaN(sc.scale)) continue;
            double d = Math.abs(p.scale() - sc.scale);
            if (d <= MATCH + 1e-9 && d < bestD) { best = p; bestD = d; }
        }
        return best != null ? best.id : ADVANCED;
    }

    /** The window a choice starts the game with: the preset's, else (Advanced) Game resolution's (legacyWindow) */
    static String window(List<Preset> ps, String choice, String legacyWindow) {
        Preset p = of(ps, choice);
        return p != null ? p.window : legacyWindow;
    }

    /** The scene overlay a choice starts the game with, by its name in the pack's scene_res rows: the preset's id for a preset with its own scene, "" for the game's own palette; Advanced: Scene resolution's (legacyScene) */
    static String scene(List<Preset> ps, String choice, String legacyScene) {
        Preset p = of(ps, choice);
        if (p != null) return p.overlay() ? p.id : "";
        return ADVANCED.equals(choice) && legacyScene != null ? legacyScene : "";
    }

    /** The choice's label in Game settings: "Balanced · window 1920x1080 · 3D dynamic, at least about 1280x720", or "Advanced" */
    static String label(List<Preset> ps, String choice) {
        Preset p = of(ps, choice);
        return p != null ? p.label + " · " + p.detail() : "Advanced";
    }
}
