package io.mosip.liveness.core.engine;

import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.decision.LivenessDecision;
import io.mosip.liveness.api.engine.LivenessListener;
import io.mosip.liveness.api.engine.LivenessSession;
import io.mosip.liveness.api.error.LivenessException;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.frame.PixelFormat;
import io.mosip.liveness.api.policy.FailureBehaviour;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;
import io.mosip.liveness.api.quality.BoundingBox;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.Point2D;
import io.mosip.liveness.core.analyzer.ClientLandmarkAnalyzer;
import io.mosip.liveness.core.analyzer.FaceObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.mosip.liveness.api.audit.AuditEventType;
import io.mosip.liveness.api.audit.LivenessAuditEvent;
import io.mosip.liveness.core.audit.InMemoryAuditSink;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLivenessEngineTest {

    private static FaceFrame createSharpSkinFrame(int width, int height, int seed) {
        byte[] data = new byte[width * height * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = (x - width / 2.0) / (width / 2.0);
                double dy = (y - height / 2.0) / (height / 2.0);
                double dist = Math.sqrt(dx * dx + dy * dy);
                int shading = (int) (35 * (1.0 - Math.min(1.0, dist)));
                int noise = ((seed * 17 + x * 7 + y * 13) % 15) - 7;
                int r = Math.min(240, Math.max(10, 165 + shading + noise));
                int g = Math.min(200, Math.max(10, 115 + shading + noise));
                int b = Math.min(170, Math.max(10, 85 + (shading / 2) + noise));
                int idx = (y * width + x) * 3;
                data[idx] = (byte) r;
                data[idx + 1] = (byte) g;
                data[idx + 2] = (byte) b;
            }
        }
        return FaceFrame.wrap(data, width, height, PixelFormat.RGB_888, System.nanoTime(), seed, "DEV_01", 0);
    }

    private static FaceObservation createCompliantObservation(float jitter) {
        BoundingBox box = BoundingBox.of(0.2f, 0.2f, 0.7f, 0.8f, 0.95f);
        FaceLandmarks landmarks = FaceLandmarks.builder()
                .leftEyePupil(Point2D.of(0.35f + jitter, 0.35f + (jitter / 2)))
                .rightEyePupil(Point2D.of(0.65f + jitter, 0.35f))
                .noseTip(Point2D.of(0.50f, 0.50f))
                .mouthLeft(Point2D.of(0.40f - (jitter / 2), 0.70f + (jitter / 2)))
                .mouthRight(Point2D.of(0.60f + (jitter / 2), 0.70f + (jitter / 2)))
                .chinTip(Point2D.of(0.50f, 0.85f + (jitter / 3)))
                .leftEyeAspectRatio(0.32 + (jitter * 5))
                .rightEyeAspectRatio(0.31 + (jitter * 5))
                .mouthAspectRatio(0.12)
                .estimatedYawDegrees(0.0)
                .estimatedPitchDegrees(0.0)
                .build();
        return FaceObservation.singleFace(box, landmarks, 0.95);
    }

    @Test
    @DisplayName("Happy path: positioning -> passive -> active challenge (blink) -> ACCEPTED")
    void testHappyPathBlinkActiveChallenge() throws Exception {
        ClientLandmarkAnalyzer analyzer = new ClientLandmarkAnalyzer();
        DefaultLivenessEngine engine = new DefaultLivenessEngine(analyzer, null, null, null, null);
        LivenessListener listener = Mockito.mock(LivenessListener.class);

        LivenessPolicy policy = LivenessPolicy.builder()
                .passiveThreshold(0.80)
                .passiveWindowFrames(3)
                .activeEnabled(true)
                .minChallenges(1)
                .allowedChallengeTypes(Set.of(ChallengeType.BLINK))
                .maxRetries(3)
                .build();

        LivenessSession session = engine.startSession("sess-happy-1", WorkflowType.RESIDENT_ENROLLMENT, policy, listener);

        // 1. FACE_POSITIONING (3 compliant frames)
        for (int i = 0; i < 3; i++) {
            Thread.sleep(15);
            analyzer.pushObservation("sess-happy-1", createCompliantObservation(0.001f * i));
            FaceFrame f = createSharpSkinFrame(100, 100, i);
            LivenessDecision d = engine.processFrame(session, f);
            assertThat(f.isWiped()).isTrue(); // Verified frame wiping
            assertThat(d.decisionType()).isEqualTo(DecisionType.RETRY);
        }

        // 2. PASSIVE_CHECK (3 frames to fill window)
        LivenessDecision passDec = null;
        for (int i = 0; i < 3; i++) {
            Thread.sleep(15);
            analyzer.pushObservation("sess-happy-1", createCompliantObservation(0.002f * (i + 1)));
            FaceFrame f = createSharpSkinFrame(100, 100, i + 10);
            passDec = engine.processFrame(session, f);
        }

        assertThat(passDec).isNotNull();
        // Since policy requires minChallenges = 1, CHALLENGE_REQUIRED must be emitted
        assertThat(passDec.decisionType()).isEqualTo(DecisionType.CHALLENGE_REQUIRED);
        assertThat(session.getCurrentChallenge()).isNotNull();
        assertThat(session.getCurrentChallenge().type()).isEqualTo(ChallengeType.BLINK);

        // 3. ACTIVE_CHALLENGE (Blink: closed eyes then reopened eyes)
        // Eyes closed frame
        FaceLandmarks closedLandmarks = FaceLandmarks.builder()
                .leftEyePupil(Point2D.of(0.35f, 0.35f))
                .rightEyePupil(Point2D.of(0.65f, 0.35f))
                .noseTip(Point2D.of(0.50f, 0.50f))
                .mouthLeft(Point2D.of(0.40f, 0.70f))
                .mouthRight(Point2D.of(0.60f, 0.70f))
                .chinTip(Point2D.of(0.50f, 0.85f))
                .leftEyeAspectRatio(0.07)
                .rightEyeAspectRatio(0.06)
                .build();
        analyzer.pushObservation("sess-happy-1", FaceObservation.singleFace(BoundingBox.of(0.2f, 0.2f, 0.7f, 0.8f, 0.95f), closedLandmarks, 0.95));
        FaceFrame fClosed = createSharpSkinFrame(100, 100, 50);
        LivenessDecision dClosed = engine.processFrame(session, fClosed);
        assertThat(dClosed.decisionType()).isEqualTo(DecisionType.CHALLENGE_REQUIRED);

        // Eyes reopened after 150ms
        try {
            Thread.sleep(120);
        } catch (InterruptedException ignored) {}

        analyzer.pushObservation("sess-happy-1", createCompliantObservation(0.001f));
        FaceFrame fReopen = createSharpSkinFrame(100, 100, 51);
        LivenessDecision finalDecision = engine.processFrame(session, fReopen);

        assertThat(finalDecision.decisionType()).isEqualTo(DecisionType.ACCEPTED);
        assertThat(finalDecision.finalScore()).isGreaterThan(0.75);
        assertThat(finalDecision.bestCompliantFrame()).isNotNull();
        assertThat(finalDecision.bestCompliantFrame().isWiped()).isFalse();

        // Wipe best frame after use
        finalDecision.bestCompliantFrame().wipe();
        engine.close();
    }

    @Test
    @DisplayName("Quality failures emit guidance without incrementing retry counter")
    void testQualityFailureDoesNotIncrementRetry() {
        ClientLandmarkAnalyzer analyzer = new ClientLandmarkAnalyzer();
        DefaultLivenessEngine engine = new DefaultLivenessEngine(analyzer, null, null, null, null);
        LivenessPolicy policy = LivenessPolicy.defaultResidentEnrollment();
        LivenessSession session = engine.startSession("sess-qual-1", WorkflowType.RESIDENT_ENROLLMENT, policy, null);

        // Push frame with no face detected
        analyzer.pushObservation("sess-qual-1", FaceObservation.none());
        FaceFrame f = createSharpSkinFrame(100, 100, 1);
        LivenessDecision d = engine.processFrame(session, f);

        assertThat(d.decisionType()).isEqualTo(DecisionType.RETRY);
        assertThat(session.getRetryCount()).isEqualTo(0); // Retry count remains 0!
        engine.close();
    }

    @Test
    @DisplayName("Exceeding max retries triggers FAILED_MAX_RETRIES with LOCKOUT")
    void testLockoutBehaviour() {
        ClientLandmarkAnalyzer analyzer = new ClientLandmarkAnalyzer();
        DefaultLivenessEngine engine = new DefaultLivenessEngine(analyzer, null, null, null, null);
        engine.setLockoutDurationMillis(30_000L);

        LivenessPolicy policy = LivenessPolicy.builder()
                .maxRetries(1)
                .failureBehaviour(FailureBehaviour.LOCKOUT)
                .build();

        LivenessSession session = engine.startSession("sess-lockout-1", WorkflowType.OPERATOR_AUTH, policy, null);

        // Force attack or retry exhaustion by failing challenge
        // Fill positioning
        for (int i = 0; i < 3; i++) {
            analyzer.pushObservation("sess-lockout-1", createCompliantObservation(0.001f * i));
            engine.processFrame(session, createSharpSkinFrame(100, 100, i));
        }

        // Fill passive
        for (int i = 0; i < policy.passiveWindowFrames(); i++) {
            analyzer.pushObservation("sess-lockout-1", createCompliantObservation(0.001f * (i + 1)));
            engine.processFrame(session, createSharpSkinFrame(100, 100, i + 10));
        }

        // Now in challenge mode: fail challenge by advancing time past timeout
        session.incrementRetry(); // retry 1
        session.incrementRetry(); // retry 2 -> exceeds maxRetries (1)

        // Process another frame
        analyzer.pushObservation("sess-lockout-1", createCompliantObservation(0.001f));
        FaceFrame f = FaceFrame.wrap(createSharpSkinFrame(100, 100, 99).getDataCopy(), 100, 100, PixelFormat.RGB_888,
                System.nanoTime() + 10_000_000_000L, 99L, "DEV_LOCK_01", 0);

        LivenessDecision decision = engine.processFrame(session, f);
        assertThat(decision.decisionType()).isEqualTo(DecisionType.FAILED_MAX_RETRIES);

        // Verify lockout blocks subsequent session for this device
        assertThat(engine.isDeviceLockedOut("DEV_LOCK_01")).isTrue();
        assertThatThrownBy(() -> engine.startSession("DEV_LOCK_01", WorkflowType.OPERATOR_AUTH, policy, null))
                .isInstanceOf(LivenessException.class);

        engine.close();
    }

    @Test
    @DisplayName("P0-1(b): Forged client landmarks mismatched with server face detection are rejected")
    void testForgedLandmarksMismatchDetected() {
        ClientLandmarkAnalyzer analyzer = new ClientLandmarkAnalyzer();
        InMemoryAuditSink sink = new InMemoryAuditSink(100);
        DefaultLivenessEngine engine = new DefaultLivenessEngine(analyzer, null, null, sink, null);

        LivenessPolicy policy = LivenessPolicy.defaultResidentEnrollment();
        LivenessSession session = engine.startSession("sess-forged-1", WorkflowType.RESIDENT_ENROLLMENT, policy, null);

        // Client pushes forged landmarks situated in extreme top-left corner (0.05, 0.05)
        BoundingBox fakeBox = BoundingBox.of(0.02f, 0.02f, 0.15f, 0.15f, 0.99f);
        FaceLandmarks fakeLandmarks = FaceLandmarks.builder()
                .leftEyePupil(Point2D.of(0.05f, 0.05f))
                .rightEyePupil(Point2D.of(0.12f, 0.05f))
                .noseTip(Point2D.of(0.08f, 0.08f))
                .mouthLeft(Point2D.of(0.06f, 0.12f))
                .mouthRight(Point2D.of(0.10f, 0.12f))
                .chinTip(Point2D.of(0.08f, 0.14f))
                .build();
        analyzer.pushObservation("sess-forged-1", FaceObservation.singleFace(fakeBox, fakeLandmarks, 0.99));

        // Real frame has face centered at (0.50, 0.50)
        FaceFrame frame = createSharpSkinFrame(100, 100, 1);
        LivenessDecision decision = engine.processFrame(session, frame);

        assertThat(decision.decisionType()).isEqualTo(DecisionType.REJECTED);
        assertThat(decision.reason()).contains("Face verification could not be completed"); // Generic message!
        assertThat(decision.reason()).doesNotContain("LANDMARK_MISMATCH"); // No leak to user

        // Verify audit sink recorded LANDMARK_MISMATCH
        List<LivenessAuditEvent> events = sink.getEventsForSession("sess-forged-1");
        assertThat(events).anyMatch(e -> e.eventType() == AuditEventType.LANDMARK_MISMATCH);

        engine.close();
    }

    @Test
    @DisplayName("P0-1(e): Skin-coloured blob without landmarks yields NO_FACE without fabrication")
    void testNoLandmarkFallbackReturnsNoFace() {
        ClientLandmarkAnalyzer analyzer = new ClientLandmarkAnalyzer();
        DefaultLivenessEngine engine = new DefaultLivenessEngine(analyzer, null, null, null, null);

        LivenessPolicy policy = LivenessPolicy.defaultResidentEnrollment();
        LivenessSession session = engine.startSession("sess-noface-1", WorkflowType.RESIDENT_ENROLLMENT, policy, null);

        // Flat skin frame without eyes/mouth features (uniform color)
        byte[] data = new byte[100 * 100 * 3];
        for (int i = 0; i < 100 * 100; i++) {
            data[i * 3] = (byte) 205;
            data[i * 3 + 1] = (byte) 140;
            data[i * 3 + 2] = (byte) 100;
        }
        FaceFrame flatFrame = FaceFrame.wrap(data, 100, 100, PixelFormat.RGB_888, System.nanoTime(), 1L, "DEV_01", 0);

        // No landmarks pushed by client
        LivenessDecision decision = engine.processFrame(session, flatFrame);

        assertThat(decision.decisionType()).isEqualTo(DecisionType.RETRY);
        assertThat(decision.reason()).contains("No face detected");
        assertThat(session.getRetryCount()).isEqualTo(0); // Quality failures don't burn retry budget

        engine.close();
    }

    @Test
    @DisplayName("P0-4: Lockout persists across reconnects and new sockets")
    void testPersistentLockoutAcrossReconnects() {
        ClientLandmarkAnalyzer analyzer = new ClientLandmarkAnalyzer();
        DefaultLivenessEngine engine = new DefaultLivenessEngine(analyzer, null, null, null, null);

        LivenessPolicy policy = LivenessPolicy.builder()
                .maxRetries(2)
                .failureBehaviour(FailureBehaviour.LOCKOUT)
                .build();

        String clientIdentity = "attacker-user-token-999";

        // Session 1: Exceed retries
        LivenessSession s1 = engine.startSession("sess-atk-1", WorkflowType.RESIDENT_AUTH, policy, null, clientIdentity);
        s1.incrementRetry();
        s1.incrementRetry();
        s1.incrementRetry(); // 3 > 2
        engine.processFrame(s1, createSharpSkinFrame(100, 100, 1));

        // After maxRetries failed sessions, reconnecting with a new session using the same identity is refused
        assertThatThrownBy(() -> engine.startSession("sess-atk-new-socket", WorkflowType.RESIDENT_AUTH, policy, null, clientIdentity))
                .isInstanceOf(LivenessException.class)
                .hasMessageContaining("currently locked out");

        engine.close();
    }

    @Test
    @DisplayName("P1-2: Video stream replay with frozen frames is detected and rejected")
    void testReplayDetectionInStream() {
        ClientLandmarkAnalyzer analyzer = new ClientLandmarkAnalyzer();
        DefaultLivenessEngine engine = new DefaultLivenessEngine(analyzer, null, null, null, null);

        LivenessPolicy policy = LivenessPolicy.builder()
                .passiveThreshold(0.70)
                .padThreshold(0.70)
                .activeEnabled(false)
                .minChallenges(0)
                .passiveWindowFrames(5)
                .build();

        LivenessSession session = engine.startSession("sess-replay-1", WorkflowType.RESIDENT_AUTH, policy, null);

        // 3 compliant positioning frames
        for (int i = 0; i < 3; i++) {
            analyzer.pushObservation("sess-replay-1", createCompliantObservation(0.001f * i));
            engine.processFrame(session, createSharpSkinFrame(100, 100, i));
        }

        // Frozen frame stream: identical pixel buffer repeated across all passive window frames
        byte[] frozenPixels = createSharpSkinFrame(100, 100, 42).getDataCopy();
        LivenessDecision lastDec = null;
        for (int i = 0; i < 5; i++) {
            analyzer.pushObservation("sess-replay-1", createCompliantObservation(0.001f));
            FaceFrame f = FaceFrame.wrap(frozenPixels.clone(), 100, 100, PixelFormat.RGB_888, System.nanoTime(), i + 10, "DEV_01", 0);
            lastDec = engine.processFrame(session, f);
        }

        assertThat(lastDec).isNotNull();
        assertThat(lastDec.decisionType()).isEqualTo(DecisionType.REJECTED);
        assertThat(lastDec.reason()).contains("Face verification could not be completed");

        engine.close();
    }
}
