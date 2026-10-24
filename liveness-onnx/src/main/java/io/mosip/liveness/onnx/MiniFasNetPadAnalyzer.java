package io.mosip.liveness.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.frame.PixelFormat;
import io.mosip.liveness.api.quality.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Neural Presentation Attack Detection (PAD) using MiniFASNet (Apache-2.0).
 * Multi-scale face crop classification detecting print, replay, and mask presentation attacks.
 * Fused with the pure-Java heuristic analyzer for defense-in-depth.
 */
public class MiniFasNetPadAnalyzer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MiniFasNetPadAnalyzer.class);

    private static final int CROP_SIZE = 80;

    private final String modelPath;
    private final boolean available;
    private OrtEnvironment env;
    private OrtSession session;

    public MiniFasNetPadAnalyzer(String modelPath) {
        this.modelPath = modelPath;
        boolean initOk = false;
        if (modelPath != null && !modelPath.isBlank() && new File(modelPath).exists()) {
            try {
                this.env = OrtEnvironment.getEnvironment();
                this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
                initOk = true;
                log.info("MiniFasNetPadAnalyzer successfully loaded model from: {}", modelPath);
            } catch (Throwable t) {
                log.warn("MiniFasNetPadAnalyzer: could not load ONNX model at {}: {}", modelPath, t.getMessage());
            }
        } else {
            log.info("MiniFasNetPadAnalyzer: No ONNX model file found at {}; neural PAD disabled, using heuristics.", modelPath);
        }
        this.available = initOk;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Evaluates face frame for presentation attacks using MiniFASNet.
     *
     * @param frame raw face frame
     * @param box face bounding box
     * @return OptionalDouble containing neural liveness score (0.0 to 1.0) where > 0.50 is genuine
     */
    public OptionalDouble evaluate(FaceFrame frame, BoundingBox box) {
        if (!available || frame == null || frame.isWiped() || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return OptionalDouble.empty();
        }

        try {
            float[] chw = cropAndPreprocess(frame, box);
            FloatBuffer buffer = FloatBuffer.wrap(chw);
            long[] shape = new long[]{1, 3, CROP_SIZE, CROP_SIZE};

            try (OnnxTensor tensor = OnnxTensor.createTensor(env, buffer, shape);
                 OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), tensor))) {

                float[][] output = (float[][]) result.get(0).getValue();
                float[] logits = output[0];

                // Softmax over logits: class 0 = spoof, class 1 = live
                float exp0 = (float) Math.exp(logits[0]);
                float exp1 = (float) Math.exp(logits[1]);
                float sum = exp0 + exp1;
                float pLive = sum > 0.0f ? exp1 / sum : 0.5f;

                return OptionalDouble.of(Math.max(0.0, Math.min(1.0, (double) pLive)));
            }
        } catch (Throwable t) {
            log.warn("Error running MiniFASNet neural inference: {}", t.getMessage());
            return OptionalDouble.empty();
        }
    }

    private float[] cropAndPreprocess(FaceFrame frame, BoundingBox box) {
        byte[] raw = frame.getRawDataUnsafe();
        int w = frame.getWidth();
        int h = frame.getHeight();
        PixelFormat fmt = frame.getFormat();
        int bpp = fmt.getBytesPerPixel();

        BoundingBox b = box != null ? box : BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 1.0f);
        int x0 = Math.max(0, b.pixelLeft(w));
        int y0 = Math.max(0, b.pixelTop(h));
        int bw = Math.max(1, Math.min(w - x0, b.pixelWidth(w)));
        int bh = Math.max(1, Math.min(h - y0, b.pixelHeight(h)));

        float[] chw = new float[3 * CROP_SIZE * CROP_SIZE];
        int plane = CROP_SIZE * CROP_SIZE;

        for (int dy = 0; dy < CROP_SIZE; dy++) {
            int sy = y0 + (dy * bh / CROP_SIZE);
            if (sy >= h) sy = h - 1;
            for (int dx = 0; dx < CROP_SIZE; dx++) {
                int sx = x0 + (dx * bw / CROP_SIZE);
                if (sx >= w) sx = w - 1;

                int offset = (sy * w + sx) * bpp;
                int r, g, bVal;
                if (fmt == PixelFormat.RGB_888 || fmt == PixelFormat.RGBA_8888) {
                    r = raw[offset] & 0xFF;
                    g = raw[offset + 1] & 0xFF;
                    bVal = raw[offset + 2] & 0xFF;
                } else if (fmt == PixelFormat.BGR_888) {
                    bVal = raw[offset] & 0xFF;
                    g = raw[offset + 1] & 0xFF;
                    r = raw[offset + 2] & 0xFF;
                } else {
                    r = g = bVal = raw[offset] & 0xFF;
                }

                int dstIdx = dy * CROP_SIZE + dx;
                chw[dstIdx] = (r - 127.5f) / 128.0f;
                chw[plane + dstIdx] = (g - 127.5f) / 128.0f;
                chw[2 * plane + dstIdx] = (bVal - 127.5f) / 128.0f;
            }
        }
        return chw;
    }

    @Override
    public void close() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
        }
        if (env != null) {
            try { env.close(); } catch (Exception ignored) {}
        }
    }
}
