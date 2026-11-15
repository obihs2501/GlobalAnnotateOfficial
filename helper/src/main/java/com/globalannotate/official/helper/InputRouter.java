package com.globalannotate.official.helper;

import android.os.SystemClock;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Two jobs in the system input filter (InputManagerService.filterInputEvent):
 *
 * <p>1. <b>Observe-only</b> corner gesture: a stylus that starts in the top-right
 * region and moves inward summons annotation. The event is never consumed
 * because of it, so no area of the screen becomes a dead zone.
 * 2. toolType split (ROOT_SPLIT): ONLY in that mode are stylus events copied to
 * the app's Binder sink and dropped from normal dispatch. Finger events are
 * always untouched.</p>
 *
 * <p>"Dropped" must match the hooked method's return type, so the value is derived
 * from the real signature (boolean false = drop, int 0 = handled, void = null).</p>
 */
public final class InputRouter {

    private static boolean armed = false;
    private static boolean fired = false;

    /**
     * True once the start of the current gesture has been observed. Some ROMs
     * start a hover stream with HOVER_MOVE and no HOVER_ENTER, so the first
     * event of the stream has to be treated as its start.
     */
    private static boolean tracked = false;
    private static float startX = 0f;
    private static float startY = 0f;
    private static long lastLaunchMs = 0L;

    /**
     * Finger corner-swipe state. Separate from the pen observation so a finger
     * stream and the existing pen-tip stream cannot clobber each other.
     */
    private static boolean fingerTracked = false;
    private static boolean fingerArmed = false;
    private static boolean fingerFired = false;
    private static float fingerStartX = 0f;
    private static float fingerStartY = 0f;

    private InputRouter() {
    }

    public static void install(XposedInterface api, ClassLoader classLoader, AppBridge bridge) {
        int hooked = 0;
        // filterInputEvent can drop events, so ROOT_SPLIT forwarding lives here.
        hooked += hook(api, classLoader, bridge, CompatProfile.INPUT_SERVICE_CLASSES,
                CompatProfile.FILTER_METHOD, true);
        // The system gesture listener sees every touch stream because
        // DisplayPolicy registers it for the whole display. filterInputEvent
        // above is only called by the native dispatcher while an input filter
        // is installed, so on this ROM it never sees a finger and the corner
        // summon was dead.
        hooked += hook(api, classLoader, bridge, CompatProfile.POINTER_EVENT_CLASSES,
                CompatProfile.POINTER_EVENT_METHOD, false);
        if (hooked == 0) {
            GaLog.e("no input hook found; ROOT_SPLIT and corner swipe disabled");
        }
    }

