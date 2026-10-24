package io.mosip.liveness.core.audit;

import io.mosip.liveness.api.audit.AuditEventType;
import io.mosip.liveness.api.audit.LivenessAuditEvent;
import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.classifier.PadClassification;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.policy.WorkflowType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Standalone Command Line Tool for verifying cryptographic integrity and authenticity of MOSIP
 * audit trails (audit.jsonl). Pure Java 17 with zero external runtime dependencies.
 *
 * <p>Usage:
 * <pre>
 *   java -cp liveness-core.jar:liveness-api.jar io.mosip.liveness.core.audit.AuditVerifierCli audit.jsonl [--key HMAC_SECRET]
 * </pre>
 */
public class AuditVerifierCli {

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    public static int run(String[] args) {
        if (args.length == 0 || "--help".equalsIgnoreCase(args[0]) || "-h".equalsIgnoreCase(args[0])) {
            printUsage();
            return args.length == 0 ? 1 : 0;
        }

        String filePath = args[0];
        String hmacKey = System.getenv("LIVENESS_AUDIT_HMAC_KEY");

        for (int i = 1; i < args.length; i++) {
            if ("--key".equals(args[i]) && i + 1 < args.length) {
                hmacKey = args[i + 1];
                i++;
            }
        }

        File auditFile = new File(filePath);
        if (!auditFile.exists() || !auditFile.isFile()) {
            System.err.println("Error: Audit file not found: " + filePath);
            return 1;
        }

        System.out.println("===============================================================");
        System.out.println("       MOSIP LIVENESS & PAD - AUDIT CHAIN VERIFIER CLI         ");
        System.out.println("===============================================================");
        System.out.println("Target File: " + auditFile.getAbsolutePath());
        System.out.println("HMAC Key:    " + (hmacKey != null && !hmacKey.isBlank() ? "[CONFIGURED]" : "[NONE - SHA-256 Linkage Only]"));
        System.out.println("Scanning audit log events...");

        List<LivenessAuditEvent> events = new ArrayList<>();
        int lineNum = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(auditFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    LivenessAuditEvent ev = parseJsonEvent(line);
                    events.add(ev);
                } catch (Exception e) {
                    System.err.println("Warning: Skipping malformed line " + lineNum + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Fatal: Failed to read audit file: " + e.getMessage());
            return 1;
        }

        System.out.println("Parsed " + events.size() + " total audit events across log file.");

        if (events.isEmpty()) {
            System.out.println("Notice: No valid audit events found in file.");
            return 0;
        }

        AuditChainVerifier.AuditVerificationResult result = AuditChainVerifier.verify(events, hmacKey);

        System.out.println("---------------------------------------------------------------");
        if (result.valid()) {
            System.out.println("✅ VERIFICATION SUCCESSFUL: Cryptographic audit chain is 100% valid.");
            System.out.println("Message: " + result.message());
            System.out.println("Status: Zero tampering, zero front-truncation, valid forward hash links.");
            System.out.println("===============================================================");
            return 0;
        } else {
            System.err.println("❌ VERIFICATION FAILED: TAMPERING OR CORRUPTION DETECTED!");
            System.err.println("First Broken Event Index: " + result.firstBrokenIndex());
            System.err.println("Failure Reason:           " + result.message());
            System.err.println("===============================================================");
            return 1;
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java io.mosip.liveness.core.audit.AuditVerifierCli <path-to-audit.jsonl> [--key <HMAC_SECRET>]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --key <secret>  Optional HMAC secret key used to verify final event signature.");
        System.out.println("                  Can also be provided via LIVENESS_AUDIT_HMAC_KEY environment variable.");
    }

    /**
     * Parses a single JSON line into a LivenessAuditEvent using pure Java string extraction.
     */
    public static LivenessAuditEvent parseJsonEvent(String json) {
        String eventId = extractField(json, "eventId");
        String sessionId = extractField(json, "sessionId");
        String previousHash = extractField(json, "previousHash");
        String currentHash = extractField(json, "currentHash");
        long timestampMillis = extractLong(json, "timestampMillis");

        String wfStr = extractField(json, "workflow");
        WorkflowType workflow = wfStr.isEmpty() ? WorkflowType.RESIDENT_AUTH : WorkflowType.valueOf(wfStr);

        String evStr = extractField(json, "eventType");
        AuditEventType eventType = evStr.isEmpty() ? AuditEventType.SESSION_START : AuditEventType.valueOf(evStr);

        String decStr = extractField(json, "decision");
        DecisionType decision = decStr.isEmpty() ? null : DecisionType.valueOf(decStr);

        Double passiveScore = extractDouble(json, "passiveScore");
        String padClassStr = extractField(json, "padClassification");
        PadClassification padClass = padClassStr.isEmpty() ? null : PadClassification.valueOf(padClassStr);
        Double padScore = extractDouble(json, "padScore");

        String chStr = extractField(json, "challengeType");
        ChallengeType challengeType = chStr.isEmpty() ? null : ChallengeType.valueOf(chStr);

        String errorCode = extractField(json, "errorCode");
        if (errorCode.isEmpty()) errorCode = null;

        String modelVersion = extractField(json, "modelVersion");
        String deviceId = extractField(json, "deviceId");

        String signature = extractField(json, "signature");
        if (signature.isEmpty()) signature = null;

        Map<String, String> details = extractDetailsMap(json);

        return new LivenessAuditEvent(
                eventId,
                sessionId,
                timestampMillis,
                workflow,
                eventType,
                decision,
                passiveScore,
                padClass,
                padScore,
                challengeType,
                errorCode,
                modelVersion,
                deviceId,
                details,
                previousHash,
                currentHash,
                signature
        );
    }

    private static String extractField(String json, String name) {
        String pattern = "\"" + name + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return "";
        return json.substring(start, end);
    }

    private static long extractLong(String json, String name) {
        String pattern = "\"" + name + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0L;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end <= start) return 0L;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Double extractDouble(String json, String name) {
        String pattern = "\"" + name + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        if (json.startsWith("null", start)) return null;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        if (end <= start) return null;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> extractDetailsMap(String json) {
        int idx = json.indexOf("\"details\":");
        if (idx < 0) return Map.of();
        int braceStart = json.indexOf("{", idx);
        if (braceStart < 0) return Map.of();
        int braceEnd = json.indexOf("}", braceStart);
        if (braceEnd <= braceStart) return Map.of();

        String body = json.substring(braceStart + 1, braceEnd).trim();
        if (body.isEmpty()) return Map.of();

        Map<String, String> map = new TreeMap<>();
        String[] pairs = body.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String k = kv[0].replaceAll("[\"\\s]", "");
                String v = kv[1].replaceAll("[\"\\s]", "");
                map.put(k, v);
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
