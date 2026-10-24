package io.mosip.liveness.core.audit;

import io.mosip.liveness.api.audit.AuditEventType;
import io.mosip.liveness.api.audit.LivenessAuditEvent;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.policy.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditVerifierCliTest {

    @Test
    @DisplayName("parseJsonEvent accurately parses audit JSON representation")
    void testParseJsonEvent() {
        String json = "{\"eventId\":\"ev-1\",\"sessionId\":\"sess-1\",\"timestampMillis\":1700000000000," +
                "\"workflow\":\"RESIDENT_AUTH\",\"eventType\":\"SESSION_START\",\"decision\":\"RETRY\"," +
                "\"passiveScore\":0.82,\"padClassification\":\"GENUINE\",\"padScore\":0.18," +
                "\"challengeType\":\"BLINK\",\"errorCode\":null,\"previousHash\":\"0000000000000000000000000000000000000000000000000000000000000000\"," +
                "\"currentHash\":\"abc123hash\",\"signature\":null,\"details\":{\"client\":\"unit-test\",\"seq\":\"1\"}}";

        LivenessAuditEvent ev = AuditVerifierCli.parseJsonEvent(json);

        assertThat(ev.eventId()).isEqualTo("ev-1");
        assertThat(ev.sessionId()).isEqualTo("sess-1");
        assertThat(ev.timestampMillis()).isEqualTo(1700000000000L);
        assertThat(ev.workflow()).isEqualTo(WorkflowType.RESIDENT_AUTH);
        assertThat(ev.eventType()).isEqualTo(AuditEventType.SESSION_START);
        assertThat(ev.decision()).isEqualTo(DecisionType.RETRY);
        assertThat(ev.passiveScore()).isEqualTo(0.82);
        assertThat(ev.padScore()).isEqualTo(0.18);
        assertThat(ev.previousHash()).isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(ev.details()).containsEntry("client", "unit-test");
        assertThat(ev.details()).containsEntry("seq", "1");
    }

    @Test
    @DisplayName("AuditVerifierCli verifies a valid chain from file")
    void testCliVerificationValidChain(@TempDir Path tempDir) throws Exception {
        File auditFile = tempDir.resolve("audit.jsonl").toFile();

        LivenessAuditEvent ev1 = LivenessAuditEvent.builder()
                .sessionId("sess-1")
                .previousHash(AuditChainVerifier.GENESIS_HASH)
                .workflow(WorkflowType.RESIDENT_AUTH)
                .eventType(AuditEventType.SESSION_START)
                .details(Map.of("step", "init"))
                .build();

        LivenessAuditEvent ev2 = LivenessAuditEvent.builder()
                .sessionId("sess-1")
                .previousHash(ev1.currentHash())
                .workflow(WorkflowType.RESIDENT_AUTH)
                .eventType(AuditEventType.FINAL_DECISION)
                .decision(DecisionType.ACCEPTED)
                .passiveScore(0.95)
                .padScore(0.05)
                .details(Map.of("step", "accept"))
                .build();

        try (FileWriter writer = new FileWriter(auditFile, StandardCharsets.UTF_8)) {
            writer.write(FileAuditSink.toJsonLine(ev1) + "\n");
            writer.write(FileAuditSink.toJsonLine(ev2) + "\n");
        }

        // Test CLI execution
        int exitCode = AuditVerifierCli.run(new String[]{auditFile.getAbsolutePath()});
        assertThat(exitCode).isEqualTo(0);
    }
}
