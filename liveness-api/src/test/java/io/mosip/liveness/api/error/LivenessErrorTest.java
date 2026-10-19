package io.mosip.liveness.api.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessErrorTest {

    @Test
    @DisplayName("All LivenessError error codes and keys are unique and non-empty")
    void testUniqueErrorCodes() {
        Set<String> seenCodes = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();

        for (LivenessError error : LivenessError.values()) {
            assertThat(error.getErrorCode()).isNotBlank();
            assertThat(error.getUserMessageKey()).isNotBlank();
            assertThat(error.getDefaultUserMessage()).isNotBlank();
            assertThat(error.getDiagnosticDescription()).isNotBlank();

            assertThat(seenCodes.add(error.getErrorCode()))
                    .as("Duplicate error code found: " + error.getErrorCode())
                    .isTrue();

            assertThat(seenKeys.add(error.getUserMessageKey()))
                    .as("Duplicate message key found: " + error.getUserMessageKey())
                    .isTrue();
        }

        // Verify total number of error codes covers all specified requirements
        assertThat(LivenessError.values().length).isGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("PAD failure uses generic message to prevent adversarial information leakage")
    void testPadGenericMessage() {
        LivenessError padError = LivenessError.PAD_FAILURE;
        assertThat(padError.getDefaultUserMessage())
                .isEqualTo("Face verification could not be completed. Please try again.");
        assertThat(padError.getDiagnosticDescription()).contains("Presentation Attack");
        assertThat(padError.isRecoverable()).isFalse();
    }

    @Test
    @DisplayName("LivenessException preserves error code and diagnostics")
    void testLivenessException() {
        LivenessException ex = new LivenessException(LivenessError.CAMERA_UNAVAILABLE);
        assertThat(ex.getError()).isEqualTo(LivenessError.CAMERA_UNAVAILABLE);
        assertThat(ex.getErrorCode()).isEqualTo("LIV_ERR_003");
        assertThat(ex.getMessage()).contains("Video stream source could not be opened");
    }
}
