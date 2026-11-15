package com.globalannotate.official.helper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The helper app's mirror of the module switches.
 *
 * <p>The authoritative copy lives in system_server ({@link ModulePrefs}); this
 * only gives the settings screen a value to display and re-send when it opens.
 * Both are written together on every toggle, so they only diverge if one side's
 * storage is cleared independently.</p>
 */
final class HelperSettings {

    private static final String PREFS = "ga_helper";
    private static final String KEY_PEN_GESTURES = "pen_gestures_enabled";

    private HelperSettings() {
    }

    static boolean penGesturesEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PEN_GESTURES, true);
    }

    static void setPenGesturesEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PEN_GESTURES, enabled).apply();
    }

    /**
     * Sends the value to the module's runtime receiver in system_server. An
     * implicit broadcast is fine here: the receiver is registered at runtime,
     * so the Android 8+ implicit-broadcast restriction (manifest receivers
     * only) does not apply.
     */
    static void sendPenGestures(Context context, boolean enabled) {
        final Intent intent = new Intent(XActions.SET_PEN_GESTURES)
                .putExtra(XActions.EXTRA_TOKEN, XActions.TOKEN)
                .putExtra(XActions.EXTRA_ENABLED, enabled)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            context.sendBroadcast(intent);
            GaLog.i("pen gestures -> system_server: " + enabled);
        } catch (RuntimeException error) {
            GaLog.e("pen gesture broadcast failed: " + error);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
