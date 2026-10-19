package io.mosip.liveness.api.quality;

/**
 * Normalized 2D coordinate point for facial landmarks.
 */
public record Point2D(float x, float y) {

    public static Point2D of(float x, float y) {
        return new Point2D(x, y);
    }

    public double distanceTo(Point2D other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
