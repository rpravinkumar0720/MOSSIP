package io.mosip.liveness.api.policy;

/**
 * MOSIP operational workflows requiring face liveness and PAD verification.
 */
public enum WorkflowType {
    /** Resident demographic & biometric enrollment. Requires strictest ISO quality and liveness. */
    RESIDENT_ENROLLMENT("resident.enrollment"),

    /** Resident 1:1 or 1:N biometric authentication. High throughput with strict PAD. */
    RESIDENT_AUTH("resident.auth"),

    /** Registration client operator login authentication. */
    OPERATOR_AUTH("operator.auth"),

    /** Registration client supervisor authorization / exception override. */
    SUPERVISOR_AUTH("supervisor.auth");

    private final String configPrefix;

    WorkflowType(String configPrefix) {
        this.configPrefix = configPrefix;
    }

    public String getConfigPrefix() {
        return configPrefix;
    }
}
