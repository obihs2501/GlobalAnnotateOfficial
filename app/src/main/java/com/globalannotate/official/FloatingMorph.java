package com.globalannotate.official;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.animation.LinearInterpolator;

/**
 * Port of Mi Canvas' floating-picker morph motion
 * ({@code p067IIiiIiiI.II11lli.m3494lIlilI1iii},
 * {@code m3468Ii1IiiIiiIi}, {@code m3489Il1IIl}, {@code m3493lIIIl1l}).
 *
 * <p>The official picker keeps one invariant while it animates:
 * {@code wrapperTranslation + toolSlot == ballCentre}. The ball is the visible
 * object while collapsed; during expansion the same centre is handed over to
 * the capsule's tool slot, so the ball appears to grow into the capsule instead
 * of jumping. Collapsing is the same motion reversed, but the whole capsule
 * (not only its content) flies to the released corner before the ball is shown
 * again.</p>
 *
 * <p>All coordinates are screen pixels. This class never touches a View
 * directly; everything goes through {@link FloatingHost} so the host stays the
 * single owner of layout and visibility.</p>
 */
public final class FloatingMorph {

    /** Notified with the host's visible region, for whatever places the overlay. */
    public interface Placement {
        void onVisibleRect(Rect r, boolean smooth);
    }

    /** One animation frame, already spring-interpolated into {@code 0..1}. */
    private interface Frame {
        void onFrame(float q);
    }

    private static final float EXPAND_DAMPING = 0.8f;
    private static final float EXPAND_RESPONSE = 0.4f;
    private static final float SNAP_DAMPING = 0.78f;
    private static final float SNAP_RESPONSE = 0.36f;

    /** Expansion hand-over point: ball is swapped for the capsule here. */
    private static final float EXPAND_SWAP = 0.35f;

    /**
     * Collapse hand-over point: from here on the ball is drawn over the
     * shrinking capsule and finishes the turn to upright inside the morph.
     * The official picker only ever shows one of the two objects, but the ball
     * is added after the wrapper, so painting it on top of the last part of the
     * collapse reads as the capsule turning into the ball and removes the
     * separate rotation that used to run after the morph had landed.
     */
    private static final float COLLAPSE_BALL_SHOW = 0.65f;

    private final FloatingHost host;
    private final Placement placement;

    /** The single running animator, or null. Identity-checked by its listeners. */
    private ValueAnimator animator;

    // Current ball centre, in screen pixels.
    private float cx;
    private float cy;
    private int sizePx;

    // Current wrapper (capsule root) top-left, in screen pixels.
    private float wrapperLeft;
    private float wrapperTop;

    // Screen size in pixels, provided by the service through setScreenSize().
    private int screenW;
    private int screenH;

    public FloatingMorph(FloatingHost host, Placement placement) {
        this.host = host;
        this.placement = placement;
    }

    // ------------------------------------------------------------------
    // Direct placement (no animation)
    // ------------------------------------------------------------------

    /** First appearance of the minimised ball. */
    public void showBallAt(float cx, float cy, int sizePx) {
        stopAnimation();
        this.cx = cx;
        this.cy = cy;
        this.sizePx = sizePx;
        host.showBallAt(cx, cy, sizePx);
        placement.onVisibleRect(host.visibleRect(), false);
    }

    /** Drag of the minimised ball; the centre is clamped to the screen. */
    public void moveBallTo(float cx, float cy) {
        stopAnimation();
        final float radius = ballRadius();
        float x = cx;
        float y = cy;
        if (screenW > 0) {
            x = clamp(x, radius, screenW - radius);
        }
        if (screenH > 0) {
            y = clamp(y, radius, screenH - radius);
        }
        this.cx = x;
        this.cy = y;
        host.setBallCenter(x, y);
        placement.onVisibleRect(host.visibleRect(), false);
    }

    /** Drag of the expanded capsule; the ball stays hidden underneath. */
    public void moveCapsuleTo(float wrapperX, float wrapperY) {
        stopAnimation();
        wrapperLeft = wrapperX;
        wrapperTop = wrapperY;
        host.setWrapperTopLeft(wrapperX, wrapperY);
        host.setBallVisible(false);
        // Keep the official invariant even while dragging.
        cx = wrapperX + host.toolSlotX();
        cy = wrapperY + host.toolSlotY();
        placement.onVisibleRect(host.visibleRect(), false);
    }

    /**
     * Instant capsule-drag hand-over: the capsule disappears and the ball is
     * under the finger from this frame on, with no collapse animation and no
     * anchor decision (official {@code m3492l1i1ili}). The caller starts the
     * brush turn to upright; the gesture continues 1:1 from here.
     */
    public void handOverToBall(float ballCx, float ballCy) {
        stopAnimation();
        final float radius = ballRadius();
        float x = ballCx;
        float y = ballCy;
        if (screenW > 0) {
            x = clamp(x, radius, screenW - radius);
        }
        if (screenH > 0) {
            y = clamp(y, radius, screenH - radius);
        }
        this.cx = x;
        this.cy = y;
        host.setCapsuleVisible(false);
        host.setBallTopLeft(x - radius, y - radius);
        host.setBallVisible(true);
        host.setCollapseProgress(1f);
        placement.onVisibleRect(host.visibleRect(), false);
    }

