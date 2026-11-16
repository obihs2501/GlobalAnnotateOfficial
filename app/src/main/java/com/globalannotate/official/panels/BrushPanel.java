package com.globalannotate.official.panels;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.globalannotate.official.R;

/**
 * Standalone Java port of the old Kotlin {@code widget/BrushPanel.kt}
 * (Mi Canvas {@code penpicker_popup_layout_pen_set}). The old panel inflated
 * two XML rows and swapped Mi Canvas' {@code SelectPropertyView} in at runtime;
 * this port keeps the same measured geometry but draws its own 28dp rails.
 *
 * <p>Order is the old {@code build()} order: size, sensitivity, tip, alpha,
 * under the 110dp live preview. Unlike the old panel the sensitivity and tip
 * rows are always present (the service used to hide them for tools without
 * those properties), because the required {@code setBrush} signature always
 * carries all four values.</p>
 *
 * <p>Constant map (old source:line -> value):</p>
 * <pre>
 * PANEL_WIDTH_DP 288 ................ AnnotationService.kt:1371 (dp(288));
 *                                     penpicker_dimens.xml (penpicker_pen_set_popup_window_width)
 * PANEL_RADIUS_DP 36 ................ penpicker_dimens.xml (penpicker_color_picker_background_radius)
 * panelBg 0xFFFFFFFF ............... penpicker_colors.xml (penpicker_background_color)
 * PREVIEW_HEIGHT_DP 110 ............. layout/penpicker_popup_layout_pen_set.xml:12-13;
 *                                     penpicker_dimens.xml (pk_pen_set_preview_image_height)
 * CONTENT_PAD_H_DP 20 ............... layout/penpicker_popup_layout_pen_set.xml:20
 * CONTENT_PAD_BOTTOM_DP 16 .......... layout/penpicker_popup_layout_pen_set.xml:16
 * ROW_SLIDER_HEIGHT_DP 28 ........... layout/penpicker_layout_pen_property_item.xml:28-29
 * ROW_SLIDER_MARGIN_TOP_DP 8 ........ layout/penpicker_layout_pen_property_item.xml:31
 * ROW_TEXT_SP 14 .................... penpicker_styles.xml (penpicker_pen_set_size)
 * rowTextColor 0xCC000000 ......... penpicker_colors.xml (penpicker_pen_set_text)
 * TOOL RANGES ....................... PenStyle.kt:43-51; PENCIL max 22 at :44-46 is the
 *                                     user's deliberate deviation from Mi Canvas' 6
 * DEFAULT TOOL INK_PEN .............. BrushConfig.kt:10
 * DEFAULT_WIDTH 15 .................. PenStyle.kt:47 (INK_PEN default size)
 * DEFAULT_ALPHA 255 ................. PenStyle.kt:47 (INK_PEN default alpha)
 * DEFAULT_SENSITIVITY 0.5 ........... PenStyle.kt:87-88
 * DEFAULT_TIP 2 ..................... BrushConfig.kt:16
 * MIN_ALPHA 3 ....................... PenStyle.kt:90-91; ColorPanel.kt:218
 * MAX_TIP 4 ......................... BrushPanel.kt:165-169
 * ROW TITLES / ORDER ................ BrushPanel.kt:149-175
 * SIZE LABEL TRUNCATES .............. BrushPanel.kt:147
 * ALPHA PERCENT alpha*100/255 ....... BrushPanel.kt:172-174
 * TRACK_HEIGHT_DP 28 ................ pk_seekbar_height (penpicker dimen)
 * THUMB_STROKE_DP 3 ................. I1llI1.lIIIlll (3dp * thumb_scale, = 1.0)
 * THUMB ring radius ................. I1llI1.onDraw: height/2 - stroke/2
 * THUMB travel ...................... I1llI1.onDraw: width - height (margin = 0dp,
 *                                     pk_seekbar_thumb_margin)
 * ALPHA background .................. I1llI1: LayerDrawable {pk_alpha_bg,
 *                                     GradientDrawable {0x00FFFFFF, pen color},
 *                                     corner radius penpicker_seekbar_thumb_radius/2}
 * trackBg 0x0F000000 ............... penpicker_colors.xml (pk_property_seekbar_bg;
 *                                     SelectPropertyView.setupBackground)
 * ACCENT 0xFF3482FF ................. SelectPropertyView.lIIIlll ring paint
 * PREVIEW_SAMPLES 48 ................ StrokePreview.kt:45
 * PREVIEW_MARGIN_FACTOR 0.6 ......... StrokePreview.kt:54-57
 * PREVIEW_MARGIN_EXTRA_DP 2 ......... StrokePreview.kt:54
 * PREVIEW_AMP 20% / MIN 2dp ......... StrokePreview.kt:58-59
 * PREVIEW_PRESSURE 0.35 + 0.65 ...... StrokePreview.kt:64-66
 * ERASER_PREVIEW 0xFFD0D0D0 ......... StrokePreview.kt:42
 * DEFAULT_COLOR 0xFF40A9FF .......... BrushConfig.kt:12
 * TIP ROW ........................... a 1..4 rail, like the old TIP slider
 *                                     (BrushPanel.kt:165-169); the value snaps to
 *                                     whole nib sizes.
 * SHOW/HIDE animation ............... AnnotationService.kt:1680-1700, 1716-1730
 * PLACEMENT gaps .................... AnnotationService.kt:1656-1679
 * CLOSE_* ........................... invented close control for Listener.onDismiss()
 * </pre>
 */
