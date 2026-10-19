package io.mosip.liveness.api.error;

/**
 * Standard error codes and localized message mapping for MOSIP Face Liveness and PAD.
 * Designed to prevent adversarial information leakage: PAD failures and attack detections
 * map to generic user messages while recording exact diagnostics in the audit log.
 */
public enum LivenessError {

    DEVICE_UNAVAILABLE(
            "LIV_ERR_001",
            "liveness.error.device_unavailable",
            "Biometric device is unavailable. Please check the connection.",
            "The requested capture device is not plugged in, claimed by another process, or not found.",
            true
    ),

    DEVICE_CONNECTION_FAILURE(
            "LIV_ERR_002",
            "liveness.error.device_connection_failure",
            "Failed to connect to the biometric device. Please reconnect and try again.",
            "Handshake or communication timeout with capture device adapter.",
            true
    ),

    CAMERA_UNAVAILABLE(
            "LIV_ERR_003",
            "liveness.error.camera_unavailable",
            "Camera stream is unavailable or blocked by another application.",
            "Video stream source could not be opened or access was denied by OS permissions.",
            true
    ),

    FACE_NOT_DETECTED(
            "LIV_ERR_004",
            "liveness.error.face_not_detected",
            "No face detected. Please look directly at the camera.",
            "Face detection model returned 0 candidate bounding boxes.",
            true
    ),

    MULTIPLE_FACES(
            "LIV_ERR_005",
            "liveness.error.multiple_faces",
            "Multiple faces detected. Ensure only the applicant is in view.",
            "Face detector identified two or more faces with confidence exceeding threshold.",
            true
    ),

    POOR_FACE_QUALITY(
            "LIV_ERR_006",
            "liveness.error.poor_face_quality",
            "Image quality is insufficient. Please adjust lighting and hold still.",
            "Frame failed ISO 19794-5 quality gates (blur, illumination, or pose angle).",
            true
    ),

    LIVENESS_BELOW_THRESHOLD(
            "LIV_ERR_007",
            "liveness.error.liveness_below_threshold",
            "We could not verify face liveness. Please try again.",
            "Passive liveness confidence score fell below policy threshold.",
            true
    ),

    PAD_FAILURE(
            "LIV_ERR_008",
            "liveness.error.generic_failure",
            "Face verification could not be completed. Please try again.",
            "Presentation Attack Detection (PAD) classified frame as attack (print, screen, replay, mask).",
            false
    ),

    ACTIVE_CHALLENGE_FAILURE(
            "LIV_ERR_009",
            "liveness.error.challenge_failure",
            "Required action was not completed. Please follow the on-screen instructions.",
            "Action detector did not observe the required facial transition before timeout.",
            true
    ),

    CHALLENGE_TIMEOUT(
            "LIV_ERR_010",
            "liveness.error.challenge_timeout",
            "Verification timed out. Please try again.",
            "Active challenge window expired without action completion.",
            true
    ),

    MAX_RETRIES_EXCEEDED(
            "LIV_ERR_011",
            "liveness.error.max_retries_exceeded",
            "Maximum verification attempts exceeded. Please seek supervisor assistance.",
            "Session retry count exceeded liveness.max.retries policy.",
            false
    ),

    INVALID_FRAME_DATA(
            "LIV_ERR_012",
            "liveness.error.invalid_frame",
            "Corrupted image data received from device.",
            "Frame buffer is null, zero-length, or has mismatched dimension bytes.",
            true
    ),

    ENGINE_ERROR(
            "LIV_ERR_013",
            "liveness.error.engine_error",
            "An internal verification error occurred. Please contact system support.",
            "Unhandled exception or native ONNX inference failure in liveness engine.",
            false
    ),

    IDENTITY_MISMATCH(
            "LIV_ERR_014",
            "liveness.error.identity_mismatch",
            "Face identity changed during verification. Please remain still.",
            "Face embedding or landmark geometry shifted abruptly during active challenge (face swap attempt).",
            false
    ),

    DEVICE_DISCONNECTED_MIDSTREAM(
            "LIV_ERR_015",
            "liveness.error.device_disconnected",
            "Biometric device was disconnected during capture. Please reconnect.",
            "Physical USB disconnect or socket dropped during active frame streaming.",
            true
    );

    private final String errorCode;
    private final String userMessageKey;
    private final String defaultUserMessage;
    private final String diagnosticDescription;
    private final boolean recoverable;

    LivenessError(String errorCode,
                  String userMessageKey,
                  String defaultUserMessage,
                  String diagnosticDescription,
                  boolean recoverable) {
        this.errorCode = errorCode;
        this.userMessageKey = userMessageKey;
        this.defaultUserMessage = defaultUserMessage;
        this.diagnosticDescription = diagnosticDescription;
        this.recoverable = recoverable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getUserMessageKey() {
        return userMessageKey;
    }

    public String getDefaultUserMessage() {
        return defaultUserMessage;
    }

    public String getDiagnosticDescription() {
        return diagnosticDescription;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
