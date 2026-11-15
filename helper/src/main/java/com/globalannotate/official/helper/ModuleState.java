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

    /** True only when the app is in ROOT_SPLIT mode. */
    public static volatile boolean splitMode = false;

    /** Bound to com.globalannotate.official.AnnotationInputService. */
    public static volatile IBinder sink = null;

    private ModuleState() {
    }
}
