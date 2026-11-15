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

import java.util.ArrayList;

/**
 * Standalone Java port of the old Kotlin {@code widget/ColorPanel.kt}
 * (Mi Canvas {@code penpicker_popup_color_picker}). Nothing here is wired into
 * the service; every view is built in code and no resource id is referenced.
 *
 * <p>Structure, in the old file's order: title row, two-tab pill, a pager with
 * the 13x9 board page and the H/S/L page, then the 72dp HUD block plus the two
 * six-dot recent rows. The board and rails are local ports of
 * {@code ColorPickPanelView} / {@code PickerBar}, not Mi Canvas classes.</p>
 *
 * <p>Constant map (old source:line -> value):</p>
 * <pre>
 * PANEL_WIDTH_DP 360 ................ AnnotationService.kt:1563-1576; res/values/penpicker_dimens.xml
 *                                     (penpicker_color_picker_popup_width)
 * PANEL_HEIGHT_DP 571 ............... AnnotationService.kt:1563-1576 (pk_color_picker_popup_height)
 * PANEL_RADIUS_DP 36 ................ penpicker_dimens.xml (penpicker_color_picker_background_radius)
 * panelBg 0xFFFFFFFF ............... penpicker_colors.xml (penpicker_background_color)
 * TITLE_ROW_DP 44 ................... layout/penpicker_popup_color_picker.xml:23-24
 * TITLE_MARGIN_TOP_DP 20 ............ layout/penpicker_popup_color_picker.xml:11
 * TITLE_MARGIN_BOTTOM_DP 12 ......... layout/penpicker_popup_color_picker.xml:12
 * PAGE_MARGIN_DP 20 ................. layout/penpicker_popup_color_picker.xml:13
 * TITLE_TEXT_SP 20 .................. penpicker_styles.xml (penpicker_colorpicker_title)
 * BODY_TEXT_SP 14 ................... penpicker_styles.xml (penpicker_pen_set_size)
 * titleColor 0xFF000000 ............ penpicker_colors.xml (penpicker_colorpicker_text_title)
 * bodyTextColor 0xCC000000 ....... penpicker_colors.xml (penpicker_pen_set_text)
 * TAB_HEIGHT_DP 40 .................. layout/penpicker_popup_color_picker.xml:30-31
 * TAB_TEXT_SP 14 .................... FilterSortView.kt:78-81
 * tabBg 0xFFFFFFFF ................. FilterSortView.kt:52
 * tabStrokeColor 0x0D000000 ....... FilterSortView.kt:54
 * tabSelectedFill 0x0F000000 ...... FilterSortView.kt:72
 * tabTextColor 0xFF000000 ......... FilterSortView.kt:73-75
 * TAB_PADDING_DP 3 .................. FilterSortView.kt:43-44
 * PAGER_TOP_MARGIN_DP 16 ............ layout/penpicker_popup_color_picker.xml:53
 * PAGER_SIDE_MARGIN_DP 15 ........... layout/penpicker_popup_color_picker.xml:61
 * GRID_WIDTH_DP 330 / HEIGHT 230 .... layout/penpicker_popup_color_picker_grid.xml:29-30
 * GRID_COLS 13 / GRID_ROWS 9 ........ CreationPalette.kt:20-21
 * GRID_MARGIN_TOP_DP 3 .............. layout/penpicker_popup_color_picker_grid.xml:31
 * GRID_LABEL_MARGIN_START_DP 5 ...... layout/penpicker_popup_color_picker_grid.xml:22
 * GRID_RADIUS_DP 8.89 ............... ColorPickPanelView.kt:62-65
 * GRID_BORDER_WIDTH 1px / 0x1A000000 . ColorPickPanelView.kt:38-43
 * GRID_RING_DP 8.14 ................. ColorPickPanelView.kt:33-37
 * GRID_ROW0_RING 0xFFE4E4E4 ......... ColorPickPanelView.kt:95
 * GRID_ALPHA_TOP_MARGIN_DP 16 ....... layout/penpicker_popup_color_picker_grid.xml:38
 * GRID_ALPHA_SIDE_PAD_DP 5 .......... layout/penpicker_popup_color_picker_grid.xml:39
 * ALPHA_LABEL_PAD_BOTTOM_DP 8 ....... layout/penpicker_popup_color_picker_grid.xml:41
 * SLIDER_WIDTH_DP 320 ............... penpicker_dimens.xml (pk_color_picker_view_page_slider_width)
 * SLIDER_MARGIN_START_DP 5 .......... penpicker_dimens.xml (pk_color_picker_view_page_slider_margin_start)
 * SLIDER_HEIGHT_DP 28 ............... penpicker_dimens.xml (pk_color_picker_view_page_slider_seek_bar_height)
 * SLIDER_MARGIN_TOP_DP 8 ............ penpicker_dimens.xml (pk_color_picker_view_page_slider_seek_bar_margin_top)
 * SLIDER_LABEL_MARGIN_TOP_DP 24 ..... penpicker_dimens.xml (pk_color_picker_view_page_slider_text_margin_top)
 * BOTTOM_TOP_MARGIN_DP 28 ........... layout/penpicker_popup_color_picker.xml:67
 * BOTTOM_BOTTOM_MARGIN_DP 20 ........ layout/penpicker_popup_color_picker.xml:68
 * SWATCH_SIZE_DP 72 ................. penpicker_dimens.xml (penpicker_color_picker_popup_picker_display_width_height)
 * SWATCH_RADIUS_DP 17.24 ............ ColorSwatchBlock.kt:33-36
 * SWATCH_DEFAULT 0xFFA8D4F5 ......... ColorSwatchBlock.kt:17
 * RECENT_START_MARGIN_DP 16 ......... layout/penpicker_popup_color_picker.xml:82
 * RECENT_ROW_GAP_DP 16 .............. layout/penpicker_popup_color_picker.xml:89
 * RECENT_DOT_DP 28 .................. ColorPanel.kt:919
 * RECENT_GAP_DP 13 .................. ColorPanel.kt:920 (initial pitch; spreadRecentRow refines it)
 * RECENT DOT LOOK ................... ColorPanel.kt:504-531 (28dp dot, light-colour hairline,
 *                                     white 1.25dp checked ring at 0.76r)
 * DEFAULT_RECENTS ................... BrushConfig.kt:37-42
 *                                     (#A8D4F5/#E8464A/#FFFFFF/#000000)
 * MAX_RECENT 12 / RECENT_PER_ROW 6 .. ColorPanel.kt:913 and :380-397
 * TRACK_HEIGHT_DP 28 ................ PickerBar.kt:56-62
 * THUMB_DIAMETER_DP 24 .............. PickerBar.kt:56-62
 * THUMB_RING_DP 8.14 ................ PickerBar.kt:56-62
 * THUMB_BORDER_DP 0.7 ............... PickerBar.kt:56-66
 * THUMB_MARGIN_DP 1.81 .............. PickerBar.kt:56-66
 * trackBg 0x0F000000 ............... penpicker_colors.xml (pk_property_seekbar_bg)
 * trackBorder 0x0D000000 ........... PickerBar.kt:107-108
 * thumbBorder 0x0F000000 ........... PickerBar.kt:125-126
 * CHECKER 0xFF9A9A9A / 0xFFD6D6D6 ... PickerBar.kt:163-182
 * DEFAULT_COLOR 0xFFE8464A .......... ColorPanel.kt:104
 * DEFAULT_ALPHA 255 ................. ColorPanel.kt:95
 * MIN_ALPHA 3 ....................... ColorPanel.kt:218, 545
 * SHOW_DURATION 280 / 100 / scale .6  AnnotationService.kt:1680-1700
 * HIDE_DURATION 150 / scale .6 ...... AnnotationService.kt:1716-1730
 * PLACEMENT_GAP 10 / FALLBACK 8 ..... AnnotationService.kt:1666-1674
 * PLACEMENT_TOP_INSET 12 ............ AnnotationService.kt:1670-1672
 * SCREEN_EDGE 8 ..................... AnnotationService.kt:1664-1666
 * CLOSE_* ........................... invented: the old 44dp eyedropper slot
 *                                     (layout/penpicker_popup_color_picker.xml:23-24) is
 *                                     replaced by a close control because the required
 *                                     Listener has onDismiss() and no onPipette().
 * </pre>
 */
