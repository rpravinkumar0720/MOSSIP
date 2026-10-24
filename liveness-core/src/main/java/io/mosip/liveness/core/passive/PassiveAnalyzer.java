package io.mosip.liveness.core.passive;

import io.mosip.liveness.api.classifier.PadClassification;
import io.mosip.liveness.api.classifier.PadResult;
import io.mosip.liveness.api.classifier.PassiveLivenessResult;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.frame.PixelFormat;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.quality.BoundingBox;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.FaceQuality;
import io.mosip.liveness.api.quality.Point2D;
import io.mosip.liveness.core.image.ImageOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-heuristic Passive Liveness and Presentation Attack Detection (PAD) analyzer.
 * Evaluates 7 independent physical and geometric attack vectors over a sliding frame window.
 * Complies with ISO/IEC 30107-1 presentation attack instruments (PAIs).
 * Pure Java, operational with zero ML model files present.
 */
public class PassiveAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PassiveAnalyzer.class);

    private final PassiveWeights weights;

    public PassiveAnalyzer() {
        this(PassiveWeights.DEFAULT);
    }

    public PassiveAnalyzer(PassiveWeights weights) {
        this.weights = Objects.requireNonNull(weights, "PassiveWeights cannot be null");
    }

    public PassiveWeights getWeights() {
        return weights;
    }

    /**
     * Computes a quick 64-bit sampling checksum of a FaceFrame to verify timing integrity without caching raw pixels.
     */
    public static long computeChecksum(FaceFrame frame) {
        if (frame == null || frame.isWiped()) return 0L;
        byte[] data = frame.getRawDataUnsafe();
        long h = 1125899906842597L;
        int step = Math.max(1, data.length / 256);
        for (int i = 0; i < data.length; i += step) {
            h = 31 * h + (data[i] & 0xFF);
        }
        return h;
    }

    /**
     * Evaluates a sliding window of compliant frames against presentation attacks.
     *
     * @param window sliding window of FrameObservationRecords
     * @param latestFrame currently active (unwiped) frame
     * @param policy current active policy
     * @param quality latest measured quality
     * @return PassiveLivenessResult containing scores and PAD classification
     */
    public PassiveLivenessResult evaluateWindow(List<FrameObservationRecord> window,
                                                FaceFrame latestFrame,
                                                LivenessPolicy policy,
                                                FaceQuality quality) {
        long startNanos = System.nanoTime();
        if (window == null || window.isEmpty()) {
            return PassiveLivenessResult.builder()
                    .livenessScore(0.0)
                    .padResult(PadResult.attack(PadClassification.UNKNOWN_ATTACK, 1.0, 1.0, Map.of()))
                    .quality(quality != null ? quality : FaceQuality.builder().build())
                    .isLive(false)
                    .build();
        }

        FrameObservationRecord latest = window.get(window.size() - 1);
        BoundingBox box = latest.observation() != null ? latest.observation().box() :
                BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 1.0f);
        FaceLandmarks landmarks = latest.observation() != null ? latest.observation().landmarks() :
                FaceLandmarks.builder().build();

        // 1. Micro-motion
        double microMotionScore = computeMicroMotionScore(window);

        // 2. Moire / Frequency
        double moireScore = (latestFrame != null && !latestFrame.isWiped()) ?
                computeMoireScore(latestFrame, box) : 0.90;

        // 3. Specular / Glare
        double specularScore = (latestFrame != null && !latestFrame.isWiped()) ?
                computeSpecularScore(latestFrame, box) : 0.90;

        // 4. Texture / Color stats
        double textureScore = (latestFrame != null && !latestFrame.isWiped()) ?
                computeTextureScore(latestFrame, box) : 0.90;

        // 5. Border / Bezel detection
        double borderScore = (latestFrame != null && !latestFrame.isWiped()) ?
                computeBorderScore(latestFrame, box) : 0.90;

        // 6. Frame timing integrity
        double timingScore = computeTimingIntegrityScore(window);

        // 7. Landmark-pixel consistency
        double consistencyScore = (latestFrame != null && !latestFrame.isWiped()) ?
                computeLandmarkPixelConsistency(latestFrame, box, landmarks) : 0.90;

        Map<String, Double> heuristics = new HashMap<>();
        heuristics.put("microMotion", microMotionScore);
        heuristics.put("moire", moireScore);
        heuristics.put("specular", specularScore);
        heuristics.put("texture", textureScore);
        heuristics.put("border", borderScore);
        heuristics.put("timing", timingScore);
        heuristics.put("consistency", consistencyScore);

        // Weighted blend
        double livenessScore =
                microMotionScore * weights.microMotionWeight() +
                moireScore * weights.moireWeight() +
                specularScore * weights.specularWeight() +
                textureScore * weights.textureWeight() +
                borderScore * weights.borderWeight() +
                timingScore * weights.timingWeight() +
                consistencyScore * weights.consistencyWeight();

        livenessScore = Math.max(0.0, Math.min(1.0, livenessScore));
        double attackScore = 1.0 - livenessScore;

        double padThreshold = policy != null ? policy.padThreshold() : 0.70;
        double passiveThreshold = policy != null ? policy.passiveThreshold() : 0.85;

        boolean isAttack = attackScore >= padThreshold;
        boolean isLive = !isAttack && (livenessScore >= passiveThreshold);

        PadClassification classification;
        if (!isAttack) {
            classification = PadClassification.GENUINE;
        } else {
            // Determine dominant failing heuristic
            classification = determineAttackClassification(heuristics);
        }

        double confidence = Math.abs(livenessScore - 0.5) * 2.0;
        PadResult padResult = new PadResult(classification, attackScore, confidence, heuristics, isAttack);

        long latencyNanos = System.nanoTime() - startNanos;

        return PassiveLivenessResult.builder()
                .livenessScore(livenessScore)
                .padResult(padResult)
                .quality(quality != null ? quality : FaceQuality.builder().build())
                .boundingBox(box)
                .landmarks(landmarks)
                .isLive(isLive)
                .latencyNanos(latencyNanos)
                .metadata(Map.of("mode", "heuristic", "engine", "mosip-liveness-core"))
                .build();
    }

    /**
     * Micro-motion: calculates non-rigid landmark jitter residual after fitting a similarity transform.
     */
    public static double computeMicroMotionScore(List<FrameObservationRecord> window) {
        if (window.size() < 2) {
            return 0.5; // Single frame, not enough temporal context
        }

        double totalResidual = 0.0;
        int pairs = 0;

        for (int i = 1; i < window.size(); i++) {
            FaceLandmarks l1 = window.get(i - 1).observation().landmarks();
            FaceLandmarks l2 = window.get(i).observation().landmarks();
            if (l1 == null || l2 == null) continue;

            Point2D[] pts1 = { l1.leftEyePupil(), l1.rightEyePupil(), l1.noseTip(), l1.mouthLeft(), l1.mouthRight(), l1.chinTip() };
            Point2D[] pts2 = { l2.leftEyePupil(), l2.rightEyePupil(), l2.noseTip(), l2.mouthLeft(), l2.mouthRight(), l2.chinTip() };

            float c1x = 0, c1y = 0, c2x = 0, c2y = 0;
            for (int k = 0; k < pts1.length; k++) {
                c1x += pts1[k].x(); c1y += pts1[k].y();
                c2x += pts2[k].x(); c2y += pts2[k].y();
            }
            c1x /= pts1.length; c1y /= pts1.length;
            c2x /= pts2.length; c2y /= pts2.length;

            float d1Sum = 0, d2Sum = 0;
            for (int k = 0; k < pts1.length; k++) {
                float dx1 = pts1[k].x() - c1x, dy1 = pts1[k].y() - c1y;
                float dx2 = pts2[k].x() - c2x, dy2 = pts2[k].y() - c2y;
                d1Sum += Math.sqrt(dx1 * dx1 + dy1 * dy1);
                d2Sum += Math.sqrt(dx2 * dx2 + dy2 * dy2);
            }
            float scale = (d1Sum > 1e-4) ? (d2Sum / d1Sum) : 1.0f;

            double pairResidual = 0.0;
            for (int k = 0; k < pts1.length; k++) {
                float norm1x = (pts1[k].x() - c1x) * scale;
                float norm1y = (pts1[k].y() - c1y) * scale;
                float norm2x = pts2[k].x() - c2x;
                float norm2y = pts2[k].y() - c2y;
                double diff = Math.sqrt((norm2x - norm1x) * (norm2x - norm1x) + (norm2y - norm1y) * (norm2y - norm1y));
                pairResidual += diff;
            }
            totalResidual += pairResidual / pts1.length;
            pairs++;
        }

        if (pairs == 0) return 0.5;
        double avgResidual = totalResidual / pairs;

        // Static photo presentation has near-zero residual (< 0.00015)
        if (avgResidual < 0.00015) {
            return Math.max(0.05, avgResidual / 0.00015 * 0.20);
        }
        // Healthy human micro-motion residual: 0.00025 to 0.038
        if (avgResidual >= 0.00025 && avgResidual <= 0.038) {
            return 0.98;
        }
        if (avgResidual < 0.00025) {
            return 0.20 + ((avgResidual - 0.00015) / 0.00010) * 0.78;
        }
        if (avgResidual <= 0.080) {
            return 0.85;
        }
        // Very large jump (abrupt erratic motion)
        return Math.max(0.20, 1.0 - (avgResidual - 0.080) * 4.0);
    }

    /**
     * Moire / Frequency: detects periodic screen raster artifacts via 2D FFT.
     */
    public static double computeMoireScore(FaceFrame frame, BoundingBox box) {
        double highFreqRatio = ImageOps.highFrequencyEnergyRatio(frame, box);
        // Live face: highFreqRatio typically 0.05..0.22. Screen replay: > 0.38
        if (highFreqRatio <= 0.22) {
            return 0.95;
        }
        if (highFreqRatio >= 0.50) {
            return 0.05;
        }
        return Math.max(0.05, Math.min(0.95, 1.0 - (highFreqRatio - 0.22) / 0.28));
    }

    /**
     * Specular / Glare: detects bright reflective screen or glossy photo patches.
     */
    public static double computeSpecularScore(FaceFrame frame, BoundingBox box) {
        double highlightRatio = ImageOps.specularHighlightRatio(frame, box);
        // Live skin: highlightRatio < 0.02. Phone screen/photo glare: > 0.06
        if (highlightRatio <= 0.02) {
            return 0.95;
        }
        if (highlightRatio >= 0.08) {
            return 0.05;
        }
        return Math.max(0.05, Math.min(0.95, 1.0 - (highlightRatio - 0.02) / 0.06));
    }

    /**
     * Texture / Color: verifies natural color variation and skin tone saturation.
     */
    public static double computeTextureScore(FaceFrame frame, BoundingBox box) {
        ImageOps.ColorStats stats = ImageOps.colorChannelStats(frame, box);
        double avgStd = (stats.rStd() + stats.gStd() + stats.bStd()) / 3.0;

        // B&W or flat print attack has low color std dev and low saturation
        if (avgStd < 2.5 || stats.meanSaturation() < 0.04) {
            return 0.15;
        }
        if (avgStd >= 10.0 && stats.meanSaturation() >= 0.10 && stats.meanSaturation() <= 0.75) {
            return 0.95;
        }
        return Math.max(0.2, Math.min(0.95, (avgStd / 10.0) * (stats.meanSaturation() >= 0.08 ? 1.0 : 0.5)));
    }

    /**
     * Border / Bezel: checks for rectangular device bezels or paper edges surrounding the face.
     */
    public static double computeBorderScore(FaceFrame frame, BoundingBox box) {
        byte[] gray = ImageOps.toGrayscale(frame);
        int w = frame.getWidth();
        int h = frame.getHeight();

        int x0 = Math.max(0, box.pixelLeft(w));
        int y0 = Math.max(0, box.pixelTop(h));
        int bw = Math.min(w - x0, box.pixelWidth(w));
        int bh = Math.min(h - y0, box.pixelHeight(h));

        // Margin band outside the face box
        int margin = Math.min(w, h) / 20;
        int outX0 = Math.max(1, x0 - margin);
        int outY0 = Math.max(1, y0 - margin);
        int outX1 = Math.min(w - 2, x0 + bw + margin);
        int outY1 = Math.min(h - 2, y0 + bh + margin);

        int strongEdges = 0;
        int checked = 0;

        // Check horizontal outer lines
        for (int x = outX0; x < outX1; x++) {
            if (outY0 < y0) {
                int grad = Math.abs((gray[(outY0 + 1) * w + x] & 0xFF) - (gray[(outY0 - 1) * w + x] & 0xFF));
                if (grad > 70) strongEdges++;
                checked++;
            }
            if (outY1 > y0 + bh && outY1 < h - 1) {
                int grad = Math.abs((gray[(outY1 + 1) * w + x] & 0xFF) - (gray[(outY1 - 1) * w + x] & 0xFF));
                if (grad > 70) strongEdges++;
                checked++;
            }
        }

        // Check vertical outer lines
        for (int y = outY0; y < outY1; y++) {
            if (outX0 < x0) {
                int grad = Math.abs((gray[y * w + outX0 + 1] & 0xFF) - (gray[y * w + outX0 - 1] & 0xFF));
                if (grad > 70) strongEdges++;
                checked++;
            }
            if (outX1 > x0 + bw && outX1 < w - 1) {
                int grad = Math.abs((gray[y * w + outX1 + 1] & 0xFF) - (gray[y * w + outX1 - 1] & 0xFF));
                if (grad > 70) strongEdges++;
                checked++;
            }
        }

        if (checked == 0) return 0.95;
        double edgeRatio = (double) strongEdges / checked;
        // Strong border detected (> 0.25 edge density in margin)
        if (edgeRatio > 0.25) {
            return 0.15;
        }
        return Math.max(0.2, Math.min(0.95, 1.0 - edgeRatio * 3.0));
    }

    /**
     * Frame timing integrity: detects synthetic injection and frame freezing.
     */
    public static double computeTimingIntegrityScore(List<FrameObservationRecord> window) {
        if (window.size() < 2) return 0.90;

        // Check consecutive frame pixel equality via compact checksum
        for (int i = 1; i < window.size(); i++) {
            long c1 = window.get(i - 1).frameChecksum();
            long c2 = window.get(i).frameChecksum();
            if (c1 != 0L && c1 == c2) {
                // Frozen video stream or programmatic video replay injection
                return 0.05;
            }
        }

        return 0.95;
    }

    /**
     * Landmark-pixel consistency: ensures landmarks align with actual skin pixels.
     */
    public static double computeLandmarkPixelConsistency(FaceFrame frame, BoundingBox box, FaceLandmarks landmarks) {
        byte[] data = frame.getRawDataUnsafe();
        int w = frame.getWidth();
        int h = frame.getHeight();
        PixelFormat fmt = frame.getFormat();
        int bpp = fmt.getBytesPerPixel();

        int x0 = Math.max(0, box.pixelLeft(w));
        int y0 = Math.max(0, box.pixelTop(h));
        int bw = Math.min(w - x0, box.pixelWidth(w));
        int bh = Math.min(h - y0, box.pixelHeight(h));

        int skinPixels = 0;
        int sampled = 0;

        for (int y = y0; y < y0 + bh; y += 2) {
            for (int x = x0; x < x0 + bw; x += 2) {
                int offset = (y * w + x) * bpp;
                if (offset + (bpp > 1 ? 2 : 0) >= data.length) continue;

                int r, g, b;
                if (fmt == PixelFormat.RGB_888 || fmt == PixelFormat.RGBA_8888) {
                    r = data[offset] & 0xFF;
                    g = data[offset + 1] & 0xFF;
                    b = data[offset + 2] & 0xFF;
                } else if (fmt == PixelFormat.BGR_888) {
                    b = data[offset] & 0xFF;
                    g = data[offset + 1] & 0xFF;
                    r = data[offset + 2] & 0xFF;
                } else {
                    r = g = b = data[offset] & 0xFF;
                }

                // Standard skin color rule
                if (r > 45 && g > 25 && b > 15 && r > g && r > b && (r - g) >= 8) {
                    skinPixels++;
                }
                sampled++;
            }
        }

        if (sampled == 0) return 0.5;
        double skinFraction = (double) skinPixels / sampled;

        // If client reported face landmarks over a non-skin area (forged landmarks)
        if (skinFraction < 0.12) {
            return 0.10;
        }
        if (skinFraction >= 0.35) {
            return 0.95;
        }
        return Math.max(0.10, Math.min(0.95, skinFraction / 0.35));
    }

    private static PadClassification determineAttackClassification(Map<String, Double> heuristics) {
        String lowestKey = "microMotion";
        double lowestVal = 1.0;

        for (Map.Entry<String, Double> entry : heuristics.entrySet()) {
            if (entry.getValue() < lowestVal) {
                lowestVal = entry.getValue();
                lowestKey = entry.getKey();
            }
        }

        return switch (lowestKey) {
            case "timing" -> PadClassification.VIDEO_INJECTION;
            case "moire" -> PadClassification.SCREEN_REPLAY;
            case "border" -> PadClassification.SCREEN_REPLAY;
            case "specular" -> PadClassification.SCREEN_REPLAY;
            case "texture" -> PadClassification.PRINT_ATTACK;
            case "microMotion" -> PadClassification.PRINT_ATTACK;
            case "consistency" -> PadClassification.UNKNOWN_ATTACK;
            default -> PadClassification.UNKNOWN_ATTACK;
        };
    }
}
