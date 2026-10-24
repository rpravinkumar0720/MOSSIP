package io.mosip.liveness.api.audit;

/**
 * Event categories logged during biometric face verification lifecycle.
 */
public enum AuditEventType {
    /** Liveness verification session initialized. */
    SESSION_START,

    /** ISO 19794-5 face quality checks evaluated on frame. */
    QUALITY_CHECK,

    /** Passive liveness and PAD inference completed. */
    PASSIVE_EVALUATED,

    /** Dynamic unpredictable active challenge issued to subject. */
    CHALLENGE_ISSUED,

    /** Active challenge completed and evaluated. */
    CHALLENGE_EVALUATED,

    /** Final orchestration decision produced (ACCEPTED, RETRY, REJECTED). */
    FINAL_DECISION,

    /** Presentation attack instrument detected. */
    PAD_ATTACK_DETECTED,

    /** Recoverable or unrecoverable error encountered. */
    ERROR_OCCURRED,

    /** Supervisor authorized override after max retries or exception. */
    SUPERVISOR_OVERRIDE,

    /** Client landmarks mismatched server-side face detection or pixel observations. */
    LANDMARK_MISMATCH
}
