package com.globalannotate.official;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.globalannotate.official.R;

/**
 * The brush artwork composed exactly like the ball branch of the old Kotlin
 * {@code BrushGlyph}: body untouched, head tinted with the ink colour through
 * {@code PorterDuff.SRC_IN}, and the highlight layer on top for pencil, marker
 * and watercolour only.
 *
 * <p>Artwork is loaded by name from the app's own drawables first
 * ({@code pk_<art>_body/head/highlight}, or {@code pk_eraser_tool}) and then
 * from Mi Canvas' resources. Only {@link BitmapDrawable}s are accepted, which
 * is what both packages ship for the brushes.
 *
 * <p>Official ball composition: the artwork box is 48dp (eraser 68dp) by
 * 124dp at a top margin of 8dp inside the 96dp reference ball, {@code
 * FIT_CENTER}, then cropped by the ball circle. That is the exact arithmetic
 * of the Kotlin {@code fit()} branch for {@code drawDisc || ballArt}; the box
 * scales with the actual ball, which is 96dp in this app. The official
 * {@code MinimizedBallView}, however, performs the box placement in its
 * ImageView layout; when this drawable receives exactly that brush-box bounds
 * (as {@code MinimizedBallView}'s ImageView does), the box is already applied,
 * so the artwork is only fitted into the bounds and the circular crop is left
 * to the parent's {@code clipToOutline}. Applying the ball box again there
 * would halve the glyph and the inscribed circle would cut the pen in two.
 */
public final class BrushDrawable extends Drawable {

    /** Mi Canvas package that owns the official brush art. */
    private static final String MC_PACKAGE = "com.miui.creation";

    /**
     * Official MinimizedBallView reference diameter. The 48x124dp box at an
     * 8dp top is defined for a 96dp ball; the actual resting ball here is
     * 96dp, and the box scales with the bounds.
     */
    private static final float REFERENCE_BALL_SIZE_DP = 96f;
    /** Official {@code cp_brush_width_normal}, {@code cp_brush_width_large}. */
    private static final float BRUSH_WIDTH_DP = 48f;
    private static final float ERASER_WIDTH_DP = 68f;
    /** Official {@code cp_brush_height} and the ImageView top margin, in dp. */
    private static final float BRUSH_HEIGHT_DP = 124f;
    private static final float BRUSH_TOP_MARGIN_DP = 8f;
    /** Bounds whose aspect is the brush-box aspect are the ImageView itself. */
    private static final float BOX_ASPECT_TOLERANCE = 0.02f;

    private static Resources sHostResources;
    private static boolean sHostResolved;

    private final Types.PenStyle style;
    private final boolean ballArt;

    private final Bitmap bodyBitmap;
    private final Bitmap headBitmap;
    private final Bitmap highlightBitmap;

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Path circle = new Path();

    private int alpha = 255;
    private ColorFilter colorFilter;

    public BrushDrawable(Context context, Types.PenStyle style, int color, boolean ballArt) {
        this.style = style != null ? style : Types.PenStyle.INK_PEN;
        this.ballArt = ballArt;

        final boolean night = isNight(context);
        if (this.style == Types.PenStyle.ERASER) {
            // pk_eraser_tool already ships as the finished grey rubber; Mi
            // Canvas does not tint it, and neither do we.
            bodyBitmap = loadNamed(context, "pk_eraser_tool");
            headBitmap = null;
            highlightBitmap = null;
        } else {
            final String art = this.style.art;
            bodyBitmap = loadLayer(context, "pk_" + art + "_body", night);
            headBitmap = loadLayer(context, "pk_" + art + "_head", night);
            highlightBitmap = hasHighlight(this.style)
                    ? loadLayer(context, "pk_" + art + "_highlight", night)
                    : null;
        }
        // The head is the only tinted layer; the body and the highlight keep
        // their shipped colours.
        headPaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect bounds = getBounds();
        final int w = bounds.width();
        final int h = bounds.height();
        if (w <= 0 || h <= 0) {
            return;
        }

        final boolean ballComposition = ballArt && usesBallComposition(w, h);
        final int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        if (ballComposition) {
            // The official ball is this oval clip. In ImageView-box mode the
            // parent ball already clips, and an inscribed circle here would
            // crop the pen instead.
            final float radius = Math.min(w, h) / 2f;
            circle.reset();
            circle.addCircle(w / 2f, h / 2f, radius, Path.Direction.CW);
            canvas.clipPath(circle);
        }

        bodyPaint.setAlpha(alpha);
        bodyPaint.setColorFilter(colorFilter);
        headPaint.setAlpha(alpha);
        highlightPaint.setAlpha(alpha);
        highlightPaint.setColorFilter(colorFilter);

        drawLayer(canvas, bodyBitmap, bodyPaint, w, h, ballComposition);
        drawLayer(canvas, headBitmap, headPaint, w, h, ballComposition);
        drawLayer(canvas, highlightBitmap, highlightPaint, w, h, ballComposition);

        canvas.restoreToCount(save);
    }

