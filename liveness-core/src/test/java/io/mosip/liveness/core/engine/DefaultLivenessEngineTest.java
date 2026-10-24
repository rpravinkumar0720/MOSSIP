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
                int checker = ((x % 2 == 0) ^ (y % 2 == 0)) ? 25 : -25;
                int r = Math.min(240, Math.max(10, 165 + shading + checker));
                int g = Math.min(200, Math.max(10, 115 + shading + checker));
                int b = Math.min(170, Math.max(10, 85 + (shading / 2) + checker));
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
                .leftEyePupil(Point2D.of(0.35f + jitter, 0.35f))
                .rightEyePupil(Point2D.of(0.65f, 0.35f))
                .noseTip(Point2D.of(0.50f, 0.50f))
                .mouthLeft(Point2D.of(0.40f, 0.70f))
                .mouthRight(Point2D.of(0.60f, 0.70f))
                .chinTip(Point2D.of(0.50f, 0.85f))
                .leftEyeAspectRatio(0.32)
                .rightEyeAspectRatio(0.31)
                .mouthAspectRatio(0.12)
                .estimatedYawDegrees(0.0)
                .estimatedPitchDegrees(0.0)
                .build();
        return FaceObservation.singleFace(box, landmarks, 0.95);
    }

    @Test
    @DisplayName("Happy path: positioning -> passive -> active challenge (blink) -> ACCEPTED")
    void testHappyPathBlinkActiveChallenge() {
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
            analyzer.pushObservation("sess-happy-1", createCompliantObservation(0.001f * i));
            FaceFrame f = createSharpSkinFrame(100, 100, i);
            LivenessDecision d = engine.processFrame(session, f);
            assertThat(f.isWiped()).isTrue(); // Verified frame wiping
            assertThat(d.decisionType()).isEqualTo(DecisionType.RETRY);
        }

        // 2. PASSIVE_CHECK (3 frames to fill window)
        LivenessDecision passDec = null;
        for (int i = 0; i < 3; i++) {
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
}
