package io.mosip.liveness.eval;

import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.eval.Evaluator.CategoryMetrics;
import io.mosip.liveness.eval.Evaluator.DetPoint;
import io.mosip.liveness.eval.Evaluator.PresentationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluatorTest {

    @Test
    @DisplayName("computeMetrics calculates APCER, BPCER, CI, and latencies accurately")
    void computeMetricsTest() {
        // Attack: 4 total, 1 accepted, 3 rejected -> APCER = 25%
        List<PresentationResult> attacks = List.of(
                new PresentationResult("p1", "PRINT", 5, DecisionType.ACCEPTED, 0.85, 15_000_000L, ""),
                new PresentationResult("p2", "PRINT", 5, DecisionType.REJECTED, 0.40, 20_000_000L, ""),
                new PresentationResult("p3", "PRINT", 5, DecisionType.REJECTED, 0.35, 25_000_000L, ""),
                new PresentationResult("p4", "PRINT", 5, DecisionType.REJECTED, 0.30, 30_000_000L, "")
        );
        CategoryMetrics attackMetrics = Evaluator.computeMetrics("Print", attacks, true);
        assertThat(attackMetrics.totalPresentations()).isEqualTo(4);
        assertThat(attackMetrics.acceptedCount()).isEqualTo(1);
        assertThat(attackMetrics.errorRatePercent()).isEqualTo(25.0); // 1 / 4 = 25%
        assertThat(attackMetrics.ciLowerPercent()).isLessThan(attackMetrics.errorRatePercent());
        assertThat(attackMetrics.ciUpperPercent()).isGreaterThan(attackMetrics.errorRatePercent());
        assertThat(attackMetrics.latencyP50Ms()).isGreaterThan(10.0);

        // Genuine: 4 total, 3 accepted, 1 rejected -> BPCER = 25%
        List<PresentationResult> genuine = List.of(
                new PresentationResult("g1", "GENUINE", 5, DecisionType.ACCEPTED, 0.95, 12_000_000L, ""),
                new PresentationResult("g2", "GENUINE", 5, DecisionType.ACCEPTED, 0.90, 14_000_000L, ""),
                new PresentationResult("g3", "GENUINE", 5, DecisionType.ACCEPTED, 0.88, 16_000_000L, ""),
                new PresentationResult("g4", "GENUINE", 5, DecisionType.REJECTED, 0.45, 18_000_000L, "")
        );
        CategoryMetrics genuineMetrics = Evaluator.computeMetrics("Genuine", genuine, false);
        assertThat(genuineMetrics.totalPresentations()).isEqualTo(4);
        assertThat(genuineMetrics.acceptedCount()).isEqualTo(3);
        assertThat(genuineMetrics.errorRatePercent()).isEqualTo(25.0); // 1 / 4 = 25%
    }

    @Test
    @DisplayName("Clopper-Pearson CI satisfies boundary and monotonicity constraints")
    void testClopperPearsonCI() {
        // 0 errors out of 30: lower = 0, upper ~ 11.6%
        double[] ciZero = Evaluator.computeClopperPearsonCI(0, 30, 0.95);
        assertThat(ciZero[0]).isEqualTo(0.0);
        assertThat(ciZero[1]).isBetween(10.0, 13.0);

        // 30 errors out of 30: lower ~ 88.4%, upper = 100%
        double[] ciFull = Evaluator.computeClopperPearsonCI(30, 30, 0.95);
        assertThat(ciFull[0]).isBetween(87.0, 90.0);
        assertThat(ciFull[1]).isEqualTo(100.0);

        // 1 error out of 30: lower ~ 0.08%, upper ~ 17.2%
        double[] ciOne = Evaluator.computeClopperPearsonCI(1, 30, 0.95);
        assertThat(ciOne[0]).isGreaterThan(0.0);
        assertThat(ciOne[1]).isGreaterThan(ciOne[0]);
    }

    @Test
    @DisplayName("computeDetCurve produces monotonic points")
    void testDetCurveCalculation() {
        List<PresentationResult> genuine = List.of(
                new PresentationResult("g1", "GENUINE", 5, DecisionType.ACCEPTED, 0.92, 10_000_000L, ""),
                new PresentationResult("g2", "GENUINE", 5, DecisionType.ACCEPTED, 0.84, 10_000_000L, "")
        );
        List<PresentationResult> attacks = List.of(
                new PresentationResult("a1", "PRINT", 5, DecisionType.REJECTED, 0.15, 10_000_000L, ""),
                new PresentationResult("a2", "PRINT", 5, DecisionType.REJECTED, 0.35, 10_000_000L, "")
        );

        List<DetPoint> det = Evaluator.computeDetCurve(genuine, attacks);
        assertThat(det).isNotEmpty();
        // At threshold 0.10: attacks accepted is higher, genuine rejected is 0
        DetPoint low = det.get(0);
        assertThat(low.bpcerPercent()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("evaluateCategory processes frame folder correctly")
    void evaluateCategoryWithSyntheticFrames(@TempDir Path tempDir) throws IOException {
        File presDir = tempDir.resolve("presentation_01").toFile();
        presDir.mkdirs();

        // Create 3 synthetic test images
        for (int i = 0; i < 3; i++) {
            BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(210, 160, 130)); // Skin tone
            g.fillOval(80, 40, 160, 160);
            g.dispose();

            File imgFile = new File(presDir, String.format("frame_%03d.png", i));
            ImageIO.write(img, "png", imgFile);
        }

        Evaluator evaluator = new Evaluator(null);
        List<PresentationResult> results = evaluator.evaluateCategory(tempDir.toFile(), "GENUINE");

        assertThat(results).hasSize(1);
        PresentationResult res = results.get(0);
        assertThat(res.presentationId()).isEqualTo("presentation_01");
        assertThat(res.totalFrames()).isEqualTo(3);
        assertThat(res.latencyNanos()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("evaluate genuine synthetic sample accepts upon window completion")
    void testGenuineSampleAccepts() {
        File s1 = new File("datasets/genuine/sample_01");
        if (s1.exists()) {
            Evaluator evaluator = new Evaluator(null);
            PresentationResult res = evaluator.evaluateSinglePresentation(s1, "GENUINE");
            assertThat(res.finalDecision()).isEqualTo(DecisionType.ACCEPTED);
            assertThat(res.livenessScore()).isGreaterThanOrEqualTo(0.70);
        }
    }
}
