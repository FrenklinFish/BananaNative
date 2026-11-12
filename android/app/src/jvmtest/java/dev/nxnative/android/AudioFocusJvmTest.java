package dev.nxnative.android;

/** AudioFocus on the JVM (dm/tools/tests/test_android_audiofocus.py): which focus changes interrupt the sound, and when the return asks for the focus again. Prints "ok" or throws */
public final class AudioFocusJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) {
        AudioFocus f = new AudioFocus();
        f.requested(true);
        check(!f.askAgain(), "granted at start: nothing to ask");
        // A call: transient, Android gives the focus back itself
        check(f.changed(AudioFocus.LOSS_TRANSIENT), "transient loss interrupts");
        check(!f.askAgain(), "transient loss: Android gives it back, no new request");
        check(!f.changed(AudioFocus.GAIN), "gain ends the interruption");
        check(f.changed(AudioFocus.LOSS_TRANSIENT_CAN_DUCK) && !f.askAgain(), "ducking: interrupted, given back by Android");
        check(!f.changed(AudioFocus.GAIN), "gain after ducking");
        // Another app plays music while the game is minimized: lost for good, asked again on the return
        check(f.changed(AudioFocus.LOSS), "permanent loss interrupts");
        check(f.askAgain(), "permanent loss: ask again on the return");
        f.requested(false);
        check(f.askAgain(), "refused: ask again on the next return");
        f.requested(true);
        check(!f.askAgain(), "granted again: nothing more to ask");
        // A transient loss after a permanent one (the other app's prompt) does not forget that the request was dropped
        f.changed(AudioFocus.LOSS); f.changed(AudioFocus.LOSS_TRANSIENT);
        check(f.askAgain(), "still dropped");
        check(!f.changed(AudioFocus.GAIN) && !f.askAgain(), "a gain means it is held");
        // Refused at start: the first return asks
        AudioFocus g = new AudioFocus(); g.requested(false);
        check(g.askAgain(), "refused at start");
        check(!g.changed(0), "an unknown change does not interrupt");
        System.out.println("ok");
    }
}