public final class BrushPanel extends LinearLayout {

    /**
     * Mi Canvas brush set; minimums and defaults from PenStyle.kt:43-51. Pens
     * cap at the user's 25-unit ceiling (official pencil max is 6, ink 30);
     * the marker is allowed 45 because a broad highlighter-like band is still
     * useful. Water-colour stays at 25.
     */
    public enum Tool {
        BALLPOINT(1, 25, 6, "压力灵敏度", false, 255),
        PENCIL(1, 25, 3, "倾斜角灵敏度", false, 150),
        INK_PEN(1, 25, 15, "压力灵敏度", false, 255),
        MARKER(5, 45, 25, "压力灵敏度", false, 110),
        WATER_COLOR(5, 25, 25, "压力灵敏度", false, 72),
        // Official penpicker pixel eraser range is 4..32 (Mi Canvas'
        // SelectAlphaView.setMin(4)/setMax(32)); the value is a radius.
        // Pixel eraser uses the same pressure rail as the drawing pens. The
        // value controls how strongly stylus pressure changes the erase radius.
        ERASER(4, 32, 6, "压感触发", true, 255);

        public final int minSize;
        public final int maxSize;
        public final int defaultSize;
        public final String sensitivityTitle;
        public final boolean eraser;
        /**
         * Style opacity ceiling, kept in sync with AnnotationView's private
         * alphaFactor table so the sample does not look more opaque than the
         * stroke it previews.
         */
        public final int previewAlpha;

        Tool(int minSize, int maxSize, int defaultSize, String sensitivityTitle,
             boolean eraser, int previewAlpha) {
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.defaultSize = defaultSize;
            this.sensitivityTitle = sensitivityTitle;
            this.eraser = eraser;
            this.previewAlpha = previewAlpha;
        }
    }

    /** Callbacks the old panel exposed as {@code onParam(kind, value)}. */
    public interface Listener {
        /**
         * One value changed. {@code width} is in Mi Canvas pen units,
         * {@code alpha} is the old raw 3..255 value, {@code sensitivity} is
         * 0..1 and {@code tip} is 1..4.
         */
        void onBrushChanged(float width, float alpha, float sensitivity, int tip);

        /**
         * The eraser mode tab changed: {@code false} is the pixel eraser, the
         * default, and {@code true} is the whole-stroke eraser.
         */
        void onEraserModeChanged(boolean wholeStroke);

        /** The rows above changed visibility, so the panel height changed. */
        void onContentHeightChanged();

        /** The user asked to close the panel (the close button). */
        void onDismiss();
    }

    // Panel box, from AnnotationService.showPanel(brushPanel, anchorX, 288, 0).
    private static final float PANEL_WIDTH_DP = 288f;
    private static final float PANEL_RADIUS_DP = 36f;
    private final int panelBg;

    // Preview and property rows.
    private static final int PREVIEW_HEIGHT_DP = 110;
    private static final int CONTENT_PAD_H_DP = 20;
    private static final int CONTENT_PAD_BOTTOM_DP = 16;
    private static final int ROW_SLIDER_HEIGHT_DP = 28;
    private static final int ROW_SLIDER_MARGIN_TOP_DP = 8;
    private static final float ROW_TEXT_SP = 14f;
    private final int rowTextColor;

    // Defaults, from BrushConfig/PenStyle.
    private static final int DEFAULT_COLOR = 0xFF40A9FF;
    private static final int DEFAULT_ALPHA = 255;
    private static final float DEFAULT_SENSITIVITY = 0.5f;
    private static final int DEFAULT_TIP = 2;
    private static final int MIN_ALPHA = 3;
    private static final int MAX_ALPHA = 255;
    private static final int MAX_TIP = 4;

