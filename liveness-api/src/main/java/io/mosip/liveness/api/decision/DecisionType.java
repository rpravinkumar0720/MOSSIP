package io.mosip.liveness.api.decision;

/**
 * High-level orchestration decision for biometric face verification.
 */
public enum DecisionType {
    /** Live genuine human face confirmed. Safe to capture and store/authenticate. */
    ACCEPTED,

    /** Passive check was inconclusive or low-confidence; triggering dynamic active challenge. */
    CHALLENGE_REQUIRED,

    /** Presentation attack definitively detected or active challenge failed. */
    REJECTED,

    /** Recoverable quality or positioning issue; user prompted to retry. */
    RETRY,

    /** User exceeded maximum configured retries. */
    FAILED_MAX_RETRIES,

    /** Hardware failure, camera loss, or pipeline execution error. */
    ERROR
}
