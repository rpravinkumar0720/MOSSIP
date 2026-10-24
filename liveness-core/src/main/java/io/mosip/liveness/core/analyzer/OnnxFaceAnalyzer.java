package io.mosip.liveness.core.analyzer;

import io.mosip.liveness.api.frame.FaceFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;

/**
 * Optional ONNX-based face detector.
 * If model path is not configured, model file does not exist, or ONNX Runtime is not on the classpath,
 * it is safely disabled and logs at INFO level.
 */
public class OnnxFaceAnalyzer implements FaceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OnnxFaceAnalyzer.class);

    private final String modelPath;
    private final boolean available;

    public OnnxFaceAnalyzer(String modelPath) {
        this.modelPath = modelPath;
        if (modelPath != null && !modelPath.isBlank() && new File(modelPath).exists()) {
            boolean hasRuntime = false;
            try {
                Class.forName("ai.onnxruntime.OrtEnvironment");
                hasRuntime = true;
            } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            }
            this.available = hasRuntime;
            if (this.available) {
                log.info("OnnxFaceAnalyzer initialized with model at: {}", modelPath);
            } else {
                log.info("OnnxFaceAnalyzer: ONNX runtime not found on classpath; falling back to heuristic/client analyzer.");
            }
        } else {
            this.available = false;
            log.info("OnnxFaceAnalyzer: No ONNX face model configured; operating in baseline heuristic mode.");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String getModelPath() {
        return modelPath;
    }

    @Override
    public Optional<FaceObservation> analyze(String sessionId, FaceFrame frame) {
        if (!available) {
            return Optional.empty();
        }
        // If an ONNX model is integrated in future, inference can be called here.
        return Optional.empty();
    }
}
