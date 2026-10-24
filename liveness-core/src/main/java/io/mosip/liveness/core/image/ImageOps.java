package io.mosip.liveness.core.image;

import io.mosip.liveness.api.frame.FaceFrame;
import io.mosip.liveness.api.frame.PixelFormat;
import io.mosip.liveness.api.quality.BoundingBox;

import java.util.Objects;

/**
 * Pure Java image operations for liveness detection and presentation attack detection (PAD).
 * Zero AWT/JavaFX dependencies to guarantee cross-platform portability across Desktop and Android.
 */
public final class ImageOps {

    private ImageOps() {
    }

    public record ColorStats(
            double rMean, double gMean, double bMean,
            double rStd, double gStd, double bStd,
            double meanSaturation, double saturationStd
    ) {}

    /**
     * Converts a FaceFrame to an 8-bit single-channel grayscale byte array.
     */
    public static byte[] toGrayscale(FaceFrame frame) {
        Objects.requireNonNull(frame, "FaceFrame cannot be null");
        return toGrayscale(frame.getRawDataUnsafe(), frame.getWidth(), frame.getHeight(), frame.getFormat());
    }

    /**
     * Converts raw pixel data to grayscale using standard ITU-R BT.601 luma weights:
     * Y = 0.299 * R + 0.587 * G + 0.114 * B
     */
    public static byte[] toGrayscale(byte[] data, int width, int height, PixelFormat format) {
        Objects.requireNonNull(data, "Pixel data cannot be null");
        int totalPixels = width * height;
        byte[] gray = new byte[totalPixels];

        if (format == PixelFormat.GRAY_8) {
            System.arraycopy(data, 0, gray, 0, Math.min(data.length, totalPixels));
            return gray;
        }

        int bpp = format.getBytesPerPixel();
        for (int i = 0; i < totalPixels; i++) {
            int offset = i * bpp;
            if (offset + (bpp > 1 ? 2 : 0) >= data.length) {
                break;
            }
            int r;
            int g;
            int b;

            if (format == PixelFormat.RGB_888 || format == PixelFormat.RGBA_8888) {
                r = data[offset] & 0xFF;
                g = data[offset + 1] & 0xFF;
                b = data[offset + 2] & 0xFF;
            } else if (format == PixelFormat.BGR_888) {
                b = data[offset] & 0xFF;
                g = data[offset + 1] & 0xFF;
                r = data[offset + 2] & 0xFF;
            } else {
                // Fallback for planar or other formats: direct copy
                gray[i] = data[Math.min(i, data.length - 1)];
                continue;
            }

            int luma = (299 * r + 587 * g + 114 * b + 500) / 1000;
            gray[i] = (byte) Math.min(255, Math.max(0, luma));
        }
        return gray;
    }

    /**
     * Computes the normalized mean luminance (0.0 to 1.0) of a frame.
     */
    public static double meanLuma(FaceFrame frame) {
        byte[] gray = toGrayscale(frame);
        return meanLuma(gray, frame.getWidth(), frame.getHeight(), null);
    }

    /**
     * Computes normalized mean luminance (0.0 to 1.0) over an optional BoundingBox ROI.
     */
    public static double meanLuma(byte[] grayData, int width, int height, BoundingBox box) {
        int x0 = box != null ? Math.max(0, box.pixelLeft(width)) : 0;
        int y0 = box != null ? Math.max(0, box.pixelTop(height)) : 0;
        int x1 = box != null ? Math.min(width, x0 + box.pixelWidth(width)) : width;
        int y1 = box != null ? Math.min(height, y0 + box.pixelHeight(height)) : height;

        long sum = 0;
        long count = 0;

        for (int y = y0; y < y1; y++) {
            int rowOffset = y * width;
            for (int x = x0; x < x1; x++) {
                int index = rowOffset + x;
                if (index < grayData.length) {
                    sum += (grayData[index] & 0xFF);
                    count++;
                }
            }
        }

        if (count == 0) {
            return 0.5;
        }
        return (double) sum / (count * 255.0);
    }

    /**
     * Computes the Laplacian variance over the face ROI of a FaceFrame.
     * Higher variance indicates a sharp image; low variance (< 80) indicates blur.
     */
    public static double laplacianVariance(FaceFrame frame, BoundingBox box) {
        byte[] gray = toGrayscale(frame);
        return laplacianVariance(gray, frame.getWidth(), frame.getHeight(), box);
    }

