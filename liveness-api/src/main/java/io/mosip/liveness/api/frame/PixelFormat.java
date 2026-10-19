package io.mosip.liveness.api.frame;

/**
 * Supported pixel and image data formats for biometric face frames.
 * Covers standard webcams, MDS MJPEG streams, Android camera formats, and raw buffers.
 */
public enum PixelFormat {
    /** Interleaved RGB 24-bit (3 bytes per pixel: R, G, B). */
    RGB_888(3, false),

    /** Interleaved BGR 24-bit (standard OpenCV / Windows camera output: B, G, R). */
    BGR_888(3, false),

    /** Interleaved RGBA 32-bit (4 bytes per pixel: R, G, B, A). */
    RGBA_8888(4, false),

    /** Android standard camera preview format (Y plane followed by interleaved VU). */
    NV21(1, true),

    /** Multi-planar YUV format used by Android Camera2 API. */
    YUV_420_888(1, true),

    /** Single channel 8-bit grayscale image buffer. */
    GRAY_8(1, false),

    /** Compressed JPEG byte stream (commonly emitted by MDS or USB webcams). */
    JPEG(0, false);

    private final int bytesPerPixel;
    private final boolean isPlanar;

    PixelFormat(int bytesPerPixel, boolean isPlanar) {
        this.bytesPerPixel = bytesPerPixel;
        this.isPlanar = isPlanar;
    }

    public int getBytesPerPixel() {
        return bytesPerPixel;
    }

    public boolean isPlanar() {
        return isPlanar;
    }
}
