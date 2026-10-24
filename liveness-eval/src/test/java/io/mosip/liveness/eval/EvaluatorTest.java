package io.mosip.liveness.eval;

import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.eval.Evaluator.CategoryMetrics;
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
    @DisplayName("computeMetrics calculates APCER and BPCER accurately")
    void computeMetricsTest() {
        // Attack: 10 total, 1 accepted, 9 rejected -> APCER = 10%
        List<PresentationResult> attacks = List.of(
                new PresentationResult("p1", "PRINT", 5, DecisionType.ACCEPTED, 0.85, ""),
                new PresentationResult("p2", "PRINT", 5, DecisionType.REJECTED, 0.40, ""),
                new PresentationResult("p3", "PRINT", 5, DecisionType.REJECTED, 0.35, ""),
                new PresentationResult("p4", "PRINT", 5, DecisionType.REJECTED, 0.30, "")
        );
        CategoryMetrics attackMetrics = Evaluator.computeMetrics("Print", attacks, true);
        assertThat(attackMetrics.totalPresentations()).isEqualTo(4);
        assertThat(attackMetrics.acceptedCount()).isEqualTo(1);
        assertThat(attackMetrics.errorRatePercent()).isEqualTo(25.0); // 1 / 4 = 25%

        // Genuine: 4 total, 3 accepted, 1 rejected -> BPCER = 25%
        List<PresentationResult> genuine = List.of(
                new PresentationResult("g1", "GENUINE", 5, DecisionType.ACCEPTED, 0.95, ""),
                new PresentationResult("g2", "GENUINE", 5, DecisionType.ACCEPTED, 0.90, ""),
                new PresentationResult("g3", "GENUINE", 5, DecisionType.ACCEPTED, 0.88, ""),
                new PresentationResult("g4", "GENUINE", 5, DecisionType.REJECTED, 0.45, "")
        );
        CategoryMetrics genuineMetrics = Evaluator.computeMetrics("Genuine", genuine, false);
        assertThat(genuineMetrics.totalPresentations()).isEqualTo(4);
        assertThat(genuineMetrics.acceptedCount()).isEqualTo(3);
        assertThat(genuineMetrics.errorRatePercent()).isEqualTo(25.0); // 1 / 4 = 25%
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
    }
}
