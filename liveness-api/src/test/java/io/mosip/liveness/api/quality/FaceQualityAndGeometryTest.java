package io.mosip.liveness.api.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class FaceQualityAndGeometryTest {

    @Test
    @DisplayName("BoundingBox calculates dimensions and pixel coordinates accurately")
    void testBoundingBox() {
        BoundingBox bbox = BoundingBox.of(0.2f, 0.1f, 0.8f, 0.9f, 0.95f);

        assertThat(bbox.width()).isCloseTo(0.6f, offset(0.001f));
        assertThat(bbox.height()).isCloseTo(0.8f, offset(0.001f));
        assertThat(bbox.centerX()).isCloseTo(0.5f, offset(0.001f));
        assertThat(bbox.centerY()).isCloseTo(0.5f, offset(0.001f));
        assertThat(bbox.area()).isCloseTo(0.48f, offset(0.001f));

        // In 1280x720 image
        assertThat(bbox.pixelLeft(1280)).isEqualTo(256);
        assertThat(bbox.pixelTop(720)).isEqualTo(72);
        assertThat(bbox.pixelWidth(1280)).isEqualTo(768);
        assertThat(bbox.pixelHeight(720)).isEqualTo(576);
    }

    @Test
    @DisplayName("FaceLandmarks computes Eye Aspect Ratio (EAR) correctly")
    void testEyeAspectRatio() {
        // Construct landmark points for an open eye
        Point2D p1 = Point2D.of(0.20f, 0.30f); // outer corner
        Point2D p2 = Point2D.of(0.23f, 0.28f); // upper-outer
        Point2D p3 = Point2D.of(0.27f, 0.28f); // upper-inner
        Point2D p4 = Point2D.of(0.30f, 0.30f); // inner corner
        Point2D p5 = Point2D.of(0.27f, 0.32f); // lower-inner
        Point2D p6 = Point2D.of(0.23f, 0.32f); // lower-outer

        double earOpen = FaceLandmarks.computeEyeAspectRatio(p1, p2, p3, p4, p5, p6);
        assertThat(earOpen).isGreaterThan(0.25);

        // Construct landmark points for a closed eye (vertical distance near zero)
        Point2D c2 = Point2D.of(0.23f, 0.30f);
        Point2D c3 = Point2D.of(0.27f, 0.30f);
        Point2D c5 = Point2D.of(0.27f, 0.30f);
        Point2D c6 = Point2D.of(0.23f, 0.30f);

        double earClosed = FaceLandmarks.computeEyeAspectRatio(p1, c2, c3, p4, c5, c6);
        assertThat(earClosed).isLessThan(0.05);
    }

    @Test
    @DisplayName("FaceLandmarks computes Mouth Aspect Ratio (MAR) correctly")
    void testMouthAspectRatio() {
        Point2D leftCorner = Point2D.of(0.40f, 0.70f);
        Point2D rightCorner = Point2D.of(0.60f, 0.70f);

        // Neutral closed mouth
        Point2D upperLipClosed = Point2D.of(0.50f, 0.69f);
        Point2D lowerLipClosed = Point2D.of(0.50f, 0.71f);
        double marClosed = FaceLandmarks.computeMouthAspectRatio(upperLipClosed, lowerLipClosed, leftCorner, rightCorner);
        assertThat(marClosed).isLessThan(0.2);

        // Open mouth
        Point2D upperLipOpen = Point2D.of(0.50f, 0.65f);
        Point2D lowerLipOpen = Point2D.of(0.50f, 0.75f);
        double marOpen = FaceLandmarks.computeMouthAspectRatio(upperLipOpen, lowerLipOpen, leftCorner, rightCorner);
        assertThat(marOpen).isGreaterThan(0.4);
    }

    @Test
    @DisplayName("FaceQuality tracks compliance and specific ISO failure reasons")
    void testFaceQuality() {
        FaceQuality quality = FaceQuality.builder()
                .brightnessScore(0.3)
                .isCompliant(false)
                .qualityFailures(Set.of(QualityFailureReason.TOO_DARK, QualityFailureReason.BLURRY))
                .build();

        assertThat(quality.isCompliant()).isFalse();
        assertThat(quality.qualityFailures()).contains(QualityFailureReason.TOO_DARK, QualityFailureReason.BLURRY);
        assertThat(QualityFailureReason.TOO_DARK.getGuidanceMessage()).contains("lighting");
    }
}
