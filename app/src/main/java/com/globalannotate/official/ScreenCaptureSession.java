package com.globalannotate.official;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * A MediaProjection session that survives across saves.
 *
 * <p>Android 14+ makes the consent token single-use and ties a projection to
 * exactly one virtual display, so the only way to avoid the "start recording
 * or casting?" dialog on every save is to keep that one virtual display alive
 * for the whole service run. The display mirrors the screen into an
 * {@link ImageReader} continuously; frames are drained and dropped until a
 * capture is requested, at which point the first frame produced after the
 * request is returned.</p>
 *
 * <p>The reader queue is drained immediately before each request, and the
 * request is matched against ImageReader timestamps only. A nudge callback is
 * available for platforms that do not publish a frame for a static display.</p>
 */
public final class ScreenCaptureSession {

    private static final String TAG = "GAScreenCapture";
    private static final String DISPLAY_NAME = "GlobalAnnotateScreenCapture";
    private static final int MAX_IMAGES = 2;
    private static final long NUDGE_DELAY_MS = 450L;
    private static final long CAPTURE_TIMEOUT_MS = 5000L;

    /** Receives one captured frame (or an error) on the main thread. */
    public interface FrameListener {
        void onFrame(Bitmap bitmap);

        void onError(String message);
    }

    private static final class Pending {
        final long afterTimestampNs;
        final FrameListener listener;
        Runnable nudgeTask;
        Runnable timeoutTask;

        Pending(long afterTimestampNs, FrameListener listener) {
            this.afterTimestampNs = afterTimestampNs;
            this.listener = listener;
        }
    }

    private final MediaProjection projection;
    private final VirtualDisplay virtualDisplay;
    private final MediaProjection.Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable onStopped;
    private ImageReader reader;
    private int width;
    private int height;
    private int dpi;
    private boolean stopped;
    private boolean released;
    private long framesSeen;
    private long lastImageTimestampNs;
    private Pending pending;

