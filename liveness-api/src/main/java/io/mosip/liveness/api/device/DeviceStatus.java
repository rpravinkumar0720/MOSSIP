package io.mosip.liveness.api.device;

/**
 * Operational states of a face biometric capture device.
 */
public enum DeviceStatus {
    /** Device is declared but not yet initialized or connected. */
    UNINITIALIZED,

    /** Device hardware is detected and communication link established. */
    CONNECTED,

    /** Device is actively capturing and streaming frames. */
    STREAMING,

    /** Frame streaming has been temporarily stopped, but device remains connected. */
    STOPPED,

    /** Physical device has been unplugged or communication session closed. */
    DISCONNECTED,

    /** Device encountered an unrecoverable hardware or communication error. */
    ERROR
}
