package io.mosip.liveness.api.quality;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Facial landmark representation with geometry helpers for action detection
 * (Eye Aspect Ratio for blinks, Mouth Aspect Ratio for smiles, and 3D pose angles).
 */
public record FaceLandmarks(
        List<Point2D> points,
        Point2D leftEyePupil,
        Point2D rightEyePupil,
        Point2D noseTip,
        Point2D mouthLeft,
        Point2D mouthRight,
        Point2D chinTip,
        double leftEyeAspectRatio,
        double rightEyeAspectRatio,
        double mouthAspectRatio,
        double estimatedYawDegrees,
        double estimatedPitchDegrees,
        double estimatedRollDegrees
) {
    public FaceLandmarks {
        points = points != null ? List.copyOf(points) : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Computes the Eye Aspect Ratio (EAR) given 6 landmark points surrounding an eye:
     * p1 (outer corner), p2 (upper-outer), p3 (upper-inner), p4 (inner corner),
     * p5 (lower-inner), p6 (lower-outer).
     * EAR = (dist(p2, p6) + dist(p3, p5)) / (2.0 * dist(p1, p4))
     */
    public static double computeEyeAspectRatio(Point2D p1, Point2D p2, Point2D p3,
                                               Point2D p4, Point2D p5, Point2D p6) {
        Objects.requireNonNull(p1);
        Objects.requireNonNull(p2);
        Objects.requireNonNull(p3);
        Objects.requireNonNull(p4);
        Objects.requireNonNull(p5);
        Objects.requireNonNull(p6);

        double verticalA = p2.distanceTo(p6);
        double verticalB = p3.distanceTo(p5);
        double horizontal = p1.distanceTo(p4);

        if (horizontal <= 0.0001) {
            return 0.0;
        }
        return (verticalA + verticalB) / (2.0 * horizontal);
    }

    /**
     * Computes Mouth Aspect Ratio (MAR) for smile and open-mouth detection:
     * MAR = dist(upperLip, lowerLip) / dist(leftCorner, rightCorner)
     */
    public static double computeMouthAspectRatio(Point2D upperLip, Point2D lowerLip,
                                                 Point2D leftCorner, Point2D rightCorner) {
        Objects.requireNonNull(upperLip);
        Objects.requireNonNull(lowerLip);
        Objects.requireNonNull(leftCorner);
        Objects.requireNonNull(rightCorner);

        double vertical = upperLip.distanceTo(lowerLip);
        double horizontal = leftCorner.distanceTo(rightCorner);

        if (horizontal <= 0.0001) {
            return 0.0;
        }
        return vertical / horizontal;
    }

    public static class Builder {
        private List<Point2D> points = Collections.emptyList();
        private Point2D leftEyePupil = Point2D.of(0, 0);
        private Point2D rightEyePupil = Point2D.of(0, 0);
        private Point2D noseTip = Point2D.of(0, 0);
        private Point2D mouthLeft = Point2D.of(0, 0);
        private Point2D mouthRight = Point2D.of(0, 0);
        private Point2D chinTip = Point2D.of(0, 0);
        private double leftEyeAspectRatio = 0.3;
        private double rightEyeAspectRatio = 0.3;
        private double mouthAspectRatio = 0.15;
        private double estimatedYawDegrees = 0.0;
        private double estimatedPitchDegrees = 0.0;
        private double estimatedRollDegrees = 0.0;

        public Builder points(List<Point2D> points) {
            this.points = points;
            return this;
        }

        public Builder leftEyePupil(Point2D leftEyePupil) {
            this.leftEyePupil = leftEyePupil;
            return this;
        }

        public Builder rightEyePupil(Point2D rightEyePupil) {
            this.rightEyePupil = rightEyePupil;
            return this;
        }

        public Builder noseTip(Point2D noseTip) {
            this.noseTip = noseTip;
            return this;
        }

        public Builder mouthLeft(Point2D mouthLeft) {
            this.mouthLeft = mouthLeft;
            return this;
        }

        public Builder mouthRight(Point2D mouthRight) {
            this.mouthRight = mouthRight;
            return this;
        }

        public Builder chinTip(Point2D chinTip) {
            this.chinTip = chinTip;
            return this;
        }

        public Builder leftEyeAspectRatio(double leftEyeAspectRatio) {
            this.leftEyeAspectRatio = leftEyeAspectRatio;
            return this;
        }

        public Builder rightEyeAspectRatio(double rightEyeAspectRatio) {
            this.rightEyeAspectRatio = rightEyeAspectRatio;
            return this;
        }

        public Builder mouthAspectRatio(double mouthAspectRatio) {
            this.mouthAspectRatio = mouthAspectRatio;
            return this;
        }

        public Builder estimatedYawDegrees(double estimatedYawDegrees) {
            this.estimatedYawDegrees = estimatedYawDegrees;
            return this;
        }

        public Builder estimatedPitchDegrees(double estimatedPitchDegrees) {
            this.estimatedPitchDegrees = estimatedPitchDegrees;
            return this;
        }

        public Builder estimatedRollDegrees(double estimatedRollDegrees) {
            this.estimatedRollDegrees = estimatedRollDegrees;
            return this;
        }

        public FaceLandmarks build() {
            return new FaceLandmarks(points, leftEyePupil, rightEyePupil, noseTip,
                    mouthLeft, mouthRight, chinTip, leftEyeAspectRatio, rightEyeAspectRatio,
                    mouthAspectRatio, estimatedYawDegrees, estimatedPitchDegrees, estimatedRollDegrees);
        }
    }
}
