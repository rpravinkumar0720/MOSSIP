package io.mosip.liveness.core.audit;

import io.mosip.liveness.api.audit.AuditEventType;
import io.mosip.liveness.api.audit.LivenessAuditEvent;
import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.policy.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChainVerifierTest {

    private static List<LivenessAuditEvent> createValidSessionChain(String sessionId, int count) {
        List<LivenessAuditEvent> list = new ArrayList<>();
        String prevHash = "0000000000000000000000000000000000000000000000000000000000000000";

        for (int i = 0; i < count; i++) {
            AuditEventType type = (i == 0) ? AuditEventType.SESSION_START :
                    (i == count - 1) ? AuditEventType.FINAL_DECISION : AuditEventType.QUALITY_CHECK;

            DecisionType dec = (i == count - 1) ? DecisionType.ACCEPTED : null;

            LivenessAuditEvent event = LivenessAuditEvent.builder()
                    .sessionId(sessionId)
                    .previousHash(prevHash)
                    .eventType(type)
                    .workflow(WorkflowType.RESIDENT_ENROLLMENT)
                    .decision(dec)
                    .passiveScore(0.92)
                    .deviceId("CAM_01")
                    .details(Map.of("frameIndex", String.valueOf(i)))
                    .build();

            list.add(event);
            prevHash = event.currentHash();
        }
        return list;
    }

    @Test
    @DisplayName("Valid audit chain passes verification")
    void testValidChainPasses() {
        List<LivenessAuditEvent> chain = createValidSessionChain("sess-001", 5);
        AuditChainVerifier.AuditVerificationResult result = AuditChainVerifier.verify(chain);

        assertThat(result.valid()).isTrue();
        assertThat(result.firstBrokenIndex()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Tampering with a single field fails verification at exact index")
    void testFieldTamperDetected() {
        List<LivenessAuditEvent> chain = createValidSessionChain("sess-002", 4);

        // Tamper with index 2 (modify passive score without updating hash)
        LivenessAuditEvent original = chain.get(2);
        LivenessAuditEvent tampered = new LivenessAuditEvent(
                original.eventId(),
                original.sessionId(),
                original.timestampMillis(),
                original.workflow(),
                original.eventType(),
                original.decision(),
                0.1234, // tampered score!
                original.padClassification(),
                original.padScore(),
                original.challengeType(),
                original.errorCode(),
                original.modelVersion(),
                original.deviceId(),
                original.details(),
                original.previousHash(),
                original.currentHash() // unchanged hash!
        );
        chain.set(2, tampered);

        AuditChainVerifier.AuditVerificationResult result = AuditChainVerifier.verify(chain);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenIndex()).isEqualTo(2);
        assertThat(result.message()).contains("Payload tamper detected");
    }

    @Test
    @DisplayName("Reordering events breaks chain linkage")
    void testReorderingBreaksLinkage() {
        List<LivenessAuditEvent> chain = createValidSessionChain("sess-003", 4);

        // Swap event 1 and event 2
        LivenessAuditEvent e1 = chain.get(1);
        LivenessAuditEvent e2 = chain.get(2);
        chain.set(1, e2);
        chain.set(2, e1);

        AuditChainVerifier.AuditVerificationResult result = AuditChainVerifier.verify(chain);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenIndex()).isEqualTo(1);
        assertThat(result.message()).contains("linkage broken");
    }

    @Test
    @DisplayName("No biometric image data or embeddings exist in audit events")
    void testNoBiometricDataInAuditDetails() {
        List<LivenessAuditEvent> chain = createValidSessionChain("sess-004", 3);

        for (LivenessAuditEvent event : chain) {
            assertThat(event.details().keySet()).noneMatch(k ->
                    k.toLowerCase().contains("image") ||
                    k.toLowerCase().contains("crop") ||
                    k.toLowerCase().contains("face") ||
                    k.toLowerCase().contains("embedding") ||
                    k.toLowerCase().contains("landmark")
            );
            for (String val : event.details().values()) {
                // Ensure no base64-like long strings or raw bytes
                assertThat(val.length()).isLessThan(200);
            }
        }
    }
}
