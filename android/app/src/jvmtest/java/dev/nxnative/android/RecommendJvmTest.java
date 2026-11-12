package dev.nxnative.android;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recommend (the recommended settings per phone) with Profile's column 24, on the JVM: parsing, the match rules, later rows winning, the driver rows on the phone's own
 * driver, which installed driver a driver value takes, the values in the launcher's terms and the order a recommended setting's value takes (the game's own, the
 * recommendation, the default). args[0]: the tested phone's readiness facts (honor-pnm-n49.readiness.txt); args[1]: Fire Emblem's column as build.gradle makes it. Prints "ok" or throws
 */
public final class RecommendJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static Map<String, String> facts(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> honor = Readiness.facts(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        check(Recommend.known(honor) && "8".equals(honor.get("driver.id")), "the tested phone's facts: " + honor.get("driver.id"));

        // Parsing: rows as build.gradle writes them; malformed rows and matches on names are skipped
        List<Recommend.Row> rows = Recommend.parse("any|upscaler=fsr1 hold60=on;driver.id=8|upscaler=bilinear;feature.meshShader=0|option.DM_X=on;"
                + "gpu.name=Adreno|upscaler=native;bad;|x=1;driver.id=8|driver=18;driver.id=18|fps_cap=30");
        check(rows.size() == 5, "five rows, the malformed and the name match left out: " + rows);
        check(rows.get(3).driverRow() && !rows.get(0).driverRow(), "a driver row");
        check(Recommend.parse(null).isEmpty() && Recommend.parse("").isEmpty(), "no rows");
        check(Recommend.conditions("driver.name=Turnip") == null && Recommend.conditions("gpu.name=x") == null && Recommend.conditions("driver.info=x") == null
              && Recommend.conditions("driver.custom=x") == null, "names are not facts a match reads");
        check(Recommend.conditions("ext.VK_EXT_mesh_shader=1,limit.maxComputeSharedMemorySize>=49152,gpu.device&0xff000000=0x44000000").size() == 3, "three conditions");
        check(Recommend.conditions("ext.=1") == null && Recommend.conditions("driver.id") == null && Recommend.conditions("driver.id==8") == null, "malformed conditions");

        // Matching
        check(Recommend.matches("any", null) && Recommend.matches("any", facts()), "any matches without facts");
        check(!Recommend.matches("driver.id=8", null) && !Recommend.matches("driver.id=8", facts("unchecked", "x")), "no facts: only any");
        check(Recommend.matches("driver.id=8", honor) && !Recommend.matches("driver.id=18", honor) && Recommend.matches("driver.id!=18", honor), "driver ID");
        check(Recommend.matches("gpu.vendor=0x5143", honor) && Recommend.matches("gpu.vendor=20803", honor), "the vendor ID, hex or decimal");
        check(Recommend.matches("gpu.device&0xff000000=0x44000000", honor) && !Recommend.matches("gpu.device&0xff000000=0x43000000", honor), "a masked device ID");
        check(Recommend.matches("feature.meshShader=1,ext.VK_EXT_mesh_shader=1", honor), "features and extensions it reports");
        check(Recommend.matches("ext.VK_NOT_THERE=0", honor) && !Recommend.matches("ext.VK_NOT_THERE=1", honor), "a fact the driver does not report reads 0");
        check(Recommend.matches("vk.api>=1.3", honor) && Recommend.matches("vk.api<1.5", honor) && !Recommend.matches("vk.api>1.4.295", honor), "dotted versions");
        check(Recommend.matches("limit.maxComputeSharedMemorySize<49152", honor), "a limit");
        check(!Recommend.matches("driver.id=8,feature.meshShader=0", honor), "every condition must hold");

        // Values: later rows win, driver rows apart
        Map<String, String> v = Recommend.values(rows, honor);
        check(v.get("upscaler").equals("bilinear") && v.get("hold60").equals("on") && !v.containsKey("option.DM_X") && !v.containsKey("driver") && !v.containsKey("fps_cap"),
              "Qualcomm's driver: the later row's upscaler, the first row's hold60: " + v);
        Map<String, String> turnip = facts("gpu.vendor", "0x5143", "driver.id", "18");
        v = Recommend.values(rows, turnip);
        check(v.get("upscaler").equals("fsr1") && v.get("fps_cap").equals("30") && v.get("option.DM_X").equals("on"), "Turnip, no mesh shading reported: " + v);
        check(Recommend.values(rows, null).equals(Map.of("upscaler", "fsr1", "hold60", "on")), "no facts yet: the any rows");
        check("18".equals(Recommend.driver(rows, honor)) && Recommend.driver(rows, turnip) == null && Recommend.driver(rows, null) == null, "driver rows read the phone's own driver");

        // Which driver a value takes
        Map<String, Map<String, String>> inst = new LinkedHashMap<>();
        inst.put("a-qcom", facts("gpu.vendor", "0x5143", "driver.id", "8"));
        inst.put("b-turnip", turnip);
        inst.put("c-turnip", turnip);
        inst.put("d-unknown", facts());
        check(Recommend.SYSTEM.equals(Recommend.resolveDriver("system", honor, inst, "")), "system");
        check(Recommend.SYSTEM.equals(Recommend.resolveDriver("8", honor, inst, "a-qcom")), "the system driver reports the ID: the system driver");
        check("b-turnip".equals(Recommend.resolveDriver("18", honor, inst, "")) && "c-turnip".equals(Recommend.resolveDriver("18", honor, inst, "c-turnip")),
              "an installed driver known to report it, the main screen's pick first");
        check(Recommend.resolveDriver("18", honor, Map.of("d-unknown", facts()), "d-unknown") == null && Recommend.resolveDriver("99", honor, inst, "") == null
              && Recommend.resolveDriver(null, honor, inst, "") == null, "none known: the row is left out");

        // Fire Emblem's row (args[1]): Qualcomm's driver before 512.800 (the host's qcom_old) → an installed Turnip; the tested phone's 512.842 is not one
        List<Recommend.Row> fe = Recommend.parse(args[1]);
        check(fe.size() == 1 && fe.get(0).driverRow(), "Fire Emblem's row: " + args[1]);
        check(Recommend.driver(fe, honor) == null, "512.842 (" + honor.get("driver.version") + "): no recommendation");
        Map<String, String> thor = new LinkedHashMap<>(honor);
        thor.put("driver.version", String.format("%#x", (512 << 22) | (676 << 12) | 53));
        check("18".equals(Recommend.driver(fe, thor)), "512.676.53: a Turnip");
        thor.put("driver.version", String.format("%#x", (512 << 22) | (800 << 12) | 77));
        check(Recommend.driver(fe, thor) == null, "512.800.77: none");
        thor.put("driver.version", String.format("%#x", (512 << 22) | (676 << 12))); thor.put("driver.id", "18");
        check(Recommend.driver(fe, thor) == null, "another driver ID: none");

        // What is kept of a readiness answer: the facts a match reads, no names
        String kept = Recommend.keep(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        Map<String, String> k = Readiness.facts(kept);
        check(k.get("driver.id").equals("8") && k.containsKey("gpu.device") && k.containsKey("ext.VK_EXT_mesh_shader") && !k.containsKey("gpu.name") && !k.containsKey("driver.name")
              && !k.containsKey("driver.info"), "kept facts: " + k.keySet());
        check(Recommend.keep("unchecked=no answer").isEmpty() && Recommend.keep(null).isEmpty(), "no real answer: nothing kept");

        // The launcher's terms
        check(Recommend.index(Recommend.UPSCALERS, "sgsr1") == 4 && Recommend.index(Recommend.DLSS_MODES, "fsr3") == 3 && Recommend.index(Recommend.DRS_VALUES, "40") == 5
              && Recommend.index(Recommend.UPSCALERS, null) == -1, "indices");
        check(Recommend.bool("on", false) && !Recommend.bool("off", true) && Recommend.bool(null, true) && !Recommend.bool("x", false), "on / off");
        check(Recommend.cap("60", true) == 0 && Recommend.cap("30", true) == 30 && Recommend.cap("15", true) == null, "a limited game's caps (60 is stored as 0)");
        check(Recommend.cap("own", false) == 0 && Recommend.cap("60", false) == 60 && Recommend.cap("45", false) == null && Recommend.cap(null, false) == null, "the other games' caps");
        check(Recommend.capName(0, true).equals("60") && Recommend.capName(30, true).equals("30") && Recommend.capName(0, false).equals("own") && Recommend.capName(-1, false).equals("unset"),
              "caps in a row's words");

        // The order of a value: the game's own, the recommendation, the default
        check(Recommend.effective(1, 2, 3) == 1 && Recommend.effective(null, 2, 3) == 2 && Recommend.effective(null, null, 3) == 3, "own > recommended > default");
        check(Recommend.source(true, "x").equals("yours") && Recommend.source(false, "x").equals("recommended") && Recommend.source(false, null).equals("default"), "sources");
        check(Recommend.changed(true, "fsr1", "sgsr1") && !Recommend.changed(true, "sgsr1", "sgsr1") && !Recommend.changed(false, "fsr1", "sgsr1") && !Recommend.changed(true, "fsr1", null),
              "the changed marker: the player's own value, other than the recommended one");
        check(Recommend.prefKeys("upscaler", true).equals(List.of("dlss_mode")) && Recommend.prefKeys("upscaler", false).equals(List.of("ndsr_choice2"))
              && Recommend.prefKeys("res_preset", false).equals(Arrays.asList("res_preset", "window", "scene_res")) && Recommend.prefKeys("option.DM_X", false).equals(List.of("opt_DM_X"))
              && Recommend.prefKeys("driver", false).equals(List.of("gpu_driver")) && Recommend.prefKeys("nope", false).isEmpty(), "the keys Back to recommended clears");

        // The log line and Game settings' note
        List<Recommend.State> st = Arrays.asList(new Recommend.State("driver", "GPU driver", "b-turnip", false, "b-turnip"),
                new Recommend.State("upscaler", "Upscaler", "fsr1", true, "sgsr1"), new Recommend.State("drs", "Dynamic resolution", "40", false, null));
        check(Recommend.logLine(st).equals("[nxlaunch] settings: GPU driver b-turnip (recommended), Upscaler fsr1 (yours), Dynamic resolution 40 (default)"), Recommend.logLine(st));
        check(Recommend.line(st, (s, x) -> s.equals("driver") ? "Turnip V39" : "SGSR 1").equals("Turnip V39 · SGSR 1"), Recommend.line(st, (s, x) -> x));
        check(Recommend.line(st.subList(2, 3), (s, x) -> x) == null, "nothing recommended: no card");
        check(st.get(1).changed() && !st.get(0).changed() && st.get(1).source().equals("yours"), "a state");
        check(Recommend.changedCount(st) == 1 && Recommend.changedLine(1).equals("You changed 1 of these.") && Recommend.changedLine(0) == null, "the card's changed line, none at 0");
        check(Recommend.changedCount(st.subList(0, 1)) == 0, "nothing changed");
        check("Recommended".equals(Recommend.tag(st.get(0))) && "Changed".equals(Recommend.tag(st.get(1))) && Recommend.tag(st.get(2)) == null && Recommend.tag(null) == null,
              "the tags: Recommended, Changed, none without a recommendation");
        check("Recommended".equals(Recommend.tag(new Recommend.State("hold60", "Hold 60 fps", "on", true, "on"))), "the player's value is the recommended one: Recommended");

        // No facts kept for the phone's own driver (a fresh install, or a start whose DM_VK_DRIVER line names the system driver): GLES's strings stand in
        // (Recommend.glFacts, kept by Prefs.rememberGlFacts at the launcher's start), so Ocarina of Time's card shows on this Adreno 840 before the first game start
        String gl = Recommend.glFacts("Qualcomm", "OpenGL ES 3.2 V@0842.44 (GIT@718a07726a, Ic95db9d944, 1780021672) (Date:05/29/26)");
        check(gl.equals("gpu.vendor=0x5143\ndriver.id=8\ndriver.version=0x8034a02c\n"), "Qualcomm's own driver from GLES: " + gl);
        check(Recommend.keep(gl).equals(gl), "kept as they are");
        Map<String, String> gf = Readiness.facts(Recommend.keep(gl));
        check(gf.get("driver.version").equals(honor.get("driver.version")) && gf.get("gpu.vendor").equals(honor.get("gpu.vendor")) && gf.get("driver.id").equals(honor.get("driver.id")),
              "the same vendor, driver ID and version the readiness check reports on the tested phone");
        List<Recommend.Row> oot = Recommend.parse(args[2]);
        check(Recommend.values(oot, gf).equals(Map.of("res_preset", "performance", "vegetation", "off")), "Ocarina of Time's values from GLES's facts: " + Recommend.values(oot, gf));
        check("system".equals(Recommend.driver(oot, gf)), "Ocarina of Time's driver from GLES's facts: " + Recommend.driver(oot, gf));
        Map<String, String> thorOld = new LinkedHashMap<>(gf);
        thorOld.put("driver.version", "0x802a4035");
        check(Recommend.driver(oot, thorOld) == null, "Ocarina of Time: Qualcomm 0x802a4035 (Adreno 740): no system driver");
        thorOld.put("driver.version", "0x80320040");
        check("system".equals(Recommend.driver(oot, thorOld)), "Ocarina of Time: Qualcomm 0x80320040: system driver");
        // top bit set: the compare is unsigned 32-bit, never a signed int
        for (String[] c : new String[][] {{"0x8031ffff", "n"}, {"0x80320000", "y"}, {"0xffffffff", "y"}, {"0x7fffffff", "n"}, {"0x0", "n"}}) {
            thorOld.put("driver.version", c[0]);
            check("y".equals(c[1]) == "system".equals(Recommend.driver(oot, thorOld)), "Ocarina of Time gate at " + c[0] + ": " + Recommend.driver(oot, thorOld));
        }
        check(Recommend.values(oot, Readiness.facts("")).equals(Map.of("res_preset", "performance", "vegetation", "off")) && Recommend.driver(oot, Readiness.facts("")) == null, "no facts at all: the any row (Performance, Full vegetation off) and no driver");
        check(Recommend.glFacts("Qualcomm", "OpenGL ES 3.2 Mesa 25.1.0").equals("gpu.vendor=0x5143\ndriver.id=13\n"), "Mesa on an Adreno");
        check(Recommend.glFacts("ARM", "OpenGL ES 3.2 v1.r44p0-01eac0").equals("gpu.vendor=0x13b5\ndriver.id=9\n"), "Arm's own driver");
        check(Recommend.glFacts("Google", "x").isEmpty() && Recommend.glFacts(null, null).isEmpty() && Recommend.keep("").isEmpty(), "an unknown vendor: nothing");
        // The start-up check's driver choice: an explicit DM_VK_DRIVER=system line probes the system driver, so its facts are kept
        check(Recommend.SYSTEM.equals(Recommend.probedChoice("DM_VK_DRIVER=system", null, Recommend.SYSTEM)), "system line, system choice");
        check(Recommend.SYSTEM.equals(Recommend.probedChoice("DM_VK_DRIVER=system", "DM_VK_DRIVER=/d/turnip", "turnip")) && Recommend.SYSTEM.equals(Recommend.probedChoice("DM_VK_DRIVER=", null, Recommend.SYSTEM)),
              "an explicit system line wins over the launcher's driver");
        check(Recommend.SYSTEM.equals(Recommend.probedChoice(null, null, Recommend.SYSTEM)) && "turnip".equals(Recommend.probedChoice("DM_VK_DRIVER=/d/turnip", "DM_VK_DRIVER=/d/turnip", "turnip")),
              "the launcher's own choice");
        check(Recommend.probedChoice("DM_VK_DRIVER=/d/other", null, Recommend.SYSTEM) == null, "another driver from a user line: its facts are not kept");

        // Profile's column 24 (build.gradle recommendOf)
        String[] c = new String[25];
        Arrays.fill(c, "");
        c[0] = "04007EF01F8AE000"; c[1] = "x"; c[2] = "0"; c[3] = "u"; c[4] = "131072 main=aa"; c[24] = args[1];
        check(Profile.parse(String.join("\t", c)).get(0).recommend.equals(args[1]), "profile column 24");
        check(Profile.parse("04007EF01F8AE000\tx\t0\tu\t131072 main=aa").get(0).recommend.isEmpty(), "an older line has none");
        System.out.println("ok");
    }
}
