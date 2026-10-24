package io.mosip.liveness.core.challenge;

import io.mosip.liveness.api.challenge.Challenge;
import io.mosip.liveness.api.challenge.ChallengeResult;
import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.engine.LivenessSession;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.Point2D;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChallengeVerifierTest {

    private static FaceLandmarks.Builder baseBuilder() {
        return FaceLandmarks.builder()
                .leftEyePupil(Point2D.of(0.35f, 0.35f))
                .rightEyePupil(Point2D.of(0.65f, 0.35f))
                .noseTip(Point2D.of(0.50f, 0.50f))
                .mouthLeft(Point2D.of(0.40f, 0.70f))
                .mouthRight(Point2D.of(0.60f, 0.70f))
                .chinTip(Point2D.of(0.50f, 0.85f))
                .leftEyeAspectRatio(0.30)
                .rightEyeAspectRatio(0.30)
                .mouthAspectRatio(0.15)
                .estimatedYawDegrees(0.0)
                .estimatedPitchDegrees(0.0);
    }

    private static FaceLandmarks createNeutralLandmarks() {
        return baseBuilder().build();
    }

    @Test
    @DisplayName("ChallengeGenerator produces non-repeating allowed challenge types")
    void testChallengeGenerator() {
        ChallengeGenerator generator = new ChallengeGenerator();
        LivenessPolicy policy = LivenessPolicy.builder()
                .allowedChallengeTypes(Set.of(ChallengeType.BLINK, ChallengeType.SMILE))
                .activeTimeoutSeconds(5)
                .build();
        LivenessSession session = new LivenessSession("sess-ch-1", WorkflowType.RESIDENT_ENROLLMENT, policy);

        Challenge c1 = generator.generateChallenge(session, null);
        assertThat(c1.type()).isIn(ChallengeType.BLINK, ChallengeType.SMILE);

        Challenge c2 = generator.generateChallenge(session, c1.type());
        assertThat(c2.type()).isNotEqualTo(c1.type());
    }

    @Test
    @DisplayName("Blink challenge completes on closed then reopened eyes")
    void testBlinkChallengeSuccess() {
        Challenge challenge = Challenge.create(ChallengeType.BLINK, "nonce123", 4000);
        FaceLandmarks neutral = createNeutralLandmarks();
        ChallengeVerifier verifier = new ChallengeVerifier(challenge, neutral);

        long t0 = challenge.issuedTimestampNanos() + 10_000_000L;

        // Step 1: Open eyes (in progress)
        Optional<ChallengeResult> r1 = verifier.update(neutral, t0);
        assertThat(r1).isEmpty();

        // Step 2: Eyes close (EAR < 0.14)
        FaceLandmarks closed = baseBuilder()
                .leftEyeAspectRatio(0.08)
                .rightEyeAspectRatio(0.07)
                .build();
        long t1 = t0 + 100_000_000L;
        Optional<ChallengeResult> r2 = verifier.update(closed, t1);
        assertThat(r2).isEmpty();

        // Step 3: Eyes reopen (EAR >= 0.22) after 150 ms
        long t2 = t1 + 150_000_000L;
        Optional<ChallengeResult> r3 = verifier.update(neutral, t2);

        assertThat(r3).isPresent();
        assertThat(r3.get().completed()).isTrue();
        assertThat(r3.get().type()).isEqualTo(ChallengeType.BLINK);
        assertThat(r3.get().identityContinuityScore()).isGreaterThan(0.7);
    }

    @Test
    @DisplayName("Smile challenge completes when held for minHoldDuration")
    void testSmileChallengeSuccess() {
        Challenge challenge = Challenge.create(ChallengeType.SMILE, "nonce123", 4000);
        FaceLandmarks neutral = createNeutralLandmarks();
        ChallengeVerifier verifier = new ChallengeVerifier(challenge, neutral);

        long t0 = challenge.issuedTimestampNanos() + 10_000_000L;

        // Smiling landmarks (MAR >= 0.28)
        FaceLandmarks smiling = baseBuilder()
                .mouthAspectRatio(0.32)
                .build();

        // Smile starts
        Optional<ChallengeResult> r1 = verifier.update(smiling, t0);
        assertThat(r1).isEmpty();

        // Smile held for 350 ms (challenge.minHoldDurationMillis() = 300)
        long t1 = t0 + 350_000_000L;
        Optional<ChallengeResult> r2 = verifier.update(smiling, t1);

        assertThat(r2).isPresent();
        assertThat(r2.get().completed()).isTrue();
        assertThat(r2.get().type()).isEqualTo(ChallengeType.SMILE);
    }

    @Test
    @DisplayName("Turn left challenge completes when yaw rotates >= 20 degrees")
    void testTurnLeftSuccess() {
        Challenge challenge = Challenge.create(ChallengeType.TURN_LEFT, "nonce123", 5000);
        FaceLandmarks neutral = createNeutralLandmarks();
        ChallengeVerifier verifier = new ChallengeVerifier(challenge, neutral);

        long t0 = challenge.issuedTimestampNanos() + 50_000_000L;
        FaceLandmarks turned = baseBuilder()
                .estimatedYawDegrees(22.0)
                .build();

        Optional<ChallengeResult> r = verifier.update(turned, t0);
        assertThat(r).isPresent();
        assertThat(r.get().completed()).isTrue();
        assertThat(r.get().type()).isEqualTo(ChallengeType.TURN_LEFT);
    }

    @Test
    @DisplayName("Turn right challenge completes when yaw rotates <= -20 degrees")
    void testTurnRightSuccess() {
        Challenge challenge = Challenge.create(ChallengeType.TURN_RIGHT, "nonce123", 5000);
        FaceLandmarks neutral = createNeutralLandmarks();
        ChallengeVerifier verifier = new ChallengeVerifier(challenge, neutral);

        long t0 = challenge.issuedTimestampNanos() + 50_000_000L;
        FaceLandmarks turned = baseBuilder()
                .estimatedYawDegrees(-22.0)
                .build();

        Optional<ChallengeResult> r = verifier.update(turned, t0);
        assertThat(r).isPresent();
        assertThat(r.get().completed()).isTrue();
        assertThat(r.get().type()).isEqualTo(ChallengeType.TURN_RIGHT);
    }

    @Test
    @DisplayName("Challenge expiration produces failure with CHALLENGE_TIMEOUT")
    void testChallengeTimeout() {
        Challenge challenge = Challenge.create(ChallengeType.BLINK, "nonce123", 2000);
        FaceLandmarks neutral = createNeutralLandmarks();
        ChallengeVerifier verifier = new ChallengeVerifier(challenge, neutral);

        // Advance arrival past timeout (2500 ms)
        long expiredNanos = challenge.issuedTimestampNanos() + 2_500_000_000L;
        Optional<ChallengeResult> r = verifier.update(neutral, expiredNanos);

        assertThat(r).isPresent();
        assertThat(r.get().completed()).isFalse();
        assertThat(r.get().failureReason()).contains("LIV_ERR_010"); // CHALLENGE_TIMEOUT
    }

    @Test
    @DisplayName("Actions prior to challenge issue time are ignored (anti-pre-recorded video)")
    void testActionBeforeIssueTimeIgnored() {
        Challenge challenge = Challenge.create(ChallengeType.TURN_LEFT, "nonce123", 5000);
        FaceLandmarks neutral = createNeutralLandmarks();
        ChallengeVerifier verifier = new ChallengeVerifier(challenge, neutral);

        // Timestamp strictly before challenge.issuedTimestampNanos()
        long preIssueNanos = challenge.issuedTimestampNanos() - 100_000_000L;
        FaceLandmarks turned = FaceLandmarks.builder().estimatedYawDegrees(25.0).build();

        Optional<ChallengeResult> r = verifier.update(turned, preIssueNanos);
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("Face swapped mid-challenge triggers IDENTITY_MISMATCH")
    void testIdentityMismatchFails() {
        Challenge challenge = Challenge.create(ChallengeType.TURN_LEFT, "nonce123", 5000);
        FaceLandmarks personA = createNeutralLandmarks();
        ChallengeVerifier verifier = new ChallengeVerifier(challenge, personA);

        long t0 = challenge.issuedTimestampNanos() + 50_000_000L;

        // Person B with completely different facial ratios (narrow eyes, distant chin, wide mouth)
        FaceLandmarks personB = FaceLandmarks.builder()
                .leftEyePupil(Point2D.of(0.45f, 0.20f))
                .rightEyePupil(Point2D.of(0.55f, 0.20f))
                .noseTip(Point2D.of(0.50f, 0.60f))
                .mouthLeft(Point2D.of(0.20f, 0.75f))
                .mouthRight(Point2D.of(0.80f, 0.75f))
                .chinTip(Point2D.of(0.50f, 0.99f))
                .estimatedYawDegrees(25.0)
                .build();

        Optional<ChallengeResult> r = verifier.update(personB, t0);
        assertThat(r).isPresent();
        assertThat(r.get().completed()).isFalse();
        assertThat(r.get().failureReason()).contains("LIV_ERR_014"); // IDENTITY_MISMATCH
    }
}
