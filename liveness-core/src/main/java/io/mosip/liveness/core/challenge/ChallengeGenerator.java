package io.mosip.liveness.core.challenge;

import io.mosip.liveness.api.challenge.Challenge;
import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.engine.LivenessSession;
import io.mosip.liveness.api.policy.LivenessPolicy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Generates dynamic, cryptographically bound active challenges.
 * Avoids immediate repeat of the previous challenge and binds to the session's nonce.
 */
public class ChallengeGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a new random Challenge allowed by the session policy, avoiding consecutive repeats.
     */
    public Challenge generateChallenge(LivenessSession session, ChallengeType lastType) {
        Objects.requireNonNull(session, "LivenessSession cannot be null");
        LivenessPolicy policy = session.getPolicy();

        Set<ChallengeType> allowed = policy.allowedChallengeTypes();
        if (allowed == null || allowed.isEmpty()) {
            allowed = Set.of(ChallengeType.BLINK, ChallengeType.SMILE);
        }

        List<ChallengeType> candidates = new ArrayList<>(allowed);
        if (candidates.size() > 1 && lastType != null) {
            candidates.remove(lastType);
        }

        ChallengeType selected = candidates.get(secureRandom.nextInt(candidates.size()));
        long timeoutMillis = policy.activeTimeoutSeconds() > 0 ?
                policy.activeTimeoutSeconds() * 1000L : selected.getDefaultTimeoutMillis();

        return Challenge.create(selected, session.getNonce(), timeoutMillis);
    }
}
