package com.globalannotate.official;

import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

import java.io.DataOutputStream;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

/**
 * Root-side stylus gesture monitor, launched with {@code app_process} from the
 * main APK. This is the no-LSPosed replacement for the helper's system_server
 * hook: it installs a privileged gesture monitor (AOSP's SPY window, the same
 * mechanism SystemUI's edge swipe uses) and forwards only stylus/eraser events
 * to the app over an abstract Unix socket.
 *
 * <p>Finger events are never consumed: they are finished with {@code false},
 * so the InputDispatcher keeps delivering them to the normal target below our
 * transparent overlay. That is 「事件透传」: fingers reach the app below while
 * the pen still draws.</p>
 *
 * <p>A socket is used instead of binding the app's Binder service because an
 * app_process has no registered application thread, so ActivityManager refuses
 * bindService. The root process only needs to write to an abstract socket the
 * app creates — no permission, no bind, no system_server hook.</p>
 */
public final class StylusMonitor {

    private static final String TAG = "GAStylusMonitor";
    private static final String MONITOR_NAME = "ga-stylus";
    /** Must match FloatingService.STYLUS_SOCKET_NAME. */
    private static final String SOCKET_NAME = "globalannotate.stylus";

    private static Object monitor;            // android.view.InputMonitor
    private static InputEventReceiver receiver;
    private static LocalSocket socket;
    private static DataOutputStream out;

    private StylusMonitor() {
    }

    public static void main(String[] args) {
        Log.i(TAG, "starting as uid=" + android.os.Process.myUid());
        diag("main uid=" + android.os.Process.myUid());
        Looper.prepareMainLooper();

        if (!connect()) {
            diag("cannot connect to app socket");
            return;
        }
        if (!installMonitor()) {
            diag("cannot install gesture monitor");
            return;
        }

        diag("entering looper");
        Looper.loop();
    }

    private static boolean connect() {
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT));
            out = new DataOutputStream(socket.getOutputStream());
            diag("connected to " + SOCKET_NAME);
            return true;
        } catch (Throwable error) {
            diag("connect failed: " + error);
            return false;
        }
    }

    /** Installs the gesture monitor. All hidden framework calls are reflective. */
    private static boolean installMonitor() {
        try {
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
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "cannot install gesture monitor", error);
            diag("installMonitor failed: " + error);
            return false;
        }
    }

    /** Finishes every event unhandled so fingers keep flowing to the app below. */
    private static void handleEvent(InputEvent event) {
        try {
            if (event instanceof MotionEvent) {
                final MotionEvent motion = (MotionEvent) event;
                // MotionEvent.TOOL_TYPE_STYLUS = 3, TOOL_TYPE_ERASER = 4
                // (hidden constants in the SDK).
                final int tool = motion.getToolType(0);
                final int action = motion.getActionMasked();
                diag("event tool=" + tool + " action=" + action
                        + " src=" + motion.getSource());
                if (tool == 3 || tool == 4 || tool == 1 || tool == 2) {  // DIAG: accept all
                    forward(motion);
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "handleEvent failed", error);
        } finally {
            receiver.finishInputEvent(event, false);
        }
    }

    /** Writes one length-prefixed parceled MotionEvent to the app socket. */
    private static void forward(MotionEvent event) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            event.writeToParcel(parcel, 0);
            final byte[] bytes = parcel.marshall();
            synchronized (StylusMonitor.class) {
                out.writeInt(bytes.length);
                out.write(bytes);
                out.flush();
            }
        } catch (Throwable error) {
            Log.w(TAG, "forward stylus failed", error);
            diag("forward failed: " + error);
        } finally {
            if (parcel != null) parcel.recycle();
        }
    }

    private static void diag(String message) {
        try {
            java.io.FileOutputStream outFile = new java.io.FileOutputStream(
                    "/data/local/tmp/ga-monitor-diag.txt", true);
            outFile.write((System.currentTimeMillis() + " " + message + "\n")
                    .getBytes("UTF-8"));
            outFile.close();
        } catch (Throwable ignored) {
            // Diagnostics are best effort.
        }
    }
}
