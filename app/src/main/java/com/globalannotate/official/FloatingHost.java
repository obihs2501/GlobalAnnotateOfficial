package com.globalannotate.official;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The floating picker host: a full-screen {@link FrameLayout} that holds the
 * capsule wrapper and the minimised ball, in that order, with the ball on top.
 *
 * <p>The service owns the overlay window; this class never creates, moves or
 * resizes a window. It is a plain view hierarchy inside the full-screen visual
 * overlay. Every public position is expressed in <b>screen pixels</b> and is
 * converted to a local translation by subtracting the host's own
 * {@link #getLocationOnScreen(int[])} origin.</p>
 *
 * <p>Layout is absolute and explicit, like the capsule content itself: the
 * wrapper is laid out at {@code (0, 0, capsuleWidth, capsuleHeight)} and the
 * ball at {@code (0, 0, size, size)}. Translations move them, so neither the
 * window nor the measured layout changes while the morph runs.</p>
 *
 * <p>The one exception to the screen-pixel rule is {@link #toolSlotX()} /
 * {@link #toolSlotY()}: they are <b>wrapper-local</b>, because the morph
 * invariant is {@code wrapperTranslation + toolSlot == ballCentre}. The service
 * places the wrapper with {@code wrapperLeft = ballCx - host.toolSlotX()} and
 * the selected tool stays exactly under the ball for every animation frame.</p>
 */
public final class FloatingHost extends FrameLayout {

    /** Official minimised-ball diameter, used only if the ball has no size yet. */
    private static final float BALL_SIZE_DP = 96f;

    private final FrameLayout wrapper;
    private final CapsuleView capsule;
    private final MinimizedBallView ball;

    /** Host origin in screen pixels; translations are {@code screen - origin}. */
    private final int[] origin = new int[2];

    private final float density;

    private int capsuleWidth;
    private int capsuleHeight;
    /** Mirror of {@link MinimizedBallView#getBallSizePx()}. */
    private int ballSize;

    public FloatingHost(Context context, CapsuleView capsuleView) {
        super(context);
        if (capsuleView == null) {
            throw new IllegalArgumentException("capsuleView == null");
        }
        capsule = capsuleView;
        density = context.getResources().getDisplayMetrics().density;

        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(true);

        // The capsule's own dock is authoritative for the initial size; the
        // service pre-lays-out for its dock and confirms through setCapsuleSize.
        Point size = capsule.contentSize(capsule.dock());
        capsuleWidth = Math.max(0, size.x);
        capsuleHeight = Math.max(0, size.y);

        wrapper = new FrameLayout(context);
        wrapper.setClipChildren(true);
        addView(wrapper, new LayoutParams(capsuleWidth, capsuleHeight));
        wrapper.addView(capsule, new FrameLayout.LayoutParams(capsuleWidth, capsuleHeight));

        ball = new MinimizedBallView(context);
        ballSize = ball.getBallSizePx();
        if (ballSize <= 0) {
            ballSize = Math.max(1, Math.round(BALL_SIZE_DP * density));
            ball.setBallSizePx(ballSize);
        }
        addView(ball, new LayoutParams(ballSize, ballSize));
        ball.setVisibility(INVISIBLE);
        wrapper.setVisibility(VISIBLE);

        // The collapse outline must be exactly the ball circle at p == 1, so the
        // capsule needs the ball diameter even when the service only animates
        // FloatingMorph and never calls enterBallMode().
        capsule.setBallDiameterPx(ballSize);

        captureScreenOrigin();
        setCapsuleSize(capsuleWidth, capsuleHeight);
    }

    // -------------------- children --------------------

    public CapsuleView capsule() {
        return capsule;
    }

    public MinimizedBallView ball() {
        return ball;
    }

    public void setBallGlyph(Types.PenStyle style, int color) {
        if (style != null) {
            ball.setBrush(style, color);
        }
    }

    // -------------------- ball --------------------

    /** First appearance of the minimised ball: ball visible, capsule hidden. */
    public void showBallAt(float cx, float cy, int sizePx) {
        applyBallSize(sizePx);
        ball.setVisibility(VISIBLE);
        wrapper.setVisibility(INVISIBLE);
        setBallCenter(cx, cy);
    }

    /** Positions the ball by its centre, screen pixels. */
    public void setBallCenter(float cx, float cy) {
        float half = ballSizePx() / 2f;
        setBallTopLeft(cx - half, cy - half);
    }

    /** Positions the ball by its top-left corner, screen pixels. */
    public void setBallTopLeft(float left, float top) {
        ball.setTranslationX(left - origin[0]);
        ball.setTranslationY(top - origin[1]);
    }

    public void setBallVisible(boolean visible) {
        ball.setVisibility(visible ? VISIBLE : INVISIBLE);
    }

    public int ballSizePx() {
        int size = ball.getBallSizePx();
        if (size > 0) {
            ballSize = size;
        }
        return ballSize;
    }

    private void applyBallSize(int sizePx) {
        int size = sizePx;
        if (size <= 0) {
            size = Math.max(1, Math.round(BALL_SIZE_DP * density));
        }
        ball.setBallSizePx(size);
        ballSize = ball.getBallSizePx();

        LayoutParams params = (LayoutParams) ball.getLayoutParams();
        if (params == null) {
            params = new LayoutParams(ballSize, ballSize);
        } else {
            params.width = ballSize;
            params.height = ballSize;
        }
        ball.setLayoutParams(params);

        int spec = MeasureSpec.makeMeasureSpec(ballSize, MeasureSpec.EXACTLY);
        ball.measure(spec, spec);
        ball.layout(0, 0, ballSize, ballSize);

        capsule.setBallDiameterPx(ballSize);
        requestLayout();
    }

    // -------------------- capsule wrapper --------------------

    /** Positions the wrapper (the capsule root) by its top-left, screen px. */
    public void setWrapperTopLeft(float x, float y) {
        wrapper.setTranslationX(x - origin[0]);
        wrapper.setTranslationY(y - origin[1]);
    }

    /** Screen X of the wrapper's left edge. */
    public float wrapperX() {
        return origin[0] + wrapper.getTranslationX();
    }

    /** Screen Y of the wrapper's top edge. */
    public float wrapperY() {
        return origin[1] + wrapper.getTranslationY();
    }

    public void setCapsuleSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        capsuleWidth = w;
        capsuleHeight = h;

        LayoutParams wrapperParams = (LayoutParams) wrapper.getLayoutParams();
        if (wrapperParams == null) {
            wrapperParams = new LayoutParams(w, h);
        } else {
            wrapperParams.width = w;
            wrapperParams.height = h;
        }
        wrapper.setLayoutParams(wrapperParams);

        FrameLayout.LayoutParams capsuleParams =
                (FrameLayout.LayoutParams) capsule.getLayoutParams();
        if (capsuleParams == null) {
            capsuleParams = new FrameLayout.LayoutParams(w, h);
        } else {
            capsuleParams.width = w;
            capsuleParams.height = h;
        }
        capsule.setLayoutParams(capsuleParams);

        int widthSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int heightSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        wrapper.measure(widthSpec, heightSpec);
        capsule.measure(widthSpec, heightSpec);
        wrapper.layout(0, 0, w, h);
        capsule.layout(0, 0, w, h);
        requestLayout();
    }

    public int capsuleWidth() {
        return capsuleWidth;
    }

    public int capsuleHeight() {
        return capsuleHeight;
    }

    public void setCapsuleVisible(boolean visible) {
        wrapper.setVisibility(visible ? VISIBLE : INVISIBLE);
    }

    // -------------------- morph plumbing --------------------

    /** 1 = the capsule drawn as the ball circle, 0 = fully open. */
    public void setCollapseProgress(float p) {
        capsule.setCollapseProgress(p);
    }

    /**
     * Capsule-local X of the selected tool slot, measured from the wrapper's
     * top-left. {@code wrapperLeft + toolSlotX() == ballCentreX} during a morph.
     */
    public float toolSlotX() {
        return capsule.toolSlot().x;
    }

    /** Capsule-local Y of the selected tool slot, from the wrapper's top-left. */
    public float toolSlotY() {
        return capsule.toolSlot().y;
    }

    // -------------------- host geometry --------------------

    public void setScreenSizeDp(float widthDp, float heightDp) {
        capsule.setScreenSizeDp(widthDp, heightDp);
    }

    /** Caches the host's screen origin; call after every window move. */
    public void captureScreenOrigin() {
        getLocationOnScreen(origin);
    }

    public Point screenOrigin() {
        return new Point(origin[0], origin[1]);
    }

    /**
     * The visible part of the picker in screen pixels: the union of the ball
     * rect and the capsule (wrapper) rect for whichever of them is visible.
     * Returns an empty rect when both are hidden.
     */
    public Rect visibleRect() {
        boolean ballVisible = ball.getVisibility() == VISIBLE;
        boolean capsuleVisible = wrapper.getVisibility() == VISIBLE;
        Rect union = new Rect();
        boolean any = false;

        if (ballVisible) {
            int size = ballSizePx();
            int left = Math.round(origin[0] + ball.getTranslationX());
            int top = Math.round(origin[1] + ball.getTranslationY());
            union.set(left, top, left + size, top + size);
            any = true;
        }
        if (capsuleVisible) {
            int left = Math.round(wrapperX());
            int top = Math.round(wrapperY());
            Rect rect = new Rect(left, top, left + capsuleWidth, top + capsuleHeight);
            if (any) {
                union.union(rect);
            } else {
                union.set(rect);
                any = true;
            }
        }
        return any ? union : new Rect();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = capsuleWidth > 0 ? capsuleWidth : Math.max(0, right - left);
        int h = capsuleHeight > 0 ? capsuleHeight : Math.max(0, bottom - top);
        wrapper.layout(0, 0, w, h);

        int size = ballSizePx();
        ball.layout(0, 0, size, size);
    }
}
