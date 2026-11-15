package com.globalannotate.official;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

/**
 * Bound Binder sink for the stylus events the LSPosed helper reroutes from
 * {@code system_server} while ROOT_SPLIT mode is active.
 *
 * <p>Protocol (duplicated from the helper's {@code XIpc}, the two APKs are
 * independent): descriptor {@value #DESCRIPTOR}, transaction
 * {@link #TX_STYLUS_EVENT} = interface token + {@code MotionEvent} parcel,
 * transaction {@link #TX_SET_ACTIVE} = interface token + one int (1 = split
 * mode on). The service is exported because {@code system_server} binds it; it
 * declares no permission, matching the helper, but {@link #onBind(Intent)}
 * additionally accepts only the system, root, shell and own UIDs.</p>
 *
 * <p>Events are delivered to a same-process {@link InputSink}. Until a sink
 * registers (normally FloatingService, which this class may not modify), the
 * events are dropped and recycled: they are never queued, so a missing sink
 * cannot pin MotionEvent pool slots. Registration is one line for the overlay
 * owner: {@code AnnotationInputService.setInputSink(this)}.</p>
 */
public final class AnnotationInputService extends Service {

    private static final String TAG = "GaAnnotationInput";

    /** Kept byte-identical to the helper's {@code XIpc}. */
    public static final String DESCRIPTOR = "com.globalannotate.ipc.IAnnotationInput";
    public static final int TX_STYLUS_EVENT = 1;
    public static final int TX_SET_ACTIVE = 2;

    private Handler mainHandler;
    private final InputBinder binder = new InputBinder();

    /**
     * Same-process consumer of the rerouted stylus stream. Both methods run
     * on the main thread.
     *
     * <p>{@link #onStylusEvent(MotionEvent)} receives an event that is valid
     * only for the duration of the call: the service recycles it as soon as
     * the method returns, so a consumer that needs it later must copy it with
     * {@link MotionEvent#obtain(MotionEvent)}. Reusing the event synchronously
     * (for example feeding an AnnotationView) needs no copy.</p>
     */
    public interface InputSink {
        void onStylusEvent(MotionEvent event);

        void onSplitActiveChanged(boolean active);
    }

    /** Registers (or clears, with {@code null}) the overlay's input sink. */
    public static void setInputSink(InputSink sink) {
        InputBridge.setSink(sink);
    }

    /**
     * Publishes the overlay instance for diagnostics and for the
     * {@code FloatingService == null} guard in the bridge. The overlay owner
     * should set this in {@code onCreate} and clear it in {@code onDestroy}.
     */
    public static void setFloatingService(FloatingService service) {
        InputBridge.floatingService = service;
    }

    /** True while the helper's ROOT_SPLIT routing is switched on. */
    public static boolean isSplitActive() {
        return InputBridge.splitActive;
    }

    /**
     * Mirrors a split-mode switch that arrives by broadcast instead of by
     * {@link #TX_SET_ACTIVE}. Safe before the service is created.
     */
    public static void setSplitActive(boolean active) {
        InputBridge.setSplitActive(active);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        InputBridge.attach(mainHandler);
        Log.i(TAG, "created");
    }

    @Override
    public void onDestroy() {
        InputBridge.detach();
        Log.i(TAG, "destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != Process.ROOT_UID
                && uid != Process.SHELL_UID && uid != Process.myUid()) {
            Log.w(TAG, "bind rejected for uid " + uid);
            return null;
        }
        return binder;
    }

    private final class InputBinder extends Binder {

        InputBinder() {
            // Makes getInterfaceDescriptor() answer with the helper's
            // descriptor and lets Binder handle INTERFACE_TRANSACTION.
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                if (code == TX_STYLUS_EVENT) {
                    data.enforceInterface(DESCRIPTOR);
                    final MotionEvent event = MotionEvent.CREATOR.createFromParcel(data);
                    InputBridge.postStylus(event);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                if (code == TX_SET_ACTIVE) {
                    data.enforceInterface(DESCRIPTOR);
                    final boolean active = data.readInt() != 0;
                    InputBridge.setSplitActive(active);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } catch (Exception error) {
                // A malformed parcel or a rogue caller must not take the app
                // process down. The one-way stylus path has no reply parcel at
                // all; the synchronous set-active path gets the exception.
                Log.w(TAG, "transaction " + code + " failed", error);
                if (reply != null) {
                    try {
                        reply.writeException(error);
                    } catch (RuntimeException ignored) {
                        // The reply parcel is already unusable.
                    }
                }
                return true;
            }
        }
    }
}

/**
 * Static seam between {@link AnnotationInputService} and the in-process
 * overlay that consumes rerouted stylus input.
 *
 * <p>The bound service hands every transaction here instead of calling into
 * FloatingService directly: this file may not modify FloatingService, and a
 * static sink keeps the IPC layer independent of the overlay implementation.
 * All state is updated on the Binder thread and every consumer call is posted
 * to the main thread.</p>
 */
final class InputBridge {

    private static final String TAG = "GaAnnotationInput";

    /** Null until the overlay registers itself. */
    static volatile AnnotationInputService.InputSink sink;

    /** Diagnostic / liveness reference; may stay null if the overlay opts out. */
    static volatile FloatingService floatingService;

    /** True only while the helper's ROOT_SPLIT routing is on. */
    static volatile boolean splitActive;

    private static volatile Handler mainHandler;

    /** Stylus touch currently in flight, used to synthesize ACTION_CANCEL. */
    private static final TouchState TOUCH = new TouchState();

