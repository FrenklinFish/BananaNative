package dev.nxnative.android;

/**
 * The game's audio focus (GameActivity's AudioFocusRequest), without Android types so the JVM test can run it (AudioFocusJvmTest).
 * A transient loss (a call, a navigation prompt, a notification sound) is followed by a gain from Android once the other app is done. A permanent loss (another app
 * started playing music or a video, which a frontend or the home screen may do while the game is minimized) never is: Android drops the request, so the game kept its
 * sound suspended (Native.audioInterrupt) after returning, with everything else running. A focus that is lost for good, or was refused, is asked for again on the return
 */
final class AudioFocus {
    // AudioManager's values
    static final int GAIN = 1, LOSS = -1, LOSS_TRANSIENT = -2, LOSS_TRANSIENT_CAN_DUCK = -3;

    private boolean dropped;   // lost for good or refused: no gain will come without a new request

    /** The listener's change: whether the game's sound is interrupted now */
    boolean changed(int f) {
        if (f == GAIN) { dropped = false; return false; }
        if (f == LOSS) dropped = true;
        return f == LOSS || f == LOSS_TRANSIENT || f == LOSS_TRANSIENT_CAN_DUCK;
    }
    /** The answer to a request: granted ends the interruption (no listener call comes for it), refused leaves it to the next return */
    void requested(boolean granted) { dropped = !granted; }
    /** On returning to the foreground: whether to request the focus again */
    boolean askAgain() { return dropped; }
}
