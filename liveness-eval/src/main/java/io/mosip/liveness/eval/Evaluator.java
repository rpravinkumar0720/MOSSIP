package io.mosip.liveness.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.api.classifier.PassiveLivenessResult;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.decision.LivenessDecision;
import io.mosip.liveness.api.engine.LivenessListener;
import io.mosip.liveness.api.engine.LivenessSession;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.frame.PixelFormat;
import io.mosip.liveness.api.policy.FailureBehaviour;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;
import io.mosip.liveness.api.quality.FaceLandmarks;
import io.mosip.liveness.api.quality.Point2D;
import io.mosip.liveness.core.analyzer.ClientLandmarkAnalyzer;
import io.mosip.liveness.core.analyzer.LandmarkGeometry;
import io.mosip.liveness.core.audit.InMemoryAuditSink;
import io.mosip.liveness.core.challenge.ChallengeGenerator;
import io.mosip.liveness.core.engine.DefaultLivenessEngine;
import io.mosip.liveness.core.passive.PassiveAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs presentation evaluation benchmarks on labeled directories of frame sequences and sidecar JSON files.
 */
public class Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Evaluator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public record PresentationResult(
            String presentationId,
            String category,
            int totalFrames,
            DecisionType finalDecision,
            double livenessScore,
            String details
    ) {}

    public record CategoryMetrics(
            String category,
            int totalPresentations,
            int acceptedCount,
            int rejectedCount,
            int retryOrInconclusiveCount,
            double errorRatePercent // APCER for attacks, BPCER for genuine
    ) {}

    private final String modelPath;

    public Evaluator(String modelPath) {
        this.modelPath = modelPath;
    }

    /**
     * Evaluates a directory containing subdirectories of presentations.
     * If the directory directly contains image files, it treats it as a single presentation.
     */
    public List<PresentationResult> evaluateCategory(File categoryDir, String categoryName) {
        if (categoryDir == null || !categoryDir.exists() || !categoryDir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] subDirs = categoryDir.listFiles(File::isDirectory);
        List<PresentationResult> results = new ArrayList<>();

        if (subDirs != null && subDirs.length > 0) {
            Arrays.sort(subDirs, Comparator.comparing(File::getName));
            for (File presentationDir : subDirs) {
                results.add(evaluateSinglePresentation(presentationDir, categoryName));
            }
        } else {
            // Check if directory directly contains image files
            File[] images = categoryDir.listFiles(f -> isImageFile(f.getName()));
            if (images != null && images.length > 0) {
                results.add(evaluateSinglePresentation(categoryDir, categoryName));
            }
        }

        return results;
    }

    public PresentationResult evaluateSinglePresentation(File presentationDir, String categoryName) {
        File[] imageFiles = presentationDir.listFiles(f -> isImageFile(f.getName()));
        if (imageFiles == null || imageFiles.length == 0) {
            return new PresentationResult(presentationDir.getName(), categoryName, 0, DecisionType.ERROR, 0.0, "No image files found");
        }

        Arrays.sort(imageFiles, Comparator.comparing(File::getName));

        ClientLandmarkAnalyzer clientAnalyzer = new ClientLandmarkAnalyzer();
        DefaultLivenessEngine engine = new DefaultLivenessEngine(
                clientAnalyzer,
                new PassiveAnalyzer(),
                new ChallengeGenerator(),
                new InMemoryAuditSink(500),
                modelPath
        );

        LivenessPolicy policy = LivenessPolicy.builder()
                .passiveThreshold(0.80)
                .padThreshold(0.70)
                .activeEnabled(false) // Passive evaluation benchmark
                .minChallenges(0)
                .passiveWindowFrames(Math.min(5, imageFiles.length))
                .failureBehaviour(FailureBehaviour.BLOCK)
                .build();

        String sessionId = "eval-" + UUID.randomUUID();
        LivenessSession session = engine.startSession(sessionId, WorkflowType.RESIDENT_AUTH, policy, null);

        DecisionType lastDecision = DecisionType.RETRY;
        double lastScore = 0.0;
        int frameSeq = 0;

        try {
            for (File imgFile : imageFiles) {
                BufferedImage bImg = ImageIO.read(imgFile);
                if (bImg == null) continue;

                int w = bImg.getWidth();
                int h = bImg.getHeight();
                byte[] rgb = new byte[w * h * 3];
                int[] pixels = bImg.getRGB(0, 0, w, h, null, 0, w);
                for (int i = 0; i < pixels.length; i++) {
                    int p = pixels[i];
                    rgb[i * 3] = (byte) ((p >> 16) & 0xFF);
                    rgb[i * 3 + 1] = (byte) ((p >> 8) & 0xFF);
                    rgb[i * 3 + 2] = (byte) (p & 0xFF);
                }

                // Check for sidecar JSON
                File sidecarJson = getSidecarFile(imgFile);
                if (sidecarJson != null && sidecarJson.exists()) {
                    List<Point2D> points = parseSidecarPoints(sidecarJson);
                    if (!points.isEmpty()) {
                        clientAnalyzer.pushLandmarks(sessionId, points, 0.99, 1);
                    }
                }

                FaceFrame frame = FaceFrame.wrap(rgb, w, h, PixelFormat.RGB_888, System.nanoTime(), frameSeq++, sessionId, 0);
                try {
                    LivenessDecision decision = engine.processFrame(session, frame);
                    lastDecision = decision.decisionType();
                    lastScore = decision.finalScore();

                    if (lastDecision == DecisionType.ACCEPTED || lastDecision == DecisionType.REJECTED) {
                        break;
                    }
                } finally {
                    if (!frame.isWiped()) {
                        frame.wipe();
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error evaluating presentation {}: {}", presentationDir.getName(), e.getMessage());
            lastDecision = DecisionType.ERROR;
        } finally {
            engine.close();
        }

        return new PresentationResult(
                presentationDir.getName(),
                categoryName,
                imageFiles.length,
                lastDecision,
                lastScore,
                "Frames: " + imageFiles.length
        );
    }

    public static CategoryMetrics computeMetrics(String category, List<PresentationResult> results, boolean isAttack) {
        int total = results.size();
        if (total == 0) {
            return new CategoryMetrics(category, 0, 0, 0, 0, 0.0);
        }

        int accepted = 0;
        int rejected = 0;
        int retryOrError = 0;

        for (PresentationResult r : results) {
            if (r.finalDecision() == DecisionType.ACCEPTED) {
                accepted++;
            } else if (r.finalDecision() == DecisionType.REJECTED || r.finalDecision() == DecisionType.FAILED_MAX_RETRIES) {
                rejected++;
            } else {
                retryOrError++;
            }
        }

        double errorRate;
        if (isAttack) {
            // APCER = attacks accepted / total attacks
            errorRate = ((double) accepted / total) * 100.0;
        } else {
            // BPCER = genuine rejected / total genuine
            errorRate = ((double) (rejected + retryOrError) / total) * 100.0;
        }

        return new CategoryMetrics(category, total, accepted, rejected, retryOrError, errorRate);
    }

    private static boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private static File getSidecarFile(File imgFile) {
        String path = imgFile.getAbsolutePath();
        int dot = path.lastIndexOf('.');
        if (dot > 0) {
            String jsonPath = path.substring(0, dot) + ".json";
            return new File(jsonPath);
        }
        return null;
    }

    private static List<Point2D> parseSidecarPoints(File jsonFile) {
        try {
            JsonNode root = mapper.readTree(jsonFile);
            JsonNode lms = root.has("landmarks") ? root.get("landmarks") : root;
            if (lms.isArray()) {
                List<Point2D> pts = new ArrayList<>(lms.size());
                for (JsonNode pt : lms) {
                    if (pt.isArray() && pt.size() >= 2) {
                        pts.add(new Point2D((float) pt.get(0).asDouble(), (float) pt.get(1).asDouble()));
                    } else if (pt.isObject() && pt.has("x") && pt.has("y")) {
                        pts.add(new Point2D((float) pt.get("x").asDouble(), (float) pt.get("y").asDouble()));
                    }
                }
                return pts;
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }
}
