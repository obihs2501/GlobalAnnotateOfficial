package com.globalannotate.official.helper;

import android.util.Log;

/** Routes module logs through libxposed's log() when available, else Logcat. */
public final class GaLog {
    private static final String DEFAULT_TAG = "GlobalAnnotate";

    /** Java stand-in for Kotlin's {@code ((Int, String, String) -> Unit)?}. */
    public interface Sink {
        void log(int priority, String tag, String message);
    }

    private static volatile Sink sink;

    private GaLog() {
    }

    public static void setSink(Sink target) {
        sink = target;
    }

    public static Sink getSink() {
        return sink;
    }

    public static void i(String message) {
        i(message, DEFAULT_TAG);
    }

    public static void i(String message, String tag) {
        Sink target = sink;
        if (target != null) {
            target.log(Log.INFO, tag, message);
        } else {
            Log.i(tag, message);
        }
    }

    public static void e(String message) {
        e(message, DEFAULT_TAG);
    }

    public static void e(String message, String tag) {
        Sink target = sink;
        if (target != null) {
            target.log(Log.ERROR, tag, message);
        } else {
            Log.e(tag, message);
        }
    }
}
