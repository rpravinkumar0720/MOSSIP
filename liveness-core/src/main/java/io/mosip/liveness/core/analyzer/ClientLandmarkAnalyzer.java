package io.mosip.liveness.core.analyzer;

import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.quality.BoundingBox;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.Point2D;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe analyzer receiving client-side MediaPipe landmark detections pushed over WebSocket.
 * Stores latest observation per session ID and clears when consumed or session ends.
 */
public class ClientLandmarkAnalyzer implements FaceAnalyzer {

    private final Map<String, FaceObservation> latestObservations = new ConcurrentHashMap<>();

    /**
     * Pushes landmarks received from client WebSocket message.
     */
    public void pushLandmarks(String sessionId, List<Point2D> points, double confidence, int faceCount) {
        if (sessionId == null) return;

        if (faceCount <= 0 || points == null || points.isEmpty()) {
            latestObservations.put(sessionId, FaceObservation.none());
            return;
        }

        if (faceCount > 1) {
            latestObservations.put(sessionId, FaceObservation.multipleFaces(faceCount));
            return;
        }

        BoundingBox box = LandmarkGeometry.computeBoundingBox(points, (float) confidence);
        FaceLandmarks landmarks = LandmarkGeometry.buildFromPoints(points);
        latestObservations.put(sessionId, new FaceObservation(1, box, landmarks, confidence));
    }

    /**
     * Direct push of pre-constructed FaceObservation (useful for testing and sequence simulation).
     */
    public void pushObservation(String sessionId, FaceObservation observation) {
        if (sessionId != null && observation != null) {
            latestObservations.put(sessionId, observation);
        }
    }

    @Override
    public Optional<FaceObservation> analyze(String sessionId, FaceFrame frame) {
        if (sessionId == null) {
            return Optional.empty();
        }
        // Retrieve and remove latest observation (consumed per frame)
        FaceObservation obs = latestObservations.remove(sessionId);
        if (obs != null) {
            return Optional.of(obs);
        }

        // Pure-Java fallback: estimate face region from frame pixels if client landmarks delayed
        if (frame != null && !frame.isWiped() && frame.getWidth() > 0 && frame.getHeight() > 0) {
            return estimateFromFramePixels(frame);
        }

        return Optional.empty();
    }

    private Optional<FaceObservation> estimateFromFramePixels(FaceFrame frame) {
        byte[] data = frame.getRawDataUnsafe();
        int w = frame.getWidth();
        int h = frame.getHeight();
        int bpp = frame.getFormat().getBytesPerPixel();
        if (data == null || data.length < w * h * bpp) return Optional.empty();

        int minX = w, maxX = 0, minY = h, maxY = 0;
        int skinCount = 0;
        int totalSampled = 0;

        for (int y = 0; y < h; y += 4) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x += 4) {
                int offset = (rowOffset + x) * bpp;
                int r, g, b;
                if (bpp >= 3) {
                    r = data[offset] & 0xFF;
                    g = data[offset + 1] & 0xFF;
                    b = data[offset + 2] & 0xFF;
                } else {
                    r = g = b = data[offset] & 0xFF;
                }

                if (r > 45 && g > 25 && b > 15 && r > g && r > b && (r - g) >= 8) {
                    skinCount++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
                totalSampled++;
            }
        }

        double skinRatio = (double) skinCount / Math.max(1, totalSampled);
        if (skinRatio < 0.05 || minX >= maxX || minY >= maxY) {
            return Optional.empty();
        }

        float left = Math.max(0.0f, (float) minX / w);
        float top = Math.max(0.0f, (float) minY / h);
        float right = Math.min(1.0f, (float) maxX / w);
        float bottom = Math.min(1.0f, (float) maxY / h);

        BoundingBox box = BoundingBox.of(left, top, right, bottom, 0.85f);
        FaceLandmarks landmarks = FaceLandmarks.builder()
                .leftEyePupil(Point2D.of(left + (right - left) * 0.35f, top + (bottom - top) * 0.35f))
                .rightEyePupil(Point2D.of(left + (right - left) * 0.65f, top + (bottom - top) * 0.35f))
                .noseTip(Point2D.of(left + (right - left) * 0.50f, top + (bottom - top) * 0.52f))
                .mouthLeft(Point2D.of(left + (right - left) * 0.38f, top + (bottom - top) * 0.72f))
                .mouthRight(Point2D.of(left + (right - left) * 0.62f, top + (bottom - top) * 0.72f))
                .chinTip(Point2D.of(left + (right - left) * 0.50f, top + (bottom - top) * 0.90f))
                .leftEyeAspectRatio(0.28)
                .rightEyeAspectRatio(0.28)
                .mouthAspectRatio(0.12)
                .build();

        return Optional.of(FaceObservation.singleFace(box, landmarks, 0.85));
    }

    /**
     * Cleans up session state upon completion or disconnect.
     */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            latestObservations.remove(sessionId);
        }
    }
}
