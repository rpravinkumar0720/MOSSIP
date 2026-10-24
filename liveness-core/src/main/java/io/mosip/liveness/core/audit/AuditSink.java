package io.mosip.liveness.core.audit;

import io.mosip.liveness.api.audit.LivenessAuditEvent;

import java.util.List;

/**
 * Sink abstraction for recording tamper-evident biometric audit events.
 */
public interface AuditSink extends AutoCloseable {

    /**
     * Appends an audit event to the tamper-evident sink.
     *
     * @param event the event to record
     */
    void append(LivenessAuditEvent event);

    /**
     * Retrieves all recorded audit events for a given session.
     */
    List<LivenessAuditEvent> getEventsForSession(String sessionId);

    @Override
    default void close() {
        // Default no-op
    }
}
