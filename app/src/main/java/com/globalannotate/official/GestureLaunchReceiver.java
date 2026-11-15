package com.globalannotate.official;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Explicit broadcast surface used by the LSPosed helper to drive the overlay
 * from {@code system_server}.
 *
 * <p>The component name, action strings and the token are duplicated here on
 * purpose: the app and the helper APK are independent and must not share a
 * compile-time dependency. Every control action is only accepted when its
 * {@code "token"} extra equals {@link #TOKEN}; anything else is logged and
 * dropped. Unknown actions and malformed extras never throw.</p>
 *
 * <p>The helper sends {@code LAUNCH} for the pen corner swipe / squeeze, and
 * {@code GESTURE} for the barrel gestures. {@code TOGGLE},
 * {@code SET_SPLIT_MODE} and {@code SET_MODULE_ENABLED} are also part of the
 * protocol, so they are accepted (and mirrored locally) even though the
 * current helper only broadcasts the first two.</p>
 */
public final class GestureLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "GaGestureLaunch";

    /** Kept byte-identical to the helper's {@code XActions}. */
    public static final String ACTION_LAUNCH = "com.globalannotate.action.LAUNCH";
    public static final String ACTION_TOGGLE = "com.globalannotate.action.TOGGLE";
    public static final String ACTION_GESTURE = "com.globalannotate.action.GESTURE";
    public static final String ACTION_SET_SPLIT_MODE =
            "com.globalannotate.action.SET_SPLIT_MODE";
    public static final String ACTION_SET_MODULE_ENABLED =
            "com.globalannotate.action.SET_MODULE_ENABLED";

    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_GESTURE = "gesture";
    public static final String EXTRA_ENABLED = "enabled";
    public static final String TOKEN = "ga-hyperos-2026";

    /**
     * FloatingService exposes only {@code ACTION_START} / {@code ACTION_STOP};
     * it has no toggle action. TOGGLE therefore picks one of the two from this
     * persisted bit.
     *
     * <p>The bit is written only by this receiver. A start from MainActivity
     * or a stop from the notification is not observable here, so it can go
     * stale; the worst case is that the next TOGGLE re-asserts the state the
     * overlay already has (starting a running service is idempotent, stopping
     * a missing one is a no-op).</p>
     */
    private static final String PREFS = "ga_launch";
    private static final String KEY_RUNNING = "overlay_running";
    private static final String KEY_SPLIT_MODE = "split_mode";
    private static final String KEY_MODULE_ENABLED = "module_enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        final String action = intent.getAction();
        if (action == null) {
            Log.w(TAG, "broadcast without an action ignored");
            return;
        }
        if (!TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN))) {
            Log.w(TAG, "rejected " + action + ": bad or missing token");
            return;
        }

        if (ACTION_LAUNCH.equals(action)) {
            startOverlay(context);
        } else if (ACTION_TOGGLE.equals(action)) {
            toggleOverlay(context);
        } else if (ACTION_GESTURE.equals(action)) {
            onGesture(context, intent.getStringExtra(EXTRA_GESTURE));
        } else if (ACTION_SET_SPLIT_MODE.equals(action)) {
            final boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, false);
            prefs(context).edit().putBoolean(KEY_SPLIT_MODE, enabled).apply();
            // Mirror the switch into the input sink even when the helper's
            // Binder transaction (TX_SET_ACTIVE) is the authoritative path.
            AnnotationInputService.setSplitActive(enabled);
            Log.i(TAG, "split mode=" + enabled);
        } else if (ACTION_SET_MODULE_ENABLED.equals(action)) {
            final boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, false);
            prefs(context).edit().putBoolean(KEY_MODULE_ENABLED, enabled).apply();
            Log.i(TAG, "module enabled=" + enabled);
        } else {
            // The manifest filter is only a hint: adb or a newer helper may
            // send actions this build does not know yet. Never crash for them.
            Log.i(TAG, "ignoring unknown action " + action);
        }
    }

    /**
     * A gesture while the overlay is down summons it, matching the old app's
     * {@code if (!active) setActive(true) else applyGesture()}. The current
     * FloatingService has no gesture command surface (eraser toggle, undo,
     * redo, ...), so a gesture while it is already up is logged and ignored
     * instead of guessed at.
     */
    private static void onGesture(Context context, String name) {
        if (name == null || name.isEmpty()) {
            Log.w(TAG, "gesture without a name ignored");
            return;
        }
        if (isOverlayRunning(context)) {
            Log.i(TAG, "gesture " + name + " ignored: overlay already running");
            return;
        }
        Log.i(TAG, "gesture " + name + " summons the overlay");
        startOverlay(context);
    }

    private static void toggleOverlay(Context context) {
        if (isOverlayRunning(context)) {
            stopOverlay(context);
        } else {
            startOverlay(context);
        }
    }

    private static void startOverlay(Context context) {
        final Intent intent = new Intent(context, FloatingService.class)
                .setAction(FloatingService.ACTION_START);
        try {
            context.startForegroundService(intent);
            setOverlayRunning(context, true);
            // The helper only binds AnnotationInputService after it sees
            // SET_MODULE_ENABLED=true (GlobalAnnotateHook.bindIfNeeded); the
            // old app sent this from AnnotationService.onCreate, which this
            // build may not modify. Without it ROOT_SPLIT has no Binder sink.
            notifyModule(context, true);
            Log.i(TAG, "startForegroundService(FloatingService) requested");
        } catch (RuntimeException error) {
            // Android 12+ background-FGS rules can still refuse this on a ROM
            // that ignores the SYSTEM_ALERT_WINDOW exemption. Leave the state
            // bit untouched so a later attempt can retry.
            Log.e(TAG, "startForegroundService failed", error);
        }
    }

    private static void stopOverlay(Context context) {
        final Intent stop = new Intent(context, FloatingService.class)
                .setAction(FloatingService.ACTION_STOP);
        try {
            // The service is already foreground, so a plain startService is
            // enough to deliver ACTION_STOP (same path as MainActivity).
            context.startService(stop);
        } catch (RuntimeException error) {
            Log.w(TAG, "startService(STOP) failed; stopping directly", error);
            try {
                context.stopService(new Intent(context, FloatingService.class));
            } catch (RuntimeException ignored) {
                // Nothing left to stop.
            }
        }
        setOverlayRunning(context, false);
        // Mirrors the old app's onDestroy: stop the system-side hooks and drop
        // ROOT_SPLIT once the overlay is gone.
        notifyModule(context, false);
    }

    /**
     * Tells the helper's runtime receiver in system_server to enable/disable
     * routing. It is an implicit broadcast on purpose: the helper receiver is
     * registered at runtime, so it is not subject to the Android 8+ implicit
     * broadcast restrictions that apply to manifest receivers.
     */
    private static void notifyModule(Context context, boolean enabled) {
        final Intent intent = new Intent(ACTION_SET_MODULE_ENABLED)
                .putExtra(EXTRA_TOKEN, TOKEN)
                .putExtra(EXTRA_ENABLED, enabled)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            context.sendBroadcast(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "module notification failed", error);
        }
    }

    private static boolean isOverlayRunning(Context context) {
        return prefs(context).getBoolean(KEY_RUNNING, false);
    }

    private static void setOverlayRunning(Context context, boolean running) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