public final class ColorPanel extends LinearLayout {

    /** Callbacks the old panel exposed as {@code onEditorColor/onAlpha}. */
    public interface Listener {
        /** A colour was picked, opaque ARGB (alpha is reported separately). */
        void onColorPicked(int color);

        /** Opacity in the old raw 3..255 units, as a float. */
        void onAlphaPicked(float alpha);

        /** The user asked to close the panel (the close button). */
        void onDismiss();
    }

    // Panel box, from AnnotationService.colorPopupSize (old 360x571).
    private static final float PANEL_WIDTH_DP = 360f;
    private static final float PANEL_HEIGHT_DP = 571f;
    private static final float PANEL_RADIUS_DP = 36f;
    private final int panelBg;

    // Title row: 44dp eyedropper box, 20dp top, 12dp bottom, 20dp page margins.
    private static final int TITLE_ROW_DP = 44;
    private static final int TITLE_MARGIN_TOP_DP = 20;
    private static final int TITLE_MARGIN_BOTTOM_DP = 12;
    private static final int PAGE_MARGIN_DP = 20;
    private static final float TITLE_TEXT_SP = 20f;
    private static final float BODY_TEXT_SP = 14f;
    private final int titleColor;
    private final int bodyTextColor;

    // Two-tab pill, from FilterSortView.
    private static final int TAB_HEIGHT_DP = 40;
    private static final float TAB_TEXT_SP = 14f;
    private final int tabBg;
    private final int tabStrokeColor;
    private final int tabSelectedFill;
    private final int tabTextColor;
    private static final int TAB_PADDING_DP = 3;

    // Pager and pages.
    private static final int PAGER_TOP_MARGIN_DP = 16;
    private static final int PAGER_SIDE_MARGIN_DP = 15;
    private static final int GRID_WIDTH_DP = 330;
    private static final int GRID_HEIGHT_DP = 230;
    private static final int GRID_MARGIN_TOP_DP = 3;
    private static final int GRID_LABEL_MARGIN_START_DP = 5;
    private static final int GRID_COLS = 13;
    private static final int GRID_ROWS = 9;
    private static final float GRID_RADIUS_DP = 8.89f;
    private static final float GRID_BORDER_WIDTH = 1f;
    private final int gridBorderColor;
    private static final float GRID_RING_DP = 8.14f;
    private static final int GRID_ROW0_RING = 0xFFE4E4E4;
    private static final int GRID_ALPHA_TOP_MARGIN_DP = 16;
    private static final int GRID_ALPHA_SIDE_PAD_DP = 5;
    private static final int ALPHA_LABEL_PAD_BOTTOM_DP = 8;
    private static final int SLIDER_WIDTH_DP = 320;
    private static final int SLIDER_MARGIN_START_DP = 5;
    private static final int SLIDER_HEIGHT_DP = 28;
    private static final int SLIDER_MARGIN_TOP_DP = 8;
    private static final int SLIDER_LABEL_MARGIN_TOP_DP = 24;

