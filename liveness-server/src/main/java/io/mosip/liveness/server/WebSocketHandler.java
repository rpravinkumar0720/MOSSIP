package io.mosip.liveness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import io.mosip.liveness.api.challenge.Challenge;
import io.mosip.liveness.api.challenge.ChallengeResult;
import io.mosip.liveness.api.classifier.PadResult;
import io.mosip.liveness.api.classifier.PassiveLivenessResult;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.decision.LivenessDecision;
import io.mosip.liveness.api.engine.LivenessEngine;
import io.mosip.liveness.api.engine.LivenessListener;
import io.mosip.liveness.api.engine.LivenessSession;
import io.mosip.liveness.api.error.LivenessError;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.frame.PixelFormat;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;
import io.mosip.liveness.api.quality.BoundingBox;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.FaceQuality;
import io.mosip.liveness.api.quality.Point2D;
import io.mosip.liveness.api.quality.QualityFailureReason;
import io.mosip.liveness.core.analyzer.ClientLandmarkAnalyzer;
import io.mosip.liveness.core.analyzer.FaceObservation;
import io.mosip.liveness.core.analyzer.LandmarkGeometry;
import io.mosip.liveness.core.engine.DefaultLivenessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles WebSocket sessions for real-time video frame streaming and liveness detection.
 */
