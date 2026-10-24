# System Architecture & Technical Specification

**Project:** MOSIP Face Liveness & Presentation Attack Detection (PAD)  
**Language:** Java 17 (Pure Java Core, Zero External Heavy Runtime Dependencies)  
**Standards:** ISO/IEC 19794-5, ISO/IEC 30107-1/-3, RFC 7515 (JWS)  

---

## 1. Modular Architecture

```
                                  +-----------------------+
                                  |     liveness-api      |  (Pure Java 17 Interfaces, Records & Enums)
                                  +-----------------------+
                                        ^           ^
                    +-------------------+           +-------------------+
                    |                                                   |
          +-------------------+                               +-------------------+
          |   liveness-core   | (Quality, Passive PAD,        | liveness-device-  |
          |                   |  Challenges, Audit Chain,     |      mock         |
          |                   |  JWS Receipts, Verifier CLI)  |                   |
          +-------------------+                               +-------------------+
             ^             ^                                            ^
             |             |                                            |
    +-----------------+    |                                            |
    |  liveness-onnx  |    |                                            |
    | (Optional Ultra-|    |                                            |
    |  Face, MiniFAS) |    |                                            |
    +-----------------+    |                                            |
             ^             |                                            |
             |             |                                            |
             +-------------+-----------------------+                    |
                           |                       |                    |
                 +-------------------+   +-------------------+          |
                 |  liveness-server  |   |   liveness-eval   |----------+
                 | (Javalin 6 WS/REST|   | (ISO 30107-3 CLI  |
                 |  Webcam Client)   |   |  Benchmark Suite) |
                 +-------------------+   +-------------------+
```

---

## 2. End-to-End Verification Lifecycle

```mermaid
sequenceDiagram
    autonumber
    actor User as Subject / Web Browser
    participant Server as LivenessServer (Javalin 6)
    participant Engine as DefaultLivenessEngine
    participant Detector as ServerFaceDetector & Validator
    participant Quality as QualityEvaluator (ISO 19794-5)
    participant Passive as PassiveAnalyzer (8 Signals)
    participant Challenge as ChallengeVerifier (Active PAD)
    participant Audit as Cryptographic AuditSink
    participant Adapter as MosipBiometricSdkAdapter

    User->>Server: POST /api/v1/sessions (WorkflowType, ClientIdentity)
    Server-->>User: Signed SessionToken (HMAC-SHA256, Exp, Nonce)

    User->>Server: WebSocket Connect (/ws/liveness?token=...)
    Server->>Engine: startSession(sessionId, workflow, policy, listener)
    Engine->>Audit: Append SESSION_START (previousHash = GENESIS)

    loop Frame Streaming (~12-15 FPS)
        User->>Server: Frame Payload (JPEG base64, 4-dec Landmarks, seq, tsMs, challengeToken)
        Server->>Server: ImageReader Header Check (Dim <= 1280x1280)
        Server->>Server: Seq & TsMs Monotonicity & ChallengeToken Echo Verification
        Server->>Engine: processFrame(session, frame)

        Engine->>Detector: Cross-Validate Client Landmarks vs Server Pixels
        alt Landmark Mismatch (IoU < 0.40 or Pose Delta > 18 deg)
            Detector-->>Engine: INVALID (Attack Evidence)
            Engine->>Audit: Append LANDMARK_MISMATCH (REJECTED)
            Engine-->>Server: LivenessDecision.rejected(...)
            Server-->>User: Generic Message ("Face verification could not be completed.")
        else Landmark Validated
            Engine->>Quality: Evaluate ISO 19794-5 Gating
            alt Quality Non-Compliant (Blur, Pose, Dark, Eyes Closed)
                Quality-->>Engine: Quality Feedback Guidance
                Engine-->>Server: LivenessDecision.retry(guidance)
                Server-->>User: Localized Guidance Prompt (EN / தமிழ்)
            else Quality Compliant
                alt State: PASSIVE_CHECK (Sliding Window)
                    Engine->>Passive: evaluateWindow(slidingWindow, activeFrame)
                    Passive->>Passive: Evaluate 8 Signals + Logistic Fusion + Hard Veto
                    alt Presentation Attack Detected (attackScore >= 0.70 or Hard Veto)
                        Passive-->>Engine: PAD Attack (PRINT, SCREEN, MASK)
                        Engine->>Audit: Append PAD_ATTACK_DETECTED (REJECTED)
                        Engine-->>Server: LivenessDecision.rejected(...)
                        Server-->>User: Generic Message ("Face verification could not be completed.")
                    else Genuine Passive Score Satisfies Threshold
                        alt Active Challenge Required by Policy
                            Engine->>Challenge: issueNextChallenge()
                            Challenge-->>Server: Challenge Issued (Action, Nonce, Timeout)
                            Server-->>User: Challenge Prompt ("Blink eyes naturally")
                        else Passive-Only Policy Satisfied
                            Engine->>Audit: Append FINAL_DECISION (ACCEPTED, HMAC Signature)
                            Engine->>Adapter: createLivenessAssertion(decision, policy, nonce)
                            Adapter-->>Engine: Signed MosipLivenessReceipt (JWS)
                            Engine-->>Server: LivenessDecision.accepted(receipt)
                            Server-->>User: Generic Success ("Face verification successful.")
                        end
                    end
                end
            end
        end
    end

    Server->>Engine: finishSession(session)
    Engine->>Engine: Wipe bestCompliantFrame RAM & Evict Session Context
```

