package io.mosip.liveness.api.device;

/**
 * Callback listener notified of state transitions and hardware events from capture devices.
 */
@FunctionalInterface
public interface DeviceStatusListener {

    /**
     * Invoked when a device transitions between operational statuses.
     *
     * @param device the source capture device
     * @param oldStatus previous state
     * @param newStatus updated state
     * @param message human/diagnostic description of the transition or error
     */
    void onStatusChanged(FaceCaptureDevice device, DeviceStatus oldStatus, DeviceStatus newStatus, String message);
}
