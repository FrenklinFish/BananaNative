package dev.nxnative.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** SceneRes (Scene resolution) with Profile's column and Pack's scene_res rows, on the JVM. args[0]: an empty folder. Prints "ok" or throws */
public final class SceneResJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) throws Exception {
        check(SceneRes.parse(null).isEmpty() && SceneRes.parse("").isEmpty() && SceneRes.parse("  ").isEmpty(), "empty");
        List<SceneRes.Preset> ps = SceneRes.parse("720p|720p (about 1275x717);720p-min|Dynamic, at least 720p;540p|;bad/name|x;|nameless");
        check(ps.size() == 3, "three presets: " + ps);
        check(ps.get(0).name.equals("720p") && ps.get(0).label.equals("720p (about 1275x717)"), "first");
        check(ps.get(2).name.equals("540p") && ps.get(2).label.equals("540p"), "an empty label is the name");
        check(SceneRes.of(ps, "") == null && SceneRes.of(ps, null) == null && SceneRes.of(ps, "gone") == null, "Game default and unknown choices");
        check(SceneRes.of(ps, "720p-min") == ps.get(1), "a choice");
        check(SceneRes.label(ps, "").equals("Game default") && SceneRes.label(ps, "720p").equals("720p (about 1275x717)"), "labels");

        // Profile's column 21 (build.gradle sceneOf)
        String line = "0400486024DEC000\tThe Legend of Zelda: Ocarina of Time\t22852\tabcdef012345\t131072 main=aa" + "\t".repeat(16) + "\t720p|About 720p;360p|About 360p";
        Profile p = Profile.parse(line).get(0);
        check(SceneRes.presets(p).size() == 2 && SceneRes.presets(p).get(1).name.equals("360p"), "profile column: " + p.scenes);
        check(SceneRes.presets(Profile.parse("0400486024DEC000\tx\t0\tu\t131072 main=aa").get(0)).isEmpty(), "an older line has none");
        check(SceneRes.presets(null).isEmpty(), "no profile");

        // A pack with scene_res rows (pack_android.py) and its folders
        File pack = new File(args[0], "pack");
        check(new File(pack, "scene_res/720p/Pack").mkdirs(), "mkdirs");
        Files.write(new File(pack, "main.patched.bin").toPath(), new byte[] {0});
        Files.write(new File(pack, "pack.tsv").toPath(), ("format\t1\ntitle_id\t0400486024DEC000\nname\tx\nversion\t131072\ntarget\tandroid\nmodules\tmain\nromfs\tx.nup:update/131072/romfs\n"
                + "scene_res\t720p\tscene_res/720p\nscene_res\t360p\tscene_res/360p\nscene_res\tevil\t../outside\n").getBytes(StandardCharsets.UTF_8));
        Pack pk = Pack.read(pack);
        check(pk.scenes.equals(Map.of("720p", "scene_res/720p", "360p", "scene_res/360p", "evil", "../outside")), "pack rows: " + pk.scenes);
        List<SceneRes.Preset> all = SceneRes.parse("720p|About 720p;360p|About 360p;540p|About 540p;evil|x");
        File ov = SceneRes.overlay(all, pk.scenes, pack, "720p");
        check(ov != null && ov.equals(new File(pack, "scene_res/720p")), "the overlay folder: " + ov);
        check(SceneRes.envLine(ov).equals("DM_ROMFS_OVERLAY=" + new File(pack, "scene_res/720p").getPath()), "env line");
        check(SceneRes.overlay(all, pk.scenes, pack, "360p") == null, "a row whose folder is gone");
        check(SceneRes.overlay(all, pk.scenes, pack, "540p") == null, "a preset the pack has no row for (made before the option)");
        check(SceneRes.overlay(all, pk.scenes, pack, "evil") == null, "never outside the pack");
        check(SceneRes.overlay(all, pk.scenes, pack, "") == null, "Game default");
        check(SceneRes.overlay(ps, pk.scenes, pack, "720p") != null && SceneRes.overlay(ps, Map.of(), pack, "720p") == null, "older packs have no rows");
        check(SceneRes.envLine(null) == null, "no line without a folder");
        check(SceneRes.note(all, "", null) == null, "no note for Game default");
        check(SceneRes.note(all, "720p", ov).equals("[nxlaunch] scene resolution 720p (About 720p): DM_ROMFS_OVERLAY " + ov.getPath()), "note: " + SceneRes.note(all, "720p", ov));
        check(SceneRes.note(all, "540p", null).startsWith("[RED] ") && SceneRes.note(all, "540p", null).contains("Prepare again"), "a missing overlay is red");
        check(SceneRes.note(all, "gone", null).startsWith("[RED] "), "an unknown choice is red");

        // scene_arg rows (Star Fox): game arguments, nothing in the pack
        List<SceneRes.Preset> ar = SceneRes.parse("s075|75%: 3D up to 1440x810|0.75|arg|-render_resolution_scale_console=0.75 -render_resolution_scale_handheld=0.75;"
                + "nodynres|No dynamic resolution|-|arg|-no_render_dynres_enabled;empty|x|1|arg|;720p|About 720p|0.6133|fixed");
        check(ar.size() == 3 && ar.get(0).arg() && ar.get(1).arg() && !ar.get(2).arg(), "an arg row without arguments is skipped: " + ar);
        check(ar.get(0).scale == 0.75 && Double.isNaN(ar.get(1).scale) && ar.get(2).args.isEmpty(), "scales and arguments");
        check(SceneRes.argLine(ar, "s075").equals("DM_GAME_ARGS_ADD=-render_resolution_scale_console=0.75 -render_resolution_scale_handheld=0.75"), "arg line: " + SceneRes.argLine(ar, "s075"));
        check(SceneRes.argLine(ar, "nodynres").equals("DM_GAME_ARGS_ADD=-no_render_dynres_enabled"), "a choice without a scale");
        check(SceneRes.argLine(ar, "") == null && SceneRes.argLine(ar, "gone") == null && SceneRes.argLine(ar, "720p") == null, "Game default, unknown and overlay choices add no arguments");
        check(SceneRes.overlay(ar, Map.of("s075", "scene_res/720p"), pack, "s075") == null, "an arg choice never takes an overlay folder");
        check(SceneRes.note(ar, "s075", null).equals("[nxlaunch] scene resolution s075 (75%: 3D up to 1440x810): DM_GAME_ARGS_ADD -render_resolution_scale_console=0.75 -render_resolution_scale_handheld=0.75"),
                "arg note: " + SceneRes.note(ar, "s075", null));
        check(SceneRes.label(ar, "nodynres").equals("No dynamic resolution"), "arg label");
        System.out.println("ok");
    }
}
