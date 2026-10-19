package io.mosip.liveness.api.quality;

/**
 * Quality check failures specified by ISO/IEC 19794-5 (Biometric data interchange formats - Face image data).
 */
public enum QualityFailureReason {
    /** Face is insufficiently lit (average luma below minimum threshold). */
    TOO_DARK("Face lighting is too dark. Please ensure better front lighting."),

    /** Overexposed or washed out due to excessive backlight or flash. */
    TOO_BRIGHT("Face is overexposed. Please avoid harsh direct lighting or backlight."),

    /** Frame lacks high-frequency sharpness (low Laplacian variance / camera motion blur). */
    BLURRY("Image is blurry. Please hold still."),

    /** Face occupies too small a proportion of the frame (resident is too far). */
    FACE_TOO_SMALL("Move closer to the camera."),

    /** Face is cropped or too close to camera lens. */
    FACE_TOO_CLOSE("Move slightly back from the camera."),

    /** Face horizontal rotation (yaw) exceeds ISO 19794-5 tolerance. */
    POSE_YAW_EXCEEDED("Look straight into the camera."),

    /** Face vertical tilt (pitch) exceeds ISO 19794-5 tolerance. */
    POSE_PITCH_EXCEEDED("Keep your head level with the camera."),

    /** Head tilt (roll) exceeds tolerance. */
    POSE_ROLL_EXCEEDED("Keep your head upright."),

    /** Eyes are closed or obscured during capture. */
    EYES_CLOSED("Please keep your eyes open."),

    /** Mouth is open beyond standard neutral facial expression. */
    MOUTH_OPEN("Please keep a neutral facial expression with mouth closed."),

    /** No human face detected in frame. */
    NO_FACE_DETECTED("No face detected. Please look directly at the camera."),

    /** Multiple human faces detected simultaneously in the capture frame. */
    MULTIPLE_FACES_DETECTED("Multiple faces detected. Only one person should be in the frame.");

    private final String guidanceMessage;

    QualityFailureReason(String guidanceMessage) {
        this.guidanceMessage = guidanceMessage;
    }

    public String getGuidanceMessage() {
        return guidanceMessage;
    }
}
