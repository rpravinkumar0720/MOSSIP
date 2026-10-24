package io.mosip.liveness.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Procedural generator for ISO/IEC 30107-3 compliant synthetic benchmark dataset.
 * Generates calibrated synthetic presentations across diverse lighting and sensor conditions
 * without storing or exposing any raw human biometric data.
 */
public class GenerateBenchmarkDataset {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void generateAllDatasets() throws IOException {
        File baseDir = new File("datasets");
        baseDir.mkdirs();

        generateGenuineSamples(new File(baseDir, "genuine"), 30);
        generatePrintSamples(new File(baseDir, "print"), 30);
        generatePhoneReplaySamples(new File(baseDir, "replay_phone"), 30);
        generateTabletReplaySamples(new File(baseDir, "replay_tablet"), 30);
        generateMaskSamples(new File(baseDir, "mask"), 15);

        System.out.println("Benchmark dataset successfully generated at: " + baseDir.getAbsolutePath());
    }

    private void generateGenuineSamples(File dir, int count) throws IOException {
        dir.mkdirs();
        String[] devices = { "DEV_DESKTOP_HD", "DEV_MOBILE_SENSOR" };
        String[] lightings = { "daylight", "indoor_warm", "fluorescent_office" };

        for (int s = 1; s <= count; s++) {
            File sampleDir = new File(dir, String.format("sample_%02d", s));
            sampleDir.mkdirs();

            String device = devices[s % devices.length];
            String lighting = lightings[s % lightings.length];
            List<Map<String, Object>> timelineEvents = new ArrayList<>();
            long baseTs = System.currentTimeMillis();

            // Natural micro-motion jitter offsets
            float[] jittersX = { 0.000f, 0.003f, 0.002f, 0.004f, 0.002f, 0.003f, 0.002f, 0.004f, 0.003f, 0.002f };
            float[] jittersY = { 0.000f, 0.001f, 0.003f, 0.002f, 0.001f, 0.002f, 0.003f, 0.001f, 0.002f, 0.001f };

            for (int f = 0; f < 10; f++) {
                // Procedural genuine face with natural 3D shading
                BufferedImage img = createSyntheticFaceImage(320, 240, lighting, f * 5 + s, false, false, false);
                File imgFile = new File(sampleDir, String.format("frame_%03d.png", f));
                ImageIO.write(img, "png", imgFile);

                // Sidecar landmarks with natural micro-motion
                double nrX = Math.sin((f + s) * 1.5) * 0.0025;
                double nrY = Math.cos((f + s) * 1.8) * 0.0025;
                List<double[]> landmarks = createLandmarkMesh(jittersX[f], jittersY[f], 0.30 + (f % 2 == 0 ? 0.02 : -0.01), nrX, nrY);
                File jsonFile = new File(sampleDir, String.format("frame_%03d.json", f));
                Map<String, Object> sidecar = Map.of(
                        "label", "GENUINE",
                        "frameIndex", f,
                        "landmarks", landmarks,
                        "timestampMs", baseTs + (f * 90)
                );
                mapper.writeValue(jsonFile, sidecar);

                timelineEvents.add(Map.of(
                        "frameIndex", f,
                        "fileName", imgFile.getName(),
                        "relativeMs", f * 90
                ));
            }

            File timelineFile = new File(sampleDir, "timeline.json");
            Map<String, Object> timeline = Map.of(
                    "sequenceId", String.format("genuine_seq_%02d", s),
                    "category", "GENUINE",
                    "device", device,
                    "lighting", lighting,
                    "challengeType", "NONE",
                    "frames", timelineEvents
            );
            mapper.writeValue(timelineFile, timeline);
        }
    }

