package io.mosip.liveness.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.mosip.liveness.api.audit.LivenessAuditEvent;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;
import io.mosip.liveness.core.analyzer.ClientLandmarkAnalyzer;
import io.mosip.liveness.core.audit.AuditChainVerifier;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.FileAuditSink;
import io.mosip.liveness.core.audit.InMemoryAuditSink;
import io.mosip.liveness.core.challenge.ChallengeGenerator;
import io.mosip.liveness.core.engine.DefaultLivenessEngine;
import io.mosip.liveness.core.passive.PassiveAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Javalin application configuring REST endpoints, WebSocket streaming,
 * and static assets for the HTML5 webcam client.
 */
public class LivenessServerApp {

    private static final Logger log = LoggerFactory.getLogger(LivenessServerApp.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ServerConfig config;
    private final DefaultLivenessEngine engine;
    private final InMemoryAuditSink memoryAuditSink;
    private final WebSocketHandler wsHandler;
    private Javalin app;

    public LivenessServerApp(ServerConfig config) {
        this.config = config;
        this.memoryAuditSink = new InMemoryAuditSink(10_000);

        AuditSink effectiveSink = this.memoryAuditSink;
        if (config.auditFilePath() != null && !config.auditFilePath().isBlank()) {
            try {
                FileAuditSink fileSink = new FileAuditSink(new java.io.File(config.auditFilePath()));
                effectiveSink = new CompositeAuditSink(memoryAuditSink, fileSink);
            } catch (IOException e) {
                log.error("Failed to initialize FileAuditSink at {}: {}", config.auditFilePath(), e.getMessage());
            }
        }

        this.engine = new DefaultLivenessEngine(
                new ClientLandmarkAnalyzer(),
                new PassiveAnalyzer(),
                new ChallengeGenerator(),
                effectiveSink,
                config.modelPath()
        );
        this.engine.setLockoutDurationMillis(config.lockoutSeconds() * 1000L);
        this.wsHandler = new WebSocketHandler(this.engine, config);
    }

    public Javalin createApp() {
        this.app = Javalin.create(javalinConfig -> {
            javalinConfig.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
            });
        });

        // WebSocket endpoint
        app.ws("/ws/liveness", ws -> {
            ws.onConnect(wsHandler::onConnect);
            ws.onMessage(wsHandler::onMessage);
            ws.onClose(wsHandler::onClose);
            ws.onError(wsHandler::onError);
        });

        // REST endpoints
        long serverStartTimeMillis = System.currentTimeMillis();

