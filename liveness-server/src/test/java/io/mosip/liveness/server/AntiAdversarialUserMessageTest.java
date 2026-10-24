package io.mosip.liveness.server;

import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.error.LivenessError;
import io.mosip.liveness.api.quality.QualityFailureReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AntiAdversarialUserMessageTest {

    private static final List<String> FORBIDDEN_WORDS = List.of(
            "print", "screen", "replay", "mask", "photo", "injection"
    );

    @Test
    @DisplayName("PAD attack outcome must produce strictly generic user message")
    void padFailureMustBeGeneric() {
        String msgEn = LocalizationService.getDecisionMessage(DecisionType.REJECTED, "en");
        assertThat(msgEn).isEqualTo("Face verification could not be completed. Please try again.");

        String defaultErr = LivenessError.PAD_FAILURE.getDefaultUserMessage();
        assertThat(defaultErr).isEqualTo("Face verification could not be completed. Please try again.");
    }

    @Test
    @DisplayName("No user-facing decision or quality guidance message in normal mode contains forbidden PAI words")
    void noForbiddenAttackWordsInUserFacingMessages() {
        // Check all decision messages
        for (DecisionType type : DecisionType.values()) {
            String msg = LocalizationService.getDecisionMessage(type, "en").toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_WORDS) {
                assertThat(msg)
                        .as("Decision message for %s must not contain '%s'", type, forbidden)
                        .doesNotContain(forbidden);
            }
        }

        // Check all quality failure guidance messages
        for (QualityFailureReason reason : QualityFailureReason.values()) {
            String guidance = LocalizationService.getQualityGuidance(reason, "en").toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_WORDS) {
                assertThat(guidance)
                        .as("Quality guidance for %s must not contain '%s'", reason, forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    @DisplayName("Tamil messages must be non-empty and also not contain English attack words")
    void tamilMessagesValid() {
        for (DecisionType type : DecisionType.values()) {
            String msg = LocalizationService.getDecisionMessage(type, "ta");
            assertThat(msg).isNotBlank();
            for (String forbidden : FORBIDDEN_WORDS) {
                assertThat(msg.toLowerCase(Locale.ROOT)).doesNotContain(forbidden);
            }
        }
    }
}
