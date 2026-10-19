package io.mosip.liveness.api.quality;

/**
 * Normalized bounding box coordinates for a detected face.
 * All coordinates are normalized in the range [0.0, 1.0].
 */
public record BoundingBox(float left, float top, float right, float bottom, float confidence) {

    public BoundingBox {
        if (left < 0f || top < 0f || right < left || bottom < top) {
            throw new IllegalArgumentException(
                    String.format("Invalid BoundingBox: [%f, %f, %f, %f]", left, top, right, bottom));
        }
    }

    public static BoundingBox of(float left, float top, float right, float bottom, float confidence) {
        return new BoundingBox(left, top, right, bottom, confidence);
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    public float centerX() {
        return left + width() / 2.0f;
    }

    public float centerY() {
        return top + height() / 2.0f;
    }

    public float area() {
        return width() * height();
    }

    public int pixelLeft(int imageWidth) {
        return Math.round(left * imageWidth);
    }

    public int pixelTop(int imageHeight) {
        return Math.round(top * imageHeight);
    }

    public int pixelWidth(int imageWidth) {
        return Math.round(width() * imageWidth);
    }

    public int pixelHeight(int imageHeight) {
        return Math.round(height() * imageHeight);
    }
}
