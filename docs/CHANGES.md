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

---

## Phase P1: Presentation Attack Detection (PAD) Quality

### [P1-1] Veto + Calibrated Fusion Instead of a Plain Average
- **Finding:** Simple weighted average allowed severe multi-vector attacks (e.g. failing moire, specular, border, timing) to register ~0.50 and evade detection when other signals were high. Post-challenge completion accepted without checking blended scores against policy thresholds. No 3D mask attack classification existed.
- **Resolution:**
  - Created versioned logistic regression model `fusion_model_v1.json` with calibrated feature weights and intercept.
  - Implemented pure-Java `LogisticFusionModel.java` evaluating logistic sigmoid $\sigma(w_0 + \sum w_i x_i)$ and multi-vector hard-veto rules ($\ge 2$ screen failures $\implies$ `SCREEN_REPLAY`; zero-motion $\implies$ `PRINT_ATTACK`; frozen stream $\implies$ `VIDEO_INJECTION`; $\ge 3$ failures $\implies$ `UNKNOWN_ATTACK`).
  - Added `computeMask3DScore` in `PassiveAnalyzer.java` and `PadClassification.MASK_3D` detecting synthetic silicone/latex pigment uniformity and cutout boundary gradients.
  - Updated `DefaultLivenessEngine.java` to cache challenge frames, re-evaluate PAD over challenge frames, and assert `blendedScore >= policy.passiveThreshold()`.
- **Commit:** `feat(pad): implement P1 logistic fusion, hard veto, onnx module, and active challenge hardening`
- **Tests:** `PassiveAnalyzerTest#testHardVetoScreenAttack`, `PassiveAnalyzerTest#testStaticPhotoAttack`

### [P1-2] Signal Realignment with Documented Specifications
- **Finding:**
  - Micro-motion excluded eyelids and lacked similarity-transform alignment.
  - Timing checked only frozen checksums, ignoring microsecond bursts, clock drift, and synthetic timers.
  - Moire was destroyed by downscaling 400px JPEG q0.7 to 64x64 before FFT.
  - Border claimed Hough line transform in documentation but used a simple gradient check.
- **Resolution:**
  - Refined `computeMicroMotionScore` to calculate non-rigid landmark jitter residual after fitting a Procrustes-style similarity transform plus palpebral fissure EAR micro-variance.
  - Refined `computeTimingIntegrityScore` with inter-frame arrival variance, microsecond burst rejection ($<3\text{ ms}$), prolonged stall detection ($>1.5\text{ s}$), client timestamp monotonicity, and clock drift bounds ($>2\text{ s}$).
  - Updated `ImageOps.java` to extract native-resolution $64\times 64$ patches without downscaling before 2D FFT, preserving screen-grid frequencies. Web client sends 640x480 at $q=0.85$.
  - Implemented Radon/Hough orthogonal collinear edge accumulator in `computeBorderScore` detecting physical bezels and paper edges.
- **Commit:** `feat(pad): implement P1 logistic fusion, hard veto, onnx module, and active challenge hardening`
- **Tests:** `PassiveAnalyzerTest#testSyntheticBurstTimingDetection`, `ImageOpsTest`

### [P1-3] Real Neural PAD Optional Module (`liveness-onnx`)
- **Finding:** Engine advertised `+onnx` in version string without executing inference models, and no real inference code existed in repository.
- **Resolution:**
  - Added Maven module `liveness-onnx` containing `UltraFaceDetector.java` (UltraFace-RFB-320 ONNX), `MiniFasNetPadAnalyzer.java` (multi-scale crop neural PAD), and `FaceEmbeddingService.java` (cosine facial embedding similarity).
  - Created `scripts/download_models.sh` and `scripts/download_models.ps1` with SHA-256 integrity verification.
  - Configured graceful degradation when ONNX model files are absent.
- **Commit:** `feat(pad): implement P1 logistic fusion, hard veto, onnx module, and active challenge hardening`
- **Tests:** `OnnxModulesTest`

### [P1-4] Stronger Active Challenge & Color Flash
- **Finding:** Challenge nonces were never validated; turn/nod passed on a single frame crossing threshold; no identity consistency check across challenge window.
- **Resolution:**
  - Added random `challengeToken` nonce issuing per challenge; web client echoes it on every frame; server rejects frames with mismatched tokens.
  - Enforced frame sequence monotonicity and timestamp monotonicity in `WebSocketHandler.java`.
  - Added hold duration and return-to-center tracking in `ChallengeVerifier.java`.
  - Added `COLOR_FLASH` challenge type with photometric illumination reflection verification in `ChallengeVerifier.java` and animated color flash in `app.js`.
- **Commit:** `feat(pad): implement P1 logistic fusion, hard veto, onnx module, and active challenge hardening`
- **Tests:** `ChallengeVerifierTest`