    // Bottom block: 72dp HUD + two 28dp recent rows, 16dp between them.
    private static final int BOTTOM_TOP_MARGIN_DP = 28;
    private static final int BOTTOM_BOTTOM_MARGIN_DP = 20;
    private static final int SWATCH_SIZE_DP = 72;
    private static final float SWATCH_RADIUS_DP = 17.24f;
    private static final int SWATCH_DEFAULT = 0xFFA8D4F5;
    private static final int RECENT_START_MARGIN_DP = 16;
    private static final int RECENT_ROW_GAP_DP = 16;
    private static final int RECENT_DOT_DP = 28;
    private static final int RECENT_GAP_DP = 13;
    private static final int RECENT_PER_ROW = 6;
    private static final int MAX_RECENT = 12;

    // Rail / thumb geometry, from PickerBar.
    private static final float TRACK_HEIGHT_DP = 28f;
    private static final float THUMB_DIAMETER_DP = 24f;
    private static final float THUMB_RING_DP = 8.14f;
    private static final float THUMB_BORDER_DP = 0.7f;
    private static final float THUMB_MARGIN_DP = 1.81f;
    private final int trackBg;
    private final int trackBorder;
    private final int thumbBorder;
    private static final int CHECKER_A = 0xFF9A9A9A;
    private static final int CHECKER_B = 0xFFD6D6D6;

    // Colour state.
    private static final int DEFAULT_COLOR = 0xFFE8464A;
    private static final int DEFAULT_ALPHA = 255;
    private static final int MIN_ALPHA = 3;
    private static final int MAX_ALPHA = 255;
    private static final int[] DEFAULT_RECENTS = {
            0xFFA8D4F5, 0xFFE8464A, 0xFFFFFFFF, 0xFF000000,
    };

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

    // Close affordance. Invented: replaces the old 44dp eyedropper slot.
    private static final float CLOSE_ARM_DP = 7f;
    private static final float CLOSE_STROKE_DP = 1.8f;
    private final int closeColor;

    // Rail modes.
    private static final int RAIL_HUE = 0;
    private static final int RAIL_SATURATION = 1;
    private static final int RAIL_LIGHTNESS = 2;
    private static final int RAIL_ALPHA = 3;

    /** The 13x9 board's swatches, verbatim from CreationPalette.COLORS. */
    private static final int[] PALETTE = {
            -78389, -9536, -6208, -4413, -2625, -1771328, -3738674, -3935502, -3350273, -2237955, -1191171, -405531, -1,
            -149066, -13401, -8793, -6229, -3418, -2427481, -5182022, -5509908, -4664321, -3159299, -1654532, -541478, -1710619,
            -224628, -21130, -13706, -9605, -5517, -3870860, -8133741, -8658206, -7357697, -4936708, -2647045, -878907, -3355444,
            -303016, -30665, -19913, -14017, -7885, -5642187, -11872415, -12593707, -10642433, -7174662, -3838215, -1284438, -5855578,
            -378834, -38395, -24827, -17649, -9984, -7020029, -14824134, -15742262, -13335809, -8951815, -4764937, -1621867, -8355712,
            -2805721, -2532860, -2521340, -2515187, -2508800, -8470525, -15094223, -15881044, -13864999, -10135340, -6537262, -3918209, -10461088,
            -5626337, -5421053, -5411837, -5406966, -5401600, -10118654, -15430873, -16086647, -14460755, -11450711, -8637784, -6476699, -12171706,
            -8578025, -8375037, -8368125, -8364536, -8360704, -11898622, -15767779, -16226971, -15056512, -12831619, -10738308, -9166773, -13816531,
            -10217966, -10081790, -10076158, -10073338, -10070272, -12887551, -15969769, -16431289, -15585703, -14015401, -12576170, -11463116, -16777216,
    };

    private final Listener listener;
    private final float density;

    private final float[] hsv = new float[3];
    private int color = DEFAULT_COLOR;
    private int alpha = DEFAULT_ALPHA;
    /** Guards H/S while a rail is dragged; see ColorPanel.kt:98-102. */
    private boolean holdingHsv;
    private int showEpoch;

    private final ArrayList<Integer> recentColors = new ArrayList<>();

    private Board board;
    private Rail hueRail;
    private Rail saturationRail;
    private Rail lightnessRail;
    private Rail gridAlphaRail;
    private Rail sliderAlphaRail;
    private SwatchBlock swatch;
    private RecentRow recentRow1;
    private RecentRow recentRow2;
    private TextView gridAlphaValue;
    private TextView sliderAlphaValue;
    private TabBar tabs;
    private LinearLayout gridPage;
    private LinearLayout sliderPage;
    private int currentPage;

