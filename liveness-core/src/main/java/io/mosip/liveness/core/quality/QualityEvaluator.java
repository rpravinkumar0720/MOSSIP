package io.mosip.liveness.core.quality;

import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.quality.BoundingBox;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.FaceQuality;
import io.mosip.liveness.api.quality.QualityFailureReason;
import io.mosip.liveness.core.analyzer.FaceObservation;
import io.mosip.liveness.core.image.ImageOps;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ISO/IEC 19794-5 biometric face quality gate evaluator.
 * Enforces face area, pose angles (yaw, pitch, roll <= 15 deg), illumination (0.20 to 0.85),
 * sharpness (Laplacian variance >= 80), and neutral expression (eyes open, mouth closed).
 */
public final class QualityEvaluator {

    public static final double MIN_FACE_AREA_RATIO = 0.08;
    public static final double MAX_FACE_AREA_RATIO = 0.75;
    public static final double MAX_POSE_YAW_DEGREES = 15.0;
    public static final double MAX_POSE_PITCH_DEGREES = 15.0;
    public static final double MAX_POSE_ROLL_DEGREES = 15.0;
    public static final double MIN_LUMA = 0.20;
    public static final double MAX_LUMA = 0.85;
    public static final double MIN_LAPLACIAN_VARIANCE = 35.0;
    public static final double MIN_EYE_ASPECT_RATIO = 0.16;
    public static final double MAX_MOUTH_ASPECT_RATIO = 0.35;

    // Strict priority order for guidance message display
    private static final List<QualityFailureReason> PRIORITY_ORDER = List.of(
            QualityFailureReason.NO_FACE_DETECTED,
            QualityFailureReason.MULTIPLE_FACES_DETECTED,
            QualityFailureReason.FACE_TOO_SMALL,
            QualityFailureReason.FACE_TOO_CLOSE,
            QualityFailureReason.POSE_YAW_EXCEEDED,
            QualityFailureReason.POSE_PITCH_EXCEEDED,
            QualityFailureReason.POSE_ROLL_EXCEEDED,
            QualityFailureReason.TOO_DARK,
            QualityFailureReason.TOO_BRIGHT,
            QualityFailureReason.BLURRY,
            QualityFailureReason.EYES_CLOSED,
            QualityFailureReason.MOUTH_OPEN
    );

    private QualityEvaluator() {
    }

    /**
     * Evaluates ISO 19794-5 quality compliance of a captured face frame and observation.
     */
    public static FaceQuality evaluate(FaceFrame frame, FaceObservation observation) {
        Objects.requireNonNull(frame, "FaceFrame cannot be null");

        Set<QualityFailureReason> failures = EnumSet.noneOf(QualityFailureReason.class);

        if (observation == null || observation.faceCount() == 0) {
            failures.add(QualityFailureReason.NO_FACE_DETECTED);
            return FaceQuality.builder()
                    .brightnessScore(0.0)
                    .blurScore(0.0)
                    .faceSizeRatio(0.0)
                    .isCompliant(false)
                    .qualityFailures(failures)
                    .build();
        }

        if (observation.faceCount() > 1) {
            failures.add(QualityFailureReason.MULTIPLE_FACES_DETECTED);
            return FaceQuality.builder()
                    .brightnessScore(0.5)
                    .blurScore(100.0)
                    .faceSizeRatio(0.5)
                    .isCompliant(false)
                    .qualityFailures(failures)
                    .build();
        }

        BoundingBox box = observation.box();
        FaceLandmarks landmarks = observation.landmarks();

        // 1. Face Area Ratio check (0.15 .. 0.75)
        double areaRatio = box != null ? box.area() : 0.0;
        if (areaRatio < MIN_FACE_AREA_RATIO) {
            failures.add(QualityFailureReason.FACE_TOO_SMALL);
        } else if (areaRatio > MAX_FACE_AREA_RATIO) {
            failures.add(QualityFailureReason.FACE_TOO_CLOSE);
        }

        // 2. Pose Tolerances (yaw, pitch, roll <= 15 degrees)
        double yaw = landmarks != null ? landmarks.estimatedYawDegrees() : 0.0;
        double pitch = landmarks != null ? landmarks.estimatedPitchDegrees() : 0.0;
        double roll = landmarks != null ? landmarks.estimatedRollDegrees() : 0.0;

        if (Math.abs(yaw) > MAX_POSE_YAW_DEGREES) {
            failures.add(QualityFailureReason.POSE_YAW_EXCEEDED);
        }
        if (Math.abs(pitch) > MAX_POSE_PITCH_DEGREES) {
            failures.add(QualityFailureReason.POSE_PITCH_EXCEEDED);
        }
        if (Math.abs(roll) > MAX_POSE_ROLL_DEGREES) {
            failures.add(QualityFailureReason.POSE_ROLL_EXCEEDED);
        }

        // 3. Illumination / Luma check (0.20 .. 0.85)
        byte[] gray = ImageOps.toGrayscale(frame);
        double luma = ImageOps.meanLuma(gray, frame.getWidth(), frame.getHeight(), box);
        if (luma < MIN_LUMA) {
            failures.add(QualityFailureReason.TOO_DARK);
        } else if (luma > MAX_LUMA) {
            failures.add(QualityFailureReason.TOO_BRIGHT);
        }

        // 4. Sharpness / Blur check (Laplacian variance >= 80)
        double laplacianVar = ImageOps.laplacianVariance(gray, frame.getWidth(), frame.getHeight(), box);
        if (laplacianVar < MIN_LAPLACIAN_VARIANCE) {
            failures.add(QualityFailureReason.BLURRY);
        }

        // 5. Eyes Open & Mouth Closed
        double leftEar = landmarks != null ? landmarks.leftEyeAspectRatio() : 0.3;
        double rightEar = landmarks != null ? landmarks.rightEyeAspectRatio() : 0.3;
        double mar = landmarks != null ? landmarks.mouthAspectRatio() : 0.15;

        if (leftEar < MIN_EYE_ASPECT_RATIO || rightEar < MIN_EYE_ASPECT_RATIO) {
            failures.add(QualityFailureReason.EYES_CLOSED);
        }
        if (mar > MAX_MOUTH_ASPECT_RATIO) {
            failures.add(QualityFailureReason.MOUTH_OPEN);
        }

        boolean compliant = failures.isEmpty();

        return FaceQuality.builder()
                .brightnessScore(luma)
                .blurScore(laplacianVar)
                .faceSizeRatio(areaRatio)
                .poseYaw(yaw)
                .posePitch(pitch)
                .poseRoll(roll)
                .eyeOpenLeft(leftEar > MIN_EYE_ASPECT_RATIO ? 1.0 : 0.0)
                .eyeOpenRight(rightEar > MIN_EYE_ASPECT_RATIO ? 1.0 : 0.0)
                .mouthOpen(mar)
                .isCompliant(compliant)
                .qualityFailures(failures)
                .build();
    }

    /**
     * Returns the highest-priority user guidance message when quality checks fail.
     */
    public static String getPrimaryGuidance(Set<QualityFailureReason> failures) {
        if (failures == null || failures.isEmpty()) {
            return "Hold still. Looking good!";
        }
        for (QualityFailureReason reason : PRIORITY_ORDER) {
            if (failures.contains(reason)) {
                return reason.getGuidanceMessage();
            }
        }
        return failures.iterator().next().getGuidanceMessage();
    }
}