    // ------------------------------------------------------------------
    // Morphs
    // ------------------------------------------------------------------

    /**
     * Ball -> capsule. The caller has already laid out the capsule for
     * {@code dock}, so {@link FloatingHost#capsuleWidth()} and
     * {@link FloatingHost#capsuleHeight()} are valid. The wrapper starts under
     * the ball and travels to the prepared capsule position while the capsule
     * unfolds from the tool slot.
     */
    public void beginExpand(Types.Dock dock, float capsuleX, float capsuleY, Runnable after) {
        stopAnimation();
        host.setCapsuleSize(host.capsuleWidth(), host.capsuleHeight());

        final float slotX = host.toolSlotX();
        final float slotY = host.toolSlotY();
        final float fromCx = cx;
        final float fromCy = cy;
        final float toCx = capsuleX + slotX;
        final float toCy = capsuleY + slotY;

        wrapperLeft = fromCx - slotX;
        wrapperTop = fromCy - slotY;
        host.setWrapperTopLeft(wrapperLeft, wrapperTop);
        host.setCapsuleVisible(false);
        host.setBallVisible(true);
        host.setCollapseProgress(1f);

        final boolean[] switched = new boolean[1];
        final Spring spring = new Spring(EXPAND_DAMPING, EXPAND_RESPONSE);

        // The capsule's tools are drawn at the dock angle. Turn the ball's
        // brush to that angle while the ball is still the visible object, so
        // the hand-over frame matches and no rotation is left for after the
        // morph (official does the same inside the expand transition).
        final float rotationFrom = host.ball().getRotation();
        final float rotationSweep = shortestDelta(rotationFrom,
                host.capsule().toolRotation());

        startAnimation(spring, new Frame() {
            @Override
            public void onFrame(float q) {
                final float frameCx = fromCx + (toCx - fromCx) * q;
                final float frameCy = fromCy + (toCy - fromCy) * q;
                cx = frameCx;
                cy = frameCy;
                wrapperLeft = frameCx - slotX;
                wrapperTop = frameCy - slotY;

                host.setBallCenter(frameCx, frameCy);
                host.setWrapperTopLeft(wrapperLeft, wrapperTop);

                if (q < EXPAND_SWAP) {
                    host.ball().setRotation(
                            rotationFrom + rotationSweep * (q / EXPAND_SWAP));
                }

                // The hand-over frame must be the ball exactly: the first
                // visible capsule frame is the p == 1 circle at the ball
                // centre. Easing p from the raw q at the swap made the capsule
                // appear already half-open (a 100px pop, official hands over at
                // p == 1 and unfolds from the next frame on).
                final boolean justSwitched = !switched[0] && q >= EXPAND_SWAP;
                final float p = justSwitched
                        ? 1f
                        : 1f - clamp((q - EXPAND_SWAP) / (1f - EXPAND_SWAP), 0f, 1f);
                host.setCollapseProgress(p);

                if (q >= EXPAND_SWAP) {
                    if (justSwitched) {
                        switched[0] = true;
                        host.setBallVisible(false);
                        host.setCapsuleVisible(true);
                    }
                } else {
                    host.setBallVisible(true);
                    host.setCapsuleVisible(false);
                }
                placement.onVisibleRect(host.visibleRect(), true);
            }
        }, new Runnable() {
            @Override
            public void run() {
                cx = toCx;
                cy = toCy;
                wrapperLeft = capsuleX;
                wrapperTop = capsuleY;
                host.setWrapperTopLeft(capsuleX, capsuleY);
                host.setCollapseProgress(0f);
                host.setCapsuleVisible(true);
                host.setBallVisible(false);
                placement.onVisibleRect(host.visibleRect(), true);
                if (after != null) {
                    after.run();
                }
            }
        });
    }

