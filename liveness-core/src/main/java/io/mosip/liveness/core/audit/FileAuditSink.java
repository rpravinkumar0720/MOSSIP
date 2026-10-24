package io.mosip.liveness.core.audit;

import io.mosip.liveness.api.audit.LivenessAuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only, flushed file audit sink that records audit records as JSON Lines.
 * Pure Java, strictly compliant with privacy mandates.
 */
public class FileAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(FileAuditSink.class);

    private final File auditFile;
    private final BufferedWriter writer;
    private final Map<String, List<LivenessAuditEvent>> sessionCache = new ConcurrentHashMap<>();

    public FileAuditSink(File auditFile) throws IOException {
        this.auditFile = Objects.requireNonNull(auditFile, "Audit file cannot be null");
        File parent = auditFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        this.writer = new BufferedWriter(new FileWriter(auditFile, StandardCharsets.UTF_8, true));
    }

    @Override
    public synchronized void append(LivenessAuditEvent event) {
        Objects.requireNonNull(event, "LivenessAuditEvent cannot be null");
        sessionCache.computeIfAbsent(event.sessionId(), k -> new CopyOnWriteArrayList<>()).add(event);

        try {
            String jsonLine = toJsonLine(event);
            writer.write(jsonLine);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to append audit record to file: {}", auditFile.getAbsolutePath(), e);
        }
    }

    @Override
    public List<LivenessAuditEvent> getEventsForSession(String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        List<LivenessAuditEvent> list = sessionCache.get(sessionId);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    @Override
    public synchronized void close() {
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            log.warn("Error closing audit log writer", e);
        }
    }

    public File getAuditFile() {
        return auditFile;
    }

    /**
     * Converts a LivenessAuditEvent to a single-line JSON string without third-party dependencies.
     */
    public static String toJsonLine(LivenessAuditEvent event) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{");
        appendKv(sb, "eventId", event.eventId(), true);
        appendKv(sb, "sessionId", event.sessionId(), true);
        sb.append("\"timestampMillis\":").append(event.timestampMillis()).append(",");
        appendKv(sb, "workflow", event.workflow() != null ? event.workflow().name() : null, true);
        appendKv(sb, "eventType", event.eventType() != null ? event.eventType().name() : null, true);
        appendKv(sb, "decision", event.decision() != null ? event.decision().name() : null, true);
        sb.append("\"passiveScore\":").append(event.passiveScore() != null ? String.format("%.4f", event.passiveScore()) : "null").append(",");
        appendKv(sb, "padClassification", event.padClassification() != null ? event.padClassification().name() : null, true);
        sb.append("\"padScore\":").append(event.padScore() != null ? String.format("%.4f", event.padScore()) : "null").append(",");
        appendKv(sb, "challengeType", event.challengeType() != null ? event.challengeType().name() : null, true);
        appendKv(sb, "errorCode", event.errorCode(), true);
        appendKv(sb, "modelVersion", event.modelVersion(), true);
        appendKv(sb, "deviceId", event.deviceId(), true);
        appendKv(sb, "previousHash", event.previousHash(), true);
        appendKv(sb, "currentHash", event.currentHash(), false);
        sb.append("}");
        return sb.toString();
    }

    private static void appendKv(StringBuilder sb, String key, String value, boolean trailingComma) {
        sb.append("\"").append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeJson(value)).append("\"");
        }
        if (trailingComma) {
            sb.append(",");
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
