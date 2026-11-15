package com.globalannotate.official;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import com.globalannotate.official.R;

/**
 * Direct Java port of Mi Canvas' minimized brush ball
 * ({@code p452lilii.II11I}, decompiled as {@code p452lilii.II11I}).
 *
 * <p>The resting ball is 108dp: Mi Canvas' {@code MinimizedBallView} field
 * defaults to 96dp, but the MAXIMIZE picker collapses to its capsule
 * cross-axis, which is 108dp ({@code II11lli.m3506llIllil()} returns 108dp
 * except for the MIDSIZE ui state). This app has no MIDSIZE mode, so 108dp is
 * the default. The brush box is still scaled against the official 96dp
 * reference: {@code cp_brush_width_normal} (eraser:
 * {@code cp_brush_width_large}) x {@code cp_brush_height} at an 8dp top
 * margin, all times {@code ballSize / 96dp}. At 108dp that is 54dp (eraser
 * 76.5dp) x 139.5dp at a 9dp margin, {@code TOP | CENTER_HORIZONTAL},
 * {@code FIT_CENTER}.
 *
 * <p>The disc is painted with {@code penpicker_background_color}, clipped by
 * a circular outline, with elevation 0. A GestureDetector turns a single tap
 * into {@link #performClick()}, which runs the runnable installed through
 * {@link #setOnBallClickListener(Runnable)}.
 */
public final class MinimizedBallView extends FrameLayout {

    /** Resting ball diameter: 108dp in MAXIMIZE (96dp is MIDSIZE only). */
    public static final float BALL_SIZE_DP = 108f;
    /** Official MinimizedBallView diameter: the brush box scale reference. */
    private static final float BRUSH_REFERENCE_DP = 96f;
    /** ImageView top margin used by the MAXIMIZE layout, before scaling. */
    private static final float GLYPH_TOP_MARGIN_DP = 8f;
    /** Fallback copies of {@code cp_brush_width_normal/large} and {@code cp_brush_height}. */
    private static final float BRUSH_WIDTH_NORMAL_DP = 48f;
    private static final float BRUSH_WIDTH_LARGE_DP = 68f;
    private static final float BRUSH_HEIGHT_DP = 124f;

    /** Mi Canvas package that owns the official brush dimensions and strings. */
    private static final String MC_PACKAGE = "com.miui.creation";
    /** Mi Canvas' zh-rCN {@code pk_minimized_ball_talkback}, last-resort label. */
    private static final String FALLBACK_TALKBACK = "\u6700\u5c0f\u5316\u7b14\u888b";