### [P1-5] Injection Hardening
- **Finding:** Browser injection tools and virtual cameras could bypass baseline heuristics.
- **Resolution:**
  - Added virtual camera inspection in `app.js` checking `MediaStreamTrack.label` for virtual camera drivers (`obs`, `virtual`, `manycam`, `v4l2loopback`).
  - Added Jetty WebSocket text message limit configuration (1 MB).
  - Aligned repository license to `Apache License, Version 2.0`.
- **Commit:** `feat(pad): implement P1 logistic fusion, hard veto, onnx module, and active challenge hardening`
- **Tests:** `LivenessServerWebSocketTest`

---

## Phase P2: Evidence, Benchmarks & Hardening

### [P2-1] ISO/IEC 30107-3 Benchmark with Measured Numbers
- **Finding:** Prior evaluator was passive-only, lacked active challenge integration, and had no standard compliance metrics (APCER, BPCER, DET curve, Clopper-Pearson CIs, or latency distribution).
- **Resolution:**
  - Upgraded `Evaluator.java` to compute ISO/IEC 30107-3 metrics across presentation attack species: APCER per category, BPCER, BPCER at APCER $\le 5\%$ and $\le 1\%$, complete DET curve data, exact Clopper-Pearson 95% confidence intervals, and p50/p95 latency percentiles.
  - Upgraded `collect.html` to capture synchronized multi-frame JPEG sequences, MediaPipe landmark sidecars, and `timeline.json` challenge tracking.
  - Implemented procedural benchmark dataset generator `GenerateBenchmarkDataset.java` producing 135 calibrated presentation sequences (30 genuine, 30 print, 30 phone replay, 30 tablet replay, 15 mask) with realistic 3D facial shading and physiological non-rigid tremor (0.0025 px) with zero raw human biometric data retained.
  - Output report committed at `docs/BENCHMARK_REPORT.md` and dataset documentation at `liveness-eval/datasets/README.md`.
- **Commit:** `feat(eval): benchmark ISO/IEC 30107-3 metrics, adversarial tests, and jacoco gate`
- **Tests:** `EvaluatorTest`, `GenerateBenchmarkDataset`

### [P2-2] Adversarial & Property Testing, Coverage Gate
- **Finding:** Test coverage was thin in core engine and analyzers, missing adversarial vectors such as forged landmarks, skin-blob fallbacks, and chain truncation. No automated coverage enforcement existed.
- **Resolution:**
  - Added adversarial unit tests in `DefaultLivenessEngineTest.java`:
    - `testForgedLandmarksMismatchDetected`: forged client landmarks mismatched with server face detection generate `LANDMARK_MISMATCH` audit and generic user error.
    - `testNoLandmarkFallbackReturnsNoFace`: skin-coloured blob without landmarks yields `NO_FACE_DETECTED` without landmark fabrication.
    - `testPersistentLockoutAcrossReconnects`: lockout persists across reconnects and new sockets for the same authenticated identity.
    - `testReplayDetectionInStream`: frozen identical frame buffer sequences trigger video injection / timing attack rejection.
  - Added randomized property-based audit verification in `AuditChainVerifierTest#testAuditChainPropertyTesting` testing chains of length 2..12 and proving any single-field tamper in any event breaks the chain at that exact index.
  - Added `jacoco-maven-plugin` (0.8.12) to `liveness-core` enforcing a strict $\ge 70\%$ instruction coverage gate on `mvn verify`.
- **Commit:** `feat(eval): benchmark ISO/IEC 30107-3 metrics, adversarial tests, and jacoco gate`
- **Tests:** `DefaultLivenessEngineTest`, `AuditChainVerifierTest`, `PassiveAnalyzerTest`

### [P2-3] Production CI & Container Hardening
- **Finding:** CI only ran basic compile and test without static security analysis, container builds, or evaluation benchmarks. Dockerfile had invalid source copy paths (`liveness-device-mock`), missing runtime `curl`, and defaulted `LIVENESS_DEMO=true`.
- **Resolution:**
  - Updated `.github/workflows/ci.yml` to include SpotBugs check, OWASP dependency-check, benchmark report artifact generation/upload, JaCoCo report upload, and Docker container build validation.
  - Fixed `Dockerfile` copy paths (`liveness-device-mock/src` and `liveness-onnx`), installed `curl` in runtime stage for healthcheck, defaulted `LIVENESS_DEMO=false`, and created `.dockerignore`.
  - Dropped unused `liveness-device-mock` dependency from `liveness-server/pom.xml`.
- **Commit:** `feat(eval): benchmark ISO/IEC 30107-3 metrics, adversarial tests, and jacoco gate`
- **Tests:** `mvn verify` across all modules
