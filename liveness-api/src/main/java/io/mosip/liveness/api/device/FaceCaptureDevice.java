package io.mosip.liveness.api.device;

import io.mosip.liveness.api.frame.FaceFrame;

import java.util.function.Consumer;

/**
 * Universal abstraction for biometric face capture devices.
 * Implemented by MDS (MOSIP Device Service) adapters, mock devices, and hardware webcams.
 * Follows the adapter pattern to ensure vendor independence.
 */
public interface FaceCaptureDevice extends AutoCloseable {

    /**
     * Initializes hardware/network communication with the capture device.
     *
     * @throws io.mosip.liveness.api.error.LivenessException if connection fails
     */
    void connect();

    /**
     * Gracefully halts streaming and closes connection to device.
     */
    void disconnect();

    /**
     * Starts continuous frame streaming at the negotiated resolution and frame rate.
     *
     * @param frameConsumer consumer called for each received frame
     */
    void startStream(Consumer<FaceFrame> frameConsumer);

    /**
     * Stops continuous frame streaming without disconnecting hardware.
     */
    void stopStream();

    /**
     * Captures a single pristine biometric still frame (ISO 19794-5 compliant)
     * typically after liveness verification succeeds.
     *
     * @return captured still FaceFrame
     */
    FaceFrame captureStill();

    /**
     * Returns hardware capabilities and security level (L0/L1) for this device.
     */
    DeviceCapabilities getCapabilities();

    /**
     * Returns current operational status of the device.
     */
    DeviceStatus getStatus();

    /**
     * Registers a listener for device lifecycle events and disconnect alerts.
     */
    void addStatusListener(DeviceStatusListener listener);

    /**
     * Unregisters a status listener.
     */
    void removeStatusListener(DeviceStatusListener listener);

    @Override
    default void close() {
        disconnect();
    }
}
