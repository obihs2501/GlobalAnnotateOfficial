package com.globalannotate.official;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/**
 * Mi Canvas' arc (radial) pen menu, ported as a standalone, full-screen,
 * transparent view.
 *
 * <p><b>Geometry, as found in the decompiled Mi Canvas 4.1.8 sources.</b>
 *
 * <p>The official picker is a 492dp square whose arc centre is
 * {@code PointF(246, 246)}. Its band is an annulus between
 * {@code arc_pen_picker_inner_circle_radius} (160dp) and
 * {@code arc_pen_picker_outer_circle_radius} (224dp), with round caps of
 * radius {@code (224 - 160) / 2 = 32dp} centred on the
 * {@code arc_pen_picker_middle_circle_radius} (192dp) ring. These are the
 * constants in {@code lIi11II1ill.ll111i}/{@code lIi11II1ill.l1IIii} and in
 * the APK's {@code arc_pen_picker_*} dimens.
 *
 * <p>The tool arc starts at {@code arc_pen_picker_start_angle} 82 degrees and
 * ends at {@code arc_pen_picker_end_angle} -42 degrees for the right hand
 * ({@code p179I1I1iI.Iiliill.m9535l1IIii()} and {@code Iiliill()}). The four
 * button angles come from {@code Iiliill.m9522I1i1l()}, which for the right
 * hand is {@code [start, start - btnGap, end + btnGap, end]} with
 * {@code arc_pen_picker_btn_and_btn_gap = 16}: undo 82, redo 66, colour -26,
 * more -42. The controller repeats this in
 * {@code p067IIiiIiiI.lI1lill1.m3587l1lII(boolean)}.
 *
 * <p>Each brush is a 48dp by 123dp box whose <em>tip top edge</em> sits on the
 * {@code arc_pen_picker_outermost_circle_radius} (246dp) ring:
 * {@code ll111i.m28200IIiIIiii1()} builds the polar point at radius
 * {@code getMeasuredWidth() / 2 = 246dp} and then offsets the box inward by
 * half its height, so the artwork's top edge, not its centre, is anchored on
 * the 246dp ring. Unselected brushes are translated 22dp inward by
 * {@code p129Iill.II11I.m7439liiIl1i(angle, tool_element_distance)} where
 * {@code arc_pen_picker_tool_element_distance = 22dp}; selected brushes are at
 * 246dp. The {@code dispatchDraw()} clip path of {@code ll111i} keeps them
 * between 160dp and 246dp.
 *
 * <p>Official brush angles are computed by {@code ll111i.lI1lill1()}: first
 * angle 66 - 17 = 49, then gaps of 19/17/17/19 degrees (14 when both tools are
 * INK, 17 when one is, 19 otherwise), giving 49, 30, 13, -4, -23. The last of
 * those sits under the elevated white button sector in the official view. In
 * this five-brush port the fan is spread evenly at 49/34/19/4/-11 instead, so
 * that the WATER_COLOR brush remains visible and tappable; the anchor radii,
 * the 22dp pull and the 17 degree gap after REDO are unchanged. Change
 * {@link #TOOL_ANGLES} if the exact official positions are wanted.
 *
 * <p>The button sector of {@code lIi11II1ill.l1IIii} runs from -15.89 degrees
 * (colour angle + 10.11, see {@code l1IIii.Iiliill()}) to -42 degrees. It draws
 * exactly one divider line, from the 160dp inner edge to {@code 224 - 10 =
 * 214dp}, 1dp wide in {@code Color.argb(25, 0, 0, 0)}
 * ({@code m28147lIil11()}). Buttons are laid out 40dp square with 26dp icons
 * ({@code lIi11II1ill.lIIIlll.m28168I11lii()}); the colour wheel overrides its
 * own measurement to 44dp ({@code color_button_uni_size} in
 * {@code p016I1IllI.lIIIlll}). All of them sit on the 192dp ring. The colour
 * wheel is enabled only while an INK tool with a colour is selected
 * ({@code ll111i.m28204I1llI1()}).
 *
 * <p><b>Layout in this port.</b> The official square is placed with its right
 * edge against the screen edge, so the arc centre is 246dp inside the right
 * edge and the band hugs that edge. The official logical angle theta (0 = up,
 * positive = towards the left) is rotated ninety degrees clockwise onto this
 * screen: a point at radius r and logical angle theta is at
 * {@code (cx + r cos(theta), cy - r sin(theta))}. Theta therefore decreases
 * from the undo button at the top to the more button at the bottom. The pivot
 * for {@link #setOpenProgress(float)} is that arc centre, which is also the
 * official square's centre and the default pivot of the official scale
 * animation.
 *
 * <p><b>Drawing order.</b> Band, five brushes, divider, then the four buttons.
 * Mi Canvas marks the button container with elevation 10, which puts its white
 * sector, divider and controls above the brushes; this port keeps those on top
 * but leaves the band itself as the only background, so all five brushes stay
 * visible. The official blurred shadow (a {@code BlurMaskFilter} bitmap in
 * {@code lIi11II1ill.II11lli}) and the elevation shadow are not reproduced:
 * they need a software layer, and the PRD does not require them. Icons are
 * drawn as paths because this file may not use resources or XML.
 *
 * <p>All methods must be called on the UI thread. The view is transparent
 * outside the band and expects to be added match-parent by the host; it never
 * changes its own visibility except through {@link #show()} and
 * {@link #hide()}.
 */
