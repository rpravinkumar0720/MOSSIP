package io.mosip.liveness.api.device;

/**
 * MOSIP Device Specification Security Levels (L0 / L1).
 * L0: Basic software-authenticated biometric device.
 * L1: Hardware-secured biometric device with on-chip crypto signing, secure element, and tamper detection.
 */
public enum SecurityLevel {
    /** Basic biometric device, software level security. */
    L0,

    /** Hardware-secured device with on-device cryptographic key storage and signing. */
    L1
}
