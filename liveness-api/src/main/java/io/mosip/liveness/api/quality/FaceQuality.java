package io.mosip.liveness.api.quality;

import java.util.Collections;
import java.util.Set;

/**
 * ISO/IEC 19794-5 biometric face quality evaluation metrics.
 */
public record FaceQuality(
        double brightnessScore,
        double blurScore,
        double faceSizeRatio,
        double poseYaw,
        double posePitch,
        double poseRoll,
        double eyeOpenLeft,
        double eyeOpenRight,
        double mouthOpen,
        boolean isCompliant,
        Set<QualityFailureReason> qualityFailures
) {
    public FaceQuality {
        qualityFailures = qualityFailures != null ? Set.copyOf(qualityFailures) : Collections.emptySet();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double brightnessScore = 0.65;
        private double blurScore = 150.0;
        private double faceSizeRatio = 0.35;
        private double poseYaw = 0.0;
        private double posePitch = 0.0;
        private double poseRoll = 0.0;
        private double eyeOpenLeft = 0.9;
        private double eyeOpenRight = 0.9;
        private double mouthOpen = 0.1;
        private boolean isCompliant = true;
        private Set<QualityFailureReason> qualityFailures = Collections.emptySet();

        public Builder brightnessScore(double brightnessScore) {
            this.brightnessScore = brightnessScore;
            return this;
        }

        public Builder blurScore(double blurScore) {
            this.blurScore = blurScore;
            return this;
        }

        public Builder faceSizeRatio(double faceSizeRatio) {
            this.faceSizeRatio = faceSizeRatio;
            return this;
        }

        public Builder poseYaw(double poseYaw) {
            this.poseYaw = poseYaw;
            return this;
        }

        public Builder posePitch(double posePitch) {
            this.posePitch = posePitch;
            return this;
        }

        public Builder poseRoll(double poseRoll) {
            this.poseRoll = poseRoll;
            return this;
        }

        public Builder eyeOpenLeft(double eyeOpenLeft) {
            this.eyeOpenLeft = eyeOpenLeft;
            return this;
        }

        public Builder eyeOpenRight(double eyeOpenRight) {
            this.eyeOpenRight = eyeOpenRight;
            return this;
        }

        public Builder mouthOpen(double mouthOpen) {
            this.mouthOpen = mouthOpen;
            return this;
        }

        public Builder isCompliant(boolean isCompliant) {
            this.isCompliant = isCompliant;
            return this;
        }

        public Builder qualityFailures(Set<QualityFailureReason> qualityFailures) {
            this.qualityFailures = qualityFailures;
            return this;
        }

        public FaceQuality build() {
            return new FaceQuality(brightnessScore, blurScore, faceSizeRatio, poseYaw, posePitch,
                    poseRoll, eyeOpenLeft, eyeOpenRight, mouthOpen, isCompliant, qualityFailures);
        }
    }
}