        app.get("/api/health", ctx -> {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "UP");
            resp.put("healthy", true);
            resp.put("engineVersion", engine.getEngineVersion());
            resp.put("mode", config.diagnosticMode() ? "diagnostic" : "normal");
            resp.put("demo", config.demoMode());
            resp.put("lockoutSeconds", config.lockoutSeconds());
            resp.put("uptimeSeconds", (System.currentTimeMillis() - serverStartTimeMillis) / 1000);
            resp.put("totalAuditEvents", memoryAuditSink.getAllEvents().size());
            resp.put("auditChainIntegrity", AuditChainVerifier.verify(memoryAuditSink.getAllEvents()).valid());
            resp.put("activeWebSocketSessions", wsHandler.getActiveSessionCount());
            resp.put("timestamp", java.time.Instant.now().toString());
            ctx.json(resp);
        });

        // Audit endpoints
        app.get("/api/audit", ctx -> {
            List<LivenessAuditEvent> allEvents = memoryAuditSink.getAllEvents();
            ctx.json(allEvents);
        });

        app.get("/api/audit/verify", ctx -> {
            List<LivenessAuditEvent> allEvents = memoryAuditSink.getAllEvents();
            AuditChainVerifier.AuditVerificationResult result = AuditChainVerifier.verify(allEvents);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("valid", result.valid());
            resp.put("count", allEvents.size());
            resp.put("firstBrokenIndex", result.firstBrokenIndex());
            resp.put("message", result.message());
            resp.put("tamperFree", result.valid());
            ctx.json(resp);
        });

        app.get("/api/audit/{sessionId}", ctx -> {
            String sessionId = ctx.pathParam("sessionId");
            List<LivenessAuditEvent> events = memoryAuditSink.getEventsForSession(sessionId);
            ctx.json(events);
        });

        app.get("/api/audit/{sessionId}/verify", ctx -> {
            String sessionId = ctx.pathParam("sessionId");
            List<LivenessAuditEvent> events = memoryAuditSink.getEventsForSession(sessionId);
            AuditChainVerifier.AuditVerificationResult result = AuditChainVerifier.verify(events);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("sessionId", sessionId);
            resp.put("valid", result.valid());
            resp.put("count", events.size());
            resp.put("firstBrokenIndex", result.firstBrokenIndex());
            resp.put("message", result.message());
            resp.put("tamperFree", result.valid());
            ctx.json(resp);
        });

        app.post("/api/audit/verify", ctx -> {
            try {
                List<LivenessAuditEvent> events = mapper.readValue(
                        ctx.body(),
                        new TypeReference<List<LivenessAuditEvent>>() {}
                );
                AuditChainVerifier.AuditVerificationResult result = AuditChainVerifier.verify(events);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("valid", result.valid());
                resp.put("count", events != null ? events.size() : 0);
                resp.put("firstBrokenIndex", result.firstBrokenIndex());
                resp.put("message", result.message());
                resp.put("tamperFree", result.valid());
                ctx.json(resp);
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                        "valid", false,
                        "firstBrokenIndex", -1,
                        "message", "Malformed audit events payload: " + e.getMessage()
                ));
            }
        });

        // Policy endpoints
        app.get("/api/policy", ctx -> {
            Map<String, Object> allPolicies = new LinkedHashMap<>();
            for (WorkflowType wf : WorkflowType.values()) {
                LivenessPolicy p = resolveWorkflowPolicy(wf);
                allPolicies.put(wf.name(), serializePolicy(wf, p));
            }
            ctx.json(allPolicies);
        });

        app.get("/api/policy/{workflow}", ctx -> {
            String wfParam = ctx.pathParam("workflow").trim().toUpperCase().replace("-", "_");
            WorkflowType wf;
            try {
                wf = WorkflowType.valueOf(wfParam);
            } catch (IllegalArgumentException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                        "error", "Unknown workflow: " + wfParam,
                        "validWorkflows", List.of("RESIDENT_AUTH", "RESIDENT_ENROLLMENT", "OPERATOR_AUTH", "SUPERVISOR_AUTH")
                ));
                return;
            }

            LivenessPolicy policy = resolveWorkflowPolicy(wf);
            ctx.json(serializePolicy(wf, policy));
        });

        app.post("/api/supervisor/override", ctx -> {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                String targetSessionId = body.path("targetSessionId").asText("");
                String supervisorSessionId = body.path("supervisorSessionId").asText("");

                if (targetSessionId.isBlank()) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "targetSessionId is required"));
                    return;
                }

                // Verify supervisor session if in strict mode, or check that it was accepted
                boolean authorized = false;
                if (supervisorSessionId.isBlank()) {
                    if (config.demoMode()) {
                        // Demo shortcut allows supervisor override with placeholder supervisor id
                        supervisorSessionId = "demo-supervisor";
                        authorized = true;
                    }
                } else {
                    List<LivenessAuditEvent> supervisorEvents = memoryAuditSink.getEventsForSession(supervisorSessionId);
                    boolean hasAccepted = supervisorEvents.stream()
                            .anyMatch(ev -> ev.decision() == DecisionType.ACCEPTED && ev.workflow() == WorkflowType.SUPERVISOR_AUTH);
                    authorized = hasAccepted || config.demoMode();
                }

                if (!authorized) {
                    ctx.status(HttpStatus.FORBIDDEN).json(Map.of(
                            "error", "Supervisor override requires a valid prior ACCEPTED SUPERVISOR_AUTH session."
                    ));
                    return;
                }

                engine.recordSupervisorOverride(targetSessionId, supervisorSessionId);
                ctx.json(Map.of(
                        "success", true,
                        "targetSessionId", targetSessionId,
                        "supervisorSessionId", supervisorSessionId,
                        "message", "Supervisor override successfully recorded in audit log."
                ));
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid request body: " + e.getMessage()));
            }
        });

        return app;
    }

    private static LivenessPolicy resolveWorkflowPolicy(WorkflowType wf) {
        return switch (wf) {
            case RESIDENT_ENROLLMENT -> LivenessPolicy.residentEnrollment();
            case RESIDENT_AUTH -> LivenessPolicy.residentAuth();
            case OPERATOR_AUTH -> LivenessPolicy.operatorAuth();
            case SUPERVISOR_AUTH -> LivenessPolicy.supervisorAuth();
        };
    }

    private static Map<String, Object> serializePolicy(WorkflowType wf, LivenessPolicy policy) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("workflow", wf.name());
        resp.put("description", switch (wf) {
            case RESIDENT_AUTH -> "Rapid passive-first citizen authentication for kiosks and mobile apps";
            case RESIDENT_ENROLLMENT -> "High-assurance passive + active biometric enrollment verification";
            case OPERATOR_AUTH -> "Operator workstation credential validation with rapid active challenges";
            case SUPERVISOR_AUTH -> "High-privilege supervisor authorization with strict attack threshold";
        });
        resp.put("passiveThreshold", policy.passiveThreshold());
        resp.put("padThreshold", policy.padThreshold());
        resp.put("activeEnabled", policy.activeEnabled());
        resp.put("minChallenges", policy.minChallenges());
        resp.put("maxRetries", policy.maxRetries());
        resp.put("passiveWindowFrames", policy.passiveWindowFrames());
        resp.put("activeTimeoutSeconds", policy.activeTimeoutSeconds());
        resp.put("failureBehaviour", policy.failureBehaviour().name());
        resp.put("allowedChallenges", policy.allowedChallengeTypes().stream().map(Enum::name).toList());
        return resp;
    }

    public void start() {
        if (app == null) {
            createApp();
        }
        app.start(config.port());
        log.info("MOSIP Liveness Server started on port {} (mode={}, demo={})",
                config.port(), config.diagnosticMode() ? "diagnostic" : "normal", config.demoMode());
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
        engine.close();
    }

    public DefaultLivenessEngine getEngine() {
        return engine;
    }

    public InMemoryAuditSink getMemoryAuditSink() {
        return memoryAuditSink;
    }

    private static class CompositeAuditSink implements AuditSink {
        private final InMemoryAuditSink memory;
        private final FileAuditSink file;

        CompositeAuditSink(InMemoryAuditSink memory, FileAuditSink file) {
            this.memory = memory;
            this.file = file;
        }

        @Override
        public synchronized void append(LivenessAuditEvent event) {
            memory.append(event);
            if (file != null) {
                file.append(event);
            }
        }

        @Override
        public List<LivenessAuditEvent> getEventsForSession(String sessionId) {
            return memory.getEventsForSession(sessionId);
        }

        @Override
        public void close() {
            memory.close();
            if (file != null) {
                file.close();
            }
        }
    }
}
