package dev.nxnative.android;

import java.io.File;
import java.util.List;
import java.util.Map;

/** ResPresets (Resolution presets) with Profile's columns 9, 21 and 22 and SceneRes, on the JVM: parsing, the labels, the migration of older choices, and what a start takes. args[0]: an empty folder. Prints "ok" or throws */
public final class ResPresetsJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static final String SCALE = "scale:original=1/console,2560x1440=1/console,1920x1080=0/handheld,1280x720=2/handheld";
    static final String SCENES = "720p|Fixed 0.6133|0.6133|fixed;720p-min|At least 0.6133 (dynamic)|0.6133|floor;540p|Fixed 0.46875|0.46875|fixed;near|Fixed 0.7|0.7|fixed;360p|Fixed 0.3125|0.3125|fixed;old|Old line";
    static final String PRESETS = "quality|Quality|1920x1080|default|0|0|0|open;balanced|Balanced|1920x1080|floor|720|1920|1080|open;"
            + "performance|Performance|1280x720|fixed|540|1280|720|open;max-performance|Max performance|1280x720|fixed|360|1280|720|measured;"
            + "bad|x|1280x720|fixed|540|0|0|open;bad2|x|1280x720|sideways|540|1280|720|open;bad3|x|1280x720|fixed|900|1280|720|open;short|x|1280x720";

    /** A Profile line as build.gradle writes it, with the resolution, scenes and presets columns */
    static Profile profile(String resolution, String scenes, String presets) {
        String[] c = new String[23];
        java.util.Arrays.fill(c, "");
        c[0] = "0400486024DEC000"; c[1] = "x"; c[2] = "0"; c[3] = "u"; c[4] = "131072 main=aa";
        c[9] = resolution; c[21] = scenes; c[22] = presets;
        return Profile.parse(String.join("\t", c)).get(0);
    }

    public static void main(String[] args) throws Exception {
        check(ResPresets.parse(null).isEmpty() && ResPresets.parse("").isEmpty(), "empty");
        List<ResPresets.Preset> ps = ResPresets.parse(PRESETS);
        check(ps.size() == 4, "four presets, the malformed rows skipped: " + ps);
        ResPresets.Preset q = ps.get(0), b = ps.get(1), pf = ps.get(2), mp = ps.get(3);
        check(!q.overlay() && q.scale() == 0, "Quality: the game's own palette");
        check(b.overlay() && Math.abs(b.scale() - 720.0 / 1080) < 1e-9, "Balanced's scale: " + b.scale());
        check(pf.scale() == 0.75 && mp.scale() == 0.5, "the fixed scales");
        check(q.detail().equals("window 1920x1080 · 3D the game's own dynamic resolution"), q.detail());
        check(b.detail().equals("window 1920x1080 · 3D dynamic, at least about 1280x720"), b.detail());
        check(pf.detail().equals("window 1280x720 · 3D about 960x540"), pf.detail());
        check(mp.detail().equals("window 1280x720 · 3D 640x360"), "a measured output says no about: " + mp.detail());
        check(ResPresets.label(ps, "performance").equals("Performance · window 1280x720 · 3D about 960x540"), ResPresets.label(ps, "performance"));
        check(ResPresets.label(ps, ResPresets.ADVANCED).equals("Advanced"), "Advanced's label");
        check(new ResPresets.Preset("o", "O", "original", "default", 0, 0, 0, false).detail().startsWith("window the game's own ·"), "the original window");

        // Profile's columns 21 and 22 (build.gradle sceneOf, presetsOf) and the window rows
        Profile p = profile(SCALE, SCENES, PRESETS);
        check(ResPresets.presets(p).size() == 4 && ResPresets.presets(null).isEmpty(), "profile column 22: " + p.presets);
        check(ResPresets.presets(Profile.parse("0400486024DEC000\tx\t0\tu\t131072 main=aa").get(0)).isEmpty(), "an older line has none");
        List<SceneRes.Preset> scenes = SceneRes.presets(p);
        check(scenes.size() == 6 && scenes.get(1).mode.equals("floor") && scenes.get(1).scale == 0.6133 && Double.isNaN(scenes.get(5).scale), "scene rows with scale and mode: " + scenes);
        check("1280x720".equals(p.windowRow("960x540")) && "1920x1080".equals(p.windowRow("1920x1080")) && "original".equals(p.windowRow("original")), "window rows");
        check("1280x720".equals(profile("window", "", "").windowRow("1280x720")), "a window_fn game: the value itself");

        // The choice in force (ResPresets.current)
        check(ResPresets.current(ResPresets.parse(""), p, scenes, "", true, "1280x720", "").isEmpty(), "a game without presets");
        check(ResPresets.current(ps, p, scenes, "", false, "1920x1080", "").equals("quality"), "nothing chosen: the default, today's start");
        check(ResPresets.current(ps, p, scenes, "advanced", false, "1920x1080", "").equals(ResPresets.ADVANCED), "Advanced stored");
        check(ResPresets.current(ps, p, scenes, "performance", true, "1920x1080", "540p").equals("performance"), "a stored preset wins over older choices");
        check(ResPresets.current(ps, p, scenes, "gone", false, "1920x1080", "").equals("quality"), "a preset the profile lost: the default");
        check(ResPresets.current(ps, p, scenes, "", true, "1920x1080", "").equals("quality"), "Phone 1080p, the game's palette: Quality");
        check(ResPresets.current(ps, p, scenes, "", true, "1280x720", "").equals(ResPresets.ADVANCED), "Phone 720p, the game's palette: no such preset, Advanced keeps it");
        check(ResPresets.current(ps, p, scenes, "", true, "1920x1080", "720p-min").equals("balanced"), "1080p, at least 0.6133: Balanced (0.6667, the nearest floor)");
        check(ResPresets.current(ps, p, scenes, "", true, "1920x1080", "720p").equals(ResPresets.ADVANCED), "1080p, fixed 0.6133: no fixed preset at 1080p");
        check(ResPresets.current(ps, p, scenes, "", true, "960x540", "near").equals("performance"), "Phone 540p (the 720p row), fixed 0.7: Performance (0.75)");
        check(ResPresets.current(ps, p, scenes, "", true, "1280x720", "540p").equals("max-performance"), "720p, fixed 0.46875: Max performance (0.5, within 0.1)");
        check(ResPresets.current(ps, p, scenes, "", true, "1280x720", "360p").equals(ResPresets.ADVANCED), "720p, fixed 0.3125: too far from 0.75 and 0.5");
        check(ResPresets.current(ps, p, scenes, "", true, "1920x1080", "gone").equals(ResPresets.ADVANCED), "a scene the profile lost: as before");
        check(ResPresets.current(ps, p, scenes, "", true, "1920x1080", "old").equals(ResPresets.ADVANCED), "a scene row without a scale matches nothing");
        check(ResPresets.current(ps, p, scenes, "", true, "original", "").equals(ResPresets.ADVANCED), "Original: no preset");

        // What a start takes
        check(ResPresets.window(ps, "performance", "1920x1080").equals("1280x720") && ResPresets.window(ps, ResPresets.ADVANCED, "2560x1440").equals("2560x1440"), "window");
        check(ResPresets.scene(ps, "performance", "540p").equals("performance") && ResPresets.scene(ps, "quality", "540p").isEmpty(), "a preset's scene: its id, or the palette");
        check(ResPresets.scene(ps, ResPresets.ADVANCED, "540p").equals("540p") && ResPresets.scene(ps, ResPresets.ADVANCED, null).isEmpty(), "Advanced: 3D scene's choice");
        check(p.resolutionEnv(ResPresets.window(ps, "max-performance", "1920x1080")).equals(List.of("DM_GRAPHICS_SCALE=2", "DM_OPMODE=0")), "the preset's window as graphics scale and mode");

        // The preset's overlay in a pack (scene_res rows named by the preset's id)
        File pack = new File(args[0], "pack");
        check(new File(pack, "scene_res/performance/Pack").mkdirs(), "mkdirs");
        Map<String, String> rows = Map.of("performance", "scene_res/performance", "balanced", "scene_res/balanced", "evil", "../x");
        File ov = SceneRes.overlay(rows, pack, "performance");
        check(ov != null && ov.equals(new File(pack, "scene_res/performance")), "the preset's folder: " + ov);
        check(SceneRes.overlay(rows, pack, "balanced") == null && SceneRes.overlay(rows, pack, "evil") == null && SceneRes.overlay(rows, pack, "") == null
                && SceneRes.overlay(Map.of(), pack, "performance") == null, "a gone folder, outside the pack, none, an older pack");
        check(SceneRes.note("balanced", "Balanced", null).startsWith("[RED] ") && SceneRes.note("balanced", "Balanced", null).contains("Prepare again"), "a missing overlay is red");
        check(SceneRes.note("performance", "Performance", ov).equals("[nxlaunch] scene resolution performance (Performance): DM_ROMFS_OVERLAY " + ov.getPath()), "the note");
        System.out.println("ok");
    }
}
