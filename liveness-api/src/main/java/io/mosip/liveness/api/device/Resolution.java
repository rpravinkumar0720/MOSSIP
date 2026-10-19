package io.mosip.liveness.api.device;

import java.util.Objects;

/**
 * Image or video frame dimension specification.
 */
public record Resolution(int width, int height) implements Comparable<Resolution> {

    public Resolution {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Width and height must be positive: " + width + "x" + height);
        }
    }

    public static final Resolution VGA_640_480 = new Resolution(640, 480);
    public static final Resolution HD_1280_720 = new Resolution(1280, 720);
    public static final Resolution FHD_1920_1080 = new Resolution(1920, 1080);

    public int totalPixels() {
        return width * height;
    }

    public double aspectRatio() {
        return (double) width / (double) height;
    }

    @Override
    public int compareTo(Resolution other) {
        Objects.requireNonNull(other);
        return Integer.compare(this.totalPixels(), other.totalPixels());
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
