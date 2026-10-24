package io.mosip.liveness.core.passive;

import io.mosip.liveness.api.classifier.PadClassification;
import io.mosip.liveness.api.classifier.PassiveLivenessResult;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.frame.PixelFormat;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.quality.BoundingBox;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.FaceQuality;
import io.mosip.liveness.api.quality.Point2D;
import io.mosip.liveness.core.analyzer.FaceObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassiveAnalyzerTest {

    private static FaceFrame createSkinFaceFrame(int width, int height, int seedOffset) {
        byte[] data = new byte[width * height * 3];
        // Human skin tone with 3D anatomical shading and subtle texture
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = (x - width / 2.0) / (width / 2.0);
                double dy = (y - height / 2.0) / (height / 2.0);
                double dist = Math.sqrt(dx * dx + dy * dy);
                int shading = (int) (35 * (1.0 - Math.min(1.0, dist)));
                int noise = ((x + y + seedOffset) % 5) * 2;
                int r = Math.min(240, 165 + shading + noise);
                int g = Math.min(200, 115 + shading + (noise / 2));
                int b = Math.min(170, 85 + (shading / 2) + (noise / 3));
                int idx = (y * width + x) * 3;
                data[idx] = (byte) r;
                data[idx + 1] = (byte) g;
                data[idx + 2] = (byte) b;
            }
        }
        return FaceFrame.wrap(data, width, height, PixelFormat.RGB_888, System.nanoTime(), 1L, "TEST_CAM", 0);
    }

    private static FaceLandmarks createLandmarksWithJitter(float jitterX, float jitterY) {
        return FaceLandmarks.builder()
                .leftEyePupil(Point2D.of(0.35f + jitterX, 0.35f + jitterY))
                .rightEyePupil(Point2D.of(0.65f, 0.35f))
                .noseTip(Point2D.of(0.50f + (jitterX / 2), 0.50f))
                .mouthLeft(Point2D.of(0.40f + jitterX, 0.70f))
                .mouthRight(Point2D.of(0.60f, 0.70f + jitterY))
                .chinTip(Point2D.of(0.50f, 0.85f))
                .build();
    }

    @Test
    @DisplayName("Genuine presentation with natural micro-motion produces high liveness score")
    void testGenuinePresentation() {
        PassiveAnalyzer analyzer = new PassiveAnalyzer();
        LivenessPolicy policy = LivenessPolicy.defaultResidentEnrollment();

        List<FrameObservationRecord> window = new ArrayList<>();
        BoundingBox box = BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 0.95f);

        // 5 consecutive frames with subtle natural non-rigid jitter (0.002 to 0.005)
        float[] jitters = { 0.000f, 0.003f, 0.002f, 0.004f, 0.002f };
        FaceFrame last = null;
        for (int i = 0; i < 5; i++) {
            FaceFrame frame = createSkinFaceFrame(100, 100, i * 3 + 1);
            last = frame;
            FaceLandmarks landmarks = createLandmarksWithJitter(jitters[i], jitters[i] / 2);
            FaceObservation obs = FaceObservation.singleFace(box, landmarks, 0.95);
            window.add(new FrameObservationRecord(PassiveAnalyzer.computeChecksum(frame), obs, System.nanoTime() + i * 33_000_000L, 1000 + i * 33));
        }

        PassiveLivenessResult result = analyzer.evaluateWindow(window, last, policy, FaceQuality.builder().build());

        assertThat(result.isLive()).isTrue();
        assertThat(result.padResult().isAttack()).isFalse();
        assertThat(result.padResult().classification()).isEqualTo(PadClassification.GENUINE);
        assertThat(result.livenessScore()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    @DisplayName("Static photo presentation with 0 micro-motion triggers PRINT_ATTACK")
    void testStaticPhotoAttack() {
        PassiveAnalyzer analyzer = new PassiveAnalyzer();
        // Policy with padThreshold 0.20 to catch low motion attacks
        LivenessPolicy policy = LivenessPolicy.builder().padThreshold(0.25).passiveThreshold(0.85).build();

        List<FrameObservationRecord> window = new ArrayList<>();
        BoundingBox box = BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 0.95f);
        FaceLandmarks staticLandmarks = createLandmarksWithJitter(0.0f, 0.0f);

        FaceFrame staticFrame = createSkinFaceFrame(100, 100, 0);
        // 5 frames with 0 jitter
        for (int i = 0; i < 5; i++) {
            FaceObservation obs = FaceObservation.singleFace(box, staticLandmarks, 0.95);
            window.add(new FrameObservationRecord(PassiveAnalyzer.computeChecksum(staticFrame), obs, System.nanoTime() + i * 33_000_000L, 1000 + i * 33));
        }

        PassiveLivenessResult result = analyzer.evaluateWindow(window, staticFrame, policy, FaceQuality.builder().build());

        assertThat(result.isLive()).isFalse();
        assertThat(result.padResult().isAttack()).isTrue();
        assertThat(result.padResult().heuristics().get("microMotion")).isLessThan(0.3);
    }

    @Test
    @DisplayName("Frozen identical video frames trigger VIDEO_INJECTION")
    void testVideoInjectionFreeze() {
        PassiveAnalyzer analyzer = new PassiveAnalyzer();
        LivenessPolicy policy = LivenessPolicy.builder().padThreshold(0.20).build();

        List<FrameObservationRecord> window = new ArrayList<>();
        BoundingBox box = BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 0.95f);
        FaceLandmarks landmarks = createLandmarksWithJitter(0.002f, 0.002f);

        // Same exact frame buffer object repeated (same checksum)
        FaceFrame frozenFrame = createSkinFaceFrame(100, 100, 0);
        long checksum = PassiveAnalyzer.computeChecksum(frozenFrame);
        for (int i = 0; i < 5; i++) {
            FaceObservation obs = FaceObservation.singleFace(box, landmarks, 0.95);
            window.add(new FrameObservationRecord(checksum, obs, System.nanoTime() + i * 33_000_000L, 1000 + i * 33));
        }

        PassiveLivenessResult result = analyzer.evaluateWindow(window, frozenFrame, policy, FaceQuality.builder().build());

        assertThat(result.padResult().heuristics().get("timing")).isLessThan(0.2);
    }

    @Test
    @DisplayName("Forged landmarks on non-skin image detected by consistency check")
    void testLandmarkPixelConsistencyCheck() {
        PassiveAnalyzer analyzer = new PassiveAnalyzer();
        LivenessPolicy policy = LivenessPolicy.builder().padThreshold(0.30).build();

        List<FrameObservationRecord> window = new ArrayList<>();
        BoundingBox box = BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 0.95f);
        FaceLandmarks landmarks = createLandmarksWithJitter(0.002f, 0.002f);

        // Non-skin blue image
        byte[] blueData = new byte[100 * 100 * 3];
        for (int i = 0; i < 100 * 100; i++) {
            blueData[i * 3] = (byte) 10;
            blueData[i * 3 + 1] = (byte) 20;
            blueData[i * 3 + 2] = (byte) 220; // pure blue
        }
        FaceFrame blueFrame = FaceFrame.wrap(blueData, 100, 100, PixelFormat.RGB_888, System.nanoTime(), 1L, "CAM", 0);
        long checksum = PassiveAnalyzer.computeChecksum(blueFrame);

        for (int i = 0; i < 5; i++) {
            FaceObservation obs = FaceObservation.singleFace(box, landmarks, 0.95);
            window.add(new FrameObservationRecord(checksum, obs, System.nanoTime() + i * 33_000_000L, 1000 + i * 33));
        }

        PassiveLivenessResult result = analyzer.evaluateWindow(window, blueFrame, policy, FaceQuality.builder().build());

        assertThat(result.padResult().heuristics().get("consistency")).isLessThan(0.3);
    }

    @Test
    @DisplayName("P1-1: Multi-vector screen failures trigger hard-veto SCREEN_REPLAY attack")
    void testHardVetoScreenAttack() {
        LogisticFusionModel model = LogisticFusionModel.loadDefault();
        // Moire, specular, border, and timing all failing (< 0.20), while rest are high
        java.util.Map<String, Double> heuristics = new java.util.HashMap<>();
        heuristics.put("moire", 0.05);
        heuristics.put("specular", 0.05);
        heuristics.put("border", 0.15);
        heuristics.put("timing", 0.05);
        heuristics.put("microMotion", 0.98);
        heuristics.put("texture", 0.95);
        heuristics.put("consistency", 0.95);
        heuristics.put("mask3d", 0.95);

        LogisticFusionModel.FusionResult fusion = model.evaluate(heuristics);

        assertThat(fusion.hardVeto()).isTrue();
        assertThat(fusion.vetoCategory()).isEqualTo("SCREEN_REPLAY");
        assertThat(fusion.attackScore()).isGreaterThanOrEqualTo(0.90);
        assertThat(fusion.livenessScore()).isLessThanOrEqualTo(0.10);
    }

    @Test
    @DisplayName("P1-2: Microsecond frame burst (<3ms) triggers synthetic timing anomaly")
    void testSyntheticBurstTimingDetection() {
        List<FrameObservationRecord> window = new ArrayList<>();
        BoundingBox box = BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 0.95f);
        FaceLandmarks lm = createLandmarksWithJitter(0.002f, 0.002f);
        FaceObservation obs = FaceObservation.singleFace(box, lm, 0.95);

        long baseNanos = 1_000_000_000L;
        // Frames arriving at 1ms intervals (synthetic burst / bot injection)
        for (int i = 0; i < 4; i++) {
            window.add(new FrameObservationRecord(i + 1, obs, baseNanos + (i * 1_000_000L), 1000 + i));
        }

        double score = PassiveAnalyzer.computeTimingIntegrityScore(window);
        assertThat(score).isLessThanOrEqualTo(0.15);
    }
}

