package com.globalannotate.official;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import xc.engine.cbridge.MiCurveNative;

/**
 * Hidden on-device probe for Mi Canvas' official ink engine (libmicurve.so).
 *
 * Start it from adb:
 *
 *   adb shell am start -n com.globalannotate.official/.MainActivity -e probe ink
 *
 * The probe is debug-only and never runs on the normal annotation path.
 *
 * It does three things:
 *  1. tries to load the proprietary engine (System.loadLibrary, then
 *     System.load from the app's files dir) and logs every attempt,
 *  2. builds a pressure-varying curved stroke through the native API
 *     (createLine -> insertPoints -> nativeGenCurveVertexBuffer ->
 *     nativeDrawVertexBuffer) inside a GLES2 GLSurfaceView, logging
 *     glGetError after every call,
 *  3. reads the framebuffer back to files/ink_probe.png and logs its path.
 *
 * Rendering is one-shot (RENDERMODE_WHEN_DIRTY): this build's
 * GenerateVertexBuffer never resets its vertex count, so the reuse path
 * (nativeGenCurveVertexBuffer(..., handle, true) + nativeDrawVertexBufferReuse)
 * appends another copy of the stroke on every frame. One generate/draw pair per
 * EGL surface avoids that growth; the presented frame stays on screen.
 *
 * If any step fails it logs loudly under tag "GAInk" and draws a procedural
 * placeholder stroke so a screenshot still distinguishes "engine failed" from
 * "nothing rendered".
 *
 * libmicurve.so is Xiaomi proprietary code and is deliberately NOT in the APK.
 * Push it onto the device first:
 *
 *   adb exec-out run-as com.globalannotate.official \
 *       sh -c 'cat > files/libmicurve.so' < libmicurve.so
 *
 * The native engine compiles no shaders of its own: it only imports
 * glVertexAttribPointer / glEnableVertexAttribArray / glDrawArrays. The caller
 * must supply the GL program and read the vertex format the engine produces:
 *
 *   attribute 0 (vec4): x, y, widthPx, penTypeExtra
 *   attribute 1 (float): rotateAngle
 *   attribute 2 (float): penType-4 tilt factor (0 for other pens)
 *
 * and it draws GL_POINTS; gl_PointSize must come from attribute 0's z.
 * Blending/depth state is also caller-owned.
 */
public final class InkProbeActivity extends Activity {

    static final String TAG = "GAInk";

    /**
     * PenTypeValue.INK_PEN in Mi Canvas (1=INK_PEN, 2=BALLPOINT, 3=PENCIL,
     * 4=MARK, 10=WATER_COLOR). INK_PEN is chosen because its per-point width is
     * pressure-dependent (see curveParam); BALLPOINT (2) and MARK (4) use a
     * constant width for the whole stroke, so they would not show the pressure
     * data travelling through the engine.
     */
    private static final int PEN_TYPE_INK = 1;

    private GLSurfaceView glView;
    private InkRenderer renderer;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final boolean engineLoaded = loadEngine();

