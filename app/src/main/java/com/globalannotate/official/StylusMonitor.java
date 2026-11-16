package com.globalannotate.official;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

/**
 * Root-side stylus gesture monitor, launched with {@code app_process} from the
 * main APK. This is the no-LSPosed replacement for the helper's system_server
 * hook: it installs a privileged gesture monitor (AOSP's SPY window, the same
 * mechanism SystemUI's edge swipe uses), receives every pointer event on the
 * display, and forwards only stylus/eraser events to the app's Binder sink.
 *
 * <p>Finger events are never consumed: they are finished with {@code false},
 * so the InputDispatcher keeps delivering them to the normal target below our
 * transparent overlay. That is 「事件透传」: fingers reach the app below while
 * the pen still draws.</p>
 *
 * <p>Running as uid 0 (via {@code /system/bin/su -c}) satisfies the
 * MONITOR_INPUT check and the app service's ROOT_UID bind check. Hidden
 * framework types are reached reflectively where the SDK lacks them.</p>
 */
public final class StylusMonitor {

    private static final String TAG = "GAStylusMonitor";
    private static final String MONITOR_NAME = "ga-stylus";

    private static Object monitor;            // android.view.InputMonitor
    private static InputEventReceiver receiver;
    private static volatile IBinder appSink;

    private StylusMonitor() {
    }

    public static void main(String[] args) {
        Log.i(TAG, "starting as uid=" + android.os.Process.myUid());
        diag("main uid=" + android.os.Process.myUid());
        Looper.prepareMainLooper();

        final Context app = systemContext();
        if (app == null) {
            Log.e(TAG, "no system context from ActivityThread.systemMain()");
            diag("no system context");
            return;
        }
        diag("got context " + app);

        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                appSink = service;
                Log.i(TAG, "bound to annotation input service");
                startMonitor();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                appSink = null;
                Log.w(TAG, "annotation input service disconnected");
            }
        };

        final Intent intent = new Intent().setComponent(new ComponentName(
                "com.globalannotate.official",
                "com.globalannotate.official.AnnotationInputService"));
        try {
            diag("bindService -> " + app.bindService(intent, connection,
                    Context.BIND_AUTO_CREATE));
        } catch (RuntimeException error) {
            Log.e(TAG, "bindService failed", error);
            diag("bindService threw " + error);
            return;
        }

        diag("entering looper");
        Looper.loop();
        diag("looper exited");
    }

    /** Writes a plain-text trace where adb can read it without logcat tags. */
    private static void diag(String message) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    "/data/local/tmp/ga-monitor-diag.txt", true);
            out.write((System.currentTimeMillis() + " " + message + "\n")
                    .getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
            // Diagnostics are best effort.
        }
    }

    /** ActivityThread.systemMain().getSystemContext() via reflection. */
    private static Context systemContext() {
        try {
            final Class<?> at = Class.forName("android.app.ActivityThread");
            final Object thread = at.getMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable error) {
            Log.e(TAG, "systemContext failed", error);
            return null;
        }
    }

    /** Installs the gesture monitor. All hidden framework calls are reflective. */
    private static void startMonitor() {
        if (monitor != null) {
            return;
        }
        try {
            // InputManagerGlobal.getInstance().monitorGestureInput(name, displayId)
            final Class<?> imGlobal = Class.forName("android.hardware.input.InputManagerGlobal");
            final Object instance = imGlobal.getMethod("getInstance").invoke(null);
            monitor = imGlobal.getMethod("monitorGestureInput", String.class, int.class)
                    .invoke(instance, MONITOR_NAME, 0);
            final Object channel = monitor.getClass().getMethod("getInputChannel")
                    .invoke(monitor);

            receiver = new InputEventReceiver((InputChannel) channel, Looper.myLooper()) {
                @Override
                public void onInputEvent(InputEvent event) {
                    handleEvent(event);
                }
            };
            Log.i(TAG, "gesture monitor installed");
            diag("gesture monitor installed");
        } catch (Throwable error) {
            Log.e(TAG, "cannot install gesture monitor", error);
            diag("startMonitor failed: " + error);
        }
    }

    /**
     * Finishes every event with {@code false} so fingers keep flowing to the
     * app below. Stylus events are copied to the app's Binder sink first.
     */
    private static void handleEvent(InputEvent event) {
        try {
            if (event instanceof MotionEvent) {
                final MotionEvent motion = (MotionEvent) event;
                // MotionEvent.TOOL_TYPE_STYLUS = 3, TOOL_TYPE_ERASER = 4.
                // Constants are inlined here because they are @hidden in the SDK.
                final int tool = motion.getToolType(0);
                if (tool == 3 || tool == 4) {
                    forward(motion);
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "handleEvent failed", error);
        } finally {
            // Never pilfer: the dispatcher must keep the real target too.
            receiver.finishInputEvent(event, false);
        }
    }

    private static void forward(MotionEvent event) {
        final IBinder sink = appSink;
        if (sink == null) {
            return;
        }
        Parcel data = null;
        try {
            data = Parcel.obtain();
            data.writeInterfaceToken(AnnotationInputService.DESCRIPTOR);
            event.writeToParcel(data, 0);
            sink.transact(AnnotationInputService.TX_STYLUS_EVENT, data, null,
                    IBinder.FLAG_ONEWAY);
        } catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "forward stylus failed", error);
        } finally {
            if (data != null) data.recycle();
        }
    }
}
