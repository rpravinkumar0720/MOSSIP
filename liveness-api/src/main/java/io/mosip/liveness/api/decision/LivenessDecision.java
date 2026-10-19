package io.mosip.liveness.api.decision;

import io.mosip.liveness.api.challenge.ChallengeResult;
import io.mosip.liveness.api.classifier.PassiveLivenessResult;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.policy.WorkflowType;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Final decision result produced by the Liveness Engine for a session or frame evaluation.
 */
public record LivenessDecision(
        String sessionId,
        WorkflowType workflow,
        DecisionType decisionType,
        String reason,
        PassiveLivenessResult passiveResult,
        List<ChallengeResult> challengeResults,
        double finalScore,
        long timestampMillis,
        FaceFrame bestCompliantFrame
) {
    public LivenessDecision {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(workflow, "workflow cannot be null");
        Objects.requireNonNull(decisionType, "decisionType cannot be null");
        challengeResults = challengeResults != null ? List.copyOf(challengeResults) : Collections.emptyList();
    }

    public static LivenessDecision accepted(String sessionId, WorkflowType workflow,
                                           PassiveLivenessResult passiveResult,
                                           List<ChallengeResult> challengeResults,
                                           double finalScore,
                                           FaceFrame bestCompliantFrame) {
        return new LivenessDecision(sessionId, workflow, DecisionType.ACCEPTED,
                "Liveness and PAD verified successfully", passiveResult, challengeResults,
                finalScore, System.currentTimeMillis(), bestCompliantFrame);
    }

    public static LivenessDecision challengeRequired(String sessionId, WorkflowType workflow,
                                                     PassiveLivenessResult passiveResult,
                                                     String reason) {
        return new LivenessDecision(sessionId, workflow, DecisionType.CHALLENGE_REQUIRED,
                reason, passiveResult, Collections.emptyList(),
                passiveResult != null ? passiveResult.livenessScore() : 0.0,
                System.currentTimeMillis(), null);
    }

    public static LivenessDecision retry(String sessionId, WorkflowType workflow,
                                         String reason, PassiveLivenessResult passiveResult) {
        return new LivenessDecision(sessionId, workflow, DecisionType.RETRY,
                reason, passiveResult, Collections.emptyList(), 0.0,
                System.currentTimeMillis(), null);
    }

    public static LivenessDecision rejected(String sessionId, WorkflowType workflow,
                                            String reason, PassiveLivenessResult passiveResult,
                                            List<ChallengeResult> challengeResults) {
        return new LivenessDecision(sessionId, workflow, DecisionType.REJECTED,
                reason, passiveResult, challengeResults, 0.0,
                System.currentTimeMillis(), null);
    }

    public static LivenessDecision failedMaxRetries(String sessionId, WorkflowType workflow,
                                                   String reason) {
        return new LivenessDecision(sessionId, workflow, DecisionType.FAILED_MAX_RETRIES,
                reason, null, Collections.emptyList(), 0.0,
                System.currentTimeMillis(), null);
    }

    public static LivenessDecision error(String sessionId, WorkflowType workflow,
                                         String reason) {
        return new LivenessDecision(sessionId, workflow, DecisionType.ERROR,
                reason, null, Collections.emptyList(), 0.0,
                System.currentTimeMillis(), null);
    }
}