    private static final ViewOutlineProvider OVAL_OUTLINE = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int width = view.getWidth();
            if (width > 0) {
                outline.setOval(0, 0, width, width);
            }
        }
    };

    private static Resources sHostResources;
    private static boolean sHostResolved;

    private final ImageView glyphView;
    private final GestureDetector gestureDetector;

    private int ballSizePx;
    private int glyphTopMarginPx;
    private int boxWidthPx;
    private int boxTopMarginPx;
    private Types.PenStyle style = Types.PenStyle.INK_PEN;
    private float rotationDeg;
    private Runnable ballClickListener;

    public MinimizedBallView(Context context) {
        super(context);
        final float density = context.getResources().getDisplayMetrics().density;
        ballSizePx = Math.max(1, Math.round(BALL_SIZE_DP * density));
        glyphTopMarginPx = Math.max(0, Math.round(GLYPH_TOP_MARGIN_DP * density));

        setBackgroundColor(pickerBackgroundColor(context));
        setOutlineProvider(OVAL_OUTLINE);
        setClipToOutline(true);
        setElevation(0f);
        setClickable(true);
        applyTalkbackDescription(context);

        glyphView = new ImageView(context);
        glyphView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(glyphView);
        updateGlyphLayout();

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                performClick();
                return true;
            }
        });
    }

    /**
     * Resizes the ball and re-derives the glyph box. Mirrors the official
     * {@code setBallSizePx}: non-positive sizes are ignored, and an unchanged
     * size whose layout params already match is a no-op.
     */
    public void setBallSizePx(int size) {
        if (size <= 0) {
            return;
        }
        ViewGroup.LayoutParams params = getLayoutParams();
        if (ballSizePx == size && params != null
                && params.width == size && params.height == size) {
            return;
        }
        ballSizePx = size;
        params = getLayoutParams();
        if (params != null && (params.width != size || params.height != size)) {
            params.width = size;
            params.height = size;
            setLayoutParams(params);
        }
        updateGlyphLayout();
    }

    public int getBallSizePx() {
        return ballSizePx;
    }

    /**
     * Installs the brush drawable, exactly like the official
     * {@code setBrush(Drawable, PenStyle)}. A null {@code style} keeps the
     * current one; the style only selects the eraser's wider box.
     */
    public void setBrush(Drawable drawable, Types.PenStyle style) {
        if (style != null) {
            this.style = style;
        }
        glyphView.setImageDrawable(drawable);
        updateGlyphLayout();
    }

    /** Convenience that composes the official brush artwork for a colour. */
    public void setBrush(Types.PenStyle style, int color) {
        if (style == null) {
            return;
        }
        setBrush(new BrushDrawable(getContext(), style, color, true), style);
    }

    public Drawable getBrushDrawable() {
        return glyphView.getDrawable();
    }

    /**
     * Overrides {@link View#setRotation(float)} so rotation turns the brush
     * content only, never the disc. The ImageView is pivoted on the ball
     * centre (the box is centred horizontally, so the pivot is the box centre
     * in x and {@code ballSize/2 - topMargin} in y).
     */
    @Override
    public void setRotation(float degrees) {
        rotationDeg = degrees;
        applyRotationPivot();
    }

    @Override
    public float getRotation() {
        return rotationDeg;
    }

    /** Replacement for the official {@code setOnClickListener(Runnable)}. */
    public void setOnBallClickListener(Runnable listener) {
        ballClickListener = listener;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        Runnable listener = ballClickListener;
        if (listener != null) {
            listener.run();
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Official {@code m35864IlIlIii11li}: {@code scale = ballSize / dp(96)},
     * then the ImageView gets the brush box times that scale, at the scaled
     * 8dp top margin and {@code TOP | CENTER_HORIZONTAL} (49).
     */
    private void updateGlyphLayout() {
        final float density = getResources().getDisplayMetrics().density;
        final float referenceDpPx = BRUSH_REFERENCE_DP * density;
        final float scale = referenceDpPx > 0f ? ballSizePx / referenceDpPx : 1f;

        final int normalWidth = resolveBrushDimen("cp_brush_width_normal", BRUSH_WIDTH_NORMAL_DP);
        final int largeWidth = resolveBrushDimen("cp_brush_width_large", BRUSH_WIDTH_LARGE_DP);
        final int height = resolveBrushDimen("cp_brush_height", BRUSH_HEIGHT_DP);

        final boolean eraser = style == Types.PenStyle.ERASER;
        boxWidthPx = Math.max(1, (int) ((eraser ? largeWidth : normalWidth) * scale));
        final int boxHeight = Math.max(1, (int) (height * scale));
        boxTopMarginPx = Math.max(0, (int) (glyphTopMarginPx * scale));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(boxWidthPx, boxHeight);
        params.topMargin = boxTopMarginPx;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        glyphView.setLayoutParams(params);
        applyRotationPivot();
    }

    private void applyRotationPivot() {
        glyphView.setPivotX(boxWidthPx / 2f);
        glyphView.setPivotY(ballSizePx / 2f - boxTopMarginPx);
        glyphView.setRotation(rotationDeg);
    }

    /**
     * Official brush-box dimensions. Mi Canvas is asked first because that is
     * the composition being ported; the host app's copied tokens are the next
     * fallback, then the official dp numbers.
     */
    private int resolveBrushDimen(String name, float fallbackDp) {
        Resources host = hostResources(getContext());
        if (host != null) {
            int id = host.getIdentifier(name, "dimen", MC_PACKAGE);
            if (id != 0) {
                try {
                    return host.getDimensionPixelSize(id);
                } catch (Resources.NotFoundException ignored) {
                    // Fall through to the app's own copy.
                }
            }
        }
        Resources own = getResources();
        int ownId = own.getIdentifier(name, "dimen", getContext().getPackageName());
        if (ownId != 0) {
            try {
                return own.getDimensionPixelSize(ownId);
            } catch (Resources.NotFoundException ignored) {
                // Fall through to the documented official dp value.
            }
        }
        return Math.round(fallbackDp * own.getDisplayMetrics().density);
    }

    private static int pickerBackgroundColor(Context context) {
        try {
            return ContextCompat.getColor(context, R.color.penpicker_background_color);
        } catch (Resources.NotFoundException missing) {
            return Color.WHITE;
        }
    }

    private void applyTalkbackDescription(Context context) {
        int id = getResources().getIdentifier(
                "pk_minimized_ball_talkback", "string", context.getPackageName());
        if (id != 0) {
            setContentDescription(getResources().getString(id));
            return;
        }
        // The penpicker string lives in Mi Canvas when the host app has not
        // copied it; prefer the host's locale-resolved copy over no label.
        Resources host = hostResources(context);
        if (host != null) {
            int hostId = host.getIdentifier("pk_minimized_ball_talkback", "string", MC_PACKAGE);
            if (hostId != 0) {
                setContentDescription(host.getString(hostId));
                return;
            }
        }
        setContentDescription(FALLBACK_TALKBACK);
    }

    private static Resources hostResources(Context context) {
        if (!sHostResolved) {
            sHostResolved = true;
            try {
                Context app = context.getApplicationContext();
                if (app == null) {
                    app = context;
                }
                sHostResources = app.getPackageManager()
                        .getResourcesForApplication(MC_PACKAGE);
            } catch (Exception unavailable) {
                // Mi Canvas is not installed: the app's own tokens still work.
                sHostResources = null;
            }
        }
        return sHostResources;
    }
}
