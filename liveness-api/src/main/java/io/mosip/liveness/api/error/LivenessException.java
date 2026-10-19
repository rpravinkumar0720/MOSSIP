package io.mosip.liveness.api.error;

import java.util.Objects;

/**
 * Domain exception for liveness and PAD processing failures.
 */
public class LivenessException extends RuntimeException {

    private final LivenessError error;

    public LivenessException(LivenessError error) {
        super(Objects.requireNonNull(error, "error cannot be null").getDiagnosticDescription());
        this.error = error;
    }

    public LivenessException(LivenessError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error cannot be null");
    }

    public LivenessException(LivenessError error, Throwable cause) {
        super(Objects.requireNonNull(error, "error cannot be null").getDiagnosticDescription(), cause);
        this.error = error;
    }

    public LivenessException(LivenessError error, String message, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error cannot be null");
    }

    public LivenessError getError() {
        return error;
    }

    public String getErrorCode() {
        return error.getErrorCode();
    }
}
