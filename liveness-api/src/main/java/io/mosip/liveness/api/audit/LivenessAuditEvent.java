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
import java.util.Locale;
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
        String currentHash,
        String signature
) {
    public LivenessAuditEvent(
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
        this(eventId, sessionId, timestampMillis, workflow, eventType, decision, passiveScore,
                padClassification, padScore, challengeType, errorCode, modelVersion, deviceId,
                details, previousHash, currentHash, null);
    }

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
     * Serializes details map into canonical, key-sorted JSON to ensure deterministic hashing.
     */
    public static String canonicalJsonDetails(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return "{}";
        }
        java.util.List<String> sortedKeys = new java.util.ArrayList<>(details.keySet());
        Collections.sort(sortedKeys);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < sortedKeys.size(); i++) {
            String k = sortedKeys.get(i);
            String v = details.get(k);
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(k)).append("\":\"").append(escapeJson(v != null ? v : "")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Computes the deterministic SHA-256 hash over the event payload including canonical details concatenated with previousHash.
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
                                     String deviceId,
                                     Map<String, String> details) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonicalString = (previousHash != null ? previousHash : "GENESIS") + "|"
                    + eventId + "|"
                    + sessionId + "|"
                    + timestampMillis + "|"
                    + workflow + "|"
                    + eventType + "|"
                    + (decision != null ? decision.name() : "NULL") + "|"
                    + (passiveScore != null ? String.format(Locale.ROOT, "%.4f", passiveScore) : "NULL") + "|"
                    + (padClassification != null ? padClassification.name() : "NULL") + "|"
                    + (padScore != null ? String.format(Locale.ROOT, "%.4f", padScore) : "NULL") + "|"
                    + (challengeType != null ? challengeType.name() : "NULL") + "|"
                    + (errorCode != null ? errorCode : "NULL") + "|"
                    + (modelVersion != null ? modelVersion : "NULL") + "|"
                    + (deviceId != null ? deviceId : "NULL") + "|"
                    + canonicalJsonDetails(details);

            byte[] hashBytes = digest.digest(canonicalString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest algorithm not available", e);
        }
    }

    /**
     * Backward-compatible computeHash overload omitting details.
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
        return computeHash(previousHash, eventId, sessionId, timestampMillis, workflow, eventType,
                decision, passiveScore, padClassification, padScore, challengeType, errorCode,
                modelVersion, deviceId, Collections.emptyMap());
    }

    /**
     * Computes HMAC-SHA256 signature over currentHash using the specified secret key.
     */
    public static String computeHmacSignature(String secretKey, String hash) {
        if (secretKey == null || secretKey.isBlank() || hash == null) {
            return null;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] sigBytes = mac.doFinal(hash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sigBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * Returns a copy of this event stamped with an HMAC-SHA256 signature over currentHash.
     */
    public LivenessAuditEvent withSignature(String secretKey) {
        String sig = computeHmacSignature(secretKey, this.currentHash);
        return new LivenessAuditEvent(eventId, sessionId, timestampMillis, workflow,
                eventType, decision, passiveScore, padClassification, padScore,
                challengeType, errorCode, modelVersion, deviceId, details, previousHash, currentHash, sig);
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
        private String signature = null;

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
            this.details = details != null ? details : Collections.emptyMap();
            return this;
        }

        public Builder previousHash(String previousHash) {
            this.previousHash = previousHash;
            return this;
        }

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        public LivenessAuditEvent build() {
            String currentHash = computeHash(previousHash, eventId, sessionId, timestampMillis,
                    workflow, eventType, decision, passiveScore, padClassification, padScore,
                    challengeType, errorCode, modelVersion, deviceId, details);

            return new LivenessAuditEvent(eventId, sessionId, timestampMillis, workflow,
                    eventType, decision, passiveScore, padClassification, padScore,
                    challengeType, errorCode, modelVersion, deviceId, details, previousHash, currentHash, signature);
        }
    }
}

