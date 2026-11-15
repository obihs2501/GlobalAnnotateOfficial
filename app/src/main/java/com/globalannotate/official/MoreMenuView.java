package com.globalannotate.official;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;

/**
 * Standalone Java port of the Miuix "more" popup that Mi Canvas' pen picker
 * shows next to the capsule ({@code p249l1lII1Il.l111I}, inflated from
 * {@code penpicker_more_action_view}). The official popup is a 200dp floating
 * card with 24dp corners, 6dp item margins, text rows
 * ({@code more_action_item_view}) and switch rows
 * ({@code more_action_checked_item_view}), its two lists split by a 1dp line.
 * This class keeps that card, margins and row skeleton, and follows the
 * brief's 1dp divider between every row. It is filled with the GlobalAnnotate
 * actions from the product spec.
 *
 * <p>Constant map (official resource -&gt; value):</p>
 * <pre>
 * penpicker_common_edit_popup_window_width ....... 200dp (l111I.lilI1Il)
 * penpicker_more_action_background_radius ........ 24dp  (l111I.lilI1Il)
 * more item start/end margin ..................... 6dp   (l111I.I1l1l1Ii)
 * first/last item vertical padding ............... 6dp   (l111I.m25539IIii1I)
 * penpicker_more_action_item text size ........... 16sp  (style)
 *                                                  (15sp here, brief's 14-15sp)
 * penpicker_more_item_text_color ................. #FF000000 / night #CCFFFFFF
 * penpicker_more_item_press_bg ................... #1A000000, 16dp corners
 * penpicker_dividing_line_color .................. #1A000000 / night #1AFFFFFF
 * more_action_checked_text_color ................. #FF3482FF
 * penpicker_background_color ..................... #FFFFFFFF
 * </pre>
 *
 * <p>The official row height and switch dimensions cannot be recovered from
 * the stripped APK (its {@code res/*.xml} entries are absent). The brief pins
 * rows to 48-52dp; this port uses 48dp, the Miuix list-row height, so the
 * ten-row card measures about 501dp (6 + 10 x 48 + 9 x 1 + 6), before
 * per-device pixel rounding. The icons and
 * the 40x24dp switch are drawn in code because the official drawables are
 * resources. Day/night colours are picked once in
 * {@link #onAttachedToWindow()}, like the other hand-built panels.</p>
 *
 * <p>Dismissal: this view is exactly the card, so a tap outside its bounds is
 * never delivered to it. The host owns outside-dismiss and
 * {@link Listener#onDismiss()} is never invoked from here. Action rows only
 * report {@link Listener#onAction(Action)}; they do not hide the menu, so the
 * host can decide whether an action closes it.</p>
 *
 * <p>Placement and show/hide timing mirror {@code panels/ColorPanel} and
 * {@code panels/BrushPanel}: centred on the anchor, above it, clamped into the
 * visible band with the same 8/10/12dp insets, 280ms scale-in, 100ms fade-in
 * and 150ms scale-out.</p>
 */
public final class MoreMenuView extends LinearLayout {

    /** Host callbacks. A row tap reports one action; a switch tap one setting. */
    public interface Listener {
        void onAction(Action action);

        void onToggle(Setting setting, boolean enabled);

        /** Outside taps are the host's job; see the class comment. */
        void onDismiss();
    }

    /** The six action rows, in card order. */
    public enum Action {
        PEN_GESTURES("笔身手势"),
        LONG_SCREENSHOT("长截图"),
        SAVE_IMAGE("保存图片"),
        SAVE_PDF("保存 PDF"),
        CLEAR_NOTES("清空批注"),
        EXIT_ANNOTATION("退出批注");

        public final String label;

        Action(String label) {
            this.label = label;
        }
    }

    /** The three supported switch rows, in card order. All default to off. */
    public enum Setting {
        FINGER_DRAW("手指涂画"),
        FINGER_FOLLOW("手指跟随"),
        EVENT_PASSTHROUGH("事件透传");

        public final String label;

        Setting(String label) {
            this.label = label;
        }
    }

    // Card box, from the official MoreActionPopupWindow.
    private static final float CARD_WIDTH_DP = 200f;
    private static final float CARD_RADIUS_DP = 24f;
    private static final int CARD_PAD_VERTICAL_DP = 6;

    // Rows. The official item style only gives a 21dp minHeight; the brief's
    // 48-52dp band is used for the tappable row instead.
    private static final int ROW_HEIGHT_DP = 48;
    private static final int ROW_MARGIN_H_DP = 6;
    private static final int ROW_PAD_H_DP = 10;
    // Official penpicker_more_item_press_bg: 16dp corners, #1A000000 pressed.
    private static final float ROW_RADIUS_DP = 16f;
    private static final float LABEL_TEXT_SP = 15f;

