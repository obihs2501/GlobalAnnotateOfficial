package com.globalannotate.official;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes annotated screenshots to shared storage.
 *
 * <p>PNG files are inserted into {@link MediaStore.Images} under
 * {@code Pictures/GlobalAnnotate}. PDF files are inserted into
 * {@link MediaStore.Downloads} under {@code Download/GlobalAnnotate}. Rows are
 * created with {@code IS_PENDING=1} and published only after all bytes have
 * been written, so a failed export never leaves a half-written visible file.
 * The app targets API 33+, so the scoped-storage MediaStore APIs are used and
 * no storage permission is needed.</p>
 *
 * <p>All methods run synchronously on the calling thread and start no threads
 * of their own. Callers that must not block a UI thread should dispatch the
 * work themselves.</p>
 */
public final class Exporter {

    private static final String TAG = "GAExporter";

    private static final String PNG_MIME_TYPE = "image/png";
    private static final String PDF_MIME_TYPE = "application/pdf";

    /** MediaStore collection-relative destinations (API 29+). */
    private static final String PNG_RELATIVE_PATH =
            Environment.DIRECTORY_PICTURES + "/GlobalAnnotate";
    private static final String PDF_RELATIVE_PATH =
            Environment.DIRECTORY_DOWNLOADS + "/GlobalAnnotate";

    /** A4 measured at 72 points per inch. Pages stay inside this box. */
    private static final int A4_SHORT_SIDE_POINTS = 595;
    private static final int A4_LONG_SIDE_POINTS = 842;

    private static final int MAX_PDF_NAME_LENGTH = 120;

    private Exporter() {
        // No instances.
    }

    /**
     * Writes the composited image as a PNG into {@code Pictures/GlobalAnnotate}.
     * When only one bitmap is supplied it is compressed directly, avoiding an
     * unnecessary full-size copy (important for long screenshots).
     *
     * @param context    any context; only its content resolver is used
     * @param ink        the transparent annotation layer, may be {@code null}
     * @param background the captured screen, may be {@code null} only when
     *                   {@code ink} is not
     * @return the inserted MediaStore Uri, or {@code null} on failure
     */
    public static Uri savePng(Context context, Bitmap ink, Bitmap background) {
        if (context == null) {
            Log.w(TAG, "savePng: context is null");
            return null;
        }
        if (ink == null && background == null) {
            Log.w(TAG, "savePng: no ink or background bitmap");
            return null;
        }

        Bitmap flattened = null;
        boolean ownsFlattened = false;
        Uri uri = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) {
                Log.w(TAG, "savePng: content resolver is unavailable");
                return null;
            }

