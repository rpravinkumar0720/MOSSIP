package io.mosip.liveness.api.audit;

import io.mosip.liveness.api.classifier.PadClassification;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.policy.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessAuditEventTest {

    @Test
    @DisplayName("Audit events calculate SHA-256 hash chaining correctly")
    void testHashChaining() {
        String genesisHash = "0000000000000000000000000000000000000000000000000000000000000000";

        // Event 1: Session start
        LivenessAuditEvent event1 = LivenessAuditEvent.builder()
                .eventId("EVT_001")
                .sessionId("SESS_001")
                .timestampMillis(1000L)
                .workflow(WorkflowType.RESIDENT_ENROLLMENT)
                .eventType(AuditEventType.SESSION_START)
                .previousHash(genesisHash)
                .build();

        assertThat(event1.previousHash()).isEqualTo(genesisHash);
        assertThat(event1.currentHash()).isNotBlank().hasSize(64); // 64 hex chars for SHA-256

        // Event 2: Passive evaluation chained from Event 1
        LivenessAuditEvent event2 = LivenessAuditEvent.builder()
                .eventId("EVT_002")
                .sessionId("SESS_001")
                .timestampMillis(1100L)
                .workflow(WorkflowType.RESIDENT_ENROLLMENT)
                .eventType(AuditEventType.PASSIVE_EVALUATED)
                .passiveScore(0.92)
                .padClassification(PadClassification.GENUINE)
                .padScore(0.05)
                .previousHash(event1.currentHash())
                .build();

        assertThat(event2.previousHash()).isEqualTo(event1.currentHash());
        assertThat(event2.currentHash()).isNotBlank().hasSize(64);
        assertThat(event2.currentHash()).isNotEqualTo(event1.currentHash());

        // Event 3: Final Decision chained from Event 2
        LivenessAuditEvent event3 = LivenessAuditEvent.builder()
                .eventId("EVT_003")
                .sessionId("SESS_001")
                .timestampMillis(1200L)
                .workflow(WorkflowType.RESIDENT_ENROLLMENT)
                .eventType(AuditEventType.FINAL_DECISION)
                .decision(DecisionType.ACCEPTED)
                .previousHash(event2.currentHash())
                .build();

        assertThat(event3.previousHash()).isEqualTo(event2.currentHash());

        // Tamper test: if Event 2 is altered, its recomputed hash will not match event3's previousHash
        String tamperedEvent2Hash = LivenessAuditEvent.computeHash(
                event1.currentHash(),
                "EVT_002",
                "SESS_001",
                1100L,
                WorkflowType.RESIDENT_ENROLLMENT,
                AuditEventType.PASSIVE_EVALUATED,
                null,
                0.15, // TAMPERED SCORE!
                PadClassification.GENUINE,
                0.05,
                null,
                null,
                "1.0.0",
                "UNKNOWN_DEVICE"
        );

        assertThat(tamperedEvent2Hash).isNotEqualTo(event2.currentHash());
        assertThat(event3.previousHash()).isNotEqualTo(tamperedEvent2Hash);
    }

    @Test
    @DisplayName("Details map is included in hash and tampering with details changes hash")
    void testDetailsTamperingDetected() {
        String genesisHash = "0000000000000000000000000000000000000000000000000000000000000000";

        LivenessAuditEvent event = LivenessAuditEvent.builder()
                .eventId("EVT_100")
                .sessionId("SESS_100")
                .eventType(AuditEventType.CHALLENGE_ISSUED)
                .details(java.util.Map.of("challengeId", "CHG_123", "type", "BLINK"))
                .previousHash(genesisHash)
                .build();

        String tamperedHash = LivenessAuditEvent.computeHash(
                genesisHash,
                "EVT_100",
                "SESS_100",
                event.timestampMillis(),
                event.workflow(),
                AuditEventType.CHALLENGE_ISSUED,
                null,
                null,
                null,
                null,
                null,
                null,
                event.modelVersion(),
                event.deviceId(),
                java.util.Map.of("challengeId", "CHG_999", "type", "BLINK") // TAMPERED DETAIL
        );

        assertThat(event.currentHash()).isNotEqualTo(tamperedHash);
    }

    @Test
    @DisplayName("HMAC-SHA256 signature computation and validation")
    void testHmacSignature() {
        String secretKey = "super-secret-audit-key-123456";
        String hash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";

        String sig = LivenessAuditEvent.computeHmacSignature(secretKey, hash);
        assertThat(sig).isNotBlank().hasSize(64);

        // Same inputs produce identical signature
        String sig2 = LivenessAuditEvent.computeHmacSignature(secretKey, hash);
        assertThat(sig2).isEqualTo(sig);

        // Different hash produces different signature
        String sigOtherHash = LivenessAuditEvent.computeHmacSignature(secretKey, "1111111111111111111111111111111111111111111111111111111111111111");
        assertThat(sigOtherHash).isNotEqualTo(sig);

        // Wrong secret key produces different signature
        String sigWrongKey = LivenessAuditEvent.computeHmacSignature("wrong-key", hash);
        assertThat(sigWrongKey).isNotEqualTo(sig);
    }
}

