package io.mosip.liveness.api.engine;

import io.mosip.liveness.api.challenge.Challenge;
import io.mosip.liveness.api.policy.LivenessPolicy;
import io.mosip.liveness.api.policy.WorkflowType;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable stateful session tracker for a single biometric verification attempt.
 * Houses the anti-replay session nonce, retry counter, active challenges, and lifecycle state.
 */
public class LivenessSession implements AutoCloseable {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String sessionId;
    private final WorkflowType workflow;
    private final LivenessPolicy policy;
    private final String nonce;
    private final long createdTimestampMillis;
    private final AtomicInteger retryCount = new AtomicInteger(0);

    private volatile Challenge currentChallenge;
    private volatile boolean closed = false;

    public LivenessSession(String sessionId, WorkflowType workflow, LivenessPolicy policy) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
        this.workflow = Objects.requireNonNull(workflow, "workflow cannot be null");
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        this.nonce = generateNonce();
        this.createdTimestampMillis = System.currentTimeMillis();
    }

    private static String generateNonce() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String getSessionId() {
        return sessionId;
    }

    public WorkflowType getWorkflow() {
        return workflow;
    }

    public LivenessPolicy getPolicy() {
        return policy;
    }

    public String getNonce() {
        return nonce;
    }

    public long getCreatedTimestampMillis() {
        return createdTimestampMillis;
    }

    public int getRetryCount() {
        return retryCount.get();
    }

    public int incrementRetry() {
        return retryCount.incrementAndGet();
    }

    public void resetRetries() {
        retryCount.set(0);
    }

    public Challenge getCurrentChallenge() {
        return currentChallenge;
    }

    public void setCurrentChallenge(Challenge currentChallenge) {
        this.currentChallenge = currentChallenge;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.currentChallenge = null;
    }

    @Override
    public String toString() {
        return "LivenessSession{" +
                "sessionId='" + sessionId + '\'' +
                ", workflow=" + workflow +
                ", retries=" + retryCount.get() +
                ", closed=" + closed +
                '}';
    }
}
