package com.globalannotate.official.helper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Hook points verified on Xiaomi Pad 8 Pro / HyperOS 3 {@code OS3.0.307.0.WOCCNXM}
 * and HyperOS 4 {@code OS4.0.4.0.XPYCNXM} by FocusPenProX, plus AOSP input classes.
 * Every lookup is best-effort and fail-open: a missing class simply logs.
 */
public final class CompatProfile {
    private CompatProfile() {
    }

    public static final String TOUCH_FILM_CLASS = "com.miui.server.input.stylus.MiuiStylusTouchFilmManager";
    public static final String SHORTCUT_MANAGER_CLASS = "com.miui.server.input.stylus.MiuiStylusShortcutManager";
    public static final String POLICY_CLASS = "com.android.server.policy.MiuiPhoneWindowManager";

    public static final String INTERCEPT_KEY_METHOD = "interceptKeyBeforeQueueing";
    public static final String FILTER_METHOD = "filterInputEvent";
    /**
     * WindowManager's system-gestures listener. Unlike
     * {@code InputManagerService.filterInputEvent} (which the native
     * dispatcher only calls while an input filter is installed), this pointer
     * listener is registered by DisplayPolicy for the whole display, so it
     * sees every finger and stylus stream from every app — the only reliable
     * observation point for the corner summon.
     */
    public static final String POINTER_EVENT_CLASS =
            "com.android.server.wm.SystemGesturesPointerEventListener";
    public static final String POINTER_EVENT_METHOD = "onPointerEvent";

    public static final List<String> KEY_HOOK_CLASSES = Collections.unmodifiableList(Arrays.asList(
            TOUCH_FILM_CLASS,
            POLICY_CLASS,
            SHORTCUT_MANAGER_CLASS));

    public static final List<String> INPUT_SERVICE_CLASSES = Collections.unmodifiableList(Arrays.asList(
            "com.android.server.input.InputManagerService",
            "com.android.server.input.InputManagerService$LocalService",
            "com.miui.server.input.MiuiInputManagerService"));

    /** Pointer listeners that receive every display touch stream. */
    public static final List<String> POINTER_EVENT_CLASSES = Collections.unmodifiableList(Arrays.asList(
            POINTER_EVENT_CLASS));

    /**
     * Observation zone for the pen-tip corner gesture. NOT a blocked area:
     * events here are always forwarded to the app underneath.
     */
    public static final int CORNER_WIDTH_DP = 140;
    public static final int CORNER_HEIGHT_DP = 140;
    public static final int SWIPE_THRESHOLD_DP = 52;
    public static final long CORNER_COOLDOWN_MS = 800L;

    /**
     * Finger corner-summon zone (Huawei-style). The small inset stays inside
     * the physical corner while skipping the very edge (where the system's own
     * gesture monitors live); only an inward, horizontally dominant swipe
     * fires, so a downward pull from the top-right still opens the control
     * centre.
     */
    public static final int FINGER_CORNER_INSET_DP = 8;
    public static final int FINGER_CORNER_HEIGHT_DP = 140;

    /**
     * Grace period for the LAUNCH broadcast to reach a live app before
     * AppBridge starts the service from system_server itself.
     */
    public static final long LAUNCH_FALLBACK_DELAY_MS = 700L;

    /**
     * After a refused system-side FGS start, wait this long before trying it
     * again: the activity fallback already covers the gesture, and some ROMs
     * post a user-visible notification for every denied background FGS.
     */
    public static final long LAUNCH_FGS_RETRY_MS = 10000L;

    /**
     * Rate limit for the transparent MainActivity fallback: if the service
     * cannot be started at all, repeated swipes must not flash it.
     */
    public static final long LAUNCH_ACTIVITY_COOLDOWN_MS = 3000L;

    /** Minimum interval between forwarded MOVE events (ms). */
    public static final long FORWARD_INTERVAL_MS = 4L;

    public static final long DOUBLE_TAP_WINDOW_MS = 320L;
}
