package com.globalannotate.official;

/**
 * miuix Folme's analytic spring, copied from
 * `miuix.animation.utils.SpringInterpolator`:
 *
 *   w = 2*pi/response, z = damping
 *   x(t) = 1 - e^(-z*w*t) * (cos(wd*t) + z*w/wd * sin(wd*t))
 *
 * The animator duration is the spring's settle time, so the curve is not
 * artificially compressed and its overshoot stays.
 */
public final class Spring implements android.animation.TimeInterpolator {

    private final double omega;
    private final double zeta;
    private final double omegaD;
    private final long durationMs;

    public Spring(float damping, float responseSeconds) {
        float response = Math.max(responseSeconds, 0.05f);
        omega = 2.0 * Math.PI / response;
        zeta = Math.max(0.1f, Math.min(2f, damping));
        omegaD = zeta < 1.0 ? omega * Math.sqrt(1.0 - zeta * zeta) : 0.0;
        durationMs = solveDurationSeconds();
    }

    public long durationMs() {
        return durationMs;
    }

    @Override
    public float getInterpolation(float input) {
        if (input <= 0f) return 0f;
        if (input >= 1f) return 1f;
        double t = (double) input * durationMs / 1000.0;
        return (float) valueAt(t);
    }

    private double valueAt(double t) {
        if (zeta >= 1.0) {
            return 1.0 - Math.exp(-omega * t) * (1.0 + omega * t);
        }
        double decay = Math.exp(-zeta * omega * t);
        return 1.0 - decay * (Math.cos(omegaD * t)
                + (zeta * omega / omegaD) * Math.sin(omegaD * t));
    }

    private double velocityAt(double t) {
        if (zeta >= 1.0) {
            return omega * omega * t * Math.exp(-omega * t);
        }
        return Math.exp(-zeta * omega * t) * (omega * omega / omegaD) * Math.sin(omegaD * t);
    }

    private long solveDurationSeconds() {
        double t = 0.0;
        while (t < 3.0) {
            t += 0.001;
            if (Math.abs(valueAt(t) - 1.0) <= 0.001 && Math.abs(velocityAt(t)) <= 5.0e-4) {
                return Math.max(80L, Math.round(t * 1000.0));
            }
        }
        return 600L;
    }
}