        renderer = new InkRenderer(this, engineLoaded);
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(11f);
        status.setPadding(24, 24, 24, 24);
        status.setText(engineLoaded ? "engine loaded, waiting for GL..." : "engine NOT loaded, procedural fallback");

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(glView, match);
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        overlay.gravity = Gravity.TOP | Gravity.START;
        root.addView(status, overlay);
        setContentView(root);
    }

    /**
     * Load attempt (a): the normal library path, proves whether CI ever bundles
     * the .so (it must not, the repo is public).
     * Load attempt (b): the file pushed into the app's private files dir.
     * Android 14+ refuses to dlopen app-writable files, so the file is marked
     * read-only before System.load(); the exception text is logged either way.
     */
    private boolean loadEngine() {
        Log.i(TAG, "load step 1: System.loadLibrary(\"micurve\")");
        try {
            System.loadLibrary("micurve");
            Log.i(TAG, "load step 1 OK: libmicurve found in the APK/nativeLibraryDir");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "load step 1 FAILED: " + t, t);
        }

        final File lib = new File(getFilesDir(), "libmicurve.so");
        Log.i(TAG, "load step 2: file=" + lib.getAbsolutePath()
                + " exists=" + lib.isFile() + " len=" + lib.length()
                + " canRead=" + lib.canRead() + " canWrite=" + lib.canWrite()
                + " canExecute=" + lib.canExecute());
        if (!lib.isFile()) {
            Log.e(TAG, "load step 2 FAILED: file missing; use the adb exec-out/run-as recipe in the class javadoc");
            return false;
        }
        try {
            boolean readOnly = lib.setReadOnly();
            Log.i(TAG, "load step 2: setReadOnly=" + readOnly + " canWriteNow=" + lib.canWrite());
            System.load(lib.getAbsolutePath());
            Log.i(TAG, "load step 2 OK: System.load(" + lib.getAbsolutePath() + ")");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "load step 2 FAILED: " + t, t);
        }

        // Last resort: an injected copy with an alternate file name.
        final File alt = new File(getFilesDir(), "libmicurve_probe.so");
        if (alt.isFile()) {
            try {
                alt.setReadOnly();
                System.load(alt.getAbsolutePath());
                Log.i(TAG, "load step 3 OK: System.load(" + alt.getAbsolutePath() + ")");
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "load step 3 FAILED: " + t, t);
            }
        }
        Log.e(TAG, "load: no usable engine; the probe will draw the procedural fallback");
        return false;
    }

    void setStatusText(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (status != null) {
                    status.setText(text);
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glView != null) {
            glView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) {
            glView.onResume();
            // Re-present the stroke if the surface was recreated while paused.
            glView.requestRender();
        }
    }

    @Override
    protected void onDestroy() {
        if (renderer != null) {
            renderer.releaseNative();
        }
        super.onDestroy();
    }

    /** GLES2 renderer: owns the program, the native line and the readback. */
    private static final class InkRenderer implements GLSurfaceView.Renderer {

        private final InkProbeActivity host;
        private final Context context;
        private final boolean engineLoaded;

        private int program;
        private int uScreen = -1;
        private int uColor = -1;

        private int width = 1;
        private int height = 1;

        private long line;
        private boolean strokeBuilt;
        private boolean done;

        InkRenderer(InkProbeActivity host, boolean engineLoaded) {
            this.host = host;
            this.context = host.getApplicationContext();
            this.engineLoaded = engineLoaded;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            done = false;
            GLES20.glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            Log.i(TAG, "GL vendor=" + GLES20.glGetString(GLES20.GL_VENDOR)
                    + " renderer=" + GLES20.glGetString(GLES20.GL_RENDERER)
                    + " version=" + GLES20.glGetString(GLES20.GL_VERSION));
            final float[] range = new float[2];
            GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, range, 0);
            Log.i(TAG, "GL_ALIASED_POINT_SIZE_RANGE=[" + range[0] + ", " + range[1] + "]");
            drainGl("after GL setup");

            program = buildProgram();
            Log.i(TAG, "program=" + program + " err=" + drainGl("after program link"));
            if (program == 0) {
                Log.e(TAG, "shader program failed; probe cannot render anything");
                host.setStatusText("SHADER FAILED (see GAInk log)");
            }
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            width = Math.max(1, w);
            height = Math.max(1, h);
            GLES20.glViewport(0, 0, width, height);
            Log.i(TAG, "surface " + width + "x" + height);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            if (done) {
                return;
            }
            done = true;

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            final boolean inkOnScreen;
            if (engineLoaded && program != 0) {
                inkOnScreen = drawNativeInk();
            } else {
                inkOnScreen = false;
            }
            if (!inkOnScreen) {
                drawPlaceholder();
            }
            saveFrame();
        }

        /** Draws the stroke generated by libmicurve; returns true on success. */
        private boolean drawNativeInk() {
            try {
                if (line == 0L) {
                    line = MiCurveNative.createLine(0L);
                    strokeBuilt = false;
                    Log.i(TAG, "createLine(0L) -> " + hex(line)
                            + " err=" + drainGl("after createLine"));
                    if (line == 0L) {
                        return false;
                    }
                }
                if (!strokeBuilt) {
                    buildStroke();
                    strokeBuilt = true;
                }

                final long handle = MiCurveNative.nativeGenCurveVertexBuffer(
                        line, curveParam(), 0L, false);
                final int genErr = drainGl("after nativeGenCurveVertexBuffer");
                Log.i(TAG, "nativeGenCurveVertexBuffer(reuse=false) -> " + hex(handle)
                        + " err=" + genErr);
                if (handle == 0L) {
                    throw new IllegalStateException("nativeGenCurveVertexBuffer returned 0");
                }

                GLES20.glUseProgram(program);
                GLES20.glUniform2f(uScreen, width, height);
                GLES20.glUniform4f(uColor, 1.0f, 1.0f, 1.0f, 1.0f);
                drainGl("after uniforms");

                // Consumes and frees the vertex buffer (the engine's one-shot API).
                MiCurveNative.nativeDrawVertexBuffer(handle);
                final int drawErr = drainGl("after nativeDrawVertexBuffer");
                Log.i(TAG, "nativeDrawVertexBuffer(" + hex(handle) + ") err=" + drawErr);
                if (drawErr != GLES20.GL_NO_ERROR) {
                    throw new IllegalStateException("glGetError after draw: 0x" + Integer.toHexString(drawErr));
                }
                host.setStatusText("native ink OK\npoints=" + MiCurveNative.size(line)
                        + "\nvb=" + hex(handle));
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "native ink path FAILED: " + t, t);
                host.setStatusText("native ink FAILED: " + t);
                return false;
            }
        }

        /**
         * Builds one curved stroke, 240 points, pressure 2000..9000 (the engine
         * stores pressure as short = percent * 100, like Mi Curves' Java side).
         * With penType INK_PEN this pressure sweep shows up directly as the
         * per-point stamp width, so the rendered stroke thins out at both ends.
         * dataStatus MUST be 1: GenerateVertexBuffer skips any point whose
         * dataStatus != 1.
         */
        private void buildStroke() {
            final int n = 240;
            final float[] xs = new float[n];
            final float[] ys = new float[n];
            final float[] angles = new float[n];
            final short[] pressure = new short[n];
            final short[] tilt = new short[n];
            final short[] orientation = new short[n];
            final short[] velocity = new short[n];
            final short[] dataStatus = new short[n];
            final int[] time = new int[n];

            final float top = height * 0.22f;
            final float bottom = height * 0.78f;
            final float left = width * 0.12f;
            final float right = width * 0.88f;
            for (int i = 0; i < n; i++) {
                final float t = i / (float) (n - 1);
                xs[i] = left + (right - left) * t;
                ys[i] = (top + bottom) * 0.5f
                        + (bottom - top) * 0.5f * (float) Math.sin(t * Math.PI * 2.0);
                pressure[i] = (short) (2000 + 7000 * (float) Math.sin(t * Math.PI));
                tilt[i] = 3000;      // 30.00 degrees, >0 so penType 4 paths have tilt data
                orientation[i] = 0;
                velocity[i] = (short) (400 + 600 * (float) Math.sin(t * Math.PI));
                angles[i] = 0.0f;
                time[i] = i * 8;
                dataStatus[i] = 1;
            }

            MiCurveNative.insertPoints(line, n, xs, ys, pressure, tilt, time,
                    orientation, velocity, angles, dataStatus);
            Log.i(TAG, "insertPoints(" + n + ") -> size=" + MiCurveNative.size(line)
                    + " err=" + drainGl("after insertPoints"));
        }

        /**
         * FCurveParam, exactly the 10-float array Mi Canvas builds in
         * kd.l1l1II (CurveRenderer.draw). The native side copies it as
         *
         *   +0x00 int   penType          (array[0])
         *   +0x04 float penSizePx        (array[1])
         *   +0x08 float widthScale       (array[2], multiplies penSize)
         *   +0x0c float clipLeft         (array[4]; array[3] is NOT read)
         *   +0x10 float clipTop          (array[5])
         *   +0x14 float clipRight        (array[6])
         *   +0x18 float clipBottom       (array[7])
         *   +0x1c float penShape         (array[8], pressure exponent for INK_PEN)
         *   +0x20 float reserved         (array[9], unused by vertex generation)
         *   +0x24 bool  uniformPenSize   (only if array.length >= 11, array[10] != 0)
         *
         * For penType 1 (INK_PEN) GenerateVertexBuffer computes the per-point
         * width as widthScale * (penSize*0.96 * (pressure/100)^(1.34-penShape)
         * + penSize*0.039), clamped to at least 0.8px: penSize setup at
         * 0x30a18..0x30a68, per-point branch at 0x31508..0x31528. With
         * penSize 26 and penShape 0.5 a pressure sweep of 0.2..1.0 therefore
         * changes the stamp size from roughly 7px to 26px.
         */
        private float[] curveParam() {
            return new float[]{
                    PEN_TYPE_INK,         // penType
                    26.0f,                // penSizePx
                    1.0f,                 // widthScale
                    1.0f,                 // array[3]: screen ratio, ignored by native
                    0.0f,                 // clipLeft
                    0.0f,                 // clipTop
                    width,                // clipRight
                    height,               // clipBottom
                    0.5f,                 // penShape
                    0.0f                  // reserved
            };
        }

        /** Procedural fallback: same GL program, same 6-float vertex format. */
        private void drawPlaceholder() {
            if (program == 0) {
                return;
            }
            GLES20.glUseProgram(program);
            GLES20.glUniform2f(uScreen, width, height);
            GLES20.glUniform4f(uColor, 0.45f, 0.70f, 1.0f, 1.0f);

            final int n = 240;
            final FloatBuffer buffer = ByteBuffer.allocateDirect(n * 6 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            final float top = height * 0.30f;
            final float bottom = height * 0.70f;
            for (int i = 0; i < n; i++) {
                final float t = i / (float) (n - 1);
                final float x = width * 0.12f + width * 0.76f * t;
                final float y = (top + bottom) * 0.5f
                        + (bottom - top) * 0.5f * (float) Math.sin(t * Math.PI * 2.0);
                buffer.put(x).put(y).put(18.0f).put(1.0f).put(0.0f).put(0.0f);
            }
            buffer.position(0);
            GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 24, buffer);
            GLES20.glEnableVertexAttribArray(0);
            buffer.position(4);
            GLES20.glVertexAttribPointer(1, 1, GLES20.GL_FLOAT, false, 24, buffer);
            GLES20.glEnableVertexAttribArray(1);
            buffer.position(5);
            GLES20.glVertexAttribPointer(2, 1, GLES20.GL_FLOAT, false, 24, buffer);
            GLES20.glEnableVertexAttribArray(2);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n);
            Log.i(TAG, "placeholder stroke drawn (GL_POINTS, n=" + n + ") err="
                    + drainGl("after placeholder draw"));
        }

        private void saveFrame() {
            try {
                final int w = width;
                final int h = height;
                final ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4)
                        .order(ByteOrder.nativeOrder());
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE, pixels);
                final int err = drainGl("after glReadPixels");
                Log.i(TAG, "glReadPixels " + w + "x" + h + " err=" + err);

                final Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                pixels.position(0);
                raw.copyPixelsFromBuffer(pixels);
                final Matrix flip = new Matrix();
                flip.preScale(1.0f, -1.0f);
                final Bitmap upright = Bitmap.createBitmap(raw, 0, 0, w, h, flip, false);

                final File out = new File(context.getFilesDir(), "ink_probe.png");
                final FileOutputStream stream = new FileOutputStream(out);
                try {
                    upright.compress(Bitmap.CompressFormat.PNG, 100, stream);
                } finally {
                    stream.close();
                }
                Log.i(TAG, "PROBE_PNG " + out.getAbsolutePath() + " len=" + out.length());
                host.setStatusText("png: " + out.getAbsolutePath()
                        + "\nengine=" + (engineLoaded && program != 0));
            } catch (IOException e) {
                Log.e(TAG, "saving ink_probe.png failed: " + e, e);
            } catch (Throwable t) {
                Log.e(TAG, "readback failed: " + t, t);
            }
        }

        void releaseNative() {
            if (!engineLoaded) {
                return;
            }
            try {
                if (line != 0L) {
                    MiCurveNative.releaseLine(line);
                    Log.i(TAG, "releaseLine(" + hex(line) + ")");
                    line = 0L;
                }
            } catch (Throwable t) {
                Log.e(TAG, "releaseNative threw: " + t, t);
            }
        }

        // ---- GL helpers ----

        private int buildProgram() {
            final int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            final int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            if (vs == 0 || fs == 0) {
                return 0;
            }
            final int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            // Attribute locations are fixed by libmicurve's NativeDrawVertexBuffer.
            GLES20.glBindAttribLocation(p, 0, "aPos");
            GLES20.glBindAttribLocation(p, 1, "aRot");
            GLES20.glBindAttribLocation(p, 2, "aTilt");
            GLES20.glLinkProgram(p);
            final int[] linked = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0);
            Log.i(TAG, "link ok=" + (linked[0] != 0) + " log=" + GLES20.glGetProgramInfoLog(p));
            if (linked[0] == 0) {
                GLES20.glDeleteProgram(p);
                return 0;
            }
            uScreen = GLES20.glGetUniformLocation(p, "uScreen");
            uColor = GLES20.glGetUniformLocation(p, "uColor");
            Log.i(TAG, "uniform uScreen=" + uScreen + " uColor=" + uColor);
            return p;
        }

        private static int compile(int type, String source) {
            final int shader = GLES20.glCreateShader(type);
            if (shader == 0) {
                Log.e(TAG, "glCreateShader(" + type + ") failed");
                return 0;
            }
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            final int[] ok = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            Log.i(TAG, "compile type=" + type + " ok=" + (ok[0] != 0)
                    + " log=" + GLES20.glGetShaderInfoLog(shader));
            if (ok[0] == 0) {
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private static int drainGl(String where) {
            int err = GLES20.glGetError();
            if (err != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "glGetError " + where + " -> 0x" + Integer.toHexString(err));
            }
            return err;
        }

        private static String hex(long value) {
            return "0x" + Long.toHexString(value);
        }

        /**
         * Vertex format produced by MiCurveNative::GenerateVertexBuffer:
         * 6 floats per point, stride 24 bytes.
         *   aPos = (x, y, widthPx, penExtra), aRot = rotateAngle, aTilt = tiltFactor
         * The engine draws GL_POINTS, so the stamp size comes from gl_PointSize.
         */
        private static final String VERTEX_SHADER =
                "uniform vec2 uScreen;\n"
                + "attribute vec4 aPos;\n"
                + "attribute float aRot;\n"
                + "attribute float aTilt;\n"
                + "varying float vRot;\n"
                + "varying float vExtra;\n"
                + "void main() {\n"
                + "  gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0,\n"
                + "                     1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0);\n"
                + "  gl_PointSize = clamp(aPos.z, 1.0, 256.0);\n"
                + "  vRot = aRot;\n"
                + "  vExtra = aPos.w;\n"
                + "}\n";

        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n"
                + "uniform vec4 uColor;\n"
                + "varying float vRot;\n"
                + "varying float vExtra;\n"
                + "void main() {\n"
                + "  vec2 c = gl_PointCoord - vec2(0.5);\n"
                + "  float s = sin(vRot);\n"
                + "  float co = cos(vRot);\n"
                + "  vec2 r = vec2(c.x * co - c.y * s, c.x * s + c.y * co);\n"
                + "  float d = length(r) * 2.0;\n"
                + "  float a = 1.0 - smoothstep(0.70, 1.0, d);\n"
                + "  gl_FragColor = vec4(uColor.rgb, uColor.a * max(a, 0.0));\n"
                + "}\n";
    }
}
