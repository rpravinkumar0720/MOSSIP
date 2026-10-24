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
import java.util.Optional;

/**
 * Facial feature embedding service using MobileFaceNet/ArcFace ONNX.
 * Extracts deep identity vectors and calculates cosine similarity to guarantee
 * face continuity between initial session capture and final decision, defeating mid-session face swaps.
 */
public class FaceEmbeddingService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FaceEmbeddingService.class);

    private static final int EMBED_SIZE = 112;

    private final String modelPath;
    private final boolean available;
    private OrtEnvironment env;
    private OrtSession session;

    public FaceEmbeddingService(String modelPath) {
        this.modelPath = modelPath;
        boolean initOk = false;
        if (modelPath != null && !modelPath.isBlank() && new File(modelPath).exists()) {
            try {
                this.env = OrtEnvironment.getEnvironment();
                this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
                initOk = true;
                log.info("FaceEmbeddingService successfully loaded embedding model from: {}", modelPath);
            } catch (Throwable t) {
                log.warn("FaceEmbeddingService: could not load model at {}: {}", modelPath, t.getMessage());
            }
        } else {
            log.info("FaceEmbeddingService: No embedding model at {}; operating with landmark geometric signature.", modelPath);
        }
        this.available = initOk;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Extracts L2-normalized 128/512-d facial embedding vector.
     */
    public Optional<float[]> extractEmbedding(FaceFrame frame, BoundingBox box) {
        if (!available || frame == null || frame.isWiped() || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return Optional.empty();
        }

        try {
            float[] chw = cropAndPreprocess(frame, box);
            FloatBuffer buffer = FloatBuffer.wrap(chw);
            long[] shape = new long[]{1, 3, EMBED_SIZE, EMBED_SIZE};

            try (OnnxTensor tensor = OnnxTensor.createTensor(env, buffer, shape);
                 OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), tensor))) {

                float[][] raw = (float[][]) result.get(0).getValue();
                float[] emb = raw[0];

                // L2 normalize
                double norm = 0.0;
                for (float v : emb) norm += v * v;
                norm = Math.sqrt(norm);
                if (norm > 1e-6) {
                    for (int i = 0; i < emb.length; i++) {
                        emb[i] = (float) (emb[i] / norm);
                    }
                }
                return Optional.of(emb);
            }
        } catch (Throwable t) {
            log.warn("Error running FaceEmbedding ONNX inference: {}", t.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Computes cosine similarity between two normalized feature embeddings (-1.0 to 1.0).
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return Math.max(-1.0, Math.min(1.0, dot));
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

        float[] chw = new float[3 * EMBED_SIZE * EMBED_SIZE];
        int plane = EMBED_SIZE * EMBED_SIZE;

        for (int dy = 0; dy < EMBED_SIZE; dy++) {
            int sy = y0 + (dy * bh / EMBED_SIZE);
            if (sy >= h) sy = h - 1;
            for (int dx = 0; dx < EMBED_SIZE; dx++) {
                int sx = x0 + (dx * bw / EMBED_SIZE);
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

                int dstIdx = dy * EMBED_SIZE + dx;
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
