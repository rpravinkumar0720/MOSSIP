package io.mosip.liveness.core.engine;

import io.mosip.liveness.api.audit.AuditEventType;
import io.mosip.liveness.api.audit.LivenessAuditEvent;
import io.mosip.liveness.api.challenge.Challenge;
import io.mosip.liveness.api.challenge.ChallengeResult;
import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.classifier.PadClassification;
import io.mosip.liveness.api.classifier.PassiveLivenessResult;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.decision.LivenessDecision;
import io.mosip.liveness.api.engine.LivenessEngine;
import io.mosip.liveness.api.engine.LivenessListener;
import io.mosip.liveness.api.engine.LivenessSession;
import io.mosip.liveness.api.error.LivenessError;
import io.mosip.liveness.api.error.LivenessException;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.policy.FailureBehaviour;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;
import io.mosip.liveness.api.quality.FaceQuality;
import io.mosip.liveness.core.analyzer.ClientLandmarkAnalyzer;
import io.mosip.liveness.core.analyzer.FaceAnalyzer;
import io.mosip.liveness.core.analyzer.FaceObservation;
import io.mosip.liveness.core.analyzer.OnnxFaceAnalyzer;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.InMemoryAuditSink;
import io.mosip.liveness.core.challenge.ChallengeGenerator;
import io.mosip.liveness.core.challenge.ChallengeVerifier;
import io.mosip.liveness.core.passive.FrameObservationRecord;
import io.mosip.liveness.core.passive.PassiveAnalyzer;
import io.mosip.liveness.core.quality.QualityEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standard implementation of MOSIP LivenessEngine.
 * Orchestrates multi-stage state transitions:
 * FACE_POSITIONING -> PASSIVE_CHECK -> ACTIVE_CHALLENGE -> DECIDED
 * Integrates ISO 19794-5 quality gating, 7-signal passive PAD heuristics,
 * dynamic anti-replay active challenges, and SHA-256 hash-chained audit logging.
 */
