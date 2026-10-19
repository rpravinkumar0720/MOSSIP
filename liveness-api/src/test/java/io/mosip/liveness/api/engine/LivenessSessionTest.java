package io.mosip.liveness.api.engine;

import io.mosip.liveness.api.challenge.Challenge;
import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessSessionTest {

    @Test
    @DisplayName("LivenessSession creates cryptographic nonce and tracks lifecycle")
    void testSessionLifecycle() {
        LivenessPolicy policy = LivenessPolicy.defaultResidentEnrollment();
        LivenessSession session = new LivenessSession("SESSION_100", WorkflowType.RESIDENT_ENROLLMENT, policy);

        assertThat(session.getSessionId()).isEqualTo("SESSION_100");
        assertThat(session.getWorkflow()).isEqualTo(WorkflowType.RESIDENT_ENROLLMENT);
        assertThat(session.getNonce()).isNotNull().hasSize(32); // 16 bytes = 32 hex chars
        assertThat(session.getRetryCount()).isEqualTo(0);
        assertThat(session.isClosed()).isFalse();

        // Increment retry
        int retries = session.incrementRetry();
        assertThat(retries).isEqualTo(1);
        assertThat(session.getRetryCount()).isEqualTo(1);

        // Challenge setting
        Challenge challenge = Challenge.create(ChallengeType.BLINK, session.getNonce(), 5000);
        session.setCurrentChallenge(challenge);
        assertThat(session.getCurrentChallenge()).isEqualTo(challenge);
        assertThat(challenge.promptText()).isEqualTo("Please blink");

        // Close session
        session.close();
        assertThat(session.isClosed()).isTrue();
        assertThat(session.getCurrentChallenge()).isNull();
    }
}
