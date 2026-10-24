package io.mosip.liveness.core.analyzer;

import io.mosip.liveness.api.frame.FaceFrame;

import java.util.Optional;

/**
 * Interface for face detection and landmark extraction.
 */
public interface FaceAnalyzer {

    /**
     * Analyzes the frame for the given session.
     *
     * @param sessionId current session ID
     * @param frame captured face frame
     * @return face observation if detected, or empty
     */
    Optional<FaceObservation> analyze(String sessionId, FaceFrame frame);
}