    public ColorPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        // Night mode resolves these through values-night, like Mi Canvas.
        this.panelBg = pickerColor(context, R.color.penpicker_background_color, 0xFFFFFFFF);
        this.titleColor = pickerColor(context, R.color.penpicker_colorpicker_text_title, 0xFF000000);
        this.bodyTextColor = pickerColor(context, R.color.penpicker_pen_set_text, 0xCC000000);
        this.tabBg = pickerColor(context, R.color.penpicker_background_color, 0xFFFFFFFF);
        this.tabStrokeColor = pickerColor(context, R.color.creation_seekbar_border, 0x0D000000);
        this.tabSelectedFill = pickerColor(context,
                R.color.penpicker_circle_button_background_color, 0x0F000000);
        this.tabTextColor = pickerColor(context, R.color.penpicker_colorpicker_text_title,
                0xFF000000);
        this.gridBorderColor = pickerColor(context, R.color.penpicker_dividing_line_color, 0x1A000000);
        this.trackBg = pickerColor(context, R.color.pk_property_seekbar_bg, 0x0F000000);
        this.trackBorder = pickerColor(context, R.color.creation_seekbar_border, 0x0D000000);
        this.thumbBorder = pickerColor(context, R.color.pk_property_seekbar_bg, 0x0F000000);
        this.closeColor = pickerColor(context, R.color.penpicker_pen_set_text, 0xCC000000);
        Color.colorToHSV(color, hsv);
        setOrientation(VERTICAL);
        setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(panelBg);
        background.setCornerRadius(dpf(PANEL_RADIUS_DP));
        setBackground(background);
        setClipToOutline(true);
        setElevation(dpf(8f));
        buildUi(context);
        setRecentColors(DEFAULT_RECENTS);
        syncViews(true);
    }

    // Public API.

    /** Sets the colour without firing the listener, like the old property. */
    public void setColor(int value) {
        color = value | 0xFF000000;
        if (!holdingHsv) {
            Color.colorToHSV(color, hsv);
        }
        if (board != null) {
            syncViews(true);
        }
    }

    /** Opaque ARGB; opacity is carried separately, exactly as in the old panel. */
    public int getColor() {
        return color;
    }

    /** Opacity in the old raw 3..255 units (View.getAlpha() is the view alpha). */
    public int getAlphaValue() {
        return alpha;
    }

    /** Sets opacity without firing {@link Listener#onAlphaPicked(float)}. */
    public void setAlphaValue(int value) {
        alpha = clampInt(value, MIN_ALPHA, MAX_ALPHA);
        if (gridAlphaRail != null) {
            gridAlphaRail.setProgress(alpha / 255f);
            sliderAlphaRail.setProgress(alpha / 255f);
        }
        updateAlphaLabels();
        if (swatch != null) {
            swatch.setColor(argb());
        }
    }

    /** Replaces the recent strip; empty/null restores the four defaults. */
    public void setRecentColors(int[] values) {
        recentColors.clear();
        if (values == null || values.length == 0) {
            for (int value : DEFAULT_RECENTS) {
                recentColors.add(value | 0xFF000000);
            }
        } else {
            for (int i = 0; i < values.length && i < MAX_RECENT; i++) {
                recentColors.add(values[i] | 0xFF000000);
            }
        }
        rebuildRecent();
    }

    /** The old service measured the panel exactly this way (EXACTLY / UNSPECIFIED). */
    public int measuredHeightForWidth(int widthPx) {
        int width = widthPx > 0 ? widthPx : dp(PANEL_WIDTH_DP);
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return getMeasuredHeight();
    }

    /**
     * Places the panel above {@code anchorY} and centres it on {@code anchorX}.
     * {@code yTop}/{@code yBottom} are the usable vertical bounds (screen
     * coordinates); the old service used a 10dp gap above the bar, an 8dp
     * fallback gap, a 12dp top inset and 8dp screen margins.
     */
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
        FrameLayout titleRow = new FrameLayout(context);
        TextView title = new TextView(context);
        title.setText("颜色");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SP);
        title.setTextColor(titleColor);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setLetterSpacing(0f);
        titleRow.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

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
        titleRow.addView(close, new FrameLayout.LayoutParams(
                dp(TITLE_ROW_DP), dp(TITLE_ROW_DP), Gravity.END | Gravity.CENTER_VERTICAL));

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TITLE_ROW_DP));
        titleLp.topMargin = dp(TITLE_MARGIN_TOP_DP);
        titleLp.bottomMargin = dp(TITLE_MARGIN_BOTTOM_DP);
        titleLp.leftMargin = dp(PAGE_MARGIN_DP);
        titleLp.rightMargin = dp(PAGE_MARGIN_DP);
        addView(titleRow, titleLp);

        tabs = new TabBar(context);
        tabs.setTabListener(new TabListener() {
            @Override
            public void onTabSelected(int index) {
                selectPage(index);
            }
        });
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_HEIGHT_DP));
        tabsLp.leftMargin = dp(PAGE_MARGIN_DP);
        tabsLp.rightMargin = dp(PAGE_MARGIN_DP);
        addView(tabs, tabsLp);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(PAGER_TOP_MARGIN_DP);
        addView(scroll, scrollLp);

        FrameLayout pager = new FrameLayout(context);
        FrameLayout.LayoutParams pagerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pagerLp.leftMargin = dp(PAGER_SIDE_MARGIN_DP);
        pagerLp.rightMargin = dp(PAGER_SIDE_MARGIN_DP);
        scroll.addView(pager, pagerLp);

        buildGridPage(context, pager);
        buildSliderPage(context, pager);

        buildBottomBlock(context);
    }

    private void buildGridPage(Context context, FrameLayout pager) {
        gridPage = new LinearLayout(context);
        gridPage.setOrientation(VERTICAL);

        TextView gridLabel = bodyLabel("色相");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(GRID_LABEL_MARGIN_START_DP);
        gridPage.addView(gridLabel, glp);

        board = new Board(context);
        LinearLayout.LayoutParams boardLp = new LinearLayout.LayoutParams(
                dp(GRID_WIDTH_DP), dp(GRID_HEIGHT_DP));
        boardLp.topMargin = dp(GRID_MARGIN_TOP_DP);
        gridPage.addView(board, boardLp);

        LinearLayout alphaSection = new LinearLayout(context);
        alphaSection.setOrientation(VERTICAL);
        alphaSection.setPadding(dp(GRID_ALPHA_SIDE_PAD_DP), 0, dp(GRID_ALPHA_SIDE_PAD_DP), 0);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = dp(GRID_ALPHA_TOP_MARGIN_DP);
        gridPage.addView(alphaSection, sectionLp);

        FrameLayout header = alphaHeader(context);
        gridAlphaValue = (TextView) header.getChildAt(1);
        alphaSection.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        gridAlphaRail = new Rail(context, RAIL_ALPHA);
        alphaSection.addView(gridAlphaRail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SLIDER_HEIGHT_DP)));

        pager.addView(gridPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildSliderPage(Context context, FrameLayout pager) {
        sliderPage = new LinearLayout(context);
        sliderPage.setOrientation(VERTICAL);

        sliderPage.addView(bodyLabel("色相"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hueRail = new Rail(context, RAIL_HUE);
        LinearLayout.LayoutParams hueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SLIDER_HEIGHT_DP));
        hueLp.topMargin = dp(SLIDER_MARGIN_TOP_DP);
        sliderPage.addView(hueRail, hueLp);

        TextView saturationLabel = bodyLabel("饱和度");
        LinearLayout.LayoutParams satLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        satLabelLp.topMargin = dp(SLIDER_LABEL_MARGIN_TOP_DP);
        sliderPage.addView(saturationLabel, satLabelLp);
        saturationRail = new Rail(context, RAIL_SATURATION);
        LinearLayout.LayoutParams satLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SLIDER_HEIGHT_DP));
        satLp.topMargin = dp(SLIDER_MARGIN_TOP_DP);
        sliderPage.addView(saturationRail, satLp);

        TextView lightnessLabel = bodyLabel("亮度");
        LinearLayout.LayoutParams lightLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lightLabelLp.topMargin = dp(SLIDER_LABEL_MARGIN_TOP_DP);
        sliderPage.addView(lightnessLabel, lightLabelLp);
        lightnessRail = new Rail(context, RAIL_LIGHTNESS);
        LinearLayout.LayoutParams lightLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SLIDER_HEIGHT_DP));
        lightLp.topMargin = dp(SLIDER_MARGIN_TOP_DP);
        sliderPage.addView(lightnessRail, lightLp);

        LinearLayout alphaSection = new LinearLayout(context);
        alphaSection.setOrientation(VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = dp(SLIDER_LABEL_MARGIN_TOP_DP);
        sliderPage.addView(alphaSection, sectionLp);

        FrameLayout header = alphaHeader(context);
        sliderAlphaValue = (TextView) header.getChildAt(1);
        alphaSection.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sliderAlphaRail = new Rail(context, RAIL_ALPHA);
        LinearLayout.LayoutParams alphaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SLIDER_HEIGHT_DP));
        alphaLp.topMargin = dp(SLIDER_MARGIN_TOP_DP);
        alphaSection.addView(sliderAlphaRail, alphaLp);

        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(
                dp(SLIDER_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        pageLp.leftMargin = dp(SLIDER_MARGIN_START_DP);
        pager.addView(sliderPage, pageLp);
        sliderPage.setVisibility(GONE);
    }

    private void buildBottomBlock(Context context) {
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBaselineAligned(false);

        swatch = new SwatchBlock(context);
        bottom.addView(swatch, new LinearLayout.LayoutParams(
                dp(SWATCH_SIZE_DP), dp(SWATCH_SIZE_DP)));

        LinearLayout recentBox = new LinearLayout(context);
        recentBox.setOrientation(VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                0, dp(SWATCH_SIZE_DP), 1f);
        boxLp.leftMargin = dp(RECENT_START_MARGIN_DP);
        bottom.addView(recentBox, boxLp);

        recentRow1 = new RecentRow(context, 0);
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(RECENT_DOT_DP));
        row1Lp.bottomMargin = dp(RECENT_ROW_GAP_DP);
        recentBox.addView(recentRow1, row1Lp);

        recentRow2 = new RecentRow(context, 1);
        recentBox.addView(recentRow2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(RECENT_DOT_DP)));

        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SWATCH_SIZE_DP));
        bottomLp.topMargin = dp(BOTTOM_TOP_MARGIN_DP);
        bottomLp.bottomMargin = dp(BOTTOM_BOTTOM_MARGIN_DP);
        bottomLp.leftMargin = dp(PAGE_MARGIN_DP);
        bottomLp.rightMargin = dp(PAGE_MARGIN_DP);
        addView(bottom, bottomLp);
    }

    private FrameLayout alphaHeader(Context context) {
        FrameLayout header = new FrameLayout(context);
        header.setPadding(0, 0, 0, dp(ALPHA_LABEL_PAD_BOTTOM_DP));
        TextView label = bodyLabel("不透明度");
        TextView value = bodyLabel("100%");
        header.addView(label, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START));
        header.addView(value, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END));
        return header;
    }

    private TextView bodyLabel(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_TEXT_SP);
        view.setTextColor(bodyTextColor);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setLetterSpacing(0f);
        return view;
    }

    private void selectPage(int index) {
        if (index == currentPage) {
            return;
        }
        currentPage = index;
        View incoming = index == 0 ? gridPage : sliderPage;
        View outgoing = index == 0 ? sliderPage : gridPage;
        float direction = index == 0 ? -1f : 1f;
        incoming.animate().cancel();
        outgoing.animate().cancel();
        outgoing.setVisibility(GONE);
        outgoing.setAlpha(1f);
        outgoing.setTranslationX(0f);
        incoming.setVisibility(VISIBLE);
        incoming.setAlpha(0f);
        incoming.setTranslationX(dpf(24f) * direction);
        incoming.animate().alpha(1f).translationX(0f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    // Colour state.

    private void pickColor(int value, boolean commit) {
        color = value | 0xFF000000;
        Color.colorToHSV(color, hsv);
        syncViews(true);
        if (listener != null) {
            listener.onColorPicked(color);
        }
        if (commit) {
            addRecent(color);
        }
    }

    private void applyHsv() {
        holdingHsv = true;
        color = Color.HSVToColor(hsv) | 0xFF000000;
        holdingHsv = false;
        syncViews(false);
        if (listener != null) {
            listener.onColorPicked(color);
        }
    }

    private void applyAlpha(float progress, Rail source) {
        alpha = clampInt(Math.round(progress * 255f), MIN_ALPHA, MAX_ALPHA);
        float normalized = alpha / 255f;
        if (gridAlphaRail != null && gridAlphaRail != source) {
            gridAlphaRail.setProgress(normalized);
        }
        if (sliderAlphaRail != null && sliderAlphaRail != source) {
            sliderAlphaRail.setProgress(normalized);
        }
        updateAlphaLabels();
        if (swatch != null) {
            swatch.setColor(argb());
        }
        if (listener != null) {
            listener.onAlphaPicked(alpha);
        }
    }

    private void syncViews(boolean fromGrid) {
        board.setColor(color);
        swatch.setColor(argb());
        int pureHue = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        int pureSat = Color.HSVToColor(new float[]{hsv[0], Math.max(hsv[1], 0.001f), 1f});
        hueRail.setBaseColor(pureHue);
        saturationRail.setBaseColor(pureHue);
        lightnessRail.setBaseColor(pureSat);
        gridAlphaRail.setBaseColor(color);
        sliderAlphaRail.setBaseColor(color);
        if (fromGrid) {
            hueRail.setProgress(hsv[0] / 360f);
            saturationRail.setProgress(hsv[1]);
            lightnessRail.setProgress(hsv[2]);
        }
        gridAlphaRail.setProgress(alpha / 255f);
        sliderAlphaRail.setProgress(alpha / 255f);
        updateAlphaLabels();
        if (recentRow1 != null) {
            recentRow1.invalidate();
            recentRow2.invalidate();
        }
    }

    private void updateAlphaLabels() {
        String text = (alpha * 100 / 255) + "%";
        if (gridAlphaValue != null) {
            gridAlphaValue.setText(text);
        }
        if (sliderAlphaValue != null) {
            sliderAlphaValue.setText(text);
        }
    }

    private void addRecent(int value) {
        int rgb = value | 0xFF000000;
        for (int i = recentColors.size() - 1; i >= 0; i--) {
            if (((recentColors.get(i) ^ rgb) & 0xFFFFFF) == 0) {
                recentColors.remove(i);
            }
        }
        recentColors.add(0, rgb);
        while (recentColors.size() > MAX_RECENT) {
            recentColors.remove(recentColors.size() - 1);
        }
        rebuildRecent();
    }

    private void rebuildRecent() {
        if (recentRow1 != null) {
            recentRow1.invalidate();
            recentRow2.invalidate();
        }
    }

    private boolean sameRgb(int a, int b) {
        return ((a ^ b) & 0xFFFFFF) == 0;
    }

    private void onRailProgress(Rail rail, float progress) {
        switch (rail.mode) {
            case RAIL_HUE:
                hsv[0] = progress * 360f;
                applyHsv();
                break;
            case RAIL_SATURATION:
                hsv[1] = progress;
                applyHsv();
                break;
            case RAIL_LIGHTNESS:
                hsv[2] = progress;
                applyHsv();
                break;
            case RAIL_ALPHA:
                applyAlpha(progress, rail);
                break;
            default:
                break;
        }
    }

    private void onRailCommit(Rail rail) {
        if (rail.mode != RAIL_ALPHA) {
            addRecent(color);
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

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int desiredHeight = dp(PANEL_HEIGHT_DP);
        int height;
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(desiredHeight, heightSize);
        } else {
            height = desiredHeight;
        }

        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    // Utility helpers.

    private int argb() {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

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

    // Child views.

    /** The 13x9 board, a Java port of {@code ColorPickPanelView.kt}. */
    private final class Board extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF cell = new RectF();
        private int selected = DEFAULT_COLOR;

        Board(Context context) {
            super(context);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dpf(GRID_RING_DP));
            ringPaint.setColor(Color.WHITE);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(GRID_BORDER_WIDTH);
            borderPaint.setColor(gridBorderColor);
            setClickable(true);
        }

        void setColor(int value) {
            selected = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            float width = w;
            float height = h;
            float radius = dpf(GRID_RADIUS_DP);
            clipPath.reset();
            clipPath.addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW);

            int saved = canvas.save();
            canvas.clipPath(clipPath);
            float cellW = width / GRID_COLS;
            float cellH = height / GRID_ROWS;
            fillPaint.setStyle(Paint.Style.FILL);
            for (int r = 0; r < GRID_ROWS; r++) {
                for (int c = 0; c < GRID_COLS; c++) {
                    int index = r * GRID_COLS + c;
                    if (index >= PALETTE.length) {
                        break;
                    }
                    fillPaint.setColor(PALETTE[index]);
                    cell.set(c * cellW, r * cellH, (c + 1) * cellW, (r + 1) * cellH);
                    canvas.drawRect(cell, fillPaint);
                }
            }
            canvas.restoreToCount(saved);

            canvas.drawRoundRect(0f, 0f, width, height, radius, radius, borderPaint);

            int index = -1;
            for (int i = 0; i < PALETTE.length; i++) {
                if (PALETTE[i] == selected) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                int c = index % GRID_COLS;
                int r = index / GRID_COLS;
                float cx = (c + 0.5f) * cellW;
                float cy = (r + 0.5f) * cellH;
                float rr = Math.min(cellW, cellH) / 2f
                        - ringPaint.getStrokeWidth() / 2f - 0.5f * density;
                ringPaint.setColor(r == 0 ? GRID_ROW0_RING : Color.WHITE);
                int ringSave = canvas.save();
                canvas.clipPath(clipPath);
                canvas.drawCircle(cx, cy, rr, ringPaint);
                canvas.restoreToCount(ringSave);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    pickAt(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    return true;
                case MotionEvent.ACTION_UP:
                    pickAt(event.getX(), event.getY());
                    ColorPanel.this.addRecent(color);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ColorPanel.this.addRecent(color);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void pickAt(float x, float y) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            int c = (int) (x / (w / (float) GRID_COLS));
            int r = (int) (y / (h / (float) GRID_ROWS));
            if (c < 0 || c >= GRID_COLS || r < 0 || r >= GRID_ROWS) {
                return;
            }
            int index = r * GRID_COLS + c;
            if (index >= PALETTE.length) {
                return;
            }
            ColorPanel.this.pickColor(PALETTE[index], false);
        }
    }

    /**
     * Colour rail, a Java port of {@code PickerBar} plus the four subclasses:
     * 28dp round track, 24dp ring thumb, 8.14dp ring, 1.81dp travel inset.
     */
    private final class Rail extends View {
        private final int mode;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final Path clipPath = new Path();
        private float progress;
        private int baseColor = DEFAULT_COLOR;

        Rail(Context context, int mode) {
            super(context);
            this.mode = mode;
            ringPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStyle(Paint.Style.STROKE);
            setClickable(true);
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
                    : dp(SLIDER_HEIGHT_DP);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            if (w <= 0) {
                return;
            }
            float height = getHeight();
            float trackHeight = dpf(TRACK_HEIGHT_DP);
            float cy = height / 2f;
            float radius = trackHeight / 2f;
            trackRect.set(0f, cy - trackHeight / 2f, w, cy + trackHeight / 2f);

            if (mode == RAIL_ALPHA) {
                drawChecker(canvas, trackRect, radius);
            }

            Shader shader = null;
            switch (mode) {
                case RAIL_HUE:
                    shader = new LinearGradient(0f, 0f, w, 0f,
                            new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                                    Color.BLUE, Color.MAGENTA, Color.RED},
                            null, Shader.TileMode.CLAMP);
                    break;
                case RAIL_SATURATION: {
                    float[] base = new float[3];
                    Color.colorToHSV(baseColor, base);
                    int grey = Color.HSVToColor(new float[]{base[0], 0f, base[2]});
                    int full = Color.HSVToColor(new float[]{base[0], 1f, base[2]});
                    shader = new LinearGradient(0f, 0f, w, 0f, grey, full,
                            Shader.TileMode.CLAMP);
                    break;
                }
                case RAIL_LIGHTNESS: {
                    float[] base = new float[3];
                    Color.colorToHSV(baseColor, base);
                    int mid = Color.HSVToColor(new float[]{base[0], base[1], 1f});
                    shader = new LinearGradient(0f, 0f, w, 0f,
                            new int[]{Color.BLACK, mid, Color.WHITE}, null,
                            Shader.TileMode.CLAMP);
                    break;
                }
                case RAIL_ALPHA:
                    shader = new LinearGradient(0f, 0f, w, 0f,
                            Color.TRANSPARENT, baseColor | 0xFF000000,
                            Shader.TileMode.CLAMP);
                    break;
                default:
                    break;
            }

            if (shader != null) {
                trackPaint.setShader(shader);
                canvas.drawRoundRect(trackRect, radius, radius, trackPaint);
                trackPaint.setShader(null);
            } else {
                trackPaint.setColor(trackBg);
                canvas.drawRoundRect(trackRect, radius, radius, trackPaint);
            }

            borderPaint.setStrokeWidth(dpf(1.5f));
            borderPaint.setColor(trackBorder);
            canvas.drawRoundRect(trackRect, radius, radius, borderPaint);

            float thumbDiameter = dpf(THUMB_DIAMETER_DP);
            float thumbRadius = thumbDiameter / 2f;
            float margin = dpf(THUMB_MARGIN_DP);
            float travel = Math.max(0f, w - thumbDiameter - 2f * margin);
            float cx = margin + thumbRadius + progress * travel;
            float ringWidth = dpf(THUMB_RING_DP);
            float ringCentre = thumbRadius - ringWidth / 2f;

            thumbPaint.setStyle(Paint.Style.FILL);
            thumbPaint.setColor(mode == RAIL_HUE ? Color.TRANSPARENT : baseColor);
            canvas.drawCircle(cx, cy, ringCentre, thumbPaint);

            ringPaint.setStrokeWidth(ringWidth);
            ringPaint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, ringCentre, ringPaint);

            borderPaint.setStrokeWidth(dpf(THUMB_BORDER_DP));
            borderPaint.setColor(thumbBorder);
            canvas.drawCircle(cx, cy, thumbRadius - dpf(THUMB_BORDER_DP) / 2f, borderPaint);
        }

        private void drawChecker(Canvas canvas, RectF track, float radius) {
            float cell = Math.max(track.height() / 3f, 2f * density);
            int saved = canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(track, radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);
            int i = 0;
            for (float x = track.left; x < track.right; x += cell) {
                float right = Math.min(x + cell, track.right);
                checkerPaint.setColor(i % 2 == 0 ? CHECKER_A : CHECKER_B);
                canvas.drawRect(x, track.top, right, track.top + cell, checkerPaint);
                checkerPaint.setColor(i % 2 == 0 ? CHECKER_B : CHECKER_A);
                canvas.drawRect(x, track.top + cell, right, track.bottom, checkerPaint);
                i++;
            }
            canvas.restoreToCount(saved);
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
                    ColorPanel.this.onRailCommit(this);
                    lockParent(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ColorPanel.this.onRailCommit(this);
                    lockParent(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void updateFromX(float x) {
            float thumbDiameter = dpf(THUMB_DIAMETER_DP);
            float margin = dpf(THUMB_MARGIN_DP);
            float travel = getWidth() - thumbDiameter - 2f * margin;
            float value = travel <= 0f ? 0f : (x - margin - thumbDiameter / 2f) / travel;
            setProgress(value);
            ColorPanel.this.onRailProgress(this, progress);
        }

        private void lockParent(boolean lock) {
            ViewParent parent = getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(lock);
                parent = parent.getParent();
            }
        }
    }

    /** 72dp HUD block, a Java port of {@code ColorSwatchBlock.kt}. */
    private final class SwatchBlock extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int swatchColor = SWATCH_DEFAULT;

        SwatchBlock(Context context) {
            super(context);
        }

        void setColor(int value) {
            swatchColor = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(swatchColor);
            float radius = dpf(SWATCH_RADIUS_DP);
            canvas.drawRoundRect(0f, 0f, getWidth(), getHeight(), radius, radius, paint);
        }
    }

    /** One dotted recent row; the gap is spread from the measured width. */
    private final class RecentRow extends View {
        private final int rowIndex;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int downIndex = -1;

        RecentRow(Context context, int rowIndex) {
            super(context);
            this.rowIndex = rowIndex;
            setClickable(true);
        }

        private int count() {
            int available = recentColors.size() - rowIndex * RECENT_PER_ROW;
            return Math.max(0, Math.min(RECENT_PER_ROW, available));
        }

        private int colorAt(int index) {
            return recentColors.get(rowIndex * RECENT_PER_ROW + index);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int count = count();
            if (count == 0 || getWidth() <= 0) {
                return;
            }
            float dot = dpf(RECENT_DOT_DP);
            float gap = RECENT_GAP_DP * density;
            if (count > 1) {
                gap = (getWidth() - dot * count) / (count - 1);
            }
            if (gap < 0f) {
                gap = 0f;
            }
            float cy = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                int swatchColor = colorAt(i);
                float cx = i * (dot + gap) + dot / 2f;
                float radius = Math.min(dot, getHeight()) / 2f - 1f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(swatchColor);
                canvas.drawCircle(cx, cy, radius, paint);
                int lum = (299 * Color.red(swatchColor)
                        + 587 * Color.green(swatchColor)
                        + 114 * Color.blue(swatchColor)) / 1000;
                if (lum > 235) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(0.2f * density);
                    paint.setColor(0x66000000);
                    canvas.drawCircle(cx, cy, radius - 0.1f * density, paint);
                }
                if (sameRgb(swatchColor, color)) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.25f * density);
                    paint.setColor(Color.WHITE);
                    canvas.drawCircle(cx, cy, radius * 0.76f, paint);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int count = count();
            if (count == 0) {
                return true;
            }
            float dot = dpf(RECENT_DOT_DP);
            float gap = RECENT_GAP_DP * density;
            if (count > 1) {
                gap = (getWidth() - dot * count) / (count - 1);
            }
            if (gap < 0f) {
                gap = 0f;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downIndex = hitIndex(event.getX(), dot, gap, count);
                    return true;
                case MotionEvent.ACTION_UP:
                    int up = hitIndex(event.getX(), dot, gap, count);
                    if (up >= 0 && up == downIndex) {
                        ColorPanel.this.pickColor(colorAt(up), true);
                    }
                    downIndex = -1;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    downIndex = -1;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private int hitIndex(float x, float dot, float gap, int count) {
            if (getWidth() <= 0) {
                return -1;
            }
            int index = (int) (x / (dot + gap));
            if (index < 0 || index >= count) {
                return -1;
            }
            float left = index * (dot + gap);
            return x >= left && x <= left + dot ? index : -1;
        }
    }

    /** Tab callback; at outer level because Java 11 forbids static members in inner classes. */
    private interface TabListener {
        void onTabSelected(int index);
    }

    /** Miuix FilterSortView pill: white surface, black labels, selected fill. */
    private final class TabBar extends View {
        private final String[] labels = {"网格取色", "滑块取色"};
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        private TabListener tabListener;
        private int selected;

        TabBar(Context context) {
            super(context);
            strokePaint.setStyle(Paint.Style.STROKE);
            setClickable(true);
        }

        void setTabListener(TabListener value) {
            tabListener = value;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float radius = h / 2f;
            rect.set(0f, 0f, w, h);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(tabBg);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            strokePaint.setStrokeWidth(dpf(1f));
            strokePaint.setColor(tabStrokeColor);
            canvas.drawRoundRect(rect, radius, radius, strokePaint);

            float pad = dpf(TAB_PADDING_DP);
            float tabWidth = (w - 2f * pad) / 2f;
            rect.set(pad + selected * tabWidth, pad,
                    pad + (selected + 1) * tabWidth, h - pad);
            fillPaint.setColor(tabSelectedFill);
            float innerRadius = (h - 2f * pad) / 2f;
            canvas.drawRoundRect(rect, innerRadius, innerRadius, fillPaint);

            textPaint.setTextSize(sp(TAB_TEXT_SP));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(tabTextColor);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = h / 2f - (metrics.ascent + metrics.descent) / 2f;
            for (int i = 0; i < labels.length; i++) {
                textPaint.setTypeface(i == selected ? medium : regular);
                float cx = pad + (i + 0.5f) * tabWidth;
                canvas.drawText(labels[i], cx, baseline, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_UP:
                    int index = event.getX() < getWidth() / 2f ? 0 : 1;
                    if (index != selected) {
                        selected = index;
                        invalidate();
                        if (tabListener != null) {
                            tabListener.onTabSelected(index);
                        }
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }

    /** 44dp close box, the slot the old panel used for its eyedropper. */
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