    /** The Kotlin {@code fit()} ballArt branch, applied to the layer bitmap. */
    private void drawLayer(Canvas canvas, Bitmap src, Paint paint, int w, int h,
                           boolean ballComposition) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return;
        }
        final float drawWidth;
        final float drawHeight;
        final float left;
        final float top;
        if (ballComposition) {
            final float d = Math.min(w, h);
            // unit is px per dp: the official box is expressed in dp.
            final float unit = d / REFERENCE_BALL_SIZE_DP;
            final float boxWidth =
                    (style == Types.PenStyle.ERASER ? ERASER_WIDTH_DP : BRUSH_WIDTH_DP) * unit;
            final float boxHeight = BRUSH_HEIGHT_DP * unit;
            final float boxTop = BRUSH_TOP_MARGIN_DP * unit;
            final float scale = Math.min(boxWidth / src.getWidth(), boxHeight / src.getHeight());
            drawWidth = src.getWidth() * scale;
            drawHeight = src.getHeight() * scale;
            left = (w - drawWidth) / 2f;
            top = boxTop + (boxHeight - drawHeight) / 2f;
        } else {
            // Either the ImageView already performed the ball box placement,
            // or ballArt is off: plain FIT_CENTER inside the bounds.
            final float scale = Math.min((float) w / src.getWidth(), (float) h / src.getHeight());
            drawWidth = src.getWidth() * scale;
            drawHeight = src.getHeight() * scale;
            left = (w - drawWidth) / 2f;
            top = (h - drawHeight) / 2f;
        }
        dst.set(left, top, left + drawWidth, top + drawHeight);
        canvas.drawBitmap(src, null, dst, paint);
    }

    private boolean usesBallComposition(int w, int h) {
        final float boxAspect =
                (style == Types.PenStyle.ERASER ? ERASER_WIDTH_DP : BRUSH_WIDTH_DP)
                        / BRUSH_HEIGHT_DP;
        return Math.abs(((float) w / (float) h) - boxAspect) > BOX_ASPECT_TOLERANCE;
    }

    @Override
    public void setAlpha(int alpha) {
        final int clamped = Math.max(0, Math.min(255, alpha));
        if (this.alpha != clamped) {
            this.alpha = clamped;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        if (colorFilter != filter) {
            colorFilter = filter;
            invalidateSelf();
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** Mi Canvas only composites a highlight for these three tools. */
    private static boolean hasHighlight(Types.PenStyle style) {
        return style == Types.PenStyle.PENCIL
                || style == Types.PenStyle.MARKER
                || style == Types.PenStyle.WATER_COLOR;
    }

    /**
     * Night artwork is selected by the picker background's luminance, exactly
     * like the Kotlin widget. The official package has no {@code _dark} brush
     * bitmaps today, so this normally falls back to the day artwork.
     */
    private static boolean isNight(Context context) {
        final int background;
        try {
            background = ContextCompat.getColor(context, R.color.penpicker_background_color);
        } catch (Resources.NotFoundException missing) {
            return false;
        }
        final float luminance = 0.299f * Color.red(background)
                + 0.587f * Color.green(background)
                + 0.114f * Color.blue(background);
        return luminance < 128f;
    }

    /** Try the night layer first, then the normal one. */
    private static Bitmap loadLayer(Context context, String baseName, boolean night) {
        if (night) {
            Bitmap dark = loadNamed(context, baseName + "_dark");
            if (dark != null) {
                return dark;
            }
        }
        return loadNamed(context, baseName);
    }

    /** App drawable first, then Mi Canvas' drawable with the same name. */
    private static Bitmap loadNamed(Context context, String name) {
        Resources own = context.getResources();
        int ownId = own.getIdentifier(name, "drawable", context.getPackageName());
        if (ownId != 0) {
            try {
                Bitmap bitmap = bitmapOf(ContextCompat.getDrawable(context, ownId));
                if (bitmap != null) {
                    return bitmap;
                }
            } catch (Exception ignored) {
                // Fall through to Mi Canvas' copy.
            }
        }
        Resources host = hostResources(context);
        if (host != null) {
            int hostId = host.getIdentifier(name, "drawable", MC_PACKAGE);
            if (hostId != 0) {
                try {
                    return bitmapOf(host.getDrawable(hostId, null));
                } catch (Exception ignored) {
                    // Artwork missing or not loadable: the layer stays empty.
                }
            }
        }
        return null;
    }

    private static Bitmap bitmapOf(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        return null;
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
                sHostResources = null;
            }
        }
        return sHostResources;
    }
}
