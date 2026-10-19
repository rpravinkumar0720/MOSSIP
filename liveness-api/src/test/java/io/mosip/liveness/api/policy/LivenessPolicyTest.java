package io.mosip.liveness.api.policy;

import io.mosip.liveness.api.challenge.ChallengeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LivenessPolicyTest {

    @Test
    @DisplayName("Default presets for Resident, Operator, and Supervisor workflows are well-formed")
    void testPresets() {
        LivenessPolicy residentPolicy = LivenessPolicy.defaultResidentEnrollment();
        assertThat(residentPolicy.enabled()).isTrue();
        assertThat(residentPolicy.passiveThreshold()).isEqualTo(0.85);
        assertThat(residentPolicy.activeEnabled()).isTrue();
        assertThat(residentPolicy.failureBehaviour()).isEqualTo(FailureBehaviour.FALLBACK_SUPERVISOR_OVERRIDE);
        assertThat(residentPolicy.allowedChallengeTypes()).contains(ChallengeType.BLINK, ChallengeType.SMILE);

        LivenessPolicy operatorPolicy = LivenessPolicy.defaultOperatorAuth();
        assertThat(operatorPolicy.failureBehaviour()).isEqualTo(FailureBehaviour.LOCKOUT);
        assertThat(operatorPolicy.passiveThreshold()).isGreaterThan(residentPolicy.passiveThreshold());

        LivenessPolicy supervisorPolicy = LivenessPolicy.defaultSupervisorAuth();
        assertThat(supervisorPolicy.failureBehaviour()).isEqualTo(FailureBehaviour.BLOCK);
        assertThat(supervisorPolicy.maxRetries()).isEqualTo(2);
    }

    @Test
    @DisplayName("Invalid threshold ranges throw IllegalArgumentException")
    void testThresholdValidation() {
        assertThatThrownBy(() -> LivenessPolicy.builder().passiveThreshold(1.5).build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LivenessPolicy.builder().passiveThreshold(-0.1).build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LivenessPolicy.builder().padThreshold(1.1).build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LivenessPolicy.builder().passiveWindowFrames(0).build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LivenessPolicy.builder().maxRetries(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Custom builder accurately captures all configuration parameters")
    void testCustomBuilder() {
        LivenessPolicy custom = LivenessPolicy.builder()
                .enabled(true)
                .passiveThreshold(0.92)
                .passiveWindowFrames(7)
                .activeEnabled(false)
                .minChallenges(2)
                .allowedChallengeTypes(Set.of(ChallengeType.BLINK))
                .activeTimeoutSeconds(15)
                .maxRetries(5)
                .failureBehaviour(FailureBehaviour.BLOCK)
                .padThreshold(0.80)
                .diagnosticMode(true)
                .modelVersion("2.1.0-alpha")
                .metricsEnabled(true)
                .build();

        assertThat(custom.passiveThreshold()).isEqualTo(0.92);
        assertThat(custom.passiveWindowFrames()).isEqualTo(7);
        assertThat(custom.activeEnabled()).isFalse();
        assertThat(custom.diagnosticMode()).isTrue();
        assertThat(custom.modelVersion()).isEqualTo("2.1.0-alpha");
    }
}
