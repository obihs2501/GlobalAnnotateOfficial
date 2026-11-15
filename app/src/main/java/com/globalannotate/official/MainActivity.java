package com.globalannotate.official;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Launcher trampoline: tapping the app icon starts the annotation overlay and
 * closes immediately, like Mi Canvas' floating button. There is deliberately
 * no home screen — the overlay's own ball is the UI. The system overlay
 * permission and the notification permission are the only things that can
 * interrupt the start; both are requested here and the activity finishes as
 * soon as they are answered.
 */
public final class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 1001;

    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Debug-only hook for the official Mi Canvas ink engine probe; the
        // normal launch path below is untouched. Trigger with:
        //   adb shell am start -n com.globalannotate.official/.MainActivity -e probe ink
        final Intent probeIntent = getIntent();
        if (probeIntent != null && "ink".equals(probeIntent.getStringExtra("probe"))) {
            startActivity(new Intent(this, InkProbeActivity.class));
            finish();
            return;
        }
        if (needsNotificationPermission()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
            return;
        }
        launchAndFinish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            // Granted or not, the overlay can run; the notification is only a
            // convenience for stopping it.
            launchAndFinish();
        }
    }

    private boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void launchAndFinish() {
        if (launched) {
            return;
        }
        launched = true;
        startAnnotation();
        finish();
    }

    private void startAnnotation() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.ga_overlay_needed, Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        final Intent intent = new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_START);
        try {
            startForegroundService(intent);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.ga_service_failed, Toast.LENGTH_LONG).show();
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
}
