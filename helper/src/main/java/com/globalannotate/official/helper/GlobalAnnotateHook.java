package com.globalannotate.official.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * libxposed API 102 entry point.
 *
 * <p>Registered in META-INF/xposed/java_init.list; scope.list contains
 * {@code system}, so the framework injects us into system_server and calls
 * {@link #onSystemServerStarting} with the system classloader.</p>
 */
public final class GlobalAnnotateHook extends XposedModule {

    private static final String TAG = "GlobalAnnotate";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        GaLog.setSink((priority, tag, message) -> log(priority, tag, message));
        log(Log.INFO, TAG, "loaded: process=" + param.getProcessName()
                + ", system=" + param.isSystemServer() + ", api=" + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        ClassLoader classLoader = param.getClassLoader();
        log(Log.INFO, TAG, "system server starting; classloader=" + classLoader);
        Context context = systemContext();
        if (context == null) {
            log(Log.ERROR, TAG, "system Context unavailable; hooks not installed");
            return;
        }
        AppBridge bridge = new AppBridge(context);
        registerControlReceiver(context, bridge);
        GestureLauncher.install(this, classLoader, bridge);
        InputRouter.install(this, classLoader, bridge);
        log(Log.INFO, TAG, "installed; idle until SET_MODULE_ENABLED");
    }

    /**
     * system_server has no Application object, so {@code currentApplication()} is
     * usually null here. Try the system Context paths that actually work in
     * this process, newest first.
     */
    private Context systemContext() {
        Class<?> activityThread;
        try {
            activityThread = Class.forName("android.app.ActivityThread");
        } catch (Throwable error) {
            return null;
        }

        try {
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object current = currentActivityThread.invoke(null);
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object systemContext = getSystemContext.invoke(current);
            if (systemContext instanceof Context) {
                log(Log.INFO, TAG, "context from ActivityThread.getSystemContext()");
                return (Context) systemContext;
            }
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "getSystemContext failed: " + error);
        }

        try {
            Method systemMain = activityThread.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object main = systemMain.invoke(null);
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object context = getSystemContext.invoke(main);
            if (context instanceof Context) {
                log(Log.INFO, TAG, "context from ActivityThread.systemMain()");
                return (Context) context;
            }
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "systemMain failed: " + error);
        }

        try {
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            if (app instanceof Context) {
                log(Log.INFO, TAG, "context from ActivityThread.currentApplication()");
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // Kotlin's original runCatching had no onFailure handler here.
        }

        return null;
    }

    private void registerControlReceiver(Context context, AppBridge bridge) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!XActions.TOKEN.equals(intent.getStringExtra(XActions.EXTRA_TOKEN))) return;
                boolean enabled = intent.getBooleanExtra(XActions.EXTRA_ENABLED, false);
                String action = intent.getAction();
                if (action == null) return;
                if (XActions.SET_MODULE_ENABLED.equals(action)) {
                    ModuleState.enabled = enabled;
                    if (enabled) bridge.bindIfNeeded();
                    if (!enabled) {
                        ModuleState.splitMode = false;
                        bridge.setSplitActive(false);
                    }
                    log(Log.INFO, TAG, "module enabled=" + enabled);
                } else if (XActions.SET_SPLIT_MODE.equals(action)) {
                    ModuleState.splitMode = enabled;
                    // ROOT_SPLIT needs the Binder sink. Binding here as well
                    // means the switch works even when the overlay was started
                    // from MainActivity, which never sends SET_MODULE_ENABLED.
                    if (enabled) bridge.bindIfNeeded();
                    bridge.setSplitActive(enabled);
                    log(Log.INFO, TAG, "split mode=" + enabled);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(XActions.SET_MODULE_ENABLED);
        filter.addAction(XActions.SET_SPLIT_MODE);

        // onSystemServerStarting runs before ActivityManagerService is
        // published. Context.registerReceiver() goes through IActivityManager,
        // which is still null there: on this ROM the call threw
        // "registerReceiverWithFeature ... on a null object reference", so the
        // receiver never existed and neither SET_MODULE_ENABLED nor
        // SET_SPLIT_MODE ever reached the module (ROOT_SPLIT stayed dead).
        // The main looper only starts dispatching after system_server has
        // finished starting, so posting one registration is enough; a short
        // retry covers a service that is still being published.
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                try {
                    context.registerReceiver(receiver, filter, null, handler,
                            Context.RECEIVER_EXPORTED);
                    log(Log.INFO, TAG, "control receiver registered");
                } catch (Throwable error) {
                    attempts++;
                    if (attempts < 50) {
                        log(Log.WARN, TAG, "registerReceiver retry " + attempts
                                + ": " + error);
                        handler.postDelayed(this, 200L);
                    } else {
                        log(Log.ERROR, TAG, "registerReceiver failed: " + error);
                    }
                }
            }
        });
    }
}
