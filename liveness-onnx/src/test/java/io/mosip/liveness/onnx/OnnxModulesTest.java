package io.mosip.liveness.onnx;

import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.frame.PixelFormat;
import io.mosip.liveness.api.quality.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxModulesTest {

    @Test
    @DisplayName("UltraFaceDetector degrades gracefully when model is absent")
    void testUltraFaceGracefulDegradation() {
        try (UltraFaceDetector detector = new UltraFaceDetector("non_existent_model.onnx")) {
            assertThat(detector.isAvailable()).isFalse();

            FaceFrame frame = FaceFrame.wrap(new byte[100 * 100 * 3], 100, 100, PixelFormat.RGB_888, 1L, 1L, "dev", 0);
            assertThat(detector.analyze("sess-1", frame)).isEmpty();
        }
    }

    @Test
    @DisplayName("MiniFasNetPadAnalyzer degrades gracefully when model is absent")
    void testMiniFasNetGracefulDegradation() {
        try (MiniFasNetPadAnalyzer analyzer = new MiniFasNetPadAnalyzer("non_existent_minifasnet.onnx")) {
            assertThat(analyzer.isAvailable()).isFalse();

            FaceFrame frame = FaceFrame.wrap(new byte[100 * 100 * 3], 100, 100, PixelFormat.RGB_888, 1L, 1L, "dev", 0);
            assertThat(analyzer.evaluate(frame, BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 1.0f))).isEmpty();
        }
    }

    @Test
    @DisplayName("FaceEmbeddingService calculates cosine similarity correctly")
    void testCosineSimilarityCalculation() {
        float[] v1 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v2 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v3 = new float[]{0.0f, 1.0f, 0.0f};
        float[] v4 = new float[]{-1.0f, 0.0f, 0.0f};

        assertThat(FaceEmbeddingService.cosineSimilarity(v1, v2)).isEqualTo(1.0);
        assertThat(FaceEmbeddingService.cosineSimilarity(v1, v3)).isEqualTo(0.0);
        assertThat(FaceEmbeddingService.cosineSimilarity(v1, v4)).isEqualTo(-1.0);
    }
}
