package io.mosip.liveness.core.receipt;

import io.mosip.liveness.api.decision.LivenessDecision;
import io.mosip.liveness.api.frame.FaceFrame;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Digitally signed MOSIP Liveness Receipt conforming to JWS compact serialization (RFC 7515).
 * Binds the biometric session, challenge nonce, decision, workflow, policy hash, and SHA-256
 * digest of the best compliant frame.
 */
public record MosipLivenessReceipt(
        String sessionId,
        String nonce,
        String decision,
        String workflow,
        String policyHash,
        String bestFrameSha256,
        long issuedAt,
        long expiresAt,
        String jwsToken
) {

    public MosipLivenessReceipt {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(jwsToken, "jwsToken must not be null");
    }

    /**
     * Issues a signed JWS liveness receipt from an accepted LivenessDecision.
     *
     * @param decision liveness decision containing session and optional best frame
     * @param nonce session challenge nonce
     * @param policyHash SHA-256 digest of active policy parameters
     * @param hmacSecretKey secret key for HMAC-SHA256 signature
     * @param validitySeconds lifetime of receipt in seconds (e.g. 300s = 5 minutes)
     * @return signed MosipLivenessReceipt
     */
    public static MosipLivenessReceipt issue(
            LivenessDecision decision,
            String nonce,
            String policyHash,
            byte[] hmacSecretKey,
            long validitySeconds) {

        Objects.requireNonNull(decision, "decision cannot be null");
        Objects.requireNonNull(hmacSecretKey, "hmacSecretKey cannot be null");

        String effectiveNonce = nonce != null ? nonce : "";
        String effectivePolicyHash = policyHash != null ? policyHash : "default-policy";
        String frameDigest = computeFrameDigest(decision.bestCompliantFrame());

        long nowSec = Instant.now().getEpochSecond();
        long expSec = nowSec + Math.max(30, validitySeconds);

        // Header: {"alg":"HS256","typ":"JWT"}
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));

        // Payload
        String payloadJson = String.format(java.util.Locale.ROOT,
                "{\"iss\":\"io.mosip.liveness\",\"sub\":\"%s\",\"decision\":\"%s\",\"workflow\":\"%s\",\"nonce\":\"%s\",\"policyHash\":\"%s\",\"frameHash\":\"%s\",\"iat\":%d,\"exp\":%d}",
                escapeJson(decision.sessionId()),
                escapeJson(decision.decisionType().name()),
                escapeJson(decision.workflow().name()),
                escapeJson(effectiveNonce),
                escapeJson(effectivePolicyHash),
                escapeJson(frameDigest),
                nowSec,
                expSec
        );
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

        String signingInput = encodedHeader + "." + encodedPayload;
        byte[] signatureBytes = computeHmacSha256(signingInput.getBytes(StandardCharsets.UTF_8), hmacSecretKey);
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);

        String jws = signingInput + "." + encodedSignature;

        return new MosipLivenessReceipt(
                decision.sessionId(),
                effectiveNonce,
                decision.decisionType().name(),
                decision.workflow().name(),
                effectivePolicyHash,
                frameDigest,
                nowSec,
                expSec,
                jws
        );
    }

    /**
     * Validates a compact JWS token against the secret key and expected challenge nonce.
     */
    public static MosipLivenessReceipt verify(String jwsToken, byte[] hmacSecretKey, String expectedNonce) {
        Objects.requireNonNull(jwsToken, "jwsToken cannot be null");
        Objects.requireNonNull(hmacSecretKey, "hmacSecretKey cannot be null");

        String[] parts = jwsToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWS compact format: expected 3 dot-delimited parts");
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSig = computeHmacSha256(signingInput.getBytes(StandardCharsets.UTF_8), hmacSecretKey);
        byte[] providedSig = Base64.getUrlDecoder().decode(parts[2]);

        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new SecurityException("JWS signature verification failed: invalid HMAC signature");
        }

        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

        String sub = extractJsonField(payloadJson, "sub");
        String decision = extractJsonField(payloadJson, "decision");
        String workflow = extractJsonField(payloadJson, "workflow");
        String nonce = extractJsonField(payloadJson, "nonce");
        String policyHash = extractJsonField(payloadJson, "policyHash");
        String frameHash = extractJsonField(payloadJson, "frameHash");
        long iat = extractJsonLong(payloadJson, "iat");
        long exp = extractJsonLong(payloadJson, "exp");

        long nowSec = Instant.now().getEpochSecond();
        if (exp < nowSec) {
            throw new SecurityException("JWS token expired (exp=" + exp + ", now=" + nowSec + ")");
        }

        if (expectedNonce != null && !expectedNonce.isBlank() && !expectedNonce.equals(nonce)) {
            throw new SecurityException("Challenge nonce mismatch: expected '" + expectedNonce + "' but token contained '" + nonce + "'");
        }

        return new MosipLivenessReceipt(sub, nonce, decision, workflow, policyHash, frameHash, iat, exp, jwsToken);
    }

    private static String computeFrameDigest(FaceFrame frame) {
        if (frame == null || frame.isWiped()) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(frame.getRawDataUnsafe());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    private static byte[] computeHmacSha256(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractJsonField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return "";
        int start = idx + key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return "";
        return json.substring(start, end);
    }

    private static long extractJsonLong(String json, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return 0L;
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end <= start) return 0L;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
