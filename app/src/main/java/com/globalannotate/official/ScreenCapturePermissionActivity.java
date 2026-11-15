package com.globalannotate.official;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Transparent consent hop for {@link ScreenCaptureSession}.
 *
 * <p>Android 14+ only allows a {@code MediaProjection} to be created while a
 * foreground service with the {@code mediaProjection} type is running, and the
 * consent result must be consumed immediately: the app's foreground service
 * starts this activity, the system dialog returns a one-shot token here, and
 * the token is forwarded straight to {@link FloatingService}, which holds the
 * virtual display for the rest of its run. Keeping the session instead of
 * asking again on every save is what makes repeated exports tolerable.</p>
 */
public final class ScreenCapturePermissionActivity extends Activity {

    private static final String TAG = "GaScreenPermission";
    private static final int REQUEST_CONSENT = 0x6A02;

    private boolean requested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The activity is transparent and has no content; the system dialog is
        // the whole UI. A recreated instance must not ask a second time.
        if (savedInstanceState != null || requested) {
            finish();
            return;
        }
        final MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Log.w(TAG, "MediaProjectionManager unavailable");
            finishWithCanceledResult();
            return;
        }
        final Intent consent;
        try {
            consent = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(
                            MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot create screen capture intent", error);
            finishWithCanceledResult();
            return;
        }
        requested = true;
        try {
            startActivityForResult(consent, REQUEST_CONSENT);
            overridePendingTransition(0, 0);
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot launch screen capture consent", error);
            finishWithCanceledResult();
        }
    }

    private void finishWithCanceledResult() {
        final Intent result = new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_CAPTURE_CONSENT_RESULT)
                .putExtra(FloatingService.EXTRA_CONSENT_RESULT_CODE, Activity.RESULT_CANCELED);
        try {
            startService(result);
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot notify service about capture cancellation", error);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CONSENT) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            finishWithCanceledResult();
            return;
        }
        final Intent result = new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_CAPTURE_CONSENT_RESULT)
                .putExtra(FloatingService.EXTRA_CONSENT_RESULT_CODE, resultCode)
                .putExtra(FloatingService.EXTRA_CONSENT_DATA, data);
        try {
            startService(result);
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot deliver consent result", error);
            finishWithCanceledResult();
            return;
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