public class WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // 15 FPS rate limit: minimum 66.6 ms between frames
    private static final long MIN_FRAME_INTERVAL_NANOS = 66_000_000L;
    private static final int MAX_FRAME_BYTES = 1_048_576; // 1 MB
    private static final int MAX_DIMENSION = 1280;

    private final DefaultLivenessEngine engine;
    private final ServerConfig config;
    private final Map<String, SocketSessionState> sessions = new ConcurrentHashMap<>();

    public WebSocketHandler(DefaultLivenessEngine engine, ServerConfig config) {
        this.engine = engine;
        this.config = config;
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    private static class SocketSessionState {
        final WsConnectContext ws;
        final String lang;
        final WorkflowType workflow;
        LivenessSession session;
        long lastFrameNanos = 0L;
        long lastActivityMillis = System.currentTimeMillis();

        SocketSessionState(WsConnectContext ws, String lang, WorkflowType workflow) {
            this.ws = ws;
            this.lang = lang != null ? lang : "en";
            this.workflow = workflow != null ? workflow : WorkflowType.RESIDENT_AUTH;
        }
    }

    public void onConnect(WsConnectContext ctx) {
        String workflowParam = ctx.queryParam("workflow");
        String lang = ctx.queryParam("lang");

        WorkflowType workflow = WorkflowType.RESIDENT_AUTH;
        if (workflowParam != null) {
            try {
                workflow = WorkflowType.valueOf(workflowParam.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        SocketSessionState state = new SocketSessionState(ctx, lang, workflow);
        LivenessPolicy policy = resolvePolicy(workflow, config.demoMode());

        LivenessListener listener = createListener(state);
        String sessionId = java.util.UUID.randomUUID().toString();
        LivenessSession session = engine.startSession(sessionId, workflow, policy, listener);
        state.session = session;
        sessions.put(ctx.sessionId(), state);

        log.info("WebSocket connected: socketId={}, livenessSessionId={}, workflow={}, lang={}",
                ctx.sessionId(), session.getSessionId(), workflow, lang);
    }

    public void onMessage(WsMessageContext ctx) {
        SocketSessionState state = sessions.get(ctx.sessionId());
        if (state == null || state.session == null || state.session.isClosed()) {
            return;
        }
        state.lastActivityMillis = System.currentTimeMillis();

        String message = ctx.message();
        if (message == null || message.isBlank()) return;

        try {
            JsonNode root = mapper.readTree(message);
            String type = root.path("type").asText("");

            if ("cancel".equalsIgnoreCase(type)) {
                log.info("Client requested session cancel: socketId={}", ctx.sessionId());
                engine.cancelSession(state.session);
                ctx.closeSession();
                sessions.remove(ctx.sessionId());
                return;
            }

            if ("frame".equalsIgnoreCase(type)) {
                handleFrameMessage(state, root);
            }
        } catch (Exception e) {
            log.error("Error processing WebSocket message for socket {}", ctx.sessionId(), e);
            sendJson(state.ws, Map.of(
                    "type", "error",
                    "code", LivenessError.ENGINE_ERROR.getErrorCode(),
                    "message", LocalizationService.translate("DECISION_ERROR", "Internal error processing frame", state.lang)
            ));
        }
    }

    private void handleFrameMessage(SocketSessionState state, JsonNode root) throws IOException {
        long nowNanos = System.nanoTime();
        if (nowNanos - state.lastFrameNanos < MIN_FRAME_INTERVAL_NANOS) {
            // Drop frame to enforce 15 FPS rate limit
            return;
        }
        state.lastFrameNanos = nowNanos;

        String b64 = root.path("jpegB64").asText();
        if (b64.isEmpty()) return;

        // Strip data:image/jpeg;base64, prefix if present
        int commaIdx = b64.indexOf(',');
        if (commaIdx >= 0) {
            b64 = b64.substring(commaIdx + 1);
        }

        byte[] jpegBytes = Base64.getDecoder().decode(b64);
        if (jpegBytes.length > MAX_FRAME_BYTES) {
            sendJson(state.ws, Map.of(
                    "type", "error",
                    "code", LivenessError.INVALID_FRAME_DATA.getErrorCode(),
                    "message", "Frame exceeds maximum size (1 MB)"
            ));
            return;
        }

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        if (img == null) {
            return;
        }

        int w = img.getWidth();
        int h = img.getHeight();
        if (w > MAX_DIMENSION || h > MAX_DIMENSION || w <= 0 || h <= 0) {
            sendJson(state.ws, Map.of(
                    "type", "error",
                    "code", LivenessError.INVALID_FRAME_DATA.getErrorCode(),
                    "message", "Frame dimensions out of bounds"
            ));
            return;
        }

        // Convert BufferedImage to RGB byte array
        byte[] rgbBytes = new byte[w * h * 3];
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            rgbBytes[i * 3] = (byte) ((p >> 16) & 0xFF);
            rgbBytes[i * 3 + 1] = (byte) ((p >> 8) & 0xFF);
            rgbBytes[i * 3 + 2] = (byte) (p & 0xFF);
        }

        long clientTsMs = root.path("tsMs").asLong(System.currentTimeMillis());
        long seq = root.path("seq").asLong(0L);

        // Parse landmarks if provided
        List<Point2D> points = parseLandmarks(root.path("landmarks"));
        if (!points.isEmpty()) {
            if (engine.getFaceAnalyzer() instanceof ClientLandmarkAnalyzer clientAnalyzer) {
                clientAnalyzer.pushLandmarks(state.session.getSessionId(), points, 0.99, 1);
            }
        }

        FaceFrame frame = FaceFrame.wrap(
                rgbBytes,
                w,
                h,
                PixelFormat.RGB_888,
                nowNanos,
                seq,
                state.session.getSessionId(),
                0
        );

        try {
            LivenessDecision decision = engine.processFrame(state.session, frame);

            // If diagnostic mode is enabled, send diagnostic telemetry
            if (config.diagnosticMode()) {
                sendDiagnosticIfAvailable(state);
            }
        } finally {
            if (!frame.isWiped()) {
                frame.wipe();
            }
        }
    }

    private List<Point2D> parseLandmarks(JsonNode lmNode) {
        if (lmNode == null || !lmNode.isArray()) return List.of();
        List<Point2D> result = new ArrayList<>(lmNode.size());
        for (JsonNode pt : lmNode) {
            double x, y;
            if (pt.isArray() && pt.size() >= 2) {
                x = pt.get(0).asDouble();
                y = pt.get(1).asDouble();
            } else if (pt.isObject()) {
                x = pt.path("x").asDouble();
                y = pt.path("y").asDouble();
            } else {
                continue;
            }
            if (Double.isFinite(x) && Double.isFinite(y)) {
                result.add(new Point2D((float) Math.max(0.0, Math.min(1.0, x)), (float) Math.max(0.0, Math.min(1.0, y))));
            }
        }
        return result;
    }

    private void sendDiagnosticIfAvailable(SocketSessionState state) {
        if (state.session == null) return;
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("type", "diagnostic");
        diag.put("sessionId", state.session.getSessionId());
        diag.put("engineVersion", engine.getEngineVersion());
        diag.put("weights", engine.getPassiveAnalyzer().getWeights().asMap());
        sendJson(state.ws, diag);
    }

    public void onClose(WsCloseContext ctx) {
        SocketSessionState state = sessions.remove(ctx.sessionId());
        if (state != null && state.session != null && !state.session.isClosed()) {
            engine.cancelSession(state.session);
        }
        log.info("WebSocket closed: socketId={}", ctx.sessionId());
    }

    public void onError(WsErrorContext ctx) {
        log.warn("WebSocket error for socket {}: {}", ctx.sessionId(), ctx.error() != null ? ctx.error().getMessage() : "unknown");
    }

    private LivenessListener createListener(SocketSessionState state) {
        return new LivenessListener() {
            @Override
            public void onStateChanged(LivenessSession session, String stateName, String prompt) {
                String localizedPrompt = LocalizationService.translate("STATE_" + stateName, prompt, state.lang);
                sendJson(state.ws, Map.of(
                        "type", "state",
                        "state", stateName,
                        "prompt", localizedPrompt
                ));
            }

            @Override
            public void onQualityFeedback(LivenessSession session, FaceQuality quality, String guidanceMessage) {
                QualityFailureReason primaryReason = quality.qualityFailures().isEmpty()
                        ? null
                        : quality.qualityFailures().iterator().next();
                String localizedGuidance = primaryReason != null
                        ? LocalizationService.getQualityGuidance(primaryReason, state.lang)
                        : (guidanceMessage != null ? guidanceMessage : "");

                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("blur", quality.blurScore());
                metrics.put("yaw", quality.poseYaw());
                metrics.put("pitch", quality.posePitch());
                metrics.put("roll", quality.poseRoll());
                metrics.put("ear", (quality.eyeOpenLeft() + quality.eyeOpenRight()) / 2.0);
                metrics.put("mar", quality.mouthOpen());
                metrics.put("luma", quality.brightnessScore());
                metrics.put("faceAreaRatio", quality.faceSizeRatio());
                metrics.put("isCompliant", quality.isCompliant());

                sendJson(state.ws, Map.of(
                        "type", "quality",
                        "guidance", localizedGuidance,
                        "metrics", metrics
                ));
            }

            @Override
            public void onPassiveProgress(LivenessSession session, int collectedFrames, int totalFramesRequired) {
                sendJson(state.ws, Map.of(
                        "type", "progress",
                        "collected", collectedFrames,
                        "window", totalFramesRequired
                ));
            }

            @Override
            public void onChallengeIssued(LivenessSession session, Challenge challenge) {
                String prompt = LocalizationService.getChallengePrompt(challenge.type(), state.lang);
                sendJson(state.ws, Map.of(
                        "type", "challenge",
                        "challengeType", challenge.type().name(),
                        "prompt", prompt,
                        "timeoutMs", challenge.timeoutMillis()
                ));
            }

            @Override
            public void onDecision(LivenessSession session, LivenessDecision decision) {
                // Rule 6.5: For ANY PAD attack, force generic message!
                String userMsg;
                if (decision.decisionType() == DecisionType.REJECTED) {
                    userMsg = LocalizationService.translate("DECISION_REJECTED",
                            LivenessError.PAD_FAILURE.getDefaultUserMessage(), state.lang);
                } else if (decision.decisionType() == DecisionType.ACCEPTED) {
                    userMsg = LocalizationService.translate("DECISION_ACCEPTED",
                            "Face verification successful.", state.lang);
                } else if (decision.decisionType() == DecisionType.FAILED_MAX_RETRIES) {
                    userMsg = LocalizationService.translate("DECISION_FAILED_MAX_RETRIES",
                            LivenessError.MAX_RETRIES_EXCEEDED.getDefaultUserMessage(), state.lang);
                } else {
                    userMsg = LocalizationService.getDecisionMessage(decision.decisionType(), state.lang);
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("type", "decision");
                resp.put("decision", decision.decisionType().name());
                resp.put("userMessage", userMsg);
                resp.put("score", decision.finalScore());
                resp.put("sessionId", session.getSessionId());

                sendJson(state.ws, resp);
            }

            @Override
            public void onError(LivenessSession session, LivenessError error, String technicalDetails) {
                sendJson(state.ws, Map.of(
                        "type", "error",
                        "code", error.getErrorCode(),
                        "message", error.getDefaultUserMessage()
                ));
            }
        };
    }

    private void sendJson(WsConnectContext ws, Object payload) {
        if (ws == null) return;
        try {
            String json = mapper.writeValueAsString(payload);
            ws.send(json);
        } catch (Exception e) {
            log.warn("Failed to send WebSocket message: {}", e.getMessage());
        }
    }

    private static LivenessPolicy resolvePolicy(WorkflowType workflow, boolean demoMode) {
        LivenessPolicy base = switch (workflow) {
            case RESIDENT_ENROLLMENT -> LivenessPolicy.residentEnrollment();
            case RESIDENT_AUTH -> LivenessPolicy.residentAuth();
            case OPERATOR_AUTH -> LivenessPolicy.operatorAuth();
            case SUPERVISOR_AUTH -> LivenessPolicy.supervisorAuth();
        };

        if (demoMode) {
            // Relax timeout slightly in demo mode (e.g. 6 seconds instead of 4)
            return LivenessPolicy.builder()
                    .passiveThreshold(base.passiveThreshold())
                    .padThreshold(base.padThreshold())
                    .activeEnabled(base.activeEnabled())
                    .allowedChallengeTypes(base.allowedChallengeTypes())
                    .minChallenges(base.minChallenges())
                    .activeTimeoutSeconds(6)
                    .maxRetries(base.maxRetries())
                    .passiveWindowFrames(base.passiveWindowFrames())
                    .failureBehaviour(base.failureBehaviour())
                    .build();
        }
        return base;
    }
}
