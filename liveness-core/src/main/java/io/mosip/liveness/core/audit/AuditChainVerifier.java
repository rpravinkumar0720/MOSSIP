package io.mosip.liveness.core.audit;

import io.mosip.liveness.api.audit.LivenessAuditEvent;

import java.util.List;

/**
 * Validates cryptographic SHA-256 hash chaining of audit events for a session.
 */
public final class AuditChainVerifier {

    public record AuditVerificationResult(boolean valid, int firstBrokenIndex, String message) {
        public static AuditVerificationResult success(int count) {
            return new AuditVerificationResult(true, -1, "Cryptographic hash chain verified (" + count + " events).");
        }

        public static AuditVerificationResult failure(int brokenIndex, String reason) {
            return new AuditVerificationResult(false, brokenIndex, reason);
        }
    }

    private AuditChainVerifier() {
    }

    /**
     * Verifies that each event's currentHash matches its payload hash, and that previousHash links to
     * the prior event's currentHash within the session.
     *
     * @param events chronological list of audit events
     * @return verification result
     */
    public static AuditVerificationResult verify(List<LivenessAuditEvent> events) {
        if (events == null || events.isEmpty()) {
            return AuditVerificationResult.success(0);
        }

        String expectedPreviousHash = "0000000000000000000000000000000000000000000000000000000000000000";

        for (int i = 0; i < events.size(); i++) {
            LivenessAuditEvent event = events.get(i);

            // 1. Verify linkage to previous event
            if (i == 0) {
                // First event may use genesis or event's declared previousHash
                expectedPreviousHash = event.previousHash();
            } else {
                if (!event.previousHash().equalsIgnoreCase(expectedPreviousHash)) {
                    return AuditVerificationResult.failure(i,
                            String.format("Chain linkage broken at event [%d] id '%s': expected previousHash '%s' but found '%s'",
                                    i, event.eventId(), expectedPreviousHash, event.previousHash()));
                }
            }

            // 2. Recompute current hash from canonical payload fields
            String recomputedHash = LivenessAuditEvent.computeHash(
                    event.previousHash(),
                    event.eventId(),
                    event.sessionId(),
                    event.timestampMillis(),
                    event.workflow(),
                    event.eventType(),
                    event.decision(),
                    event.passiveScore(),
                    event.padClassification(),
                    event.padScore(),
                    event.challengeType(),
                    event.errorCode(),
                    event.modelVersion(),
                    event.deviceId()
            );

            if (!recomputedHash.equalsIgnoreCase(event.currentHash())) {
                return AuditVerificationResult.failure(i,
                        String.format("Payload tamper detected at event [%d] id '%s': recomputed hash '%s' does not match recorded '%s'",
                                i, event.eventId(), recomputedHash, event.currentHash()));
            }

            expectedPreviousHash = event.currentHash();
        }

        return AuditVerificationResult.success(events.size());
    }
}
