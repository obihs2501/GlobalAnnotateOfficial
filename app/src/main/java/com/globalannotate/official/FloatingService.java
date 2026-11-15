package com.globalannotate.official;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.WindowManager;
import android.widget.Toast;

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
    private static final float PEN_WIDTH_DP = 4f;

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
    private float lastRawX = Float.NaN;
    private float lastRawY = Float.NaN;

    private int screenW;
    private int screenH;
    private float density;
    private int insetPx;

    private Types.PenStyle penStyle = Types.PenStyle.BALLPOINT;
    private int penColor = Types.FIXED_COLORS[3];

    private final FloatingMorph.Placement placement = new FloatingMorph.Placement() {
        @Override
        public void onVisibleRect(Rect r, boolean smooth) {
            if (r == null || proxyPinned) {
                return;
            }
            updateProxyRect(r);
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
                final Rect visible = morph.visibleRect();
                final float x = visible != null ? visible.exactCenterX() : morph.ballCx();
                final float y = visible != null ? visible.exactCenterY() : morph.ballCy();
                collapseToNearest(x, y, false);
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
        annotationView.setPenWidth(PEN_WIDTH_DP * density);
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
        }

        @Override
        public void onColor(int color) {
            penColor = color;
            annotationView.setPenColor(color);
            refreshCapsule();
        }

        @Override
        public void onCustomColor(int anchorX) {
            Toast.makeText(FloatingService.this, R.string.ga_color_picker_hint,
                    Toast.LENGTH_SHORT).show();
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
                public void onDragEnd() {
                    handleDragEnd();
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
    // Ball / capsule interaction
    // ------------------------------------------------------------------

    private void onCapsuleTouchDown() {
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
            // The collapse animation runs first; after it the ball follows the
            // finger directly.
            morph.moveBallTo(rawX, rawY);
        }
    }

    private void handleDragEnd() {
        if (!started || morph == null) {
            return;
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
        cancelRunningMorph();
        final Anchor anchor = nearestAnchor(x, y);
        expanded = false;
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
