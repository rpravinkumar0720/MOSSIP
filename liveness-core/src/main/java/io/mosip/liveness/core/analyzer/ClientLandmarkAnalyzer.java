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

        // Security rule P0-1(e): Never fabricate landmarks from raw pixels; return empty (NO_FACE)
        return Optional.empty();
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

