package io.mosip.liveness.core.challenge;

import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.Point2D;

/**
 * Normalized geometric facial signature used to ensure identity continuity during active challenges.
 * Prevents face-swap attacks mid-session.
 */
public record IdentitySignature(double eyeDistanceRatio, double eyeToNoseRatio, double mouthToEyeRatio) {

    public static IdentitySignature compute(FaceLandmarks landmarks) {
        if (landmarks == null) {
            return new IdentitySignature(0.5, 0.4, 0.8);
        }

        Point2D leftEye = landmarks.leftEyePupil();
        Point2D rightEye = landmarks.rightEyePupil();
        Point2D nose = landmarks.noseTip();
        Point2D chin = landmarks.chinTip();
        Point2D mouthL = landmarks.mouthLeft();
        Point2D mouthR = landmarks.mouthRight();

        Point2D eyeMid = Point2D.of((leftEye.x() + rightEye.x()) / 2.0f, (leftEye.y() + rightEye.y()) / 2.0f);

        double eyeDist = leftEye.distanceTo(rightEye);
        double noseToChin = nose.distanceTo(chin);
        double eyeToNose = eyeMid.distanceTo(nose);
        double mouthWidth = mouthL.distanceTo(mouthR);

        if (eyeDist < 1e-4 || noseToChin < 1e-4) {
            // Non-informative landmarks (e.g. uninitialized or sparse test landmarks)
            return new IdentitySignature(0.0, 0.0, 0.0);
        }

        double r1 = eyeDist / noseToChin;
        double r2 = eyeToNose / noseToChin;
        double r3 = mouthWidth / eyeDist;

        return new IdentitySignature(r1, r2, r3);
    }

    public double distanceTo(IdentitySignature other) {
        if (other == null) return 0.0;
        if (this.eyeDistanceRatio == 0.0 || other.eyeDistanceRatio == 0.0) {
            return 0.0;
        }
        double d1 = this.eyeDistanceRatio - other.eyeDistanceRatio;
        double d2 = this.eyeToNoseRatio - other.eyeToNoseRatio;
        double d3 = this.mouthToEyeRatio - other.mouthToEyeRatio;
        return Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
    }
}