    /** Hooks every match of {@code methodName} in the list and returns how many. */
    private static int hook(XposedInterface api, ClassLoader classLoader, AppBridge bridge,
                            List<String> classNames, String methodName, boolean canFilter) {
        int hooked = 0;
        for (String className : classNames) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className, false, classLoader);
            } catch (Throwable error) {
                continue;
            }
            for (Method method : findMethods(clazz, methodName)) {
                final Object dropped = droppedValueFor(method);
                boolean ok;
                try {
                    method.setAccessible(true);
                    api.hook(method).intercept(chain -> {
                        try {
                            return handle(chain, bridge, dropped, canFilter);
                        } catch (Throwable error) {
                            GaLog.e("input router error: " + error);
                            return chain.proceed();
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
        return hooked;
    }

    /** Value that makes the framework drop the event, matching the signature. */
    private static Object droppedValueFor(Method method) {
        Class<?> type = method.getReturnType();
        if (type == Boolean.TYPE || type == Boolean.class) return Boolean.FALSE;
        if (type == Integer.TYPE || type == Integer.class) return Integer.valueOf(0);
        if (type == Long.TYPE || type == Long.class) return Long.valueOf(0L);
        return null;
    }

    private static Object handle(XposedInterface.Chain chain, AppBridge bridge, Object dropped,
                                 boolean canFilter) throws Throwable {
        if (!ModuleState.enabled) return chain.proceed();
        List<Object> args = chain.getArgs();
        Object first = args.isEmpty() ? null : args.get(0);
        if (!(first instanceof MotionEvent)) return chain.proceed();
        MotionEvent event = (MotionEvent) first;
        if (event.getPointerCount() == 0) return chain.proceed();
        int tool = event.getToolType(0);
        boolean isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS
                || tool == MotionEvent.TOOL_TYPE_ERASER;

        // Observation only — never consumes because of the corner gesture.
        if (isStylus) {
            observeCorner(event, bridge);
        } else if (tool == MotionEvent.TOOL_TYPE_FINGER) {
            // Huawei-style summon: an inward horizontal swipe from the
            // top-right or bottom-right corner while the launcher is showing.
            // Finger events are never dropped; the gesture only launches the
            // overlay. Only a horizontally dominant swipe fires, so a downward
            // pull from the top-right still opens the control centre.
            observeFingerCorner(event, bridge);
        }

        // Forwarding is only legal where the event can be dropped
        // (filterInputEvent). onPointerEvent is a void listener that must not
        // own the stream; were both live, forwarding twice would duplicate
        // every pen sample.
        if (canFilter && ModuleState.splitMode && ModuleState.sink != null && isStylus) {
            bridge.forwardStylus(event);
            return dropped; // pen is intentionally routed to the annotation layer
        }
        return chain.proceed();
    }

    /**
     * Finger counterpart of {@link #observeCorner(MotionEvent, AppBridge)}.
     * Only an inward, horizontally dominant swipe fires, so a vertical pull
     * from the top-right (control centre) still reaches the system untouched.
     * The launcher check keeps the right-edge back gesture in ordinary apps
     * from opening the annotation layer.
     */
    private static void observeFingerCorner(MotionEvent event, AppBridge bridge) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginFingerGesture(event, bridge);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!fingerTracked) {
                    beginFingerGesture(event, bridge);
                    return;
                }
                if (!fingerArmed || fingerFired) return;
                final float inward = fingerStartX - event.getX();
                final float vertical = Math.abs(event.getY() - fingerStartY);
                if (inward <= CompatProfile.SWIPE_THRESHOLD_DP * bridge.displayDensity()) {
                    return;
                }
                // Horizontal-dominant: a diagonal down-left drag from the
                // top-right must not steal the control-centre pull.
                if (inward < vertical) return;
                final long now = SystemClock.uptimeMillis();
                if (now - lastLaunchMs < CompatProfile.CORNER_COOLDOWN_MS) return;
                fingerFired = true;
                if (!bridge.isLauncherForeground()) {
                    GaLog.i("finger corner ignored: launcher not foreground");
                    return;
                }
                lastLaunchMs = now;
                GaLog.i("finger corner swipe fired: inward=" + (int) inward + "px");
                bridge.launch();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                fingerArmed = false;
                fingerFired = false;
                fingerTracked = false;
                break;
            default:
                break;
        }
    }

    private static void beginFingerGesture(MotionEvent event, AppBridge bridge) {
        final int[] size = bridge.displaySize();
        final int screenWidth = size[0];
        final int screenHeight = size[1];
        final float density = bridge.displayDensity();
        final float inset = CompatProfile.FINGER_CORNER_INSET_DP * density;
        final float width = Math.min(CompatProfile.CORNER_WIDTH_DP * density, screenWidth);
        final float height = Math.min(CompatProfile.FINGER_CORNER_HEIGHT_DP * density,
                screenHeight);
        final float x = event.getX();
        final float y = event.getY();

        fingerTracked = true;
        fingerFired = false;
        fingerStartX = x;
        fingerStartY = y;
        final boolean rightEdge = x >= screenWidth - width;
        final boolean topRight = rightEdge && y >= inset && y <= inset + height;
        final boolean bottomRight = rightEdge
                && y >= screenHeight - inset - height
                && y <= screenHeight - inset;
        fingerArmed = topRight || bottomRight;
        if (fingerArmed) {
            GaLog.i("finger corner armed at (" + (int) x + "," + (int) y + ") "
                    + (topRight ? "top-right" : "bottom-right")
                    + " screen=" + screenWidth + "x" + screenHeight);
        }
    }

    private static void observeCorner(MotionEvent event, AppBridge bridge) {
        int[] size = bridge.displaySize();
        int screenWidth = size[0];
        int screenHeight = size[1];
        float cornerW = Math.min(bridge.cornerWidthPx(), (float) screenWidth);
        float cornerH = Math.min(bridge.cornerHeightPx(), (float) screenHeight);
        float threshold = bridge.swipeThresholdPx();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_HOVER_ENTER:
                beginGesture(event, screenWidth, screenHeight, cornerW, cornerH);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (!tracked) {
                    // No DOWN / HOVER_ENTER preceded this stream: use the first
                    // seen point as the start instead of silently ignoring it.
                    beginGesture(event, screenWidth, screenHeight, cornerW, cornerH);
                    return;
                }
                if (!armed || fired) return;
                float inward = Math.max(startX - event.getX(), event.getY() - startY);
                if (inward <= threshold) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastLaunchMs < CompatProfile.CORNER_COOLDOWN_MS) {
                    GaLog.i("corner swipe ignored: cooldown");
                    return;
                }
                lastLaunchMs = now;
                fired = true;
                GaLog.i("corner swipe fired: inward=" + (int) inward + "px");
                bridge.launch();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_HOVER_EXIT:
                armed = false;
                fired = false;
                tracked = false;
                break;
            default:
                break;
        }
    }

    /**
     * Arms the top-right observation corner for a new gesture. The event is
     * still forwarded normally; arming only makes the passive swipe detector
     * eligible to fire.
     */
    private static void beginGesture(MotionEvent event, int screenWidth, int screenHeight,
                                     float cornerW, float cornerH) {
        tracked = true;
        fired = false;
        startX = event.getX();
        startY = event.getY();
        armed = event.getX() >= screenWidth - cornerW && event.getY() <= cornerH;
        if (armed) {
            GaLog.i("corner armed at (" + (int) event.getX() + "," + (int) event.getY() + ") "
                    + "screen=" + screenWidth + "x" + screenHeight);
        }
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
