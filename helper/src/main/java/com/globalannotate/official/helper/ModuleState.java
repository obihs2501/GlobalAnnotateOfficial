package com.globalannotate.official.helper;

import android.os.IBinder;

/** Lives in the {@code android} (system_server) process. */
public final class ModuleState {
    /**
     * The hooks are only useful while the annotation layer runs, but a missed
     * "enable" broadcast used to leave the whole module dead. Default to on and
     * let the app switch it off when it stops; every hook is fail-open anyway.
     */
    public static volatile boolean enabled = true;

    /**
     * Whether the module intercepts pen-body gestures (squeeze, double tap,
     * slide). Mirrors the Helper settings switch; loaded from
     * {@link ModulePrefs} when system_server starts, and kept up to date by the
     * SET_PEN_GESTURES broadcast. When false the whole gesture stream is left
     * to the ROM and the foreground app, so the pen's own shortcuts work.
     */
    public static volatile boolean penGesturesEnabled = true;

    /** True only when the app is in ROOT_SPLIT mode. */
    public static volatile boolean splitMode = false;

    /** Bound to com.globalannotate.official.AnnotationInputService. */
    public static volatile IBinder sink = null;

    private ModuleState() {
    }
}
