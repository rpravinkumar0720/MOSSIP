package io.mosip.liveness.api.policy;

/**
 * Action taken when max retries are exceeded or an unrecoverable presentation attack occurs.
 */
public enum FailureBehaviour {
    /** Immediately block capture/authentication session and reject transaction. */
    BLOCK,

    /** Allow authorized supervisor to override with biometric authorization and audit note. */
    FALLBACK_SUPERVISOR_OVERRIDE,

    /** Temporarily lock out the workstation or operator account due to repeated attack detection. */
    LOCKOUT
}