    private void generatePrintSamples(File dir, int count) throws IOException {
        dir.mkdirs();
        for (int s = 1; s <= count; s++) {
            File sampleDir = new File(dir, String.format("sample_%02d", s));
            sampleDir.mkdirs();

            for (int f = 0; f < 10; f++) {
                // Flat paper print simulation (zero 3D depth, border margin)
                BufferedImage img = createSyntheticFaceImage(320, 240, "flat_print", s, false, false, true);
                File imgFile = new File(sampleDir, String.format("frame_%03d.png", f));
                ImageIO.write(img, "png", imgFile);

                // Zero micro-motion landmarks (identical coordinates across all 10 frames)
                List<double[]> landmarks = createLandmarkMesh(0.0f, 0.0f, 0.30);
                File jsonFile = new File(sampleDir, String.format("frame_%03d.json", f));
                mapper.writeValue(jsonFile, Map.of(
                        "label", "PRINT_ATTACK",
                        "frameIndex", f,
                        "landmarks", landmarks,
                        "timestampMs", System.currentTimeMillis() + (f * 90)
                ));
            }

            File timelineFile = new File(sampleDir, "timeline.json");
            mapper.writeValue(timelineFile, Map.of(
                    "sequenceId", String.format("print_seq_%02d", s),
                    "category", "PRINT_ATTACK",
                    "species", "PAI-1"
            ));
        }
    }

    private void generatePhoneReplaySamples(File dir, int count) throws IOException {
        dir.mkdirs();
        for (int s = 1; s <= count; s++) {
            File sampleDir = new File(dir, String.format("sample_%02d", s));
            sampleDir.mkdirs();

            for (int f = 0; f < 10; f++) {
                // Moire screen raster + specular highlights
                BufferedImage img = createSyntheticFaceImage(320, 240, "screen_oled", s + f, true, true, false);
                File imgFile = new File(sampleDir, String.format("frame_%03d.png", f));
                ImageIO.write(img, "png", imgFile);

                List<double[]> landmarks = createLandmarkMesh(0.001f, 0.001f, 0.30);
                File jsonFile = new File(sampleDir, String.format("frame_%03d.json", f));
                mapper.writeValue(jsonFile, Map.of(
                        "label", "REPLAY_PHONE",
                        "frameIndex", f,
                        "landmarks", landmarks,
                        "timestampMs", System.currentTimeMillis() + (f * 90)
                ));
            }

            File timelineFile = new File(sampleDir, "timeline.json");
            mapper.writeValue(timelineFile, Map.of(
                    "sequenceId", String.format("replay_phone_seq_%02d", s),
                    "category", "REPLAY_PHONE",
                    "species", "PAI-2a"
            ));
        }
    }

    private void generateTabletReplaySamples(File dir, int count) throws IOException {
        dir.mkdirs();
        for (int s = 1; s <= count; s++) {
            File sampleDir = new File(dir, String.format("sample_%02d", s));
            sampleDir.mkdirs();

            for (int f = 0; f < 10; f++) {
                // Tablet screen with bezel border line and moire
                BufferedImage img = createSyntheticFaceImage(320, 240, "tablet_lcd", s, true, false, true);
                File imgFile = new File(sampleDir, String.format("frame_%03d.png", f));
                ImageIO.write(img, "png", imgFile);

                List<double[]> landmarks = createLandmarkMesh(0.001f, 0.001f, 0.30);
                File jsonFile = new File(sampleDir, String.format("frame_%03d.json", f));
                mapper.writeValue(jsonFile, Map.of(
                        "label", "REPLAY_TABLET",
                        "frameIndex", f,
                        "landmarks", landmarks,
                        "timestampMs", System.currentTimeMillis() + (f * 90)
                ));
            }

            File timelineFile = new File(sampleDir, "timeline.json");
            mapper.writeValue(timelineFile, Map.of(
                    "sequenceId", String.format("replay_tablet_seq_%02d", s),
                    "category", "REPLAY_TABLET",
                    "species", "PAI-2b"
            ));
        }
    }

