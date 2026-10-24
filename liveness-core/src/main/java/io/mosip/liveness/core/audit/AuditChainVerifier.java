package io.mosip.liveness.core.audit;

import io.mosip.liveness.api.audit.LivenessAuditEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates cryptographic SHA-256 hash chaining and optional HMAC-SHA256 signatures of audit events.
 */
public final class AuditChainVerifier {

    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

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
     * Verifies that each event's currentHash matches its payload hash, that previousHash links to
     * the prior event's currentHash within the session, and that the first event begins at GENESIS.
     */
    public static AuditVerificationResult verify(List<LivenessAuditEvent> events) {
        return verify(events, null);
    }

    /**
     * Verifies an event list, optionally verifying the HMAC-SHA256 signature on the final event.
     */
    public static AuditVerificationResult verify(List<LivenessAuditEvent> events, String hmacSecretKey) {
        if (events == null || events.isEmpty()) {
            return AuditVerificationResult.success(0);
        }

        // If events contain multiple distinct sessions, verify per-session
        Map<String, List<LivenessAuditEvent>> bySession = new LinkedHashMap<>();
        for (LivenessAuditEvent ev : events) {
            bySession.computeIfAbsent(ev.sessionId(), k -> new ArrayList<>()).add(ev);
        }

        if (bySession.size() > 1) {
            return verifySessions(events, hmacSecretKey);
        }

        return verifySingleSession(events, hmacSecretKey);
    }

    /**
     * Verifies a single session's event chain.
     */
    public static AuditVerificationResult verifySingleSession(List<LivenessAuditEvent> events, String hmacSecretKey) {
        if (events == null || events.isEmpty()) {
            return AuditVerificationResult.success(0);
        }

        String expectedPreviousHash = GENESIS_HASH;

        for (int i = 0; i < events.size(); i++) {
            LivenessAuditEvent event = events.get(i);

            // 1. Verify linkage to previous event: first event MUST start with GENESIS_HASH
            if (i == 0) {
                if (!GENESIS_HASH.equalsIgnoreCase(event.previousHash())) {
                    return AuditVerificationResult.failure(0,
                            String.format("Front-truncation detected at event [0] id '%s': expected GENESIS previousHash but found '%s'",
                                    event.eventId(), event.previousHash()));
                }
            } else {
                if (!event.previousHash().equalsIgnoreCase(expectedPreviousHash)) {
                    return AuditVerificationResult.failure(i,
                            String.format("Chain linkage broken at event [%d] id '%s': expected previousHash '%s' but found '%s'",
                                    i, event.eventId(), expectedPreviousHash, event.previousHash()));
                }
            }

            // 2. Recompute current hash from canonical payload fields including canonical details
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
                    event.deviceId(),
                    event.details()
            );

            if (!recomputedHash.equalsIgnoreCase(event.currentHash())) {
                return AuditVerificationResult.failure(i,
                        String.format("Payload tamper detected at event [%d] id '%s': recomputed hash '%s' does not match recorded '%s'",
                                i, event.eventId(), recomputedHash, event.currentHash()));
            }

            expectedPreviousHash = event.currentHash();
        }

        // 3. Optional HMAC-SHA256 signature verification on the final event
        if (hmacSecretKey != null && !hmacSecretKey.isBlank()) {
            LivenessAuditEvent finalEvent = events.get(events.size() - 1);
            String recordedSig = finalEvent.signature();
            if (recordedSig == null || recordedSig.isBlank()) {
                return AuditVerificationResult.failure(events.size() - 1,
                        "Final event signature missing: recomputed chain rejected without secret key.");
            }
            String expectedSig = LivenessAuditEvent.computeHmacSignature(hmacSecretKey, finalEvent.currentHash());
            if (!recordedSig.equalsIgnoreCase(expectedSig)) {
                return AuditVerificationResult.failure(events.size() - 1,
                        String.format("Final event signature invalid: expected '%s' but found '%s'. Tampering detected.",
                                expectedSig, recordedSig));
            }
        }

        return AuditVerificationResult.success(events.size());
    }

    /**
     * Verifies multiple sessions, grouping events by sessionId and validating each chain independently.
     */
    public static AuditVerificationResult verifySessions(List<LivenessAuditEvent> allEvents, String hmacSecretKey) {
        if (allEvents == null || allEvents.isEmpty()) {
            return AuditVerificationResult.success(0);
        }

        Map<String, List<LivenessAuditEvent>> sessionMap = new LinkedHashMap<>();
        for (LivenessAuditEvent event : allEvents) {
            sessionMap.computeIfAbsent(event.sessionId(), k -> new ArrayList<>()).add(event);
        }

        int verifiedCount = 0;
        int globalIndex = 0;
        for (Map.Entry<String, List<LivenessAuditEvent>> entry : sessionMap.entrySet()) {
            List<LivenessAuditEvent> sessionEvents = entry.getValue();
            AuditVerificationResult res = verifySingleSession(sessionEvents, hmacSecretKey);
            if (!res.valid()) {
                int brokenGlobalIdx = globalIndex + res.firstBrokenIndex();
                return AuditVerificationResult.failure(brokenGlobalIdx,
                        "Session [" + entry.getKey() + "] " + res.message());
            }
            verifiedCount += sessionEvents.size();
            globalIndex += sessionEvents.size();
        }

        return AuditVerificationResult.success(verifiedCount);
    }

    /**
     * Verifies multiple sessions, grouping events by sessionId and validating each chain independently.
     */
    public static AuditVerificationResult verifySessions(List<LivenessAuditEvent> allEvents) {
        return verifySessions(allEvents, null);
    }
}

