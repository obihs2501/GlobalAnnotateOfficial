package com.globalannotate.official.helper;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Global pen-gesture launch, hooked at the key-policy entry that
 * WondersReady/FocusPen documents:
 *
 * <pre>
 *   BaseMiuiPhoneWindowManager.interceptKeyBeforeQueueingInternal
 *     -&gt; MiuiStylusTouchFilmManager.interceptKeyBeforeQueueing
 * </pre>
 *
 * <p>Runs in system_server for every foreground app, so a squeeze / double tap
 * summons annotation from anywhere. With libxposed API 102, NOT calling
 * chain.proceed() consumes the event; the replacement value must still match
 * the hooked method's return type or the framework throws ClassCastException,
 * so the value is derived from the real signature (int / boolean / void).</p>
 */
public final class GestureLauncher {

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pendingSingle = null;
    private static long lastSqueezeDownMs = 0L;

    private GestureLauncher() {
    }

    public static void install(XposedInterface api, ClassLoader classLoader, AppBridge bridge) {
        int hooked = 0;
        for (String className : CompatProfile.KEY_HOOK_CLASSES) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className, false, classLoader);
            } catch (Throwable error) {
                continue;
            }
            for (Method method : findMethods(clazz, CompatProfile.INTERCEPT_KEY_METHOD)) {
                final Object consumed = consumedValueFor(method);
                boolean ok;
                try {
                    method.setAccessible(true);
                    api.hook(method).intercept(chain -> {
                        try {
                            return handle(chain, bridge, consumed);
                        } catch (Throwable error) {
                            GaLog.e("gesture hook error: " + error);
                            return chain.proceed(); // fail open
                        }
                    });
                    ok = true;
                } catch (Throwable error) {
                    GaLog.e("hook " + className + "#" + method.getName() + " failed: " + error);
                    ok = false;
                }
                if (ok) {
                    hooked++;
                    GaLog.i("hooked " + className + "#" + method.getName()
                            + "(" + joinSimpleNames(method.getParameterTypes()) + "): "
                            + method.getReturnType().getSimpleName());
                }
            }
        }
        if (hooked == 0) GaLog.e("no key-policy hook found; check CompatProfile against this ROM");
    }

    /** Consumed value matching the real return type of the hooked method. */
    private static Object consumedValueFor(Method method) {
        Class<?> type = method.getReturnType();
        if (type == Integer.TYPE || type == Integer.class) return Integer.valueOf(0);
        if (type == Boolean.TYPE || type == Boolean.class) return Boolean.TRUE;
        if (type == Long.TYPE || type == Long.class) return Long.valueOf(0L);
        return null; // void
    }

    private static Object handle(XposedInterface.Chain chain, AppBridge bridge, Object consumed)
            throws Throwable {
        if (!ModuleState.enabled) return chain.proceed();
        // The global pen-gesture listener is a user switch. When it is off the
        // key events must flow to the ROM untouched, so the pen's own squeeze
        // shortcuts keep working; consume nothing and swallow nothing.
        if (!ModuleState.penGesturesEnabled) return chain.proceed();
        List<Object> args = chain.getArgs();
        Object first = args.isEmpty() ? null : args.get(0);
        if (!(first instanceof KeyEvent)) return chain.proceed();
        KeyEvent event = (KeyEvent) first;
        int keyCode = event.getKeyCode();
        boolean isPenKey = (keyCode >= XPenKeys.SQUEEZE && keyCode <= XPenKeys.SLIDE_DOWN)
                || event.getScanCode() == XPenKeys.SCANCODE_SQUEEZE_RAW;
        if (!isPenKey) return chain.proceed();
        // One physical squeeze can produce more than one key event (and the
        // ROM reports its own double tap separately). Log the raw stream so a
        // device session can tell a bounce from a deliberate second squeeze.
        GaLog.i("pen key code=" + keyCode + " action=" + event.getAction()
                + " repeat=" + event.getRepeatCount() + " scan=" + event.getScanCode());

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            switch (keyCode) {
                case XPenKeys.DOUBLE_TAP:
                    cancelPending();
                    bridge.gesture("DOUBLE_TAP");
                    break;
                case XPenKeys.SLIDE_UP:
                    bridge.gesture("SLIDE_UP");
                    break;
                case XPenKeys.SLIDE_DOWN:
                    bridge.gesture("SLIDE_DOWN");
                    break;
                default:
                    onSqueeze(bridge);
                    break;
            }
        }
        // Consume the whole DOWN/UP pair: skip chain.proceed().
        return consumed;
    }

    private static void onSqueeze(AppBridge bridge) {
        long now = SystemClock.uptimeMillis();
        cancelPending();
        if (now - lastSqueezeDownMs <= CompatProfile.DOUBLE_TAP_WINDOW_MS) {
            bridge.gesture("DOUBLE_TAP");
        } else {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    pendingSingle = null;
                    bridge.gesture("SQUEEZE");
                }
            };
            pendingSingle = runnable;
            handler.postDelayed(runnable, CompatProfile.DOUBLE_TAP_WINDOW_MS);
        }
        lastSqueezeDownMs = now;
    }

    private static void cancelPending() {
        Runnable pending = pendingSingle;
        if (pending != null) handler.removeCallbacks(pending);
        pendingSingle = null;
    }

    /**
     * Drops a squeeze that is still inside its double-tap window. Called when
     * the user turns the listener off, so a gesture recognised just before the
     * switch flipped cannot still launch annotation.
     */
    static void cancelPendingGesture() {
        cancelPending();
        lastSqueezeDownMs = 0L;
    }

    private static List<Method> findMethods(Class<?> clazz, String name) {
        List<Method> result = new ArrayList<>();
        addNamed(clazz.getDeclaredMethods(), name, result);
        addNamed(clazz.getMethods(), name, result);
        return result;
    }

    /** Preserves order while dropping duplicates, like Kotlin's distinct(). */
    private static void addNamed(Method[] methods, String name, List<Method> result) {
        for (Method method : methods) {
            if (!method.getName().equals(name)) continue;
            if (!result.contains(method)) result.add(method);
        }
    }

    private static String joinSimpleNames(Class<?>[] types) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(types[i].getSimpleName());
        }
        return builder.toString();
    }
}