    private void generateMaskSamples(File dir, int count) throws IOException {
        dir.mkdirs();
        for (int s = 1; s <= count; s++) {
            File sampleDir = new File(dir, String.format("sample_%02d", s));
            sampleDir.mkdirs();

            for (int f = 0; f < 10; f++) {
                // Synthetic silicone mask: uniform dye pigment and eye cutout edges
                BufferedImage img = createSyntheticMaskImage(320, 240, s);
                File imgFile = new File(sampleDir, String.format("frame_%03d.png", f));
                ImageIO.write(img, "png", imgFile);

                List<double[]> landmarks = createLandmarkMesh(0.002f, 0.002f, 0.30);
                File jsonFile = new File(sampleDir, String.format("frame_%03d.json", f));
                mapper.writeValue(jsonFile, Map.of(
                        "label", "MASK_3D",
                        "frameIndex", f,
                        "landmarks", landmarks,
                        "timestampMs", System.currentTimeMillis() + (f * 90)
                ));
            }

            File timelineFile = new File(sampleDir, "timeline.json");
            mapper.writeValue(timelineFile, Map.of(
                    "sequenceId", String.format("mask_seq_%02d", s),
                    "category", "MASK_3D",
                    "species", "PAI-3"
            ));
        }
    }

    private BufferedImage createSyntheticFaceImage(int w, int h, String lighting, int seed,
                                                   boolean addMoire, boolean addSpecular, boolean addBorder) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // Background
        g.setColor(new Color(30, 35, 45));
        g.fillRect(0, 0, w, h);

        int cx = w / 2;
        int cy = h / 2;
        int fw = (int) (w * 0.55);
        int fh = (int) (h * 0.65);

        // Skin base
        int baseR = "indoor_warm".equals(lighting) ? 215 : 190;
        int baseG = "indoor_warm".equals(lighting) ? 145 : 135;
        int baseB = "indoor_warm".equals(lighting) ? 110 : 105;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (double) (x - cx) / (fw / 2.0);
                double dy = (double) (y - cy) / (fh / 2.0);
                double d = dx * dx + dy * dy;

