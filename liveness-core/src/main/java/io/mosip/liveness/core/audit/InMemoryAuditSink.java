package io.mosip.liveness.core.audit;

import io.mosip.liveness.api.audit.LivenessAuditEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory audit sink with bounded capacity and per-session indexing.
 */
public class InMemoryAuditSink implements AuditSink {

    private final int maxCapacity;
    private final List<LivenessAuditEvent> allEvents = new CopyOnWriteArrayList<>();
    private final Map<String, List<LivenessAuditEvent>> sessionEvents = new ConcurrentHashMap<>();

    public InMemoryAuditSink() {
        this(10_000);
    }

    public InMemoryAuditSink(int maxCapacity) {
        this.maxCapacity = Math.max(100, maxCapacity);
    }

    @Override
    public synchronized void append(LivenessAuditEvent event) {
        Objects.requireNonNull(event, "LivenessAuditEvent cannot be null");

        if (allEvents.size() >= maxCapacity) {
            LivenessAuditEvent removed = allEvents.remove(0);
            List<LivenessAuditEvent> sessionList = sessionEvents.get(removed.sessionId());
            if (sessionList != null) {
                sessionList.remove(removed);
            }
        }

        allEvents.add(event);
        sessionEvents.computeIfAbsent(event.sessionId(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public List<LivenessAuditEvent> getEventsForSession(String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        List<LivenessAuditEvent> list = sessionEvents.get(sessionId);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    public List<LivenessAuditEvent> getAllEvents() {
        return new ArrayList<>(allEvents);
    }

    public synchronized void clear() {
        allEvents.clear();
        sessionEvents.clear();
    }
}
