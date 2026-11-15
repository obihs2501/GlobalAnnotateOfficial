package com.globalannotate.official.panels;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.globalannotate.official.R;

import java.util.EnumMap;

/**
 * "笔身手势" settings card: maps the four pen-body gestures the LSPosed helper
 * broadcasts to one annotation action each.
 *
 * <p>Two levels, like the other picker cards. Level 1 lists the four gestures
 * as "手势 — 当前动作"; tapping one swaps the card to the action list for that
 * gesture, whose first row is the back row. Tapping an action reports it to
 * the listener, which persists it, and returns to level 1. The two levels
 * have different heights, so the card reports each swap through
 * {@link Listener#onContentHeightChanged()} and the service re-sizes the
 * panel window; this view never touches the WindowManager itself.</p>
 *
 * <p>All views are built in code (TextView / LinearLayout / ScrollView) like
 * the other panels. The card is fixed white like the brush and colour cards
 * (they float above any app and theme): #FFFFFFFF with 36dp corners and 24dp
 * padding, black / grey labels and the picker's #3482FF accent. The action
 * list scrolls when the window is clamped to a short screen.</p>
 */
public final class PenGesturePanel extends LinearLayout {

    /**
     * The four gestures. The enum names are the exact strings the helper
     * broadcasts as {@code EXTRA_GESTURE} and the service stores in prefs;
     * do not rename them.
     */
    public enum Gesture {
        SQUEEZE(R.string.ga_gesture_squeeze, Action.ARC_PALETTE),
        DOUBLE_TAP(R.string.ga_gesture_double_tap, Action.ARC_PALETTE),
        SLIDE_UP(R.string.ga_gesture_slide_up, Action.NONE),
        SLIDE_DOWN(R.string.ga_gesture_slide_down, Action.NONE);

        public final int labelRes;
        /** Used until the user binds an action to this gesture. */
        public final Action defaultAction;

        Gesture(int labelRes, Action defaultAction) {
            this.labelRes = labelRes;
            this.defaultAction = defaultAction;
        }

        public String label(Context context) {
            return context.getString(labelRes);
        }

        /** Resolves a helper broadcast name, or null when it is not ours. */
        public static Gesture fromName(String name) {
            if (name == null) {
                return null;
            }
            for (Gesture gesture : values()) {
                if (gesture.name().equals(name)) {
                    return gesture;
                }
            }
            return null;
        }
    }

    /** Actions a gesture can run; FloatingService executes them. */
    public enum Action {
        NONE(R.string.ga_action_none),
        ARC_PALETTE(R.string.ga_action_arc_palette),
        ERASER(R.string.ga_action_eraser),
        UNDO(R.string.ga_action_undo),
        REDO(R.string.ga_action_redo),
        SAVE_IMAGE(R.string.ga_action_save_image),
        SAVE_PDF(R.string.ga_action_save_pdf),
        CLEAR(R.string.ga_action_clear),
        EXIT(R.string.ga_action_exit);

        public final int labelRes;

        Action(int labelRes) {
            this.labelRes = labelRes;
        }

        public String label(Context context) {
            return context.getString(labelRes);
        }

        /** Resolves a stored action name, or null when it is unknown. */
        public static Action fromName(String name) {
            if (name == null) {
                return null;
            }
            for (Action action : values()) {
                if (action.name().equals(name)) {
                    return action;
                }
            }
            return null;
        }
    }

    /** Host callbacks; the service owns persistence and the panel window. */
    public interface Listener {
        /** The user picked {@code action} for {@code gesture}; persist it. */
        void onActionSelected(Gesture gesture, Action action);

        /** The card swapped levels; the panel window needs a new height. */
        void onContentHeightChanged();

        /** The user asked to close the card (outside tap is the host's job). */
        void onDismiss();
    }

    // Card box. The width is repeated in FloatingService before the view has
    // a parent, as the other panels' widths are.
    private static final float PANEL_WIDTH_DP = 300f;
    private static final float PANEL_RADIUS_DP = 36f;
    private static final int PANEL_PAD_DP = 24;
    private static final float TITLE_TEXT_SP = 16f;
    private static final int TITLE_GAP_DP = 12;

