package com.globalannotate.official;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * Full-screen ink canvas for the overlay.
 *
 * <p>Strokes are kept as point streams and replayed on every frame. The view is
 * software rendered so the eraser can use {@link PorterDuff.Mode#CLEAR}: an
 * eraser stroke is just another stroke in the list and erases everything drawn
 * before it. Undo/redo simply move strokes between two lists.</p>
 */
public final class AnnotationView extends View {

    /** One finished or in-flight stroke. Points are stored x, y, pressure. */
    private static final class Stroke {
        final Types.PenStyle style;
        final int color;
        final float baseWidth;
        /** Pen opacity 3..255 captured when the stroke started. */
        final int alpha;
        final boolean eraser;
        final boolean stylus;
        final ArrayList<float[]> points = new ArrayList<>();
        /**
         * Stable per-stroke noise seed. Grain and mottling patterns are
         * derived from it, so they never shimmer between frames.
         */
        final int seed;
        /** Render-only geometry cache; never touched by undo/snapshot code. */
        RenderCache renderCache;

        Stroke(Types.PenStyle style, int color, float baseWidth, int alpha,
               boolean eraser, boolean stylus) {
            this.style = style;
            this.color = color;
            this.baseWidth = baseWidth;
            this.alpha = alpha;
            this.eraser = eraser;
            this.stylus = stylus;
            this.seed = nextSeed = nextSeed * 1103515245 + 12345;
        }

        void add(float x, float y, float pressure) {
            points.add(new float[]{x, y, pressure});
        }
    }

    /** Source for {@link Stroke#seed}; an LCG keeps seeds stable per stroke. */
    private static int nextSeed = 0x1D2C3B4A;

    /**
     * Cached replay geometry for one stroke. Everything here is derived from
     * {@link Stroke#points} and the stroke width; it is rebuilt only when the
     * point count or the endpoint coordinates change (append or rotation
     * rescale), never per frame for a finished stroke.
     */
    private static final class RenderCache {
        int pointCount = -1;
        float width;
        float firstX;
        float firstY;
        float lastX;
        float lastY;
        float totalLength;
        /** Packed unit normals: [nx0, ny0, nx1, ny1, ...]. */
        float[] normals = new float[0];
        /** Half stroke width at each point, from the style's width profile. */
        float[] halfWidths = new float[0];
        Path center;
        Path outline;
        Path outlineCore;
        Path grainA;
        Path grainB;
        PathEffect dashA;
        PathEffect dashB;
        PathEffect mottleDash;
        BlurMaskFilter haloBlur;
        BlurMaskFilter mottleBlur;
    }

    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private final ArrayList<Stroke> redoStack = new ArrayList<>();

    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint erasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Water-colour washes: mask filters and dashes live here only. */
    private final Paint softPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Pencil grain passes: their own paint so inkPaint state never leaks. */
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Reused by eraser strokes, which keep their original replay. */
    private final Path strokePath = new Path();

    private Types.PenStyle penStyle = Types.PenStyle.BALLPOINT;
    private int penColor = Types.FIXED_COLORS[3];
    private float penWidth;
    /** Opacity from the brush panel, Mi Canvas' raw 3..255 units. */
    private int penAlpha = 255;
    /** Whether a finger starts a stroke; stylus input always draws. */
    private boolean fingerDrawEnabled = true;

    private Stroke active;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    /** Strokes removed by the last clear(), restorable as one undo action. */
    private ArrayList<Stroke> clearedSnapshot;
    /** A redo that should re-apply the clear it undid. */
    private boolean clearRedoPending;

    public AnnotationView(Context context) {
        super(context);
        // CLEAR is only reliable on a software canvas.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setFocusable(false);

        penWidth = 4f * getResources().getDisplayMetrics().density;

        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeCap(Paint.Cap.ROUND);
        inkPaint.setStrokeJoin(Paint.Join.ROUND);
        inkPaint.setDither(true);

        erasePaint.setStyle(Paint.Style.STROKE);
        erasePaint.setStrokeCap(Paint.Cap.ROUND);
        erasePaint.setStrokeJoin(Paint.Join.ROUND);
        erasePaint.setDither(true);
        erasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    // ------------------------------------------------------------------
    // Public brush API
    // ------------------------------------------------------------------

    public void setPenStyle(Types.PenStyle style) {
        if (style != null) {
            penStyle = style;
        }
    }

    public void setPenColor(int color) {
        penColor = color;
    }

    public void setPenWidth(float width) {
        penWidth = Math.max(1f, width);
    }

    /** Pen opacity in Mi Canvas' raw 3..255 units; existing strokes keep theirs. */
    public void setPenAlpha(int alpha) {
        penAlpha = Math.max(3, Math.min(255, alpha));
    }

    /**
     * Turns finger strokes on or off ("手指涂画" in the more menu). The stylus
     * always draws; turning fingers off only stops them starting ink.
     */
    public void setFingerDrawEnabled(boolean enabled) {
        fingerDrawEnabled = enabled;
    }

    public Types.PenStyle penStyle() {
        return penStyle;
    }

    public int penColor() {
        return penColor;
    }

    public int penAlpha() {
        return penAlpha;
    }

    public boolean canUndo() {
        return !strokes.isEmpty() || clearedSnapshot != null;
    }

    public boolean canRedo() {
        return !redoStack.isEmpty() || clearRedoPending;
    }

    public void undo() {
        finishActive();
        if (clearedSnapshot != null) {
            // One clear is one undoable action: bring every stroke back.
            strokes.addAll(clearedSnapshot);
            clearedSnapshot = null;
            clearRedoPending = true;
            invalidate();
            return;
        }
        if (strokes.isEmpty()) {
            return;
        }
        // Undoing past the clear means the clear is no longer the next redo.
        clearRedoPending = false;
        redoStack.add(strokes.remove(strokes.size() - 1));
        invalidate();
    }

    public void redo() {
        finishActive();
        if (clearRedoPending) {
            clearedSnapshot = new ArrayList<>(strokes);
            strokes.clear();
            clearRedoPending = false;
            invalidate();
            return;
        }
        if (redoStack.isEmpty()) {
            return;
        }
        strokes.add(redoStack.remove(redoStack.size() - 1));
        invalidate();
    }

    public void clear() {
        active = null;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        // Clearing is undoable: keep the removed strokes as one action.
        clearedSnapshot = strokes.isEmpty() ? null : new ArrayList<>(strokes);
        clearRedoPending = false;
        strokes.clear();
        redoStack.clear();
        invalidate();
    }

    /**
     * Keeps ink at the same relative place when the screen rotates. The
     * overlay window is resized by WindowManager, so the stored points are
     * rescaled to the new width/height instead of sliding off the screen.
     * The first layout pass (old size zero) must not scale anything.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw <= 0 || oldh <= 0 || w <= 0 || h <= 0) {
            return;
        }
        if (w == oldw && h == oldh) {
            return;
        }
        final float sx = (float) w / (float) oldw;
        final float sy = (float) h / (float) oldh;
        for (int i = 0; i < strokes.size(); i++) {
            scaleStroke(strokes.get(i), sx, sy);
        }
        for (int i = 0; i < redoStack.size(); i++) {
            scaleStroke(redoStack.get(i), sx, sy);
        }
        if (clearedSnapshot != null) {
            for (int i = 0; i < clearedSnapshot.size(); i++) {
                scaleStroke(clearedSnapshot.get(i), sx, sy);
            }
        }
    }

    private static void scaleStroke(Stroke stroke, float sx, float sy) {
        for (int i = 0; i < stroke.points.size(); i++) {
            final float[] point = stroke.points.get(i);
            point[0] *= sx;
            point[1] *= sy;
        }
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            // A previous gesture was lost; close it instead of leaking it.
            finishActive();
            final int toolType = event.getToolType(0);
            if (!fingerDrawEnabled && toolType != MotionEvent.TOOL_TYPE_STYLUS
                    && toolType != MotionEvent.TOOL_TYPE_ERASER) {
                // Swallow: without the passthrough helper the window would eat
                // the touch anyway, and a stroke here would be unwanted ink.
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                return true;
            }
            activePointerId = event.getPointerId(0);
            beginStroke(event, 0);
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            // A second finger never draws.
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            final int index = event.findPointerIndex(activePointerId);
            if (index < 0 || active == null) {
                return true;
            }
            for (int h = 0; h < event.getHistorySize(); h++) {
                appendPoint(event, index, h);
            }
            appendPoint(event, index, -1);
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
            final int index = event.getActionIndex();
            if (event.getPointerId(index) == activePointerId) {
                appendPoint(event, index, -1);
                finishActive();
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                invalidate();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            finishActive();
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            invalidate();
            return true;
        }

        return true;
    }

    private void beginStroke(MotionEvent event, int index) {
        final Types.PenStyle style = penStyle;
        final boolean eraser = style.eraser;
        final boolean stylus = event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS;
        active = new Stroke(style, penColor, penWidth, penAlpha, eraser, stylus);
        active.add(event.getX(index), event.getY(index), event.getPressure(index));
        strokes.add(active);
        // Any new stroke invalidates the redo branch, including a redo of a
        // clear that was undone.
        redoStack.clear();
        clearedSnapshot = null;
        clearRedoPending = false;
    }

    private void appendPoint(MotionEvent event, int pointerIndex, int historyIndex) {
        if (active == null || pointerIndex < 0) {
            return;
        }
        if (historyIndex >= 0 && historyIndex < event.getHistorySize()) {
            active.add(event.getHistoricalX(pointerIndex, historyIndex),
                    event.getHistoricalY(pointerIndex, historyIndex),
                    event.getHistoricalPressure(pointerIndex, historyIndex));
        } else {
            active.add(event.getX(pointerIndex), event.getY(pointerIndex),
                    event.getPressure(pointerIndex));
        }
    }

    private void finishActive() {
        // A one-point stroke is a dot and stays in the list; there is nothing
        // else to commit because strokes are drawn from the point list.
        active = null;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // The overlay window keeps its last buffer unless the root view clears
        // it. Without this, erased ink and undone strokes leave ghosts.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        for (int i = 0; i < strokes.size(); i++) {
            drawStroke(canvas, strokes.get(i));
        }
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        final int count = stroke.points.size();
        if (count == 0) {
            return;
        }
        final float width = stroke.baseWidth * widthFactor(stroke.style);

        // The eraser keeps the original replay: its CLEAR behaviour, including
        // the per-segment stylus path, must not change with the pens.
        if (stroke.eraser) {
            drawEraserStroke(canvas, stroke, width);
            return;
        }
        if (count == 1) {
            drawDot(canvas, stroke, width, stroke.points.get(0));
            return;
        }

        switch (stroke.style) {
            case PENCIL:
                drawPencil(canvas, stroke, width);
                break;
            case MARKER:
                drawMarker(canvas, stroke, width);
                break;
            case WATER_COLOR:
                drawWaterColor(canvas, stroke, width);
                break;
            case INK_PEN:
            case BALLPOINT:
            default:
                drawFilledInk(canvas, stroke, width);
                break;
        }
    }

    /** Original eraser replay, unchanged (round caps, CLEAR, per-segment). */
    private void drawEraserStroke(Canvas canvas, Stroke stroke, float width) {
        final int count = stroke.points.size();
        if (count == 1) {
            final float[] point = stroke.points.get(0);
            final float radius = width * 0.5f * pressureFactor(stroke, point[2]);
            erasePaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(point[0], point[1], radius, erasePaint);
            erasePaint.setStyle(Paint.Style.STROKE);
            return;
        }
        if (!stroke.stylus) {
            strokePath.reset();
            final float[] first = stroke.points.get(0);
            strokePath.moveTo(first[0], first[1]);
            for (int i = 1; i < count; i++) {
                final float[] point = stroke.points.get(i);
                strokePath.lineTo(point[0], point[1]);
            }
            erasePaint.setStrokeWidth(width);
            canvas.drawPath(strokePath, erasePaint);
            return;
        }
        float[] previous = stroke.points.get(0);
        for (int i = 1; i < count; i++) {
            float[] point = stroke.points.get(i);
            erasePaint.setStrokeWidth(width * pressureFactor(stroke, point[2]));
            canvas.drawLine(previous[0], previous[1], point[0], point[1], erasePaint);
            previous = point;
        }
    }

    /**
     * Ballpoint and ink pen: one filled variable-width outline, replayed from
     * the cache. Ballpoint stays near-constant and hard-edged; the ink pen's
     * width profile pinches both ends and follows stylus pressure strongly.
     */
    private void drawFilledInk(Canvas canvas, Stroke stroke, float width) {
        final RenderCache cache = ensureCache(stroke, width);
        final Paint paint = prepareInkPaint(stroke);
        paint.setStyle(Paint.Style.FILL);
        paint.setPathEffect(null);
        paint.setMaskFilter(null);
        canvas.drawPath(cache.outline, paint);
    }

    /**
     * Marker: one stroked path for every input, stylus included, so the
     * translucent colour is composited once and never darkens at joints or
     * where the stroke crosses itself. Butt caps and bevel joins give the
     * chisel-tip footprint.
     */
    private void drawMarker(Canvas canvas, Stroke stroke, float width) {
        final RenderCache cache = ensureCache(stroke, width);
        final Paint paint = prepareInkPaint(stroke);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.BEVEL);
        paint.setPathEffect(null);
        paint.setMaskFilter(null);
        canvas.drawPath(cache.center, paint);
    }

    /**
     * Pencil: a thin solid core plus two deterministically jittered, dashed
     * centre lines. The dashes and lateral offsets give the broken graphite
     * texture; all noise comes from the stored stroke seed, so the pattern is
     * identical on every redraw.
     */
    private void drawPencil(Canvas canvas, Stroke stroke, float width) {
        final RenderCache cache = ensureCache(stroke, width);

        final Paint paint = prepareInkPaint(stroke);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setPathEffect(null);
        paint.setMaskFilter(null);
        paint.setStrokeWidth(Math.max(0.6f, width * 0.56f));
        paint.setAlpha(scaledAlpha(stroke, 0.34f));
        canvas.drawPath(cache.center, paint);

        final Paint grain = grainPaint;
        grain.setXfermode(null);
        grain.setMaskFilter(null);
        grain.setColor(stroke.color);
        grain.setStyle(Paint.Style.STROKE);
        grain.setStrokeCap(Paint.Cap.ROUND);
        grain.setStrokeJoin(Paint.Join.ROUND);

        grain.setStrokeWidth(Math.max(0.5f, width * 0.44f));
        grain.setAlpha(scaledAlpha(stroke, 0.55f));
        grain.setPathEffect(cache.dashA);
        canvas.drawPath(cache.grainA, grain);

        grain.setStrokeWidth(Math.max(0.4f, width * 0.34f));
        grain.setAlpha(scaledAlpha(stroke, 0.45f));
        grain.setPathEffect(cache.dashB);
        canvas.drawPath(cache.grainB, grain);

        grain.setPathEffect(null);
    }

    /**
     * Water colour: a blurred wide wash, a narrower unblurred pigment core and
     * a blurred dashed centre line for mottling. The wash carries the wet edge;
     * the core keeps the middle from washing out.
     */
    private void drawWaterColor(Canvas canvas, Stroke stroke, float width) {
        final RenderCache cache = ensureCache(stroke, width);
        final Paint paint = softPaint;
        paint.setXfermode(null);
        paint.setColor(stroke.color);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setPathEffect(null);

        paint.setMaskFilter(cache.haloBlur);
        paint.setAlpha(scaledAlpha(stroke, 0.30f));
        canvas.drawPath(cache.outline, paint);

        paint.setMaskFilter(null);
        paint.setAlpha(scaledAlpha(stroke, 0.55f));
        canvas.drawPath(cache.outlineCore, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, width * 0.42f));
        paint.setPathEffect(cache.mottleDash);
        paint.setMaskFilter(cache.mottleBlur);
        paint.setAlpha(scaledAlpha(stroke, 0.40f));
        canvas.drawPath(cache.center, paint);

        paint.setMaskFilter(null);
        paint.setPathEffect(null);
        paint.setAlpha(255);
    }

    private Paint prepareInkPaint(Stroke stroke) {
        inkPaint.setXfermode(null);
        inkPaint.setMaskFilter(null);
        inkPaint.setPathEffect(null);
        inkPaint.setColor(stroke.color);
        // Panel opacity times the style's own factor (long-standing look).
        inkPaint.setAlpha(scaledAlpha(stroke, 1f));
        return inkPaint;
    }

    /** Panel opacity, the style factor and an extra per-pass factor. */
    private static int scaledAlpha(Stroke stroke, float factor) {
        final int base = stroke.alpha * alphaFactor(stroke.style) / 255;
        return Math.max(0, Math.min(255, Math.round(base * factor)));
    }

    private void drawDot(Canvas canvas, Stroke stroke, float width, float[] point) {
        final float radius = width * 0.5f * pressureFactor(stroke, point[2]);
        final float x = point[0];
        final float y = point[1];
        final float phase = (stroke.seed & 0xFFFF) * (float) (Math.PI * 2.0 / 65536.0);

        switch (stroke.style) {
            case MARKER: {
                // Flat chisel tip: a square tap, not a round dot.
                final Paint paint = prepareInkPaint(stroke);
                paint.setStyle(Paint.Style.FILL);
                paint.setMaskFilter(null);
                paint.setPathEffect(null);
                canvas.drawRect(x - radius, y - radius, x + radius, y + radius, paint);
                break;
            }
            case PENCIL: {
                final Paint paint = prepareInkPaint(stroke);
                paint.setStyle(Paint.Style.FILL);
                paint.setMaskFilter(null);
                paint.setPathEffect(null);
                for (int i = 0; i < 4; i++) {
                    final float angle = phase + i * 1.5708f;
                    final float distance = i == 0 ? 0f : radius * 0.55f;
                    final float dotRadius = radius * (i == 0 ? 0.7f : 0.28f);
                    paint.setAlpha(scaledAlpha(stroke, i == 0 ? 0.30f : 0.45f));
                    canvas.drawCircle(x + (float) Math.cos(angle) * distance,
                            y + (float) Math.sin(angle) * distance, dotRadius, paint);
                }
                break;
            }
            case WATER_COLOR: {
                // Reuses the stroke cache only for its blur filters.
                final RenderCache cache = ensureCache(stroke, width);
                final Paint paint = softPaint;
                paint.setXfermode(null);
                paint.setColor(stroke.color);
                paint.setStyle(Paint.Style.FILL);
                paint.setPathEffect(null);
                paint.setMaskFilter(cache.haloBlur);
                paint.setAlpha(scaledAlpha(stroke, 0.30f));
                canvas.drawCircle(x, y, Math.max(0.5f, radius * 1.05f), paint);
                paint.setMaskFilter(null);
                paint.setAlpha(scaledAlpha(stroke, 0.55f));
                canvas.drawCircle(x, y, Math.max(0.5f, radius * 0.78f), paint);
                paint.setAlpha(255);
                break;
            }
            case INK_PEN: {
                final Paint paint = prepareInkPaint(stroke);
                paint.setStyle(Paint.Style.FILL);
                paint.setMaskFilter(null);
                paint.setPathEffect(null);
                canvas.drawCircle(x, y, Math.max(0.5f, radius * 0.88f), paint);
                break;
            }
            case BALLPOINT:
            default: {
                final Paint paint = prepareInkPaint(stroke);
                paint.setStyle(Paint.Style.FILL);
                paint.setMaskFilter(null);
                paint.setPathEffect(null);
                canvas.drawCircle(x, y, radius, paint);
                break;
            }
        }
    }

    /**
     * Only stylus points carry pressure, so finger strokes use 1.0 and each
     * style turns pressure into a different amount of width change.
     */
    private static float pressureFactor(Stroke stroke, float pressure) {
        if (stroke.eraser) {
            // Original eraser response, kept bit-for-bit.
            if (!stroke.stylus) {
                return 1f;
            }
            final float p = Math.max(0.1f, Math.min(1f, pressure));
            return 0.45f + 0.9f * p;
        }
        if (!stroke.stylus) {
            return 1f;
        }
        final float p = Math.max(0.1f, Math.min(1f, pressure));
        switch (stroke.style) {
            case BALLPOINT:
                // Slight response: the line stays a ballpoint line.
                return 0.88f + 0.18f * p;
            case INK_PEN:
                // Strong response; the outline taper adds the pinched ends.
                return 0.45f + 0.9f * p;
            case PENCIL:
                return 0.72f + 0.45f * p;
            case WATER_COLOR:
                return 0.60f + 0.55f * p;
            case MARKER:
            default:
                return 1f;
        }
    }

    // ---------------------------------------------------------------
    // Cached replay geometry (render-only; no point storage is changed)
    // ---------------------------------------------------------------

    /**
     * Returns the cached geometry for the stroke, rebuilding it only when the
     * points were appended to or rescaled. Endpoint comparison is enough: the
     * only writes to point coordinates are append (moves the last point) and
     * {@code scaleStroke} (moves every point, hence the first and last).
     */
    private RenderCache ensureCache(Stroke stroke, float width) {
        final ArrayList<float[]> points = stroke.points;
        final int count = points.size();
        final float[] first = points.get(0);
        final float[] last = points.get(count - 1);
        RenderCache cache = stroke.renderCache;
        if (cache != null
                && cache.pointCount == count
                && cache.width == width
                && cache.firstX == first[0]
                && cache.firstY == first[1]
                && cache.lastX == last[0]
                && cache.lastY == last[1]) {
            return cache;
        }
        if (cache == null) {
            cache = new RenderCache();
            stroke.renderCache = cache;
        }
        if (cache.width != width) {
            // Effects depend on the stroke width; drop them with the geometry.
            cache.dashA = null;
            cache.dashB = null;
            cache.mottleDash = null;
            cache.haloBlur = null;
            cache.mottleBlur = null;
        }
        cache.pointCount = count;
        cache.width = width;
        cache.firstX = first[0];
        cache.firstY = first[1];
        cache.lastX = last[0];
        cache.lastY = last[1];

        computeGeometry(stroke, cache, width);
        switch (stroke.style) {
            case PENCIL:
                if (cache.center == null) {
                    cache.center = new Path();
                }
                if (cache.grainA == null) {
                    cache.grainA = new Path();
                }
                if (cache.grainB == null) {
                    cache.grainB = new Path();
                }
                buildPencilCenter(stroke, cache.center);
                buildGrain(stroke, cache, cache.grainA, 0x51ED270B, 0.40f, width);
                buildGrain(stroke, cache, cache.grainB, 0x2C1B3C6D, 0.58f, width);
                if (cache.dashA == null) {
                    final float unit = Math.max(2f, width);
                    cache.dashA = new DashPathEffect(
                            new float[]{unit * 0.55f, unit * 0.35f},
                            (stroke.seed >>> 8 & 0x7) * 0.5f);
                    cache.dashB = new DashPathEffect(
                            new float[]{unit * 0.28f, unit * 0.62f,
                                    unit * 0.18f, unit * 0.40f},
                            (stroke.seed >>> 12 & 0x7) * 0.7f);
                }
                break;
            case MARKER:
                if (cache.center == null) {
                    cache.center = new Path();
                }
                buildSmoothCenter(stroke, cache.center);
                break;
            case WATER_COLOR:
                if (cache.center == null) {
                    cache.center = new Path();
                }
                if (cache.outline == null) {
                    cache.outline = new Path();
                }
                if (cache.outlineCore == null) {
                    cache.outlineCore = new Path();
                }
                buildSmoothCenter(stroke, cache.center);
                buildOutline(stroke, cache, cache.outline, 1f);
                buildOutline(stroke, cache, cache.outlineCore, 0.78f);
                if (cache.haloBlur == null) {
                    cache.haloBlur = new BlurMaskFilter(
                            Math.max(1.5f, width * 0.22f), BlurMaskFilter.Blur.NORMAL);
                    cache.mottleBlur = new BlurMaskFilter(
                            Math.max(1f, width * 0.12f), BlurMaskFilter.Blur.NORMAL);
                    final float unit = Math.max(2f, width);
                    cache.mottleDash = new DashPathEffect(
                            new float[]{unit * 1.1f, unit * 0.65f},
                            (stroke.seed >>> 4 & 0xF) * 0.9f);
                }
                break;
            case INK_PEN:
            case BALLPOINT:
            default:
                if (cache.outline == null) {
                    cache.outline = new Path();
                }
                buildOutline(stroke, cache, cache.outline, 1f);
                break;
        }
        return cache;
    }

    /** Normals and per-point half widths; reuses the cached arrays. */
    private static void computeGeometry(Stroke stroke, RenderCache cache, float width) {
        final ArrayList<float[]> points = stroke.points;
        final int n = points.size();
        ensureCapacity(cache, n);

        float total = 0f;
        for (int i = 1; i < n; i++) {
            final float[] a = points.get(i - 1);
            final float[] b = points.get(i);
            total += (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        cache.totalLength = total;

        for (int i = 0; i < n; i++) {
            final float[] p = points.get(i);
            final float[] previous = points.get(i == 0 ? 0 : i - 1);
            final float[] next = points.get(i == n - 1 ? n - 1 : i + 1);
            final float dx = next[0] - previous[0];
            final float dy = next[1] - previous[1];
            final float length = (float) Math.hypot(dx, dy);
            if (length < 0.0001f) {
                cache.normals[i * 2] = 1f;
                cache.normals[i * 2 + 1] = 0f;
            } else {
                cache.normals[i * 2] = -dy / length;
                cache.normals[i * 2 + 1] = dx / length;
            }
            cache.halfWidths[i] = halfWidthAt(stroke, cache, i, n, width, p[2]);
        }
    }

    private static void ensureCapacity(RenderCache cache, int count) {
        if (cache.normals.length >= count * 2 && cache.halfWidths.length >= count) {
            return;
        }
        final int capacity = Math.max(32, count + (count >> 1) + 8);
        cache.normals = new float[capacity * 2];
        cache.halfWidths = new float[capacity];
    }

    /**
     * Per-style half width at point {@code index}. {@code u} runs 0..1 along
     * the point stream (input points are dense for a stylus and reasonably
     * even for a finger, so index fraction tracks stroke progress closely).
     */
    private static float halfWidthAt(Stroke stroke, RenderCache cache, int index,
                                     int count, float width, float pressure) {
        final float base = width * 0.5f;
        final float u = count <= 1 ? 0.5f : index / (float) (count - 1);
        final float pressureScale = pressureFactor(stroke, pressure);
        switch (stroke.style) {
            case INK_PEN: {
                // Pinched ends, thick middle. Short strokes keep more of their
                // width so a small tick does not collapse to a hairline.
                float profile = 0.16f + 0.84f * (float) Math.sin(Math.PI * u);
                final float strength = Math.min(1f,
                        cache.totalLength / Math.max(1f, width * 4f));
                profile = 1f - strength * (1f - profile);
                return base * profile * pressureScale;
            }
            case WATER_COLOR: {
                // Gentle width breathing; phase comes from the stroke seed.
                final float phase = ((stroke.seed >>> 5) & 0xFF) * 0.0245f;
                final float wobble = 1f + 0.13f
                        * (float) Math.sin(u * Math.PI * 4.0 + phase);
                return base * wobble * pressureScale;
            }
            case PENCIL:
            case BALLPOINT:
            default:
                return base * pressureScale;
        }
    }

    /** Quadratic-smoothed centre line, for marker/water-colour replay. */
    private static void buildSmoothCenter(Stroke stroke, Path out) {
        final ArrayList<float[]> points = stroke.points;
        final int n = points.size();
        out.rewind();
        final float[] first = points.get(0);
        out.moveTo(first[0], first[1]);
        if (n == 1) {
            return;
        }
        if (n == 2) {
            final float[] last = points.get(1);
            out.lineTo(last[0], last[1]);
            return;
        }
        for (int i = 1; i < n - 1; i++) {
            final float[] current = points.get(i);
            final float[] next = points.get(i + 1);
            out.quadTo(current[0], current[1],
                    (current[0] + next[0]) * 0.5f,
                    (current[1] + next[1]) * 0.5f);
        }
        final float[] last = points.get(n - 1);
        out.lineTo(last[0], last[1]);
    }

    /** Raw polyline for pencil; graphite does not need a smoothed spine. */
    private static void buildPencilCenter(Stroke stroke, Path out) {
        final ArrayList<float[]> points = stroke.points;
        final int n = points.size();
        out.rewind();
        final float[] first = points.get(0);
        out.moveTo(first[0], first[1]);
        for (int i = 1; i < n; i++) {
            final float[] point = points.get(i);
            out.lineTo(point[0], point[1]);
        }
    }

    /**
     * Deterministically offsets the centre line sideways (and slightly along
     * it) to make one pencil grain pass.
     */
    private static void buildGrain(Stroke stroke, RenderCache cache, Path out,
                                   int salt, float amplitude, float width) {
        final ArrayList<float[]> points = stroke.points;
        final int n = points.size();
        final float half = width * 0.5f;
        out.rewind();
        for (int i = 0; i < n; i++) {
            final float[] point = points.get(i);
            final float nx = cache.normals[i * 2];
            final float ny = cache.normals[i * 2 + 1];
            final float lateral = hashNoise(stroke.seed, i, salt) * half * amplitude;
            final float along = hashNoise(stroke.seed, i, salt + 17)
                    * half * amplitude * 0.35f;
            final float x = point[0] + nx * lateral + ny * along;
            final float y = point[1] + ny * lateral - nx * along;
            if (i == 0) {
                out.moveTo(x, y);
            } else {
                out.lineTo(x, y);
            }
        }
    }

    /**
     * Filled variable-width outline: smoothed right side forward, left side
     * back, plus round caps as extra sub-paths. The polygon is CCW in screen
     * coordinates, so the CW/CCW direction here must stay CCW to union with
     * the non-zero fill rule instead of punching holes in the caps.
     */
    private static void buildOutline(Stroke stroke, RenderCache cache, Path out,
                                     float widthScale) {
        final ArrayList<float[]> points = stroke.points;
        final int n = points.size();
        out.rewind();
        if (n == 0) {
            return;
        }
        final float[] first = points.get(0);
        final float firstHalf = cache.halfWidths[0] * widthScale;
        final float firstNx = cache.normals[0];
        final float firstNy = cache.normals[1];
        out.moveTo(first[0] + firstNx * firstHalf, first[1] + firstNy * firstHalf);

        if (n == 1) {
            out.lineTo(first[0] - firstNx * firstHalf,
                    first[1] - firstNy * firstHalf);
            out.close();
            out.addCircle(first[0], first[1], firstHalf, Path.Direction.CCW);
            return;
        }

        for (int i = 1; i < n - 1; i++) {
            final float[] current = points.get(i);
            final float currentHalf = cache.halfWidths[i] * widthScale;
            final float cx = current[0] + cache.normals[i * 2] * currentHalf;
            final float cy = current[1] + cache.normals[i * 2 + 1] * currentHalf;
            final float[] next = points.get(i + 1);
            final float nextHalf = cache.halfWidths[i + 1] * widthScale;
            final float nx = next[0] + cache.normals[(i + 1) * 2] * nextHalf;
            final float ny = next[1] + cache.normals[(i + 1) * 2 + 1] * nextHalf;
            out.quadTo(cx, cy, (cx + nx) * 0.5f, (cy + ny) * 0.5f);
        }

        final float[] last = points.get(n - 1);
        final float lastHalf = cache.halfWidths[n - 1] * widthScale;
        final float lastNx = cache.normals[(n - 1) * 2];
        final float lastNy = cache.normals[(n - 1) * 2 + 1];
        out.lineTo(last[0] + lastNx * lastHalf, last[1] + lastNy * lastHalf);
        out.lineTo(last[0] - lastNx * lastHalf, last[1] - lastNy * lastHalf);

        for (int i = n - 2; i >= 1; i--) {
            final float[] current = points.get(i);
            final float currentHalf = cache.halfWidths[i] * widthScale;
            final float cx = current[0] - cache.normals[i * 2] * currentHalf;
            final float cy = current[1] - cache.normals[i * 2 + 1] * currentHalf;
            final float[] previous = points.get(i - 1);
            final float previousHalf = cache.halfWidths[i - 1] * widthScale;
            final float px = previous[0]
                    - cache.normals[(i - 1) * 2] * previousHalf;
            final float py = previous[1]
                    - cache.normals[(i - 1) * 2 + 1] * previousHalf;
            out.quadTo(cx, cy, (cx + px) * 0.5f, (cy + py) * 0.5f);
        }

        out.lineTo(first[0] - firstNx * firstHalf, first[1] - firstNy * firstHalf);
        out.close();
        out.addCircle(first[0], first[1], firstHalf, Path.Direction.CCW);
        out.addCircle(last[0], last[1], lastHalf, Path.Direction.CCW);
    }

    /** Stable hash noise in [-1, 1]; used only while building a cached path. */
    private static float hashNoise(int seed, int index, int salt) {
        int h = seed * 0x9E3779B1 + index * 0x85EBCA6B + salt * 0xC2B2AE35;
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return (h & 0xFFFF) * (1f / 32767.5f) - 1f;
    }

    private static float widthFactor(Types.PenStyle style) {
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

    /**
     * Style opacity ceiling. Pencil stays well below full ink; marker is a
     * translucent highlighter; water colour is the faintest. The extra pass
     * factors in the draw helpers compose on top of these values.
     */
    private static int alphaFactor(Types.PenStyle style) {
        switch (style) {
            case PENCIL:
                return 150;
            case MARKER:
                return 110;
            case WATER_COLOR:
                return 72;
            case ERASER:
                return 255;
            case BALLPOINT:
            case INK_PEN:
            default:
                return 255;
        }
    }
}
