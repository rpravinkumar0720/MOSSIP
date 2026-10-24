package io.mosip.liveness.core.passive;

/**
 * Configurable weights for passive liveness and presentation attack detection (PAD) heuristic signals.
 * All weights sum to 1.0.
 */
public record PassiveWeights(
        double microMotionWeight,
        double moireWeight,
        double specularWeight,
        double textureWeight,
        double borderWeight,
        double timingWeight,
        double consistencyWeight
) {
    public static final PassiveWeights DEFAULT = new PassiveWeights(
            0.22, // micro-motion (non-rigid landmark residual)
            0.18, // moire / 2D FFT high frequency energy ratio
            0.14, // specular highlight / reflection ratio
            0.16, // color channel std dev & skin saturation
            0.10, // border / smartphone bezel detection
            0.10, // frame timing integrity / jitter
            0.10  // landmark-pixel skin consistency
    );

    public PassiveWeights {
        double sum = microMotionWeight + moireWeight + specularWeight +
                textureWeight + borderWeight + timingWeight + consistencyWeight;
        if (Math.abs(sum - 1.0) > 0.01) {
            // Normalize weights if not summing to 1.0
            microMotionWeight /= sum;
            moireWeight /= sum;
            specularWeight /= sum;
            textureWeight /= sum;
            borderWeight /= sum;
            timingWeight /= sum;
            consistencyWeight /= sum;
        }
    }

    public java.util.Map<String, Double> asMap() {
        return java.util.Map.of(
                "microMotion", microMotionWeight,
                "moire", moireWeight,
                "specular", specularWeight,
                "texture", textureWeight,
                "border", borderWeight,
                "timing", timingWeight,
                "consistency", consistencyWeight
        );
    }
}
