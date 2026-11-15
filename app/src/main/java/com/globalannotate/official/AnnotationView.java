package com.globalannotate.official;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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

        Stroke(Types.PenStyle style, int color, float baseWidth, int alpha,
               boolean eraser, boolean stylus) {
            this.style = style;
            this.color = color;
            this.baseWidth = baseWidth;
            this.alpha = alpha;
            this.eraser = eraser;
            this.stylus = stylus;
        }

        void add(float x, float y, float pressure) {
            points.add(new float[]{x, y, pressure});
        }
    }

    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private final ArrayList<Stroke> redoStack = new ArrayList<>();

    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint erasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        return !strokes.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        finishActive();
        if (strokes.isEmpty()) {
            return;
        }
        redoStack.add(strokes.remove(strokes.size() - 1));
        invalidate();
    }

    public void redo() {
        finishActive();
        if (redoStack.isEmpty()) {
            return;
        }
        strokes.add(redoStack.remove(redoStack.size() - 1));
        invalidate();
    }

    public void clear() {
        active = null;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        strokes.clear();
        redoStack.clear();
        invalidate();
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
        // Any new stroke invalidates the redo branch.
        redoStack.clear();
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

        final Paint paint = stroke.eraser ? erasePaint : prepareInkPaint(stroke);
        final float width = stroke.baseWidth * widthFactor(stroke.style);

        if (count == 1) {
            drawDot(canvas, paint, stroke, width, stroke.points.get(0));
            return;
        }

        if (!stroke.stylus) {
            // One path per stroke gives semi-transparent markers a uniform
            // colour instead of darkening at every segment joint.
            strokePath.reset();
            final float[] first = stroke.points.get(0);
            strokePath.moveTo(first[0], first[1]);
            for (int i = 1; i < count; i++) {
                final float[] point = stroke.points.get(i);
                strokePath.lineTo(point[0], point[1]);
            }
            paint.setStrokeWidth(width);
            canvas.drawPath(strokePath, paint);
            return;
        }

        float[] previous = stroke.points.get(0);
        for (int i = 1; i < count; i++) {
            float[] point = stroke.points.get(i);
            paint.setStrokeWidth(width * pressureFactor(stroke, point[2]));
            canvas.drawLine(previous[0], previous[1], point[0], point[1], paint);
            previous = point;
        }
    }

    private Paint prepareInkPaint(Stroke stroke) {
        inkPaint.setXfermode(null);
        inkPaint.setColor(stroke.color);
        // Panel opacity times the style's own factor (long-standing look).
        inkPaint.setAlpha(stroke.alpha * alphaFactor(stroke.style) / 255);
        return inkPaint;
    }

    private void drawDot(Canvas canvas, Paint paint, Stroke stroke, float width, float[] point) {
        paint.setStrokeWidth(width * pressureFactor(stroke, point[2]));
        final Paint.Style oldStyle = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(point[0], point[1], width * 0.5f
                * pressureFactor(stroke, point[2]), paint);
        paint.setStyle(oldStyle);
    }

    private float pressureFactor(Stroke stroke, float pressure) {
        if (!stroke.stylus) {
            return 1f;
        }
        float p = Math.max(0.1f, Math.min(1f, pressure));
        // Stylus pressure changes width gently; the base width still dominates.
        return 0.45f + 0.9f * p;
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

    private static int alphaFactor(Types.PenStyle style) {
        switch (style) {
            case PENCIL:
                return 205;
            case MARKER:
                return 115;
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
