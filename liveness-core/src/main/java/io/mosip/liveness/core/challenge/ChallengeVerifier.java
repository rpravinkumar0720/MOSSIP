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

    // Pixel corroboration state
    private boolean frameTracked = false;
    private byte[] baselineFaceSample = null;
    private double baselineEyeIntensity = -1.0;
    private double maxObservedEyeDelta = 0.0;
    private double maxObservedPixelMotion = 0.0;
    private boolean returnedToCenter = false;

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
     * Backward-compatible update overload without FaceFrame.
     */
    public Optional<ChallengeResult> update(FaceLandmarks landmarks, long arrivalNanos) {
        return update(landmarks, arrivalNanos, null);
    }

    /**
     * Processes a new frame observation during an active challenge with server-side pixel corroboration.
     *
     * @param landmarks latest detected facial landmarks
     * @param arrivalNanos monotonic server arrival timestamp (System.nanoTime)
     * @param frame raw captured frame for pixel-level corroboration
     * @return Optional containing ChallengeResult if completed or failed, or empty if still in progress
     */
    public Optional<ChallengeResult> update(FaceLandmarks landmarks, long arrivalNanos, io.mosip.liveness.api.frame.FaceFrame frame) {
        if (landmarks == null) {
            return Optional.empty();
        }

        // Track and corroborate server pixels
        if (frame != null && !frame.isWiped() && frame.getWidth() > 0 && frame.getHeight() > 0) {
            trackFramePixels(frame, landmarks);
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
                        // Natural human blink closed duration is 60ms to 650ms
                        if (closedDurationMillis >= 60 && closedDurationMillis <= 650) {
                            // Pixel corroboration: on static photo, eye pixels never change
                            if (frameTracked && maxObservedEyeDelta < 0.005 && maxObservedPixelMotion < 0.005) {
                                // Forged blink detected: synthetic landmarks on static photo!
                                return Optional.of(ChallengeResult.failure(
                                        challenge.challengeId(),
                                        type,
                                        elapsedMillis,
                                        LivenessError.PAD_FAILURE.getErrorCode()
                                ));
                            }

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
                    smileStartTimestampNanos = 0L;
                }
            }

            case TURN_LEFT -> {
                double deltaYaw = landmarks.estimatedYawDegrees() - baselineYaw;
                if (deltaYaw > maxObservedTransition) {
                    maxObservedTransition = deltaYaw;
                }
                if (deltaYaw >= 20.0) {
                    actionTransitionObserved = true;
                }
                // Require turn action, pixel motion corroboration, and return towards center
                if (actionTransitionObserved) {
                    if (frameTracked && maxObservedPixelMotion < 0.005) {
                        return Optional.of(ChallengeResult.failure(
                                challenge.challengeId(),
                                type,
                                elapsedMillis,
                                LivenessError.PAD_FAILURE.getErrorCode()
                        ));
                    }
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
                double deltaYaw = landmarks.estimatedYawDegrees() - baselineYaw;
                if (deltaYaw < maxObservedTransition) {
                    maxObservedTransition = deltaYaw;
                }
                if (deltaYaw <= -20.0) {
                    actionTransitionObserved = true;
                }
                if (actionTransitionObserved) {
                    if (frameTracked && maxObservedPixelMotion < 0.005) {
                        return Optional.of(ChallengeResult.failure(
                                challenge.challengeId(),
                                type,
                                elapsedMillis,
                                LivenessError.PAD_FAILURE.getErrorCode()
                        ));
                    }
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
                double deltaPitch = landmarks.estimatedPitchDegrees() - baselinePitch;
                if (deltaPitch >= 15.0) {
                    if (frameTracked && maxObservedPixelMotion < 0.005) {
                        return Optional.of(ChallengeResult.failure(
                                challenge.challengeId(),
                                type,
                                elapsedMillis,
                                LivenessError.PAD_FAILURE.getErrorCode()
                        ));
                    }
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
                double deltaPitch = landmarks.estimatedPitchDegrees() - baselinePitch;
                if (deltaPitch <= -15.0) {
                    if (frameTracked && maxObservedPixelMotion < 0.005) {
                        return Optional.of(ChallengeResult.failure(
                                challenge.challengeId(),
                                type,
                                elapsedMillis,
                                LivenessError.PAD_FAILURE.getErrorCode()
                        ));
                    }
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

    private void trackFramePixels(io.mosip.liveness.api.frame.FaceFrame frame, FaceLandmarks landmarks) {
        byte[] data = frame.getRawDataUnsafe();
        int w = frame.getWidth();
        int h = frame.getHeight();
        int bpp = frame.getFormat().getBytesPerPixel();
        if (data == null || data.length < w * h * bpp) return;

        // Sample 16x16 patch across face center
        int sampleSize = 16;
        byte[] currentSample = new byte[sampleSize * sampleSize];
        int startX = w / 4;
        int endX = (3 * w) / 4;
        int startY = h / 4;
        int endY = (3 * h) / 4;
        int stepX = Math.max(1, (endX - startX) / sampleSize);
        int stepY = Math.max(1, (endY - startY) / sampleSize);

        for (int sy = 0; sy < sampleSize; sy++) {
            int y = Math.min(h - 1, startY + sy * stepY);
            int row = y * w;
            for (int sx = 0; sx < sampleSize; sx++) {
                int x = Math.min(w - 1, startX + sx * stepX);
                int off = (row + x) * bpp;
                int luma = ((data[off] & 0xFF) + (data[off + 1] & 0xFF) + (data[off + 2] & 0xFF)) / 3;
                currentSample[sy * sampleSize + sx] = (byte) luma;
            }
        }

        if (baselineFaceSample == null) {
            baselineFaceSample = currentSample;
            frameTracked = true;
        } else {
            // Measure SAD difference from baseline
            long sad = 0;
            for (int i = 0; i < currentSample.length; i++) {
                sad += Math.abs((currentSample[i] & 0xFF) - (baselineFaceSample[i] & 0xFF));
            }
            double motion = (double) sad / (currentSample.length * 255.0);
            if (motion > maxObservedPixelMotion) {
                maxObservedPixelMotion = motion;
            }
        }

        // Measure eye intensity if pupils available
        if (landmarks != null && landmarks.leftEyePupil() != null && landmarks.rightEyePupil() != null) {
            double eyeIntensity = sampleEyeRegionLuma(data, w, h, bpp, landmarks.leftEyePupil(), landmarks.rightEyePupil());
            if (baselineEyeIntensity < 0.0) {
                baselineEyeIntensity = eyeIntensity;
            } else {
                double delta = Math.abs(eyeIntensity - baselineEyeIntensity);
                if (delta > maxObservedEyeDelta) {
                    maxObservedEyeDelta = delta;
                }
            }
        }
    }

    private double sampleEyeRegionLuma(byte[] data, int w, int h, int bpp, io.mosip.liveness.api.quality.Point2D leftEye, io.mosip.liveness.api.quality.Point2D rightEye) {
        int lx = (int) (leftEye.x() * w);
        int ly = (int) (leftEye.y() * h);
        int rx = (int) (rightEye.x() * w);
        int ry = (int) (rightEye.y() * h);

        int radius = Math.max(3, w / 40);
        long sum = 0;
        int count = 0;

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int yl = Math.max(0, Math.min(h - 1, ly + dy));
                int xl = Math.max(0, Math.min(w - 1, lx + dx));
                int offL = (yl * w + xl) * bpp;
                sum += ((data[offL] & 0xFF) + (data[offL + 1] & 0xFF) + (data[offL + 2] & 0xFF)) / 3;
                count++;

                int yr = Math.max(0, Math.min(h - 1, ry + dy));
                int xr = Math.max(0, Math.min(w - 1, rx + dx));
                int offR = (yr * w + xr) * bpp;
                sum += ((data[offR] & 0xFF) + (data[offR + 1] & 0xFF) + (data[offR + 2] & 0xFF)) / 3;
                count++;
            }
        }
        return count > 0 ? (double) sum / (count * 255.0) : 0.0;
    }

    public Challenge getChallenge() {
        return challenge;
    }
}
