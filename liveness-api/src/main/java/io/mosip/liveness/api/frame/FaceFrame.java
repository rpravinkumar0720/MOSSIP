package io.mosip.liveness.api.frame;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable/secure representation of a single captured face frame from an L0/L1 biometric device or camera.
 * Complies with privacy requirements: supports memory wiping via {@link #wipe()} to clear raw biometric
 * pixel buffers after processing.
 */
public final class FaceFrame {

    private byte[] data;
    private final int width;
    private final int height;
    private final PixelFormat format;
    private final long timestampNanos;
    private final long sequenceNumber;
    private final String deviceId;
    private final int rotationDegrees;
    private volatile boolean wiped = false;

    /**
     * Constructs a FaceFrame with a defensive copy of the raw pixel data.
     */
    public FaceFrame(byte[] data,
                     int width,
                     int height,
                     PixelFormat format,
                     long timestampNanos,
                     long sequenceNumber,
                     String deviceId,
                     int rotationDegrees) {
        Objects.requireNonNull(data, "Frame data must not be null");
        Objects.requireNonNull(format, "PixelFormat must not be null");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Frame dimensions must be positive: " + width + "x" + height);
        }
        if (rotationDegrees % 90 != 0 || rotationDegrees < 0 || rotationDegrees >= 360) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees: " + rotationDegrees);
        }

        this.data = Arrays.copyOf(data, data.length);
        this.width = width;
        this.height = height;
        this.format = format;
        this.timestampNanos = timestampNanos;
        this.sequenceNumber = sequenceNumber;
        this.deviceId = deviceId != null ? deviceId : "UNKNOWN_DEVICE";
        this.rotationDegrees = rotationDegrees;
    }

    /**
     * Factory method wrapping existing data without copying for high-throughput pipelines.
     * The caller passes ownership of the buffer to this FaceFrame.
     */
    public static FaceFrame wrap(byte[] data,
                                 int width,
                                 int height,
                                 PixelFormat format,
                                 long timestampNanos,
                                 long sequenceNumber,
                                 String deviceId,
                                 int rotationDegrees) {
        Objects.requireNonNull(data, "Frame data must not be null");
        return new FaceFrame(data, width, height, format, timestampNanos, sequenceNumber, deviceId, rotationDegrees, false);
    }

    private FaceFrame(byte[] data,
                      int width,
                      int height,
                      PixelFormat format,
                      long timestampNanos,
                      long sequenceNumber,
                      String deviceId,
                      int rotationDegrees,
                      boolean copy) {
        this.data = copy ? Arrays.copyOf(data, data.length) : data;
        this.width = width;
        this.height = height;
        this.format = format;
        this.timestampNanos = timestampNanos;
        this.sequenceNumber = sequenceNumber;
        this.deviceId = deviceId != null ? deviceId : "UNKNOWN_DEVICE";
        this.rotationDegrees = rotationDegrees;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public PixelFormat getFormat() {
        return format;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public int getRotationDegrees() {
        return rotationDegrees;
    }

    public boolean isWiped() {
        return wiped;
    }

    /**
     * Returns a copy of the frame data, or throws IllegalStateException if the frame has been wiped.
     */
    public synchronized byte[] getDataCopy() {
        checkNotWiped();
        return Arrays.copyOf(data, data.length);
    }

    /**
     * Direct read-only access to the internal buffer for performance-critical analysis.
     * Must not be modified by caller.
     */
    public synchronized byte[] getRawDataUnsafe() {
        checkNotWiped();
        return data;
    }

    /**
     * Overwrites raw pixel memory with zeros for privacy and GDPR/biometric regulatory compliance.
     */
    public synchronized void wipe() {
        if (!wiped && data != null) {
            Arrays.fill(data, (byte) 0);
            wiped = true;
        }
    }

    private void checkNotWiped() {
        if (wiped) {
            throw new IllegalStateException("FaceFrame buffer has been wiped from memory for privacy compliance");
        }
    }

    @Override
    public String toString() {
        return "FaceFrame{" +
                "width=" + width +
                ", height=" + height +
                ", format=" + format +
                ", timestampNanos=" + timestampNanos +
                ", seq=" + sequenceNumber +
                ", deviceId='" + deviceId + '\'' +
                ", rotation=" + rotationDegrees +
                ", wiped=" + wiped +
                '}';
    }
}