    // Rows.
    private static final int ROW_HEIGHT_DP = 48;
    private static final int ACTION_ROW_HEIGHT_DP = 44;
    private static final int ROW_GAP_DP = 8;
    private static final int ACTION_ROW_GAP_DP = 6;
    private static final float ROW_RADIUS_DP = 12f;
    private static final float ROW_TEXT_SP = 15f;
    private static final int ROW_PAD_H_DP = 12;

    // Fixed white palette, matching BrushPanel / ColorPanel.
    private static final int PANEL_BG = 0xFFFFFFFF;
    private static final int TEXT_PRIMARY = 0xFF000000;
    private static final int TEXT_SECONDARY = 0xCC000000;
    private static final int ACCENT = 0xFF3482FF;
    private static final int ROW_BG = 0x0F000000;
    private static final int ROW_PRESSED_BG = 0x1F000000;
    private static final int SELECTED_BG = 0x143482FF;
    private static final int SELECTED_PRESSED_BG = 0x293482FF;

    // Open/close animation, the same as the other panels.
    private static final long SHOW_SCALE_DURATION = 280L;
    private static final long SHOW_ALPHA_DURATION = 100L;
    private static final long HIDE_DURATION = 150L;
    private static final float CLOSED_SCALE = 0.6f;

    // Placement, the same arithmetic as the other panels.
    private static final int PLACEMENT_GAP_DP = 10;
    private static final int PLACEMENT_FALLBACK_GAP_DP = 8;
    private static final int PLACEMENT_TOP_INSET_DP = 12;
    private static final int SCREEN_EDGE_DP = 8;

    private final Listener listener;
    private final float density;
    private final EnumMap<Gesture, Action> actions = new EnumMap<>(Gesture.class);
    private final LinearLayout content;
    private int showEpoch;

    public PenGesturePanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;

        setOrientation(VERTICAL);
        setClickable(true);
        setClipToOutline(true);
        setElevation(dpf(8f));
        setPadding(dp(PANEL_PAD_DP), dp(PANEL_PAD_DP),
                dp(PANEL_PAD_DP), dp(PANEL_PAD_DP));

