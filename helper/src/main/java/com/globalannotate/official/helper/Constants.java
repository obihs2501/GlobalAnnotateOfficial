package com.globalannotate.official.helper;

/**
 * Port of Constants.kt.
 *
 * <p>The Kotlin original declared three top-level {@code object}s in a single
 * file. Java has no file-level objects, so the three live here as top-level
 * classes with static members, keeping the same class names ({@code XActions},
 * {@code XIpc}, {@code XPenKeys}) and constant values.</p>
 *
 * <p>Actions, extras and the token are kept byte-identical to the app-side
 * {@code Actions} object: the two APKs are independent and must not depend on
 * each other at build time. Only references to the old app package were
 * redirected to {@code com.globalannotate.official}; the action strings never
 * embedded that package and therefore remain unchanged.</p>
 */
final class XActions {
    private XActions() {
    }

    static final String LAUNCH = "com.globalannotate.action.LAUNCH";
    static final String TOGGLE = "com.globalannotate.action.TOGGLE";
    static final String GESTURE = "com.globalannotate.action.GESTURE";
    static final String SET_SPLIT_MODE = "com.globalannotate.action.SET_SPLIT_MODE";
    static final String SET_MODULE_ENABLED = "com.globalannotate.action.SET_MODULE_ENABLED";
    static final String SET_PEN_GESTURES = "com.globalannotate.action.SET_PEN_GESTURES";

    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_GESTURE = "gesture";
    static final String EXTRA_ENABLED = "enabled";
    static final String TOKEN = "ga-hyperos-2026";

    static final String APP_PACKAGE = "com.globalannotate.official";
    static final String RECEIVER_CLASS = "com.globalannotate.official.GestureLaunchReceiver";

    /**
     * The overlay service. Not exported, but system_server may start it anyway:
     * ActivityManager.checkComponentPermission() grants SYSTEM_UID access to
     * unexported components.
     */
    static final String APP_SERVICE_CLASS = "com.globalannotate.official.FloatingService";

    /**
     * Transparent launcher activity: starts the service as a foreground app,
     * which is the last-resort fallback when a direct FGS start is refused.
     */
    static final String MAIN_ACTIVITY_CLASS = "com.globalannotate.official.MainActivity";
}

final class XIpc {
    private XIpc() {
    }

    static final String DESCRIPTOR = "com.globalannotate.ipc.IAnnotationInput";
    static final int TX_STYLUS_EVENT = 1;
    static final int TX_SET_ACTIVE = 2;
    static final String SERVICE_PACKAGE = "com.globalannotate.official";
    static final String SERVICE_CLASS = "com.globalannotate.official.AnnotationInputService";
}

final class XPenKeys {
    private XPenKeys() {
    }

    static final int SQUEEZE = 194;
    static final int DOUBLE_TAP = 195;
    static final int SLIDE_UP = 196;
    static final int SLIDE_DOWN = 197;
    static final int SCANCODE_SQUEEZE_RAW = 189;
}
