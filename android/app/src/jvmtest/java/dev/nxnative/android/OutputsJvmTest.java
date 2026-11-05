package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Outputs on the JVM: a step's outputs written in place in their folder count as complete only once Outputs.finish wrote the marker, and a new run, a stop
 * at any point before the marker, or a delete never leaves them accepted. {@code <empty folder>}: prints "ok" lines, exits 1 on the first failed check.
 * Run by dm/tools/tests/test_android_outputs.py.
 */
public final class OutputsJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
        System.out.println("ok " + what);
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** What the split writes at its end: the package folder and the tables */
    private static void tool(File split) throws IOException {
        write(new File(split, "pkg/vs_0.bin"), "vs");
        write(new File(split, "pkg/ps_0.bin"), "ps");
        for (String t : new String[] {"shader_map.tsv", "shader_facts.tsv", "shader_pairs.tsv", "pso_pairs.tsv"}) write(new File(split, t), "# " + t + "\n");
    }

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]), split = new File(root, "split");

        Outputs.begin(split);
        check(split.isDirectory() && !Outputs.complete(split), "begin makes the folder, not complete");
        tool(split);
        check(!Outputs.complete(split), "the tool's outputs alone are not complete (a kill before finish)");
        Outputs.finish(split);
        check(Outputs.complete(split) && new File(split, "shader_map.tsv").isFile() && new File(split, "pkg/ps_0.bin").isFile(), "finish marks it complete, outputs in place");
        check(!new File(split, Outputs.MARKER + ".tmp").exists(), "no temporary marker left");

        // a rerun of the step: the marker goes first, the old outputs with it, then a stop before finish
        Outputs.begin(split);
        check(!Outputs.complete(split) && split.isDirectory() && !new File(split, "pkg").exists(), "begin on a complete folder empties it, marker first");
        write(new File(split, "shader_map.tsv"), "# cut short");
        check(!Outputs.complete(split), "a stopped rerun is not complete");

        // a temporary marker a kill left behind is not the marker
        write(new File(split, Outputs.MARKER + ".tmp"), "complete\n");
        check(!Outputs.complete(split), "a temporary marker is not complete");
        tool(split);
        Outputs.finish(split);
        check(Outputs.complete(split), "finish over a temporary marker");

        // the job's end and a refused profile check delete it
        Outputs.delete(split);
        check(!split.exists() && !Outputs.complete(split), "delete removes the folder and its marker");

        // an older build's split/ (made by renaming split.tmp/, no marker) is not taken: the split runs again
        File old = new File(root, "old-split");
        tool(old);
        check(!Outputs.complete(old), "a folder without the marker is not complete");

        // Job.deleteTree on a folder that holds outputs takes the marker before the rest
        Outputs.begin(split);
        tool(split);
        Outputs.finish(split);
        Outputs.deleteTree(split);
        check(!split.exists(), "deleteTree removes a complete folder");
        System.out.println("all ok");
    }
}