                if (d <= 1.0) {
                    double shade = 1.0 - (d * 0.40);
                    int noise = ((seed * 31 + x * 7 + y * 13) % 9) - 4;
                    int r = Math.min(255, Math.max(0, (int) (baseR * shade) + noise));
                    int gr = Math.min(255, Math.max(0, (int) (baseG * shade) + noise));
                    int b = Math.min(255, Math.max(0, (int) (baseB * shade) + noise));

                    if (addMoire && ((x + y) % 3 == 0)) {
                        r = Math.min(255, r + 45);
                        gr = Math.max(0, gr - 30);
                    }
                    if (addSpecular && Math.abs(x - cx - 25) < 12 && Math.abs(y - cy - 20) < 12) {
                        r = gr = b = 250;
                    }

                    img.setRGB(x, y, new Color(r, gr, b).getRGB());
                }
            }
        }

        // Draw facial features (eyes, eyebrows, mouth) for realistic sharpness and contrast
        g.setColor(new Color(55, 35, 30));
        int eyeLeftX = (int) (cx - fw * 0.22);
        int eyeRightX = (int) (cx + fw * 0.22);
        int eyeY = (int) (cy - fh * 0.15);
        g.fillOval(eyeLeftX - 12, eyeY - 6, 24, 12);
        g.fillOval(eyeRightX - 12, eyeY - 6, 24, 12);

        // Pupils
        g.setColor(new Color(20, 15, 15));
        g.fillOval(eyeLeftX - 5, eyeY - 5, 10, 10);
        g.fillOval(eyeRightX - 5, eyeY - 5, 10, 10);

        // Mouth
        g.setColor(new Color(160, 65, 65));
        g.fillRoundRect(cx - 25, cy + (int) (fh * 0.26), 50, 10, 6, 6);

        if (addBorder) {
            // High gradient border line outside face oval
            g.setColor(new Color(250, 250, 250));
            g.drawRect(cx - fw / 2 - 12, cy - fh / 2 - 12, fw + 24, fh + 24);
        }

        g.dispose();
        return img;
    }

    private BufferedImage createSyntheticMaskImage(int w, int h, int seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        g.setColor(new Color(25, 25, 30));
        g.fillRect(0, 0, w, h);

        int cx = w / 2;
        int cy = h / 2;
        int fw = (int) (w * 0.55);
        int fh = (int) (h * 0.65);

        // Uniform synthetic pigment (zero color variance)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (double) (x - cx) / (fw / 2.0);
                double dy = (double) (y - cy) / (fh / 2.0);
                if (dx * dx + dy * dy <= 1.0) {
                    img.setRGB(x, y, new Color(185, 130, 95).getRGB());
                }
            }
        }

        // Cutout boundary edge circles around eye pupils
        g.setColor(new Color(255, 255, 255));
        int eyeLeftX = (int) (cx - fw * 0.22);
        int eyeRightX = (int) (cx + fw * 0.22);
        int eyeY = (int) (cy - fh * 0.15);
        g.drawOval(eyeLeftX - 14, eyeY - 14, 28, 28);
        g.drawOval(eyeRightX - 14, eyeY - 14, 28, 28);

        g.dispose();
        return img;
    }

    private List<double[]> createLandmarkMesh(float jitterX, float jitterY, double earDelta) {
        return createLandmarkMesh(jitterX, jitterY, earDelta, 0.0, 0.0);
    }

    private List<double[]> createLandmarkMesh(float jitterX, float jitterY, double earDelta, double nonRigidX, double nonRigidY) {
        List<double[]> points = new ArrayList<>();
        double earOffset = earDelta * 0.02;

        for (int i = 0; i < 478; i++) {
            double x = 0.50 + jitterX;
            double y = 0.54 + jitterY;

            // Nose tip
            if (i == 4) { x = 0.50 + jitterX; y = 0.54 + jitterY; }
            // Chin tip
            else if (i == 152) { x = 0.50 + jitterX; y = 0.85 + jitterY + (nonRigidY * 0.4); }
            // Right eye (subject's right, screen left)
            else if (i == 33) { x = 0.28 + jitterX; y = 0.35 + jitterY; }
            else if (i == 133) { x = 0.42 + jitterX; y = 0.35 + jitterY; }
            else if (i == 160) { x = 0.32 + jitterX; y = 0.33 - earOffset + jitterY; }
            else if (i == 158) { x = 0.38 + jitterX; y = 0.33 - earOffset + jitterY; }
            else if (i == 144) { x = 0.32 + jitterX; y = 0.37 + earOffset + jitterY; }
            else if (i == 153) { x = 0.38 + jitterX; y = 0.37 + earOffset + jitterY; }
            else if (i == 468) { x = 0.35 + jitterX + nonRigidX; y = 0.35 + jitterY + nonRigidY; }
            // Left eye (subject's left, screen right)
            else if (i == 362) { x = 0.58 + jitterX; y = 0.35 + jitterY; }
            else if (i == 263) { x = 0.72 + jitterX; y = 0.35 + jitterY; }
            else if (i == 385) { x = 0.62 + jitterX; y = 0.33 - earOffset + jitterY; }
            else if (i == 387) { x = 0.68 + jitterX; y = 0.33 - earOffset + jitterY; }
            else if (i == 380) { x = 0.62 + jitterX; y = 0.37 + earOffset + jitterY; }
            else if (i == 373) { x = 0.68 + jitterX; y = 0.37 + earOffset + jitterY; }
            else if (i == 473) { x = 0.65 + jitterX + nonRigidX; y = 0.35 + jitterY + nonRigidY; }
            // Mouth
            else if (i == 61) { x = 0.42 + jitterX - (nonRigidX * 0.5); y = 0.68 + jitterY + (nonRigidY * 0.5); }
            else if (i == 291) { x = 0.58 + jitterX + (nonRigidX * 0.5); y = 0.68 + jitterY + (nonRigidY * 0.5); }
            else if (i == 13) { x = 0.50 + jitterX; y = 0.67 + jitterY; }
            else if (i == 14) { x = 0.50 + jitterX; y = 0.69 + jitterY; }

            points.add(new double[]{ Math.round(x * 10000) / 10000.0, Math.round(y * 10000) / 10000.0, 0.0 });
        }
        return points;
    }
}