public final class RadialPalette extends View {

    /**
     * Host callbacks. All are invoked on the UI thread. The palette reports a
     * brush tap through {@link #onTool}; the host owns the tool state and is
     * expected to call {@link #setSelected} when the active style changes.
     */
    public interface Listener {
        void onTool(Types.PenStyle style);

        void onUndo();

        void onRedo();

        void onColor();

        void onMore();

        void onDismiss();
    }

    // ----official geometry (dp)

    /** arc_pen_picker_inner_circle_radius. */
    private static final float ARC_INNER_DP = 160f;
    /** arc_pen_picker_middle_circle_radius: the button / cap-centre ring. */
    private static final float ARC_MIDDLE_DP = 192f;
    /** arc_pen_picker_outer_circle_radius: the band's outer edge. */
    private static final float ARC_OUTER_DP = 224f;
    /** arc_pen_picker_outermost_circle_radius: the brush tip ring and clip. */
    private static final float ARC_OUTERMOST_DP = 246f;
    /** (outer - inner) / 2, the round cap radius of the band. */
    private static final float BAND_CAP_DP = 32f;
    /** arc_pen_picker_tool_element_distance: unselected pull-in. */
    private static final float TOOL_INSET_DP = 22f;
    /** cp_brush_width_normal / the PKToolView width. */
    private static final float TOOL_WIDTH_DP = 48f;
    /** The brush artwork's box height; the tip is at its top centre. */
    private static final float TOOL_HEIGHT_DP = 123f;
    /** lIi11II1ill.lIIIlll.m28168I11lii() button box. */
    private static final float BUTTON_BOX_DP = 40f;
    /** Button icon size (setImageSize(26dp)). */
    private static final float BUTTON_ICON_DP = 26f;
    /** color_button_uni_size: the colour wheel diameter. */
    private static final float COLOR_WHEEL_DP = 44f;
    /** m28147lIil11() divider: inner edge to outer - 10dp. */
    private static final float DIVIDER_INNER_DP = 160f;
    private static final float DIVIDER_OUTER_DP = 214f;
    private static final float DIVIDER_WIDTH_DP = 1f;

    // ----angles (logical degrees)

    private static final float START_ANGLE = 82f;
    private static final float END_ANGLE = -42f;
    private static final float UNDO_ANGLE = 82f;
    private static final float REDO_ANGLE = 66f;
    private static final float COLOR_ANGLE = -26f;
    private static final float MORE_ANGLE = -42f;
    /** l1IIii.Iiliill(): colour angle + 10.11. */
    private static final float DIVIDER_ANGLE = -15.89f;

