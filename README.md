# MOSIP Biometric Face Liveness & Presentation Attack Detection (PAD)

A production-ready, demo-ready biometric face liveness detection and Presentation Attack Detection (PAD) subsystem designed for **MOSIP (Modular Open Source Identity Platform)**. Built on top of the `liveness-api` specification for Java 17.

[![Java CI](https://github.com/rpravinkumar0720/MOSSIP/actions/workflows/ci.yml/badge.svg)](https://github.com/rpravinkumar0720/MOSSIP/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Standards](https://img.shields.io/badge/Standards-ISO%2FIEC%2019794--5%20%7C%20ISO%2030107-green.svg)]()

---

## 📌 Problem Statement

National ID systems and biometric authentications are vulnerable to Presentation Attacks (PAIs) including **printed 2D photographs**, **digital screen replays**, and **3D masks**. Traditional solutions often rely on heavy, proprietary, native C++ SDKs or closed cloud APIs that violate data sovereignty and in-memory privacy mandates.

This repository implements an open-source, modular, pure-Java engine featuring:
1. **ISO/IEC 19794-5 Quality Gating:** Pure geometric and pixel evaluation (sharpness, pose, illumination, eye/mouth status) before biometric processing.
2. **Multi-Signal Passive PAD:** 7 independent physical and geometric heuristic signals operational with zero external machine learning models.
3. **Dynamic Unpredictable Active Challenges:** Dynamic action verification (blink, smile, head turns, tilt) bound to a 128-bit cryptographic session nonce to defeat pre-recorded videos.
4. **Anti-Adversarial Information Containment:** Attack classifications are withheld from client-facing messages to prevent spoofers from gaining intelligence.
5. **Cryptographic SHA-256 Audit Chaining:** Forward-linked tamper-evident event log strictly omitting raw images or biometric templates.

---

## 🏛️ System Architecture

```
                  +-------------------------------------------------------------+
                  |                      CLIENT (Browser)                       |
                  |  - getUserMedia (Webcam 640x480)                            |
                  |  - MediaPipe Tasks Vision FaceLandmarker JS (~10-15 FPS)    |
                  |  - Mirrored Oval Visual Guide & Real-Time Guidance           |
                  +-------------------------------------------------------------+
                                       |                 ^
                                       | Frame + Lms     | JSON Events
                                       | (WebSocket)     | (State, Prompt, Decision)
                                       v                 |
+---------------------------------------------------------------------------------------------------+
|                                  MOSIP LIVENESS SERVER (Javalin 6)                                |
|  - Rate limiter (15 FPS), 1 MB frame size guard, Base64 JPEG to RGB conversion                   |
|  - REST endpoints: /api/health, /api/audit/{id}, /api/audit/verify, /api/policy/{wf}              |
+---------------------------------------------------------------------------------------------------+
                                       |
                                       v
+---------------------------------------------------------------------------------------------------+
|                                      LIVENESS CORE ENGINE                                         |
|                                                                                                   |
|   [Stage 1: ISO 19794-5 QualityEvaluator]                                                         |
|   -> Checks Area Ratio (0.15..0.75), Pose (|Yaw|,|Pitch|,|Roll| <= 15°), Luma, Blur (Laplacian)   |
|                                       | Compliant (3 frames)                                      |
|                                       v                                                           |
|   [Stage 2: PassiveAnalyzer (Sliding Window)]                                                     |
|   -> 7 Heuristic Signals: Micro-motion residual, 2D FFT Moire, Specular Glare,                    |
|      Color stats, Border/Bezel Sobel check, Timing integrity, Skin consistency                   |
|   -> Optional: ONNX Runtime Anti-Spoofing Model (fallback to pure heuristics if absent)          |
|                                       | Inconclusive / Enrollment Policy                          |
|                                       v                                                           |
|   [Stage 3: ChallengeVerifier (Dynamic Challenges)]                                               |
|   -> Unpredictable Challenge: BLINK, SMILE, TURN_LEFT, TURN_RIGHT, LOOK_UP, NOD_HEAD              |
|   -> Cryptographic Nonce Binding + Server-Clock Monotonic Expiration                              |
|   -> Identity Continuity: Landmark ratio signature drift comparison (anti-swap)                   |
|                                       |                                                           |
|                                       v                                                           |
|   [Stage 4: Decision & SHA-256 Hash-Chained AuditSink]                                            |
|   -> ACCEPTED (best compliant frame cloned; intermediate frames wiped)                            |
|   -> REJECTED ("Face verification could not be completed. Please try again.")                     |
+---------------------------------------------------------------------------------------------------+
```

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

*Alternatively with Docker:*
```bash
docker build -t mosip-liveness .
docker run -p 8080:8080 mosip-liveness
```

---

## 📦 Multi-Module Organization

| Module | Description | Dependencies |
| :--- | :--- | :--- |
| **`liveness-api`** | Standard interfaces, policies, records, and audit definitions | Zero external dependencies (pure Java 17) |
| **`liveness-core`** | QualityEvaluator, 7-signal PassiveAnalyzer, ChallengeVerifier, DefaultLivenessEngine | `liveness-api`, `slf4j-api`, optional `onnxruntime` |
| **`liveness-device-mock`** | `ImageSequenceDevice` simulating camera streams from directories or synthetic frames | `liveness-api`, `liveness-core` |
| **`liveness-server`** | Javalin 6 WebSocket/REST backend & embedded static HTML5/MediaPipe client | `liveness-core`, `javalin`, `jackson`, `logback` |
| **`liveness-eval`** | CLI benchmark tool evaluating APCER and BPCER against labeled presentation folders | `liveness-core`, `liveness-device-mock` |

---

## 🔒 Security & Privacy Guarantees

- **RAM Frame Wiping:** Frames are processed exclusively in volatile memory. `FaceFrame.wipe()` zeroes internal pixel buffers immediately after analysis.
- **Zero Disk Biometrics:** The server never writes raw images or embeddings to disk.
- **Cryptographic Audit Log:** Every audit event is chained with a SHA-256 digest (`previousHash` $\to$ `currentHash`). Audits record timestamps, scores, and decisions, with zero biometric data.
- **Anti-Adversarial Messages:** User prompts for any detected attack are strictly normalized to:
  `"Face verification could not be completed. Please try again."`
  PAI categories (`PRINT_ATTACK`, `SCREEN_REPLAY`, `VIDEO_INJECTION`) exist exclusively in internal audit trails and `--diagnostic` mode.

---

## 📊 Benchmark Evaluation CLI (`liveness-eval`)

The `liveness-eval` CLI evaluates APCER (Attack Presentation Classification Error Rate) and BPCER (Bona Fide Presentation Classification Error Rate) against labeled directories:

```bash
java -jar liveness-eval/target/liveness-eval-1.0.0-SNAPSHOT.jar \
  --genuine datasets/genuine \
  --print datasets/print_attacks \
  --replay datasets/replay_attacks \
  --out evaluation-report.md
```

Evaluation datasets can be created directly from the browser using the built-in collector page:  
**[http://localhost:8080/collect.html](http://localhost:8080/collect.html)**

---

## ⚠️ Limitations & Honesty Statement

> [!WARNING]
> 1. **Baseline Heuristic PAD:** The passive presentation attack detection implemented in `PassiveAnalyzer` is an open-source heuristic baseline (non-rigid motion estimation, 2D FFT moire detection, specular highlights, color statistics, and edge detection). While designed around ISO/IEC 30107 principles, **it does not constitute a certified ISO 30107-3 Level 1/2 biometric solution on its own**.
> 2. **Statistical Significance:** Demonstrations on small datasets (< 1,000 presentations) produce wide statistical confidence intervals. Certified deployments require independent testing against extensive biometric corpora.
> 3. **Production Model Integration:** Deployers operating in high-threat environments can seamlessly plug in a certified ONNX anti-spoofing model using the `--model <path.onnx>` flag or `LIVENESS_MODEL_PATH` environment variable. The engine blends heuristic and neural inference scores when configured.

---

## 📄 License & Attributions

Distributed under the **Apache License 2.0**. See [`LICENSE`](LICENSE) for details.

### Third-Party Software & Models:
- **[Google MediaPipe](https://github.com/google/mediapipe):** Client-side real-time face landmark mesh detection (Apache 2.0).
- **[Javalin](https://javalin.io):** Lightweight Java 17 web framework (Apache 2.0).
- **[ONNX Runtime](https://onnxruntime.ai):** High-performance neural network execution engine (MIT).
- **[Eclipse Temurin](https://adoptium.net):** OpenJDK runtime distribution.
