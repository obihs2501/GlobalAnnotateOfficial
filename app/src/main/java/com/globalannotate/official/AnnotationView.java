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
import java.util.IdentityHashMap;

/**
 * Full-screen ink canvas for the overlay.
 *
 * <p>Strokes are kept as point streams and replayed on every frame. The view is
 * software rendered so the eraser can use {@link PorterDuff.Mode#CLEAR}: an
 * eraser stroke is just another stroke in the list and erases everything drawn
 * before it. Undo/redo move whole edits (a drawn stroke, or a set of strokes
 * removed by clear / the whole-stroke eraser) between two stacks.</p>
 */
public final class AnnotationView extends View {

    /** One finished or in-flight stroke. Points are stored x, y, pressure. */
    private static final class Stroke {
        /**
         * Creation order. The stroke list is kept sorted by it, so a removed
         * stroke can be put back at exactly its old z-order position.
         */
        final long seq;
        final Types.PenStyle style;
        final int color;
        final float baseWidth;
        /** Pen opacity 3..255 captured when the stroke started. */
        final int alpha;
        final boolean eraser;
        final boolean stylus;
        final ArrayList<float[]> points = new ArrayList<>();

        Stroke(long seq, Types.PenStyle style, int color, float baseWidth, int alpha,
               boolean eraser, boolean stylus) {
            this.seq = seq;
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

    /**
     * One undoable edit: either a stroke that was drawn, or a set of strokes
     * that were removed together (clear, whole-stroke eraser). Removed strokes
     * are kept as objects, so undo can restore them with their old points,
     * style and z-order.
     */
    private static final class Edit {
        /** The stroke this edit drew; null for a removal. */
        final Stroke drawn;
        /** The strokes this edit removed; null for a draw. */
        final ArrayList<Stroke> removed;

        private Edit(Stroke drawn, ArrayList<Stroke> removed) {
            this.drawn = drawn;
            this.removed = removed;
        }

        static Edit draw(Stroke stroke) {
            return new Edit(stroke, null);
        }

        static Edit removal(ArrayList<Stroke> strokes) {
            return new Edit(null, strokes);
        }
    }

    private final ArrayList<Stroke> strokes = new ArrayList<>();
    /** Committed edits, oldest first; undo() takes the last one. */
    private final ArrayList<Edit> undoStack = new ArrayList<>();
    /** Undone edits, oldest first; redo() takes the last one. */
    private final ArrayList<Edit> redoStack = new ArrayList<>();
    private long nextStrokeSeq = 1L;

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
    /** Whole-stroke eraser (笔画橡皮) instead of the raster eraser stroke. */
    private boolean strokeEraseEnabled;
    /** In-flight whole-stroke erase gesture; null when not erasing strokes. */
    private Edit activeErase;

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

    /**
     * Switches the ERASER tool between the raster eraser (a CLEAR stroke, the
     * default) and the whole-stroke eraser (笔画橡皮). A whole-stroke erase
     * gesture removes every ink stroke it touches; the whole gesture is one
     * undoable action, like a clear.
     */
    public void setStrokeEraseEnabled(boolean enabled) {
        strokeEraseEnabled = enabled;
        if (!enabled) {
            finishActive();
        }
    }

    public boolean isStrokeEraseEnabled() {
        return strokeEraseEnabled;
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
        return !undoStack.isEmpty() || active != null
                || (activeErase != null && !activeErase.removed.isEmpty());
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        finishActive();
        if (undoStack.isEmpty()) {
            return;
        }
        final Edit edit = undoStack.remove(undoStack.size() - 1);
        if (edit.drawn != null) {
            strokes.remove(edit.drawn);
        } else {
            for (int i = 0; i < edit.removed.size(); i++) {
                insertBySeq(edit.removed.get(i));
            }
        }
        redoStack.add(edit);
        invalidate();
    }

    public void redo() {
        finishActive();
        if (redoStack.isEmpty()) {
            return;
        }
        final Edit edit = redoStack.remove(redoStack.size() - 1);
        if (edit.drawn != null) {
            insertBySeq(edit.drawn);
        } else {
            for (int i = 0; i < edit.removed.size(); i++) {
                strokes.remove(edit.removed.get(i));
            }
        }
        undoStack.add(edit);
        invalidate();
    }

    public void clear() {
        // A stroke still under the finger is a real edit; commit it first so
        // undoing the clear also brings it back.
        finishActive();
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        if (strokes.isEmpty()) {
            // Nothing to clear is not an edit; keep the redo branch intact.
            invalidate();
            return;
        }
        final ArrayList<Stroke> removed = new ArrayList<>(strokes);
        strokes.clear();
        pruneRedoFor(removed);
        undoStack.add(Edit.removal(removed));
        // A removal is undoable on its own and does not clear the whole redo
        // branch; only entries that touch the removed strokes become stale.
        invalidate();
    }

    /**
     * Strokes always stay sorted by creation order. New ink is appended, so
     * only a restored stroke needs to be slotted back in, which preserves the
     * original z-order (and the way CLEAR eraser strokes cut through ink).
     * A draw edit and a removal edit can reference the same stroke (a redo
     * branch survives removals), so never insert it twice.
     */
    private void insertBySeq(Stroke stroke) {
        for (int i = strokes.size() - 1; i >= 0; i--) {
            if (strokes.get(i) == stroke) {
                return;
            }
        }
        int index = strokes.size();
        while (index > 0 && strokes.get(index - 1).seq > stroke.seq) {
            index--;
        }
        strokes.add(index, stroke);
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
        // A stroke can be referenced by more than one edit (a redo branch
        // survives removals), so scale each object exactly once.
        final IdentityHashMap<Stroke, Boolean> scaled = new IdentityHashMap<>();
        for (int i = 0; i < strokes.size(); i++) {
            final Stroke stroke = strokes.get(i);
            scaleStroke(stroke, sx, sy);
            scaled.put(stroke, Boolean.TRUE);
        }
        for (int i = 0; i < undoStack.size(); i++) {
            scaleEdit(undoStack.get(i), sx, sy, scaled);
        }
        for (int i = 0; i < redoStack.size(); i++) {
            scaleEdit(redoStack.get(i), sx, sy, scaled);
        }
        if (activeErase != null) {
            // Hits of an in-flight whole-stroke erase are not committed yet.
            scaleEdit(activeErase, sx, sy, scaled);
        }
    }

    private static void scaleEdit(Edit edit, float sx, float sy,
                                  IdentityHashMap<Stroke, Boolean> scaled) {
        if (edit.drawn != null && !scaled.containsKey(edit.drawn)) {
            scaleStroke(edit.drawn, sx, sy);
            scaled.put(edit.drawn, Boolean.TRUE);
        }
        if (edit.removed != null) {
            for (int i = 0; i < edit.removed.size(); i++) {
                final Stroke stroke = edit.removed.get(i);
                if (!scaled.containsKey(stroke)) {
                    scaleStroke(stroke, sx, sy);
                    scaled.put(stroke, Boolean.TRUE);
                }
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
            if (strokeEraseEnabled && penStyle.eraser) {
                beginStrokeErase(event, 0);
                invalidate();
                return true;
            }
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
            if (index < 0) {
                return true;
            }
            if (activeErase != null) {
                for (int h = 0; h < event.getHistorySize(); h++) {
                    eraseStrokesAt(event.getHistoricalX(index, h),
                            event.getHistoricalY(index, h));
                }
                eraseStrokesAt(event.getX(index), event.getY(index));
                invalidate();
                return true;
            }
            if (active == null) {
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
                if (activeErase != null) {
                    eraseStrokesAt(event.getX(index), event.getY(index));
                } else {
                    appendPoint(event, index, -1);
                }
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

    /**
     * A removal must not wipe the whole redo branch, but a redo entry that
     * removes a stroke this removal just deleted has become a no-op and would
     * break the undo order if replayed. Drop just those entries (and an entry
     * that loses all of its strokes). Draw entries never overlap a removal:
     * an undone draw's stroke is not on the canvas, so nothing can remove it.
     */
    private void pruneRedoFor(ArrayList<Stroke> removed) {
        for (int e = redoStack.size() - 1; e >= 0; e--) {
            final Edit edit = redoStack.get(e);
            if (edit.removed == null) {
                continue;
            }
            for (int s = edit.removed.size() - 1; s >= 0; s--) {
                if (removed.contains(edit.removed.get(s))) {
                    edit.removed.remove(s);
                }
            }
            if (edit.removed.isEmpty()) {
                redoStack.remove(e);
            }
        }
    }

    private void beginStroke(MotionEvent event, int index) {
        final Types.PenStyle style = penStyle;
        final boolean eraser = style.eraser;
        final boolean stylus = event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS;
        active = new Stroke(nextStrokeSeq++, style, penColor, penWidth, penAlpha,
                eraser, stylus);
        active.add(event.getX(index), event.getY(index), event.getPressure(index));
        strokes.add(active);
        // A new stroke is a genuinely new edit and invalidates the redo branch.
        redoStack.clear();
    }

    private void beginStrokeErase(MotionEvent event, int index) {
        active = null;
        activeErase = Edit.removal(new ArrayList<Stroke>());
        eraseStrokesAt(event.getX(index), event.getY(index));
    }

    /**
     * Whole-stroke eraser hit test. Every ink stroke within the eraser disc is
     * removed immediately for live feedback and recorded in the in-flight
     * removal edit, so the entire drag is undone in one step.
     */
    private void eraseStrokesAt(float x, float y) {
        if (activeErase == null) {
            return;
        }
        // The raster eraser draws baseWidth * widthFactor as a diameter, and
        // FloatingService treats the panel value as a radius.
        final float eraserRadius =
                penWidth * widthFactor(Types.PenStyle.ERASER) * 0.5f;
        for (int i = strokes.size() - 1; i >= 0; i--) {
            final Stroke stroke = strokes.get(i);
            // CLEAR strokes are eraser marks, not ink; they stay in history so
            // an undone erase still cuts the ink the same way it did before.
            if (stroke.eraser) {
                continue;
            }
            final float hitRadius = eraserRadius
                    + stroke.baseWidth * widthFactor(stroke.style) * 0.5f;
            if (!strokeHit(stroke, x, y, hitRadius)) {
                continue;
            }
            strokes.remove(i);
            activeErase.removed.add(stroke);
        }
    }

    private static boolean strokeHit(Stroke stroke, float x, float y, float radius) {
        final int count = stroke.points.size();
        if (count == 0) {
            return false;
        }
        final float radiusSq = radius * radius;
        if (count == 1) {
            final float[] point = stroke.points.get(0);
            final float dx = point[0] - x;
            final float dy = point[1] - y;
            return dx * dx + dy * dy <= radiusSq;
        }
        float[] previous = stroke.points.get(0);
        for (int i = 1; i < count; i++) {
            final float[] point = stroke.points.get(i);
            final float abx = point[0] - previous[0];
            final float aby = point[1] - previous[1];
            final float lengthSq = abx * abx + aby * aby;
            float t = 0f;
            if (lengthSq > 0f) {
                t = ((x - previous[0]) * abx + (y - previous[1]) * aby) / lengthSq;
                t = Math.max(0f, Math.min(1f, t));
            }
            final float dx = x - (previous[0] + t * abx);
            final float dy = y - (previous[1] + t * aby);
            if (dx * dx + dy * dy <= radiusSq) {
                return true;
            }
            previous = point;
        }
        return false;
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
        if (active != null) {
            // A one-point stroke is a dot and stays in the list; there is
            // nothing else to commit because strokes are drawn from the list.
            undoStack.add(Edit.draw(active));
            active = null;
        }
        if (activeErase != null) {
            if (!activeErase.removed.isEmpty()) {
                pruneRedoFor(activeErase.removed);
                undoStack.add(activeErase);
            }
            activeErase = null;
        }
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