    /**
     * After a cancel, the module can still deliver the tail of the old
     * stroke; drop everything until the next DOWN.
     */
    private static boolean ignoreUntilDown;

    private static boolean missingSinkLogged;

    private InputBridge() {
    }

    static void attach(Handler handler) {
        mainHandler = handler;
    }

    static void detach() {
        mainHandler = null;
        splitActive = false;
        synchronized (TOUCH) {
            TOUCH.inFlight = false;
            ignoreUntilDown = false;
        }
    }

    static void setSink(AnnotationInputService.InputSink newSink) {
        sink = newSink;
        if (newSink != null) {
            final boolean active = splitActive;
            post(new Runnable() {
                @Override
                public void run() {
                    if (sink == newSink) {
                        newSink.onSplitActiveChanged(active);
                    }
                }
            });
        }
    }

    /**
     * Called on the Binder thread. Ownership of {@code event} moves here: it
     * is recycled after the sink call, or immediately when no handler exists
     * or the post fails (a Runnable that never runs cannot recycle it).
     */
    static void postStylus(MotionEvent event) {
        if (event == null) {
            return;
        }
        final Handler handler = mainHandler;
        if (handler == null) {
            event.recycle();
            return;
        }
        final boolean posted = handler.post(new Runnable() {
            @Override
            public void run() {
                deliver(event);
            }
        });
        if (!posted) {
            event.recycle();
        }
    }

    private static void deliver(MotionEvent event) {
        try {
            if (!splitActive) {
                return;
            }
            final int action = event.getActionMasked();
            final boolean hover = action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_MOVE
                    || action == MotionEvent.ACTION_HOVER_EXIT;
            synchronized (TOUCH) {
                if (ignoreUntilDown && !hover && action != MotionEvent.ACTION_DOWN) {
                    return;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    ignoreUntilDown = false;
                }
                rememberLocked(event, action);
            }

            final AnnotationInputService.InputSink target = sink;
            if (target == null) {
                logMissingSink();
                return;
            }
            try {
                target.onStylusEvent(event);
            } catch (RuntimeException error) {
                Log.w(TAG, "stylus sink threw", error);
            }
        } finally {
            event.recycle();
        }
    }

    /** Keeps {@link #TOUCH} in sync; caller holds the TOUCH monitor. */
    private static void rememberLocked(MotionEvent event, int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                TOUCH.inFlight = true;
                captureLocked(event);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                if (TOUCH.inFlight) {
                    captureLocked(event);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                TOUCH.inFlight = false;
                break;
            default:
                break;
        }
    }

    /** Caller holds the TOUCH monitor. */
    private static void captureLocked(MotionEvent event) {
        TOUCH.downTime = event.getDownTime();
        TOUCH.x = event.getX();
        TOUCH.y = event.getY();
        TOUCH.metaState = event.getMetaState();
        TOUCH.source = event.getSource();
    }

    /**
     * Updates the routing switch and, when it turns off mid-stroke, queues a
     * synthetic ACTION_CANCEL so the overlay does not keep an ink stroke alive
     * on stale coordinates for good.
     */
    static void setSplitActive(boolean active) {
        final boolean wasActive = splitActive;
        splitActive = active;

        MotionEvent cancel = null;
        if (wasActive && !active) {
            synchronized (TOUCH) {
                if (TOUCH.inFlight) {
                    TOUCH.inFlight = false;
                    ignoreUntilDown = true;
                    cancel = MotionEvent.obtain(TOUCH.downTime, SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_CANCEL, TOUCH.x, TOUCH.y, TOUCH.metaState);
                    // Cancellation only needs the action and its position; keep
                    // the source so the overlay sees a stylus-shaped event.
                    // MotionEvent.setDeviceId() is hidden on newer platforms, so
                    // the device id is deliberately left unset.
                    cancel.setSource(TOUCH.source);
                }
            }
        }

        final AnnotationInputService.InputSink target = sink;
        final MotionEvent cancelEvent = cancel;
        final Runnable notify = new Runnable() {
            @Override
            public void run() {
                if (target != null && sink == target) {
                    try {
                        target.onSplitActiveChanged(active);
                    } catch (RuntimeException error) {
                        Log.w(TAG, "split callback threw", error);
                    }
                    if (cancelEvent != null) {
                        try {
                            target.onStylusEvent(cancelEvent);
                        } catch (RuntimeException error) {
                            Log.w(TAG, "cancel delivery threw", error);
                        }
                    }
                }
                if (cancelEvent != null) {
                    cancelEvent.recycle();
                }
            }
        };

        if (cancelEvent == null) {
            post(notify);
            return;
        }
        final Handler handler = mainHandler;
        final boolean posted = handler != null && handler.post(notify);
        if (!posted) {
            // The notify runnable never runs, so nothing else can recycle the
            // synthetic event.
            cancelEvent.recycle();
        }
    }

    private static void post(Runnable runnable) {
        final Handler handler = mainHandler;
        if (handler != null) {
            handler.post(runnable);
        }
    }

    private static void logMissingSink() {
        if (missingSinkLogged) {
            return;
        }
        missingSinkLogged = true;
        Log.w(TAG, "stylus event dropped: no input sink registered (FloatingService="
                + floatingService + ")");
    }

    /** Stylus stream state shared with the cancel-on-disable path. */
    private static final class TouchState {
        boolean inFlight;
        long downTime;
        float x;
        float y;
        int metaState;
        int source;
    }
}
