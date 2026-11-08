package dev.nxnative.android;

import android.content.Context;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The game's rumble (the host's rumble thread, nxhost_rumble_callback) on a vibrator: a controller's own (InputDevice.getVibratorManager), else the phone's, which is
 * also player 1's without a controller (the on-screen controls). A handheld's built-in pad has no vibrator of its own and need not say it is built in: the AYN Odin 2
 * Portal's is an "Xbox Wireless Controller" on USB that reports itself external, and its motor is the phone's vibrator. The host sends a player's vibration only while
 * route() has given it one (Native.rumbleRoute), and only when it changes.
 * A Switch 2 game streams HD rumble: Donkey Kong Bananza sends 400 values a second, short peaks (0.3-0.55) between near-silence. Passed on one by one, every value
 * cancelled the vibration before it and lasted about 12 ms, too short for a phone's motor to spin up: it ran at 6-9 %. So the vibrator follows the rumble's envelope
 * instead: every TICK_MS the strongest value since the last tick (and the value holding now), easing off by DECAY a tick once the game goes quiet, and a new vibration
 * only when the amplitude moved by a few steps. A square root lifts the game's usual 0.1-0.4 and keeps their order, so the game's own rumble setting still scales it.
 * The vibrators have one motor, so the sharpness (low / high frequency) is not used. DM_RUMBLE=0 in env.txt turns rumble off, DM_RUMBLE_GAIN scales it (0..3, the host's).
 */
final class Rumble {
    private final Vibrator phone;
    private final Map<Integer, Vibrator> byPlayer = new HashMap<>();   // under this
    private final VibrationAttributes attrs = new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build();
    private static final int TICK_MS = 40, STEP = 6;   // the envelope's rate, and the least amplitude change (of 255) worth a new vibration
    private static final float DECAY = 0.6f;            // a tick's fall once the game goes quiet: about 0.2 s from full to off
    /** Per vibrator (players can share the phone's): the value now, the peak since the last tick, the level sent (0..1, under this) */
    private static final class Env { float now, peak, out; int amp; }
    private final Map<Vibrator, Env> env = new IdentityHashMap<>();
    private ScheduledExecutorService ticker;
    private volatile boolean muted;   // the in-game panel's Rumble off (mute): the game's changes are dropped until it is on again

    Rumble(Context c) {
        VibratorManager vm = c.getSystemService(VibratorManager.class);
        Vibrator v = vm != null ? vm.getDefaultVibrator() : null;
        phone = v != null && v.hasVibrator() ? v : null;
    }

    /** Right after Native.start (the host reads DM_RUMBLE then, and its lines reach the log): installs the listener and gives player 1 the phone (until a controller takes it) */
    boolean start() {
        if (!Native.setRumbleListener(this)) return false;
        set(0, phone);
        Native.note("rumble: " + (phone != null ? "the phone's vibrator" + (phone.hasAmplitudeControl() ? "" : " (no amplitude control: full strength)") : "no vibrator in the phone"));
        return true;
    }

    /** A controller has a player (after its first key, or when connected): its own vibrator, else the phone's */
    void route(int device) {
        int p = Native.devicePlayer(device);
        InputDevice d = InputDevice.getDevice(device);
        if (p < 0 || d == null) return;
        VibratorManager vm = d.getVibratorManager();
        int[] ids = vm.getVibratorIds();
        Vibrator v = ids.length > 0 ? vm.getDefaultVibrator() : phone;
        if (set(p, v)) Native.note("rumble: player " + (p + 1) + " (" + d.getName() + ") on " + (v == null ? "no vibrator" : ids.length > 0 ? "the controller's vibrator" : "the phone's vibrator"));
    }

    /** A player's vibrator; true when it changed */
    private boolean set(int player, Vibrator v) {
        Vibrator was;
        synchronized (this) { was = byPlayer.get(player); if (was == v && byPlayer.containsKey(player)) return false; byPlayer.put(player, v); }
        if (was != null) was.cancel();
        Native.rumbleRoute(player, v != null);
        return true;
    }

    /** From the host's rumble thread (nxlaunch.c rumble): intensity 0..1, 0 = quiet. Only noted here: tick() drives the vibrator */
    @SuppressWarnings("unused")
    void onRumble(int player, float intensity, float sharpness) {
        if (muted) return;
        Vibrator v;
        synchronized (this) {
            v = byPlayer.get(player);
            if (v == null) return;
            Env e = env.get(v); if (e == null) env.put(v, e = new Env());
            e.now = Math.max(0, Math.min(1, intensity)); e.peak = Math.max(e.peak, e.now);
            if (ticker == null && e.now > 0) {
                ticker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "rumble"); t.setDaemon(true); return t; });
                ticker.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Every TICK_MS: each vibrator to its envelope (the peak since the last tick, else the level easing off) */
    private void tick() {
        Map<Vibrator, Integer> send = new IdentityHashMap<>();
        synchronized (this) {
            for (Map.Entry<Vibrator, Env> m : env.entrySet()) {
                Env e = m.getValue();
                float target = Math.max(e.peak, e.now); e.peak = 0;
                e.out = Math.max(target, e.out * DECAY);
                if (e.out < 0.02f) e.out = 0;
                int amp = e.out <= 0 ? 0 : Math.max(1, Math.min(255, Math.round(255 * (float)Math.sqrt(e.out))));
                if (amp == e.amp || (amp != 0 && e.amp != 0 && Math.abs(amp - e.amp) < STEP)) continue;
                e.amp = amp; send.put(m.getKey(), amp);
            }
        }
        for (Map.Entry<Vibrator, Integer> m : send.entrySet()) {
            if (m.getValue() == 0) m.getKey().cancel();
            // One long step that repeats: held until the next change replaces it or cancel() stops it
            else m.getKey().vibrate(VibrationEffect.createWaveform(new long[] {10_000}, new int[] {m.getValue()}, 0), attrs);
        }
    }

    /** The in-game panel's Rumble switch while the game runs: off stops every vibrator at once; on takes the game's next change */
    void mute(boolean m) { muted = m; if (m) stopAll(); }

    /** The screen goes away or the game is suspended: every vibrator stops (the host sends the next change when the game rumbles again) */
    void stopAll() {
        Vibrator[] all;
        synchronized (this) { all = byPlayer.values().toArray(new Vibrator[0]); for (Env e : env.values()) { e.now = e.peak = e.out = 0; e.amp = 0; } }
        for (Vibrator v : all) if (v != null) v.cancel();
    }
}
