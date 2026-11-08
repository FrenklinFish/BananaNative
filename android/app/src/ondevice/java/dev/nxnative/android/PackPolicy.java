package dev.nxnative.android;

import java.util.Locale;

/**
 * PackService's automatic pause (ONDEVICE.md "The service and the screen"), with no Android classes so PackPolicyJvmTest runs it:
 * <ul>
 * <li>battery below 20 % and not charging;</li>
 * <li>heat: thermal status (PowerManager.getCurrentThermalStatus) SEVERE or worse, and once paused for heat, until it is MODERATE or better.</li>
 * </ul>
 * "Go on anyway" (the card and the notification, while a heat pause waits) overrides the heat pause for as long as the job runs in this service: a thermal HAL that
 * reports SEVERE on a cool handheld (GitHub #38) no longer holds the job. CRITICAL or worse still pauses (until SEVERE or better): there the system itself is about
 * to throttle hard or shut down. The battery pause has no override.
 */
final class PackPolicy {
    private PackPolicy() {}

    /** PowerManager.THERMAL_STATUS_* (API 29) */
    static final int NONE = 0, LIGHT = 1, MODERATE = 2, SEVERE = 3, CRITICAL = 4, EMERGENCY = 5, SHUTDOWN = 6;
    static final int BATTERY_MIN = 20;

    /** Why the job waits ("battery", "thermal"), null when it may run. was: the reason it waits now (null: it runs); heatOverride: Go on anyway was tapped */
    static String reason(int batteryPct, boolean charging, int thermal, String was, boolean heatOverride) {
        if (batteryPct < BATTERY_MIN && !charging) return "battery";
        boolean hot = "thermal".equals(was);
        if (heatOverride ? thermal >= CRITICAL || hot && thermal > SEVERE : thermal >= SEVERE || hot && thermal > MODERATE) return "thermal";
        return null;
    }

    /** Whether Go on anyway applies to this wait: a heat pause that the override lifts (not one at CRITICAL or worse) */
    static boolean canOverride(String reason, int thermal) { return "thermal".equals(reason) && thermal < CRITICAL; }

    static String thermalName(int s) {
        switch (s) {
            case NONE: return "none";
            case LIGHT: return "light";
            case MODERATE: return "moderate";
            case SEVERE: return "severe";
            case CRITICAL: return "critical";
            case EMERGENCY: return "emergency";
            case SHUTDOWN: return "shutdown";
            default: return "unknown";
        }
    }

    /** The readings behind a decision, for the step's log and the card: "thermal status 3 (severe), headroom 0.62, battery 80 %, charging" */
    static String readings(int batteryPct, boolean charging, int thermal, float headroom) {
        return "thermal status " + thermal + " (" + thermalName(thermal) + "), headroom " + (Float.isNaN(headroom) ? "not known" : String.format(Locale.ROOT, "%.2f", headroom))
                + ", battery " + batteryPct + " %" + (charging ? ", charging" : "");
    }
}
