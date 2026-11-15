package com.globalannotate.official;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.globalannotate.official.panels.BrushPanel;
import com.globalannotate.official.panels.ColorPanel;
import com.globalannotate.official.panels.PenGesturePanel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Foreground overlay service. It owns three windows, bottom to top:
 *
 * <ol>
 *   <li>the full-screen, touchable {@link AnnotationView} that draws ink;</li>
 *   <li>a full-screen, non-touchable {@link FloatingHost} that shows the ball
 *       and the capsule;</li>
 *   <li>a tiny transparent, touchable proxy window that exactly covers the
 *       host's visible region and forwards touches to the capsule.</li>
 * </ol>
 *
 * The proxy is the only window that ever moves. It is pinned for the whole
 * DOWN..UP stream so that a morph cannot slide the touch target out from under
 * the finger; the visual host stays full-screen and is never touchable.
 *
 * <p>A fourth, temporary window carries a panel while it is open. It is sized
 * to the panel, placed beside the capsule, and modal so that the touch that
 * dismisses it never reaches the canvas underneath.</p>
 */
public final class FloatingService extends Service {

    public static final String ACTION_START =
            "com.globalannotate.official.action.START";
    public static final String ACTION_STOP =
            "com.globalannotate.official.action.STOP";

    private static final String CHANNEL_ID = "ga_floating";
    private static final int NOTIFICATION_ID = 0x6A01;
    private static final String PREFS = "ga_floating";
    private static final String KEY_BALL_CX = "ball_cx";
    private static final String KEY_BALL_CY = "ball_cy";

    private static final float EDGE_INSET_DP = 16f;

    /**
     * Panel windows. The widths repeat the panels' own private sizes
     * ({@code ColorPanel} is 360dp, {@code BrushPanel} 288dp, {@code
     * PenGesturePanel} 300dp) because the WindowManager needs them before the
     * panel has a parent.
     */
    private static final float COLOR_PANEL_WIDTH_DP = 360f;
    private static final float BRUSH_PANEL_WIDTH_DP = 288f;
    private static final float GESTURE_PANEL_WIDTH_DP = 300f;
    /** The more card is 200dp wide (official MoreActionPopupWindow). */
    private static final float MORE_MENU_WIDTH_DP = 200f;
    /** Free space kept between a panel and the capsule. */
    private static final float PANEL_GAP_DP = 12f;
    /** Both panels play a 150ms exit before their window is detached. */
    private static final long PANEL_HIDE_MS = 180L;
    /** Overlay frames to skip before a screenshot, so the hidden views are gone. */
    private static final long SCREENSHOT_HIDE_MS = 260L;
    /** Root grants can block on a prompt; never leave the worker stuck. */
    private static final long SCREENSHOT_TIMEOUT_SEC = 6L;
    /** Brush/colour edits are written once the sliders have been quiet this long. */
    private static final long PERSIST_DELAY_MS = 400L;

    private static final String KEY_STYLE = "style";
    private static final String KEY_COLOR = "color";
    private static final String KEY_RECENT_COLORS = "recentColors";
    private static final String BRUSH_KEY_PREFIX = "brush_";
    /** One stored {@link PenGesturePanel.Action} name per pen gesture. */
    private static final String GESTURE_KEY_PREFIX = "gesture_";
    /** One persisted boolean per MoreMenuView switch. */
    private static final String SWITCH_KEY_PREFIX = "switch_";
    /** How long an enable waits for the helper's TX_SET_ACTIVE confirmation. */
    private static final long SPLIT_CONFIRM_MS = 1000L;
    private static final int MAX_RECENT_COLORS = 12;
    private static final int MIN_PEN_ALPHA = 3;
    private static final int MAX_PEN_ALPHA = 255;

    private static final int BASE_OVERLAY_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private AnnotationView annotationView;
    private FloatingHost host;
    private FloatingMorph morph;
    private View proxy;
    private WindowManager.LayoutParams proxyParams;

    private boolean started;
    private boolean expanded;
    private boolean proxyPinned;
    private boolean swallowOutsideGesture;

    private boolean ballDrag;
    private boolean capsuleDrag;
    private boolean afterCollapseDrag;
    private boolean pendingRelease;
    /** True while the overlay is hidden for a screenshot: no input at all. */
    private boolean exporting;

    private float grabDx;
    private float grabDy;
    /** Finger-to-ball offset latched when a capsule drag hands over to the ball. */
    private float collapseGrabDx;
    private float collapseGrabDy;
    private float lastRawX = Float.NaN;
    private float lastRawY = Float.NaN;

    private int screenW;
    private int screenH;
    private float density;
    private int insetPx;

    private Types.PenStyle penStyle = Types.PenStyle.BALLPOINT;
    private int penColor = Types.FIXED_COLORS[3];
    /**
     * Fallback colour for a tool with no stored colour of its own. The old
     * single "color" preference seeds it, so an upgrade keeps the colour the
     * user was working with.
     */
    private int defaultPenColor = Types.FIXED_COLORS[3];

    /** Per-tool brush state, in the panel's own units; loaded lazily from prefs. */
    private final HashMap<Types.PenStyle, BrushSettings> brushSettings = new HashMap<>();
    /** MRU of the colour editor, newest first, at most {@link #MAX_RECENT_COLORS}. */
    private final ArrayList<Integer> recentColors = new ArrayList<>();
    /** The colour the editor last reported; committed to the MRU on the next flush. */
    private int pendingRecentColor;

    private ColorPanel colorPanel;
    private BrushPanel brushPanel;
    private MoreMenuView moreMenu;
    private PenGesturePanel gesturePanel;
    private FrameLayout colorPanelHost;
    private FrameLayout brushPanelHost;
    private FrameLayout moreMenuHost;
    private FrameLayout gesturePanelHost;
    /** Anchor the gesture card opened on, for re-placing it after a level swap. */
    private int gesturePanelAnchorX;

    /** Arc pen palette, its full-screen window and the open/close animation. */
    private RadialPalette palette;
    private FrameLayout paletteHost;
    private ValueAnimator paletteAnimator;
    private boolean paletteOpen;
    /** A deferred open is queued behind a collapse animation. */
    private boolean palettePendingOpen;
    /** Invalidates a deferred open when the palette is toggled again. */
    private int paletteShowToken;
    /** Last instance, for gesture broadcasts that arrive on the Binder thread. */
    private static volatile FloatingService instance;
    /** Window root of the panel currently attached, or null. */
    private FrameLayout openPanelHost;
    /** Screen origin of the attached panel window; stylus events are rebased. */
    private int openPanelX;
    private int openPanelY;
    private int openPanelW;
    private int openPanelH;
    /** Every panel window this service has attached and not yet removed. */
    private final ArrayList<FrameLayout> attachedPanelHosts = new ArrayList<>();
    /** Invalidates the delayed window removal of an already dismissed panel. */
    private int panelDismissToken;

    private final Runnable persistTask = new Runnable() {
        @Override
        public void run() {
            persistPanelStateNow();
        }
    };

    private final FloatingMorph.Placement placement = new FloatingMorph.Placement() {
        @Override
        public void onVisibleRect(Rect r, boolean smooth) {
            if (r == null || proxyPinned) {
                return;
            }
            updateProxyRect(r);
        }
    };

    /**
     * Stylus stream rerouted from the LSPosed helper while ROOT_SPLIT is on.
     * It arrives on the main thread and is only valid inside the call, so the
     * canvas consumes it synchronously through {@code dispatchTouchEvent}.
     */
    private final AnnotationInputService.InputSink inputSink =
            new AnnotationInputService.InputSink() {
                @Override
                public void onStylusEvent(MotionEvent event) {
                    if (!started || exporting) {
                        return;
                    }
                    // The helper drops these events from normal window
                    // dispatch, so whichever overlay window is on top has to
                    // receive them here: the palette, an open panel, or the
                    // canvas. A panel is placed at its own window origin, so
                    // screen coordinates must be rebased for it.
                    if (paletteOpen && paletteHost != null) {
                        paletteHost.dispatchTouchEvent(event);
                        return;
                    }
                    if (openPanelHost != null) {
                        // A panel window can never receive ACTION_OUTSIDE for
                        // an injected event, so an outside DOWN dismisses here.
                        final boolean inside = event.getX() >= openPanelX
                                && event.getX() < openPanelX + openPanelW
                                && event.getY() >= openPanelY
                                && event.getY() < openPanelY + openPanelH;
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !inside) {
                            dismissPanels();
                            return;
                        }
                        final MotionEvent local = MotionEvent.obtain(event);
                        local.offsetLocation(-openPanelX, -openPanelY);
                        openPanelHost.dispatchTouchEvent(local);
                        local.recycle();
                        return;
                    }
                    if (annotationView != null) {
                        annotationView.dispatchTouchEvent(event);
                    }
                }

