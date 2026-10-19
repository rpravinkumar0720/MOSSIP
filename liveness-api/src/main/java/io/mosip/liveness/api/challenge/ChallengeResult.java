package io.mosip.liveness.api.challenge;

import java.util.Objects;

/**
 * Detailed verification outcome for an active liveness challenge.
 * Enforces identity continuity to stop face-swapping mid-session.
 */
public record ChallengeResult(
        String challengeId,
        ChallengeType type,
        boolean completed,
        boolean detectedAction,
        long durationMillis,
        double transitionScore,
        double identityContinuityScore,
        String failureReason
) {
    public ChallengeResult {
        Objects.requireNonNull(challengeId, "challengeId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
    }

    public static ChallengeResult success(String challengeId, ChallengeType type,
                                          long durationMillis, double transitionScore,
                                          double identityContinuityScore) {
        return new ChallengeResult(challengeId, type, true, true, durationMillis,
                transitionScore, identityContinuityScore, null);
    }

    public static ChallengeResult failure(String challengeId, ChallengeType type,
                                          long durationMillis, String failureReason) {
        return new ChallengeResult(challengeId, type, false, false, durationMillis,
                0.0, 0.0, failureReason);
    }
}
