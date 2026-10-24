# MOSIP Biometric Face Liveness & Presentation Attack Detection (PAD)

A reference implementation and prototype biometric face liveness detection and Presentation Attack Detection (PAD) subsystem designed for **MOSIP (Modular Open Source Identity Platform)**. Built on top of the `liveness-api` specification for Java 17.

[![Java CI](https://github.com/rpravinkumar0720/MOSSIP/actions/workflows/ci.yml/badge.svg)](https://github.com/rpravinkumar0720/MOSSIP/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Standards](https://img.shields.io/badge/Standards-ISO%2FIEC%2019794--5%20%7C%20ISO%2030107-green.svg)]()

---

## 📌 Problem Statement & Architecture

National ID systems and biometric authentications are vulnerable to Presentation Attacks (PAIs) including **printed 2D photographs**, **digital screen replays**, and **3D masks**. Traditional solutions often rely on heavy, proprietary, native C++ SDKs or closed cloud APIs that violate data sovereignty and in-memory privacy mandates.

This repository implements an open-source, modular, pure-Java engine featuring:
1. **ISO/IEC 19794-5 Quality Gating:** Pure geometric and pixel evaluation (sharpness, pose, illumination, eye/mouth status) before biometric processing.
2. **Multi-Signal Passive PAD:** 8 independent physical and geometric heuristic signals operational with zero external machine learning models (calibrated logistic regression fusion with hard-veto rules).
3. **Optional Neural PAD Module (`liveness-onnx`):** Deep learning inference using UltraFace RFB-320, MiniFASNetV2, and MobileFaceNet embeddings when ONNX Runtime is present.
4. **Dynamic Unpredictable Active Challenges:** Unpredictable action verification (blink, smile, head turns, nod, color flash) bound to a cryptographic session nonce and per-frame echo tokens.
5. **Anti-Adversarial Information Containment:** Attack classifications are withheld from client-facing messages to prevent spoofers from gaining attack intelligence.
6. **Cryptographic SHA-256 Audit Chaining:** Forward-linked tamper-evident event log with HMAC-SHA256 session signatures, strictly omitting raw images or biometric templates.
7. **Digitally Signed MOSIP Receipts (JWS):** Issues RFC 7515 compliant JWS tokens binding session decision, nonce, policy hash, and compliant frame hash for MOSIP Registration Client / IDA consumption.

Detailed specifications:
- [System Architecture & Sequence Diagram](ARCHITECTURE.md)
- [Comprehensive Threat Model & Security Boundaries](THREAT_MODEL.md)
- [Step-by-Step Hackathon & Judge Demo Guide](DEMO.md)

---

## 🏛️ System Architecture

```
                  +-------------------------------------------------------------+
                  |                      CLIENT (Browser)                       |
                  |  - getUserMedia (Webcam 640x480)                            |
                  |  - Vendored MediaPipe Tasks Vision FaceLandmarker (Offline) |
                  |  - Mirrored Oval Visual Guide & Real-Time Guidance           |
                  +-------------------------------------------------------------+
                                       |                 ^
                                       | Frame + Lms     | JSON Events
                                       | (WebSocket)     | (State, Prompt, Decision)
                                       v                 |
+---------------------------------------------------------------------------------------------------+
|                                  MOSIP LIVENESS SERVER (Javalin 6)                                |
|  - Rate limiter (15 FPS), 1 MB frame size guard, Base64 JPEG to RGB conversion                   |
|  - Token-gated endpoints (/api/audit*, /api/policy/*, /api/supervisor/override)                   |
|  - REST endpoints: /api/health, /api/v1/sessions, /api/audit/verify                              |
+---------------------------------------------------------------------------------------------------+
                                       |
                                       v
+---------------------------------------------------------------------------------------------------+
|                                      LIVENESS CORE ENGINE                                         |
|                                                                                                   |
|   [Stage 1: ISO 19794-5 QualityEvaluator]                                                         |
|   -> Checks Area Ratio (0.08..0.75), Pose (|Yaw|,|Pitch|,|Roll| <= 15°), Luma, Blur (Lap >= 35.0) |
|   -> Landmark Cross-Validation (ServerFaceDetector IoU & pose delta vs client mesh)               |
|                                       | Compliant (3 frames)                                      |
|                                       v                                                           |
|   [Stage 2: PassiveAnalyzer (Sliding Window)]                                                     |
|   -> 8 Heuristic Signals: Micro-motion residual, 2D FFT Moire, Specular Glare,                    |
|      Color stats, Border/Bezel Hough check, Timing integrity, Landmark-pixel consistency, 3D Mask |
|   -> Logistic Regression Fusion & Hard-Veto Rules                                                 |
|   -> Optional Neural Module (liveness-onnx): MiniFASNet multi-scale passive PAD                    |
|                                       | Inconclusive / Enrollment Policy                          |
|                                       v                                                           |
|   [Stage 3: ChallengeVerifier (Dynamic Challenges)]                                               |
|   -> Unpredictable Challenge: BLINK, SMILE, TURN_LEFT, TURN_RIGHT, LOOK_UP, NOD_HEAD, COLOR_FLASH |
|   -> Challenge Token Echo Binding + Monotonic Sequence / Timestamp Verification                   |
|   -> Identity Continuity: Multi-ratio signature & embedding cosine similarity                      |
|   -> Post-Challenge PAD Re-evaluation Gate                                                        |
|                                       |                                                           |
|                                       v                                                           |
|   [Stage 4: Decision, JWS Receipt & Cryptographic AuditSink]                                      |
|   -> ACCEPTED (JWS receipt issued; best frame cloned; volatile RAM pixel buffers wiped)           |
|   -> REJECTED ("Face verification could not be completed. Please try again.")                     |
|   -> SHA-256 Hash-Chained Audit Trail with HMAC-SHA256 Terminal Signatures                         |
+---------------------------------------------------------------------------------------------------+
```

---

## 📊 ISO/IEC 30107-3 Benchmark Results

Evaluated across **135 test sequences** (30 Bona Fide, 30 Print Attacks, 30 Mobile Screen Replays, 30 Tablet Screen Replays, 15 3D Mask Presentations) under standardized ISO/IEC 30107-3 protocols:

| Metric | Target | Measured Result | 95% Clopper-Pearson CI |
| :--- | :--- | :--- | :--- |
| **BPCER (Bona Fide Error)** | $\le 2.0\%$ | **0.00% (0 / 30)** | $[0.00\%, 11.57\%]$ |
| **APCER - PAI-1 (Print)** | $\le 1.0\%$ | **0.00% (0 / 30)** | $[0.00\%, 11.57\%]$ |
| **APCER - PAI-2a (Phone Replay)** | $\le 1.0\%$ | **0.00% (0 / 30)** | $[0.00\%, 11.57\%]$ |
| **APCER - PAI-2b (Tablet Replay)**| $\le 1.0\%$ | **0.00% (0 / 30)** | $[0.00\%, 11.57\%]$ |
| **APCER - PAI-3 (3D Mask)** | $\le 5.0\%$ | **0.00% (0 / 15)** | $[0.00\%, 21.80\%]$ |
| **Max Overall APCER** | $\le 1.0\%$ | **0.00% (0 / 105)** | $[0.00\%, 3.45\%]$ |
| **BPCER @ APCER $\le 5\%$** | $\le 2.0\%$ | **0.00%** | Baseline |
| **BPCER @ APCER $\le 1\%$** | $\le 2.0\%$ | **0.00%** | Baseline |
| **Processing Latency (p50)**| $\le 80\text{ ms}$ | **47.1 ms** | Real-time 21+ FPS |
| **Processing Latency (p95)**| $\le 120\text{ ms}$ | **56.4 ms** | Real-time 17+ FPS |

*Complete benchmark methodology, DET curves, and procedural generators documented in [`docs/BENCHMARK_REPORT.md`](docs/BENCHMARK_REPORT.md).*

---

## ⚡ 3-Command Quick Start

### 1. Build Multi-Module Project (Java 17 & Maven)
```bash
mvn package -DskipTests
```

### 2. Start the Liveness & PAD Server
```bash
java -jar liveness-server/target/liveness-server-1.0.0-SNAPSHOT.jar
```

### 3. Open in Browser
Visit **[http://localhost:8080](http://localhost:8080)** on any device with a camera.

*Alternatively with Docker & HTTPS:*
```bash
docker compose up --build
```
Access via local HTTPS at **`https://localhost:8443`** (enabling mobile LAN webcam streaming).

---

## 📦 Multi-Module Organization

| Module | Description | Dependencies |
| :--- | :--- | :--- |
| **`liveness-api`** | Standard interfaces, policies, records, and audit definitions | Zero external dependencies (pure Java 17) |
| **`liveness-core`** | QualityEvaluator, 8-signal PassiveAnalyzer, ChallengeVerifier, DefaultLivenessEngine, AuditVerifierCli, MosipLivenessReceipt | Pure Java 17, `slf4j-api` |
| **`liveness-onnx`** | Optional neural inference module (UltraFace detector, MiniFASNet anti-spoofing, MobileFaceNet embeddings) | `onnxruntime` |
| **`liveness-device-mock`** | `ImageSequenceDevice` simulating camera streams from directories or synthetic frames | `liveness-api`, `liveness-core` |
| **`liveness-server`** | Javalin 6 WebSocket/REST backend & embedded HTML5/MediaPipe client with Judge Mode & Red Team Lab | `liveness-core`, `javalin`, `jackson`, `logback` |
| **`liveness-eval`** | ISO 30107-3 benchmark suite measuring APCER, BPCER, Clopper-Pearson CIs, and latency | `liveness-core`, `liveness-device-mock` |

---

## 🔒 Security & Privacy Guarantees

- **RAM Frame Wiping:** Frames are processed exclusively in volatile memory. `FaceFrame.wipe()` zeroes internal pixel buffers immediately after analysis.
- **Zero Disk Biometrics:** The server never writes raw images or embeddings to disk.
- **Cryptographic Audit Log:** Every audit event is chained with a SHA-256 digest (`previousHash` $\to$ `currentHash`) and signed with HMAC-SHA256 upon session completion.
- **Anti-Adversarial Messages:** User prompts for any detected attack are strictly normalized to:
  `"Face verification could not be completed. Please try again."`
  PAI categories (`PRINT_ATTACK`, `SCREEN_REPLAY`, `VIDEO_INJECTION`, `MASK_3D`) exist exclusively in internal audit trails and token-gated Judge Mode.
- **Standalone Offline Audit Verifier CLI:**
  ```bash
  java -cp liveness-core/target/liveness-core-1.0.0-SNAPSHOT.jar:liveness-api/target/liveness-api-1.0.0-SNAPSHOT.jar \
    io.mosip.liveness.core.audit.AuditVerifierCli audit.jsonl --key <HMAC_SECRET>
  ```

---

## ⚠️ Limitations & Honesty Statement

> [!WARNING]
> 1. **Reference Implementation & Prototype:** This system is an open-source reference implementation and research prototype. While engineered against ISO/IEC 19794-5 and ISO/IEC 30107 principles, it has not undergone formal Common Criteria (CC) or FIDO Biometric certification.
> 2. **Statistical Small-N Sample:** While 135 benchmark sequences show 0.00% APCER and 0.00% BPCER, small-N test sets exhibit Clopper-Pearson 95% confidence bounds up to 3.45% overall and 11.57% per species. Commercial deployments require testing on corpora of $\ge 10,000$ presentations.
> 3. **3D Mask Limitations:** Monocular 2D webcams cannot perform hardware stereoscopic structured-light or Time-of-Flight (ToF) depth sensing. The implemented `computeMask3DScore` evaluates skin reflectance, micro-pore texture variance, and edge contour discontinuities. High-grade custom prosthetic masks may bypass 2D cues without infrared or structured-light sensors.
> 4. **Hardware Threat Boundary:** Browser clients cannot defend against rooted operating systems or hypervisor-level virtual camera drivers without hardware-backed native device attestation (e.g. Android KeyStore / MOSIP SBI).

---

## 📄 License & Attributions

Distributed under the **Apache License 2.0**. See [`LICENSE`](LICENSE) for details.
