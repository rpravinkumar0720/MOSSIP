package io.mosip.liveness.api.classifier;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of Presentation Attack Detection (PAD) evaluation on captured face frames.
 * Evaluates both machine-learned embeddings and multi-spectral/texture heuristics.
 */
public record PadResult(
        PadClassification classification,
        double attackScore,
        double confidence,
        Map<String, Double> heuristics,
        boolean isAttack
) {
    public PadResult {
        Objects.requireNonNull(classification, "classification cannot be null");
        heuristics = heuristics != null ? Map.copyOf(heuristics) : Collections.emptyMap();
    }

    public static PadResult genuine(double confidence) {
        return new PadResult(PadClassification.GENUINE, 0.0, confidence, Collections.emptyMap(), false);
    }

    public static PadResult attack(PadClassification classification, double attackScore,
                                   double confidence, Map<String, Double> heuristics) {
        return new PadResult(classification, attackScore, confidence, heuristics, true);
    }
}
