package io.mosip.liveness.api.frame;

import java.util.function.Consumer;

/**
 * Functional stream source that supplies face frames asynchronously to subscribers.
 */
public interface FaceFrameSource extends AutoCloseable {

    /**
     * Subscribes a frame consumer to receive live frames as they arrive from the device.
     *
     * @param onFrame consumer invoked on the frame dispatcher thread for each captured frame
     */
    void subscribe(Consumer<FaceFrame> onFrame);

    /**
     * Unsubscribes the current frame consumer and halts frame dispatching.
     */
    void unsubscribe();

    /**
     * Checks if frames are currently actively streaming.
     *
     * @return true if streaming, false otherwise
     */
    boolean isStreaming();

    @Override
    default void close() {
        unsubscribe();
    }
}
