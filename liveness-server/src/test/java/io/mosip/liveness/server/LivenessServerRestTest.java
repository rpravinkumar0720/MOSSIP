package io.mosip.liveness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.mosip.liveness.api.audit.AuditEventType;
import io.mosip.liveness.api.audit.LivenessAuditEvent;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.policy.WorkflowType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessServerRestTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private LivenessServerApp serverApp;
    private Javalin app;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        ServerConfig config = new ServerConfig(0, true, null, null, 30, true);
        serverApp = new LivenessServerApp(config);
        app = serverApp.createApp();
        app.start(0);
        port = app.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        serverApp.stop();
    }

    @Test
    @DisplayName("GET /api/health returns UP and version details")
    void healthEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/health"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = mapper.readTree(resp.body());
        assertThat(json.path("status").asText()).isEqualTo("UP");
        assertThat(json.path("engineVersion").asText()).contains("mosip-liveness-core");
        assertThat(json.path("mode").asText()).isEqualTo("diagnostic");
    }

    @Test
    @DisplayName("GET /api/policy/{workflow} returns effective parameters")
    void policyEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/policy/RESIDENT_AUTH"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = mapper.readTree(resp.body());
        assertThat(json.path("workflow").asText()).isEqualTo("RESIDENT_AUTH");
        assertThat(json.path("passiveThreshold").asDouble()).isGreaterThan(0.0);
        assertThat(json.path("maxRetries").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("POST /api/audit/verify correctly validates chain integrity")
    void auditVerifyEndpoint() throws Exception {
        String session = "test-session-123";
        LivenessAuditEvent ev1 = LivenessAuditEvent.builder()
                .sessionId(session)
                .workflow(WorkflowType.RESIDENT_AUTH)
                .eventType(AuditEventType.SESSION_START)
                .decision(DecisionType.RETRY)
                .previousHash("0000000000000000000000000000000000000000000000000000000000000000")
                .build();

        LivenessAuditEvent ev2 = LivenessAuditEvent.builder()
                .sessionId(session)
                .workflow(WorkflowType.RESIDENT_AUTH)
                .eventType(AuditEventType.FINAL_DECISION)
                .decision(DecisionType.ACCEPTED)
                .previousHash(ev1.currentHash())
                .build();

        List<LivenessAuditEvent> validChain = List.of(ev1, ev2);
        String body = mapper.writeValueAsString(validChain);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/audit/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = mapper.readTree(resp.body());
        assertThat(json.path("valid").asBoolean()).isTrue();
        assertThat(json.path("firstBrokenIndex").asInt()).isEqualTo(-1);

        // Tamper with second event
        LivenessAuditEvent tampered = new LivenessAuditEvent(
                ev2.eventId(),
                ev2.sessionId(),
                ev2.timestampMillis(),
                ev2.workflow(),
                ev2.eventType(),
                DecisionType.REJECTED, // Tampered!
                ev2.passiveScore(),
                ev2.padClassification(),
                ev2.padScore(),
                ev2.challengeType(),
                ev2.errorCode(),
                ev2.modelVersion(),
                ev2.deviceId(),
                ev2.details(),
                ev2.previousHash(),
                ev2.currentHash()
        );

        String tamperedBody = mapper.writeValueAsString(List.of(ev1, tampered));
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/audit/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(tamperedBody))
                .build();

        HttpResponse<String> resp2 = client.send(req2, HttpResponse.BodyHandlers.ofString());
        assertThat(resp2.statusCode()).isEqualTo(200);
        JsonNode json2 = mapper.readTree(resp2.body());
        assertThat(json2.path("valid").asBoolean()).isFalse();
        assertThat(json2.path("firstBrokenIndex").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/supervisor/override records override audit event in demo mode")
    void supervisorOverrideEndpoint() throws Exception {
        Map<String, String> payload = Map.of(
                "targetSessionId", "locked-session-456",
                "supervisorSessionId", "sup-789"
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/supervisor/override"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = mapper.readTree(resp.body());
        assertThat(json.path("success").asBoolean()).isTrue();

        // Check that override event is stored in audit sink
        List<LivenessAuditEvent> events = serverApp.getMemoryAuditSink().getEventsForSession("locked-session-456");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo(AuditEventType.SUPERVISOR_OVERRIDE);
    }

    @Test
    @DisplayName("GET /api/policy returns all workflow policies")
    void allPoliciesEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/policy"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = mapper.readTree(resp.body());
        assertThat(json.has("RESIDENT_AUTH")).isTrue();
        assertThat(json.has("RESIDENT_ENROLLMENT")).isTrue();
        assertThat(json.has("OPERATOR_AUTH")).isTrue();
        assertThat(json.has("SUPERVISOR_AUTH")).isTrue();
    }

    @Test
    @DisplayName("GET /api/audit/verify validates system in-memory audit log")
    void getAuditVerifyEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/audit/verify"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = mapper.readTree(resp.body());
        assertThat(json.path("valid").asBoolean()).isTrue();
        assertThat(json.path("tamperFree").asBoolean()).isTrue();
    }
}