    private final ImageReader.OnImageAvailableListener imageListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader source) {
                    onImage(source);
                }
            };

    private ScreenCaptureSession(MediaProjection projection, VirtualDisplay display,
                                 MediaProjection.Callback callback, int width, int height,
                                 int dpi, ImageReader reader, Runnable onStopped) {
        this.projection = projection;
        this.virtualDisplay = display;
        this.callback = callback;
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.reader = reader;
        this.onStopped = onStopped;
        reader.setOnImageAvailableListener(imageListener, handler);
    }

    /**
     * Exchanges a consent result for a live session. Returns null when the
     * system refuses the token (stale result, missing foreground service type,
     * ...). Must be called on the main thread.
     */
    public static ScreenCaptureSession start(Context context, int resultCode, Intent resultData,
                                             int width, int height, int dpi,
                                             Runnable onStopped) {
        final int w = Math.max(1, width);
        final int h = Math.max(1, height);
        final int d = Math.max(1, dpi);
        final MediaProjectionManager manager =
                (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Log.w(TAG, "MediaProjectionManager unavailable");
            return null;
        }
        final MediaProjection projection;
        try {
            projection = manager.getMediaProjection(resultCode, resultData);
        } catch (RuntimeException error) {
            Log.w(TAG, "getMediaProjection failed", error);
            return null;
        }
        if (projection == null) {
            Log.w(TAG, "getMediaProjection returned null");
            return null;
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        final ScreenCaptureSession[] holder = new ScreenCaptureSession[1];
        final MediaProjection.Callback callback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "MediaProjection stopped by the system or the user");
                ScreenCaptureSession session = holder[0];
                if (session != null) {
                    session.markStopped();
                }
                if (onStopped != null) {
                    onStopped.run();
                }
            }
        };
        // Android 14+ requires a callback before createVirtualDisplay().
        projection.registerCallback(callback, handler);

        final ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES);
        final VirtualDisplay display;
        try {
            display = projection.createVirtualDisplay(DISPLAY_NAME, w, h, d,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (RuntimeException error) {
            Log.w(TAG, "createVirtualDisplay failed", error);
            disposeQuietly(reader, projection, callback);
            return null;
        }
        if (display == null) {
            Log.w(TAG, "createVirtualDisplay returned null");
            disposeQuietly(reader, projection, callback);
            return null;
        }

        ScreenCaptureSession session = new ScreenCaptureSession(projection, display, callback,
                w, h, d, reader, onStopped);
        holder[0] = session;
        Log.i(TAG, "session started " + w + "x" + h + "@" + d);
        return session;
    }

    private static void disposeQuietly(ImageReader reader, MediaProjection projection,
                                       MediaProjection.Callback callback) {
        try {
            reader.close();
        } catch (RuntimeException ignored) {
            // Best effort.
        }
        try {
            projection.unregisterCallback(callback);
        } catch (RuntimeException ignored) {
            // Best effort.
        }
        try {
            projection.stop();
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }

    public boolean isStopped() {
        return stopped;
    }

    /**
     * Rebinds the existing reader surface to make a static virtual display
     * publish a frame. This never changes application windows or reveals the
     * annotation overlay.
     */
    public void nudge() {
        if (stopped || released || reader == null) {
            return;
        }
        try {
            virtualDisplay.setSurface(null);
            virtualDisplay.setSurface(reader.getSurface());
        } catch (RuntimeException error) {
            Log.w(TAG, "virtual display nudge failed", error);
        }
    }

    /**
     * Requests the first whole-display frame produced after this call. Must be
     * called on the main thread. The listener is always called exactly once,
     * on the main thread, either with a frame or with an error.
     *
     * @param nudge invoked once if no frame arrived after a short delay; it
     *              should refresh the virtual display without changing the
     *              visible screen
     */
    public void capture(int width, int height, int dpi, Runnable nudge, FrameListener listener) {
        if (stopped) {
            listener.onError("session stopped");
            return;
        }
        if (pending != null) {
            listener.onError("capture already in progress");
            return;
        }
        final Pending request;
        try {
            ensureSize(Math.max(1, width), Math.max(1, height), Math.max(1, dpi));
            drainImages();
            request = new Pending(lastImageTimestampNs, listener);
        } catch (RuntimeException error) {
            listener.onError("virtual display resize failed");
            return;
        }
        pending = request;
        request.nudgeTask = new Runnable() {
            @Override
            public void run() {
                if (pending != request || nudge == null) {
                    return;
                }
                Log.i(TAG, "no frame after " + NUDGE_DELAY_MS + "ms; nudging compositor");
                try {
                    nudge.run();
                } catch (RuntimeException ignored) {
                    // A failed nudge only costs the capture timeout.
                }
            }
        };
        request.timeoutTask = new Runnable() {
            @Override
            public void run() {
                if (pending != request) {
                    return;
                }
                pending = null;
                listener.onError("capture timeout");
            }
        };
        handler.postDelayed(request.nudgeTask, NUDGE_DELAY_MS);
        handler.postDelayed(request.timeoutTask, CAPTURE_TIMEOUT_MS);
    }

    /** Cancels a pending request (used when the caller gives up first). */
    public void cancelCapture() {
        Pending request = pending;
        if (request == null) {
            return;
        }
        pending = null;
        handler.removeCallbacks(request.nudgeTask);
        handler.removeCallbacks(request.timeoutTask);
    }

    /** Drops all frames that were queued before a capture request. */
    private void drainImages() {
        Image image;
        while ((image = reader.acquireLatestImage()) != null) {
            lastImageTimestampNs = Math.max(lastImageTimestampNs, image.getTimestamp());
            image.close();
        }
    }

    private void onImage(ImageReader source) {
        if (source != reader) {
            final Image stale = source.acquireLatestImage();
            if (stale != null) {
                stale.close();
            }
            return;
        }
        final Image image = source.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            framesSeen++;
            lastImageTimestampNs = Math.max(lastImageTimestampNs, image.getTimestamp());
            final Pending request = pending;
            if (request == null) {
                return;
            }
            if (image.getTimestamp() <= request.afterTimestampNs) {
                return;
            }
            pending = null;
            handler.removeCallbacks(request.nudgeTask);
            handler.removeCallbacks(request.timeoutTask);
            final Bitmap bitmap;
            try {
                bitmap = toBitmap(image, width, height);
            } catch (RuntimeException error) {
                Log.w(TAG, "frame conversion failed", error);
                request.listener.onError("frame conversion failed");
                return;
            }
            request.listener.onFrame(bitmap);
        } finally {
            image.close();
        }
    }

    /** Converts the RGBA_8888 plane to an ARGB_8888 bitmap, cropping row padding. */
    private static Bitmap toBitmap(Image image, int width, int height) {
        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();
        final int rowPadding = rowStride - pixelStride * width;
        final int paddedWidth = width + Math.max(0, rowPadding / Math.max(1, pixelStride));
        buffer.rewind();
        Bitmap padded = null;
        Bitmap cropped = null;
        try {
            padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            if (paddedWidth == width) {
                final Bitmap result = padded;
                padded = null;
                return result;
            }
            cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(cropped);
            canvas.drawBitmap(padded, new Rect(0, 0, width, height),
                    new Rect(0, 0, width, height), null);
            final Bitmap result = cropped;
            cropped = null;
            return result;
        } finally {
            if (padded != null && !padded.isRecycled()) {
                padded.recycle();
            }
            if (cropped != null && !cropped.isRecycled()) {
                cropped.recycle();
            }
        }
    }

    private void ensureSize(int w, int h, int d) {
        if (w == width && h == height && d == dpi) {
            return;
        }
        Log.i(TAG, "resize " + width + "x" + height + " -> " + w + "x" + h + "@" + d);
        final ImageReader replacement = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888,
                MAX_IMAGES);
        replacement.setOnImageAvailableListener(imageListener, handler);
        try {
            virtualDisplay.resize(w, h, d);
            virtualDisplay.setSurface(replacement.getSurface());
        } catch (RuntimeException error) {
            Log.w(TAG, "virtual display resize failed", error);
            replacement.setOnImageAvailableListener(null, null);
            replacement.close();
            throw error;
        }
        final ImageReader old = reader;
        reader = replacement;
        width = w;
        height = h;
        dpi = d;
        old.setOnImageAvailableListener(null, null);
        old.close();
    }

    private void markStopped() {
        stopped = true;
        final Pending request = pending;
        pending = null;
        if (request != null) {
            handler.removeCallbacks(request.nudgeTask);
            handler.removeCallbacks(request.timeoutTask);
            request.listener.onError("projection stopped");
        }
        releaseResources();
    }

    /** Releases every native resource; safe to call more than once. */
    public void release() {
        if (released) {
            return;
        }
        stopped = true;
        final Pending request = pending;
        pending = null;
        if (request != null) {
            handler.removeCallbacks(request.nudgeTask);
            handler.removeCallbacks(request.timeoutTask);
            request.listener.onError("session released");
        }
        releaseResources();
        Log.i(TAG, "session released after " + framesSeen + " frames");
    }

    private void releaseResources() {
        if (released) {
            return;
        }
        released = true;
        final ImageReader currentReader = reader;
        reader = null;
        if (currentReader != null) {
            currentReader.setOnImageAvailableListener(null, null);
        }
        try {
            virtualDisplay.setSurface(null);
        } catch (RuntimeException ignored) {
            // Best effort.
        }
        try {
            virtualDisplay.release();
        } catch (RuntimeException ignored) {
            // Best effort.
        }
        try {
            projection.unregisterCallback(callback);
        } catch (RuntimeException ignored) {
            // Best effort.
        }
        try {
            projection.stop();
        } catch (RuntimeException ignored) {
            // Best effort.
        }
        if (currentReader != null) {
            try {
                currentReader.close();
            } catch (RuntimeException ignored) {
                // Best effort.
            }
        }
    }
}
