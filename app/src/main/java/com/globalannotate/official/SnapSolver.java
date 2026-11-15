package com.globalannotate.official;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.EnumMap;
import java.util.Map;

/**
 * Direct port of Mi Canvas' eight-anchor picker placement solver
 * ({@code p167IlI1iIi.IiII1IlI.Iiliill}).
 *
 * <p>The solver answers one question: for a capsule of size {@code w x h} that
 * wants to sit on a given side (or corner) of {@code bounds}, where should its
 * top-left corner go? A bias may pull the free axis towards one of the two
 * corners, which is how the official picker keeps the released tool near the
 * edge the user was dragging towards.</p>
 *
 * <p>All values are screen pixels. {@code bounds} is normally the screen minus
 * the window insets; {@code inset} is the picker's own margin and
 * {@code insets} carries per-side extra room (status bar, cutout, gesture
 * area).</p>
 */
public final class SnapSolver {

    private SnapSolver() {
    }

    /** Per-side extra margins, applied in addition to the uniform inset. */
    public static final class Insets {

        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        public Insets(float l, float t, float r, float b) {
            this.left = l;
            this.top = t;
            this.right = r;
            this.bottom = b;
        }

        public static final Insets ZERO = new Insets(0f, 0f, 0f, 0f);
    }

    /**
     * Solves one anchor.
     *
     * @param bounds   usable rectangle in screen pixels
     * @param w        capsule width
     * @param h        capsule height
     * @param position requested anchor
     * @param inset    uniform margin inside {@code bounds}
     * @param insets   additional per-side margins (may be {@code null})
     * @param bias     optional corner that pulls the free axis (may be {@code null})
     * @return the capsule's top-left position
     */
    public static PointF solve(RectF bounds, float w, float h, Types.Dock position,
                               float inset, Insets insets, Types.Dock bias) {
        if (insets == null) {
            insets = Insets.ZERO;
        }
        if (position == null) {
            position = Types.Dock.LEFT_TOP;
        }

        final float l = bounds.left + inset + insets.left;
        final float t = bounds.top + inset + insets.top;
        final float r = bounds.right - inset - insets.right;
        final float b = bounds.bottom - inset - insets.bottom;

        final float maxX = r - w;
        float cx = (r + l) / 2f - w / 2f;
        final float maxY = b - h;
        float cy = (b + t) / 2f - h / 2f;

        if (bias == Types.Dock.LEFT_TOP || bias == Types.Dock.LEFT_BOTTOM) {
            cx = Math.min(l, cx);
        } else if (bias == Types.Dock.RIGHT_TOP || bias == Types.Dock.RIGHT_BOTTOM) {
            cx = Math.max(maxX, cx);
        }
        if (bias == Types.Dock.LEFT_TOP || bias == Types.Dock.RIGHT_TOP) {
            cy = Math.min(t, cy);
        } else if (bias == Types.Dock.LEFT_BOTTOM || bias == Types.Dock.RIGHT_BOTTOM) {
            cy = Math.max(maxY, cy);
        }

        float x;
        float y;
        switch (position) {
            case LEFT:
                x = l;
                y = cy;
                break;
            case TOP:
                x = cx;
                y = t;
                break;
            case RIGHT:
                x = maxX;
                y = cy;
                break;
            case BOTTOM:
                x = cx;
                y = maxY;
                break;
            case LEFT_BOTTOM:
                x = l;
                y = maxY;
                break;
            case RIGHT_TOP:
                x = maxX;
                y = t;
                break;
            case RIGHT_BOTTOM:
                x = maxX;
                y = maxY;
                break;
            case LEFT_TOP:
            default:
                x = l;
                y = t;
                break;
        }

        // Final guard: never let the capsule leave the raw bounds. Skipped only
        // for an inverted rect, which would make the interval meaningless.
        if (bounds.right >= bounds.left) {
            x = Math.max(bounds.left, Math.min(x, bounds.right - w));
        }
        if (bounds.bottom >= bounds.top) {
            y = Math.max(bounds.top, Math.min(y, bounds.bottom - h));
        }

        return new PointF(x, y);
    }

    /**
     * Solves every anchor with no bias, exactly as the official picker does when
     * it prepares the eight collapse targets.
     */
    public static Map<Types.Dock, PointF> solveAll(RectF bounds, float w, float h,
                                                   float inset, Insets insets) {
        Map<Types.Dock, PointF> all = new EnumMap<>(Types.Dock.class);
        for (Types.Dock dock : Types.Dock.values()) {
            all.put(dock, solve(bounds, w, h, dock, inset, insets, null));
        }
        return all;
    }
}
