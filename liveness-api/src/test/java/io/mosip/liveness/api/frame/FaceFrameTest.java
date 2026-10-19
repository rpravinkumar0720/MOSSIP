package io.mosip.liveness.api.frame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaceFrameTest {

    @Test
    @DisplayName("FaceFrame creates defensive copy and preserves metadata")
    void testCreationDefensiveCopy() {
        byte[] original = new byte[]{1, 2, 3, 4, 5, 6};
        FaceFrame frame = new FaceFrame(original, 640, 480, PixelFormat.RGB_888, 1000L, 1L, "DEV_01", 0);

        assertThat(frame.getWidth()).isEqualTo(640);
        assertThat(frame.getHeight()).isEqualTo(480);
        assertThat(frame.getFormat()).isEqualTo(PixelFormat.RGB_888);
        assertThat(frame.getTimestampNanos()).isEqualTo(1000L);
        assertThat(frame.getSequenceNumber()).isEqualTo(1L);
        assertThat(frame.getDeviceId()).isEqualTo("DEV_01");
        assertThat(frame.getRotationDegrees()).isEqualTo(0);
        assertThat(frame.isWiped()).isFalse();

        // Mutating original array should NOT affect frame
        original[0] = 99;
        assertThat(frame.getDataCopy()[0]).isEqualTo((byte) 1);
    }

    @Test
    @DisplayName("FaceFrame wrap allows zero-copy initialization")
    void testWrap() {
        byte[] buffer = new byte[]{10, 20, 30};
        FaceFrame frame = FaceFrame.wrap(buffer, 320, 240, PixelFormat.NV21, 500L, 2L, "DEV_02", 90);

        assertThat(frame.getRotationDegrees()).isEqualTo(90);
        assertThat(frame.getRawDataUnsafe()).isSameAs(buffer);
    }

    @Test
    @DisplayName("Invalid dimensions or rotation throw IllegalArgumentException")
    void testInvalidDimensions() {
        byte[] buf = new byte[]{1, 2, 3};

        assertThatThrownBy(() -> new FaceFrame(buf, -1, 480, PixelFormat.RGB_888, 0, 0, null, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new FaceFrame(buf, 640, 0, PixelFormat.RGB_888, 0, 0, null, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new FaceFrame(buf, 640, 480, PixelFormat.RGB_888, 0, 0, null, 45))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rotation must be 0, 90, 180, or 270");
    }

    @Test
    @DisplayName("wipe() zeroes memory and prevents subsequent access for GDPR/biometric privacy")
    void testWipeMemoryCompliance() {
        byte[] buffer = new byte[]{11, 22, 33, 44};
        FaceFrame frame = FaceFrame.wrap(buffer, 2, 2, PixelFormat.GRAY_8, 100L, 1L, "DEV", 0);

        assertThat(frame.isWiped()).isFalse();
        frame.wipe();

        assertThat(frame.isWiped()).isTrue();
        // Memory should be zeroed out
        assertThat(buffer).containsOnly((byte) 0);

        // Attempting to read data after wipe must throw
        assertThatThrownBy(frame::getDataCopy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wiped from memory");

        assertThatThrownBy(frame::getRawDataUnsafe)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wiped from memory");
    }
}
