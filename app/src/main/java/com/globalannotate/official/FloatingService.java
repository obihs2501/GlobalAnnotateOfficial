package com.globalannotate.official;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.globalannotate.official.panels.BrushPanel;
import com.globalannotate.official.panels.ColorPanel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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
     * ({@code ColorPanel} is 360dp, {@code BrushPanel} 288dp) because the
     * WindowManager needs them before the panel has a parent.
     */
    private static final float COLOR_PANEL_WIDTH_DP = 360f;
    private static final float BRUSH_PANEL_WIDTH_DP = 288f;
    /** Free space kept between a panel and the capsule. */
    private static final float PANEL_GAP_DP = 12f;
    /** Both panels play a 150ms exit before their window is detached. */
    private static final long PANEL_HIDE_MS = 180L;
    /** Brush/colour edits are written once the sliders have been quiet this long. */
    private static final long PERSIST_DELAY_MS = 400L;

    private static final String KEY_STYLE = "style";
    private static final String KEY_COLOR = "color";
    private static final String KEY_RECENT_COLORS = "recentColors";
    private static final String BRUSH_KEY_PREFIX = "brush_";
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

    /** Per-tool brush state, in the panel's own units; loaded lazily from prefs. */
    private final HashMap<Types.PenStyle, BrushSettings> brushSettings = new HashMap<>();
    /** MRU of the colour editor, newest first, at most {@link #MAX_RECENT_COLORS}. */
    private final ArrayList<Integer> recentColors = new ArrayList<>();
    /** The colour the editor last reported; committed to the MRU on the next flush. */
    private int pendingRecentColor;

    private ColorPanel colorPanel;
    private BrushPanel brushPanel;
    private FrameLayout colorPanelHost;
    private FrameLayout brushPanelHost;
    /** Window root of the panel currently attached, or null. */
    private FrameLayout openPanelHost;
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
                    if (started && annotationView != null) {
                        annotationView.dispatchTouchEvent(event);
                    }
                }

                @Override
                public void onSplitActiveChanged(boolean active) {
                    // The canvas window stays touchable for fingers either way;
                    // the helper alone decides which events it reroutes.
                }
            };

    private final View.OnTouchListener proxyTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
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
        // A restored ball keeps the official edge orientation: LEFT +90,
        // RIGHT -90, everything else upright (II11lli.m3475IlIl1l11).
        host.ball().setRotation(ballRotationFor(nearestAnchor(ball[0], ball[1]).dock));
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
            annotationView.setPenStyle(style);
            refreshCapsule();
            // Mi Canvas keeps one brush state per pen: every tool tap re-opens
            // the popup on that tool's own width / opacity / sensitivity / tip.
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
            Toast.makeText(FloatingService.this, R.string.ga_more_hint,
                    Toast.LENGTH_SHORT).show();
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

        BrushSettings(float width, float alpha, float sensitivity, int tip) {
            this.width = width;
            this.alpha = alpha;
            this.sensitivity = sensitivity;
            this.tip = tip;
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
        if (panel == null || windowRoot == null) {
            return null;
        }
        // Only one panel window is ever attached; drop the previous one and
        // invalidate any exit removal still pending for it.
        panelDismissToken++;
        if (openPanelHost != null) {
            removeOverlay(openPanelHost);
            openPanelHost = null;
        }

        final int[] origin = panelOrigin(anchorX, width, height);
        if (!addOverlay(windowRoot, panelParams(width, height, origin[0], origin[1]))) {
            return null;
        }
        openPanelHost = windowRoot;
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
                }
            }
        }, PANEL_HIDE_MS);
    }

    /** Drops every panel at once: rotation, service stop, and before a re-show. */
    private void discardPanels() {
        panelDismissToken++;
        hidePanels();
        if (openPanelHost != null) {
            removeOverlay(openPanelHost);
            openPanelHost = null;
        }
        persistPanelStateNow();
    }

    private void hidePanels() {
        if (colorPanel != null) {
            colorPanel.hide();
        }
        if (brushPanel != null) {
            brushPanel.hide();
        }
    }

    // ------------------------------------------------------------------
    // Brush / colour state
    // ------------------------------------------------------------------

    private void applyPenColor(int color) {
        penColor = color | 0xFF000000;
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

    private void applyBrushToView() {
        if (annotationView == null) {
            return;
        }
        final BrushSettings settings = settingsFor(penStyle);
        annotationView.setPenStyle(penStyle);
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
                prefs.getInt(prefix + "tip", 2));
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
            // Official II11lli shows the minimised ball upright on the four
            // corner anchors (m3475IlIl1l11 returns 0 for corners).
            host.ball().setRotation(0f);
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
        // Official II11lli.m3494lIlilI1iii cancels the ball rotation as the
        // picker opens: animate() rotation 0 over 180 ms (it rotates only the
        // brush glyph inside the ball, never the disc).
        host.ball().animate().rotation(0f).setDuration(180L).start();
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
        // Official II11lli.m3484IIii1I sets the ball's brush rotation from the
        // picker position before the ball appears: LEFT +90, RIGHT -90, all
        // other positions upright.
        host.ball().animate().cancel();   // don't let a running expand rotation overwrite the dock rotation
        host.ball().setRotation(ballRotationFor(anchor.dock));
        host.capsule().animateToolRotation(ballRotationFor(anchor.dock), 180L);
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
     * Official II11lli.m3475IlIl1l11: the minimised ball's brush is rotated only
     * on the two vertical edge anchors, +90 on the left and -90 on the right;
     * top, bottom and the four corners are upright.
     */
    private static float ballRotationFor(Types.Dock dock) {
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
