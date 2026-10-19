package io.mosip.liveness.api.policy;

import io.mosip.liveness.api.challenge.ChallengeType;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration policy for liveness and presentation attack detection.
 * Fully configurable per workflow (resident, operator, supervisor).
 */
public record LivenessPolicy(
        boolean enabled,
        double passiveThreshold,
        int passiveWindowFrames,
        boolean activeEnabled,
        int minChallenges,
        Set<ChallengeType> allowedChallengeTypes,
        int activeTimeoutSeconds,
        int maxRetries,
        FailureBehaviour failureBehaviour,
        double padThreshold,
        boolean diagnosticMode,
        String modelVersion,
        boolean metricsEnabled
) {
    public LivenessPolicy {
        Objects.requireNonNull(failureBehaviour, "failureBehaviour cannot be null");
        Objects.requireNonNull(modelVersion, "modelVersion cannot be null");
        allowedChallengeTypes = allowedChallengeTypes != null ? Set.copyOf(allowedChallengeTypes) : Collections.emptySet();

        if (passiveThreshold < 0.0 || passiveThreshold > 1.0) {
            throw new IllegalArgumentException("passiveThreshold must be between 0.0 and 1.0: " + passiveThreshold);
        }
        if (padThreshold < 0.0 || padThreshold > 1.0) {
            throw new IllegalArgumentException("padThreshold must be between 0.0 and 1.0: " + padThreshold);
        }
        if (passiveWindowFrames <= 0) {
            throw new IllegalArgumentException("passiveWindowFrames must be positive: " + passiveWindowFrames);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative: " + maxRetries);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Default policy for Resident Enrollment (strictest standards, active fallback enabled).
     */
    public static LivenessPolicy defaultResidentEnrollment() {
        return builder()
                .passiveThreshold(0.85)
                .passiveWindowFrames(5)
                .activeEnabled(true)
                .minChallenges(1)
                .allowedChallengeTypes(Set.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT))
                .activeTimeoutSeconds(8)
                .maxRetries(3)
                .failureBehaviour(FailureBehaviour.FALLBACK_SUPERVISOR_OVERRIDE)
                .padThreshold(0.70)
                .build();
    }

    /**
     * Default policy for Operator Login Authentication (rapid evaluation, strict lockout).
     */
    public static LivenessPolicy defaultOperatorAuth() {
        return builder()
                .passiveThreshold(0.88)
                .passiveWindowFrames(4)
                .activeEnabled(true)
                .minChallenges(1)
                .allowedChallengeTypes(Set.of(ChallengeType.BLINK, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT))
                .activeTimeoutSeconds(6)
                .maxRetries(3)
                .failureBehaviour(FailureBehaviour.LOCKOUT)
                .padThreshold(0.75)
                .build();
    }

    /**
     * Default policy for Supervisor Authorization.
     */
    public static LivenessPolicy defaultSupervisorAuth() {
        return builder()
                .passiveThreshold(0.90)
                .passiveWindowFrames(4)
                .activeEnabled(true)
                .minChallenges(1)
                .allowedChallengeTypes(Set.of(ChallengeType.BLINK, ChallengeType.SMILE))
                .activeTimeoutSeconds(6)
                .maxRetries(2)
                .failureBehaviour(FailureBehaviour.BLOCK)
                .padThreshold(0.80)
                .build();
    }

    public static class Builder {
        private boolean enabled = true;
        private double passiveThreshold = 0.85;
        private int passiveWindowFrames = 5;
        private boolean activeEnabled = true;
        private int minChallenges = 1;
        private Set<ChallengeType> allowedChallengeTypes = Set.of(
                ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT
        );
        private int activeTimeoutSeconds = 8;
        private int maxRetries = 3;
        private FailureBehaviour failureBehaviour = FailureBehaviour.FALLBACK_SUPERVISOR_OVERRIDE;
        private double padThreshold = 0.70;
        private boolean diagnosticMode = false;
        private String modelVersion = "1.0.0";
        private boolean metricsEnabled = true;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder passiveThreshold(double passiveThreshold) {
            this.passiveThreshold = passiveThreshold;
            return this;
        }

        public Builder passiveWindowFrames(int passiveWindowFrames) {
            this.passiveWindowFrames = passiveWindowFrames;
            return this;
        }

        public Builder activeEnabled(boolean activeEnabled) {
            this.activeEnabled = activeEnabled;
            return this;
        }

        public Builder minChallenges(int minChallenges) {
            this.minChallenges = minChallenges;
            return this;
        }

        public Builder allowedChallengeTypes(Set<ChallengeType> allowedChallengeTypes) {
            this.allowedChallengeTypes = allowedChallengeTypes;
            return this;
        }

        public Builder activeTimeoutSeconds(int activeTimeoutSeconds) {
            this.activeTimeoutSeconds = activeTimeoutSeconds;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder failureBehaviour(FailureBehaviour failureBehaviour) {
            this.failureBehaviour = failureBehaviour;
            return this;
        }

        public Builder padThreshold(double padThreshold) {
            this.padThreshold = padThreshold;
            return this;
        }

        public Builder diagnosticMode(boolean diagnosticMode) {
            this.diagnosticMode = diagnosticMode;
            return this;
        }

        public Builder modelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
            return this;
        }

        public Builder metricsEnabled(boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
            return this;
        }

        public LivenessPolicy build() {
            return new LivenessPolicy(enabled, passiveThreshold, passiveWindowFrames,
                    activeEnabled, minChallenges, allowedChallengeTypes, activeTimeoutSeconds,
                    maxRetries, failureBehaviour, padThreshold, diagnosticMode, modelVersion, metricsEnabled);
        }
    }
}
