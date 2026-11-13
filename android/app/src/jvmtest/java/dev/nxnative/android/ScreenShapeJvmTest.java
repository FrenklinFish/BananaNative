package dev.nxnative.android;

/** ScreenShape (the window's own picture shape) and GameOrientation's rotation setting; prints "ok" */
public final class ScreenShapeJvmTest {
    static void check(boolean c, String what) { if (!c) { System.err.println("FAIL: " + what); System.exit(1); } }
    static void eq(String got, String want, String what) { check(want == null ? got == null : want.equals(got), what + ": " + got + " (wanted " + want + ")"); }

    public static void main(String[] a) {
        // A 19.5:9 phone (2400x1104 window, 1080x2448 display) and an exact 20:9
        eq(ScreenShape.ratio(2448, 1080, true), "34:15", "Honor-style 2448x1080");
        eq(ScreenShape.ratio(2400, 1080, true), "20:9", "20:9 reduced");
        eq(ScreenShape.ratio(1080, 2400, true), "20:9", "reported upright, locked to landscape: turned");
        eq(ScreenShape.ratio(1080, 2400, false), "4:5", "automatic rotation, upright 9:20: clamped to 4:5");
        // The foldable's inner screen, either way
        eq(ScreenShape.ratio(2352, 2172, false), "196:181", "inner screen landscape");
        eq(ScreenShape.ratio(2172, 2352, false), "181:196", "inner screen portrait");
        // A 16:9 (or within 4 %) window passes nothing
        eq(ScreenShape.ratio(1920, 1080, true), null, "16:9");
        eq(ScreenShape.ratio(2560, 1440, false), null, "16:9 bigger");
        eq(ScreenShape.ratio(1280, 720, true), null, "720p");
        eq(ScreenShape.ratio(1920, 1040, true), null, "1.85:1 is within 4 % of 16:9");
        eq(ScreenShape.ratio(2160, 1080, true), "2:1", "2:1 is 12 % wider");
        // The very wide 2.17:1 (2340x1080) stays inside the host's range and exact
        eq(ScreenShape.ratio(2340, 1080, true), "13:6", "2.17:1");
        // Out of the host's range: clamped
        eq(ScreenShape.ratio(5000, 1000, true), "399:100", "5:1 clamped");
        eq(ScreenShape.ratio(1000, 5000, false), "4:5", "1:5 clamped to 4:5");
        // Unknown
        eq(ScreenShape.ratio(0, 1080, true), null, "no width");
        eq(ScreenShape.ratio(1080, -1, false), null, "no height");
        // A coprime size is passed as it is
        eq(ScreenShape.ratio(2411, 1080, true), "2411:1080", "coprime");
        // The rotation setting: Automatic never fixes the orientation, Landscape is the old rule
        check(!GameOrientation.fixedLandscape(1080, 2448, 2.8f, true), "automatic on a phone: no request");
        check(!GameOrientation.fixedLandscape(1600, 2560, 2.0f, true), "automatic on a tablet: no request");
        check(GameOrientation.fixedLandscape(1080, 2448, 2.8f, false), "landscape setting on a phone: sensorLandscape");
        check(!GameOrientation.fixedLandscape(2172, 2352, 3.0f, false), "landscape setting on the inner screen: still none");
        check(GameOrientation.fixedLandscape(0, 0, 0f, true) == false, "automatic: unknown size is still no request");
        System.out.println("ok");
    }
}