    // Leading 24dp glyph slot, so action and switch labels share one edge.
    private static final int ICON_SIZE_DP = 24;
    private static final int ICON_GAP_DP = 10;
    private static final float ICON_STROKE_DP = 1.5f;

    // Switch, invented: Miuix-like 40x24dp track, 18dp thumb, 3dp inset.
    private static final int SWITCH_WIDTH_DP = 40;
    private static final int SWITCH_HEIGHT_DP = 24;
    private static final float SWITCH_THUMB_DP = 18f;
    private static final float SWITCH_INSET_DP = 3f;
    private static final long TOGGLE_DURATION = 160L;

    // Divider, from creation_more_popwindow_line.
    private static final int DIVIDER_INSET_DP = 16;
    private static final float DIVIDER_HEIGHT_DP = 1f;

    // Light palette.
    private static final int CARD_LIGHT = 0xFFFFFFFF;
    private static final int TEXT_LIGHT = 0xFF000000;
    private static final int ICON_LIGHT = 0xCC000000;
    private static final int DIVIDER_LIGHT = 0x1A000000;
    private static final int PRESSED_LIGHT = 0x1A000000;
    private static final int TRACK_OFF_LIGHT = 0xFFE3E3E3;
    private static final int THUMB_SHADOW_LIGHT = 0x1A000000;

    // Dark palette. Text and line values are the official night colours.
    private static final int CARD_DARK = 0xFF2B2B2B;
    private static final int TEXT_DARK = 0xCCFFFFFF;
    private static final int ICON_DARK = 0xCCFFFFFF;
    private static final int DIVIDER_DARK = 0x1AFFFFFF;
    private static final int PRESSED_DARK = 0x1AFFFFFF;
    private static final int TRACK_OFF_DARK = 0x33FFFFFF;
    private static final int THUMB_SHADOW_DARK = 0x33000000;

    // Shared accents.
    private static final int ACCENT = 0xFF3482FF;
    private static final int THUMB = 0xFFFFFFFF;

    // Open/close animation, from AnnotationService.showPanel/closePanels.
    private static final long SHOW_SCALE_DURATION = 280L;
    private static final long SHOW_ALPHA_DURATION = 100L;
    private static final long HIDE_DURATION = 150L;
    private static final float CLOSED_SCALE = 0.6f;

    // Placement, from AnnotationService.showPanel (same as ColorPanel).
    private static final int PLACEMENT_GAP_DP = 10;
    private static final int PLACEMENT_FALLBACK_GAP_DP = 8;
    private static final int PLACEMENT_TOP_INSET_DP = 12;
    private static final int SCREEN_EDGE_DP = 8;

    private final Listener listener;
    private final float density;
    private final Typeface labelTypeface;
    private final boolean[] settings = new boolean[Setting.values().length];

    private final EnumMap<Setting, Toggle> toggleViews =
            new EnumMap<>(Setting.class);
    private final EnumMap<Setting, Row> toggleRows =
            new EnumMap<>(Setting.class);
    private final ArrayList<TextView> labelViews = new ArrayList<>();
    private final ArrayList<View> dividerViews = new ArrayList<>();
    private final ArrayList<Row> rows = new ArrayList<>();
    private final ArrayList<Glyph> glyphs = new ArrayList<>();
    private final GradientDrawable cardBackground = new GradientDrawable();

    private int cardColor = CARD_LIGHT;
    private int labelColor = TEXT_LIGHT;
    private int iconColor = ICON_LIGHT;
    private int dividerColor = DIVIDER_LIGHT;
    private int pressedColor = PRESSED_LIGHT;
    private int trackOffColor = TRACK_OFF_LIGHT;
    private int thumbShadowColor = THUMB_SHADOW_LIGHT;
    private int showEpoch;

    public MoreMenuView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        this.labelTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        setOrientation(VERTICAL);
        setClickable(true);
        setFocusable(true);
        setClipToOutline(true);
        setElevation(dpf(8f));
        setPadding(0, dp(CARD_PAD_VERTICAL_DP), 0, dp(CARD_PAD_VERTICAL_DP));

        cardBackground.setColor(cardColor);
        cardBackground.setCornerRadius(dpf(CARD_RADIUS_DP));
        setBackground(cardBackground);

