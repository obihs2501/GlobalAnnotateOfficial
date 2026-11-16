package com.globalannotate.official;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/** Registers this app for Xiaomi Focus Pen Touch Film key events without root. */
final class FocusPenSystemLink {
    interface Listener {
        void onConnectionChanged(boolean connected);
    }

    private static final String TAG = "GAFocusPenLink";
    private static final String SERVICE_PACKAGE = "com.xiaomi.touchservice";
    private static final String SERVICE_CLASS =
            "com.xiaomi.touchservice.pencilengine.PencilEngineManagerService";
    private static final String DESCRIPTOR =
            "com.xiaomi.touchservice.pencilengine.IPencilEngine";
    private static final String CALLBACK_DESCRIPTOR =
            "com.xiaomi.touchservice.pencilengine.IPencilEngineCallback";
    private static final int TX_REGISTER = 1;
    private static final int TX_UNREGISTER = 2;
    private static final int TX_SET_ENABLE = 3;
    private static final int TX_REGISTER_WITH_PACKAGE = 4;
    private static final int FEATURE_TOUCH_FILM = 2;
    private static final long BASE_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 30000L;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IBinder callback = new Binder() {
        {
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                data.readInt();
                data.readInt();
                data.readInt();
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            disconnect(true);
        }
    };

    private final Runnable rebind = new Runnable() {
        @Override
        public void run() {
            if (wanted && !bound && !binding) bind();
        }
    };
    private final Runnable retryConnected = new Runnable() {
        @Override
        public void run() {
            final IBinder binder = remote;
            if (!wanted || binder == null) return;
            final Integer result = transactSetEnable(binder, 1);
            if (result != null && result == 0) {
                setConnected(true);
                handler.postDelayed(retryConnected, 3000L);
            } else {
                setConnected(false);
                scheduleRetryConnected();
            }
        }
    };

    private void scheduleRetryConnected() {
        handler.removeCallbacks(retryConnected);
        final long delay = Math.min(MAX_RECONNECT_DELAY_MS,
                BASE_RECONNECT_DELAY_MS << Math.min(reconnectAttempt, 5));
        reconnectAttempt = Math.min(reconnectAttempt + 1, 5);
        handler.postDelayed(retryConnected, delay);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            bound = true;
            remote = service;
            onConnected();
            try {
                service.linkToDeath(deathRecipient, 0);
            } catch (RemoteException ignored) {
                disconnect(true);
                return;
            }
            handshake(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            disconnect(true);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            disconnect(true);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            disconnect(false);
        }
    };

    private IBinder remote;
    private boolean bound;
    private boolean binding;
    private boolean wanted;
    private int reconnectAttempt;

    FocusPenSystemLink(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    private void disconnect(boolean retry) {
        binding = false;
        remote = null;
        setConnected(false);
        safeUnbind();
        if (retry && wanted) scheduleRebind();
    }

    private void scheduleRebind() {
        handler.removeCallbacks(rebind);
        final long delay = Math.min(MAX_RECONNECT_DELAY_MS,
                BASE_RECONNECT_DELAY_MS << Math.min(reconnectAttempt, 5));
        reconnectAttempt = Math.min(reconnectAttempt + 1, 5);
        handler.postDelayed(rebind, delay);
    }

    private void onConnected() {
        reconnectAttempt = 0;
        handler.removeCallbacks(rebind);
    }

    void enable() {
        if (wanted) return;
        wanted = true;
        bind();
    }

    void disable() {
        wanted = false;
        handler.removeCallbacks(rebind);
        handler.removeCallbacks(retryConnected);
        final IBinder binder = remote;
        remote = null;
        if (binder != null) {
            transactSetEnable(binder, 0);
            transactVoid(binder, TX_UNREGISTER, null);
            try {
                binder.unlinkToDeath(deathRecipient, 0);
            } catch (RuntimeException ignored) {
                // The remote binder may already be dead.
            }
        }
        safeUnbind();
        setConnected(false);
    }

    private void bind() {
        if (!wanted || bound || binding) return;
        final Intent intent = new Intent().setComponent(
                new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
        try {
            final boolean didBind = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            bound = didBind;
            binding = didBind;
            Log.i(TAG, "bindService -> " + bound);
        } catch (RuntimeException error) {
            bound = false;
            binding = false;
            Log.w(TAG, "bindService failed", error);
        }
        if (!bound) {
            setConnected(false);
            if (wanted) scheduleRebind();
        } else if (remote == null) {
            handler.postDelayed(rebind, BASE_RECONNECT_DELAY_MS);
        }
    }

    private void handshake(IBinder binder) {
        final boolean registered = transactVoid(binder, TX_REGISTER_WITH_PACKAGE,
                new ParcelWriter() {
                    @Override
                    public void write(Parcel data) {
                        data.writeStrongBinder(callback);
                        data.writeString(context.getPackageName());
                    }
                });
        if (!registered && !transactVoid(binder, TX_REGISTER, new ParcelWriter() {
            @Override
            public void write(Parcel data) {
                data.writeStrongBinder(callback);
            }
        })) {
            Log.w(TAG, "Touch Film listener registration failed");
            disconnect(true);
            return;
        }
        final Integer result = transactSetEnable(binder, 1);
        Log.i(TAG, "Touch Film setEnable result=" + result);
        setConnected(result != null && result == 0);
        handler.removeCallbacks(retryConnected);
        reconnectAttempt = 0;
        handler.postDelayed(retryConnected, result != null && result == 0
                ? 3000L : BASE_RECONNECT_DELAY_MS);
    }

    private Integer transactSetEnable(IBinder binder, int enabled) {
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(FEATURE_TOUCH_FILM);
            data.writeInt(enabled);
            if (!binder.transact(TX_SET_ENABLE, data, reply, 0)) return null;
            reply.readException();
            return reply.readInt();
        } catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "setEnable failed", error);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean transactVoid(IBinder binder, int code, ParcelWriter writer) {
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!binder.transact(code, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "transaction " + code + " failed", error);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void safeUnbind() {
        if (!bound) return;
        try {
            context.unbindService(connection);
        } catch (RuntimeException ignored) {
            // Binding state can be cleared by the system after service death.
        }
        bound = false;
        binding = false;
    }

    private void setConnected(boolean connected) {
        if (listener != null) listener.onConnectionChanged(connected);
    }

    private interface ParcelWriter {
        void write(Parcel data);
    }
}
