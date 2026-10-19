package io.mosip.liveness.api.classifier;

/**
 * Presentation Attack Instrument (PAI) categories aligned with ISO/IEC 30107-1 and 30107-3.
 */
public enum PadClassification {
    /** Bona fide live human presentation. */
    GENUINE(false),

    /** 2D hardcopy photograph, photo cut-out, or glossy poster print. */
    PRINT_ATTACK(true),

    /** Digital video or still replay on mobile screen, tablet, or external monitor. */
    SCREEN_REPLAY(true),

    /** Virtual camera driver or programmatic video stream injection. */
    VIDEO_INJECTION(true),

    /** 3D latex, silicone, paper-mache, or resin wearable face mask. */
    MASK_3D(true),

    /** Deepfake synthetic video swap or anomalous presentation attack. */
    UNKNOWN_ATTACK(true);

    private final boolean isAttack;

    PadClassification(boolean isAttack) {
        this.isAttack = isAttack;
    }

    public boolean isAttack() {
        return isAttack;
    }
}
