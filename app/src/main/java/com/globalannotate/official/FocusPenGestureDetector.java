package com.globalannotate.official;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Converts Xiaomi Focus Pen key events into the app's four gesture names. */
final class FocusPenGestureDetector {
    interface Listener {
        void onGesture(String name);
    }

    private static final long DOUBLE_TAP_WINDOW_MS = 320L;
    private static final long SQUEEZE_TAP_DEDUPE_WINDOW_MS = 1600L;
    private static final int KEY_SQUEEZE = 194;
    private static final int KEY_DOUBLE_TAP = 195;
    private static final int KEY_SLIDE_UP = 196;
    private static final int KEY_SLIDE_DOWN = 197;
    private static final int SCAN_SQUEEZE = 189;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private Runnable pendingSingle;
    private Runnable doubleTapCooldown;
    private boolean squeezePressed;
    private boolean secondTap;
    private boolean ignoreCurrentUp;
    private long lastSqueezeAtMs;

    FocusPenGestureDetector(Listener listener) {
        this.listener = listener;
    }

    boolean onKey(int keyCode, int scanCode, int action, int repeatCount) {
        final int gestureKey = keyCode == KEY_SQUEEZE || keyCode == KEY_DOUBLE_TAP
                || keyCode == KEY_SLIDE_UP || keyCode == KEY_SLIDE_DOWN
                ? keyCode : scanCode == SCAN_SQUEEZE ? KEY_SQUEEZE : 0;
        if (gestureKey == 0) {
            return false;
        }
        if (gestureKey == KEY_SQUEEZE) {
            onSqueeze(action, repeatCount);
        } else if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            if (gestureKey == KEY_DOUBLE_TAP) {
                if (squeezePressed) {
                    ignoreCurrentUp = true;
                }
                if (pendingSingle != null) {
                    cancelPendingSingle();
                    armDoubleTapCooldown();
                    emit("DOUBLE_TAP");
                } else if (!isRecentSqueezeReport() && doubleTapCooldown == null) {
                    emit("DOUBLE_TAP");
                }
            } else if (gestureKey == KEY_SLIDE_UP) {
                emit("SLIDE_UP");
            } else {
                emit("SLIDE_DOWN");
            }
        }
        return true;
    }

    private void onSqueeze(int action, int repeatCount) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (squeezePressed || repeatCount > 0) {
                return;
            }
            squeezePressed = true;
            if (pendingSingle != null) {
                cancelPendingSingle();
                secondTap = true;
                armDoubleTapCooldown();
                emit("DOUBLE_TAP");
            }
            return;
        }
        if (action != KeyEvent.ACTION_UP || !squeezePressed) {
            return;
        }
        squeezePressed = false;
        if (secondTap) {
            secondTap = false;
            return;
        }
        if (ignoreCurrentUp) {
            ignoreCurrentUp = false;
            return;
        }
        cancelPendingSingle();
        pendingSingle = new Runnable() {
            @Override
            public void run() {
                pendingSingle = null;
                lastSqueezeAtMs = SystemClock.uptimeMillis();
                emit("SQUEEZE");
            }
        };
        handler.postDelayed(pendingSingle, DOUBLE_TAP_WINDOW_MS);
    }

    private void armDoubleTapCooldown() {
        if (doubleTapCooldown != null) {
            handler.removeCallbacks(doubleTapCooldown);
        }
        doubleTapCooldown = new Runnable() {
            @Override
            public void run() {
                doubleTapCooldown = null;
            }
        };
        handler.postDelayed(doubleTapCooldown, DOUBLE_TAP_WINDOW_MS);
    }

    private boolean isRecentSqueezeReport() {
        final long elapsed = SystemClock.uptimeMillis() - lastSqueezeAtMs;
        return lastSqueezeAtMs > 0L && elapsed >= 0L
                && elapsed <= SQUEEZE_TAP_DEDUPE_WINDOW_MS;
    }

    private void cancelPendingSingle() {
        if (pendingSingle != null) {
            handler.removeCallbacks(pendingSingle);
            pendingSingle = null;
        }
    }

    private void emit(String gesture) {
        if (listener != null) {
            listener.onGesture(gesture);
        }
    }

    void reset() {
        cancelPendingSingle();
        if (doubleTapCooldown != null) {
            handler.removeCallbacks(doubleTapCooldown);
            doubleTapCooldown = null;
        }
        squeezePressed = false;
        secondTap = false;
        ignoreCurrentUp = false;
        lastSqueezeAtMs = 0L;
    }
}