    /**
     * Computes Laplacian variance over an ROI using the 3x3 kernel:
     *  0   1   0
     *  1  -4   1
     *  0   1   0
     */
    public static double laplacianVariance(byte[] grayData, int width, int height, BoundingBox box) {
        int x0 = box != null ? Math.max(1, box.pixelLeft(width)) : 1;
        int y0 = box != null ? Math.max(1, box.pixelTop(height)) : 1;
        int x1 = box != null ? Math.min(width - 1, x0 + box.pixelWidth(width)) : width - 1;
        int y1 = box != null ? Math.min(height - 1, y0 + box.pixelHeight(height)) : height - 1;

        if (x1 <= x0 || y1 <= y0) {
            return 0.0;
        }

        int count = (x1 - x0) * (y1 - y0);
        if (count <= 0) {
            return 0.0;
        }

        double[] laplacian = new double[count];
        double sum = 0.0;
        int idx = 0;

        for (int y = y0; y < y1; y++) {
            int rowOffset = y * width;
            int prevRowOffset = (y - 1) * width;
            int nextRowOffset = (y + 1) * width;

            for (int x = x0; x < x1; x++) {
                int center = grayData[rowOffset + x] & 0xFF;
                int up = grayData[prevRowOffset + x] & 0xFF;
                int down = grayData[nextRowOffset + x] & 0xFF;
                int left = grayData[rowOffset + x - 1] & 0xFF;
                int right = grayData[rowOffset + x + 1] & 0xFF;

                double val = up + down + left + right - 4.0 * center;
                laplacian[idx++] = val;
                sum += val;
            }
        }

        double mean = sum / count;
        double varSum = 0.0;
        for (int i = 0; i < count; i++) {
            double diff = laplacian[i] - mean;
            varSum += diff * diff;
        }

        return varSum / count;
    }

    /**
     * Crops a bounding box region from a grayscale image.
     */
    public static byte[] cropRegion(byte[] grayData, int width, int height, BoundingBox box) {
        int x0 = Math.max(0, box.pixelLeft(width));
        int y0 = Math.max(0, box.pixelTop(height));
        int cropW = Math.max(1, Math.min(width - x0, box.pixelWidth(width)));
        int cropH = Math.max(1, Math.min(height - y0, box.pixelHeight(height)));

        byte[] cropped = new byte[cropW * cropH];
        for (int y = 0; y < cropH; y++) {
            int srcRow = (y0 + y) * width;
            int dstRow = y * cropW;
            System.arraycopy(grayData, srcRow + x0, cropped, dstRow, cropW);
        }
        return cropped;
    }

    /**
     * Downscales an 8-bit image patch using bilinear interpolation.
     */
    public static byte[] downscale(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH];
        float xRatio = (float) (srcW - 1) / Math.max(1, dstW - 1);
        float yRatio = (float) (srcH - 1) / Math.max(1, dstH - 1);

