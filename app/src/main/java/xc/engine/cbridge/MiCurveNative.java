package xc.engine.cbridge;

/**
 * JNI stub for Mi Canvas' curve/ink engine (libmicurve.so), copied verbatim
 * from the decompiled official app (class xc.engine.cbridge.MiCurveNative).
 *
 * The method names and signatures MUST stay byte-for-byte identical: the
 * library exports classic static JNI symbols such as
 * Java_xc_engine_cbridge_MiCurveNative_createLine__J and resolves natives by
 * these exact mangled names.
 *
 * Difference from the official copy: the `static { System.loadLibrary("micurve"); }`
 * block was removed. The probe loads the library explicitly (see
 * InkProbeActivity#loadEngine) so it can try several paths and log why each
 * one fails; loading it twice through a static initializer would turn a
 * recoverable UnsatisfiedLinkError into a NoClassDefFoundError.
 *
 * IMPORTANT: libmicurve.so must never be committed to this repository (the
 * repo is public and the .so is Xiaomi proprietary code). The probe copies it
 * onto the device at runtime, e.g.:
 *
 *   adb exec-out run-as com.globalannotate.official \
 *       sh -c 'cat > files/libmicurve.so' < libmicurve.so
 */
public class MiCurveNative {

    public static native void addLine(long j10, long j11);

    public static native void clearLine(long j10);

    public static native long createLine(long j10);

    public static native void duplicatePoints(long j10, long j11);

    public static native void getLine(long j10, float[] fArr, float[] fArr2, short[] sArr, short[] sArr2, int[] iArr, short[] sArr3, short[] sArr4, float[] fArr3, short[] sArr5);

    public static native void insertPoint(long j10, float f10, float f11, short s10, short s11, int i10, short s12, short s13, float f12, short s14);

    public static native void insertPoints(long j10, int i10, float[] fArr, float[] fArr2, short[] sArr, short[] sArr2, int[] iArr, short[] sArr3, short[] sArr4, float[] fArr3, short[] sArr5);

    public static native void interpolateCurve(long j10, long j11, int i10, float f10, float f11);

    public static native void nativeDebugPrintCurvePoints(long j10, int i10);

    public static native void nativeDrawVertexBuffer(long j10);

    public static native void nativeDrawVertexBufferReuse(long j10);

    public static native long nativeGenCurveVertexBuffer(long j10, float[] fArr, long j11, boolean z10);

    public static native void nativeReleaseVertexBuffer(long j10);

    public static native void releaseLine(long j10);

    public static native void resetDataStatus(long j10);

    public static native void setDataStatus(long j10, short[] sArr, int i10, int i11);

    public static native void setPointX(long j10, float[] fArr, int i10, int i11);

    public static native void setPointY(long j10, float[] fArr, int i10, int i11);

    public static native void setRotateAngle(long j10, float[] fArr, int i10, int i11);

    public static native int size(long j10);
}
