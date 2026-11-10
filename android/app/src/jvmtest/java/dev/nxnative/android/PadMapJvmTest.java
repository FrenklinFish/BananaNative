package dev.nxnative.android;

/** PadMap on the JVM (dm/tools/tests/test_android_padmap.py): the defaults, the spec read and written, the editor's swap. Prints "ok" or throws */
public final class PadMapJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) {
        PadMap d = PadMap.defaults(0);
        check(d.target[PadMap.source("BUTTON_A")] == 1 && d.target[PadMap.source("BUTTON_B")] == 0, "by position: bottom is B, right is A");
        check(d.target[PadMap.source("BUTTON_X")] == 3 && d.target[PadMap.source("BUTTON_Y")] == 2, "left is Y, top is X");
        check(!d.edited() && d.spec().isEmpty(), "the default is no map");
        PadMap l = PadMap.defaults(2);
        check(l.target[0] == 0 && l.target[1] == 1 && l.target[2] == 3, "labelled 2: A / B by label, X / Y by position");
        for (int i = 0; i < PadMap.KEYCODES.length; i++) check(PadMap.sourceOfKey(PadMap.KEYCODES[i]) == i, "keycode of " + PadMap.SOURCES[i]);
        check(PadMap.sourceOfKey(4) == -1 && PadMap.sourceOfKey(110) == -1, "Back and Home are not sources");

        PadMap m = PadMap.parse("BUTTON_A=A, BUTTON_B=B,BUTTON_Q=X,BUTTON_C=NONE,BUTTON_X=FOO", 0);
        check(m.target[0] == 0 && m.target[1] == 1 && m.set[0] && m.set[1], "entries read");
        check(!m.set[2] && m.target[2] == 3, "an unreadable entry is left out");
        check(m.spec().equals("BUTTON_A=A,BUTTON_B=B,BUTTON_C=NONE"), "spec: " + m.spec());
        check(PadMap.parse(m.spec(), 0).spec().equals(m.spec()), "round trip");

        PadMap e = PadMap.defaults(0);
        e.assign(PadMap.source("BUTTON_A"), 0);   // bottom presses A: the right button, A before, takes B
        check(e.target[0] == 0 && e.target[1] == 1, "swap: " + e.spec());
        for (int sw = 0; sw < 16; sw++) { int n = 0; for (int t : e.target) if (t == sw) n++; check(n == 1, "every Switch button on one button: " + sw); }
        check(e.spec().split(",").length == PadMap.STANDARD, "an edited map names all sixteen standard sources: " + e.spec());
        e.assign(PadMap.source("BUTTON_C"), 8);   // C presses ZL: L2 gives it up and takes C's NONE
        check(e.target[PadMap.source("BUTTON_L2")] == PadMap.NONE && e.spec().contains("BUTTON_C=ZL") && e.spec().contains("BUTTON_L2=NONE"), "C takes ZL: " + e.spec());
        e.assign(PadMap.source("BUTTON_L2"), 8);   // and back: C gives it up, unlisted again
        check(e.target[PadMap.source("BUTTON_L2")] == 8 && !e.set[16] && !e.spec().contains("BUTTON_C"), "C unlisted: " + e.spec());
        check(e.pressedBy(0).equals("A") && e.pressedBy(9).equals("R2"), "pressedBy: " + e.pressedBy(0) + " " + e.pressedBy(9));

        check(PadMap.summary("").equals("Default"), "summary default");
        check(PadMap.summary("BUTTON_A=A,BUTTON_B=B").equals("Custom (2 buttons moved)"), PadMap.summary("BUTTON_A=A,BUTTON_B=B"));
        check(PadMap.summary("BUTTON_A=B").equals("Custom (as the default)"), "summary unchanged");
        System.out.println("ok");
    }
}