    /**
     * Capsule -> ball. The wrapper (and with it the collapsing capsule) flies
     * from the current slot to the requested ball centre. From
     * {@link #COLLAPSE_BALL_SHOW} on the ball is painted on top of the shrinking
     * capsule and turns upright inside the same morph, so the hand-over is the
     * last frame of the animation instead of a separate rotation afterwards.
     */
    public void beginCollapse(float targetBallCx, float targetBallCy, Runnable after) {
        stopAnimation();

        final float slotX = host.toolSlotX();
        final float slotY = host.toolSlotY();
        final float fromCx = wrapperLeft + slotX;
        final float fromCy = wrapperTop + slotY;
        final float targetCx = targetBallCx;
        final float targetCy = targetBallCy;
        final float radius = ballRadius();

        host.setBallVisible(false);
        host.setCapsuleVisible(true);
        host.setCollapseProgress(0f);

        // The ball enters at the capsule's current tool angle and turns upright
        // within the morph, so there is no extra spin after it has landed.
        final float rotationFrom = host.ball().getRotation();
        final float rotationSweep = shortestDelta(rotationFrom, 0f);
        final Spring spring = new Spring(EXPAND_DAMPING, EXPAND_RESPONSE);

        startAnimation(spring, new Frame() {
            @Override
            public void onFrame(float q) {
                final float frameCx = fromCx + (targetCx - fromCx) * q;
                final float frameCy = fromCy + (targetCy - fromCy) * q;
                cx = frameCx;
                cy = frameCy;
                wrapperLeft = frameCx - slotX;
                wrapperTop = frameCy - slotY;

                host.setWrapperTopLeft(wrapperLeft, wrapperTop);
                host.setCollapseProgress(q);

                if (q >= COLLAPSE_BALL_SHOW) {
                    final float t = clamp(
                            (q - COLLAPSE_BALL_SHOW) / (1f - COLLAPSE_BALL_SHOW),
                            0f, 1f);
                    host.setBallCenter(frameCx, frameCy);
                    host.ball().setRotation(rotationFrom + rotationSweep * t);
                    host.setBallVisible(true);
                }
                placement.onVisibleRect(host.visibleRect(), true);
            }
        }, new Runnable() {
            @Override
            public void run() {
                host.setCapsuleVisible(false);
                host.setBallTopLeft(targetCx - radius, targetCy - radius);
                host.ball().setRotation(0f);
                host.setBallVisible(true);
                host.setCollapseProgress(1f);

                cx = targetCx;
                cy = targetCy;
                wrapperLeft = targetCx - slotX;
                wrapperTop = targetCy - slotY;
                placement.onVisibleRect(host.visibleRect(), true);
                if (after != null) {
                    after.run();
                }
            }
        });
    }

    /** Ball flight to a screen-edge anchor after the drag is released. */
    public void beginSnap(float targetBallCx, float targetBallCy, Runnable after) {
        stopAnimation();

        final float radius = ballRadius();
        final float fromLeft = cx - radius;
        final float fromTop = cy - radius;
        final float toLeft = targetBallCx - radius;
        final float toTop = targetBallCy - radius;

        final Spring spring = new Spring(SNAP_DAMPING, SNAP_RESPONSE);

        startAnimation(spring, new Frame() {
            @Override
            public void onFrame(float q) {
                final float left = fromLeft + (toLeft - fromLeft) * q;
                final float top = fromTop + (toTop - fromTop) * q;
                cx = left + radius;
                cy = top + radius;
                host.setBallTopLeft(left, top);
                placement.onVisibleRect(host.visibleRect(), true);
            }
        }, new Runnable() {
            @Override
            public void run() {
                host.setBallTopLeft(toLeft, toTop);
                cx = targetBallCx;
                cy = targetBallCy;
                placement.onVisibleRect(host.visibleRect(), true);
                if (after != null) {
                    after.run();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Animation control
    // ------------------------------------------------------------------

    /** Stops the running morph where it is; the views keep their last frame. */
    public void cancel() {
        stopAnimation();
    }

    public boolean isAnimating() {
        return animator != null;
    }

    /**
     * ValueAnimator plus spring, shared by all three morphs. The animator runs
     * linearly and the spring does the shaping, so the official overshoot and
     * settle timing are preserved.
     */
    private void startAnimation(final Spring spring, final Frame frame, final Runnable end) {
        stopAnimation();

        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(spring.durationMs());
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                // A cancelled animator can still deliver callbacks; only the
                // current one is allowed to move the views.
                if (animator != animation) {
                    return;
                }
                frame.onFrame(spring.getInterpolation(animation.getAnimatedFraction()));
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator != animation) {
                    return;
                }
                animator = null;
                end.run();
            }
        });

        animator = anim;
        anim.start();
    }

    private void stopAnimation() {
        final ValueAnimator running = animator;
        if (running != null) {
            // Clear the field first: cancel() dispatches onAnimationEnd, and
            // the identity check above must reject that callback.
            animator = null;
            running.cancel();
        }
    }

    // ------------------------------------------------------------------
    // Geometry helpers / current state
    // ------------------------------------------------------------------

    /** Screen size in pixels, used to clamp ball drags. */
    public void setScreenSize(int w, int h) {
        screenW = Math.max(0, w);
        screenH = Math.max(0, h);
    }

    public float ballCx() {
        return cx;
    }

    public float ballCy() {
        return cy;
    }

    public int ballSizePx() {
        final int hostSize = host.ballSizePx();
        if (hostSize > 0) {
            sizePx = hostSize;
        }
        return sizePx;
    }

    public float wrapperX() {
        return wrapperLeft;
    }

    public float wrapperY() {
        return wrapperTop;
    }

    public Rect visibleRect() {
        return host.visibleRect();
    }

    private float ballRadius() {
        final int hostSize = host.ballSizePx();
        if (hostSize > 0) {
            sizePx = hostSize;
        }
        return sizePx / 2f;
    }

    private static float clamp(float value, float min, float max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }

    /** Shortest signed sweep from one glyph angle to another. */
    private static float shortestDelta(float from, float to) {
        float delta = to - from;
        while (delta > 180f) {
            delta -= 360f;
        }
        while (delta < -180f) {
            delta += 360f;
        }
        return delta;
    }
}