        final GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL_BG);
        background.setCornerRadius(dpf(PANEL_RADIUS_DP));
        setBackground(background);

        for (Gesture gesture : Gesture.values()) {
            actions.put(gesture, gesture.defaultAction);
        }

        final TextView title = new TextView(context);
        title.setText(R.string.ga_gesture_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SP);
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setLetterSpacing(0f);
        final LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(TITLE_GAP_DP);
        addView(title, titleLp);

        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        showGestures();
        setVisibility(GONE);
    }

    // Public API.

    /**
     * Binds an action without firing the listener; the service fills all four
     * from prefs before calling {@link #showGestures()}.
     */
    public void setAction(Gesture gesture, Action action) {
        if (gesture == null) {
            return;
        }
        actions.put(gesture, action != null ? action : Action.NONE);
    }

    public Action getAction(Gesture gesture) {
        return gesture != null ? actions.get(gesture) : null;
    }

    /** Shows level 1: the four gestures with their current actions. */
    public void showGestures() {
        content.removeAllViews();
        final Context context = getContext();
        for (final Gesture gesture : Gesture.values()) {
            final Action action = actions.get(gesture);
            final CharSequence text = gestureRowText(context, gesture,
                    action != null ? action : Action.NONE);
            final TextView row = makeRow(text, false);
            row.setContentDescription(text);
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showActions(gesture);
                }
            });
            content.addView(row, rowParams(ROW_HEIGHT_DP, ROW_GAP_DP));
        }
        notifyContentHeightChanged();
    }

    /** Natural height of the card at a given width. */
    public int measuredHeightForWidth(int widthPx) {
        final int width = widthPx > 0 ? widthPx : dp(PANEL_WIDTH_DP);
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return getMeasuredHeight();
    }

    /** Places the card above {@code anchorY}, centred on {@code anchorX}. */
    public void showAt(int anchorX, int anchorY, int yTop, int yBottom) {
        final int width = dp(PANEL_WIDTH_DP);
        final int height = measuredHeightForWidth(width);
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params != null) {
            params.width = width;
            params.height = height;
            setLayoutParams(params);
        }
        if (getParent() instanceof ViewGroup) {
            final ViewGroup parent = (ViewGroup) getParent();
            final int parentWidth = parent.getWidth() > 0 ? parent.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            final int minX = dp(SCREEN_EDGE_DP);
            final int maxX = Math.max(minX, parentWidth - width - dp(SCREEN_EDGE_DP));
            final int x = clampInt(anchorX - width / 2, minX, maxX);
            final int topLimit = yTop + dp(PLACEMENT_TOP_INSET_DP);
            final int bottomLimit = yBottom - height;
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

    // Level 2.

    private void showActions(Gesture gesture) {
        if (gesture == null) {
            return;
        }
        content.removeAllViews();
        final Context context = getContext();
        final TextView back = makeRow(context.getString(R.string.ga_gesture_back_format,
                gesture.label(context)), false);
        back.setTextColor(TEXT_SECONDARY);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showGestures();
            }
        });
        content.addView(back, rowParams(ROW_HEIGHT_DP, ROW_GAP_DP));

        final Action selectedAction = actions.get(gesture);
        for (final Action action : Action.values()) {
            final boolean selected = action == selectedAction;
            final String text = action.label(context) + (selected ? "  ✓" : "");
            final TextView row = makeRow(text, selected);
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(gesture, action);
                }
            });
            content.addView(row, rowParams(ACTION_ROW_HEIGHT_DP, ACTION_ROW_GAP_DP));
        }
        notifyContentHeightChanged();
    }

    private void select(Gesture gesture, Action action) {
        if (gesture == null || action == null) {
            return;
        }
        actions.put(gesture, action);
        if (listener != null) {
            listener.onActionSelected(gesture, action);
        }
        // The listener persists the binding; go back to the gesture list.
        showGestures();
    }

    // Layout construction.

    private CharSequence gestureRowText(Context context, Gesture gesture, Action action) {
        final SpannableStringBuilder text = new SpannableStringBuilder();
        appendColored(text, gesture.label(context), TEXT_PRIMARY);
        appendColored(text, " — ", TEXT_SECONDARY);
        appendColored(text, action.label(context), TEXT_SECONDARY);
        return text;
    }

    private static void appendColored(SpannableStringBuilder out, String part, int color) {
        final int start = out.length();
        out.append(part);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private TextView makeRow(CharSequence text, boolean selected) {
        final TextView row = new TextView(getContext());
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP);
        row.setTextColor(selected ? ACCENT : TEXT_PRIMARY);
        row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.setIncludeFontPadding(false);
        row.setLetterSpacing(0f);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setPadding(dp(ROW_PAD_H_DP), 0, dp(ROW_PAD_H_DP), 0);
        row.setBackground(rowBackground(selected));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    /** Rounded row wash: translucent white, blue-tinted when selected. */
    private Drawable rowBackground(boolean selected) {
        final GradientDrawable normal = new GradientDrawable();
        normal.setColor(selected ? SELECTED_BG : ROW_BG);
        normal.setCornerRadius(dpf(ROW_RADIUS_DP));
        final GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(selected ? SELECTED_PRESSED_BG : ROW_PRESSED_BG);
        pressed.setCornerRadius(dpf(ROW_RADIUS_DP));
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[0], normal);
        return states;
    }

    private LinearLayout.LayoutParams rowParams(int heightDp, int gapDp) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.bottomMargin = dp(gapDp);
        return lp;
    }

    private void notifyContentHeightChanged() {
        if (listener == null || !isAttachedToWindow()) {
            return;
        }
        // Let the click handler unwind before the window is re-measured.
        post(new Runnable() {
            @Override
            public void run() {
                if (isAttachedToWindow()) {
                    listener.onContentHeightChanged();
                }
            }
        });
    }

    // Placement and measurement.

    private void applyPosition(int x, int y) {
        final ViewGroup.LayoutParams params = getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
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
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final int desiredWidth = dp(PANEL_WIDTH_DP);
        final int width;
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

    @Override
    protected void onDetachedFromWindow() {
        showEpoch++;
        animate().cancel();
        super.onDetachedFromWindow();
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
}
