package io.mosip.liveness.api.audit;

import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.classifier.PadClassification;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.policy.WorkflowType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Structured, tamper-evident audit record for biometric face verification events.
 * Implements cryptographic hash-chaining (SHA-256). Strictly excludes raw face images
 * or sensitive biometric templates in accordance with privacy regulations.
 */
public record LivenessAuditEvent(
        String eventId,
        String sessionId,
        long timestampMillis,
        WorkflowType workflow,
        AuditEventType eventType,
        DecisionType decision,
        Double passiveScore,
        PadClassification padClassification,
        Double padScore,
        ChallengeType challengeType,
        String errorCode,
        String modelVersion,
        String deviceId,
        Map<String, String> details,
        String previousHash,
        String currentHash
) {
    public LivenessAuditEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(workflow, "workflow cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
        details = details != null ? Map.copyOf(details) : Collections.emptyMap();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Computes the deterministic SHA-256 hash over the event payload concatenated with previousHash.
     */
    public static String computeHash(String previousHash,
                                     String eventId,
                                     String sessionId,
                                     long timestampMillis,
                                     WorkflowType workflow,
                                     AuditEventType eventType,
                                     DecisionType decision,
                                     Double passiveScore,
                                     PadClassification padClassification,
                                     Double padScore,
                                     ChallengeType challengeType,
                                     String errorCode,
                                     String modelVersion,
                                     String deviceId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonicalString = (previousHash != null ? previousHash : "GENESIS") + "|"
                    + eventId + "|"
                    + sessionId + "|"
                    + timestampMillis + "|"
                    + workflow + "|"
                    + eventType + "|"
                    + (decision != null ? decision.name() : "NULL") + "|"
                    + (passiveScore != null ? String.format("%.4f", passiveScore) : "NULL") + "|"
                    + (padClassification != null ? padClassification.name() : "NULL") + "|"
                    + (padScore != null ? String.format("%.4f", padScore) : "NULL") + "|"
                    + (challengeType != null ? challengeType.name() : "NULL") + "|"
                    + (errorCode != null ? errorCode : "NULL") + "|"
                    + (modelVersion != null ? modelVersion : "NULL") + "|"
                    + (deviceId != null ? deviceId : "NULL");

            byte[] hashBytes = digest.digest(canonicalString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest algorithm not available", e);
        }
    }

    public static class Builder {
        private String eventId = UUID.randomUUID().toString();
        private String sessionId = "UNKNOWN_SESSION";
        private long timestampMillis = System.currentTimeMillis();
        private WorkflowType workflow = WorkflowType.RESIDENT_ENROLLMENT;
        private AuditEventType eventType = AuditEventType.SESSION_START;
        private DecisionType decision = null;
        private Double passiveScore = null;
        private PadClassification padClassification = null;
        private Double padScore = null;
        private ChallengeType challengeType = null;
        private String errorCode = null;
        private String modelVersion = "1.0.0";
        private String deviceId = "UNKNOWN_DEVICE";
        private Map<String, String> details = Collections.emptyMap();
        private String previousHash = "0000000000000000000000000000000000000000000000000000000000000000";

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder timestampMillis(long timestampMillis) {
            this.timestampMillis = timestampMillis;
            return this;
        }

        public Builder workflow(WorkflowType workflow) {
            this.workflow = workflow;
            return this;
        }

        public Builder eventType(AuditEventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder decision(DecisionType decision) {
            this.decision = decision;
            return this;
        }

        public Builder passiveScore(Double passiveScore) {
            this.passiveScore = passiveScore;
            return this;
        }

        public Builder padClassification(PadClassification padClassification) {
            this.padClassification = padClassification;
            return this;
        }

        public Builder padScore(Double padScore) {
            this.padScore = padScore;
            return this;
        }

        public Builder challengeType(ChallengeType challengeType) {
            this.challengeType = challengeType;
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder modelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
            return this;
        }

        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder details(Map<String, String> details) {
            this.details = details;
            return this;
        }

        public Builder previousHash(String previousHash) {
            this.previousHash = previousHash;
            return this;
        }

        public LivenessAuditEvent build() {
            String currentHash = computeHash(previousHash, eventId, sessionId, timestampMillis,
                    workflow, eventType, decision, passiveScore, padClassification, padScore,
                    challengeType, errorCode, modelVersion, deviceId);

            return new LivenessAuditEvent(eventId, sessionId, timestampMillis, workflow,
                    eventType, decision, passiveScore, padClassification, padScore,
                    challengeType, errorCode, modelVersion, deviceId, details, previousHash, currentHash);
        }
    }
}