    /**
     * Rail geometry, from the official penpicker SeekBar (p249l1lII1Il.I1llI1
     * and com.xiaomi.penpicker.popup.SelectPropertyView):
     * <ul>
     *   <li>the track is the full view height ({@code pk_seekbar_height} 28dp);</li>
     *   <li>the thumb is a bare ring of {@code height/2 - stroke/2} with a 3dp
     *       stroke, no fill and no border;</li>
     *   <li>at 0/100% the ring's outer edge is flush with the track ends
     *       ({@code pk_seekbar_thumb_margin} is 0dp, so travel = width - height);</li>
     *   <li>the alpha track is {@code pk_alpha_bg}'s translucent checker with a
     *       {@code 0x00FFFFFF -> pen colour} gradient on top;</li>
     *   <li>the property track is a solid {@code pk_property_seekbar_bg} capsule.</li>
     * </ul>
     */
    private static final float THUMB_STROKE_DP = 3f;
    private final int trackBg;
    private static final int ACCENT = 0xFF3482FF;

    // Live preview, from StrokePreview.
    private static final int PREVIEW_SAMPLES = 48;
    private static final float PREVIEW_MARGIN_FACTOR = 0.6f;
    private static final float PREVIEW_MARGIN_EXTRA_DP = 2f;
    private static final float PREVIEW_AMP_FACTOR = 0.20f;
    private static final float PREVIEW_MIN_AMP_DP = 2f;
    private static final float PREVIEW_PRESSURE_MIN = 0.35f;
    private static final float PREVIEW_PRESSURE_RANGE = 0.65f;
    private static final int ERASER_PREVIEW = 0xFFD0D0D0;

    // Tip selector, an invented stand-in for the old 1..4 TIP slider.

    // Open/close animation, from AnnotationService.showPanel/closePanels.
    private static final long SHOW_SCALE_DURATION = 280L;
    private static final long SHOW_ALPHA_DURATION = 100L;
    private static final long HIDE_DURATION = 150L;
    private static final float CLOSED_SCALE = 0.6f;

    // Placement, from AnnotationService.showPanel.
    private static final int PLACEMENT_GAP_DP = 10;
    private static final int PLACEMENT_FALLBACK_GAP_DP = 8;
    private static final int PLACEMENT_TOP_INSET_DP = 12;
    private static final int SCREEN_EDGE_DP = 8;

    // Close affordance. Invented: the old brush popup had no close button.
    private static final float CLOSE_ARM_DP = 7f;
    private static final float CLOSE_STROKE_DP = 1.8f;
    private final int closeColor;

    // Eraser mode tabs. The official penpicker eraser is a miuix
    // FilterSortView (34dp pills, 14sp) with the pixel/stroke tabs; the
    // stroke mode hides the size content and shows a hint instead
    // (p249l1lII1Il.li1liliI1i1, penpicker_popup_layout_eraser_set.xml).
    private static final int TAB_HEIGHT_DP = 34;
    private static final int TAB_ROW_PAD_DP = 2;
    private static final float TAB_TEXT_SP = 14f;
    private final int tabRowBg;
    private final int tabSelectedBg;
    private final int tabTextSelected;
    private final int tabTextNormal;
    private final int eraserHintColor;
    private static final float ERASER_HINT_SP = 14f;
    private static final int ERASER_HINT_TOP_DP = 31;
    private static final int ERASER_HINT_BOTTOM_DP = 25;

    // Row kinds, mirroring BrushPanel.ParamKind.
    private static final int KIND_SIZE = 0;
    private static final int KIND_SENSITIVITY = 1;
    private static final int KIND_ALPHA = 2;
    private static final int KIND_TIP = 3;

    // Rail modes.
    private static final int RAIL_PROPERTY = 0;
    private static final int RAIL_ALPHA = 1;

    private final Listener listener;
    private final float density;

    private Tool tool = Tool.INK_PEN;
    private float brushWidth = Tool.INK_PEN.defaultSize;
    private int brushAlpha = DEFAULT_ALPHA;
    private float brushSensitivity = DEFAULT_SENSITIVITY;
    private int brushTip = DEFAULT_TIP;
    private int brushColor = DEFAULT_COLOR;
    private int showEpoch;

    private PreviewView preview;
    private LinearLayout tabsRow;
    private TextView pixelTab;
    private TextView strokeTab;
    private TextView eraserHint;
    private boolean wholeStroke;
    private SizeRow sizeRow;
    private SensitivityRow sensitivityRow;
    private TipRow tipRow;
    private AlphaRow alphaRow;

