package dev.nxnative.android;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Profile.parseOptions and Profile.buildOptionsEnv on the JVM. Prints "ok" or throws */
public final class ProfileOptionsJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) throws Exception {
        check(Profile.parseOptions(null).isEmpty(), "null -> empty");
        check(Profile.parseOptions("").isEmpty(), "empty -> empty");
        check(Profile.parseOptions("   ").isEmpty(), "whitespace -> empty");

        String single = "DM_OOT_MAX_RESOLUTION|Max resolution|Force full 3D resolution|restart|DM_OOT_TEXTURE_POOL_X2|OOT_PATCHES";
        List<Profile.Option> s = Profile.parseOptions(single);
        check(s.size() == 1, "single option count: " + s.size());
        Profile.Option opt0 = s.get(0);
        check(opt0.env.equals("DM_OOT_MAX_RESOLUTION"), "env: " + opt0.env);
        check(opt0.label.equals("Max resolution"), "label: " + opt0.label);
        check(opt0.description.equals("Force full 3D resolution"), "desc: " + opt0.description);
        check(!opt0.live, "restart is not live");
        check(opt0.implies.equals("DM_OOT_TEXTURE_POOL_X2"), "implies: " + opt0.implies);
        check(opt0.needs.equals("OOT_PATCHES"), "needs: " + opt0.needs);
        check(opt0.hasImplies(), "hasImplies");
        check(opt0.hasNeeds(), "hasNeeds");
        check(opt0.envLine().equals("DM_OOT_MAX_RESOLUTION=1"), "env line: " + opt0.envLine());

        String backwardCompat = "DM_OLD|Old opt|No extra fields|restart";
        List<Profile.Option> bc = Profile.parseOptions(backwardCompat);
        check(bc.size() == 1 && bc.get(0).implies.isEmpty() && bc.get(0).needs.isEmpty(), "backward compat parsed");

        String liveStr = "DM_LIVE_TEST|Live switch|A live setting|live";
        List<Profile.Option> l = Profile.parseOptions(liveStr);
        check(l.size() == 1 && l.get(0).live, "live parsed as true");

        String multi = "DM_OOT_MAX_RESOLUTION|Max resolution|Force full 3D resolution|restart|DM_OOT_TEXTURE_POOL_X2|OOT_PATCHES;"
                + "DM_OOT_TEXTURE_POOL_X2|Texture pool ×2|Double graphics allocation|restart||OOT_PATCHES;"
                + "DM_OOT_FPS_UNLOCK|Unlocked frame rate|Update step follows elapsed time|restart||OOT_PATCHES";
        List<Profile.Option> m = Profile.parseOptions(multi);
        check(m.size() == 3, "three options parsed: " + m.size());
        check(m.get(1).env.equals("DM_OOT_TEXTURE_POOL_X2") && m.get(1).label.equals("Texture pool ×2"), "second opt");
        check(m.get(1).needs.equals("OOT_PATCHES") && m.get(1).implies.isEmpty(), "second opt needs OOT_PATCHES");
        check(m.get(2).env.equals("DM_OOT_FPS_UNLOCK") && m.get(2).label.equals("Unlocked frame rate"), "third opt");
        check(m.get(2).needs.equals("OOT_PATCHES"), "third opt needs OOT_PATCHES");

        // Profile.findImpliedBy
        Set<String> testEnabled = new HashSet<>();
        check(Profile.findImpliedBy(m, m.get(1), testEnabled::contains) == null, "not implied when max res off");
        testEnabled.add("DM_OOT_MAX_RESOLUTION");
        Profile.Option source = Profile.findImpliedBy(m, m.get(1), testEnabled::contains);
        check(source != null && source.env.equals("DM_OOT_MAX_RESOLUTION"), "implied by max res when max res on");

