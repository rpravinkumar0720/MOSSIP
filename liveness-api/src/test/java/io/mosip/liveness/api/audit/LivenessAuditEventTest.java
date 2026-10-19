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
}