        buildRows(context);
        applyTheme(false);
        setVisibility(GONE);
    }

    // Public API.

    /**
     * Sets a switch without firing {@link Listener#onToggle}. Useful for
     * syncing the card with the service's persistent settings.
     */
    public void setToggle(Setting setting, boolean enabled) {
        if (setting == null) {
            return;
        }
        applyToggle(setting, enabled, false);
    }

    public boolean getToggle(Setting setting) {
        return setting != null && settings[setting.ordinal()];
    }

    /** Natural height of the card at a given width (old popup height = 0). */
    public int measuredHeightForWidth(int widthPx) {
        int width = widthPx > 0 ? widthPx : dp(CARD_WIDTH_DP);
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return getMeasuredHeight();
    }

    /**
     * Places the card above {@code anchorY}, centred on {@code anchorX}.
     * {@code yTop}/{@code yBottom} are the usable vertical bounds in screen
     * coordinates; the clamping is the same as {@code ColorPanel.showAt}.
     */
    public void showAt(int anchorX, int anchorY, int yTop, int yBottom) {
        int width = dp(CARD_WIDTH_DP);
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

    /** Plays the 150ms exit; does not fire {@link Listener#onDismiss()}. */
    public void hide() {
        final int epoch = ++showEpoch;
        animate().cancel();
        if (getVisibility() != VISIBLE) {
            setVisibility(GONE);
            return;
        }
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

    private void buildRows(Context context) {
        boolean first = true;
        for (Action action : Action.values()) {
            if (!first) {
                addDivider(context);
            }
            addView(buildActionRow(context, action), rowParams());
            first = false;
        }
        for (Setting setting : Setting.values()) {
            if (!first) {
                addDivider(context);
            }
            addView(buildToggleRow(context, setting), rowParams());
            first = false;
        }
    }

    private Row buildActionRow(Context context, Action action) {
        Row row = new Row(context);
        row.setPressColor(pressedColor);
        row.setContentDescription(action.label);
        row.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        Glyph glyph = new Glyph(context, action);
        glyph.setColor(iconColor);
        row.addView(glyph, new LinearLayout.LayoutParams(
                dp(ICON_SIZE_DP), dp(ICON_SIZE_DP)));

        TextView label = makeLabel(action.label);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMarginStart(dp(ICON_GAP_DP));
        row.addView(label, labelLp);

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onAction(action);
                }
            }
        });

        rows.add(row);
        labelViews.add(label);
        glyphs.add(glyph);
        return row;
    }

    private Row buildToggleRow(Context context, final Setting setting) {
        Row row = new Row(context);
        row.setPressColor(pressedColor);
        row.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        Space lead = new Space(context);
        row.addView(lead, new LinearLayout.LayoutParams(
                dp(ICON_SIZE_DP), dp(ICON_SIZE_DP)));

        TextView label = makeLabel(setting.label);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMarginStart(dp(ICON_GAP_DP));
        row.addView(label, labelLp);

        final Toggle toggle = new Toggle(context);
        toggle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        toggle.setChecked(settings[setting.ordinal()], false);
        row.addView(toggle, new LinearLayout.LayoutParams(
                dp(SWITCH_WIDTH_DP), dp(SWITCH_HEIGHT_DP)));

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSetting(setting);
            }
        });

        rows.add(row);
        labelViews.add(label);
        toggleViews.put(setting, toggle);
        toggleRows.put(setting, row);
        applyToggle(setting, settings[setting.ordinal()], false);
        return row;
    }

    private void addDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(dividerColor);
        divider.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(DIVIDER_HEIGHT_DP)));
        lp.setMarginStart(dp(DIVIDER_INSET_DP));
        lp.setMarginEnd(dp(DIVIDER_INSET_DP));
        addView(divider, lp);
        dividerViews.add(divider);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP));
        lp.setMarginStart(dp(ROW_MARGIN_H_DP));
        lp.setMarginEnd(dp(ROW_MARGIN_H_DP));
        return lp;
    }

    private TextView makeLabel(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SP);
        view.setTextColor(labelColor);
        view.setTypeface(labelTypeface);
        view.setIncludeFontPadding(false);
        view.setLetterSpacing(0f);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    // Switch state.

    private void toggleSetting(Setting setting) {
        boolean enabled = !settings[setting.ordinal()];
        applyToggle(setting, enabled, true);
        if (listener != null) {
            listener.onToggle(setting, enabled);
        }
    }

    private void applyToggle(Setting setting, boolean enabled, boolean animate) {
        settings[setting.ordinal()] = enabled;
        Toggle toggle = toggleViews.get(setting);
        if (toggle != null) {
            toggle.setChecked(enabled, animate);
        }
        Row row = toggleRows.get(setting);
        if (row != null) {
            row.setContentDescription(setting.label
                    + (enabled ? "，已开启" : "，已关闭"));
        }
    }

    // Theme.

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Configuration configuration = getResources().getConfiguration();
        boolean dark = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        applyTheme(dark);
    }

    @Override
    protected void onDetachedFromWindow() {
        showEpoch++;
        animate().cancel();
        for (Toggle toggle : toggleViews.values()) {
            toggle.cancelAnimation();
        }
        super.onDetachedFromWindow();
    }

    private void applyTheme(boolean dark) {
        cardColor = dark ? CARD_DARK : CARD_LIGHT;
        labelColor = dark ? TEXT_DARK : TEXT_LIGHT;
        iconColor = dark ? ICON_DARK : ICON_LIGHT;
        dividerColor = dark ? DIVIDER_DARK : DIVIDER_LIGHT;
        pressedColor = dark ? PRESSED_DARK : PRESSED_LIGHT;
        trackOffColor = dark ? TRACK_OFF_DARK : TRACK_OFF_LIGHT;
        thumbShadowColor = dark ? THUMB_SHADOW_DARK : THUMB_SHADOW_LIGHT;

        cardBackground.setColor(cardColor);
        for (TextView label : labelViews) {
            label.setTextColor(labelColor);
        }
        for (View divider : dividerViews) {
            divider.setBackgroundColor(dividerColor);
        }
        for (Row row : rows) {
            row.setPressColor(pressedColor);
        }
        for (Glyph glyph : glyphs) {
            glyph.setColor(iconColor);
        }
        for (Toggle toggle : toggleViews.values()) {
            toggle.invalidate();
        }
        invalidate();
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
        int desiredWidth = dp(CARD_WIDTH_DP);
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

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int blend(int from, int to, float t) {
        float f = clampFloat(t, 0f, 1f);
        int a = Math.round(Color.alpha(from)
                + (Color.alpha(to) - Color.alpha(from)) * f);
        int r = Math.round(Color.red(from)
                + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from)
                + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from)
                + (Color.blue(to) - Color.blue(from)) * f);
        return Color.argb(a, r, g, b);
    }

    // Child views.

    /** A 48dp row with the Miuix rounded press wash drawn under its content. */
    private final class Row extends LinearLayout {
        private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pressColor = PRESSED_LIGHT;

        Row(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBaselineAligned(false);
            setPadding(dp(ROW_PAD_H_DP), 0, dp(ROW_PAD_H_DP), 0);
            setClickable(true);
            setFocusable(true);
            setWillNotDraw(false);
            pressPaint.setStyle(Paint.Style.FILL);
        }

        void setPressColor(int color) {
            pressColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (isPressed() || isFocused()) {
                pressPaint.setColor(pressColor);
                float radius = dpf(ROW_RADIUS_DP);
                canvas.drawRoundRect(0f, 0f, getWidth(), getHeight(),
                        radius, radius, pressPaint);
            }
            super.onDraw(canvas);
        }
    }

    /** Miuix-style switch: 40x24dp track, 18dp thumb, 3dp inset. */
    private final class Toggle extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private float progress;
        private ValueAnimator animator;

        Toggle(Context context) {
            super(context);
        }

        void setChecked(boolean value, boolean animate) {
            float target = value ? 1f : 0f;
            cancelAnimation();
            if (!animate || !isAttachedToWindow()
                    || Math.abs(progress - target) < 0.0001f) {
                progress = target;
                invalidate();
                return;
            }
            final ValueAnimator valueAnimator = ValueAnimator.ofFloat(progress, target);
            valueAnimator.setDuration(TOGGLE_DURATION);
            valueAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    progress = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            animator = valueAnimator;
            valueAnimator.start();
        }

        void cancelAnimation() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float trackHeight = Math.min(height, dpf(SWITCH_HEIGHT_DP));
            float top = (height - trackHeight) / 2f;
            trackRect.set(0f, top, width, top + trackHeight);
            float radius = trackHeight / 2f;

            trackPaint.setColor(blend(trackOffColor, ACCENT, progress));
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

            float thumbRadius = dpf(SWITCH_THUMB_DP) / 2f;
            float inset = dpf(SWITCH_INSET_DP);
            float travel = Math.max(0f, width - 2f * inset - 2f * thumbRadius);
            float cx = inset + thumbRadius + progress * travel;
            float cy = height / 2f;

            shadowPaint.setColor(thumbShadowColor);
            canvas.drawCircle(cx, cy + dpf(0.5f), thumbRadius + dpf(0.5f),
                    shadowPaint);
            thumbPaint.setColor(THUMB);
            canvas.drawCircle(cx, cy, thumbRadius, thumbPaint);
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelAnimation();
            super.onDetachedFromWindow();
        }
    }

    /** A 24dp outlined glyph, drawn in a 20x20 design box scaled to the slot. */
    private final class Glyph extends View {
        private final Action action;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();
        private int color = ICON_LIGHT;

        Glyph(Context context, Action action) {
            super(context);
            this.action = action;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int value) {
            color = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float scale = Math.min(width, height) / 20f;
            float ox = (width - 20f * scale) / 2f;
            float oy = (height - 20f * scale) / 2f;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);

            int saved = canvas.save();
            canvas.translate(ox, oy);
            canvas.scale(scale, scale);
            switch (action) {
                case PEN_GESTURES:
                    paint.setStrokeWidth(2f);
                    canvas.drawLine(6.4f, 13.6f, 15f, 5f, paint);
                    canvas.drawLine(4.6f, 15.4f, 7.6f, 12.4f, paint);
                    paint.setStrokeWidth(ICON_STROKE_DP);
                    path.reset();
                    path.moveTo(3f, 18f);
                    path.quadTo(7f, 19.2f, 10f, 18f);
                    path.quadTo(13f, 16.8f, 17f, 18f);
                    canvas.drawPath(path, paint);
                    break;
                case LONG_SCREENSHOT:
                    paint.setStrokeWidth(ICON_STROKE_DP);
                    oval.set(4.5f, 2.5f, 15.5f, 17.5f);
                    canvas.drawRoundRect(oval, 2f, 2f, paint);
                    path.reset();
                    path.moveTo(10f, 6f);
                    path.lineTo(10f, 12.2f);
                    path.moveTo(7.6f, 9.8f);
                    path.lineTo(10f, 12.2f);
                    path.lineTo(12.4f, 9.8f);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(7.4f, 15.2f, 12.6f, 15.2f, paint);
                    break;
                case SAVE_IMAGE:
                    paint.setStrokeWidth(ICON_STROKE_DP);
                    oval.set(2.5f, 4.5f, 17.5f, 15.5f);
                    canvas.drawRoundRect(oval, 2f, 2f, paint);
                    canvas.drawCircle(13.2f, 8f, 1.3f, paint);
                    path.reset();
                    path.moveTo(4.6f, 13.8f);
                    path.lineTo(8.2f, 10.2f);
                    path.lineTo(11f, 13f);
                    path.lineTo(13.2f, 10.8f);
                    path.lineTo(16f, 13.6f);
                    canvas.drawPath(path, paint);
                    break;
                case SAVE_PDF:
                    paint.setStrokeWidth(ICON_STROKE_DP);
                    path.reset();
                    path.moveTo(5f, 2.5f);
                    path.lineTo(11.8f, 2.5f);
                    path.lineTo(15.5f, 6.2f);
                    path.lineTo(15.5f, 17.5f);
                    path.lineTo(5f, 17.5f);
                    path.close();
                    canvas.drawPath(path, paint);
                    canvas.drawLine(11.8f, 2.5f, 11.8f, 6.2f, paint);
                    canvas.drawLine(11.8f, 6.2f, 15.5f, 6.2f, paint);
                    canvas.drawLine(7.4f, 9.6f, 13f, 9.6f, paint);
                    canvas.drawLine(7.4f, 12.2f, 13f, 12.2f, paint);
                    canvas.drawLine(7.4f, 14.8f, 10.8f, 14.8f, paint);
                    break;
                case CLEAR_NOTES:
                    paint.setStrokeWidth(ICON_STROKE_DP);
                    canvas.drawLine(4.4f, 6f, 15.6f, 6f, paint);
                    path.reset();
                    path.moveTo(8f, 6f);
                    path.lineTo(8f, 3.8f);
                    path.lineTo(12f, 3.8f);
                    path.lineTo(12f, 6f);
                    canvas.drawPath(path, paint);
                    path.reset();
                    path.moveTo(6f, 6f);
                    path.lineTo(6.7f, 17f);
                    path.lineTo(13.3f, 17f);
                    path.lineTo(14f, 6f);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(8.6f, 8.8f, 8.8f, 14.2f, paint);
                    canvas.drawLine(11.4f, 8.8f, 11.2f, 14.2f, paint);
                    break;
                case EXIT_ANNOTATION:
                    paint.setStrokeWidth(1.6f);
                    oval.set(4.6f, 4.6f, 15.4f, 15.4f);
                    canvas.drawArc(oval, -60f, 300f, false, paint);
                    canvas.drawLine(10f, 2.8f, 10f, 9.2f, paint);
                    break;
                default:
                    break;
            }
            canvas.restoreToCount(saved);
        }
    }
}
