package io.mosip.liveness.api.challenge;

/**
 * Types of dynamic, unpredictable active liveness challenges issued to residents/operators.
 */
public enum ChallengeType {
    /** Prompt resident to blink both eyes naturally. */
    BLINK("Please blink", 4000),

    /** Prompt resident to smile naturally (mouth widening / cheek raising). */
    SMILE("Please smile", 4000),

    /** Prompt resident to turn head to their left (yaw angle transition). */
    TURN_LEFT("Please turn your head to the left", 5000),

    /** Prompt resident to turn head to their right (yaw angle transition). */
    TURN_RIGHT("Please turn your head to the right", 5000),

    /** Prompt resident to look or tilt head upwards (pitch transition). */
    LOOK_UP("Please tilt your head up", 5000),

    /** Prompt resident to nod head downwards. */
    NOD_HEAD("Please nod your head", 5000),

    /** Photometric reflection challenge: Observe facial skin response to randomized screen color flashes. */
    COLOR_FLASH("Please watch the screen colors", 4500);

    private final String defaultPrompt;
    private final long defaultTimeoutMillis;

    ChallengeType(String defaultPrompt, long defaultTimeoutMillis) {
        this.defaultPrompt = defaultPrompt;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    public String getDefaultPrompt() {
        return defaultPrompt;
    }

    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }
}
