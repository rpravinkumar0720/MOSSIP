package io.mosip.liveness.api.quality;

/**
 * Normalized 3D coordinate point for facial landmarks including relative depth (Z).
 */
public record Point3D(float x, float y, float z) {

    public static Point3D of(float x, float y, float z) {
        return new Point3D(x, y, z);
    }

    public Point2D to2D() {
        return new Point2D(x, y);
    }

    public double distanceTo(Point3D other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        float dz = this.z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