        for (int y = 0; y < dstH; y++) {
            float srcY = y * yRatio;
            int y0 = (int) srcY;
            int y1 = Math.min(srcH - 1, y0 + 1);
            float yDiff = srcY - y0;

            int dstRow = y * dstW;
            int srcRow0 = y0 * srcW;
            int srcRow1 = y1 * srcW;

            for (int x = 0; x < dstW; x++) {
                float srcX = x * xRatio;
                int x0 = (int) srcX;
                int x1 = Math.min(srcW - 1, x0 + 1);
                float xDiff = srcX - x0;

                int a = src[srcRow0 + x0] & 0xFF;
                int b = src[srcRow0 + x1] & 0xFF;
                int c = src[srcRow1 + x0] & 0xFF;
                int d = src[srcRow1 + x1] & 0xFF;

                float val = a * (1 - xDiff) * (1 - yDiff) +
                            b * xDiff * (1 - yDiff) +
                            c * (1 - xDiff) * yDiff +
                            d * xDiff * yDiff;

                dst[dstRow + x] = (byte) Math.min(255, Math.max(0, Math.round(val)));
            }
        }
        return dst;
    }

    /**
     * Computes the fraction of pixels in the face ROI with luminance above 245 (specular highlights / glare).
     */
    public static double specularHighlightRatio(FaceFrame frame, BoundingBox box) {
        byte[] gray = toGrayscale(frame);
        int w = frame.getWidth();
        int h = frame.getHeight();

        int x0 = box != null ? Math.max(0, box.pixelLeft(w)) : 0;
        int y0 = box != null ? Math.max(0, box.pixelTop(h)) : 0;
        int x1 = box != null ? Math.min(w, x0 + box.pixelWidth(w)) : w;
        int y1 = box != null ? Math.min(h, y0 + box.pixelHeight(h)) : h;

        int highlightCount = 0;
        int total = 0;

        for (int y = y0; y < y1; y++) {
            int rowOffset = y * w;
            for (int x = x0; x < x1; x++) {
                int luma = gray[rowOffset + x] & 0xFF;
                if (luma >= 245) {
                    highlightCount++;
                }
                total++;
            }
        }
        return total > 0 ? (double) highlightCount / total : 0.0;
    }

    /**
     * Computes per-channel statistics and color saturation over the face ROI.
     */
    public static ColorStats colorChannelStats(FaceFrame frame, BoundingBox box) {
        byte[] data = frame.getRawDataUnsafe();
        int w = frame.getWidth();
        int h = frame.getHeight();
        PixelFormat format = frame.getFormat();
        int bpp = format.getBytesPerPixel();

        int x0 = box != null ? Math.max(0, box.pixelLeft(w)) : 0;
        int y0 = box != null ? Math.max(0, box.pixelTop(h)) : 0;
        int x1 = box != null ? Math.min(w, x0 + box.pixelWidth(w)) : w;
        int y1 = box != null ? Math.min(h, y0 + box.pixelHeight(h)) : h;

        long rSum = 0;
        long gSum = 0;
        long bSum = 0;
        double satSum = 0.0;
        int count = 0;

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int offset = (y * w + x) * bpp;
                if (offset + (bpp > 1 ? 2 : 0) >= data.length) {
                    continue;
                }
                int r, g, b;
                if (format == PixelFormat.RGB_888 || format == PixelFormat.RGBA_8888) {
                    r = data[offset] & 0xFF;
                    g = data[offset + 1] & 0xFF;
                    b = data[offset + 2] & 0xFF;
                } else if (format == PixelFormat.BGR_888) {
                    b = data[offset] & 0xFF;
                    g = data[offset + 1] & 0xFF;
                    r = data[offset + 2] & 0xFF;
                } else {
                    r = g = b = data[offset] & 0xFF;
                }

                rSum += r;
                gSum += g;
                bSum += b;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                double sat = max > 0 ? (double) (max - min) / max : 0.0;
                satSum += sat;
                count++;
            }
        }

        if (count == 0) {
            return new ColorStats(128, 128, 128, 0, 0, 0, 0, 0);
        }

        double rMean = (double) rSum / count;
        double gMean = (double) gSum / count;
        double bMean = (double) bSum / count;
        double satMean = satSum / count;

        double rVar = 0.0, gVar = 0.0, bVar = 0.0, satVar = 0.0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int offset = (y * w + x) * bpp;
                if (offset + (bpp > 1 ? 2 : 0) >= data.length) continue;
                int r, g, b;
                if (format == PixelFormat.RGB_888 || format == PixelFormat.RGBA_8888) {
                    r = data[offset] & 0xFF;
                    g = data[offset + 1] & 0xFF;
                    b = data[offset + 2] & 0xFF;
                } else if (format == PixelFormat.BGR_888) {
                    b = data[offset] & 0xFF;
                    g = data[offset + 1] & 0xFF;
                    r = data[offset + 2] & 0xFF;
                } else {
                    r = g = b = data[offset] & 0xFF;
                }

                rVar += (r - rMean) * (r - rMean);
                gVar += (g - gMean) * (g - gMean);
                bVar += (b - bMean) * (b - bMean);

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                double sat = max > 0 ? (double) (max - min) / max : 0.0;
                satVar += (sat - satMean) * (sat - satMean);
            }
        }

        return new ColorStats(
                rMean, gMean, bMean,
                Math.sqrt(rVar / count), Math.sqrt(gVar / count), Math.sqrt(bVar / count),
                satMean, Math.sqrt(satVar / count)
        );
    }

    /**
     * Computes the high-frequency energy ratio on a 64x64 patch from the face ROI using 2D FFT.
     * High frequency energy ratio spikes in screen replays due to moire patterns and pixel grids.
     */
    public static double highFrequencyEnergyRatio(FaceFrame frame, BoundingBox box) {
        byte[] gray = toGrayscale(frame);
        int w = frame.getWidth();
        int h = frame.getHeight();

        BoundingBox faceBox = box != null ? box : BoundingBox.of(0.2f, 0.2f, 0.8f, 0.8f, 1.0f);
        int cropW = Math.max(1, faceBox.pixelWidth(w));
        int cropH = Math.max(1, faceBox.pixelHeight(h));
        // Extract native-resolution 64x64 patch without downscaling when face ROI is large enough
        final int N = 64;
        byte[] patch = new byte[N * N];
        if (cropW >= N && cropH >= N) {
            int cx = Math.max(0, faceBox.pixelLeft(w)) + cropW / 2;
            int cy = Math.max(0, faceBox.pixelTop(h)) + cropH / 2;
            int px0 = Math.max(0, Math.min(w - N, cx - N / 2));
            int py0 = Math.max(0, Math.min(h - N, cy - N / 2));
            for (int y = 0; y < N; y++) {
                System.arraycopy(gray, (py0 + y) * w + px0, patch, y * N, N);
            }
        } else {
            byte[] cropped = cropRegion(gray, w, h, faceBox);
            patch = downscale(cropped, cropW, cropH, N, N);
        }

        double[][] real = new double[N][N];
        double[][] imag = new double[N][N];

        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                real[y][x] = (patch[y * N + x] & 0xFF) / 255.0;
                imag[y][x] = 0.0;
            }
        }

        fft2D(real, imag, N);

        // Shift zero frequency to center and compute energy in low-frequency vs high-frequency rings
        double totalEnergy = 0.0;
        double highFreqEnergy = 0.0;
        int center = N / 2;
        int cutoffRadius = N / 6; // low-frequency cutoff radius (~10 pixels)

        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                // Shifted coordinates
                int sx = (x + center) % N - center;
                int sy = (y + center) % N - center;
                double dist = Math.sqrt(sx * sx + sy * sy);

                double magSq = real[y][x] * real[y][x] + imag[y][x] * imag[y][x];

                // Skip DC component (dist == 0)
                if (dist > 0.5) {
                    totalEnergy += magSq;
                    if (dist >= cutoffRadius) {
                        highFreqEnergy += magSq;
                    }
                }
            }
        }

        return totalEnergy > 0.00001 ? highFreqEnergy / totalEnergy : 0.0;
    }

    /**
     * 2D Fast Fourier Transform (Cooley-Tukey Radix-2).
     */
    private static void fft2D(double[][] real, double[][] imag, int n) {
        // Row-wise FFT
        double[] rRow = new double[n];
        double[] iRow = new double[n];
        for (int y = 0; y < n; y++) {
            System.arraycopy(real[y], 0, rRow, 0, n);
            System.arraycopy(imag[y], 0, iRow, 0, n);
            fft1D(rRow, iRow, n);
            System.arraycopy(rRow, 0, real[y], 0, n);
            System.arraycopy(iRow, 0, imag[y], 0, n);
        }

        // Column-wise FFT
        double[] rCol = new double[n];
        double[] iCol = new double[n];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                rCol[y] = real[y][x];
                iCol[y] = imag[y][x];
            }
            fft1D(rCol, iCol, n);
            for (int y = 0; y < n; y++) {
                real[y][x] = rCol[y];
                imag[y][x] = iCol[y];
            }
        }
    }

    /**
     * 1D Cooley-Tukey Radix-2 in-place FFT.
     */
    private static void fft1D(double[] real, double[] imag, int n) {
        // Bit-reversal permutation
        int j = 0;
        for (int i = 0; i < n - 1; i++) {
            if (i < j) {
                double tempR = real[i];
                real[i] = real[j];
                real[j] = tempR;

                double tempI = imag[i];
                imag[i] = imag[j];
                imag[j] = tempI;
            }
            int k = n >> 1;
            while (k <= j) {
                j -= k;
                k >>= 1;
            }
            j += k;
        }

        // Butterfly operations
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wStepR = Math.cos(angle);
            double wStepI = Math.sin(angle);

            for (int i = 0; i < n; i += len) {
                double wR = 1.0;
                double wI = 0.0;
                int half = len >> 1;

                for (int m = 0; m < half; m++) {
                    int uIdx = i + m;
                    int vIdx = i + m + half;

                    double uR = real[uIdx];
                    double uI = imag[uIdx];
                    double vR = real[vIdx] * wR - imag[vIdx] * wI;
                    double vI = real[vIdx] * wI + imag[vIdx] * wR;

                    real[uIdx] = uR + vR;
                    imag[uIdx] = uI + vI;
                    real[vIdx] = uR - vR;
                    imag[vIdx] = uI - vI;

                    double nextWR = wR * wStepR - wI * wStepI;
                    double nextWI = wR * wStepI + wI * wStepR;
                    wR = nextWR;
                    wI = nextWI;
                }
            }
        }
    }
}
