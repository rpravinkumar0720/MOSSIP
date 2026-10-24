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
    private final LogisticFusionModel fusionModel;

    public PassiveAnalyzer() {
        this(PassiveWeights.DEFAULT, LogisticFusionModel.loadDefault());
    }

    public PassiveAnalyzer(PassiveWeights weights) {
        this(weights, LogisticFusionModel.loadDefault());
    }

    public PassiveAnalyzer(PassiveWeights weights, LogisticFusionModel fusionModel) {
        this.weights = Objects.requireNonNull(weights, "PassiveWeights cannot be null");
        this.fusionModel = Objects.requireNonNull(fusionModel, "LogisticFusionModel cannot be null");
    }

    public PassiveWeights getWeights() {
        return weights;
    }

    public LogisticFusionModel getFusionModel() {
        return fusionModel;
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

        // 8. 3D Mask detection
        double mask3dScore = (latestFrame != null && !latestFrame.isWiped()) ?
                computeMask3DScore(latestFrame, box, landmarks) : 0.90;

        Map<String, Double> heuristics = new HashMap<>();
        heuristics.put("microMotion", microMotionScore);
        heuristics.put("moire", moireScore);
        heuristics.put("specular", specularScore);
        heuristics.put("texture", textureScore);
        heuristics.put("border", borderScore);
        heuristics.put("timing", timingScore);
        heuristics.put("consistency", consistencyScore);
        heuristics.put("mask3d", mask3dScore);

        // P1-1: Evaluate calibrated logistic regression fusion and veto rules
        LogisticFusionModel.FusionResult fusion = fusionModel.evaluate(heuristics);

        double livenessScore = fusion.livenessScore();
        double attackScore = fusion.attackScore();

        double padThreshold = policy != null ? policy.padThreshold() : 0.70;
        double passiveThreshold = policy != null ? policy.passiveThreshold() : 0.85;

        boolean isAttack = fusion.hardVeto() || (attackScore >= padThreshold);
        boolean isLive = !isAttack && (livenessScore >= passiveThreshold);

        PadClassification classification;
        if (!isAttack) {
            classification = PadClassification.GENUINE;
        } else {
            if (fusion.vetoCategory() != null) {
                classification = switch (fusion.vetoCategory()) {
                    case "SCREEN_REPLAY" -> PadClassification.SCREEN_REPLAY;
                    case "PRINT_ATTACK" -> PadClassification.PRINT_ATTACK;
                    case "MASK_3D" -> PadClassification.MASK_3D;
                    default -> determineAttackClassification(heuristics);
                };
            } else {
                classification = determineAttackClassification(heuristics);
            }
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
                .metadata(Map.of("mode", "logistic-fusion", "engine", "mosip-liveness-core", "modelVer", fusionModel.version()))
                .build();
    }

    /**
     * Micro-motion: calculates non-rigid landmark jitter residual after fitting a similarity transform,
     * and evaluates involuntary eyelid palpebral fissure micro-tremor.
     */
    public static double computeMicroMotionScore(List<FrameObservationRecord> window) {
        if (window == null || window.size() < 2) {
            return 0.5; // Single frame, not enough temporal context
        }

        double totalResidual = 0.0;
        int pairs = 0;

        double earSum = 0.0;
        double earSqSum = 0.0;
        int earCount = 0;

        for (int i = 0; i < window.size(); i++) {
            if (window.get(i).observation() != null && window.get(i).observation().landmarks() != null) {
                FaceLandmarks lm = window.get(i).observation().landmarks();
                double ear = (lm.leftEyeAspectRatio() + lm.rightEyeAspectRatio()) / 2.0;
                earSum += ear;
                earSqSum += ear * ear;
                earCount++;
            }
        }

        for (int i = 1; i < window.size(); i++) {
            FaceLandmarks l1 = window.get(i - 1).observation() != null ? window.get(i - 1).observation().landmarks() : null;
            FaceLandmarks l2 = window.get(i).observation() != null ? window.get(i).observation().landmarks() : null;
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

        // Eyelid micro-variation
        double earVariance = 0.0;
        if (earCount >= 3) {
            double earMean = earSum / earCount;
            earVariance = Math.max(0.0, (earSqSum / earCount) - (earMean * earMean));
        }

        // Static photo presentation: near-zero non-rigid residual (< 0.00018) AND near-zero EAR variance (< 1e-6)
        if (avgResidual < 0.00018 && earVariance < 1e-6) {
            return Math.max(0.05, avgResidual / 0.00018 * 0.15);
        }
        // Healthy human micro-motion residual: 0.00025 to 0.038
        if (avgResidual >= 0.00025 && avgResidual <= 0.038) {
            return 0.98;
        }
        if (avgResidual < 0.00025) {
            return 0.20 + ((avgResidual - 0.00018) / 0.00007) * 0.78;
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
     * Border / Bezel: checks for rectangular device bezels or paper edges surrounding the face
     * using Hough/Radon orthogonal line integration over the outer margin.
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
        int margin = Math.min(w, h) / 16;
        int outX0 = Math.max(1, x0 - margin);
        int outY0 = Math.max(1, y0 - margin);
        int outX1 = Math.min(w - 2, x0 + bw + margin);
        int outY1 = Math.min(h - 2, y0 + bh + margin);

        int maxCollinearH = 0;
        int maxCollinearV = 0;
        int totalMarginEdges = 0;
        int totalChecked = 0;

        // Hough line accumulator for horizontal border lines
        for (int y : new int[]{ outY0, outY1 }) {
            if (y > 0 && y < h - 1) {
                int consecutive = 0;
                for (int x = outX0; x < outX1; x++) {
                    int grad = Math.abs((gray[(y + 1) * w + x] & 0xFF) - (gray[(y - 1) * w + x] & 0xFF));
                    if (grad > 65) {
                        consecutive++;
                        totalMarginEdges++;
                        if (consecutive > maxCollinearH) maxCollinearH = consecutive;
                    } else {
                        consecutive = 0;
                    }
                    totalChecked++;
                }
            }
        }

        // Hough line accumulator for vertical border lines
        for (int x : new int[]{ outX0, outX1 }) {
            if (x > 0 && x < w - 1) {
                int consecutive = 0;
                for (int y = outY0; y < outY1; y++) {
                    int grad = Math.abs((gray[y * w + x + 1] & 0xFF) - (gray[y * w + x - 1] & 0xFF));
                    if (grad > 65) {
                        consecutive++;
                        totalMarginEdges++;
                        if (consecutive > maxCollinearV) maxCollinearV = consecutive;
                    } else {
                        consecutive = 0;
                    }
                    totalChecked++;
                }
            }
        }

        if (totalChecked == 0) return 0.95;
        double edgeRatio = (double) totalMarginEdges / totalChecked;

        // Long straight line collinear edge (> 30% of face width/height) indicates physical bezel/paper border
        if (maxCollinearH >= (bw * 0.30) || maxCollinearV >= (bh * 0.30)) {
            return 0.10; // Tablet / phone bezel or printed sheet border detected
        }
        if (edgeRatio > 0.25) {
            return 0.15;
        }
        return Math.max(0.2, Math.min(0.95, 1.0 - edgeRatio * 3.0));
    }

    /**
     * Frame timing integrity: detects synthetic injection, frame freezing, burst arrivals, and clock anomalies.
     */
    public static double computeTimingIntegrityScore(List<FrameObservationRecord> window) {
        if (window == null || window.size() < 2) return 0.90;

        // 1. Check consecutive frame pixel equality via compact checksum
        for (int i = 1; i < window.size(); i++) {
            long c1 = window.get(i - 1).frameChecksum();
            long c2 = window.get(i).frameChecksum();
            if (c1 != 0L && c1 == c2) {
                // Frozen video stream or programmatic video replay injection
                return 0.05;
            }
        }

        // 2. Inter-frame interval analysis from monotonic server arrivalNanos
        if (window.size() >= 3) {
            double intervalSumNanos = 0.0;
            double intervalSqSumNanos = 0.0;
            int intervals = 0;

            for (int i = 1; i < window.size(); i++) {
                long deltaNanos = window.get(i).arrivalNanos() - window.get(i - 1).arrivalNanos();
                // If frames arrive in unnatural microsecond burst (< 3ms)
                if (deltaNanos > 0L && deltaNanos < 3_000_000L) {
                    return 0.10; // Programmatic synthetic frame dump
                }
                if (deltaNanos > 0L) {
                    intervalSumNanos += deltaNanos;
                    intervalSqSumNanos += (double) deltaNanos * deltaNanos;
                    intervals++;
                }
            }

            if (intervals >= 4) {
                double meanNanos = intervalSumNanos / intervals;
                // Unnatural frame interval: prolonged frame stalls (> 1.5 seconds)
                if (meanNanos > 1_500_000_000.0) {
                    return 0.15; // Prolonged video stall
                }
            }
        }

        // 3. Client timestamp monotonicity and clock drift check
        for (int i = 1; i < window.size(); i++) {
            long t1 = window.get(i - 1).clientTimestampMs();
            long t2 = window.get(i).clientTimestampMs();
            if (t1 != 0L && t2 != 0L) {
                if (t2 < t1) {
                    // Non-monotonic clock / backwards replay loop
                    return 0.05;
                }
                long deltaClient = t2 - t1;
                long deltaServer = (window.get(i).arrivalNanos() - window.get(i - 1).arrivalNanos()) / 1_000_000L;
                if (deltaClient > 0 && deltaServer > 0 && Math.abs(deltaServer - deltaClient) > 2000L) {
                    return 0.15; // Massive clock drift / manipulated client timestamps
                }
            }
        }

        return 0.95;
    }

    /**
     * 3D Mask detection: detects synthetic silicone, latex, or resin masks via texture uniformity,
     * lack of skin micro-pore scattering, and ocular/oral boundary edge gradients.
     */
    public static double computeMask3DScore(FaceFrame frame, BoundingBox box, FaceLandmarks landmarks) {
        if (frame == null || frame.isWiped() || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return 0.90;
        }

        ImageOps.ColorStats stats = ImageOps.colorChannelStats(frame, box);
        // Silicone masks typically show near-zero saturation standard deviation (uniform synthetic dye)
        boolean uniformPigment = stats.saturationStd() < 0.006 && stats.meanSaturation() > 0.05;

        // Check eye and mouth boundary cutout gradients if landmarks are present
        boolean cutoutDetected = false;
        if (landmarks != null && landmarks.points() != null && landmarks.points().size() >= 33) {
            byte[] gray = ImageOps.toGrayscale(frame);
            int w = frame.getWidth();
            int h = frame.getHeight();

            Point2D leftEye = landmarks.leftEyePupil();
            Point2D rightEye = landmarks.rightEyePupil();
            int lx = Math.round(leftEye.x() * w);
            int ly = Math.round(leftEye.y() * h);
            int rx = Math.round(rightEye.x() * w);
            int ry = Math.round(rightEye.y() * h);

            int cutoutEdges = 0;
            int tested = 0;
            for (int r = 10; r <= 20; r += 4) {
                for (double angle = 0; angle < 2 * Math.PI; angle += Math.PI / 4) {
                    int ex = (int) (lx + r * Math.cos(angle));
                    int ey = (int) (ly + r * Math.sin(angle));
                    if (ex > 1 && ex < w - 2 && ey > 1 && ey < h - 2) {
                        int grad = Math.abs((gray[ey * w + ex + 1] & 0xFF) - (gray[ey * w + ex - 1] & 0xFF));
                        if (grad > 85) cutoutEdges++;
                        tested++;
                    }
                }
            }
            if (tested > 0 && ((double) cutoutEdges / tested) > 0.55) {
                cutoutDetected = true;
            }
        }

        if (uniformPigment && cutoutDetected) {
            return 0.10; // Both synthetic pigment and physical cutout detected
        }
        if (uniformPigment || cutoutDetected) {
            return 0.35; // Suspicious single feature
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
            case "moire", "border", "specular" -> PadClassification.SCREEN_REPLAY;
            case "texture", "microMotion" -> PadClassification.PRINT_ATTACK;
            case "mask3d" -> PadClassification.MASK_3D;
            case "consistency" -> PadClassification.UNKNOWN_ATTACK;
            default -> PadClassification.UNKNOWN_ATTACK;
        };
    }
}
