package io.mosip.liveness.api.classifier;

import io.mosip.liveness.api.quality.BoundingBox;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.FaceQuality;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Result of passive liveness and presentation attack analysis over single or windowed frames.
 */
public record PassiveLivenessResult(
        double livenessScore,
        PadResult padResult,
        FaceQuality quality,
        BoundingBox boundingBox,
        FaceLandmarks landmarks,
        boolean isLive,
        long latencyNanos,
        Map<String, String> metadata
) {
    public PassiveLivenessResult {
        Objects.requireNonNull(padResult, "padResult cannot be null");
        Objects.requireNonNull(quality, "quality cannot be null");
        metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double livenessScore = 0.0;
        private PadResult padResult = PadResult.genuine(1.0);
        private FaceQuality quality = FaceQuality.builder().build();
        private BoundingBox boundingBox = BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 1.0f);
        private FaceLandmarks landmarks = FaceLandmarks.builder().build();
        private boolean isLive = false;
        private long latencyNanos = 0L;
        private Map<String, String> metadata = Collections.emptyMap();

        public Builder livenessScore(double livenessScore) {
            this.livenessScore = livenessScore;
            return this;
        }

        public Builder padResult(PadResult padResult) {
            this.padResult = padResult;
            return this;
        }

        public Builder quality(FaceQuality quality) {
            this.quality = quality;
            return this;
        }

        public Builder boundingBox(BoundingBox boundingBox) {
            this.boundingBox = boundingBox;
            return this;
        }

        public Builder landmarks(FaceLandmarks landmarks) {
            this.landmarks = landmarks;
            return this;
        }

        public Builder isLive(boolean isLive) {
            this.isLive = isLive;
            return this;
        }

        public Builder latencyNanos(long latencyNanos) {
            this.latencyNanos = latencyNanos;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public PassiveLivenessResult build() {
            return new PassiveLivenessResult(livenessScore, padResult, quality,
                    boundingBox, landmarks, isLive, latencyNanos, metadata);
        }
    }
}