    /**
     * Tool order is required by the PRD: Types.PenStyle.TOOLBAR without ERASER.
     * Angles are spread evenly from the official first angle (49) to keep the
     * fifth brush clear of the colour wheel at -26 and the divider at -15.89.
     */
    private static final Types.PenStyle[] TOOLS = {
            Types.PenStyle.BALLPOINT,
            Types.PenStyle.PENCIL,
            Types.PenStyle.INK_PEN,
            Types.PenStyle.MARKER,
            Types.PenStyle.WATER_COLOR,
    };
    private static final float[] TOOL_ANGLES = {49f, 34f, 19f, 4f, -11f};

    // ----colours

    /** penpicker_background_color (day). */
    private static final int BAND_COLOR = 0xFFFFFFFF;
    /** m28147lIil11(): Color.argb(25, 0, 0, 0). */
    private static final int DIVIDER_COLOR = 0x19000000;
    /** The picker's icon colour; the shipped vectors use fillAlpha 0.8. */
    private static final int ICON_COLOR = 0xCC000000;
    private static final int BUTTON_HOVER_COLOR = 0x12000000;
    private static final int BUTTON_PRESS_COLOR = 0x1F000000;
    /** p016I1IllI.lIil11.setEnabled(): disabled icon alpha 76. */
    private static final int DISABLED_ALPHA = 76;
    private static final int DEFAULT_TOOL_COLOR = 0xFF000000;

    /** penpicker_palette_sweep_1 .. _8, and the first colour repeated at 1.0. */
    private static final int[] WHEEL_SWEEP_COLORS = {
            0xFFE7E040, 0xFFFFAA3C, 0xFFE8403B, 0xFFB33ED5,
            0xFF694AE8, 0xFF3CCAE7, 0xFF3CE885, 0xFF89E743,
            0xFFE7E040,
    };
    private static final float[] WHEEL_SWEEP_POSITIONS = {
            0f, 0.124f, 0.249f, 0.365f, 0.506f, 0.642f, 0.748f, 0.869f, 1f,
    };
    /** penpicker_palette_radial_center / _edge. */
    private static final int WHEEL_CENTER_COLOR = 0xFFE4E4E4;
    private static final int WHEEL_EDGE_COLOR = 0x00E4E4E4;

    // ----hit targets

    private static final int ACTION_NONE = 0;
    private static final int ACTION_DISMISS = 1;
    private static final int ACTION_BRUSH = 2;
    private static final int ACTION_UNDO = 3;
    private static final int ACTION_REDO = 4;
    private static final int ACTION_COLOR = 5;
    private static final int ACTION_MORE = 6;

    // ----state

    private final Listener listener;
    private final float density;

    /** Per-tool colours, indexed by PenStyle.ordinal(). */
    private final int[] toolColors = new int[Types.PenStyle.values().length];

    private final BrushDrawable[] toolDrawables = new BrushDrawable[TOOLS.length];
    private final int[] toolDrawableColors = new int[TOOLS.length];

    private Types.PenStyle selectedStyle;
    private Types.PenStyle hoveredStyle;
    private Types.PenStyle pressedTool;

    private int hoveredButton = ACTION_NONE;
    private int pressedButton = ACTION_NONE;

    private float openProgress = 1f;

    // ----drawing

    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonStatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path bandPath = new Path();
    private final Path brushClipPath = new Path();
    private final Path undoArrowHead = buildUndoArrowHead();
    private final Path undoArrowTail = buildUndoArrowTail();

    private final Shader colorWheelShader;

    private float cx;
    private float cy;
    private boolean geometryDirty = true;

    // ----hit-test scratch

    private int hitKind = ACTION_NONE;
    private Types.PenStyle hitStyle;

    // ----construction

