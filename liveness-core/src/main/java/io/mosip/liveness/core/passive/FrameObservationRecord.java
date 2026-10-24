package io.mosip.liveness.core.passive;

import io.mosip.liveness.core.analyzer.FaceObservation;

/**
 * Encapsulates face observation, arrival timestamp, and a compact pixel fingerprint.
 * Complies with strict in-memory wiping rules by not retaining raw pixel buffers across frames.
 */
public record FrameObservationRecord(
        long frameChecksum,
        FaceObservation observation,
        long arrivalNanos,
        long clientTimestampMs
) {
}
