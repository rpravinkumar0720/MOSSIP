package io.mosip.liveness.api.engine;

import io.mosip.liveness.api.challenge.Challenge;
import io.mosip.liveness.api.decision.LivenessDecision;
import io.mosip.liveness.api.error.LivenessError;
import io.mosip.liveness.api.quality.FaceQuality;

/**
 * Event-driven callback listener used by UI layers (Desktop JavaFX and Android Flutter)
 * to render real-time user prompts, oval guides, challenges, and completion indicators.
 */
public interface LivenessListener {

    /**
     * Fired when the session transitions between state machine states
     * (e.g. FACE_POSITIONING, PASSIVE_CHECK, ACTIVE_CHALLENGE).
     *
     * @param session current session
     * @param stateName internal state name
     * @param userPrompt user-friendly localized instruction to display
     */
    void onStateChanged(LivenessSession session, String stateName, String userPrompt);

    /**
     * Fired when a dynamic active challenge is triggered.
     *
     * @param session current session
     * @param challenge the challenge issued
     */
    void onChallengeIssued(LivenessSession session, Challenge challenge);

    /**
     * Real-time face quality and positioning feedback per frame.
     *
     * @param session current session
     * @param quality measured face metrics
     * @param guidanceMessage positioning advice (e.g. "Move closer", "Hold still")
     */
    void onQualityFeedback(LivenessSession session, FaceQuality quality, String guidanceMessage);

    /**
     * Progress notification during passive frame buffer collection.
     *
     * @param session current session
     * @param framesCollected number of compliant frames gathered
     * @param windowSize total frames required for sliding window analysis
     */
    void onPassiveProgress(LivenessSession session, int framesCollected, int windowSize);

    /**
     * Final decision reached for the verification session.
     *
     * @param session current session
     * @param decision final orchestration result
     */
    void onDecision(LivenessSession session, LivenessDecision decision);

    /**
     * Fired on recoverable or non-recoverable error.
     *
     * @param session current session
     * @param error error code and message definition
     * @param details technical details for diagnostic logging
     */
    void onError(LivenessSession session, LivenessError error, String details);
}
