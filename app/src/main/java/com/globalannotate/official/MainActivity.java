package com.globalannotate.official;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Launcher screen: one explanation, an overlay-permission status line and two
 * buttons. Everything is built in code so the project needs no layout files.
 */
public final class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 1001;
    /**
     * Set by the helper's last-resort activity fallback: start the overlay and
     * finish without showing the launcher screen (the helper expects the
     * activity to be a transparent trampoline).
     */
    private static final String EXTRA_AUTOSTART = "com.globalannotate.extra.AUTOSTART";

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent launch = getIntent();
        if (launch != null && launch.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            startAnnotation();
            finish();
            return;
        }
        // GaMainTheme is translucent; this soft panel keeps the launcher
        // visible behind the small control surface.
        getWindow().setBackgroundDrawable(new ColorDrawable(0xE6F7F7FA));
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildContent() {
        final int pad = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.rgb(0x11, 0x11, 0x11));
        title.setTextSize(26);
        title.setGravity(Gravity.START);
        root.addView(title, matchWrap());

        TextView explanation = new TextView(this);
        explanation.setText(R.string.ga_explanation);
        explanation.setTextColor(0xCC000000);
        explanation.setTextSize(15);
        explanation.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams explanationLp = matchWrap();
        explanationLp.topMargin = dp(14);
        root.addView(explanation, explanationLp);

        status = new TextView(this);
        status.setTextColor(0x99000000);
        status.setTextSize(14);
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.topMargin = dp(18);
        root.addView(status, statusLp);

        Button start = new Button(this);
        start.setText(R.string.ga_start);
        start.setAllCaps(false);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAnnotation();
            }
        });
        LinearLayout.LayoutParams startLp = matchWrap();
        startLp.topMargin = dp(24);
        root.addView(start, startLp);

        Button stop = new Button(this);
        stop.setText(R.string.ga_exit);
        stop.setAllCaps(false);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopAnnotation();
            }
        });
        LinearLayout.LayoutParams stopLp = matchWrap();
        stopLp.topMargin = dp(10);
        root.addView(stop, stopLp);

        // HyperOS freezes background apps aggressively; an overlay that dies
        // when another app comes forward is useless, so offer the exemption.
        Button battery = new Button(this);
        battery.setText(R.string.ga_battery_button);
        battery.setAllCaps(false);
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });
        LinearLayout.LayoutParams batteryLp = matchWrap();
        batteryLp.topMargin = dp(10);
        root.addView(battery, batteryLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void refreshStatus() {
        if (status == null) {
            return;
        }
        final boolean granted = Settings.canDrawOverlays(this);
        status.setText(getString(R.string.ga_status_format,
                getString(granted ? R.string.ga_overlay_granted : R.string.ga_overlay_missing)));
        status.setTextColor(granted ? 0xCC1B7F3A : 0xCCB3261E);
    }

    private void startAnnotation() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.ga_overlay_needed, Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }

        requestNotificationPermission();

        final Intent intent = new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_START);
        try {
            startForegroundService(intent);
            Toast.makeText(this, R.string.ga_service_started, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.ga_service_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void stopAnnotation() {
        final Intent intent = new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_STOP);
        try {
            // The service is already foreground; a plain startService is enough
            // to deliver the stop action.
            startService(intent);
            Toast.makeText(this, R.string.ga_service_stopped, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            stopService(new Intent(this, FloatingService.class));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    private void openOverlaySettings() {
        final Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    /**
     * Opens the system's battery-optimisation exemption dialog. On HyperOS the
     * same screen also exposes "后台运行" / autostart controls.
     */
    private void openBatterySettings() {
        try {
            final Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                Toast.makeText(this, R.string.ga_battery_missing, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
