package io.mosip.liveness.server;

import io.mosip.liveness.api.policy.WorkflowType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and cryptographically verifies HMAC-SHA256 signed session tokens.
 * Binds workflow, expiry, cryptographic nonce, and authenticated client identity to each session.
 */
public class SessionTokenService {

    public record SessionToken(
            String sessionId,
            WorkflowType workflow,
            long expiryMillis,
            String nonce,
            String clientIdentity,
            boolean valid
    ) {}

    private final String secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionTokenService(String secretKey) {
        this.secretKey = secretKey != null && !secretKey.isBlank() ? secretKey : "mosip-default-session-secret-2026";
    }

    /**
     * Issues a URL-safe signed session token.
     */
    public String issueToken(String sessionId, WorkflowType workflow, String clientIdentity, long validityDurationMillis) {
        long expiry = System.currentTimeMillis() + validityDurationMillis;
        byte[] nonceBytes = new byte[16];
        secureRandom.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        String safeClient = clientIdentity != null && !clientIdentity.isBlank() ? clientIdentity.trim() : "anonymous";

        String payload = sessionId + "|" + workflow.name() + "|" + expiry + "|" + nonce + "|" + safeClient;
        String sig = computeHmac(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((payload + "|" + sig).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies the cryptographic signature, payload structure, and expiry of a session token.
     */
    public SessionToken verifyToken(String tokenStr) {
        if (tokenStr == null || tokenStr.isBlank()) {
            return new SessionToken(null, null, 0, null, null, false);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(tokenStr.trim());
            String content = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = content.split("\\|");
            if (parts.length != 6) {
                return new SessionToken(null, null, 0, null, null, false);
            }
            String sessionId = parts[0];
            WorkflowType workflow = WorkflowType.valueOf(parts[1]);
            long expiry = Long.parseLong(parts[2]);
            String nonce = parts[3];
            String clientIdentity = parts[4];
            String providedSig = parts[5];

            if (System.currentTimeMillis() > expiry) {
                return new SessionToken(sessionId, workflow, expiry, nonce, clientIdentity, false);
            }

            String expectedPayload = sessionId + "|" + workflow.name() + "|" + expiry + "|" + nonce + "|" + clientIdentity;
            String expectedSig = computeHmac(expectedPayload);

            boolean sigMatches = MessageDigest.isEqual(
                    providedSig.getBytes(StandardCharsets.UTF_8),
                    expectedSig.getBytes(StandardCharsets.UTF_8)
            );

            return new SessionToken(sessionId, workflow, expiry, nonce, clientIdentity, sigMatches);
        } catch (Exception e) {
            return new SessionToken(null, null, 0, null, null, false);
        }
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
