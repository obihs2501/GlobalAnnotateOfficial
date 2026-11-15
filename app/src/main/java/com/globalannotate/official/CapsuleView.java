package com.globalannotate.official;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Java port of the old Kotlin {@code ToolbarView}: the floating pen capsule.
 *
 * <p>The capsule is one absolutely positioned {@link FrameLayout}. Its content
 * follows Mi Canvas' own table: a 108dp thick pill with a 108dp head (undo /
 * redo), a 124dp tool lane and a 204dp tail (six colour slots and the more
 * button). The vertical docks transpose the same table, as the official
 * picker does.</p>
 *
 * <p>The collapse morph is the important part. The view's outline provider is
 * driven directly by {@link #setCollapseProgress(float)}: the full round rect
 * lerps into a circle of the minimised-ball diameter, anchored at
 * {@link #toolSlot()}. At {@code p == 1} the drawn shape is exactly the ball,
 * so the host can hand the visible object over from the ball to the capsule
 * without any size or position jump. Child alpha is {@code clamp(1 - 2p)} and
 * the selected tool slides towards the anchor, exactly like the official
 * {@code m35856lIil11} frame.</p>
 *
 * <p>Touch handling is copied from the old view because the proxy window
 * dispatches events straight into {@link #dispatchTouchEvent(MotionEvent)}:
 * a DOWN always starts a gesture, a 30dp move in ball mode (or the platform
 * slop in capsule mode) starts a drag, and a DOWN that arrives while
 * {@code transitioning} is true is swallowed for the whole gesture.</p>
 */
public final class CapsuleView extends FrameLayout {

    /** Tap callbacks; every anchor is a screen X coordinate, like the old view. */
    public interface Listener {
        void onTool(Types.PenStyle style, int anchorX);

        void onColor(int color);

        void onCustomColor(int anchorX);

        void onUndo();

        void onRedo();

        void onMore(int anchorX);
    }

    /** Drag stream; coordinates match the old ToolbarView contract. */
    public interface DragListener {
        void onDragStart(boolean isBall, float viewX, float viewY);

        void onDrag(float dx, float dy, float rawX, float rawY);

        void onDragEnd();
    }

    // -------------------- geometry, from the old ToolbarView companion --------------------
    /** Capsule thickness (MAXIMIZE): 108dp. */
    private static final float CAPSULE_DP = 108f;
    /** Horizontal head (undo / redo area). */
    private static final float HEAD_DP = 108f;
    /** Tail: two 44dp colour rows plus the 48dp more button and an 8dp edge. */
    private static final float TAIL_DP = 204f;
    /** Vertical head is shorter: undo / redo sit across the capsule. */
    private static final float HEAD_VERTICAL_DP = 64f;
    /** Tool lane length; the capsule clips the overflow. */
    private static final int TOOL_LONG_DP = 124;
    private static final int LANE_TOP_DP = 8;
    private static final int NORMAL_W_DP = 48;
    private static final int LARGE_W_DP = 68;
    private static final int SLOT_DP = 44;
    /** Touch box of the three circles (the old BUTTON_BOX_DP). */
    private static final int BUTTON_BOX_DP = 48;
    /** Drawn circle diameter requested for undo / redo / more. */
    private static final float BUTTON_CIRCLE_DP = 40f;
    private static final float BUTTON_GLYPH_DP = 20f;
    private static final float DOT_DP = 32f;
    private static final int HANDLE_LONG_DP = 32;
    private static final int HANDLE_SHORT_DP = 4;
    private static final int HANDLE_TOP_DP = 4;
    private static final int HANDLE_EDGE_DP = 4;
    /** A 124dp lane inside a 108dp capsule. */
    private static final int OVERHANG_DP = 24;
    private static final int INNER_GAP_DP = 8;
    /**
     * Official {@code cp_brush_inner_distance}: unselected tools rest this far
     * out of the lane; the selected tool retracts to its laid-out slot.
     */
    private static final float BRUSH_INNER_DISTANCE_DP = 18f;
    /** Duration of the official tool protrusion swap. */
    private static final long BRUSH_STEP_MS = 220L;
    private static final float SCREEN_INSET_DP = 16f;
    private static final float MAX_HORIZONTAL_LENGTH_DP = 734f;
    private static final float MAX_VERTICAL_LENGTH_DP = 698f;
    private static final float MIN_LANE_DP = 120f;
    /** Ball drag threshold before a gesture counts as a drag. */
    private static final float BALL_TAP_SLOP_DP = 30f;

    // -------------------- colours (the official light capsule) --------------------
    private static final int CAPSULE_BG = 0xFFFFFFFF;
    private static final int HANDLE_COLOR = 0x1A000000;
    private static final int BUTTON_BG = 0x0F000000;
    private static final int ICON_COLOR = 0xCC000000;
    private static final int ICON_DISABLED = 0x33000000;
    /** Invented: a very light pill behind the selected tool. */
    private static final int SELECTED_BG = 0x10000000;
    /** Ring used where a near-white swatch would vanish on the capsule. */
    private static final int NEAR_WHITE_RING = 0x66000000;
    private static final int[] WHEEL_COLORS = {
            0xFFE7E040, 0xFFEEAA3C, 0xFFE8403B, 0xFFB33ED5,
            0xFF694AE8, 0xFF3CCAE7, 0xFF3CE885, 0xFF89E743,
    };

    private static final int HIT_TOOL = 0;
    private static final int HIT_SLOT = 1;
    private static final int HIT_UNDO = 2;
    private static final int HIT_REDO = 3;
    private static final int HIT_MORE = 4;

    private static final int BTN_UNDO = 0;
    private static final int BTN_REDO = 1;
    private static final int BTN_MORE = 2;

    private final Listener listener;
    private final float density;
    private final int scaledTouchSlop;

    private final List<ToolView> tools = new ArrayList<>(6);
    private final List<SwatchView> swatches = new ArrayList<>(6);
    private final List<Hit> hits = new ArrayList<>(16);

    private final ButtonView undo;
    private final ButtonView redo;
    private final ButtonView more;
    private final HandleView handle;

    private final ViewOutlineProvider outlineProvider;

    private DragListener dragListener;
    private Runnable ballTapListener;
    private Runnable touchDownListener;

    private Types.Dock dock = Types.Dock.TOP;
    /** Dock the tool boxes were last laid out for; a change snaps the offsets. */
    private Types.Dock laidOutDock;
    private Types.PenStyle selectedStyle = Types.PenStyle.INK_PEN;

    /** Tool protrusion blend: from {@link #offsetFromStyle} to {@link #offsetToStyle}. */
    private Types.PenStyle offsetFromStyle = Types.PenStyle.INK_PEN;
    private Types.PenStyle offsetToStyle = Types.PenStyle.INK_PEN;
    private float offsetT = 1f;
    private boolean offsetInitialized;
    private ValueAnimator offsetAnimator;

    /** 0 = full pill, 1 = the minimised ball circle. */
    private float collapse;
    private final PointF collapseCentre = new PointF();
    private float screenWidthDp = 600f;
    private float screenHeightDp = 900f;

    private boolean ballMode;
    private int ballDiameterPx;
    private boolean transitioning;
    private boolean swallowGesture;

    private float downX;
    private float downY;
    private boolean dragging;

    private float capsuleW;
    private float capsuleH;
    private float laneLength;

    public CapsuleView(Context context, Listener listener) {
        super(context);
        if (listener == null) {
            throw new IllegalArgumentException("listener == null");
        }
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        this.scaledTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setClipChildren(true);
        setClipToPadding(true);

        outlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getWidth();
                int h = view.getHeight();
                if (w <= 0 || h <= 0) {
                    return;
                }
                float width = w;
                float height = h;
                float r = Math.min(width, height) / 2f;
                float p = collapse;
                if (p <= 0.001f) {
                    outline.setRoundRect(0, 0, w, h, r);
                    return;
                }
                // The official lIil11 provider: edge lerp towards the collapse
                // anchor plus the independent radius override that takes the
                // 108dp capsule down to the minimised ball.
                float ballR = ballDiameterPx > 0 ? ballDiameterPx / 2f : r;
                float rr = r + (ballR - r) * p;
                float cx = width >= 2f * rr
                        ? clamp(collapseCentre.x, rr, width - rr)
                        : width / 2f;
                float cy = height >= 2f * rr
                        ? clamp(collapseCentre.y, rr, height - rr)
                        : height / 2f;
                float left = (cx - rr) * p;
                float right = width + (cx + rr - width) * p;
                float top = (cy - rr) * p;
                float bottom = height + (cy + rr - height) * p;
                if (p >= 0.999f) {
                    outline.setOval((int) (cx - rr), (int) (cy - rr),
                            (int) (cx + rr), (int) (cy + rr));
                } else {
                    float radius = Math.min(right - left, bottom - top) / 2f;
                    outline.setRoundRect((int) left, (int) top, (int) right, (int) bottom, radius);
                }
            }
        };
        setOutlineProvider(outlineProvider);
        setClipToOutline(true);

        for (Types.PenStyle style : Types.PenStyle.TOOLBAR) {
            ToolView tool = new ToolView(context, style);
            tools.add(tool);
            addView(tool, new LayoutParams(0, 0));
        }

        handle = new HandleView(context);
        addView(handle, new LayoutParams(0, 0));

        undo = new ButtonView(context, BTN_UNDO);
        redo = new ButtonView(context, BTN_REDO);
        addView(undo, new LayoutParams(0, 0));
        addView(redo, new LayoutParams(0, 0));

        for (int i = 0; i < Types.FIXED_COLORS.length; i++) {
            SwatchView slot = new SwatchView(context, i, Types.FIXED_COLORS[i]);
            swatches.add(slot);
            addView(slot, new LayoutParams(0, 0));
        }
        SwatchView wheel = new SwatchView(context, Types.WHEEL_SLOT, Color.BLACK);
        wheel.wheel = true;
        swatches.add(wheel);
        addView(wheel, new LayoutParams(0, 0));

        more = new ButtonView(context, BTN_MORE);
        addView(more, new LayoutParams(0, 0));

        recompute();
        update(Types.PenStyle.INK_PEN, Color.BLACK, false, false);
    }

    // -------------------- public state --------------------

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setBallTapListener(Runnable listener) {
        ballTapListener = listener;
    }

    public void setTouchDownListener(Runnable listener) {
        touchDownListener = listener;
    }

    public void setScreenSizeDp(float widthDp, float heightDp) {
        if (widthDp == screenWidthDp && heightDp == screenHeightDp) {
            return;
        }
        screenWidthDp = widthDp;
        screenHeightDp = heightDp;
        requestLayout();
    }

    public void setDock(Types.Dock value) {
        Types.Dock next = value != null ? value : Types.Dock.TOP;
        if (dock == next) {
            return;
        }
        dock = next;
        requestLayout();
    }

    public Types.Dock dock() {
        return dock;
    }

    /** Window size in px the capsule needs for a dock (does not touch state). */
    public Point contentSize(Types.Dock value) {
        boolean vertical = value != null && value.vertical();
        float[] metrics = metricsFor(vertical);
        return new Point(dp(metrics[0]), dp(metrics[1]));
    }

    /**
     * Capsule-local centre of the selected tool slot in px. This is the point
     * the capsule collapses into and the point the host maps onto the ball
     * centre during a morph.
     */
    public PointF toolSlot() {
        PointF out = new PointF();
        toolSlotInto(out);
        return out;
    }

    /**
     * Ball diameter used by the collapse outline. The host keeps this in sync
     * with the minimised ball, so {@code setCollapseProgress(1)} draws a circle
     * of exactly the ball size at {@link #toolSlot()}.
     */
    public void setBallDiameterPx(int diameterPx) {
        if (diameterPx < 0 || ballDiameterPx == diameterPx) {
            return;
        }
        ballDiameterPx = diameterPx;
        invalidateOutline();
        invalidate();
    }

    /** Collapsed state: the view is only the selected tool inside the ball. */
    public void enterBallMode(int diameterPx) {
        ballMode = true;
        ballDiameterPx = Math.max(0, diameterPx);
        collapseCentre.set(diameterPx / 2f, diameterPx / 2f);
        requestLayout();
        measure(exactly(diameterPx), exactly(diameterPx));
        layout(0, 0, diameterPx, diameterPx);
        applyCollapseProgress(1f);
    }

    /**
     * Capsule state at the start of an expand. The view is laid out at the
     * capsule's own content size and the outline starts as the ball circle at
     * the selected tool slot; the caller animates the progress towards 0.
     */
    public void enterCapsuleMode() {
        ballMode = false;
        requestLayout();
        recompute();
        int w = dp(capsuleW);
        int h = dp(capsuleH);
        measure(exactly(w), exactly(h));
        layout(0, 0, w, h);
        toolSlotInto(collapseCentre);
        applyCollapseProgress(1f);
    }

    public void setCollapseProgress(float progress) {
        applyCollapseProgress(progress);
    }

    public float collapseProgress() {
        return collapse;
    }

    public void setTransitioning(boolean value) {
        transitioning = value;
    }

    public void update(Types.PenStyle selected, int color, boolean canUndo, boolean canRedo) {
        if (selected != null) {
            if (!offsetInitialized) {
                // First bind: no protrusion animation from the default style.
                selectedStyle = selected;
                offsetFromStyle = selected;
                offsetToStyle = selected;
                offsetT = 1f;
                offsetInitialized = true;
            } else if (selected != selectedStyle) {
                // Official m7435II11lli: the old tool slides out while the new
                // one slides home; both share one animation.
                offsetFromStyle = selectedStyle;
                selectedStyle = selected;
                offsetToStyle = selected;
                startToolOffsetAnimation();
            }
        }
        for (ToolView tool : tools) {
            tool.setColor(color);
            tool.setChecked(tool.style == selectedStyle);
        }
        boolean onFixedSlot = false;
        for (int i = 0; i < Types.FIXED_COLORS.length; i++) {
            SwatchView slot = swatches.get(i);
            boolean checked = sameRgb(slot.color, color);
            slot.setChecked(checked);
            if (checked) {
                onFixedSlot = true;
            }
        }
        SwatchView wheel = swatches.get(Types.WHEEL_SLOT);
        wheel.setLiveColor(color);
        wheel.setChecked(!onFixedSlot);
        undo.setEnabledVisual(canUndo);
        redo.setEnabledVisual(canRedo);
        if (collapse > 0f) {
            // The anchor moves with the selected tool; keep a running morph
            // frame (and the ball-shaped p == 1 frame) consistent.
            applyCollapseProgress(collapse);
        }
        if (ballMode) {
            // A different tool may be the one the ball draws now; re-measure it
            // at the ball square like the old view did.
            requestLayout();
        }
        invalidate();
    }

    // -------------------- geometry --------------------

    private static float clamp(float value, float min, float max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private float toolWidthDp(Types.PenStyle style) {
        return style == Types.PenStyle.ERASER ? LARGE_W_DP : NORMAL_W_DP;
    }

    private float toolsLengthDp() {
        float total = 0f;
        for (Types.PenStyle style : Types.PenStyle.TOOLBAR) {
            total += toolWidthDp(style);
        }
        return total;
    }

    /** Capsule size in dp for a dock: {width, height, laneLength}. */
    private float[] metricsFor(boolean vertical) {
        float available = vertical
                ? screenHeightDp - 2f * SCREEN_INSET_DP
                : screenWidthDp - 2f * SCREEN_INSET_DP;
        float cap = vertical ? MAX_VERTICAL_LENGTH_DP : MAX_HORIZONTAL_LENGTH_DP;
        float avail = Math.min(available, cap);
        float head = vertical ? HEAD_VERTICAL_DP : HEAD_DP;
        float tail = TAIL_DP;
        float lane = Math.min(avail - head - tail, toolsLengthDp());
        if (lane < MIN_LANE_DP) {
            lane = MIN_LANE_DP;
        }
        if (vertical) {
            return new float[]{CAPSULE_DP, head + lane + tail, lane};
        }
        return new float[]{head + lane + tail, CAPSULE_DP, lane};
    }

    private void recompute() {
        float[] metrics = metricsFor(dock.vertical());
        capsuleW = metrics[0];
        capsuleH = metrics[1];
        laneLength = metrics[2];
    }

    /** Fills {@code out} with the capsule-local selected tool centre in px. */
    private void toolSlotInto(PointF out) {
        float[] metrics = metricsFor(dock.vertical());
        float w = dp(metrics[0]);
        float h = dp(metrics[1]);
        float r = Math.min(w, h) / 2f;

        float cursor = 0f;
        for (Types.PenStyle style : Types.PenStyle.TOOLBAR) {
            if (style == selectedStyle) {
                break;
            }
            cursor += dp(toolWidthDp(style));
        }
        float shortSide = dp(toolWidthDp(selectedStyle));

        if (dock.vertical()) {
            float laneX = dock.onLeft() ? -dp(OVERHANG_DP) : dp(INNER_GAP_DP);
            float gx = laneX + dp(TOOL_LONG_DP) / 2f;
            float gy = dp(HEAD_VERTICAL_DP) + cursor + shortSide / 2f;
            out.set(r, clamp(gy, r, Math.max(r, h - r)));
        } else {
            float gx = dp(HEAD_DP) + cursor + shortSide / 2f;
            float gy = dp(LANE_TOP_DP) + dp(TOOL_LONG_DP) / 2f;
            out.set(clamp(gx, r, Math.max(r, w - r)), r);
        }
    }

    /** True when the view is currently laid out as the minimised ball. */
    private boolean isBallLayout() {
        return ballMode && ballDiameterPx > 0
                && getWidth() == ballDiameterPx && getHeight() == ballDiameterPx;
    }

    /**
     * Gesture mode follows the actual frame as well as the flag: a capsule that
     * the host has already sized back up for an expand must never keep treating
     * taps as ball taps.
     */
    private boolean ballGesture() {
        return ballMode && (ballDiameterPx <= 0 || isBallLayout());
    }

    private ToolView selectedTool() {
        for (ToolView tool : tools) {
            if (tool.style == selectedStyle) {
                return tool;
            }
        }
        return tools.get(0);
    }

    // -------------------- measure / layout --------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        recompute();
        int desiredW = ballMode ? ballDiameterPx : dp(capsuleW);
        int desiredH = ballMode ? ballDiameterPx : dp(capsuleH);
        int measuredW = resolveSize(desiredW, widthMeasureSpec);
        int measuredH = resolveSize(desiredH, heightMeasureSpec);
        setMeasuredDimension(measuredW, measuredH);

        boolean ballLayout = ballMode && ballDiameterPx > 0
                && measuredW == ballDiameterPx && measuredH == ballDiameterPx;
        boolean vertical = dock.vertical();

        int slotBox = dp(SLOT_DP);
        int buttonBox = dp(BUTTON_BOX_DP);
        int handleW = vertical ? dp(HANDLE_SHORT_DP) : dp(HANDLE_LONG_DP);
        int handleH = vertical ? dp(HANDLE_LONG_DP) : dp(HANDLE_SHORT_DP);
        handle.measure(exactly(handleW), exactly(handleH));
        undo.measure(exactly(buttonBox), exactly(buttonBox));
        redo.measure(exactly(buttonBox), exactly(buttonBox));
        more.measure(exactly(buttonBox), exactly(buttonBox));
        for (SwatchView slot : swatches) {
            slot.measure(exactly(slotBox), exactly(slotBox));
        }

        ToolView selected = selectedTool();
        for (ToolView tool : tools) {
            if (ballLayout) {
                if (tool == selected) {
                    tool.setBallArt(true);
                    tool.setRotationDeg(0f);
                    tool.measure(exactly(ballDiameterPx), exactly(ballDiameterPx));
                } else {
                    tool.setBallArt(false);
                    tool.setRotationDeg(0f);
                    tool.measure(exactly(1), exactly(1));
                }
            } else {
                tool.setBallArt(false);
                int shortPx = dp(toolWidthDp(tool.style));
                int longPx = dp(TOOL_LONG_DP);
                if (vertical) {
                    tool.measure(exactly(longPx), exactly(shortPx));
                } else {
                    tool.measure(exactly(shortPx), exactly(longPx));
                }
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        recompute();
        hits.clear();
        if (laidOutDock != dock) {
            laidOutDock = dock;
            snapToolOffsets();
        }

        if (isBallLayout()) {
            int d = ballDiameterPx;
            ToolView selected = selectedTool();
            for (ToolView tool : tools) {
                if (tool == selected) {
                    tool.setRotationDeg(0f);
                    tool.layout(0, 0, d, d);
                } else {
                    tool.setRotationDeg(0f);
                    tool.layout(-tool.getMeasuredWidth(), -tool.getMeasuredHeight(), 0, 0);
                }
            }
            park(handle);
            park(undo);
            park(redo);
            park(more);
            for (SwatchView slot : swatches) {
                park(slot);
            }
            applyCollapseProgress(collapse);
            return;
        }

        boolean vertical = dock.vertical();
        int w = dp(capsuleW);
        int h = dp(capsuleH);
        int head = dp(vertical ? HEAD_VERTICAL_DP : HEAD_DP);
        int lane = dp(laneLength);
        int slotBox = dp(SLOT_DP);
        int buttonBox = dp(BUTTON_BOX_DP);
        int c = head + lane;

        if (vertical) {
            // The lane is wider than the capsule; the official layout hangs it
            // off the screen side and clips it with the pill outline.
            int handleX = dock.onLeft()
                    ? w - dp(HANDLE_SHORT_DP) - dp(HANDLE_EDGE_DP)
                    : dp(HANDLE_EDGE_DP);
            int handleY = (h - dp(HANDLE_LONG_DP)) / 2;
            handle.layout(handleX, handleY,
                    handleX + dp(HANDLE_SHORT_DP), handleY + dp(HANDLE_LONG_DP));

            undo.layout(dp(8), dp(16), dp(8) + buttonBox, dp(16) + buttonBox);
            redo.layout(dp(52), dp(16), dp(52) + buttonBox, dp(16) + buttonBox);

            // Row-major 3x2 grid with the colour wheel in the last cell:
            // (red|yellow), (blue|black), (white|wheel).
            int innerX = dock.onLeft() ? dp(10) : dp(54);
            int outerX = dock.onLeft() ? dp(54) : dp(10);
            swatches.get(0).layout(innerX, c + dp(8), innerX + slotBox, c + dp(8) + slotBox);
            swatches.get(1).layout(outerX, c + dp(8), outerX + slotBox, c + dp(8) + slotBox);
            swatches.get(2).layout(innerX, c + dp(52), innerX + slotBox, c + dp(52) + slotBox);
            swatches.get(3).layout(outerX, c + dp(52), outerX + slotBox, c + dp(52) + slotBox);
            swatches.get(4).layout(innerX, c + dp(96), innerX + slotBox, c + dp(96) + slotBox);
            swatches.get(5).layout(outerX, c + dp(96), outerX + slotBox, c + dp(96) + slotBox);

            more.layout(dp(30), c + dp(148), dp(30) + buttonBox, c + dp(148) + buttonBox);
        } else {
            int handleX = (w - dp(HANDLE_LONG_DP)) / 2;
            handle.layout(handleX, dp(HANDLE_TOP_DP),
                    handleX + dp(HANDLE_LONG_DP), dp(HANDLE_TOP_DP) + dp(HANDLE_SHORT_DP));

            undo.layout(dp(16), dp(30), dp(16) + buttonBox, dp(30) + buttonBox);
            redo.layout(dp(60), dp(30), dp(60) + buttonBox, dp(30) + buttonBox);

            // Row-major 2x3 grid, colour wheel last:
            // (red|yellow|blue) then (black|white|wheel).
            int[] xs = {8, 52, 96, 8, 52, 96};
            int[] ys = {10, 10, 10, 54, 54, 54};
            for (int i = 0; i < 6; i++) {
                int x = c + dp(xs[i]);
                int y = dp(ys[i]);
                swatches.get(i).layout(x, y, x + slotBox, y + slotBox);
            }
            more.layout(c + dp(148), dp(30), c + dp(148) + buttonBox, dp(30) + buttonBox);
        }

        int laneX = vertical
                ? (dock.onLeft() ? -dp(OVERHANG_DP) : dp(INNER_GAP_DP))
                : head;
        int laneY = vertical ? dp(HEAD_VERTICAL_DP) : dp(LANE_TOP_DP);
        int cursor = 0;
        float rotation = vertical ? (dock.onLeft() ? 90f : 270f) : 0f;
        for (ToolView tool : tools) {
            int shortPx = dp(toolWidthDp(tool.style));
            tool.setRotationDeg(rotation);
            if (vertical) {
                tool.layout(laneX, laneY + cursor,
                        laneX + dp(TOOL_LONG_DP), laneY + cursor + shortPx);
            } else {
                tool.layout(laneX + cursor, laneY,
                        laneX + cursor + shortPx, laneY + dp(TOOL_LONG_DP));
            }
            cursor += shortPx;
        }

        for (ToolView tool : tools) {
            addHit(tool, HIT_TOOL, -1, tool.style);
        }
        for (int i = 0; i < swatches.size(); i++) {
            addHit(swatches.get(i), HIT_SLOT, i, null);
        }
        addHit(undo, HIT_UNDO, -1, null);
        addHit(redo, HIT_REDO, -1, null);
        addHit(more, HIT_MORE, -1, null);

        // The tool boxes just moved: re-apply the current morph frame so an
        // interrupted animation cannot leave a stale translation behind.
        applyCollapseProgress(collapse);
    }

    private void park(View view) {
        view.layout(-view.getMeasuredWidth(), -view.getMeasuredHeight(), 0, 0);
    }

    private void addHit(View view, int kind, int index, Types.PenStyle style) {
        hits.add(new Hit(new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()),
                view, kind, index, style));
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    // -------------------- collapse frame --------------------

    /**
     * One frame of the official collapse: alpha {@code clamp(1 - 2p)}, the
     * selected tool slides towards the anchor (it is the only opaque child),
     * then the outline is recomputed. The anchor is always the tool slot, so
     * {@code p == 1} draws the ball circle exactly under the selected tool.
     */
    private void applyCollapseProgress(float progress) {
        collapse = clamp(progress, 0f, 1f);

        if (isBallLayout()) {
            collapseCentre.set(ballDiameterPx / 2f, ballDiameterPx / 2f);
        } else {
            toolSlotInto(collapseCentre);
        }

        float alpha = clamp(1f - 2f * collapse, 0f, 1f);
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            getChildAt(i).setAlpha(alpha);
        }

        ToolView selected = selectedTool();
        float anchorX = collapseCentre.x;
        float anchorY = collapseCentre.y;
        // Official setDistance(): every tool rests on the distance vector of its
        // orientation; the active one animates home to (0, 0).
        float outX = 0f;
        float outY = 0f;
        if (!isBallLayout()) {
            float distance = BRUSH_INNER_DISTANCE_DP * density;
            if (dock.vertical()) {
                outX = dock.onLeft() ? -distance : distance;
            } else {
                // LEFT/RIGHT map to 90/270 and both horizontal docks to ROT_0,
                // whose distance vector is +y (I1Iili.m35807I11IIlI).
                outY = distance;
            }
        }
        for (ToolView tool : tools) {
            float to = tool.style == offsetToStyle ? 0f : 1f;
            float from = tool.style == offsetFromStyle ? 0f : 1f;
            float f = from + (to - from) * offsetT;
            float fx = outX * f;
            float fy = outY * f;
            if (tool == selected) {
                float cx = (tool.getLeft() + tool.getRight()) / 2f;
                float cy = (tool.getTop() + tool.getBottom()) / 2f;
                tool.setAlpha(1f);
                tool.setTranslationX(fx + (anchorX - cx) * collapse);
                tool.setTranslationY(fy + (anchorY - cy) * collapse);
            } else {
                // Non-selected tools fade out over the same 0..0.5 collapse
                // span, so their protrusion blend must go with them.
                tool.setTranslationX(fx * (1f - collapse));
                tool.setTranslationY(fy * (1f - collapse));
            }
        }

        invalidateOutline();
        invalidate();
    }

    private void startToolOffsetAnimation() {
        if (offsetAnimator != null) {
            offsetAnimator.cancel();
            offsetAnimator = null;
        }
        offsetT = 0f;
        offsetAnimator = ValueAnimator.ofFloat(0f, 1f);
        offsetAnimator.setDuration(BRUSH_STEP_MS);
        offsetAnimator.setInterpolator(new DecelerateInterpolator());
        offsetAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                offsetT = (float) animation.getAnimatedValue();
                applyCollapseProgress(collapse);
            }
        });
        offsetAnimator.start();
        applyCollapseProgress(collapse);
    }

    /**
     * Dock changes move every tool box, so the protrusion must snap to the new
     * direction instead of sliding from a vector that no longer exists.
     */
    private void snapToolOffsets() {
        if (offsetAnimator != null) {
            offsetAnimator.cancel();
            offsetAnimator = null;
        }
        offsetFromStyle = offsetToStyle;
        offsetT = 1f;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (offsetAnimator != null) {
            offsetAnimator.cancel();
            offsetAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    // -------------------- drawing --------------------

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // The overlay window is translucent, so a stale frame survives unless
        // this view clears its own clipped area first (as the old view did).
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.drawColor(CAPSULE_BG);
        super.dispatchDraw(canvas);
    }

    // -------------------- touch --------------------

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            swallowGesture = transitioning;
            if (touchDownListener != null) {
                touchDownListener.run();
            }
            downX = event.getRawX();
            downY = event.getRawY();
            dragging = false;
            return true;
        }
        if (swallowGesture) {
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                float rawX = event.getRawX();
                float rawY = event.getRawY();
                if (!dragging) {
                    boolean ball = ballGesture();
                    float threshold = ball ? BALL_TAP_SLOP_DP * density : scaledTouchSlop;
                    double distance = Math.hypot(rawX - downX, rawY - downY);
                    if (distance > threshold) {
                        dragging = true;
                        if (dragListener != null) {
                            dragListener.onDragStart(ball, event.getX(), event.getY());
                        }
                        downX = rawX;
                        downY = rawY;
                    }
                }
                if (dragging) {
                    if (dragListener != null) {
                        dragListener.onDrag(rawX - downX, rawY - downY, rawX, rawY);
                    }
                    downX = rawX;
                    downY = rawY;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (dragging) {
                    if (dragListener != null) {
                        dragListener.onDragEnd();
                    }
                } else if (ballGesture()) {
                    if (ballTapListener != null) {
                        ballTapListener.run();
                    }
                } else {
                    handleTap(event.getX(), event.getY());
                }
                dragging = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (dragging && dragListener != null) {
                    dragListener.onDragEnd();
                }
                dragging = false;
                return true;
            }
            default:
                return true;
        }
    }

    private void handleTap(float x, float y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (!hit.rect.contains(x, y)) {
                continue;
            }
            switch (hit.kind) {
                case HIT_TOOL:
                    listener.onTool(hit.style, screenCenterX(hit.view));
                    break;
                case HIT_SLOT:
                    if (hit.index == Types.WHEEL_SLOT) {
                        listener.onCustomColor(screenCenterX(hit.view));
                    } else {
                        SwatchView slot = swatches.get(hit.index);
                        // Official I1Iili: a checked slot opens the editor, an
                        // unchecked one applies its colour.
                        if (slot.checked) {
                            listener.onCustomColor(screenCenterX(slot));
                        } else {
                            listener.onColor(slot.color);
                        }
                    }
                    break;
                case HIT_UNDO:
                    listener.onUndo();
                    break;
                case HIT_REDO:
                    listener.onRedo();
                    break;
                case HIT_MORE:
                    listener.onMore(screenCenterX(hit.view));
                    break;
                default:
                    break;
            }
            return;
        }
    }

    private int screenCenterX(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[0] + view.getWidth() / 2;
    }

    private static boolean sameRgb(int a, int b) {
        return ((a ^ b) & 0xFFFFFF) == 0;
    }

    private static final class Hit {
        final RectF rect;
        final View view;
        final int kind;
        final int index;
        final Types.PenStyle style;

        Hit(RectF rect, View view, int kind, int index, Types.PenStyle style) {
            this.rect = rect;
            this.view = view;
            this.kind = kind;
            this.index = index;
            this.style = style;
        }
    }

    // -------------------- content views --------------------

    /** One tool: brush artwork, with the seat orientation handled locally. */
    private final class ToolView extends View {
        final Types.PenStyle style;
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Drawable art;
        private int color = Color.BLACK;
        private boolean ballArt;
        private boolean checked;
        private float rotationDeg;

        ToolView(Context context, Types.PenStyle style) {
            super(context);
            this.style = style;
            rebuild(false);
        }

        void setColor(int value) {
            if (color == value) {
                return;
            }
            color = value;
            rebuild(ballArt);
            invalidate();
        }

        void setBallArt(boolean value) {
            if (ballArt == value) {
                return;
            }
            rebuild(value);
            invalidate();
        }

        void setChecked(boolean value) {
            if (checked == value) {
                return;
            }
            checked = value;
            invalidate();
        }

        void setRotationDeg(float value) {
            if (rotationDeg == value) {
                return;
            }
            rotationDeg = value;
            invalidate();
        }

        private void rebuild(boolean ball) {
            ballArt = ball;
            art = new BrushDrawable(getContext(), style, color, ball);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (checked && !ballArt && collapse <= 0.001f) {
                // Hidden once the capsule starts collapsing: at p == 1 the
                // selected tool is the brush inside the ball, not a pill.
                pill.setColor(SELECTED_BG);
                float pad = dp(2);
                canvas.drawRoundRect(new RectF(pad, pad, w - pad, h - pad),
                        dp(16), dp(16), pill);
            }
            if (art == null) {
                return;
            }
            if (rotationDeg != 0f && !ballArt) {
                // The view box is already rotated (124 x short). Rotate only the
                // artwork inside it, exactly like the old glyph's rotationDeg.
                canvas.save();
                canvas.rotate(rotationDeg, w / 2f, h / 2f);
                float artW = h;
                float artH = w;
                art.setBounds((int) (w / 2f - artW / 2f), (int) (h / 2f - artH / 2f),
                        (int) (w / 2f + artW / 2f), (int) (h / 2f + artH / 2f));
                art.draw(canvas);
                canvas.restore();
            } else {
                art.setBounds(0, 0, w, h);
                art.draw(canvas);
            }
        }
    }

    /**
     * Colour slot: a 44dp box holding a 32dp dot. The checked state is the
     * official one: full dot, background-coloured disc at 0.8125r punching a
     * gap, then the colour again at 0.65r.
     */
    private final class SwatchView extends View {
        private final int index;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        private boolean checked;
        private boolean wheel;
        private Shader wheelShader;
        private float shaderCx;
        private float shaderCy;
        private float shaderR;

        SwatchView(Context context, int index, int color) {
            super(context);
            this.index = index;
            this.color = color;
        }

        void setChecked(boolean value) {
            if (checked == value) {
                return;
            }
            checked = value;
            invalidate();
        }

        void setLiveColor(int value) {
            if (color == value) {
                return;
            }
            color = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(dp(DOT_DP) / 2f, Math.min(w, h) / 2f);
            if (r <= 0f) {
                return;
            }

            if (wheel) {
                // Live colour as the dot, with a thin sweep-gradient rim as the
                // colour-wheel hint.
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
                paint.setColor(color);
                canvas.drawCircle(cx, cy, r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setShader(wheelShader(cx, cy, r));
                canvas.drawCircle(cx, cy, r - dp(0.75f), paint);
                paint.setShader(null);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
                paint.setColor(color);
                canvas.drawCircle(cx, cy, r, paint);
            }

            boolean nearWhite = Types.isNearBackground(color);
            if (nearWhite) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1f));
                paint.setColor(NEAR_WHITE_RING);
                canvas.drawCircle(cx, cy, r, paint);
            }

            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(nearWhite ? NEAR_WHITE_RING : CAPSULE_BG);
                canvas.drawCircle(cx, cy, r * 0.8125f, paint);
                paint.setColor(color);
                canvas.drawCircle(cx, cy, r * 0.65f, paint);
            }
        }

        private Shader wheelShader(float cx, float cy, float r) {
            if (wheelShader == null || cx != shaderCx || cy != shaderCy || r != shaderR) {
                shaderCx = cx;
                shaderCy = cy;
                shaderR = r;
                wheelShader = new SweepGradient(cx, cy, WHEEL_COLORS, null);
            }
            return wheelShader;
        }
    }

    /** Undo / redo / more: a 40dp disc with a 20dp glyph. */
    private final class ButtonView extends View {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path glyph = new Path();
        private final Path tail = new Path();
        private boolean enabledVisual = true;

        ButtonView(Context context, int kind) {
            super(context);
            this.kind = kind;
        }

        void setEnabledVisual(boolean value) {
            if (enabledVisual == value) {
                return;
            }
            enabledVisual = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(dp(BUTTON_CIRCLE_DP) / 2f, Math.min(w, h) / 2f);

            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BUTTON_BG);
            canvas.drawCircle(cx, cy, radius, paint);

            paint.setColor(enabledVisual ? ICON_COLOR : ICON_DISABLED);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            if (kind == BTN_MORE) {
                paint.setStyle(Paint.Style.FILL);
                float dotRadius = dp(1.7f);
                float gap = dp(5.5f);
                canvas.drawCircle(cx - gap, cy, dotRadius, paint);
                canvas.drawCircle(cx, cy, dotRadius, paint);
                canvas.drawCircle(cx + gap, cy, dotRadius, paint);
                return;
            }

            float unit = dp(BUTTON_GLYPH_DP) / 20f;
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(kind == BTN_REDO ? -unit : unit, unit);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);

            glyph.reset();
            glyph.moveTo(-1.6f, -8.4f);
            glyph.lineTo(-5.8f, -4.2f);
            glyph.lineTo(-1.6f, 0f);
            canvas.drawPath(glyph, paint);

            tail.reset();
            tail.moveTo(-4.8f, -4.2f);
            tail.lineTo(2.2f, -4.2f);
            tail.cubicTo(6.6f, -4.2f, 7.8f, 1.2f, 4.8f, 5.4f);
            canvas.drawPath(tail, paint);
            canvas.restore();
        }
    }

    /** Mi Canvas' topBar: a 32 x 4dp (or 4 x 32dp) rounded pill. */
    private final class HandleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        HandleView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            paint.setColor(HANDLE_COLOR);
            float r = Math.min(w, h) / 2f;
            canvas.drawRoundRect(new RectF(0f, 0f, w, h), r, r, paint);
        }
    }
}
