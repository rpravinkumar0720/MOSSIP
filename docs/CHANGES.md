# MOSSIP Engineering Changelog & Audit Trace

This document maps all identified architectural, security, algorithmic, and presentation findings across phases P0–P3 directly to implementation commits and verification artifacts.

---

## Phase P0: Security & Correctness

### [P0-1] Don't Trust Client Landmarks
- **Finding:** Client-sent landmarks were accepted with hard-coded 0.99 confidence and used directly for challenge verification, allowing synthetic landmarks with a still photo to pass. Fabricated landmarks from `estimateFromFramePixels` generated perfect EAR/MAR from any skin blob.
- **Resolution:**
  - Removed fabricated landmark fallback from `ClientLandmarkAnalyzer.java`, returning `NO_FACE_DETECTED` (`Optional.empty()`) when no real face is identified.
  - Implemented pure-Java `ServerFaceDetector.java` performing skin chrominance segmentation in YCbCr space, morphological connected component bounding, and eye/mouth contrast verification.
  - Implemented `LandmarkCrossValidator.java` enforcing bounding box IoU ($\ge 0.20$), pupil-to-pupil distance ratio ($\le 0.35$), and pose yaw delta ($\le 35^\circ$). Mismatches generate `LANDMARK_MISMATCH` audit events and abort verification.
  - Implemented pixel corroboration in `ChallengeVerifier.java` validating eye region luma deltas (blink) and pixel sum-of-absolute-differences motion (turn/nod).
- **Commit:** `feat(security): implement P0 anti-spoofing, audit signing, session auth, and lockout`
- **Tests:** `DefaultLivenessEngineTest`, `LivenessServerWebSocketTest`

### [P0-2] Close the Anti-Adversarial Leak
- **Finding:** Public WebSocket decision payloads broadcasted raw scores and session IDs. Unauthenticated `/api/audit/{sessionId}` leaked attack classification (`PRINT_ATTACK`, `SCREEN_REPLAY`, `VIDEO_INJECTION`) and PAD subscores. `/api/audit` dumped the entire event store to any anonymous caller.
- **Resolution:**
  - Redacted public WebSocket decision payload in `WebSocketHandler.java` to only return high-level decision enum and localized generic user message. Raw scores and session IDs are suppressed unless authenticated in Judge Mode.
  - Protected `/api/audit*`, `/api/policy*`, and `/api/supervisor/override` endpoints behind constant-time Bearer token verification (`LIVENESS_ADMIN_TOKEN`).
  - Added token-gated "Judge Mode" toggle in web UI (`btnJudgeMode`) with full diagnostic telemetry unlocked only when authenticated.
- **Commit:** `feat(security): implement P0 anti-spoofing, audit signing, session auth, and lockout`
- **Tests:** `LivenessServerRestTest#testAuditEndpointsRequireAdminToken`, `LivenessServerWebSocketTest#testAdversarialScoreLeakPrevented`

### [P0-3] AuthN/Z, Workflow Binding, Demo Default
- **Finding:** Clients selected arbitrary workflows via `?workflow=`, allowing selection of weak policies (e.g. `RESIDENT_AUTH` with passive-only). `LIVENESS_DEMO` defaulted to true. Supervisor override was unauthenticated in demo mode. No Origin verification or connection/bandwidth limits existed.
- **Resolution:**
  - Changed `demoMode` default to `false` in `ServerConfig.java`.
  - Added authenticated `POST /api/v1/sessions` endpoint issuing HMAC-SHA256 signed session tokens encoding workflow, client identity, nonce, and expiration.
  - Enforced signed session tokens on WebSocket connection in `WebSocketHandler.java` (with graceful fallback only if `demoMode=true`).
  - Implemented Origin allow-list checks, per-IP connection limits (max 10 concurrent sockets), and per-session byte limits (15 MB).
  - Hardened `/api/supervisor/override` requiring a recent (<5 min), single-use `ACCEPTED` `SUPERVISOR_AUTH` session different from target.
- **Commit:** `feat(security): implement P0 anti-spoofing, audit signing, session auth, and lockout`
- **Tests:** `LivenessServerRestTest#testSessionTokenCreationAndPolicyBinding`, `LivenessServerRestTest#testSupervisorOverrideRequiresAcceptedSession`, `LivenessServerWebSocketTest#testMissingTokenRejectedInNonDemoMode`

### [P0-4] Lockout / Retries are Bypassable
- **Finding:** Lockout was keyed solely by frame `deviceId`, which `WebSocketHandler` populated with ephemeral `sessionId`. Reconnecting reset retry counts and lockout state.
- **Resolution:**
  - Keyed lockout and retry budgets by authenticated `clientIdentity` (token claim) and persistent `deviceId`, preserved in `DefaultLivenessEngine` across reconnections.
  - Recorded real client identity in audit events.
  - Refused new sessions with `MAX_RETRIES_EXCEEDED` once retry budget is exhausted.
- **Commit:** `feat(security): implement P0 anti-spoofing, audit signing, session auth, and lockout`
- **Tests:** `LivenessServerWebSocketTest#testPersistentLockoutAcrossSockets`

### [P0-5] Rejections Never Reach the User
- **Finding:** PAD-attack branches in `DefaultLivenessEngine` returned `LivenessDecision.rejected(...)` without notifying the session listener or triggering state change events, causing client UI to loop indefinitely without feedback.
- **Resolution:**
  - Routed all outcomes (acceptance, retry, challenge, PAD rejection) through unified `listener.onDecision(...)` and `listener.onStateChanged(...)`.
  - Included informative "Attempt N of M" messaging and reset progress on retry.
- **Commit:** `feat(security): implement P0 anti-spoofing, audit signing, session auth, and lockout`
- **Tests:** `DefaultLivenessEngineTest#testPadAttackDispatchesRejectionToListener`

### [P0-6] Tamper-Evident Audit Chain
- **Finding:** Audit event `details` map was excluded from SHA-256 hash calculation. First event previous hash was unvalidated, allowing front-truncation. Any attacker with write access could recompute valid unkeyed SHA-256 hashes. Verification treated all sessions as one chain, failing across sequential sessions.
- **Resolution:**
  - Implemented canonical sorted JSON serialization for `details` in `LivenessAuditEvent.java` hash computation.
  - Added strict check requiring initial event `previousHash` to equal `GENESIS_HASH`.
  - Added HMAC-SHA256 signature field and signing utility on final session events (`LIVENESS_AUDIT_KEY`).
  - Added session-aware verification in `AuditChainVerifier.verifySessions(...)`.
  - Added bounded LRU cache (1,000 sessions) and 10 MB rotation in `FileAuditSink.java`.
- **Commit:** `feat(security): implement P0 anti-spoofing, audit signing, session auth, and lockout`
- **Tests:** `LivenessAuditEventTest`, `AuditChainVerifierTest`