    public BrushPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        // Night mode resolves these through values-night, like Mi Canvas.
        this.panelBg = pickerColor(context, R.color.penpicker_background_color, 0xFFFFFFFF);
        this.rowTextColor = pickerColor(context, R.color.penpicker_pen_set_text, 0xCC000000);
        this.trackBg = pickerColor(context, R.color.pk_property_seekbar_bg, 0x0F000000);
        this.closeColor = pickerColor(context, R.color.penpicker_pen_set_text, 0xCC000000);
        this.tabRowBg = pickerColor(context, R.color.pk_property_seekbar_bg, 0x0F000000);
        this.tabSelectedBg = pickerColor(context, R.color.creation_eraser_tab_item, 0xFFFFFFFF);
        this.tabTextSelected = pickerColor(context, R.color.ga_tab_text_selected, 0xE6000000);
        this.tabTextNormal = pickerColor(context, R.color.ga_tab_text_normal, 0x99000000);
        this.eraserHintColor = pickerColor(context, R.color.creation_eraser_hint_color, 0x66000000);
        setOrientation(VERTICAL);
        setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(panelBg);
        background.setCornerRadius(dpf(PANEL_RADIUS_DP));
        setBackground(background);
        setClipToOutline(true);
        setElevation(dpf(8f));
        buildUi(context);
        syncRows();
        applyEraserMode(wholeStroke, false);
    }

    private TextView makeTab(Context context, String label) {
        TextView tab = new TextView(context);
        tab.setText(label);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SP);
        tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tab.setGravity(Gravity.CENTER);
        tab.setIncludeFontPadding(false);
        tab.setClickable(true);
        return tab;
    }

    /**
     * Applies the eraser mode to the row visibility. Pixel keeps the size row;
     * the stroke eraser replaces it with the official hint. The eraser has no
     * sensitivity/tip/opacity controls in Mi Canvas, so those rows are hidden
     * for it in either mode.
     */
    private void applyEraserMode(boolean value, boolean notify) {
        wholeStroke = value;
        if (tabsRow == null) {
            return;
        }
        final boolean eraser = tool.eraser;
        tabsRow.setVisibility(eraser ? VISIBLE : GONE);
        sizeRow.setVisibility(eraser && value ? GONE : VISIBLE);
        // Keep the eraser's pressure trigger threshold adjustable in both modes;
        // the same setting starts whole-stroke erasing while using a pen.
        sensitivityRow.setVisibility(VISIBLE);
        tipRow.setVisibility(eraser ? GONE : VISIBLE);
        alphaRow.setVisibility(eraser ? GONE : VISIBLE);
        eraserHint.setVisibility(eraser && value ? VISIBLE : GONE);
        styleTab(pixelTab, !value);
        styleTab(strokeTab, value);
        if (notify && listener != null) {
            listener.onEraserModeChanged(value);
            listener.onContentHeightChanged();
        }
    }

    private void styleTab(TextView tab, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? tabSelectedBg : 0x00000000);
        bg.setCornerRadius(dpf(TAB_HEIGHT_DP / 2f));
        tab.setBackground(bg);
        tab.setTextColor(selected ? tabTextSelected : tabTextNormal);
        tab.setSelected(selected);
    }

    // Public API.

    /** Selects the brush whose ranges/titles are used; does not fire the listener. */
    public void setTool(Tool value) {
        if (value == null) {
            return;
        }
        tool = value;
        brushWidth = clampFloat(brushWidth, tool.minSize, tool.maxSize);
        sensitivityRow.titleView.setText(tool.sensitivityTitle);
        sizeRow.titleView.setText(tool.eraser ? "橡皮大小" : "笔画粗细");
        preview.setEraser(tool.eraser);
        applyEraserMode(wholeStroke, false);
        syncRows();
    }

    /** Sets the eraser mode tab; does not fire the listener. */
    public void setEraserMode(boolean wholeStroke) {
        applyEraserMode(wholeStroke, false);
    }

    public boolean isWholeStroke() {
        return wholeStroke;
    }

    public Tool getTool() {
        return tool;
    }

    /** Sets all four values without firing {@link Listener#onBrushChanged}. */
    public void setBrush(float width, float alpha, float sensitivity, int tip) {
        brushWidth = clampFloat(width, tool.minSize, tool.maxSize);
        brushAlpha = clampInt(Math.round(alpha), MIN_ALPHA, MAX_ALPHA);
        brushSensitivity = clampFloat(sensitivity, 0f, 1f);
        brushTip = clampInt(tip, 1, MAX_TIP);
        syncRows();
        preview.invalidate();
    }

    public float getBrushWidth() {
        return brushWidth;
    }

    public int getBrushAlpha() {
        return brushAlpha;
    }

    public float getBrushSensitivity() {
        return brushSensitivity;
    }

    public int getTip() {
        return brushTip;
    }

    /** Tints the alpha gradient and the live preview; does not fire the listener. */
    public void setColor(int value) {
        brushColor = value | 0xFF000000;
        if (alphaRow != null) {
            alphaRow.rail.setBaseColor(brushColor);
        }
        if (preview != null) {
            preview.invalidate();
        }
    }

    public int getColor() {
        return brushColor;
    }

    /** Natural height of the popup at a given width (old showPanel height = 0). */
    public int measuredHeightForWidth(int widthPx) {
        int width = widthPx > 0 ? widthPx : dp(PANEL_WIDTH_DP);
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return getMeasuredHeight();
    }

    /** Places the panel above {@code anchorY}, centred on {@code anchorX}. */
    public void showAt(int anchorX, int anchorY, int yTop, int yBottom) {
        int width = dp(PANEL_WIDTH_DP);
        int height = measuredHeightForWidth(width);
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params != null) {
            params.width = width;
            params.height = height;
            setLayoutParams(params);
        }
        if (getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) getParent();
            int parentWidth = parent.getWidth() > 0 ? parent.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            int minX = dp(SCREEN_EDGE_DP);
            int maxX = Math.max(minX, parentWidth - width - dp(SCREEN_EDGE_DP));
            int x = clampInt(anchorX - width / 2, minX, maxX);
            int topLimit = yTop + dp(PLACEMENT_TOP_INSET_DP);
            int bottomLimit = yBottom - height;
            int y = anchorY - height - dp(PLACEMENT_GAP_DP);
            if (y < topLimit) {
                y = anchorY - dp(PLACEMENT_FALLBACK_GAP_DP) - height;
            }
            if (bottomLimit < topLimit) {
                y = topLimit;
            } else {
                y = clampInt(y, topLimit, bottomLimit);
            }
            applyPosition(x, y);
        }
        final int epoch = ++showEpoch;
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        setScaleX(CLOSED_SCALE);
        setScaleY(CLOSED_SCALE);
        post(new Runnable() {
            @Override
            public void run() {
                if (epoch != showEpoch) {
                    return;
                }
                animate().scaleX(1f).scaleY(1f)
                        .setDuration(SHOW_SCALE_DURATION)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
                animate().alpha(1f).setDuration(SHOW_ALPHA_DURATION).start();
            }
        });
    }

    /** Plays the old 150ms exit; does not fire {@link Listener#onDismiss()}. */
    public void hide() {
        final int epoch = ++showEpoch;
        animate().cancel();
        animate().scaleX(CLOSED_SCALE).scaleY(CLOSED_SCALE)
                .setDuration(HIDE_DURATION)
                .setInterpolator(new AccelerateInterpolator(2f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (epoch == showEpoch) {
                            setVisibility(GONE);
                        }
                    }
                })
                .start();
        animate().alpha(0f).setDuration(HIDE_DURATION).start();
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    // Layout construction.

    private void buildUi(Context context) {
        FrameLayout previewBox = new FrameLayout(context);
        preview = new PreviewView(context);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        CloseButton close = new CloseButton(context);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
                if (listener != null) {
                    listener.onDismiss();
                }
            }
        });
        previewBox.addView(close, new FrameLayout.LayoutParams(
                dp(44), dp(44), Gravity.END | Gravity.TOP));
        addView(previewBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(PREVIEW_HEIGHT_DP)));

        // Eraser mode: the official penpicker swaps a pixel/stroke pair of
        // FilterSortView tabs above the size content (penpicker_popup_layout_
        // eraser_set.xml). Only the eraser shows the row.
        tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(HORIZONTAL);
        GradientDrawable tabsBg = new GradientDrawable();
        tabsBg.setColor(tabRowBg);
        tabsBg.setCornerRadius(dpf(TAB_HEIGHT_DP / 2f + TAB_ROW_PAD_DP));
        tabsRow.setBackground(tabsBg);
        tabsRow.setPadding(dp(TAB_ROW_PAD_DP), dp(TAB_ROW_PAD_DP),
                dp(TAB_ROW_PAD_DP), dp(TAB_ROW_PAD_DP));
        pixelTab = makeTab(context, "像素橡皮");
        pixelTab.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                applyEraserMode(false, true);
            }
        });
        strokeTab = makeTab(context, "笔画橡皮");
        strokeTab.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                applyEraserMode(true, true);
            }
        });
        tabsRow.addView(pixelTab, new LinearLayout.LayoutParams(0, dp(TAB_HEIGHT_DP), 1f));
        tabsRow.addView(strokeTab, new LinearLayout.LayoutParams(0, dp(TAB_HEIGHT_DP), 1f));
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.leftMargin = dp(CONTENT_PAD_H_DP);
        tabsLp.rightMargin = dp(CONTENT_PAD_H_DP);
        addView(tabsRow, tabsLp);

        // The official stroke-erase hint, shown instead of the size content
        // (pk_eraser_stroke_hint, 31dp top / 25dp bottom).
        eraserHint = new TextView(context);
        eraserHint.setText("按压力阈值，普通笔自动触发笔画橡皮");
        eraserHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, ERASER_HINT_SP);
        eraserHint.setTextColor(eraserHintColor);
        eraserHint.setGravity(Gravity.CENTER);
        eraserHint.setIncludeFontPadding(false);
        eraserHint.setVisibility(GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(ERASER_HINT_TOP_DP);
        hintLp.bottomMargin = dp(ERASER_HINT_BOTTOM_DP);
        addView(eraserHint, hintLp);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setPadding(dp(CONTENT_PAD_H_DP), 0, dp(CONTENT_PAD_H_DP),
                dp(CONTENT_PAD_BOTTOM_DP));
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sizeRow = new SizeRow(context);
        content.addView(sizeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sensitivityRow = new SensitivityRow(context);
        content.addView(sensitivityRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tipRow = new TipRow(context);
        content.addView(tipRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        alphaRow = new AlphaRow(context);
        content.addView(alphaRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView bodyLabel(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP);
        view.setTextColor(rowTextColor);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setLetterSpacing(0f);
        return view;
    }

    private FrameLayout rowHeader(TextView titleView, TextView valueView) {
        FrameLayout header = new FrameLayout(getContext());
        header.addView(titleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START));
        header.addView(valueView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END));
        return header;
    }

    private void syncRows() {
        if (sizeRow == null) {
            return;
        }
        sizeRow.rail.setRange(tool.minSize, tool.maxSize);
        sizeRow.rail.setRawValue(brushWidth);
        sensitivityRow.rail.setRange(0, 100);
        sensitivityRow.rail.setRawValue(brushSensitivity * 100f);
        alphaRow.rail.setRange(MIN_ALPHA, MAX_ALPHA);
        alphaRow.rail.setRawValue(brushAlpha);
        alphaRow.rail.setBaseColor(brushColor);
        tipRow.setTip(brushTip);
        updateLabels();
    }

    private void updateLabels() {
        if (sizeRow != null) {
            sizeRow.valueView.setText(String.valueOf((int) brushWidth));
        }
        if (sensitivityRow != null) {
            sensitivityRow.valueView.setText(((int) (brushSensitivity * 100f)) + "%");
        }
        if (alphaRow != null) {
            alphaRow.valueView.setText((brushAlpha * 100 / 255) + "%");
        }
        if (tipRow != null) {
            tipRow.valueView.setText(String.valueOf(brushTip));
        }
    }

    private void onRailProgress(Rail rail) {
        switch (rail.kind) {
            case KIND_SIZE:
                brushWidth = clampFloat(rail.rawValue(), tool.minSize, tool.maxSize);
                break;
            case KIND_SENSITIVITY:
                brushSensitivity = clampFloat(rail.getProgress(), 0f, 1f);
                break;
            case KIND_ALPHA:
                brushAlpha = clampInt(Math.round(rail.rawValue()), MIN_ALPHA, MAX_ALPHA);
                rail.setBaseColor(brushColor);
                break;
            case KIND_TIP:
                brushTip = clampInt(Math.round(rail.rawValue()), 1, MAX_TIP);
                break;
            default:
                break;
        }
        updateLabels();
        preview.invalidate();
        notifyBrushChanged();
    }

    private void notifyBrushChanged() {
        if (listener != null) {
            listener.onBrushChanged(brushWidth, brushAlpha, brushSensitivity, brushTip);
        }
    }

    // Placement and measurement.

    private void applyPosition(int x, int y) {
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.leftMargin = x;
            margins.topMargin = y;
            setLayoutParams(margins);
            requestLayout();
        } else {
            setX(x);
            setY(y);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int desiredWidth = dp(PANEL_WIDTH_DP);
        int width;
        if (widthMode == MeasureSpec.EXACTLY) {
            width = widthSize;
        } else if (widthMode == MeasureSpec.AT_MOST) {
            width = Math.min(desiredWidth, widthSize);
        } else {
            width = desiredWidth;
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                heightMeasureSpec);
    }

    // Utility helpers.

    private int dp(float value) {
        return Math.round(value * density);
    }

    private float dpf(float value) {
        return value * density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    /** Resolves a palette colour; the fallback keeps the panel drawable if a
     *  host build strips the resource. */
    private static int pickerColor(Context context, int resId, int fallback) {
        try {
            return context.getColor(resId);
        } catch (Resources.NotFoundException missing) {
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // The four rows. Each keeps the old title/value header over a 28dp control.

    private abstract class BaseRow extends LinearLayout {
        final int kind;
        final TextView titleView;
        final TextView valueView;

        BaseRow(Context context, int kind, String title) {
            super(context);
            this.kind = kind;
            setOrientation(VERTICAL);
            titleView = bodyLabel(title);
            valueView = bodyLabel("");
            addView(rowHeader(titleView, valueView), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout.LayoutParams controlParams() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_SLIDER_HEIGHT_DP));
            lp.topMargin = dp(ROW_SLIDER_MARGIN_TOP_DP);
            return lp;
        }
    }

    private final class SizeRow extends BaseRow {
        final Rail rail;

        SizeRow(Context context) {
            super(context, KIND_SIZE, "笔画粗细");
            rail = new Rail(context, RAIL_PROPERTY, KIND_SIZE);
            addView(rail, controlParams());
        }
    }

    private final class SensitivityRow extends BaseRow {
        final Rail rail;

        SensitivityRow(Context context) {
            super(context, KIND_SENSITIVITY, tool.sensitivityTitle);
            rail = new Rail(context, RAIL_PROPERTY, KIND_SENSITIVITY);
            addView(rail, controlParams());
        }
    }

    private final class AlphaRow extends BaseRow {
        final Rail rail;

        AlphaRow(Context context) {
            super(context, KIND_ALPHA, "不透明度");
            rail = new Rail(context, RAIL_ALPHA, KIND_ALPHA);
            addView(rail, controlParams());
        }
    }

    private final class TipRow extends BaseRow {
        final Rail rail;

        TipRow(Context context) {
            super(context, KIND_TIP, "笔尖大小");
            rail = new Rail(context, RAIL_PROPERTY, KIND_TIP);
            rail.setRange(1, MAX_TIP);
            addView(rail, controlParams());
        }

        void setTip(int value) {
            rail.setRange(1, MAX_TIP);
            rail.setRawValue(value);
            valueView.setText(String.valueOf(value));
        }
    }

    /** One property rail: neutral track with the blue ring, or the alpha rail. */
    private final class Rail extends View {
        private final int mode;
        final int kind;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        /** Official {@code pk_alpha_bg}: translucent checker under the gradient. */
        private final Drawable alphaBg;
        private LinearGradient gradient;
        private int gradientWidth = -1;
        private int gradientColor;
        private float progress;
        private int min;
        private int max = 100;
        private int baseColor = DEFAULT_COLOR;

        Rail(Context context, int mode, int kind) {
            super(context);
            this.mode = mode;
            this.kind = kind;
            ringPaint.setStyle(Paint.Style.STROKE);
            Drawable bg = null;
            if (mode == RAIL_ALPHA) {
                try {
                    bg = context.getDrawable(R.drawable.pk_alpha_bg);
                } catch (Resources.NotFoundException missing) {
                    bg = null;
                }
            }
            alphaBg = bg;
            setClickable(true);
        }

        void setRange(int min, int max) {
            this.min = min;
            this.max = max;
        }

        void setRawValue(float raw) {
            if (max == min) {
                setProgress(0f);
                return;
            }
            setProgress((raw - min) / (max - min));
        }

        float rawValue() {
            return min + progress * (max - min);
        }

        void setProgress(float value) {
            progress = clampFloat(value, 0f, 1f);
            invalidate();
        }

        float getProgress() {
            return progress;
        }

        void setBaseColor(int value) {
            baseColor = value | 0xFF000000;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                    ? MeasureSpec.getSize(heightMeasureSpec)
                    : dp(ROW_SLIDER_HEIGHT_DP);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float cy = h / 2f;
            // The official SeekBar background fills the whole view: the track
            // is exactly pk_seekbar_height tall (28dp), not a shorter capsule.
            float radius = h / 2f;
            trackRect.set(0f, 0f, w, h);

            if (mode == RAIL_ALPHA) {
                // I1llI1: LayerDrawable {pk_alpha_bg, GradientDrawable
                // {0x00FFFFFF, pen colour}} stretched over the view bounds.
                if (alphaBg != null) {
                    alphaBg.setBounds(0, 0, w, h);
                    alphaBg.draw(canvas);
                }
                final int color = baseColor | 0xFF000000;
                if (gradient == null || gradientWidth != w || gradientColor != color) {
                    gradient = new LinearGradient(0f, 0f, w, 0f,
                            0x00FFFFFF, color, Shader.TileMode.CLAMP);
                    gradientWidth = w;
                    gradientColor = color;
                }
                trackPaint.setShader(gradient);
                canvas.drawRoundRect(trackRect, radius, radius, trackPaint);
                trackPaint.setShader(null);
            } else {
                trackPaint.setColor(trackBg);
                canvas.drawRoundRect(trackRect, radius, radius, trackPaint);
            }

            // Thumb: a bare ring, stroke 3dp, outer diameter equal to the
            // track height, centred with margin 0 (travel = width - height).
            float stroke = dpf(THUMB_STROKE_DP);
            float ringRadius = radius - stroke / 2f;
            float travel = Math.max(0f, w - h);
            float cx = radius + progress * travel;
            ringPaint.setStrokeWidth(stroke);
            ringPaint.setColor(mode == RAIL_ALPHA ? Color.WHITE : ACCENT);
            canvas.drawCircle(cx, cy, ringRadius, ringPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lockParent(true);
                    updateFromX(event.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateFromX(event.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                    updateFromX(event.getX());
                    lockParent(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    lockParent(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void updateFromX(float x) {
            // I1llI1: thumb centre = height/2 + progress * (width - height).
            float h = getHeight();
            float travel = getWidth() - h;
            float value = travel <= 0f ? 0f : (x - h / 2f) / travel;
            setProgress(value);
            BrushPanel.this.onRailProgress(this);
        }

        private void lockParent(boolean lock) {
            ViewParent parent = getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(lock);
                parent = parent.getParent();
            }
        }
    }

    /** The 110dp sample: one S-wave with the old pressure taper. */
    private final class PreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean eraser;

        PreviewView(Context context) {
            super(context);
        }

        void setEraser(boolean value) {
            eraser = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // PenStyle.toPixels: size * density; the eraser's size is a radius
            // (PenStyle.kt:34-35, 51), so its preview diameter is doubled.
            float base = brushWidth * density * (eraser ? 2f : 1f);
            base = Math.min(base, h * 0.5f);
            float margin = base * PREVIEW_MARGIN_FACTOR + dpf(PREVIEW_MARGIN_EXTRA_DP);
            float x0 = margin;
            float x1 = w - margin;
            if (x1 <= x0) {
                return;
            }
            float cy = h / 2f;
            float amp = Math.max(h * PREVIEW_AMP_FACTOR, dpf(PREVIEW_MIN_AMP_DP));
            int n = PREVIEW_SAMPLES;
            float[] xs = new float[n + 1];
            float[] ys = new float[n + 1];
            float[] ws = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float t = i / (float) n;
                xs[i] = x0 + (x1 - x0) * t;
                ys[i] = cy + amp * (float) Math.sin(2.0 * Math.PI * t);
                float pressure = PREVIEW_PRESSURE_MIN
                        + PREVIEW_PRESSURE_RANGE * (float) Math.sin(Math.PI * t);
                ws[i] = Math.max(base * pressure, 1f);
            }

            float[] topX = new float[n + 1];
            float[] topY = new float[n + 1];
            float[] bottomX = new float[n + 1];
            float[] bottomY = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float dx;
                float dy;
                if (i == 0) {
                    dx = xs[1] - xs[0];
                    dy = ys[1] - ys[0];
                } else if (i == n) {
                    dx = xs[n] - xs[n - 1];
                    dy = ys[n] - ys[n - 1];
                } else {
                    dx = xs[i + 1] - xs[i - 1];
                    dy = ys[i + 1] - ys[i - 1];
                }
                float length = (float) Math.hypot(dx, dy);
                if (length < 0.0001f) {
                    length = 1f;
                }
                float nx = -dy / length;
                float ny = dx / length;
                float half = ws[i] / 2f;
                topX[i] = xs[i] + nx * half;
                topY[i] = ys[i] + ny * half;
                bottomX[i] = xs[i] - nx * half;
                bottomY[i] = ys[i] - ny * half;
            }

            path.reset();
            path.moveTo(topX[0], topY[0]);
            for (int i = 1; i <= n; i++) {
                path.lineTo(topX[i], topY[i]);
            }
            for (int i = n; i >= 0; i--) {
                path.lineTo(bottomX[i], bottomY[i]);
            }
            path.close();

            final int previewAlpha = Math.round(brushAlpha * tool.previewAlpha / 255f);
            int color = eraser ? ERASER_PREVIEW : Color.argb(previewAlpha, Color.red(brushColor),
                    Color.green(brushColor), Color.blue(brushColor));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }
    }

    /** 44dp close box; the old brush popup had no close affordance. */
    private final class CloseButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseButton(Context context) {
            super(context);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float arm = dpf(CLOSE_ARM_DP);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpf(CLOSE_STROKE_DP));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(closeColor);
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint);
            canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, paint);
        }
    }
}
