package io.mosip.liveness.core.analyzer;

import io.mosip.liveness.api.quality.BoundingBox;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.Point2D;

import java.util.List;

/**
 * Geometric calculations for facial landmarks (MediaPipe 468/478 mesh or standard sparse landmarks).
 * Calculates EAR (Eye Aspect Ratio), MAR (Mouth Aspect Ratio), bounding boxes, and head pose angles
 * (Yaw, Pitch, Roll) using pure geometry with zero native dependencies.
 */
public final class LandmarkGeometry {

    private LandmarkGeometry() {
    }

    /**
     * Builds FaceLandmarks from a list of normalized 2D points (0.0 to 1.0).
     */
    public static FaceLandmarks buildFromPoints(List<Point2D> points) {
        if (points == null || points.isEmpty()) {
            return FaceLandmarks.builder().build();
        }

        int n = points.size();

        Point2D noseTip = getPoint(points, 4, 1, n / 2);
        Point2D chinTip = getPoint(points, 152, 199, n - 1);
        Point2D leftCorner = getPoint(points, 61, 78, Math.max(0, n / 3));
        Point2D rightCorner = getPoint(points, 291, 308, Math.min(n - 1, 2 * n / 3));

        // MediaPipe eye indices
        Point2D r1 = getPoint(points, 33, 33, 0);
        Point2D r2 = getPoint(points, 160, 160, 0);
        Point2D r3 = getPoint(points, 158, 158, 0);
        Point2D r4 = getPoint(points, 133, 133, 0);
        Point2D r5 = getPoint(points, 153, 153, 0);
        Point2D r6 = getPoint(points, 144, 144, 0);

        Point2D l1 = getPoint(points, 362, 362, 0);
        Point2D l2 = getPoint(points, 385, 385, 0);
        Point2D l3 = getPoint(points, 387, 387, 0);
        Point2D l4 = getPoint(points, 263, 263, 0);
        Point2D l5 = getPoint(points, 373, 373, 0);
        Point2D l6 = getPoint(points, 380, 380, 0);

        // Pupils (468/473 if available, otherwise midpoints of inner/outer corners)
        Point2D rightPupil = (n > 468) ? points.get(468) : midpoint(r1, r4);
        Point2D leftPupil = (n > 473) ? points.get(473) : midpoint(l1, l4);

        double rightEar = (n >= 160) ? FaceLandmarks.computeEyeAspectRatio(r1, r2, r3, r4, r5, r6) : 0.30;
        double leftEar = (n >= 388) ? FaceLandmarks.computeEyeAspectRatio(l1, l2, l3, l4, l5, l6) : 0.30;

        Point2D upperLip = getPoint(points, 13, 0, n / 2);
        Point2D lowerLip = getPoint(points, 14, 17, n / 2);
        double mar = (n >= 292) ? FaceLandmarks.computeMouthAspectRatio(upperLip, lowerLip, leftCorner, rightCorner) : 0.15;

        // Pose estimation
        double roll = estimateRoll(leftPupil, rightPupil);
        double yaw = estimateYaw(leftPupil, rightPupil, noseTip);
        double pitch = estimatePitch(leftPupil, rightPupil, noseTip, chinTip);

        return new FaceLandmarks(
                points,
                leftPupil,
                rightPupil,
                noseTip,
                leftCorner,
                rightCorner,
                chinTip,
                leftEar,
                rightEar,
                mar,
                yaw,
                pitch,
                roll
        );
    }

    /**
     * Estimates head roll (in-plane tilt) in degrees from eye pupils.
     */
    public static double estimateRoll(Point2D leftEye, Point2D rightEye) {
        float dx = leftEye.x() - rightEye.x();
        float dy = leftEye.y() - rightEye.y();
        if (Math.abs(dx) < 1e-4) {
            return 0.0;
        }
        return Math.toDegrees(Math.atan2(dy, dx));
    }

    /**
     * Estimates head yaw (left/right rotation) in degrees from eye midpoint and nose tip.
     */
    public static double estimateYaw(Point2D leftEye, Point2D rightEye, Point2D noseTip) {
        float eyeMidX = (leftEye.x() + rightEye.x()) / 2.0f;
        float eyeDist = Math.abs(leftEye.x() - rightEye.x());
        if (eyeDist < 1e-4) {
            return 0.0;
        }
        float offset = (noseTip.x() - eyeMidX) / (eyeDist / 2.0f);
        // Clamp and map to degrees (~45 degrees at offset = 1.0)
        double clamped = Math.max(-1.5, Math.min(1.5, offset));
        return clamped * 45.0;
    }

    /**
     * Estimates head pitch (up/down tilt) in degrees from nose-to-eye vs nose-to-chin ratio.
     */
    public static double estimatePitch(Point2D leftEye, Point2D rightEye, Point2D noseTip, Point2D chinTip) {
        float eyeMidY = (leftEye.y() + rightEye.y()) / 2.0f;
        float totalFaceH = chinTip.y() - eyeMidY;
        if (totalFaceH < 1e-4) {
            return 0.0;
        }
        float ratio = (noseTip.y() - eyeMidY) / totalFaceH;
        // Neutral ratio is typically ~0.38. Pitching up reduces ratio; pitching down increases ratio.
        double diff = (ratio - 0.38);
        return Math.max(-60.0, Math.min(60.0, diff * 110.0));
    }

    /**
     * Derives a face bounding box from landmarks with a margin.
     */
    public static BoundingBox computeBoundingBox(List<Point2D> points, float confidence) {
        if (points == null || points.isEmpty()) {
            return BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, confidence);
        }

        float minX = 1.0f, minY = 1.0f, maxX = 0.0f, maxY = 0.0f;
        for (Point2D p : points) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.y() > maxY) maxY = p.y();
        }

        float width = maxX - minX;
        float height = maxY - minY;
        float padX = width * 0.12f;
        float padY = height * 0.15f;

        float left = Math.max(0.0f, minX - padX);
        float top = Math.max(0.0f, minY - padY);
        float right = Math.min(1.0f, maxX + padX);
        float bottom = Math.min(1.0f, maxY + padY);

        if (right <= left || bottom <= top) {
            return BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, confidence);
        }

        return BoundingBox.of(left, top, right, bottom, confidence);
    }

    private static Point2D getPoint(List<Point2D> points, int primaryIdx, int secondaryIdx, int fallbackIdx) {
        if (primaryIdx < points.size()) return points.get(primaryIdx);
        if (secondaryIdx < points.size()) return points.get(secondaryIdx);
        if (fallbackIdx < points.size()) return points.get(fallbackIdx);
        return Point2D.of(0.5f, 0.5f);
    }

    private static Point2D midpoint(Point2D a, Point2D b) {
        return Point2D.of((a.x() + b.x()) / 2.0f, (a.y() + b.y()) / 2.0f);
    }
}