            if (background != null && ink != null) {
                flattened = flatten(background, ink);
                ownsFlattened = true;
            } else {
                flattened = background != null ? background : ink;
            }
            if (flattened == null) {
                Log.w(TAG, "savePng: flatten returned null");
                return null;
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "GlobalAnnotate_" + timestamp() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, PNG_MIME_TYPE);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, PNG_RELATIVE_PATH);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "savePng: MediaStore insert returned null");
                return null;
            }

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("openOutputStream returned null");
                }
                if (!flattened.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("Bitmap.compress returned false");
                }
            }

            ContentValues published = new ContentValues(1);
            published.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, published, null, null);

            Log.d(TAG, "savePng: wrote " + uri);
            return uri;
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "savePng: write failed", e);
            deleteQuietly(context, uri);
            return null;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "savePng: MediaStore rejected the request", e);
            deleteQuietly(context, uri);
            return null;
        } finally {
            if (ownsFlattened) {
                recycleQuietly(flattened);
            }
        }
    }

    /**
     * Writes a one-page PDF containing {@code ink} over {@code background}
     * into {@code Download/GlobalAnnotate}.
     *
     * <p>The page keeps the source aspect ratio. Native bitmap dimensions are
     * treated as points (72 dpi) and proportionally reduced when necessary so
     * the page fits inside an A4 box; small sources keep their native size.</p>
     *
     * @param context    any context; only its content resolver is used
     * @param name       optional display name; ".pdf" is appended when missing
     * @param background the captured screen, may be {@code null} only when
     *                   {@code ink} is not
     * @param ink        the transparent annotation layer, may be {@code null}
     * @return the inserted MediaStore Uri, or {@code null} on failure
     */
    public static Uri savePdf(Context context, String name, Bitmap background, Bitmap ink) {
        if (context == null) {
            Log.w(TAG, "savePdf: context is null");
            return null;
        }
        if (background == null && ink == null) {
            Log.w(TAG, "savePdf: no background or ink bitmap");
            return null;
        }

        Bitmap flattened = null;
        PdfDocument document = null;
        Uri uri = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) {
                Log.w(TAG, "savePdf: content resolver is unavailable");
                return null;
            }

            flattened = flatten(background, ink);
            if (flattened == null) {
                Log.w(TAG, "savePdf: flatten returned null");
                return null;
            }

            int bitmapWidth = flattened.getWidth();
            int bitmapHeight = flattened.getHeight();
            if (bitmapWidth <= 0 || bitmapHeight <= 0) {
                Log.w(TAG, "savePdf: flattened bitmap has no pixels");
                return null;
            }

            int[] pageSize = pdfPageSize(bitmapWidth, bitmapHeight);
            int pageWidth = pageSize[0];
            int pageHeight = pageSize[1];

            document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                    pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            Paint filterPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(flattened, null,
                    new Rect(0, 0, pageWidth, pageHeight), filterPaint);
            document.finishPage(page);

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, pdfDisplayName(name));
            values.put(MediaStore.MediaColumns.MIME_TYPE, PDF_MIME_TYPE);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, PDF_RELATIVE_PATH);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "savePdf: MediaStore insert returned null");
                return null;
            }

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("openOutputStream returned null");
                }
                document.writeTo(out);
            }

            ContentValues published = new ContentValues(1);
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, published, null, null);

            Log.d(TAG, "savePdf: wrote " + uri);
            return uri;
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "savePdf: write failed", e);
            deleteQuietly(context, uri);
            return null;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "savePdf: MediaStore rejected the request", e);
            deleteQuietly(context, uri);
            return null;
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (RuntimeException e) {
                    Log.w(TAG, "savePdf: PdfDocument.close failed", e);
                }
            }
            recycleQuietly(flattened);
        }
    }

    /**
     * Draws {@code root} into a new software-rendered ARGB_8888 bitmap.
     *
     * @return the rendered bitmap, or {@code null} for invalid input or when
     *         the view refuses to draw
     */
    public static Bitmap renderFullScreen(View root, int width, int height) {
        if (root == null) {
            Log.w(TAG, "renderFullScreen: root view is null");
            return null;
        }
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "renderFullScreen: invalid size " + width + "x" + height);
            return null;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            root.draw(canvas);
            return bitmap;
        } catch (IllegalStateException e) {
            Log.e(TAG, "renderFullScreen: software draw failed", e);
            return null;
        }
    }

    /**
     * Returns a new bitmap of the background's size with {@code ink} drawn on
     * top. A differently sized ink layer is scaled to the background's bounds
     * so both stay registered. The inputs are never recycled.
     *
     * @param background the base layer, may be {@code null} when {@code ink}
     *                   is not
     * @param ink        the overlay layer, may be {@code null}
     * @return the composited bitmap, or {@code null} when both inputs are null
     *         or drawing fails
     */
    public static Bitmap flatten(Bitmap background, Bitmap ink) {
        if (background == null && ink == null) {
            Log.w(TAG, "flatten: both bitmaps are null");
            return null;
        }

        Bitmap base = background != null ? background : ink;
        int width = base.getWidth();
        int height = base.getHeight();
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "flatten: source bitmap has no pixels");
            return null;
        }

        try {
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);

            if (background != null) {
                // The result already matches the background's size.
                canvas.drawBitmap(background, 0f, 0f, null);
            }
            if (ink != null) {
                if (ink.getWidth() == width && ink.getHeight() == height) {
                    canvas.drawBitmap(ink, 0f, 0f, null);
                } else {
                    Paint filterPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(ink, null,
                            new Rect(0, 0, width, height), filterPaint);
                }
            }
            return result;
        } catch (IllegalStateException e) {
            Log.e(TAG, "flatten: unable to composite bitmaps", e);
            return null;
        }
    }

    /**
     * Page size for the PDF in points. The bitmap is treated as 72 dpi, then
     * scaled down proportionally only when it would exceed A4's bounds.
     */
    private static int[] pdfPageSize(int width, int height) {
        float longSide = Math.max(width, height);
        float shortSide = Math.min(width, height);
        float scale = 1f;
        if (longSide > A4_LONG_SIDE_POINTS) {
            scale = A4_LONG_SIDE_POINTS / longSide;
        }
        if (shortSide * scale > A4_SHORT_SIDE_POINTS) {
            scale = Math.min(scale, A4_SHORT_SIDE_POINTS / shortSide);
        }
        int pageWidth = Math.max(1, Math.round(width * scale));
        int pageHeight = Math.max(1, Math.round(height * scale));
        return new int[]{pageWidth, pageHeight};
    }

    /** Builds a safe MediaStore display name ending in ".pdf". */
    private static String pdfDisplayName(String name) {
        String base = name == null ? "" : name.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty()) {
            base = "GlobalAnnotate_" + timestamp();
        }
        if (!base.toLowerCase(Locale.US).endsWith(".pdf")) {
            base = base + ".pdf";
        }
        if (base.length() > MAX_PDF_NAME_LENGTH) {
            base = base.substring(0, MAX_PDF_NAME_LENGTH - 4) + ".pdf";
        }
        return base;
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    /** Removes a pending MediaStore row after a failed write. */
    private static void deleteQuietly(Context context, Uri uri) {
        if (context == null || uri == null) {
            return;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver != null) {
                resolver.delete(uri, null, null);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cleanup: failed to delete pending row " + uri, e);
        }
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        try {
            bitmap.recycle();
        } catch (RuntimeException e) {
            Log.w(TAG, "cleanup: failed to recycle scratch bitmap", e);
        }
    }
}
