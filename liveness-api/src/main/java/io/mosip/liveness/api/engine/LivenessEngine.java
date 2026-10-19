package io.mosip.liveness.api.engine;

import io.mosip.liveness.api.decision.LivenessDecision;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;

/**
 * Core engine contract for face liveness detection and presentation attack detection.
 * Operates completely offline with bundled models and no network dependencies.
 */
public interface LivenessEngine extends AutoCloseable {

    /**
     * Initializes a new biometric liveness session for a specific workflow.
     *
     * @param sessionId unique session identifier
     * @param workflow MOSIP workflow (Resident, Operator, Supervisor)
     * @param policy workflow-specific policy configuration
     * @param listener event listener for UI callbacks and state changes
     * @return initialized LivenessSession handle
     */
    LivenessSession startSession(String sessionId,
                                 WorkflowType workflow,
                                 LivenessPolicy policy,
                                 LivenessListener listener);

    /**
     * Ingests and processes a single frame from the biometric device stream.
     * Evaluates face presence, ISO quality, passive liveness, PAD classification,
     * or active challenge completion depending on the session's current state.
     *
     * @param session active session handle
     * @param frame input face frame
     * @return current frame or session decision
     */
    LivenessDecision processFrame(LivenessSession session, FaceFrame frame);

    /**
     * Cancels an active session and wipes any intermediate state.
     *
     * @param session session to cancel
     */
    void cancelSession(LivenessSession session);

    /**
     * Returns the active AI model version and engine build identifier.
     */
    String getEngineVersion();

    /**
     * Checks if underlying ONNX runtime and models are loaded and ready for inference.
     */
    boolean isReady();

    @Override
    default void close() {
        // default no-op
    }
}