public class DefaultLivenessEngine implements LivenessEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultLivenessEngine.class);
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    public enum EngineState {
        FACE_POSITIONING,
        PASSIVE_CHECK,
        ACTIVE_CHALLENGE,
        DECIDED,
        CANCELLED,
        ERROR
    }

    private static class SessionContext {
        final LivenessSession session;
        final LivenessListener listener;
        final List<FrameObservationRecord> frameWindow = new ArrayList<>();
        final List<ChallengeResult> completedChallenges = new ArrayList<>();

        EngineState state = EngineState.FACE_POSITIONING;
        int consecutiveCompliantFrames = 0;
        PassiveLivenessResult latestPassiveResult = null;
        ChallengeVerifier currentVerifier = null;
        String auditTailHash = GENESIS_HASH;

        FaceFrame bestCompliantFrame = null;
        double bestQualityScore = -1.0;

        SessionContext(LivenessSession session, LivenessListener listener) {
            this.session = session;
            this.listener = listener;
        }

        void updateBestFrame(FaceFrame frame, FaceQuality quality) {
            double frontality = 1.0 - Math.min(1.0, Math.abs(quality.poseYaw()) / 30.0);
            double score = quality.blurScore() * frontality;
            if (score > bestQualityScore && !frame.isWiped()) {
                bestQualityScore = score;
                // Defensive clone of best frame
                this.bestCompliantFrame = new FaceFrame(
                        frame.getDataCopy(),
                        frame.getWidth(),
                        frame.getHeight(),
                        frame.getFormat(),
                        frame.getTimestampNanos(),
                        frame.getSequenceNumber(),
                        frame.getDeviceId(),
                        frame.getRotationDegrees()
                );
            }
        }
    }

    private final FaceAnalyzer faceAnalyzer;
    private final PassiveAnalyzer passiveAnalyzer;
    private final ChallengeGenerator challengeGenerator;
    private final AuditSink auditSink;
    private final OnnxFaceAnalyzer onnxAnalyzer;

    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lockoutMap = new ConcurrentHashMap<>();
    private long lockoutDurationMillis = 60_000L; // default 60s demo lockout

    public DefaultLivenessEngine() {
        this(new ClientLandmarkAnalyzer(), new PassiveAnalyzer(), new ChallengeGenerator(), new InMemoryAuditSink(), null);
    }

    public DefaultLivenessEngine(FaceAnalyzer analyzer,
                                 PassiveAnalyzer passiveAnalyzer,
                                 ChallengeGenerator challengeGenerator,
                                 AuditSink auditSink,
                                 String onnxModelPath) {
        this.faceAnalyzer = analyzer != null ? analyzer : new ClientLandmarkAnalyzer();
        this.passiveAnalyzer = passiveAnalyzer != null ? passiveAnalyzer : new PassiveAnalyzer();
        this.challengeGenerator = challengeGenerator != null ? challengeGenerator : new ChallengeGenerator();
        this.auditSink = auditSink != null ? auditSink : new InMemoryAuditSink();
        this.onnxAnalyzer = new OnnxFaceAnalyzer(onnxModelPath);
    }

    public void setLockoutDurationMillis(long millis) {
        this.lockoutDurationMillis = Math.max(1000L, millis);
    }

    public boolean isDeviceLockedOut(String deviceId) {
        if (deviceId == null) return false;
        Long until = lockoutMap.get(deviceId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            lockoutMap.remove(deviceId);
            return false;
        }
        return true;
    }

    public FaceAnalyzer getFaceAnalyzer() {
        return faceAnalyzer;
    }

    public AuditSink getAuditSink() {
        return auditSink;
    }

    @Override
    public LivenessSession startSession(String sessionId,
                                         WorkflowType workflow,
                                         LivenessPolicy policy,
                                         LivenessListener listener) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(workflow, "workflow cannot be null");
        Objects.requireNonNull(policy, "policy cannot be null");

        if (isDeviceLockedOut(sessionId)) {
            throw new LivenessException(LivenessError.MAX_RETRIES_EXCEEDED,
                    "Session creation refused: workstation currently locked out due to attack detection.");
        }

        LivenessSession session = new LivenessSession(sessionId, workflow, policy);
        SessionContext ctx = new SessionContext(session, listener);
        sessions.put(sessionId, ctx);

        // Record SESSION_START audit event
        recordAudit(ctx, AuditEventType.SESSION_START, null, null, null, null, null, null, Map.of());

        if (listener != null) {
            listener.onStateChanged(session, EngineState.FACE_POSITIONING.name(), "Look at the camera");
        }

        return session;
    }

    @Override
    public LivenessDecision processFrame(LivenessSession session, FaceFrame frame) {
        Objects.requireNonNull(session, "LivenessSession cannot be null");
        long arrivalNanos = System.nanoTime();

        try {
            if (session.isClosed()) {
                return LivenessDecision.error(session.getSessionId(), session.getWorkflow(), "Session is closed");
            }

            SessionContext ctx = sessions.get(session.getSessionId());
            if (ctx == null) {
                return LivenessDecision.error(session.getSessionId(), session.getWorkflow(), "Unknown session context");
            }

            // 1. Frame validation
            if (frame == null || frame.isWiped() || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                if (ctx.listener != null) {
                    ctx.listener.onError(session, LivenessError.INVALID_FRAME_DATA, "Corrupted or wiped frame");
                }
                recordAudit(ctx, AuditEventType.ERROR_OCCURRED, DecisionType.ERROR, null, null, null, null,
                        LivenessError.INVALID_FRAME_DATA.getErrorCode(), Map.of());
                return LivenessDecision.error(session.getSessionId(), session.getWorkflow(),
                        LivenessError.INVALID_FRAME_DATA.getDefaultUserMessage());
            }

            // Check lockout
            if (isDeviceLockedOut(frame.getDeviceId())) {
                return LivenessDecision.failedMaxRetries(session.getSessionId(), session.getWorkflow(),
                        "Device is temporarily locked out.");
            }

            // Check if session has already exceeded max retries
            if (session.getRetryCount() > session.getPolicy().maxRetries()) {
                return handleExceededRetries(ctx, session, frame.getDeviceId());
            }

            // 2. Face Analysis & Landmark observation
            Optional<FaceObservation> observationOpt = faceAnalyzer.analyze(session.getSessionId(), frame);
            FaceObservation observation = observationOpt.orElse(FaceObservation.none());

            // 3. ISO 19794-5 Quality gate evaluation
            FaceQuality quality = QualityEvaluator.evaluate(frame, observation);
            recordAudit(ctx, AuditEventType.QUALITY_CHECK, null, null, null, null, null, null,
                    Map.of("compliant", String.valueOf(quality.isCompliant())));

            // In ACTIVE_CHALLENGE, filter out the specific action requested by the active challenge
            java.util.Set<io.mosip.liveness.api.quality.QualityFailureReason> activeFailures =
                    new java.util.HashSet<>(quality.qualityFailures());
            if (ctx.state == EngineState.ACTIVE_CHALLENGE && session.getCurrentChallenge() != null) {
                ChallengeType chType = session.getCurrentChallenge().type();
                if (chType == ChallengeType.BLINK) {
                    activeFailures.remove(io.mosip.liveness.api.quality.QualityFailureReason.EYES_CLOSED);
                } else if (chType == ChallengeType.SMILE) {
                    activeFailures.remove(io.mosip.liveness.api.quality.QualityFailureReason.MOUTH_OPEN);
                } else if (chType == ChallengeType.TURN_LEFT || chType == ChallengeType.TURN_RIGHT) {
                    activeFailures.remove(io.mosip.liveness.api.quality.QualityFailureReason.POSE_YAW_EXCEEDED);
                } else if (chType == ChallengeType.LOOK_UP || chType == ChallengeType.NOD_HEAD) {
                    activeFailures.remove(io.mosip.liveness.api.quality.QualityFailureReason.POSE_PITCH_EXCEEDED);
                }
            }

            if (!activeFailures.isEmpty()) {
                String guidance = QualityEvaluator.getPrimaryGuidance(activeFailures);
                if (ctx.listener != null) {
                    ctx.listener.onQualityFeedback(session, quality, guidance);
                }
                ctx.consecutiveCompliantFrames = 0;
                // Quality guidance does NOT count as a failed retry
                return LivenessDecision.retry(session.getSessionId(), session.getWorkflow(), guidance, ctx.latestPassiveResult);
            }

            // Track best compliant frame
            ctx.updateBestFrame(frame, quality);

            // 4. State Machine Transitions
            switch (ctx.state) {
                case FACE_POSITIONING -> {
                    ctx.consecutiveCompliantFrames++;
                    if (ctx.consecutiveCompliantFrames >= 3) {
                        ctx.state = EngineState.PASSIVE_CHECK;
                        if (ctx.listener != null) {
                            ctx.listener.onStateChanged(session, EngineState.PASSIVE_CHECK.name(), "Hold still, checking liveness...");
                        }
                    }
                    return LivenessDecision.retry(session.getSessionId(), session.getWorkflow(), "Hold still", ctx.latestPassiveResult);
                }

                case PASSIVE_CHECK -> {
                    long checksum = PassiveAnalyzer.computeChecksum(frame);
                    ctx.frameWindow.add(new FrameObservationRecord(checksum, observation, arrivalNanos, System.currentTimeMillis()));
                    int required = session.getPolicy().passiveWindowFrames();
                    if (ctx.listener != null) {
                        ctx.listener.onPassiveProgress(session, ctx.frameWindow.size(), required);
                    }

                    if (ctx.frameWindow.size() < required) {
                        return LivenessDecision.retry(session.getSessionId(), session.getWorkflow(), "Hold still", ctx.latestPassiveResult);
                    }

                    // Window is complete: evaluate passive liveness and PAD using active frame
                    PassiveLivenessResult passiveResult = passiveAnalyzer.evaluateWindow(ctx.frameWindow, frame, session.getPolicy(), quality);
                    ctx.latestPassiveResult = passiveResult;

                    recordAudit(ctx, AuditEventType.PASSIVE_EVALUATED, null, passiveResult.livenessScore(),
                            passiveResult.padResult().classification(), passiveResult.padResult().attackScore(),
                            null, null, Map.of());

                    if (passiveResult.padResult().isAttack()) {
                        recordAudit(ctx, AuditEventType.PAD_ATTACK_DETECTED, DecisionType.REJECTED,
                                passiveResult.livenessScore(), passiveResult.padResult().classification(),
                                passiveResult.padResult().attackScore(), null,
                                LivenessError.PAD_FAILURE.getErrorCode(), Map.of());

                        int retries = session.incrementRetry();
                        if (retries > session.getPolicy().maxRetries()) {
                            return handleExceededRetries(ctx, session, frame.getDeviceId());
                        }

                        // Clear window for retry
                        ctx.frameWindow.clear();
                        ctx.state = EngineState.FACE_POSITIONING;
                        ctx.consecutiveCompliantFrames = 0;

                        // Anti-adversarial user feedback rule: NEVER disclose attack type to user
                        return LivenessDecision.rejected(session.getSessionId(), session.getWorkflow(),
                                LivenessError.PAD_FAILURE.getDefaultUserMessage(), passiveResult, ctx.completedChallenges);
                    }

                    // Not an attack: check if passive score satisfies threshold without active challenge
                    boolean satisfiesPassive = passiveResult.livenessScore() >= session.getPolicy().passiveThreshold();
                    boolean requiresActive = session.getPolicy().activeEnabled() && session.getPolicy().minChallenges() > 0;

                    if (satisfiesPassive && !requiresActive) {
                        ctx.state = EngineState.DECIDED;
                        recordAudit(ctx, AuditEventType.FINAL_DECISION, DecisionType.ACCEPTED,
                                passiveResult.livenessScore(), PadClassification.GENUINE, 0.0,
                                null, null, Map.of());

                        LivenessDecision decision = LivenessDecision.accepted(session.getSessionId(), session.getWorkflow(),
                                passiveResult, ctx.completedChallenges, passiveResult.livenessScore(), ctx.bestCompliantFrame);

                        if (ctx.listener != null) {
                            ctx.listener.onDecision(session, decision);
                        }
                        return decision;
                    }

                    if (!session.getPolicy().activeEnabled() || session.getPolicy().minChallenges() <= 0) {
                        int retries = session.incrementRetry();
                        if (retries > session.getPolicy().maxRetries()) {
                            return handleExceededRetries(ctx, session, frame.getDeviceId());
                        }
                        ctx.frameWindow.clear();
                        ctx.state = EngineState.FACE_POSITIONING;
                        ctx.consecutiveCompliantFrames = 0;
                        return LivenessDecision.retry(session.getSessionId(), session.getWorkflow(),
                                "Please keep your face clearly visible and look naturally at the camera", passiveResult);
                    }

                    // Inconclusive or active challenges mandatory -> transition to ACTIVE_CHALLENGE
                    ctx.state = EngineState.ACTIVE_CHALLENGE;
                    Challenge challenge = challengeGenerator.generateChallenge(session, null);
                    session.setCurrentChallenge(challenge);
                    ctx.currentVerifier = new ChallengeVerifier(challenge, observation.landmarks());

                    recordAudit(ctx, AuditEventType.CHALLENGE_ISSUED, null, null, null, null,
                            challenge.type(), null, Map.of("challengeId", challenge.challengeId()));

                    if (ctx.listener != null) {
                        ctx.listener.onChallengeIssued(session, challenge);
                        ctx.listener.onStateChanged(session, EngineState.ACTIVE_CHALLENGE.name(), challenge.promptText());
                    }

                    return LivenessDecision.challengeRequired(session.getSessionId(), session.getWorkflow(),
                            passiveResult, challenge.promptText());
                }

                case ACTIVE_CHALLENGE -> {
                    Challenge currentChallenge = session.getCurrentChallenge();
                    if (currentChallenge == null || ctx.currentVerifier == null) {
                        // Re-issue
                        Challenge ch = challengeGenerator.generateChallenge(session, null);
                        session.setCurrentChallenge(ch);
                        ctx.currentVerifier = new ChallengeVerifier(ch, observation.landmarks());
                        return LivenessDecision.challengeRequired(session.getSessionId(), session.getWorkflow(),
                                ctx.latestPassiveResult, ch.promptText());
                    }

                    Optional<ChallengeResult> challengeResultOpt = ctx.currentVerifier.update(observation.landmarks(), arrivalNanos);
                    if (challengeResultOpt.isEmpty()) {
                        // In progress
                        return LivenessDecision.challengeRequired(session.getSessionId(), session.getWorkflow(),
                                ctx.latestPassiveResult, currentChallenge.promptText());
                    }

                    ChallengeResult chResult = challengeResultOpt.get();
                    recordAudit(ctx, AuditEventType.CHALLENGE_EVALUATED, null, null, null, null,
                            chResult.type(), chResult.failureReason(),
                            Map.of("completed", String.valueOf(chResult.completed())));

                    if (chResult.completed()) {
                        ctx.completedChallenges.add(chResult);
                        int minRequired = session.getPolicy().minChallenges();

                        if (ctx.completedChallenges.size() >= minRequired) {
                            // Verification complete!
                            ctx.state = EngineState.DECIDED;
                            double passiveScore = ctx.latestPassiveResult != null ? ctx.latestPassiveResult.livenessScore() : 0.85;
                            double actionScore = chResult.transitionScore();
                            double blendedScore = (passiveScore * 0.6) + (actionScore * 0.4);

                            recordAudit(ctx, AuditEventType.FINAL_DECISION, DecisionType.ACCEPTED,
                                    blendedScore, PadClassification.GENUINE, 0.0, null, null, Map.of());

                            LivenessDecision decision = LivenessDecision.accepted(session.getSessionId(), session.getWorkflow(),
                                    ctx.latestPassiveResult, ctx.completedChallenges, blendedScore, ctx.bestCompliantFrame);

                            if (ctx.listener != null) {
                                ctx.listener.onDecision(session, decision);
                            }
                            return decision;
                        } else {
                            // Issue next challenge
                            Challenge nextCh = challengeGenerator.generateChallenge(session, currentChallenge.type());
                            session.setCurrentChallenge(nextCh);
                            ctx.currentVerifier = new ChallengeVerifier(nextCh, observation.landmarks());

                            recordAudit(ctx, AuditEventType.CHALLENGE_ISSUED, null, null, null, null,
                                    nextCh.type(), null, Map.of("challengeId", nextCh.challengeId()));

                            if (ctx.listener != null) {
                                ctx.listener.onChallengeIssued(session, nextCh);
                                ctx.listener.onStateChanged(session, EngineState.ACTIVE_CHALLENGE.name(), nextCh.promptText());
                            }

                            return LivenessDecision.challengeRequired(session.getSessionId(), session.getWorkflow(),
                                    ctx.latestPassiveResult, nextCh.promptText());
                        }
                    } else {
                        // Challenge failed or timed out
                        int retries = session.incrementRetry();
                        if (retries > session.getPolicy().maxRetries()) {
                            return handleExceededRetries(ctx, session, frame.getDeviceId());
                        }

                        // Try fresh challenge
                        Challenge fresh = challengeGenerator.generateChallenge(session, currentChallenge.type());
                        session.setCurrentChallenge(fresh);
                        ctx.currentVerifier = new ChallengeVerifier(fresh, observation.landmarks());

                        if (ctx.listener != null) {
                            ctx.listener.onChallengeIssued(session, fresh);
                            ctx.listener.onStateChanged(session, EngineState.ACTIVE_CHALLENGE.name(), fresh.promptText());
                        }

                        return LivenessDecision.retry(session.getSessionId(), session.getWorkflow(),
                                fresh.promptText(), ctx.latestPassiveResult);
                    }
                }

                case DECIDED -> {
                    return LivenessDecision.accepted(session.getSessionId(), session.getWorkflow(),
                            ctx.latestPassiveResult, ctx.completedChallenges,
                            ctx.latestPassiveResult != null ? ctx.latestPassiveResult.livenessScore() : 1.0,
                            ctx.bestCompliantFrame);
                }

                default -> {
                    return LivenessDecision.error(session.getSessionId(), session.getWorkflow(), "Engine in invalid state");
                }
            }

        } finally {
            // Privacy mandate: Wipe raw biometric pixel data immediately after analysis
            if (frame != null && !frame.isWiped()) {
                frame.wipe();
            }
        }
    }

    private LivenessDecision handleExceededRetries(SessionContext ctx, LivenessSession session, String deviceId) {
        ctx.state = EngineState.DECIDED;
        FailureBehaviour behaviour = session.getPolicy().failureBehaviour();

        if (behaviour == FailureBehaviour.LOCKOUT) {
            lockoutMap.put(deviceId != null ? deviceId : session.getSessionId(),
                    System.currentTimeMillis() + lockoutDurationMillis);
            log.warn("Device/session [{}] locked out for {} seconds due to max retries.",
                    deviceId, lockoutDurationMillis / 1000);
        }

        recordAudit(ctx, AuditEventType.FINAL_DECISION, DecisionType.FAILED_MAX_RETRIES,
                ctx.latestPassiveResult != null ? ctx.latestPassiveResult.livenessScore() : 0.0,
                null, null, null, LivenessError.MAX_RETRIES_EXCEEDED.getErrorCode(),
                Map.of("behaviour", behaviour.name()));

        String userMsg = LivenessError.MAX_RETRIES_EXCEEDED.getDefaultUserMessage();
        LivenessDecision decision = LivenessDecision.failedMaxRetries(session.getSessionId(), session.getWorkflow(), userMsg);
        if (ctx.listener != null) {
            ctx.listener.onDecision(session, decision);
        }
        return decision;
    }

    private void recordAudit(SessionContext ctx,
                             AuditEventType eventType,
                             DecisionType decision,
                             Double passiveScore,
                             PadClassification padClass,
                             Double padScore,
                             ChallengeType challengeType,
                             String errorCode,
                             Map<String, String> details) {
        LivenessAuditEvent event = LivenessAuditEvent.builder()
                .sessionId(ctx.session.getSessionId())
                .workflow(ctx.session.getWorkflow())
                .eventType(eventType)
                .decision(decision)
                .passiveScore(passiveScore)
                .padClassification(padClass)
                .padScore(padScore)
                .challengeType(challengeType)
                .errorCode(errorCode)
                .modelVersion(getEngineVersion())
                .deviceId(ctx.session.getSessionId())
                .details(details)
                .previousHash(ctx.auditTailHash)
                .build();

        ctx.auditTailHash = event.currentHash();
        auditSink.append(event);
    }

    @Override
    public void cancelSession(LivenessSession session) {
        if (session != null) {
            session.close();
            SessionContext ctx = sessions.remove(session.getSessionId());
            if (ctx != null) {
                recordAudit(ctx, AuditEventType.FINAL_DECISION, DecisionType.REJECTED, null, null, null,
                        null, "SESSION_CANCELLED", Map.of());
                if (ctx.bestCompliantFrame != null && !ctx.bestCompliantFrame.isWiped()) {
                    ctx.bestCompliantFrame.wipe();
                }
            }
        }
    }

    @Override
    public String getEngineVersion() {
        return "mosip-liveness-core 1.0.0 (heuristic" + (onnxAnalyzer.isAvailable() ? "+onnx" : "") + ")";
    }

    @Override
    public boolean isReady() {
        return true;
    }

    public PassiveAnalyzer getPassiveAnalyzer() {
        return passiveAnalyzer;
    }

    public ChallengeGenerator getChallengeGenerator() {
        return challengeGenerator;
    }

    /**
     * Records a supervisor override audit event for a given target session, authorized by a supervisor session.
     */
    public boolean recordSupervisorOverride(String targetSessionId, String supervisorSessionId) {
        if (targetSessionId == null || targetSessionId.isBlank()) return false;
        List<LivenessAuditEvent> events = auditSink.getEventsForSession(targetSessionId);
        String prevHash = (events != null && !events.isEmpty())
                ? events.get(events.size() - 1).currentHash()
                : GENESIS_HASH;

        LivenessAuditEvent overrideEvent = LivenessAuditEvent.builder()
                .sessionId(targetSessionId)
                .workflow(WorkflowType.SUPERVISOR_AUTH)
                .eventType(AuditEventType.SUPERVISOR_OVERRIDE)
                .decision(DecisionType.ACCEPTED)
                .modelVersion(getEngineVersion())
                .previousHash(prevHash)
                .details(Map.of("supervisorSessionId", supervisorSessionId != null ? supervisorSessionId : "unknown"))
                .build();

        auditSink.append(overrideEvent);
        return true;
    }

    @Override
    public void close() {
        for (SessionContext ctx : sessions.values()) {
            ctx.session.close();
            if (ctx.bestCompliantFrame != null && !ctx.bestCompliantFrame.isWiped()) {
                ctx.bestCompliantFrame.wipe();
            }
        }
        sessions.clear();
        auditSink.close();
    }
}
