package dev.nxnative.android;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The 3D scene (Game settings › Resolution's presets, ResPresets, or Advanced › 3D scene): the scene of a game whose profile has scene_scale_palette rows drawn at a scale, by a RomFS overlay of the
 * game's own dynamic resolution palette that the pack step made from the player's files (dm/tools/scene_res.py; pack.tsv "scene_res &lt;name&gt; &lt;folder&gt;" rows,
 * Pack.scenes: one per scene_scale row and per res_preset with a scene of its own). The launcher passes the chosen overlay's folder as DM_ROMFS_OVERLAY (dm/host/hle_fs.c reads it before the RomFS). Off ("" = Game default) unless the player
 * picks one; a preset the pack has no folder for (a pack made before the option, or a palette the pack step refused) sets nothing and asks for Prepare again.
 * A game whose engine takes its settings as arguments has scene_arg rows instead (mode "arg"): the choice passes its arguments as DM_GAME_ARGS_ADD (dm/host/main.c appends them
 * to the game's arguments), with nothing made at Prepare (Star Fox: -render_resolution_scale_console=0.75).
 * No Android classes: the JVM test runs it on the Mac.
 */
final class SceneRes {
    private SceneRes() {}

    /** The env var the overlay goes in */
    static final String ENV = "DM_ROMFS_OVERLAY";
    /** The env var a scene_arg choice's arguments go in */
    static final String ARGS_ENV = "DM_GAME_ARGS_ADD";

    /**
     * One scene_scale row: its name (the folder and the stored choice), its label, and its scale and mode (NaN and "" from a line without them); or one scene_arg row
     * (mode "arg"): the game arguments it adds (scale NaN for a choice without one)
     */
    static final class Preset {
        final String name, label, mode, args;
        final double scale;
        Preset(String name, String label) { this(name, label, Double.NaN, ""); }
        Preset(String name, String label, double scale, String mode) { this(name, label, scale, mode, ""); }
        Preset(String name, String label, double scale, String mode, String args) { this.name = name; this.label = label; this.scale = scale; this.mode = mode; this.args = args; }
        /** Whether the choice is game arguments (scene_arg) rather than an overlay the pack step made */
        boolean arg() { return mode.equals("arg"); }
        @Override public String toString() { return name + "|" + label; }
    }

    /**
     * The profile's presets as build.gradle sceneOf writes them: "name|label|scale|mode", or "name|label|scale|arg|arguments" for a scene_arg row (a row of mode arg without
     * arguments is skipped), ';' between them (empty: the game has no Scene resolution)
     */
    static List<Preset> parse(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<Preset> out = new ArrayList<>();
        for (String r : text.split(";")) {
            String[] f = r.trim().split("\\|", -1);
            if (f.length < 1 || f[0].trim().isEmpty() || !f[0].trim().matches("[A-Za-z0-9_-]+")) continue;
            String name = f[0].trim();
            double scale = Double.NaN;
            if (f.length > 2) try { scale = Double.parseDouble(f[2].trim()); } catch (NumberFormatException e) { scale = Double.NaN; }
            String mode = f.length > 3 ? f[3].trim() : "", args = f.length > 4 ? f[4].trim() : "";
            if (mode.equals("arg") && !args.startsWith("-")) continue;
            out.add(new Preset(name, f.length > 1 && !f[1].trim().isEmpty() ? f[1].trim() : name, scale, mode, mode.equals("arg") ? args : ""));
        }
        return Collections.unmodifiableList(out);
    }

    /** A profile's presets (Profile.scenes); empty without the profile */
    static List<Preset> presets(Profile p) { return p == null ? Collections.emptyList() : parse(p.scenes); }

    /** The preset of a stored choice; null for Game default ("" or null) or a name the profile no longer has */
    static Preset of(List<Preset> presets, String choice) {
        if (presets == null || choice == null || choice.isEmpty()) return null;
        for (Preset p : presets) if (p.name.equals(choice)) return p;
        return null;
    }

    /**
     * The overlay folder of a choice in a pack (its scene_res rows, name → folder relative to the pack): null for Game default, a scene_arg choice, a choice the profile does not offer,
     * a pack without the row, or a row whose folder is gone
     */
    static File overlay(List<Preset> presets, Map<String, String> packScenes, File packDir, String choice) {
        Preset p = of(presets, choice);
        return p == null || p.arg() ? null : overlay(packScenes, packDir, p.name);
    }

    /** The environment line of a scene_arg choice ("DM_GAME_ARGS_ADD=-a=1 -b=1"); null for Game default, a choice the profile does not offer, or an overlay choice */
    static String argLine(List<Preset> presets, String choice) {
        Preset p = of(presets, choice);
        return p == null || !p.arg() ? null : ARGS_ENV + "=" + p.args;
    }

    /** The overlay folder of a name in a pack's scene_res rows (a scene_scale row's or a res_preset's): null without the row, a row whose folder is gone, or one outside the pack */
    static File overlay(Map<String, String> packScenes, File packDir, String name) {
        if (name == null || name.isEmpty() || packScenes == null || packDir == null) return null;
        String rel = packScenes.get(name);
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("..")) return null;
        File d = new File(packDir, rel);
        return d.isDirectory() ? d : null;
    }

    /** The environment line for the overlay folder, null without one */
    static String envLine(File overlay) { return overlay == null ? null : ENV + "=" + overlay.getPath(); }

    /** The log line about the choice at start: what the host gets, or why it gets nothing */
    static String note(List<Preset> presets, String choice, File overlay) {
        Preset p = of(presets, choice);
        if (p == null) return choice == null || choice.isEmpty() ? null : "[RED] [nxlaunch] scene resolution " + choice + ": not a preset of this game's profile, the game's own palette";
        if (p.arg()) return "[nxlaunch] scene resolution " + p.name + " (" + p.label + "): " + ARGS_ENV + " " + p.args;
        return note(p.name, p.label, overlay);
    }

    /** The log line about an overlay the start wants (name and label: a scene_scale row's or a res_preset's) */
    static String note(String name, String label, File overlay) {
        if (overlay == null) return "[RED] [nxlaunch] scene resolution " + name + " (" + label + "): the pack has no overlay for it (Prepare again), the game's own palette";
        return "[nxlaunch] scene resolution " + name + " (" + label + "): " + ENV + " " + overlay.getPath();
    }

    /** The choice's label in Game settings: the preset's, "Game default" for none */
    static String label(List<Preset> presets, String choice) {
        Preset p = of(presets, choice);
        return p == null ? "Game default" : p.label;
    }
}