    public RadialPalette(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;

        for (int i = 0; i < toolColors.length; i++) {
            toolColors[i] = DEFAULT_TOOL_COLOR;
        }
        for (int i = 0; i < toolDrawableColors.length; i++) {
            toolDrawableColors[i] = Integer.MIN_VALUE;
        }

        bandPaint.setStyle(Paint.Style.FILL);
        bandPaint.setColor(BAND_COLOR);

        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(dp(DIVIDER_WIDTH_DP));
        dividerPaint.setStrokeCap(Paint.Cap.ROUND);
        dividerPaint.setColor(DIVIDER_COLOR);

        glyphFillPaint.setStyle(Paint.Style.FILL);
        glyphFillPaint.setColor(ICON_COLOR);
        glyphStrokePaint.setStyle(Paint.Style.STROKE);
        glyphStrokePaint.setStrokeWidth(2.4f);
        glyphStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        glyphStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        glyphStrokePaint.setColor(ICON_COLOR);

        buttonStatePaint.setStyle(Paint.Style.FILL);

        wheelPaint.setStyle(Paint.Style.FILL);
        wheelPaint.setAntiAlias(true);

        colorWheelShader = buildColorWheelShader();

        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        // The host owns the opening animation; start hidden like the other
        // transient picker views (MoreMenuView, BrushPanel).
        setVisibility(GONE);
    }

    // ----public API

    /**
     * Supplies each brush's remembered colour. The array is indexed by
     * {@link Types.PenStyle#ordinal()}; entries for tools outside the arc are
     * ignored and missing entries keep their previous value (black initially).
     * The colour tints the BrushDrawable head layer, exactly like the official
     * PKToolView.
     */
    public void setColors(int[] perTool) {
        if (perTool == null) {
            return;
        }
        boolean changed = false;
        final int count = Math.min(perTool.length, toolColors.length);
        for (int i = 0; i < count; i++) {
            if (toolColors[i] != perTool[i]) {
                toolColors[i] = perTool[i];
                changed = true;
            }
        }
        if (changed) {
            invalidate();
        }
    }

    /** Marks the active brush. Pass null (or a tool outside the arc) to clear. */
    public void setSelected(Types.PenStyle style) {
        if (selectedStyle != style) {
            selectedStyle = style;
            invalidate();
        }
    }

    /** Makes the palette visible. It does not change {@link #setOpenProgress}. */
    public void show() {
        clearInteractionState();
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        invalidate();
    }

    /** Hides the palette immediately; the host may animate out first. */
    public void hide() {
        clearInteractionState();
        if (getVisibility() != GONE) {
            setVisibility(GONE);
        }
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /**
     * The host's open animation hook. 0 draws nothing; 1 is fully open. The
     * official animation scales the 492dp wrapper from 0.5 to 1 and fades it,
     * with the pivot at the arc centre: that is exactly what this method does,
     * with the content alpha multiplied by the same value.
     */
    public void setOpenProgress(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (openProgress != clamped) {
            openProgress = clamped;
            if (clamped <= 0f) {
                clearInteractionState();
            }
            invalidate();
        }
    }

    // ----view plumbing

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        geometryDirty = true;
    }

    private void ensureGeometry() {
        if (!geometryDirty) {
            return;
        }
        geometryDirty = false;

        // The official square is clamped against the available rect, so its
        // right edge lies on the screen edge and the arc centre is 246dp in.
        cx = getWidth() - dp(ARC_OUTERMOST_DP);
        cy = getHeight() / 2f;

        buildBandPath(bandPath);
        brushClipPath.reset();
        brushClipPath.setFillType(Path.FillType.EVEN_ODD);
        brushClipPath.addCircle(cx, cy, dp(ARC_OUTERMOST_DP), Path.Direction.CW);
        brushClipPath.addCircle(cx, cy, dp(ARC_INNER_DP), Path.Direction.CW);
    }

