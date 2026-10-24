package io.mosip.liveness.core.challenge;

import io.mosip.liveness.api.challenge.Challenge;
import io.mosip.liveness.api.challenge.ChallengeResult;
import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.error.LivenessError;
import io.mosip.liveness.api.quality.FaceLandmarks;

import java.util.Objects;
import java.util.Optional;

/**
 * State tracking verifier for dynamic active liveness challenges.
 * Consumes consecutive landmark updates stamped with server-arrival timestamps.
 * Validates facial action transitions, anti-pre-recorded video timings, and identity continuity.
 */
public class ChallengeVerifier {

    public static final double MAX_IDENTITY_DRIFT = 0.25;

    private final Challenge challenge;
    private final IdentitySignature baselineSignature;
    private final double baselineYaw;
    private final double baselinePitch;
    private final double baselineMar;

    // Blink state
    private boolean blinkEyesClosed = false;
    private long blinkClosedTimestampNanos = 0L;

    // Smile state
    private long smileStartTimestampNanos = 0L;

    // Turn / Nod state
    private boolean actionTransitionObserved = false;
    private double maxObservedTransition = 0.0;

    public ChallengeVerifier(Challenge challenge, FaceLandmarks initialLandmarks) {
        this.challenge = Objects.requireNonNull(challenge, "Challenge cannot be null");
        this.baselineSignature = IdentitySignature.compute(initialLandmarks);
        if (initialLandmarks != null) {
            this.baselineYaw = initialLandmarks.estimatedYawDegrees();
            this.baselinePitch = initialLandmarks.estimatedPitchDegrees();
            this.baselineMar = initialLandmarks.mouthAspectRatio();
        } else {
            this.baselineYaw = 0.0;
            this.baselinePitch = 0.0;
            this.baselineMar = 0.15;
        }
    }

    /**
     * Processes a new frame observation during an active challenge.
     *
     * @param landmarks latest detected facial landmarks
     * @param arrivalNanos monotonic server arrival timestamp (System.nanoTime)
     * @return Optional containing ChallengeResult if completed or failed, or empty if still in progress
     */
    public Optional<ChallengeResult> update(FaceLandmarks landmarks, long arrivalNanos) {
        if (landmarks == null) {
            return Optional.empty();
        }

        // Anti-pre-recorded video check: frame must arrive strictly after challenge issuance
        if (arrivalNanos < challenge.issuedTimestampNanos()) {
            return Optional.empty();
        }

        long elapsedMillis = (arrivalNanos - challenge.issuedTimestampNanos()) / 1_000_000L;

        // Check timeout
        if (challenge.isExpired(arrivalNanos)) {
            return Optional.of(ChallengeResult.failure(
                    challenge.challengeId(),
                    challenge.type(),
                    elapsedMillis,
                    LivenessError.CHALLENGE_TIMEOUT.getErrorCode()
            ));
        }

        // Identity continuity verification
        IdentitySignature currentSig = IdentitySignature.compute(landmarks);
        double drift = baselineSignature.distanceTo(currentSig);
        if (drift > MAX_IDENTITY_DRIFT) {
            return Optional.of(ChallengeResult.failure(
                    challenge.challengeId(),
                    challenge.type(),
                    elapsedMillis,
                    LivenessError.IDENTITY_MISMATCH.getErrorCode()
            ));
        }

        double identityContinuityScore = Math.max(0.5, Math.min(1.0, 1.0 - drift));

        ChallengeType type = challenge.type();
        switch (type) {
            case BLINK -> {
                double avgEar = (landmarks.leftEyeAspectRatio() + landmarks.rightEyeAspectRatio()) / 2.0;
                if (!blinkEyesClosed) {
                    if (avgEar < 0.14) {
                        blinkEyesClosed = true;
                        blinkClosedTimestampNanos = arrivalNanos;
                    }
                } else {
                    // Eyes were closed; check for reopening
                    if (avgEar >= 0.22) {
                        long closedDurationMillis = (arrivalNanos - blinkClosedTimestampNanos) / 1_000_000L;
                        // Natural human blink closed duration is 60ms to 500ms
                        if (closedDurationMillis >= 60 && closedDurationMillis <= 650) {
                            return Optional.of(ChallengeResult.success(
                                    challenge.challengeId(),
                                    type,
                                    elapsedMillis,
                                    0.95,
                                    identityContinuityScore
                            ));
                        } else {
                            // Reset blink if held closed too unnaturally long or glitch
                            blinkEyesClosed = false;
                        }
                    }
                }
            }

            case SMILE -> {
                double currentMar = landmarks.mouthAspectRatio();
                // Smile expands mouth width and elevates aspect ratio
                boolean isSmiling = (currentMar >= baselineMar + 0.08) || (currentMar >= 0.28);
                if (isSmiling) {
                    if (smileStartTimestampNanos == 0L) {
                        smileStartTimestampNanos = arrivalNanos;
                    } else {
                        long holdMillis = (arrivalNanos - smileStartTimestampNanos) / 1_000_000L;
                        if (holdMillis >= challenge.minHoldDurationMillis()) {
                            return Optional.of(ChallengeResult.success(
                                    challenge.challengeId(),
                                    type,
                                    elapsedMillis,
                                    0.94,
                                    identityContinuityScore
                            ));
                        }
                    }
                } else {
                    // Reset smile hold if subject stopped smiling before minHoldDuration
                    smileStartTimestampNanos = 0L;
                }
            }

            case TURN_LEFT -> {
                // User turning head to their left rotates yaw by >= +20 degrees relative to baseline
                double deltaYaw = landmarks.estimatedYawDegrees() - baselineYaw;
                if (deltaYaw > maxObservedTransition) {
                    maxObservedTransition = deltaYaw;
                }
                if (deltaYaw >= 20.0) {
                    actionTransitionObserved = true;
                }
                if (actionTransitionObserved) {
                    return Optional.of(ChallengeResult.success(
                            challenge.challengeId(),
                            type,
                            elapsedMillis,
                            0.95,
                            identityContinuityScore
                    ));
                }
            }

            case TURN_RIGHT -> {
                // User turning head to their right rotates yaw by >= -20 degrees relative to baseline
                double deltaYaw = landmarks.estimatedYawDegrees() - baselineYaw;
                if (deltaYaw < maxObservedTransition) {
                    maxObservedTransition = deltaYaw;
                }
                if (deltaYaw <= -20.0) {
                    actionTransitionObserved = true;
                }
                if (actionTransitionObserved) {
                    return Optional.of(ChallengeResult.success(
                            challenge.challengeId(),
                            type,
                            elapsedMillis,
                            0.95,
                            identityContinuityScore
                    ));
                }
            }

            case LOOK_UP -> {
                // Head tilted upwards: pitch transitions positively by >= 15 degrees
                double deltaPitch = landmarks.estimatedPitchDegrees() - baselinePitch;
                if (deltaPitch >= 15.0) {
                    return Optional.of(ChallengeResult.success(
                            challenge.challengeId(),
                            type,
                            elapsedMillis,
                            0.92,
                            identityContinuityScore
                    ));
                }
            }

            case NOD_HEAD -> {
                // Head nodded downwards: pitch transitions negatively by >= 15 degrees
                double deltaPitch = landmarks.estimatedPitchDegrees() - baselinePitch;
                if (deltaPitch <= -15.0) {
                    return Optional.of(ChallengeResult.success(
                            challenge.challengeId(),
                            type,
                            elapsedMillis,
                            0.92,
                            identityContinuityScore
                    ));
                }
            }
        }

        return Optional.empty();
    }

    public Challenge getChallenge() {
        return challenge;
    }
}
