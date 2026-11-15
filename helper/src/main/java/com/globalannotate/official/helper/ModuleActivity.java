package com.globalannotate.official.helper;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings screen of the helper APK.
 *
 * <p>Every switch is mirrored into system_server by broadcast and persisted
 * there ({@link ModulePrefs}); the module starts working with the stored value
 * before any app process runs. The activity therefore only needs to display
 * the state and send changes — no boot receiver is involved, because HyperOS
 * often withholds BOOT_COMPLETED from apps without autostart permission.</p>
 */
public final class ModuleActivity extends Activity {

    private Switch penGestures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int pad = dp(24);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        final TextView status = new TextView(this);
        status.setText(R.string.module_status);
        status.setTextSize(14f);
        status.setAlpha(0.75f);
        content.addView(status, matchWrap(0));

        penGestures = new Switch(this);
        penGestures.setText(R.string.pen_gestures_title);
        penGestures.setTextSize(16f);
        penGestures.setChecked(HelperSettings.penGesturesEnabled(this));
        content.addView(penGestures, matchWrap(dp(28)));

        final TextView summary = new TextView(this);
        summary.setText(R.string.pen_gestures_summary);
        summary.setTextSize(13f);
        summary.setAlpha(0.7f);
        content.addView(summary, matchWrap(dp(8)));

        penGestures.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                HelperSettings.setPenGesturesEnabled(ModuleActivity.this, isChecked);
                HelperSettings.sendPenGestures(ModuleActivity.this, isChecked);
            }
        });

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-assert the stored value: this restores the system_server copy after
        // a module update or after the helper app's data was restored, without
        // depending on any boot receiver.
        HelperSettings.sendPenGestures(this, HelperSettings.penGesturesEnabled(this));
    }

    private static LinearLayout.LayoutParams matchWrap(int topMargin) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