                @Override
                public void onSplitActiveChanged(boolean active) {
                    // ROOT_SPLIT sends the stylus over this Binder sink and
                    // leaves fingers in normal dispatch. The canvas must stop
                    // eating them then, or the app below never sees a finger.
                    setWindowTouchable(annotationView, !active && !exporting);
                }
            };

    private final View.OnTouchListener proxyTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (exporting) {
                return true;
            }
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                proxyPinned = true;
                // Remember where inside the ball the finger landed, before the
                // drag threshold moves it. The proxy rect is the ball rect in
                // the collapsed state.
                if (!expanded && host != null) {
                    final float half = host.ballSizePx() / 2f;
                    grabDx = event.getX() - half;
                    grabDy = event.getY() - half;
                }
            }
            if (host != null && host.capsule() != null) {
                host.capsule().dispatchTouchEvent(event);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                proxyPinned = false;
                syncProxyToHost();
            }
            // The proxy must own the whole stream even if the capsule refuses
            // the initial DOWN.
            return true;
        }
    };

    private final View.OnTouchListener annotationTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (exporting) {
                // The window is NOT_TOUCHABLE during a screenshot; this is
                // the belt-and-braces guard for a race on the flag update.
                return true;
            }
            final int action = event.getActionMasked();
            if (swallowOutsideGesture) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    swallowOutsideGesture = false;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN && expanded && morph != null) {
                // Tapping outside the capsule dismisses it; the tap must not
                // leak through as an ink stroke.
                swallowOutsideGesture = true;
                // A morph owns the views until it lands. Collapsing mid-flight
                // cancelled the animator at its current outline and left the
                // capsule frozen half-open, so the press is swallowed whole
                // and the morph finishes first.
                if (!morph.isAnimating()) {
                    final Rect visible = morph.visibleRect();
                    final float x = visible != null ? visible.exactCenterX() : morph.ballCx();
                    final float y = visible != null ? visible.exactCenterY() : morph.ballCy();
                    collapseToNearest(x, y, false);
                }
                return true;
            }
            return false;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        density = getResources().getDisplayMetrics().density;
        insetPx = Math.round(EDGE_INSET_DP * density);
        restoreBrushState();
        loadRecentColors();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.ga_overlay_needed, Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!started) {
            started = true;
            if (!showOverlays()) {
                started = false;
                Toast.makeText(this, R.string.ga_service_failed, Toast.LENGTH_LONG).show();
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (morph == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                onScreenChanged();
            }
        });
    }

    @Override
    public void onDestroy() {
        discardPanels();
        closePalette(false);
        cancelPaletteAnimation();
        instance = null;
        // The receiver's persisted flag must follow the service, else the next
        // corner swipe believes the overlay is up and refuses to summon it.
        GestureLaunchReceiver.clearOverlayRunning(this);
        // Never leave the helper in ROOT_SPLIT after the overlay is gone: it
        // would keep swallowing stylus events with no sink left to draw them.
        try {
            sendBroadcast(new Intent(GestureLaunchReceiver.ACTION_SET_SPLIT_MODE)
                    .putExtra(GestureLaunchReceiver.EXTRA_TOKEN, GestureLaunchReceiver.TOKEN)
                    .putExtra(GestureLaunchReceiver.EXTRA_ENABLED, false));
        } catch (RuntimeException ignored) {
            // The process may be going away; the helper also clears split
            // when its module is disabled.
        }
        started = false;
        proxyPinned = false;
        ballDrag = false;
        capsuleDrag = false;
        afterCollapseDrag = false;
        pendingRelease = false;
        if (morph != null) {
            morph.cancel();
        }
        removeOverlay(proxy);
        removeOverlay(host);
        removeOverlay(annotationView);
        proxy = null;
        proxyParams = null;
        host = null;
        morph = null;
        annotationView = null;
        AnnotationInputService.setInputSink(null);
        AnnotationInputService.setFloatingService(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Window setup
    // ------------------------------------------------------------------

    private boolean showOverlays() {
        updateScreenMetrics();

        annotationView = new AnnotationView(this);
        annotationView.setPenStyle(penStyle);
        annotationView.setPenColor(penColor);
        // Finger drawing defaults on; see switchValue().
        annotationView.setFingerDrawEnabled(switchValue(MoreMenuView.Setting.FINGER_DRAW));
        applyBrushToView();
        annotationView.setOnTouchListener(annotationTouch);
        if (!addOverlay(annotationView, overlayParams())) {
            return false;
        }

        final CapsuleView capsule = new CapsuleView(this, capsuleListener);
        capsule.setScreenSizeDp(pxToDp(screenW), pxToDp(screenH));
        capsule.setDragListener(capsuleDragListener);
        capsule.setBallTapListener(new Runnable() {
            @Override
            public void run() {
                onBallTapped();
            }
        });
        capsule.setTouchDownListener(new Runnable() {
            @Override
            public void run() {
                onCapsuleTouchDown();
            }
        });
        capsule.setLongPressListener(new Runnable() {
            @Override
            public void run() {
                // The official arc palette is pen-key driven; a ball hold is
                // this app's reachable equivalent.
                togglePalette();
            }
        });
        capsule.update(penStyle, penColor, annotationView.canUndo(), annotationView.canRedo());

        host = new FloatingHost(this, capsule);
        host.setScreenSizeDp(pxToDp(screenW), pxToDp(screenH));
        host.setBallGlyph(penStyle, penColor);
        host.ball().setBrush(penStyle, penColor);

        // Put the capsule into its collapsed interaction mode and position the
        // ball before the window appears, so there is no first-frame flash of an
        // expanded capsule at the top-left corner.
        final float[] ball = loadBallCentre();
        capsule.enterBallMode(host.ballSizePx());
        host.showBallAt(ball[0], ball[1], host.ballSizePx());
        // This app keeps the brush inside the ball upright on every anchor.
        // The official picker turns it +90/-90 on the left/right edges, but
        // the product decision here overrides that.
        host.ball().setRotation(0f);
        // CapsuleView.ballGesture() checks the actual frame, so keep the
        // capsule's params ball-sized for as long as it is collapsed.
        host.setCapsuleSize(host.ballSizePx(), host.ballSizePx());

        // The host is pure visual output: it must never win a touch against the
        // canvas below or the proxy above, so it is NOT_TOUCHABLE. All picker
        // input arrives through the proxy window.
        if (!addOverlay(host, visualParams())) {
            return false;
        }
        // The host converts screen coordinates through its window origin.
        host.captureScreenOrigin();

        morph = new FloatingMorph(host, placement);
        morph.setScreenSize(screenW, screenH);

        proxy = new View(this);
        proxy.setBackgroundColor(Color.TRANSPARENT);
        proxy.setOnTouchListener(proxyTouch);
        proxyParams = overlayParams();
        proxyParams.width = 1;
        proxyParams.height = 1;
        proxyParams.x = 0;
        proxyParams.y = 0;
        if (!addOverlay(proxy, proxyParams)) {
            return false;
        }

        morph.showBallAt(ball[0], ball[1], host.ballSizePx());
        syncProxyToHost();

        // ROOT_SPLIT stylus input: the helper binds AnnotationInputService in
        // system_server and the bridge forwards here. Registering the sink is
        // what turns the previously dropped events into ink strokes.
        AnnotationInputService.setFloatingService(this);
        AnnotationInputService.setInputSink(inputSink);
        // The helper's split state lives in system_server memory; a switch
        // left on across a reboot is re-asserted once the sink is registered.
        if (switchValue(MoreMenuView.Setting.EVENT_PASSTHROUGH)) {
            requestSplitMode(true);
        }

        // WindowManager may finish the first traversal after addView returns;
        // re-cache the origin then and put the proxy on the real ball rect.
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (started && host != null) {
                    host.captureScreenOrigin();
                    syncProxyToHost();
                }
            }
        });
        return true;
    }

    private WindowManager.LayoutParams overlayParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.flags = BASE_OVERLAY_FLAGS;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        return lp;
    }

    /**
     * Same geometry as {@link #overlayParams()} but untouchable: used by the
     * picker host, whose only job is to draw. Touches are delivered by the
     * transparent proxy window instead.
     */
    private WindowManager.LayoutParams visualParams() {
        final WindowManager.LayoutParams lp = overlayParams();
        lp.flags = BASE_OVERLAY_FLAGS | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        return lp;
    }

    private boolean addOverlay(View view, WindowManager.LayoutParams params) {
        if (view == null) {
            return false;
        }
        try {
            windowManager.addView(view, params);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void removeOverlay(View view) {
        if (view == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
            // Already detached or the window token went away with the process.
        }
    }

    private void updateScreenMetrics() {
        final Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
        screenW = Math.max(1, bounds.width());
        screenH = Math.max(1, bounds.height());
        density = getResources().getDisplayMetrics().density;
        insetPx = Math.round(EDGE_INSET_DP * density);

        if (host != null) {
            host.setScreenSizeDp(pxToDp(screenW), pxToDp(screenH));
        }
        if (host != null && host.capsule() != null) {
            host.capsule().setScreenSizeDp(pxToDp(screenW), pxToDp(screenH));
        }
        if (morph != null) {
            morph.setScreenSize(screenW, screenH);
        }
    }

    private void updateProxyRect(Rect r) {
        if (proxy == null || proxyParams == null || r == null) {
            return;
        }
        final int width = Math.max(1, r.width());
        final int height = Math.max(1, r.height());
        if (proxyParams.x == r.left && proxyParams.y == r.top
                && proxyParams.width == width && proxyParams.height == height) {
            return;
        }
        proxyParams.x = r.left;
        proxyParams.y = r.top;
        proxyParams.width = width;
        proxyParams.height = height;
        try {
            windowManager.updateViewLayout(proxy, proxyParams);
        } catch (RuntimeException ignored) {
            // The view was removed between the animation frame and this call.
        }
    }

    private void syncProxyToHost() {
        // Moving the proxy during a stream would move the touch target out from
        // under the finger; the pinned window keeps the whole gesture.
        if (host != null && !proxyPinned) {
            updateProxyRect(host.visibleRect());
        }
    }

    // ------------------------------------------------------------------
    // Capsule listeners
    // ------------------------------------------------------------------

    private final CapsuleView.Listener capsuleListener = new CapsuleView.Listener() {
        @Override
        public void onTool(Types.PenStyle style, int anchorX) {
            if (style == null) {
                return;
            }
            penStyle = style;
            // Mi Canvas keeps one brush state per pen (width, opacity and
            // colour): switching a pen restores that pen's own values.
            applyBrushToView();
            refreshCapsule();
            // Every tool tap re-opens the popup on that tool's own values.
            showBrushPanel(style, anchorX);
        }

        @Override
        public void onColor(int color) {
            applyPenColor(color);
            rememberColor(penColor);
        }

        @Override
        public void onCustomColor(int anchorX) {
            showColorPanel(anchorX);
        }

        @Override
        public void onUndo() {
            annotationView.undo();
            refreshCapsule();
        }

        @Override
        public void onRedo() {
            annotationView.redo();
            refreshCapsule();
        }

        @Override
        public void onMore(int anchorX) {
            showMoreMenu(anchorX);
        }
    };

    private final CapsuleView.DragListener capsuleDragListener =
            new CapsuleView.DragListener() {
                @Override
                public void onDragStart(boolean isBall, float viewX, float viewY) {
                    handleDragStart(isBall, viewX, viewY);
                }

                @Override
                public void onDrag(float dx, float dy, float rawX, float rawY) {
                    handleDrag(rawX, rawY);
                }

                @Override
                public void onDragEnd(float rawX, float rawY) {
                    handleDragEnd(rawX, rawY);
                }
            };

    private void refreshCapsule() {
        if (host == null || annotationView == null) {
            return;
        }
        host.setBallGlyph(penStyle, penColor);
        host.ball().setBrush(penStyle, penColor);
        host.capsule().update(penStyle, penColor,
                annotationView.canUndo(), annotationView.canRedo());
    }

    // ------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------

    /**
     * Root of a panel window. The panel is always laid out at the window origin:
     * the window params carry the placement, so the margins showAt() writes into
     * the panel's LayoutParams must not move it a second time.
     */
    private static final class PanelHost extends FrameLayout {
        PanelHost(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                final View child = getChildAt(i);
                child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            }
        }
    }

    /** Per-tool brush values in the panel's own units; Mi Canvas keeps one per pen. */
    private static final class BrushSettings {
        float width;
        float alpha;
        float sensitivity;
        int tip;
        /** Each pen remembers its own colour, like Mi Canvas' preset colours. */
        int color;

        BrushSettings(float width, float alpha, float sensitivity, int tip, int color) {
            this.width = width;
            this.alpha = alpha;
            this.sensitivity = sensitivity;
            this.tip = tip;
            this.color = color;
        }
    }

    /** Outside presses reach the window root for a modal panel; the first dismisses. */
    private final View.OnTouchListener panelOutsideTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismissPanels();
                return true;
            }
            return false;
        }
    };

    private final ColorPanel.Listener colorPanelListener = new ColorPanel.Listener() {
        @Override
        public void onColorPicked(int color) {
            // The board and the rails report every sample while dragging; the
            // MRU only keeps the colour the drag settles on, committed by the
            // debounced persist.
            pendingRecentColor = color | 0xFF000000;
            applyPenColor(color);
        }

        @Override
        public void onAlphaPicked(float alpha) {
            applyToolAlpha(alpha);
        }

        @Override
        public void onDismiss() {
            dismissPanels();
        }
    };

    private final BrushPanel.Listener brushPanelListener = new BrushPanel.Listener() {
        @Override
        public void onBrushChanged(float width, float alpha, float sensitivity, int tip) {
            final BrushSettings settings = settingsFor(penStyle);
            settings.width = width;
            settings.alpha = alpha;
            settings.sensitivity = sensitivity;
            settings.tip = tip;
            applyBrushToView();
            persistLater();
        }

        @Override
        public void onDismiss() {
            dismissPanels();
        }
    };

    private final PenGesturePanel.Listener gesturePanelListener =
            new PenGesturePanel.Listener() {
                @Override
                public void onActionSelected(PenGesturePanel.Gesture gesture,
                                             PenGesturePanel.Action action) {
                    if (gesture == null) {
                        return;
                    }
                    final PenGesturePanel.Action value =
                            action != null ? action : PenGesturePanel.Action.NONE;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(GESTURE_KEY_PREFIX + gesture.name(), value.name())
                            .apply();
                }

                @Override
                public void onContentHeightChanged() {
                    resizeGesturePanel();
                }

                @Override
                public void onDismiss() {
                    dismissPanels();
                }
            };

    private final RadialPalette.Listener paletteListener = new RadialPalette.Listener() {
        @Override
        public void onTool(Types.PenStyle style) {
            if (style == null || annotationView == null) {
                return;
            }
            penStyle = style;
            applyBrushToView();
            if (palette != null) {
                palette.setSelected(style);
            }
            refreshCapsule();
        }

        @Override
        public void onUndo() {
            if (annotationView != null) {
                annotationView.undo();
                refreshCapsule();
            }
        }

        @Override
        public void onRedo() {
            if (annotationView != null) {
                annotationView.redo();
                refreshCapsule();
            }
        }

        @Override
        public void onColor() {
            closePalette(false);
            showColorPanel(palettePanelAnchor());
        }

        @Override
        public void onMore() {
            closePalette(false);
            showMoreMenu(palettePanelAnchor());
        }

        @Override
        public void onDismiss() {
            closePalette(true);
        }
    };

    /** Colour / more panels open at the right edge, where the palette sits. */
    private int palettePanelAnchor() {
        return Math.max(insetPx, screenW - dp(72f));
    }

    /** Ball long-press or a pen squeeze: the arc palette toggles. */
    private void togglePalette() {
        if (paletteOpen) {
            closePalette(true);
        } else if (palettePendingOpen) {
            // A second squeeze during the deferred open cancels it.
            palettePendingOpen = false;
            paletteShowToken++;
        } else {
            openPalette();
        }
    }

    private void openPalette() {
        if (!started || host == null || morph == null || exporting) {
            return;
        }
        dismissPanels();
        if (expanded) {
            // Official collapses the picker before the palette appears.
            final Rect visible = morph.visibleRect();
            final float x = visible != null ? visible.exactCenterX() : morph.ballCx();
            final float y = visible != null ? visible.exactCenterY() : morph.ballCy();
            collapseToNearest(x, y, false);
        }
        final int token = ++paletteShowToken;
        if (morph.isAnimating()) {
            // Let the capsule finish collapsing into its ball first.
            palettePendingOpen = true;
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (token == paletteShowToken) {
                        palettePendingOpen = false;
                        showPaletteNow();
                    }
                }
            }, 360L);
        } else {
            showPaletteNow();
        }
    }

    private void showPaletteNow() {
        if (!started || paletteOpen || host == null || exporting) {
            return;
        }
        if (palette == null) {
            palette = new RadialPalette(this, paletteListener);
        }
        palette.setColors(toolColors());
        palette.setSelected(penStyle);
        palette.setOpenProgress(0f);

        // The host is created once and re-attached: re-parenting the single
        // palette view into a fresh host would throw "child already has a
        // parent" on the second open and kill the process.
        if (paletteHost == null) {
            paletteHost = new PanelHost(this);
            paletteHost.setClickable(true);
            paletteHost.addView(palette, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        final WindowManager.LayoutParams params = overlayParams();
        // Full screen and touchable: the palette is transparent outside the
        // band, so its own hit test decides between a hidden control and a
        // dismiss, exactly like the official wrapper.
        params.flags = BASE_OVERLAY_FLAGS;
        if (!addOverlay(paletteHost, params)) {
            return;
        }
        paletteOpen = true;
        palette.show();
        animatePalette(0f, 1f);
    }

    private void closePalette(boolean animated) {
        // Any deferred open is stale from here on.
        palettePendingOpen = false;
        paletteShowToken++;
        if (!paletteOpen) {
            return;
        }
        if (animated) {
            animatePalette(1f, 0f);
            return;
        }
        cancelPaletteAnimation();
        finishClosePalette();
    }

    /**
     * Official open/close: the 492dp square scales 0.5 -> 1 on a
     * spring(0.75, 0.39) with a short fade. Closing is the reverse.
     */
    private void animatePalette(final float from, final float to) {
        cancelPaletteAnimation();
        final Spring spring = new Spring(0.75f, 0.39f);
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(to > from ? spring.durationMs() : 180L);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (palette == null || paletteAnimator != animation) {
                    return;
                }
                final float fraction = animation.getAnimatedFraction();
                final float shaped = to > from ? spring.getInterpolation(fraction)
                        : 1f - (1f - fraction) * (1f - fraction);
                palette.setOpenProgress(from + (to - from) * shaped);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (paletteAnimator != animation) {
                    return;
                }
                paletteAnimator = null;
                if (to <= 0f) {
                    finishClosePalette();
                }
            }
        });
        paletteAnimator = anim;
        anim.start();
    }

    private void cancelPaletteAnimation() {
        final ValueAnimator running = paletteAnimator;
        if (running != null) {
            paletteAnimator = null;
            running.cancel();
        }
    }

    private void finishClosePalette() {
        paletteOpen = false;
        if (palette != null) {
            palette.hide();
            palette.setOpenProgress(0f);
        }
        // Keep the host (and the palette inside it) for the next open; only
        // the window goes away.
        if (paletteHost != null) {
            removeOverlay(paletteHost);
        }
    }

    /** Per-tool colours for the arc; every pen keeps its own like Mi Canvas. */
    private int[] toolColors() {
        final Types.PenStyle[] styles = Types.PenStyle.values();
        final int[] colors = new int[styles.length];
        for (int i = 0; i < styles.length; i++) {
            colors[i] = settingsFor(styles[i]).color;
        }
        return colors;
    }

    /** True while the overlay windows are up; used by the gesture receiver. */
    public static boolean isRunning() {
        final FloatingService service = instance;
        return service != null && service.started;
    }

    /**
     * Gesture broadcast entry point, delivered on the Binder thread by
     * {@link GestureLaunchReceiver}. All four helper gesture names are
     * accepted; the stored binding decides what runs.
     */
    public static void handleExternalGesture(String name) {
        final FloatingService service = instance;
        final PenGesturePanel.Gesture gesture = PenGesturePanel.Gesture.fromName(name);
        if (service == null || gesture == null) {
            return;
        }
        // The binding is read on the main thread with the rest of the service
        // state, so a Binder caller never touches the views itself.
        service.mainHandler.post(new Runnable() {
            @Override
            public void run() {
                service.runGestureAction(gesture);
            }
        });
    }

    /** Runs the action currently bound to {@code gesture}; main thread only. */
    private void runGestureAction(PenGesturePanel.Gesture gesture) {
        // Every action below drives the canvas or its overlay; one guard
        // covers them all.
        if (gesture == null || annotationView == null || host == null) {
            return;
        }
        // A pen gesture is a global command: an open card must not swallow it
        // or end up in a gesture-triggered screenshot.
        dismissPanels();
        switch (storedGestureAction(gesture)) {
            case ARC_PALETTE:
                togglePalette();
                break;
            case ERASER:
                penStyle = Types.PenStyle.ERASER;
                applyBrushToView();
                refreshCapsule();
                break;
            case UNDO:
                annotationView.undo();
                refreshCapsule();
                break;
            case REDO:
                annotationView.redo();
                refreshCapsule();
                break;
            case SAVE_IMAGE:
                // The palette is its own window and setOverlayVisible() does
                // not hide it; close it before the screenshot is taken.
                closePalette(false);
                exportAnnotation(false);
                break;
            case SAVE_PDF:
                closePalette(false);
                exportAnnotation(true);
                break;
            case CLEAR:
                annotationView.clear();
                refreshCapsule();
                break;
            case EXIT:
                stopSelf();
                break;
            case NONE:
            default:
                break;
        }
    }

    private final MoreMenuView.Listener moreMenuListener = new MoreMenuView.Listener() {
        @Override
        public void onAction(MoreMenuView.Action action) {
            // The gesture card opens on the more card's own centre, so read
            // the open panel's window geometry while it is still current.
            final int panelAnchor = openPanelX + openPanelW / 2;
            dismissPanels();
            switch (action) {
                case SAVE_IMAGE:
                    exportAnnotation(false);
                    break;
                case SAVE_PDF:
                    exportAnnotation(true);
                    break;
                case CLEAR_NOTES:
                    if (annotationView != null) {
                        annotationView.clear();
                        refreshCapsule();
                    }
                    break;
                case EXIT_ANNOTATION:
                    stopSelf();
                    break;
                case PEN_GESTURES:
                    showGesturePanel(panelAnchor);
                    break;
                case LONG_SCREENSHOT:
                default:
                    Toast.makeText(FloatingService.this, R.string.ga_more_hint,
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }

        @Override
        public void onToggle(MoreMenuView.Setting setting, boolean enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(SWITCH_KEY_PREFIX + setting.name(), enabled)
                    .apply();
            if (setting == MoreMenuView.Setting.FINGER_DRAW && annotationView != null) {
                annotationView.setFingerDrawEnabled(enabled);
            }
            if (setting == MoreMenuView.Setting.EVENT_PASSTHROUGH) {
                requestSplitMode(enabled);
            }
        }

        @Override
        public void onDismiss() {
            dismissPanels();
        }
    };

    private ColorPanel ensureColorPanel() {
        if (colorPanel == null) {
            colorPanel = new ColorPanel(this, colorPanelListener);
            colorPanelHost = newPanelHost(colorPanel);
        }
        return colorPanel;
    }

    private BrushPanel ensureBrushPanel() {
        if (brushPanel == null) {
            brushPanel = new BrushPanel(this, brushPanelListener);
            brushPanelHost = newPanelHost(brushPanel);
        }
        return brushPanel;
    }

    private MoreMenuView ensureMoreMenu() {
        if (moreMenu == null) {
            moreMenu = new MoreMenuView(this, moreMenuListener);
            moreMenuHost = newPanelHost(moreMenu);
        }
        return moreMenu;
    }

    private PenGesturePanel ensureGesturePanel() {
        if (gesturePanel == null) {
            gesturePanel = new PenGesturePanel(this, gesturePanelListener);
            gesturePanelHost = newPanelHost(gesturePanel);
        }
        return gesturePanel;
    }

    private FrameLayout newPanelHost(View panel) {
        final FrameLayout root = new PanelHost(this);
        root.setClickable(true);
        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnTouchListener(panelOutsideTouch);
        return root;
    }

    /** Opens the brush popup on {@code style}'s own values, anchored at the tool. */
    private void showBrushPanel(Types.PenStyle style, int anchorX) {
        if (!started || host == null) {
            return;
        }
        final BrushPanel panel = ensureBrushPanel();
        final BrushSettings settings = settingsFor(style);
        panel.setTool(brushTool(style));
        panel.setBrush(settings.width, settings.alpha, settings.sensitivity, settings.tip);
        panel.setColor(penColor);

        final int width = panelWidthPx(BRUSH_PANEL_WIDTH_DP);
        final int height = panelHeightPx(panel.measuredHeightForWidth(width));
        final int[] origin = placePanel(panel, brushPanelHost, width, height, anchorX);
        if (origin == null) {
            return;
        }
        panel.showAt(anchorX, panelAnchorY(origin, height),
                insetPx, Math.max(insetPx, screenH - insetPx));
        pinPanelSize(panel, width, height);
    }

    /** Opens the more menu on the "more" slot's anchor. */
    private void showMoreMenu(int anchorX) {
        if (!started || host == null) {
            return;
        }
        final MoreMenuView menu = ensureMoreMenu();
        final MoreMenuView.Setting[] settings = MoreMenuView.Setting.values();
        for (MoreMenuView.Setting setting : settings) {
            menu.setToggle(setting, switchValue(setting));
        }

        final int width = panelWidthPx(MORE_MENU_WIDTH_DP);
        final int height = panelHeightPx(menu.measuredHeightForWidth(width));
        final int[] origin = placePanel(menu, moreMenuHost, width, height, anchorX);
        if (origin == null) {
            return;
        }
        menu.showAt(anchorX, panelAnchorY(origin, height),
                insetPx, Math.max(insetPx, screenH - insetPx));
        pinPanelSize(menu, width, height);
    }

    /**
     * A switch's stored value. Finger drawing stays on by default: without the
     * passthrough helper, turning it off would leave the screen inert.
     */
    private boolean switchValue(MoreMenuView.Setting setting) {
        final boolean fallback = setting == MoreMenuView.Setting.FINGER_DRAW;
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(SWITCH_KEY_PREFIX + setting.name(), fallback);
    }

    /**
     * Asks the helper to flip ROOT_SPLIT (the 「事件透传」 switch). The helper
     * confirms over the Binder sink with TX_SET_ACTIVE, and only that
     * confirmation makes the canvas drop finger touches. If no confirmation
     * arrives, the helper is missing or disabled, so the switch reverts: an
     * unconditioned disable of the canvas would leave the screen without any
     * working input.
     */
    private void requestSplitMode(final boolean enabled) {
        final Intent intent = new Intent(GestureLaunchReceiver.ACTION_SET_SPLIT_MODE)
                .putExtra(GestureLaunchReceiver.EXTRA_TOKEN, GestureLaunchReceiver.TOKEN)
                .putExtra(GestureLaunchReceiver.EXTRA_ENABLED, enabled);
        try {
            sendBroadcast(intent);
        } catch (RuntimeException ignored) {
            // Broadcast restrictions must not take the overlay down.
        }
        if (!enabled) {
            return;
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (AnnotationInputService.isSplitActive()) {
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(SWITCH_KEY_PREFIX
                                + MoreMenuView.Setting.EVENT_PASSTHROUGH.name(), false)
                        .apply();
                if (moreMenu != null) {
                    moreMenu.setToggle(MoreMenuView.Setting.EVENT_PASSTHROUGH, false);
                }
                Toast.makeText(FloatingService.this, R.string.ga_split_needs_helper,
                        Toast.LENGTH_SHORT).show();
            }
        }, SPLIT_CONFIRM_MS);
    }

    /**
     * Opens the pen-gesture card from the more menu, on the row's own anchor.
     * The card always starts on level 1 (the four gestures), with every
     * binding freshly loaded from prefs.
     */
    private void showGesturePanel(int anchorX) {
        if (!started || host == null) {
            return;
        }
        final PenGesturePanel panel = ensureGesturePanel();
        for (PenGesturePanel.Gesture gesture : PenGesturePanel.Gesture.values()) {
            panel.setAction(gesture, storedGestureAction(gesture));
        }
        panel.showGestures();
        gesturePanelAnchorX = anchorX;

        final int width = panelWidthPx(GESTURE_PANEL_WIDTH_DP);
        final int height = panelHeightPx(panel.measuredHeightForWidth(width));
        final int[] origin = placePanel(panel, gesturePanelHost, width, height, anchorX);
        if (origin == null) {
            return;
        }
        panel.showAt(anchorX, panelAnchorY(origin, height),
                insetPx, Math.max(insetPx, screenH - insetPx));
        pinPanelSize(panel, width, height);
    }

    /**
     * The gesture card swapped between its two levels, so its content height
     * changed. Re-measure and re-place the still-open window; the panel's
     * ScrollView keeps a clamped card usable on short screens.
     */
    private void resizeGesturePanel() {
        if (gesturePanel == null || gesturePanelHost == null
                || openPanelHost != gesturePanelHost) {
            return;
        }
        final int width = panelWidthPx(GESTURE_PANEL_WIDTH_DP);
        final int height = panelHeightPx(gesturePanel.measuredHeightForWidth(width));
        pinPanelSize(gesturePanel, width, height);
        placePanel(gesturePanel, gesturePanelHost, width, height, gesturePanelAnchorX);
    }

    /** The bound action for a gesture, falling back to its documented default. */
    private PenGesturePanel.Action storedGestureAction(PenGesturePanel.Gesture gesture) {
        if (gesture == null) {
            return PenGesturePanel.Action.NONE;
        }
        final String stored = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(GESTURE_KEY_PREFIX + gesture.name(), null);
        final PenGesturePanel.Action action = PenGesturePanel.Action.fromName(stored);
        return action != null ? action : gesture.defaultAction;
    }

    /** Saves the annotations over a root screenshot, or as an ink-only file. */
    private void exportAnnotation(final boolean pdf) {
        if (annotationView == null) {
            return;
        }
        final Bitmap ink = Exporter.renderFullScreen(annotationView, screenW, screenH);
        if (ink == null) {
            Toast.makeText(this, R.string.ga_export_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.ga_export_running, Toast.LENGTH_SHORT).show();

        // Hide the overlay so the ball, capsule and ink are not baked into the
        // background; the transparent ink layer was captured before hiding.
        setOverlayVisible(false);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                final HandlerThread worker = new HandlerThread("ga-export");
                worker.start();
                new Handler(worker.getLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        final Bitmap background = captureScreenRoot();
                        final Uri uri = pdf
                                ? Exporter.savePdf(FloatingService.this, null, background, ink)
                                : Exporter.savePng(FloatingService.this, ink, background);
                        final boolean withBackground = background != null;
                        if (background != null) {
                            background.recycle();
                        }
                        ink.recycle();
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                setOverlayVisible(true);
                                final int message;
                                if (uri == null) {
                                    message = R.string.ga_export_failed;
                                } else if (withBackground) {
                                    message = R.string.ga_export_saved;
                                } else {
                                    // Root was refused: only the ink layer
                                    // could be written.
                                    message = R.string.ga_export_saved_ink;
                                }
                                Toast.makeText(FloatingService.this, message,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        worker.quitSafely();
                    }
                });
            }
        }, SCREENSHOT_HIDE_MS);
    }

    private void setOverlayVisible(boolean visible) {
        exporting = !visible;
        final int visibility = visible ? View.VISIBLE : View.INVISIBLE;
        if (annotationView != null) {
            annotationView.setVisibility(visibility);
        }
        if (host != null) {
            host.setVisibility(visibility);
        }
        // Invisible views still own their window's touch region. During the
        // screenshot the canvas and the proxy must stop receiving touches, or
        // a stray tap is recorded as a stroke and eaten from the app below.
        // When ROOT_SPLIT is on, the canvas also stays untouchable: the pen
        // arrives through the Binder sink and fingers belong to the app below.
        setWindowTouchable(annotationView, visible && !AnnotationInputService.isSplitActive());
        setWindowTouchable(proxy, visible);
    }

    private void setWindowTouchable(View view, boolean touchable) {
        if (view == null || windowManager == null) {
            return;
        }
        final ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof WindowManager.LayoutParams)) {
            return;
        }
        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) raw;
        if (touchable) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            windowManager.updateViewLayout(view, lp);
        } catch (RuntimeException ignored) {
            // The window went away with the service.
        }
    }

    /**
     * Root screenshot through {@code su -c screencap -p}. KernelSU may show a
     * grant prompt the first time, so a watchdog kills the process instead of
     * leaving the export worker blocked. Returns null when root is refused or
     * the PNG does not decode; the export then saves the ink layer alone.
     */
    private static Bitmap captureScreenRoot() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "screencap -p"});
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 22);
            final InputStream stream = process.getInputStream();
            final Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    final byte[] chunk = new byte[64 * 1024];
                    try {
                        int read;
                        while ((read = stream.read(chunk)) > 0) {
                            buffer.write(chunk, 0, read);
                        }
                    } catch (IOException ignored) {
                        // Killed by the watchdog or a broken pipe.
                    }
                }
            }, "ga-screencap-reader");
            reader.start();
            if (!process.waitFor(SCREENSHOT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(500L);
                return null;
            }
            reader.join(2000L);
            final byte[] png = buffer.toByteArray();
            return png.length > 0
                    ? BitmapFactory.decodeByteArray(png, 0, png.length) : null;
        } catch (IOException | InterruptedException | SecurityException error) {
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    /** Opens the colour editor on the current pen colour and opacity. */
    private void showColorPanel(int anchorX) {
        if (!started || host == null) {
            return;
        }
        final ColorPanel panel = ensureColorPanel();
        panel.setColor(penColor);
        panel.setAlphaValue(Math.round(settingsFor(penStyle).alpha));
        panel.setRecentColors(recentColorsArray());

        final int width = panelWidthPx(COLOR_PANEL_WIDTH_DP);
        final int height = panelHeightPx(panel.measuredHeightForWidth(width));
        final int[] origin = placePanel(panel, colorPanelHost, width, height, anchorX);
        if (origin == null) {
            return;
        }
        panel.showAt(anchorX, panelAnchorY(origin, height),
                insetPx, Math.max(insetPx, screenH - insetPx));
        pinPanelSize(panel, width, height);
    }

    private int panelWidthPx(float widthDp) {
        // A panel is never narrower than its content wants to be unless the
        // display itself is narrower: its board/grid has fixed dp widths, so a
        // squeezed window would clip the rightmost column.
        return Math.min(Math.max(dp(64f), Math.round(widthDp * density)), screenW);
    }

    private int panelHeightPx(int measuredHeight) {
        return Math.min(Math.max(dp(64f), measuredHeight), screenH);
    }

    /**
     * Sizes the window, pins the panel to it and attaches it next to the
     * capsule. Returns the panel's screen origin, or null when the window could
     * not be added. The panel's own showAt() only contributes its enter
     * animation: the window params own the placement.
     */
    private int[] placePanel(View panel, FrameLayout windowRoot, int width, int height, int anchorX) {
        if (panel == null || windowRoot == null || windowManager == null) {
            return null;
        }
        // Only one panel window is ever attached. A dismissed panel can still
        // be waiting out its 150ms exit; reopening it in that window reuses
        // the attached host, because adding it twice throws and the failed
        // add would leave the old host attached forever.
        panelDismissToken++;
        final int[] origin = panelOrigin(anchorX, width, height);
        final WindowManager.LayoutParams params =
                panelParams(width, height, origin[0], origin[1]);
        if (attachedPanelHosts.contains(windowRoot)) {
            try {
                windowManager.updateViewLayout(windowRoot, params);
            } catch (RuntimeException gone) {
                removeOverlay(windowRoot);
                attachedPanelHosts.remove(windowRoot);
                return null;
            }
            openPanelHost = windowRoot;
            openPanelX = origin[0];
            openPanelY = origin[1];
            openPanelW = width;
            openPanelH = height;
            return origin;
        }

        // A different panel (or an orphaned one) goes immediately.
        detachPanelWindows();
        if (!addOverlay(windowRoot, params)) {
            return null;
        }
        attachedPanelHosts.add(windowRoot);
        openPanelHost = windowRoot;
        openPanelX = origin[0];
        openPanelY = origin[1];
        openPanelW = width;
        openPanelH = height;
        return origin;
    }

    /** Capsule edge nearest the panel, for showAt()'s own (unused) arithmetic. */
    private int panelAnchorY(int[] origin, int height) {
        final Rect capsule = host != null ? host.visibleRect() : new Rect();
        if (capsule.isEmpty()) {
            return screenH / 2;
        }
        return origin[1] + height <= capsule.top ? capsule.top : capsule.bottom;
    }

    private void pinPanelSize(View panel, int width, int height) {
        final ViewGroup.LayoutParams params = panel.getLayoutParams();
        if (params != null) {
            params.width = width;
            params.height = height;
            panel.setLayoutParams(params);
        }
    }

    /**
     * Panel window params. A panel is modal and not focusable, exactly like a
     * PopupWindow with outside-touch enabled: it deliberately does NOT set
     * FLAG_NOT_TOUCH_MODAL, so the first press outside is swallowed here and
     * delivered once as ACTION_OUTSIDE. Dismissing a panel therefore never
     * leaks a tap (or an ink dot) into the canvas underneath.
     */
    private WindowManager.LayoutParams panelParams(int width, int height, int x, int y) {
        final WindowManager.LayoutParams lp = overlayParams();
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        lp.width = width;
        lp.height = height;
        lp.x = x;
        lp.y = y;
        return lp;
    }

    /**
     * Screen origin for a panel of {@code w x h} next to the capsule: below a
     * TOP dock, above a BOTTOM dock, beside a vertical dock, centred on the
     * anchor (tool or colour slot) that opened it. When a side has no room the
     * other side is used; a panel that fits nowhere is clamped to the emptier
     * gap and only then allowed to overlap.
     */
    private int[] panelOrigin(int anchorX, int w, int h) {
        final int margin = Math.max(dp(8f), insetPx);
        final int gap = dp(PANEL_GAP_DP);
        // A panel may fill the display when there is no room left for the margin.
        final int maxX = Math.max(0, screenW - w);
        final int maxY = Math.max(0, screenH - h);
        final int minX = Math.min(margin, maxX);
        final int minY = Math.min(margin, maxY);
        final Rect capsule = host != null ? host.visibleRect() : new Rect();
        final Types.Dock dock = host != null && host.capsule() != null
                ? host.capsule().dock() : Types.Dock.TOP;

        final int x = clampInt(anchorX - w / 2, minX, maxX);
        if (capsule.isEmpty()) {
            return new int[]{x, clampInt((screenH - h) / 2, minY, maxY)};
        }

        if (dock.vertical()) {
            // A vertical capsule hugs one edge; the panel sits on the other side.
            final int beside = dock.onLeft() ? capsule.right + gap : capsule.left - gap - w;
            if (beside >= minX && beside + w <= screenW - minX) {
                return new int[]{beside, clampInt(capsule.centerY() - h / 2, minY, maxY)};
            }
        }

        final int below = capsule.bottom + gap;
        final int above = capsule.top - gap - h;
        final boolean fitsBelow = below + h <= screenH - minY;
        final boolean fitsAbove = above >= minY;
        int y;
        if (fitsBelow && fitsAbove) {
            y = dock == Types.Dock.BOTTOM ? above : below;
        } else if (fitsBelow) {
            y = below;
        } else if (fitsAbove) {
            y = above;
        } else {
            y = clampInt(capsule.top > screenH - capsule.bottom ? above : below, minY, maxY);
        }
        return new int[]{x, y};
    }

    /** Hides the open panel, then detaches its window once the exit has played. */
    private void dismissPanels() {
        final FrameLayout windowRoot = openPanelHost;
        if (windowRoot == null) {
            return;
        }
        openPanelHost = null;
        hidePanels();
        persistPanelStateNow();
        final int token = ++panelDismissToken;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token == panelDismissToken) {
                    removeOverlay(windowRoot);
                    attachedPanelHosts.remove(windowRoot);
                }
            }
        }, PANEL_HIDE_MS);
    }

    /** Drops every panel at once: rotation, service stop, and before a re-show. */
    private void discardPanels() {
        hidePanels();
        detachPanelWindows();
        persistPanelStateNow();
    }

    /**
     * Removes every attached panel window right now. The delayed removal in
     * {@link #dismissPanels()} is not the only way out: if anything invalidates
     * its token first, the window would stay registered and, being modal, would
     * keep swallowing the whole screen's touches.
     */
    private void detachPanelWindows() {
        for (int i = attachedPanelHosts.size() - 1; i >= 0; i--) {
            removeOverlay(attachedPanelHosts.get(i));
        }
        attachedPanelHosts.clear();
        openPanelHost = null;
        panelDismissToken++;
    }

    private void hidePanels() {
        if (colorPanel != null) {
            colorPanel.hide();
        }
        if (brushPanel != null) {
            brushPanel.hide();
        }
        if (moreMenu != null) {
            moreMenu.hide();
        }
        if (gesturePanel != null) {
            gesturePanel.hide();
        }
    }

    // ------------------------------------------------------------------
    // Brush / colour state
    // ------------------------------------------------------------------

    private void applyPenColor(int color) {
        penColor = color | 0xFF000000;
        settingsFor(penStyle).color = penColor;
        if (annotationView != null) {
            annotationView.setPenColor(penColor);
        }
        if (brushPanel != null) {
            brushPanel.setColor(penColor);
        }
        refreshCapsule();
        persistLater();
    }

    /** Colour-editor opacity applies to the tool that is selected right now. */
    private void applyToolAlpha(float alpha) {
        final BrushSettings settings = settingsFor(penStyle);
        settings.alpha = Math.max(MIN_PEN_ALPHA, Math.min(MAX_PEN_ALPHA, alpha));
        if (annotationView != null) {
            annotationView.setPenAlpha(Math.round(settings.alpha));
        }
        persistLater();
    }

    /**
     * Pushes the current tool's remembered brush values onto the canvas. Runs
     * on every tool switch so width, opacity and colour follow the pen instead
     * of leaking over from the previous one.
     */
    private void applyBrushToView() {
        if (annotationView == null) {
            return;
        }
        final BrushSettings settings = settingsFor(penStyle);
        penColor = settings.color;
        annotationView.setPenStyle(penStyle);
        annotationView.setPenColor(penColor);
        annotationView.setPenWidth(strokeWidthPx(penStyle, settings.width));
        annotationView.setPenAlpha(Math.round(settings.alpha));
    }

    private BrushSettings settingsFor(Types.PenStyle style) {
        BrushSettings settings = brushSettings.get(style);
        if (settings != null) {
            return settings;
        }
        final BrushPanel.Tool tool = brushTool(style);
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String prefix = BRUSH_KEY_PREFIX + style.name() + "_";
        settings = new BrushSettings(
                prefs.getFloat(prefix + "size", tool.defaultSize),
                prefs.getFloat(prefix + "alpha", MAX_PEN_ALPHA),
                prefs.getFloat(prefix + "sensitivity", 0.5f),
                prefs.getInt(prefix + "tip", 2),
                prefs.getInt(prefix + "color", defaultPenColor) | 0xFF000000);
        clampBrush(settings, tool);
        brushSettings.put(style, settings);
        return settings;
    }

    private static void clampBrush(BrushSettings settings, BrushPanel.Tool tool) {
        settings.width = Math.max(tool.minSize, Math.min(tool.maxSize, settings.width));
        settings.alpha = Math.max(MIN_PEN_ALPHA, Math.min(MAX_PEN_ALPHA, settings.alpha));
        settings.sensitivity = Math.max(0f, Math.min(1f, settings.sensitivity));
        settings.tip = Math.max(1, Math.min(4, settings.tip));
    }

    private static BrushPanel.Tool brushTool(Types.PenStyle style) {
        switch (style) {
            case PENCIL:
                return BrushPanel.Tool.PENCIL;
            case INK_PEN:
                return BrushPanel.Tool.INK_PEN;
            case MARKER:
                return BrushPanel.Tool.MARKER;
            case WATER_COLOR:
                return BrushPanel.Tool.WATER_COLOR;
            case ERASER:
                return BrushPanel.Tool.ERASER;
            case BALLPOINT:
            default:
                return BrushPanel.Tool.BALLPOINT;
        }
    }

    /**
     * Mi Canvas pen units -> on-screen stroke width in px.
     *
     * <p>Port of the deleted Kotlin app's {@code PenStyle.toPixels}
     * (PenStyle.kt:55-60): {@code raw = size * density}, plus 0.8px of marker
     * nib slack, then a per-tool floor (BALLPOINT 2.0, INK_PEN 0.8,
     * MARKER 2.2, WATER_COLOR 2.312). The eraser's size is a radius, so its
     * diameter doubles. {@link AnnotationView} still multiplies every stroke by
     * its own per-style factor, so that factor is divided out here and the ink
     * on screen is exactly the Mi Canvas width.</p>
     */
    private float strokeWidthPx(Types.PenStyle style, float units) {
        final float raw = units * density;
        final float width;
        switch (style) {
            case ERASER:
                width = raw * 2f;
                break;
            case MARKER:
                width = Math.max(raw + 0.8f, 2.2f);
                break;
            case BALLPOINT:
                width = Math.max(raw, 2f);
                break;
            case INK_PEN:
                width = Math.max(raw, 0.8f);
                break;
            case WATER_COLOR:
                width = Math.max(raw, 2.312f);
                break;
            case PENCIL:
            default:
                width = raw;
                break;
        }
        return Math.max(1f, width / strokeWidthFactor(style));
    }

    /** Keep in sync with AnnotationView's private widthFactor table. */
    private static float strokeWidthFactor(Types.PenStyle style) {
        switch (style) {
            case PENCIL:
                return 0.85f;
            case INK_PEN:
                return 1.2f;
            case MARKER:
                return 2.6f;
            case WATER_COLOR:
                return 2.0f;
            case ERASER:
                return 3.0f;
            case BALLPOINT:
            default:
                return 1f;
        }
    }

    // ------------------------------------------------------------------
    // Panel persistence (brush + colour + recent colours)
    // ------------------------------------------------------------------

    /** Old app's BrushConfig.RECENT_COLORS, then its PADDING_RECENT list. */
    private static final int[] DEFAULT_RECENT_COLORS = {
            0xFFA8D4F5, 0xFFE8464A, 0xFFFFFFFF, 0xFF000000,
            0xFF40A9FF, 0xFFFFC53D, 0xFFFF7A45, 0xFF096DD9,
            0xFFCF1322, 0xFFBAE7FF, 0xFFFFFB8F, 0xFFFFD8BF,
            0xFF87E8DE, 0xFFFFA39E, 0xFFFFADD2,
    };

    private void restoreBrushState() {
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String stored = prefs.getString(KEY_STYLE, null);
        if (stored != null) {
            for (Types.PenStyle style : Types.PenStyle.TOOLBAR) {
                if (style.name().equals(stored)) {
                    penStyle = style;
                    break;
                }
            }
        }
        penColor = prefs.getInt(KEY_COLOR, penColor) | 0xFF000000;
        // A tool that has never had its own colour stored starts from the
        // last single colour this app remembered.
        defaultPenColor = penColor;
    }

    private void loadRecentColors() {
        recentColors.clear();
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String stored = prefs.getString(KEY_RECENT_COLORS, null);
        if (stored != null && !stored.isEmpty()) {
            for (String part : stored.split(",")) {
                try {
                    appendRecentColor(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // Skip a corrupted entry instead of losing the whole strip.
                }
            }
        }
        for (int color : DEFAULT_RECENT_COLORS) {
            appendRecentColor(color);
        }
    }

    /** Adds to the MRU front (newest first), de-duplicated and capped at 12. */
    private void addToRecent(int color) {
        final int rgb = color | 0xFF000000;
        for (int i = recentColors.size() - 1; i >= 0; i--) {
            if (sameRgb(recentColors.get(i), rgb)) {
                recentColors.remove(i);
            }
        }
        recentColors.add(0, rgb);
        while (recentColors.size() > MAX_RECENT_COLORS) {
            recentColors.remove(recentColors.size() - 1);
        }
    }

    /** Adds to the back only when absent; pads the strip to twelve on first run. */
    private void appendRecentColor(int color) {
        final int rgb = color | 0xFF000000;
        if (recentColors.size() >= MAX_RECENT_COLORS) {
            return;
        }
        for (int i = 0; i < recentColors.size(); i++) {
            if (sameRgb(recentColors.get(i), rgb)) {
                return;
            }
        }
        recentColors.add(rgb);
    }

    private void rememberColor(int color) {
        addToRecent(color);
        persistLater();
    }

    private int[] recentColorsArray() {
        final int[] out = new int[recentColors.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = recentColors.get(i);
        }
        return out;
    }

    private static boolean sameRgb(int a, int b) {
        return ((a ^ b) & 0xFFFFFF) == 0;
    }

    private void persistLater() {
        mainHandler.removeCallbacks(persistTask);
        mainHandler.postDelayed(persistTask, PERSIST_DELAY_MS);
    }

    /**
     * Writes the selected tool, the colour, every known tool's brush values and
     * the colour MRU. Also commits the colour the editor settled on: the panel
     * reports every drag sample, so only the value that survives the quiet
     * period (or the panel's dismissal) counts as picked.
     */
    private void persistPanelStateNow() {
        mainHandler.removeCallbacks(persistTask);
        if (pendingRecentColor != 0) {
            addToRecent(pendingRecentColor);
            pendingRecentColor = 0;
        }

        final SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString(KEY_STYLE, penStyle.name());
        editor.putInt(KEY_COLOR, penColor);
        for (Map.Entry<Types.PenStyle, BrushSettings> entry : brushSettings.entrySet()) {
            final BrushSettings settings = entry.getValue();
            final String prefix = BRUSH_KEY_PREFIX + entry.getKey().name() + "_";
            editor.putFloat(prefix + "size", settings.width);
            editor.putFloat(prefix + "alpha", settings.alpha);
            editor.putFloat(prefix + "sensitivity", settings.sensitivity);
            editor.putInt(prefix + "tip", settings.tip);
            editor.putInt(prefix + "color", settings.color);
        }
        final StringBuilder joined = new StringBuilder();
        for (int i = 0; i < recentColors.size(); i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(recentColors.get(i).intValue());
        }
        editor.putString(KEY_RECENT_COLORS, joined.toString());
        editor.apply();
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private static int clampInt(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }

    // ------------------------------------------------------------------
    // Ball / capsule interaction
    // ------------------------------------------------------------------

    private void onCapsuleTouchDown() {
        // Never cancel a running morph on DOWN: cancel() leaves the capsule
        // at its last outline frame and the next gesture then starts from a
        // half-expanded capsule. CapsuleView swallows the whole stream while
        // transitioning, so ignoring the DOWN lets the morph land cleanly.
        if (morph != null && morph.isAnimating()) {
            return;
        }
        cancelRunningMorph();
    }

    /** Stops a morph and makes sure the capsule accepts the next gesture. */
    private void cancelRunningMorph() {
        if (morph != null && morph.isAnimating()) {
            morph.cancel();
        }
        if (host != null && host.capsule() != null) {
            host.capsule().setTransitioning(false);
        }
    }

    private void onBallTapped() {
        if (!started || host == null || morph == null || morph.isAnimating()) {
            return;
        }
        expandToDock(nearestEdge(morph.ballCx(), morph.ballCy()));
    }

    private void handleDragStart(boolean isBall, float viewX, float viewY) {
        if (!started || host == null || morph == null) {
            return;
        }
        dismissPanels();
        cancelRunningMorph();
        lastRawX = Float.NaN;
        lastRawY = Float.NaN;
        pendingRelease = false;

        if (isBall) {
            ballDrag = true;
            capsuleDrag = false;
            afterCollapseDrag = false;
            expanded = false;
            // grabDx/grabDy were captured on the proxy's DOWN event.
            host.ball().setBrush(penStyle, penColor);
        } else {
            ballDrag = false;
            capsuleDrag = true;
            afterCollapseDrag = false;
            // Collapse towards the anchor nearest the finger, not the capsule
            // centre, so the ball lands under the hand.
            final float fingerX = morph.wrapperX() + viewX;
            final float fingerY = morph.wrapperY() + viewY;
            collapseToNearest(fingerX, fingerY, true);
        }
    }

    private void handleDrag(float rawX, float rawY) {
        lastRawX = rawX;
        lastRawY = rawY;
        if (!started || morph == null) {
            return;
        }
        if (ballDrag) {
            morph.moveBallTo(rawX - grabDx, rawY - grabDy);
        } else if (capsuleDrag && afterCollapseDrag && !morph.isAnimating()) {
            // The collapse animation runs first. It lands the ball on the
            // nearest anchor, which is generally not under the finger; the
            // offset latched at hand-over keeps the first MOVE relative, so
            // the ball follows the finger instead of teleporting to it.
            morph.moveBallTo(rawX - collapseGrabDx, rawY - collapseGrabDy);
        }
    }

    private void handleDragEnd(float rawX, float rawY) {
        if (!started || morph == null) {
            return;
        }
        // UP carries the true release point; the last MOVE can be far behind.
        if (!Float.isNaN(rawX) && !Float.isNaN(rawY)) {
            lastRawX = rawX;
            lastRawY = rawY;
        }
        if (!ballDrag && !capsuleDrag) {
            return;
        }
        if (capsuleDrag && morph.isAnimating()) {
            // Keep the release until the collapse has landed.
            pendingRelease = true;
            return;
        }
        finishBallGesture();
    }

    /**
     * Applies the agreed release rule: nearest of the eight anchors to the
     * actual release point. Corners snap and stay a ball; edge centres expand
     * the capsule on exactly that edge.
     */
    private void finishBallGesture() {
        if (!started || host == null || morph == null) {
            return;
        }
        final float releaseX = Float.isNaN(lastRawX) ? morph.ballCx() : lastRawX;
        final float releaseY = Float.isNaN(lastRawY) ? morph.ballCy() : lastRawY;
        final Anchor nearest = nearestAnchor(releaseX, releaseY);

        ballDrag = false;
        capsuleDrag = false;
        afterCollapseDrag = false;
        pendingRelease = false;
        lastRawX = Float.NaN;
        lastRawY = Float.NaN;

        if (nearest.dock.corner()) {
            expanded = false;
            // The minimised ball is upright on every anchor; ease the brush
            // back to upright instead of popping if it came from a side dock.
            host.ball().animate().rotation(0f).setDuration(180L).start();
            host.capsule().setTransitioning(true);
            morph.beginSnap(nearest.cx, nearest.cy, new Runnable() {
                @Override
                public void run() {
                    if (!started || host == null) {
                        return;
                    }
                    host.capsule().setTransitioning(false);
                    saveBall(nearest.cx, nearest.cy);
                    syncProxyToHost();
                }
            });
        } else {
            expandToDock(nearest.dock);
        }
    }

    private void expandToDock(Types.Dock dock) {
        if (!started || host == null || morph == null) {
            return;
        }
        dismissPanels();
        cancelRunningMorph();
        final CapsuleView capsule = host.capsule();
        capsule.setDock(dock);
        capsule.enterCapsuleMode();
        final Point content = capsule.contentSize(dock);
        if (content != null) {
            host.setCapsuleSize(content.x, content.y);
        }
        final int capsuleW = host.capsuleWidth();
        final int capsuleH = host.capsuleHeight();
        final PointF target = SnapSolver.solve(boundsRect(), capsuleW, capsuleH,
                dock, insetPx, SnapSolver.Insets.ZERO, null);

        expanded = true;
        capsule.setTransitioning(true);
        // The ball rests upright in this app, but the capsule's tools are
        // dock-oriented. Turn the ball's glyph to the dock orientation while
        // the ball is still the visible object (before the 35% hand-over), so
        // the capsule never appears with a different glyph angle than the ball
        // it replaces. Only the brush glyph rotates, never the disc.
        host.ball().animate().rotation(dockToolRotation(dock)).setDuration(140L).start();
        morph.beginExpand(dock, target.x, target.y, new Runnable() {
            @Override
            public void run() {
                if (!started || host == null) {
                    return;
                }
                host.capsule().setTransitioning(false);
                expanded = true;
                if (pendingRelease) {
                    pendingRelease = false;
                    finishBallGesture();
                }
                syncProxyToHost();
            }
        });
    }

    private void collapseToNearest(float x, float y, boolean forCapsuleDrag) {
        if (!started || host == null || morph == null) {
            return;
        }
        dismissPanels();
        cancelRunningMorph();
        final Anchor anchor = nearestAnchor(x, y);
        expanded = false;
        // The ball hands over in the capsule's current tool orientation (so
        // nothing flips at the collapse hand-over) and only then settles
        // upright, which is the resting orientation this app wants. The
        // capsule keeps its own dock during a collapse, so read its angle
        // rather than the anchor's: a side-docked capsule can collapse to a
        // corner anchor.
        host.ball().animate().cancel();   // don't let a running expand animation overwrite the hand-over angle
        host.ball().setRotation(host.capsule().toolRotation());
        host.capsule().setTransitioning(true);
        morph.beginCollapse(anchor.cx, anchor.cy, new Runnable() {
            @Override
            public void run() {
                if (!started || host == null) {
                    return;
                }
                host.capsule().setTransitioning(false);
                // From here on a touch on the ball must be a tap, not a capsule
                // tool hit.
                host.capsule().enterBallMode(host.ballSizePx());
                host.setCapsuleSize(host.ballSizePx(), host.ballSizePx());
                // The ball is on screen now; turn the brush upright inside it.
                host.ball().animate().rotation(0f).setDuration(180L).start();
                if (forCapsuleDrag) {
                    afterCollapseDrag = true;
                    // Latch the finger-to-ball offset at hand-over. Without
                    // this the first MOVE after the collapse pinned the ball
                    // centre to the finger, a jump of up to half the screen.
                    collapseGrabDx = Float.isNaN(lastRawX) ? 0f : lastRawX - anchor.cx;
                    collapseGrabDy = Float.isNaN(lastRawY) ? 0f : lastRawY - anchor.cy;
                }
                saveBall(anchor.cx, anchor.cy);
                if (pendingRelease) {
                    pendingRelease = false;
                    finishBallGesture();
                }
                if (!proxyPinned) {
                    syncProxyToHost();
                }
            }
        });
    }

    private void onScreenChanged() {
        if (!started || host == null || morph == null) {
            return;
        }
        // Rotation / dock change: a panel placed for the old geometry is stale.
        discardPanels();
        closePalette(false);
        proxyPinned = false;
        swallowOutsideGesture = false;
        ballDrag = false;
        capsuleDrag = false;
        afterCollapseDrag = false;
        pendingRelease = false;
        cancelRunningMorph();

        updateScreenMetrics();
        expanded = false;

        final float half = host.ballSizePx() / 2f;
        final float minX = insetPx + half;
        final float maxX = screenW - insetPx - half;
        final float minY = insetPx + half;
        final float maxY = screenH - insetPx - half;
        final float cx = clamp(morph.ballCx(), minX, maxX);
        final float cy = clamp(morph.ballCy(), minY, maxY);

        host.capsule().enterBallMode(host.ballSizePx());
        host.capsule().setCollapseProgress(1f);
        host.setCollapseProgress(1f);
        host.setCapsuleSize(host.ballSizePx(), host.ballSizePx());
        host.captureScreenOrigin();
        morph.showBallAt(cx, cy, host.ballSizePx());
        saveBall(cx, cy);
        syncProxyToHost();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (started && host != null) {
                    host.captureScreenOrigin();
                    syncProxyToHost();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    private static final class Anchor {
        final Types.Dock dock;
        final float cx;
        final float cy;

        Anchor(Types.Dock dock, float cx, float cy) {
            this.dock = dock;
            this.cx = cx;
            this.cy = cy;
        }
    }

    private RectF boundsRect() {
        return new RectF(0f, 0f, screenW, screenH);
    }

    private Anchor nearestAnchor(float x, float y) {
        final int size = host.ballSizePx();
        final float half = size / 2f;
        final Map<Types.Dock, PointF> anchors = SnapSolver.solveAll(
                boundsRect(), size, size, insetPx, SnapSolver.Insets.ZERO);

        Types.Dock bestDock = Types.Dock.TOP;
        float bestX = 0f;
        float bestY = 0f;
        float bestDistance = Float.MAX_VALUE;
        for (Map.Entry<Types.Dock, PointF> entry : anchors.entrySet()) {
            final float cx = entry.getValue().x + half;
            final float cy = entry.getValue().y + half;
            final float dx = x - cx;
            final float dy = y - cy;
            final float distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestDock = entry.getKey();
                bestX = cx;
                bestY = cy;
            }
        }
        return new Anchor(bestDock, bestX, bestY);
    }

    private Types.Dock nearestEdge(float x, float y) {
        final float left = x;
        final float right = screenW - x;
        final float top = y;
        final float bottom = screenH - y;
        if (right <= left && right <= top && right <= bottom) {
            return Types.Dock.RIGHT;
        }
        if (left <= right && left <= top && left <= bottom) {
            return Types.Dock.LEFT;
        }
        if (bottom <= top) {
            return Types.Dock.BOTTOM;
        }
        return Types.Dock.TOP;
    }

    private static float clamp(float value, float min, float max) {
        if (max < min) {
            return (min + max) / 2f;
        }
        return Math.max(min, Math.min(value, max));
    }

    /**
     * Tool orientation while the capsule is docked: +90 on the left edge, 270
     * on the right (the same orientation as -90) and 0 on the top/bottom
     * edges. This is the capsule's own table; the minimised ball ignores it
     * and always shows the brush upright.
     */
    private static float dockToolRotation(Types.Dock dock) {
        if (dock == Types.Dock.LEFT) {
            return 90f;
        }
        if (dock == Types.Dock.RIGHT) {
            return -90f;
        }
        return 0f;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private float[] loadBallCentre() {
        final int size = host.ballSizePx();
        final float half = size / 2f;
        final float defaultCx = screenW - insetPx - half;
        final float defaultCy = screenH - insetPx - half;

        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final float savedCx = prefs.getFloat(KEY_BALL_CX, defaultCx);
        final float savedCy = prefs.getFloat(KEY_BALL_CY, defaultCy);

        final float minX = insetPx + half;
        final float maxX = screenW - insetPx - half;
        final float minY = insetPx + half;
        final float maxY = screenH - insetPx - half;
        return new float[]{
                clamp(savedCx, minX, maxX),
                clamp(savedCy, minY, maxY),
        };
    }

    private void saveBall(float cx, float cy) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat(KEY_BALL_CX, cx)
                .putFloat(KEY_BALL_CY, cy)
                .apply();
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void createNotificationChannel() {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.ga_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.ga_notification_channel_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        final Intent stopIntent = new Intent(this, FloatingService.class)
                .setAction(ACTION_STOP);
        final PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.ga_stop), stopPending).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(getString(R.string.ga_notification_title))
                .setContentText(getString(R.string.ga_notification_text))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(stopAction)
                .build();
    }

    private int pxToDp(int px) {
        return Math.round(px / Math.max(0.1f, density));
    }
}
