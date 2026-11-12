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
 * No Android classes: the JVM test runs it on the Mac.
 */
final class SceneRes {
    private SceneRes() {}

    /** The env var the overlay goes in */
    static final String ENV = "DM_ROMFS_OVERLAY";

    /** One scene_scale row: its name (the folder and the stored choice), its label, and its scale and mode (NaN and "" from a line without them) */
    static final class Preset {
        final String name, label, mode;
        final double scale;
        Preset(String name, String label) { this(name, label, Double.NaN, ""); }
        Preset(String name, String label, double scale, String mode) { this.name = name; this.label = label; this.scale = scale; this.mode = mode; }
        @Override public String toString() { return name + "|" + label; }
    }

    /** The profile's presets as build.gradle sceneOf writes them: "name|label|scale|mode", ';' between them (empty: the game has no Scene resolution) */
    static List<Preset> parse(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<Preset> out = new ArrayList<>();
        for (String r : text.split(";")) {
            String[] f = r.trim().split("\\|", -1);
            if (f.length < 1 || f[0].trim().isEmpty() || !f[0].trim().matches("[A-Za-z0-9_-]+")) continue;
            String name = f[0].trim();
            double scale = Double.NaN;
            if (f.length > 2) try { scale = Double.parseDouble(f[2].trim()); } catch (NumberFormatException e) { scale = Double.NaN; }
            out.add(new Preset(name, f.length > 1 && !f[1].trim().isEmpty() ? f[1].trim() : name, scale, f.length > 3 ? f[3].trim() : ""));
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
     * The overlay folder of a choice in a pack (its scene_res rows, name → folder relative to the pack): null for Game default, a choice the profile does not offer,
     * a pack without the row, or a row whose folder is gone
     */
    static File overlay(List<Preset> presets, Map<String, String> packScenes, File packDir, String choice) {
        Preset p = of(presets, choice);
        return p == null ? null : overlay(packScenes, packDir, p.name);
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