        // buildOptionsEnv (verifies that implied options are included even if not explicitly in predicate)
        Set<String> enabled = new HashSet<>();
        check(Profile.buildOptionsEnv(m, enabled::contains).isEmpty(), "none enabled -> empty env");
        enabled.add("DM_OOT_MAX_RESOLUTION");
        enabled.add("DM_OOT_FPS_UNLOCK");
        // DM_OOT_TEXTURE_POOL_X2 is not in enabled, but is implied by DM_OOT_MAX_RESOLUTION!
        List<String> env = Profile.buildOptionsEnv(m, enabled::contains);
        check(env.equals(List.of("DM_OOT_MAX_RESOLUTION=1", "DM_OOT_TEXTURE_POOL_X2=1", "DM_OOT_FPS_UNLOCK=1")), "selected options env: " + env);

        // Profile.parse integration
        String line = "0400486024DEC000\tThe Legend of Zelda: Ocarina of Time\t22852\tabcdef012345\t131072 main=aa\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t1\t" + multi;
        List<Profile> profiles = Profile.parse(line);
        check(profiles.size() == 1, "profile parsed");
        Profile p = profiles.get(0);
        check(p.vegetationDetail, "vegetationDetail kept");
        check(p.options.size() == 3, "options count: " + p.options.size());
        check(p.optionsEnv(enabled::contains).equals(env), "optionsEnv");

        // Profile.parse without option column
        String oldLine = "0400486024DEC000\tThe Legend of Zelda: Ocarina of Time\t22852\tabcdef012345\t131072 main=aa\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t1";
        Profile pOld = Profile.parse(oldLine).get(0);
        check(pOld.options.isEmpty(), "old line has empty options");

        // Profile.hasFixupMarker & isSupported
        check(!Profile.hasFixupMarker(null, "OOT_PATCHES"), "hasFixupMarker null");
        check(!Profile.hasFixupMarker(null, null), "hasFixupMarker null marker");
        java.io.File nonExistent = new java.io.File("does_not_exist_pack_folder");
        check(!Profile.hasFixupMarker(nonExistent, "OOT_PATCHES"), "hasFixupMarker non-existent");
        java.io.File tmpDir = java.nio.file.Files.createTempDirectory("pack_test").toFile();
        try {
            check(!Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker without main.fixups.tsv");
            java.io.File fixups = new java.io.File(tmpDir, "main.fixups.tsv");
            java.nio.file.Files.writeString(fixups.toPath(), "# TARGET_ANDROID 1\n# FPS_STATE_OFF 0x14000\n");
            check(!Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker without OOT_PATCHES row");
            java.nio.file.Files.writeString(fixups.toPath(), "# TARGET_ANDROID 1\n# OOT_PATCHES 0\n");
            check(!Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker with OOT_PATCHES 0");
            java.nio.file.Files.writeString(fixups.toPath(), "# TARGET_ANDROID 1\n# OOT_PATCHES 1\n");
            check(Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker with OOT_PATCHES 1");
            java.nio.file.Files.writeString(fixups.toPath(), "# TARGET_ANDROID 1\n# OOT_PATCHES\t1\n");
            check(Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker with OOT_PATCHES tab 1");

            // isSupported
            Profile.Option optWithNeeds = new Profile.Option("DM_A", "A", "Desc", false, "", "OOT_PATCHES");
            check(optWithNeeds.isSupported(tmpDir), "optWithNeeds supported when marker present");
            Profile.Option optOtherNeeds = new Profile.Option("DM_B", "B", "Desc", false, "", "NON_EXISTENT_MARKER");
            check(!optOtherNeeds.isSupported(tmpDir), "optOtherNeeds not supported when marker missing");
            Profile.Option optNoNeeds = new Profile.Option("DM_C", "C", "Desc", false, "", "");
            check(optNoNeeds.isSupported(tmpDir), "optNoNeeds supported with packDir");
            check(optNoNeeds.isSupported(null), "optNoNeeds supported with null packDir");
        } finally {
            new java.io.File(tmpDir, "main.fixups.tsv").delete();
            tmpDir.delete();
        }

        System.out.println("ok");
    }
}