---

## 3. Core Architectural Modules

### `liveness-api` (Contract Layer)
- Pure Java 17 records, interfaces, and enums.
- Zero dependencies on external libraries or frameworks.
- Defines `LivenessEngine`, `LivenessSession`, `FaceFrame`, `FaceQuality`, `LivenessDecision`, `LivenessPolicy`, `LivenessAuditEvent`.

### `liveness-core` (Inference & Security Layer)
- **`ServerFaceDetector` & `LandmarkCrossValidator`:** Eliminates client spoofing by executing independent Haar/pixel skin detection on the server and comparing bounding box IoU ($\ge 0.40$), pupil distance ($\le 0.20$), and 3D pose deltas ($\le 18^\circ$).
- **`QualityEvaluator`:** Implements ISO/IEC 19794-5 face quality checks: face size ratio ($0.08$ to $0.75$), pose tolerances ($\le 15^\circ$), luma ($0.20$ to $0.85$), and sharpness (Laplacian variance $\ge 35.0$).
- **`PassiveAnalyzer`:** Evaluates 8 independent presentation attack signals:
  1. *Micro-Motion:* Procrustes non-rigid physiological landmark jitter residual.
  2. *Moire Frequency:* 2D Fast Fourier Transform (FFT) subpixel grid frequency analysis.
  3. *Specular Glare:* Highlight intensity ratio over face ROI.
  4. *Texture / Color:* RGB standard deviation and HSV color saturation statistics.
  5. *Border / Bezel:* Orthogonal Hough / Radon line accumulator over outer frame margins.
  6. *Timing Integrity:* Inter-frame variance, microsecond burst anomalies, and sequence gaps.
  7. *Landmark Consistency:* Pixel color corroboration at landmark locations.
  8. *3D Mask Detection:* Micro-pore texture variance and contour gradient transitions.
- **`LogisticFusionModel`:** Calibrated logistic regression fusing all 8 heuristic signals with deterministic hard-veto rules (e.g. $\ge 2$ screen cues below $0.20$ triggers instant veto).
- **`MosipLivenessReceipt` & `MosipBiometricSdkAdapter`:** Signs and verifies compact RFC 7515 JWS receipt tokens for MOSIP Registration Client / IDA consumption.
- **`AuditVerifierCli`:** Standalone CLI tool that scans `audit.jsonl` files and cryptographically verifies the SHA-256 forward hash chain, genesis linkage, canonical JSON details, and terminal HMAC-SHA256 signatures.

### `liveness-onnx` (Optional Neural Module)
- Deep learning inference models loaded when ONNX Runtime is available:
  - **UltraFace RFB-320:** Real-time face detection ($< 10\text{ ms}$).
  - **MiniFASNetV2:** Multi-scale patch passive presentation attack detection.
  - **MobileFaceNet:** 512-dimensional face embeddings for cosine similarity verification across frames.
- Degrades gracefully to the pure-Java heuristic pipeline if ONNX models are absent.

### `liveness-server` (Application & UI Layer)
- Embedded Javalin 6 server providing WebSocket streaming and REST APIs.
- Token-gated endpoints: `/api/audit*`, `/api/policy*`, and `/api/supervisor/override` require `LIVENESS_ADMIN_TOKEN`.
- Embedded HTML5 client with Judge Mode, Live Telemetry Radar, Voice Guidance (Tamil & English), High-Contrast Mode, and Red Team Attack Gallery simulator.