    /**
     * The annular sector plus its two round caps, exactly the construction in
     * {@code lIi11II1ill.II11lli.lIIIlll(Il1illl)}: centre, outer arc, back
     * along the inner arc, then union circles of radius (outer-inner)/2 at the
     * middle ring. The caps are drawn as separate circles by the caller.
     */
    private void buildBandPath(Path path) {
        path.reset();
        RectF outer = new RectF(
                cx - dp(ARC_OUTER_DP), cy - dp(ARC_OUTER_DP),
                cx + dp(ARC_OUTER_DP), cy + dp(ARC_OUTER_DP));
        RectF inner = new RectF(
                cx - dp(ARC_INNER_DP), cy - dp(ARC_INNER_DP),
                cx + dp(ARC_INNER_DP), cy + dp(ARC_INNER_DP));

        // Screen/Canvas angle is -theta in the rotated frame.
        float aStart = -START_ANGLE;
        float aEnd = -END_ANGLE;
        float sweep = aEnd - aStart;

        path.moveTo(cx, cy);
        path.addArc(outer, aStart, sweep);
        path.arcTo(inner, aEnd, -sweep);
        path.close();
    }

    // ----drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (openProgress <= 0f || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        ensureGeometry();

        final float scale = 0.5f + 0.5f * openProgress;
        final int save = canvas.save();
        canvas.scale(scale, scale, cx, cy);

        drawBand(canvas);
        drawBrushes(canvas);
        drawDivider(canvas);
        drawButton(canvas, ACTION_UNDO, UNDO_ANGLE);
        drawButton(canvas, ACTION_REDO, REDO_ANGLE);
        drawButton(canvas, ACTION_COLOR, COLOR_ANGLE);
        drawButton(canvas, ACTION_MORE, MORE_ANGLE);

