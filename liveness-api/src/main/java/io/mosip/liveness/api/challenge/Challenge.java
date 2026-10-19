package io.mosip.liveness.api.challenge;

import java.util.Objects;
import java.util.UUID;

/**
 * Dynamic active liveness challenge issued to the user.
 * Includes a cryptographic nonce to prevent replay attacks and enforces strict time boundaries.
 */
public record Challenge(
        String challengeId,
        ChallengeType type,
        String promptText,
        long timeoutMillis,
        String nonce,
        long minHoldDurationMillis,
        long issuedTimestampNanos
) {
    public Challenge {
        Objects.requireNonNull(challengeId, "challengeId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(promptText, "promptText cannot be null");
        Objects.requireNonNull(nonce, "nonce cannot be null");
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive: " + timeoutMillis);
        }
    }

    public static Challenge create(ChallengeType type, String nonce, long timeoutMillis) {
        return new Challenge(
                UUID.randomUUID().toString(),
                type,
                type.getDefaultPrompt(),
                timeoutMillis > 0 ? timeoutMillis : type.getDefaultTimeoutMillis(),
                nonce,
                300,
                System.nanoTime()
        );
    }

    public boolean isExpired(long currentTimestampNanos) {
        long elapsedNanos = currentTimestampNanos - issuedTimestampNanos;
        long elapsedMillis = elapsedNanos / 1_000_000L;
        return elapsedMillis > timeoutMillis;
    }
}
