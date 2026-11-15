package com.globalannotate.official.helper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.MotionEvent;

/** Cross-process link from system_server to the main app. */
public final class AppBridge {

    /** Java stand-in for Kotlin's {@code Intent.() -> Unit} fill lambda. */
    private interface IntentFill {
        void fill(Intent intent);
    }

    private final Context context;
    private final Handler handler;

    private boolean bound = false;
    private long lastForwardMs = 0L;
    private long lastActivityFallbackMs = 0L;
    private long fgsRetryAfterMs = 0L;

    // Foreground query for the finger corner summon. Cached briefly because
    // filterInputEvent runs on the input thread for every event.
    private String foregroundPackage;
    private long foregroundCheckedAt = 0L;
    private boolean foregroundQueryLogged = false;
    private String homePackage;
    private boolean homeResolved = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ModuleState.sink = service;
            setSplitActive(ModuleState.splitMode);
            GaLog.i("input sink connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            ModuleState.sink = null;
            bound = false;
            GaLog.i("input sink disconnected; fail-open");
        }
    };

    public AppBridge(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void bindIfNeeded() {
        if (bound) return;
        Intent intent = new Intent().setComponent(
                new ComponentName(XIpc.SERVICE_PACKAGE, XIpc.SERVICE_CLASS));
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            GaLog.e("bindService failed: " + error);
            bound = false;
        }
        if (!bound) GaLog.e("bindService returned false");
    }

    public int[] displaySize() {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    public float displayDensity() {
        return context.getResources().getDisplayMetrics().density;
    }

    /**
     * True while the resolved home (launcher) activity is on top.
     *
     * <p>The finger corner summon only fires on the launcher: the same inward
     * swipe is the system back gesture elsewhere, and opening the annotation
     * layer while also navigating back is not acceptable. The top-level query
     * is cached for half a second because it is called from the input thread
     * on DOWN and then once per MOVE that crosses the threshold.</p>
     */
    public boolean isLauncherForeground() {
        final long now = SystemClock.uptimeMillis();
        if (now - foregroundCheckedAt >= 500L) {
            foregroundCheckedAt = now;
            foregroundPackage = queryForegroundPackage();
        }
        final String home = resolveHomePackage();
        return foregroundPackage != null && home != null
                && foregroundPackage.equals(home);
    }

    private String resolveHomePackage() {
        if (!homeResolved) {
            homeResolved = true;
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME);
                android.content.pm.ResolveInfo info = context.getPackageManager()
                        .resolveActivity(intent,
                                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                if (info != null && info.activityInfo != null) {
                    homePackage = info.activityInfo.packageName;
                    GaLog.i("home package=" + homePackage);
                }
            } catch (Throwable error) {
                GaLog.e("home package lookup failed: " + error);
            }
        }
        return homePackage;
    }

    /**
     * Top activity of the focused root task. Both calls are hidden framework
     * APIs, so they are reflective; a failure only disables the launcher
     * guard (the gesture then stays off) and is logged once.
     */
    private String queryForegroundPackage() {
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object service = atmClass.getMethod("getService").invoke(null);
            if (service == null) return null;
            Object info = service.getClass()
                    .getMethod("getFocusedRootTaskInfo").invoke(service);
            String pkg = topPackageOf(info);
            if (pkg != null) return pkg;
            // Fallback for ROMs where the focused-root query is unavailable:
            // ask for the single most recent task instead.
            Object tasks = service.getClass()
                    .getMethod("getTasks", int.class, boolean.class, boolean.class)
                    .invoke(service, 1, false, false);
            if (tasks instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) tasks;
                if (!list.isEmpty()) return topPackageOf(list.get(0));
            }
        } catch (Throwable error) {
            if (!foregroundQueryLogged) {
                foregroundQueryLogged = true;
                GaLog.e("foreground query failed: " + error);
            }
        }
        return null;
    }

    private static String topPackageOf(Object info) throws Exception {
        if (info == null) return null;
        java.lang.reflect.Field field = info.getClass().getField("topActivity");
        Object value = field.get(info);
        if (value instanceof ComponentName) {
            return ((ComponentName) value).getPackageName();
        }
        return null;
    }

    public float cornerWidthPx() {
        return CompatProfile.CORNER_WIDTH_DP * context.getResources().getDisplayMetrics().density;
    }

    public float cornerHeightPx() {
        return CompatProfile.CORNER_HEIGHT_DP * context.getResources().getDisplayMetrics().density;
    }

    public float swipeThresholdPx() {
        return CompatProfile.SWIPE_THRESHOLD_DP * context.getResources().getDisplayMetrics().density;
    }

    /**
     * Summons the annotation layer.
     *
     * <p>The existing explicit broadcast works while the app process is alive, but
     * a process killed in the background is only woken for the receiver: the
     * receiver's own {@code startForegroundService()} is then rejected on Android
     * 12+ ("Background started FGS: Disallowed"). AppBridge runs in
     * system_server, whose caller UID is exempt from that check:
     * ActiveServices.setFgsRestrictionLocked() grants REASON_SYSTEM_UID (and
     * the persistent process state is PROCESS_STATE_PERSISTENT), so the FGS
     * start below is accepted even when the app is in the background.</p>
     *
     * <p>The fallback is only needed while the app process is gone: the bound
     * AnnotationInputService (ModuleState.sink) is cleared by
     * onServiceDisconnected when the process dies. While it is connected the
     * broadcast above reaches the live receiver and nothing else is started.
     * Otherwise the service is started directly from system_server (accepted
     * for the system UID even when the app is in the background); that is
     * idempotent if the broadcast has just woken the process but the service
     * is not up yet. Only the transparent-activity last resort is rate
     * limited, because starting it shows a (brief) window transition.</p>
     */
    public void launch() {
        broadcast(XActions.LAUNCH, null);
        GaLog.i("launch: broadcast dispatched");
        handler.removeCallbacks(launchFallback);
        handler.postDelayed(launchFallback, CompatProfile.LAUNCH_FALLBACK_DELAY_MS);
    }

    private final Runnable launchFallback = new Runnable() {
        @Override
        public void run() {
            runLaunchFallback();
        }
    };

    private void runLaunchFallback() {
        if (!ModuleState.enabled) {
            GaLog.i("launch fallback skipped: module disabled");
            return;
        }
        if (ModuleState.sink != null) {
            // The app process is alive and bound; the broadcast path already
            // delivered LAUNCH (or the receiver will handle it).
            GaLog.i("launch fallback skipped: app process bound");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now >= fgsRetryAfterMs) {
            Intent service = new Intent()
                    .setComponent(new ComponentName(XActions.APP_PACKAGE, XActions.APP_SERVICE_CLASS))
                    .setAction(XActions.LAUNCH)
                    .putExtra(XActions.EXTRA_TOKEN, XActions.TOKEN);
            Throwable failure = null;
            ComponentName started = null;
            try {
                started = context.startForegroundService(service);
            } catch (Throwable error) {
                failure = error;
            }
            if (started != null) {
                GaLog.i("launch fallback: startForegroundService -> " + started);
                return;
            }
            // Do not hammer a refused start: every denial can post a
            // user-visible "background FGS restricted" notification.
            fgsRetryAfterMs = now + CompatProfile.LAUNCH_FGS_RETRY_MS;
            if (failure != null) {
                GaLog.e("launch fallback: startForegroundService threw: " + failure);
            } else {
                GaLog.e("launch fallback: startForegroundService returned null (denied or missing)");
            }
        } else {
            GaLog.i("launch fallback: service start suppressed for " + (fgsRetryAfterMs - now) + "ms");
        }
        startMainActivity();
    }

    /**
     * Last resort for ROMs that also block a system-UID FGS start. MainActivity
     * is transparent, starts the service from an activity (always allowed) and
     * finishes immediately; the cooldown keeps repeated swipes from flashing it.
     */
    private void startMainActivity() {
        long now = SystemClock.uptimeMillis();
        if (now - lastActivityFallbackMs < CompatProfile.LAUNCH_ACTIVITY_COOLDOWN_MS) {
            GaLog.i("launch fallback: MainActivity skipped (cooldown)");
            return;
        }
        lastActivityFallbackMs = now;
        Intent activity = new Intent()
                .setComponent(new ComponentName(XActions.APP_PACKAGE, XActions.MAIN_ACTIVITY_CLASS))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                // MainActivity only starts the service and finishes; the extra
                // keeps the launcher screen from appearing.
                .putExtra("com.globalannotate.extra.AUTOSTART", true);
        try {
            context.startActivity(activity);
            GaLog.i("launch fallback: MainActivity start requested");
        } catch (Throwable error) {
            GaLog.e("launch fallback: MainActivity start failed: " + error);
        }
    }

    public void gesture(String name) {
        broadcast(XActions.GESTURE, new IntentFill() {
            @Override
            public void fill(Intent intent) {
                intent.putExtra(XActions.EXTRA_GESTURE, name);
            }
        });
    }

    private void broadcast(String action, IntentFill fill) {
        Intent intent = new Intent(action)
                .setClassName(XIpc.SERVICE_PACKAGE, XActions.RECEIVER_CLASS)
                .putExtra(XActions.EXTRA_TOKEN, XActions.TOKEN)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (fill != null) fill.fill(intent);
        try {
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            GaLog.e("broadcast " + action + " failed: " + error);
        }
    }

    public void setSplitActive(boolean active) {
        IBinder binder = ModuleState.sink;
        if (binder == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(XIpc.DESCRIPTOR);
            data.writeInt(active ? 1 : 0);
            binder.transact(XIpc.TX_SET_ACTIVE, data, reply, 0);
            reply.readException();
        } catch (Throwable error) {
            GaLog.e("setSplitActive failed: " + error);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * One-way Binder transaction: filterInputEvent runs on an input thread, so
     * this must never block waiting for the app. MotionEvent is copied first
     * because the framework recycles the original right after dispatch.
     */
    public void forwardStylus(MotionEvent event) {
        IBinder binder = ModuleState.sink;
        if (binder == null) return;
        long now = SystemClock.uptimeMillis();
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                && now - lastForwardMs < CompatProfile.FORWARD_INTERVAL_MS) {
            return;
        }
        lastForwardMs = now;

        MotionEvent copy = MotionEvent.obtain(event);
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(XIpc.DESCRIPTOR);
            copy.writeToParcel(data, 0);
            binder.transact(XIpc.TX_STYLUS_EVENT, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable error) {
            GaLog.e("forwardStylus failed: " + error);
            ModuleState.sink = null; // fail open
        } finally {
            copy.recycle();
            data.recycle();
        }
    }
}