        canvas.restoreToCount(save);
    }

    private void drawBand(Canvas canvas) {
        bandPaint.setColor(BAND_COLOR);
        bandPaint.setAlpha(Math.round(255f * openProgress));
        canvas.drawPath(bandPath, bandPaint);
        canvas.drawCircle(screenX(ARC_MIDDLE_DP, START_ANGLE),
                screenY(ARC_MIDDLE_DP, START_ANGLE), dp(BAND_CAP_DP), bandPaint);
        canvas.drawCircle(screenX(ARC_MIDDLE_DP, END_ANGLE),
                screenY(ARC_MIDDLE_DP, END_ANGLE), dp(BAND_CAP_DP), bandPaint);
    }

    private void drawDivider(Canvas canvas) {
        dividerPaint.setColor(DIVIDER_COLOR);
        dividerPaint.setAlpha(Math.round(25f * openProgress));
        float x1 = screenX(DIVIDER_INNER_DP, DIVIDER_ANGLE);
        float y1 = screenY(DIVIDER_INNER_DP, DIVIDER_ANGLE);
        float x2 = screenX(DIVIDER_OUTER_DP, DIVIDER_ANGLE);
        float y2 = screenY(DIVIDER_OUTER_DP, DIVIDER_ANGLE);
        canvas.drawLine(x1, y1, x2, y2, dividerPaint);
    }

    private void drawBrushes(Canvas canvas) {
        ensureToolDrawables();
        final int alpha = Math.round(255f * openProgress);

        final int clipSave = canvas.save();
        canvas.clipPath(brushClipPath);

        for (int i = 0; i < TOOLS.length; i++) {
            final Types.PenStyle style = TOOLS[i];
            final BrushDrawable drawable = toolDrawables[i];
            if (drawable == null) {
                continue;
            }
            final boolean active = (style == selectedStyle)
                    || (style == hoveredStyle)
                    || (style == pressedTool);
            final float tipRadius = active
                    ? ARC_OUTERMOST_DP : ARC_OUTERMOST_DP - TOOL_INSET_DP;
            final float tipX = screenX(tipRadius, TOOL_ANGLES[i]);
            final float tipY = screenY(tipRadius, TOOL_ANGLES[i]);

            drawable.setAlpha(alpha);

            final int save = canvas.save();
            canvas.translate(tipX, tipY);
            // Local +y points inward towards the arc centre; the artwork's
            // tip (top centre of its box) is therefore on the given ring.
            canvas.rotate(90f - TOOL_ANGLES[i]);
            drawable.setBounds(
                    Math.round(-dp(TOOL_WIDTH_DP) / 2f),
                    0,
                    Math.round(dp(TOOL_WIDTH_DP) / 2f),
                    Math.round(dp(TOOL_HEIGHT_DP)));
            drawable.draw(canvas);
            canvas.restoreToCount(save);
        }

        canvas.restoreToCount(clipSave);
    }

    private void drawButton(Canvas canvas, int action, float angle) {
        final float bx = screenX(ARC_MIDDLE_DP, angle);
        final float by = screenY(ARC_MIDDLE_DP, angle);

        if (action != ACTION_COLOR
                && (hoveredButton == action || pressedButton == action)) {
            buttonStatePaint.setColor(pressedButton == action
                    ? BUTTON_PRESS_COLOR : BUTTON_HOVER_COLOR);
            buttonStatePaint.setAlpha(Math.round(
                    ((pressedButton == action ? 0x1F : 0x12) * openProgress)));
            canvas.drawCircle(bx, by, dp(BUTTON_BOX_DP / 2f), buttonStatePaint);
        }

        if (action == ACTION_COLOR) {
            drawColorWheel(canvas, bx, by, angle);
            return;
        }

        final int save = canvas.save();
        canvas.translate(bx, by);
        canvas.rotate(90f - angle);
        canvas.scale(density, density);

        final int iconAlpha = Math.round(0xCC * openProgress);
        glyphFillPaint.setAlpha(iconAlpha);
        glyphStrokePaint.setAlpha(iconAlpha);

        if (action == ACTION_UNDO || action == ACTION_REDO) {
            if (action == ACTION_REDO) {
                canvas.scale(-1f, 1f);
            }
            canvas.drawPath(undoArrowTail, glyphStrokePaint);
            canvas.drawPath(undoArrowHead, glyphFillPaint);
        } else {
            canvas.drawCircle(0f, -6.1f, 1.8f, glyphFillPaint);
            canvas.drawCircle(0f, 0f, 1.8f, glyphFillPaint);
            canvas.drawCircle(0f, 6.1f, 1.8f, glyphFillPaint);
        }

        canvas.restoreToCount(save);
    }

    private void drawColorWheel(Canvas canvas, float bx, float by, float angle) {
        final boolean enabled = selectedStyle == Types.PenStyle.INK_PEN;
        final int baseAlpha = enabled ? 255 : DISABLED_ALPHA;

        wheelPaint.setShader(colorWheelShader);
        wheelPaint.setAlpha(Math.round(baseAlpha * openProgress));

        final int save = canvas.save();
        canvas.translate(bx, by);
        // Rotate the wheel with the arc, as the official button container
        // rotates its children (lIIIlll.m28166I1i1l).
        canvas.rotate(90f - angle);
        canvas.drawCircle(0f, 0f, dp(COLOR_WHEEL_DP / 2f), wheelPaint);
        canvas.restoreToCount(save);

        wheelPaint.setShader(null);
    }

    // ----tool drawables

    private void ensureToolDrawables() {
        final Context context = getContext();
        for (int i = 0; i < TOOLS.length; i++) {
            final Types.PenStyle style = TOOLS[i];
            final int color = toolColors[style.ordinal()];
            if (toolDrawables[i] == null || toolDrawableColors[i] != color) {
                // ballArt=false: BrushDrawable only FIT_CENTERs the shipped
                // artwork inside the bounds. The official arc gives it exactly
                // the 48x123dp PKToolView box, not the 96dp ball box.
                toolDrawables[i] = new BrushDrawable(context, style, color, false);
                toolDrawableColors[i] = color;
            }
        }
    }

    /** sweep colours composed with the official light radial centre. */
    private Shader buildColorWheelShader() {
        SweepGradient sweep = new SweepGradient(0f, 0f,
                WHEEL_SWEEP_COLORS, WHEEL_SWEEP_POSITIONS);
        Matrix rotate = new Matrix();
        rotate.setRotate(-90f, 0f, 0f);
        sweep.setLocalMatrix(rotate);
        RadialGradient radial = new RadialGradient(0f, 0f,
                dp(COLOR_WHEEL_DP / 2f),
                WHEEL_CENTER_COLOR, WHEEL_EDGE_COLOR, Shader.TileMode.CLAMP);
        return new ComposeShader(sweep, radial, PorterDuff.Mode.SRC_OVER);
    }

    // ----icons (paths in dp)

    private static Path buildUndoArrowHead() {
        Path path = new Path();
        path.moveTo(-9.4f, -1.6f);
        path.lineTo(-3.7f, -7.2f);
        path.lineTo(-3.7f, 4.0f);
        path.close();
        return path;
    }

    private static Path buildUndoArrowTail() {
        Path path = new Path();
        path.moveTo(-5.2f, -1.6f);
        path.lineTo(1.2f, -1.6f);
        path.cubicTo(5.2f, -1.6f, 7.4f, 1.0f, 7.4f, 4.8f);
        path.lineTo(7.4f, 8.8f);
        return path;
    }

    // ----touch and hover

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float x = event.getX();
        final float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                hitTest(x, y);
                pressedButton = isButton(hitKind) ? hitKind : ACTION_NONE;
                pressedTool = hitKind == ACTION_BRUSH ? hitStyle : null;
                hoveredButton = ACTION_NONE;
                hoveredStyle = null;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressedButton == ACTION_NONE && pressedTool == null) {
                    return true;
                }
                hitTest(x, y);
                final int newPressedButton = isButton(hitKind) ? hitKind : ACTION_NONE;
                final Types.PenStyle newPressedTool =
                        hitKind == ACTION_BRUSH ? hitStyle : null;
                if (newPressedButton != pressedButton || newPressedTool != pressedTool) {
                    pressedButton = newPressedButton;
                    pressedTool = newPressedTool;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                hitTest(x, y);
                final int upKind = hitKind;
                final Types.PenStyle upStyle = hitStyle;
                final int downButton = pressedButton;
                final Types.PenStyle downTool = pressedTool;
                clearInteractionState();
                invalidate();

                // A tap only acts if it ends on the target it started on. A
                // tap outside the band dismisses; a drag out of a button just
                // cancels, matching the official pressed behaviour.
                if (downButton == ACTION_NONE && downTool == null) {
                    if (upKind == ACTION_DISMISS) {
                        dispatchAction(ACTION_DISMISS, null);
                    }
                    return true;
                }
                if (upKind == ACTION_BRUSH && upStyle == downTool) {
                    dispatchAction(ACTION_BRUSH, upStyle);
                } else if (upKind == downButton && downButton != ACTION_NONE) {
                    dispatchAction(upKind, null);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                clearInteractionState();
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: {
                hitTest(event.getX(), event.getY());
                final Types.PenStyle newHoveredTool =
                        hitKind == ACTION_BRUSH ? hitStyle : null;
                final int newHoveredButton = isButton(hitKind) ? hitKind : ACTION_NONE;
                if (newHoveredTool != hoveredStyle || newHoveredButton != hoveredButton) {
                    hoveredStyle = newHoveredTool;
                    hoveredButton = newHoveredButton;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_HOVER_EXIT: {
                if (hoveredStyle != null || hoveredButton != ACTION_NONE) {
                    hoveredStyle = null;
                    hoveredButton = ACTION_NONE;
                    invalidate();
                }
                return true;
            }
            default:
                return super.onHoverEvent(event);
        }
    }

    private void dispatchAction(int action, Types.PenStyle style) {
        if (listener == null) {
            return;
        }
        switch (action) {
            case ACTION_BRUSH:
                if (style != null) {
                    listener.onTool(style);
                }
                break;
            case ACTION_UNDO:
                listener.onUndo();
                break;
            case ACTION_REDO:
                listener.onRedo();
                break;
            case ACTION_COLOR:
                // The official colour tool is clickable only for INK with a
                // colour (ll111i.m28204I1llI1()).
                if (selectedStyle == Types.PenStyle.INK_PEN) {
                    listener.onColor();
                }
                break;
            case ACTION_MORE:
                listener.onMore();
                break;
            case ACTION_DISMISS:
                listener.onDismiss();
                break;
            default:
                break;
        }
    }

    private void clearInteractionState() {
        hoveredStyle = null;
        pressedTool = null;
        hoveredButton = ACTION_NONE;
        pressedButton = ACTION_NONE;
        hitKind = ACTION_NONE;
        hitStyle = null;
    }

    private static boolean isButton(int kind) {
        return kind == ACTION_UNDO || kind == ACTION_REDO
                || kind == ACTION_COLOR || kind == ACTION_MORE;
    }

    /**
     * Sets {@link #hitKind} and {@link #hitStyle}. Buttons are tested first by
     * their real 2D distance on the 192dp ring; the rest of the band then uses
     * angular nearest-neighbour, as the PRD requires. Everything outside the
     * 160..246dp annulus between -42 and 82 degrees dismisses; the button
     * sector below the divider is inert like the official container.
     */
    private void hitTest(float x, float y) {
        hitKind = ACTION_DISMISS;
        hitStyle = null;

        if (openProgress <= 0f || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        ensureGeometry();

        // Undo the drawing scale so hit testing uses the same geometry the
        // user sees while the host animates the palette open.
        final float scale = 0.5f + 0.5f * openProgress;
        final float px = cx + (x - cx) / scale;
        final float py = cy + (y - cy) / scale;

        if (withinButton(px, py, UNDO_ANGLE, dp(BUTTON_BOX_DP / 2f))) {
            hitKind = ACTION_UNDO;
            return;
        }
        if (withinButton(px, py, REDO_ANGLE, dp(BUTTON_BOX_DP / 2f))) {
            hitKind = ACTION_REDO;
            return;
        }
        if (withinButton(px, py, COLOR_ANGLE, dp(COLOR_WHEEL_DP / 2f))) {
            hitKind = ACTION_COLOR;
            return;
        }
        if (withinButton(px, py, MORE_ANGLE, dp(BUTTON_BOX_DP / 2f))) {
            hitKind = ACTION_MORE;
            return;
        }

        final float dx = px - cx;
        final float dy = py - cy;
        final float radius = (float) Math.hypot(dx, dy);
        if (radius < dp(ARC_INNER_DP) || radius > dp(ARC_OUTERMOST_DP)) {
            return;
        }

        float theta = (float) -Math.toDegrees(Math.atan2(dy, dx));
        theta = normalizeAngle(theta);
        if (theta > START_ANGLE || theta < END_ANGLE) {
            return;
        }
        if (theta < DIVIDER_ANGLE) {
            // Inside the official elevated button sector: no brush there.
            hitKind = ACTION_NONE;
            return;
        }

        float bestDistance = Float.MAX_VALUE;
        Types.PenStyle bestStyle = null;
        for (int i = 0; i < TOOLS.length; i++) {
            final float distance = Math.abs(theta - TOOL_ANGLES[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestStyle = TOOLS[i];
            }
        }
        if (bestStyle != null) {
            hitKind = ACTION_BRUSH;
            hitStyle = bestStyle;
        }
    }

    private boolean withinButton(float x, float y, float angle, float radius) {
        final float bx = screenX(ARC_MIDDLE_DP, angle);
        final float by = screenY(ARC_MIDDLE_DP, angle);
        final float dx = x - bx;
        final float dy = y - by;
        return dx * dx + dy * dy <= radius * radius;
    }

    // ----math helpers

    private float screenX(float radiusDp, float angleDegrees) {
        return cx + dp(radiusDp) * (float) Math.cos(Math.toRadians(angleDegrees));
    }

    private float screenY(float radiusDp, float angleDegrees) {
        return cy - dp(radiusDp) * (float) Math.sin(Math.toRadians(angleDegrees));
    }

    private float dp(float value) {
        return value * density;
    }

    private static float normalizeAngle(float angle) {
        float a = angle % 360f;
        if (a > 180f) {
            a -= 360f;
        } else if (a < -180f) {
            a += 360f;
        }
        return a;
    }
}
