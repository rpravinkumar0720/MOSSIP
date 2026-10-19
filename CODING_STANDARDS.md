# MOSIP Liveness & Presentation Attack Detection (PAD) - Coding Standards & Architecture Rules

## 1. Architectural Integrity & Multi-Platform Portability

### Pure Java Core Rule
- The `liveness-api` and `liveness-core` modules must remain **pure Java (Java 17)** with **zero** UI or platform-specific imports.
- **Prohibited in API and Core:**
  - `javafx.*`
  - `android.*`
  - `org.bytedeco.*` (or unmanaged JNI bindings without pure fallbacks)
- The same identical bytecode runs on:
  - **Desktop:** MOSIP Registration Client (JavaFX / OpenJDK)
  - **Android:** Registration Client Tablet / Mobile (Android ART / Flutter via Platform Channels)
- Behavior, thresholds, state machines, and decision logic must be **identical** across Desktop and Android.

---

## 2. Security and Biometric Privacy Mandates

### In-Memory Processing & Mandatory Wiping
- All face frame buffers (`FaceFrame`) must be processed strictly in volatile RAM.
- No temporary unencrypted biometric images or face crops may be written to disk, flash storage, or persistent queues.
- As soon as a frame pipeline analysis completes, `FaceFrame.wipe()` must be called to overwrite pixel memory with zeros (`Arrays.fill(data, (byte) 0)`).

### Tamper-Evident Audit Logging
- Every verification session emits structured audit records (`LivenessAuditEvent`).
- Events are cryptographically linked using SHA-256 hash chaining:
  $$\text{currentHash} = \text{SHA256}(\text{previousHash} \parallel \text{payload})$$
- **Strict Prohibition:** Never log raw images, feature embeddings, or biometric template arrays in audit logs. Log only session identifiers, timestamps, numerical scores, challenge types, error codes, and hashes.

### Anti-Adversarial User Feedback
- When Presentation Attack Detection (PAD) classifies a presentation as an attack (photo print, screen playback, replay, 3D mask):
  - **User Message:** MUST display only the generic error: `"Face verification could not be completed. Please try again."`
  - **Diagnostic/Audit Log:** Logs the specific PAI classification (`PRINT_ATTACK`, `SCREEN_REPLAY`, `VIDEO_INJECTION`, etc.) and heuristic metrics.
  - Reason: Providing specific feedback ("Screen glare detected") assists adversaries in adjusting their spoof instruments.

### Session Anti-Replay
- Every verification session generates a cryptographically secure 128-bit nonce (`SecureRandom`).
- Active challenges and audit records bind directly to this nonce and strict per-challenge monotonic timeouts.

---

## 3. Standards Compliance

### ISO/IEC 30107 (Biometric Presentation Attack Detection)
- **ISO/IEC 30107-1 (Framework):** Distinguishes bona fide presentations from presentation attack instruments (PAIs).
- **ISO/IEC 30107-3 (Testing and Reporting):**
  - **APCER (Attack Presentation Classification Error Rate):** Proportion of presentation attack presentations incorrectly classified as bona fide presentations (target $\le 1.0\%$).
  - **BPCER (Bona Fide Presentation Classification Error Rate):** Proportion of bona fide presentations incorrectly classified as presentation attacks (target $\le 3.0\%$).
  - Testing suite must evaluate attacks across print, display/screen, video replay, and 3D mask categories.

### ISO/IEC 19794-5 (Biometric Data Interchange Formats - Face Image Data)
- Face positioning gate checks:
  - Face dimensions: bounding box occupies between 15% and 75% of frame area.
  - Pose tolerances: Yaw $\le 15^\circ$, Pitch $\le 15^\circ$, Roll $\le 15^\circ$.
  - Illumination: average luma between 0.20 and 0.85; absence of specular hotspots.
  - Sharpness: Laplacian variance $\ge 80.0$ to filter motion blur.
  - Expression: neutral expression with mouth closed and both eyes open.

---

## 4. AI Models & Dataset Licensing Registry

| Model / Dataset | Component | Source / Architecture | License | Suitability / Compliance |
| :--- | :--- | :--- | :--- | :--- |
| **UltraFace (version-RFB-320)** | Face Detection | Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB | **MIT License** | Open-source, commercial/academic use permitted. Fast (<10ms), bundled ONNX. |
| **MiniFASNet (Silent-Face-Anti-Spoofing)** | Passive Anti-Spoofing | Minivision Silent-Face-Anti-Spoofing | **Apache 2.0 License** | Permissive open-source license. High-accuracy multi-scale anti-spoofing model. |
| **MediaPipe Face Mesh / 68-pt Landmark** | Action & Landmark Extraction | Google MediaPipe / InsightFace Landmark | **Apache 2.0 License** | Permissive open-source license. Fast EAR, MAR, and head-pose calculation. |
| **CASIA-FASD / Replay-Attack** | PAD Test Bench | Idiap / CASIA | **Research / Academic License** | Used exclusively for offline benchmark evaluation and APCER/BPCER test reports. |

---

## 5. Coding Conventions

- **Java Version:** Java 17 LTS (configured with `<release>17</release>` in Maven).
- **Immutability:** Domain models, results, and policies must use immutable Java records or unmodifiable defensive copies.
- **Null Safety:** Public APIs must use `Objects.requireNonNull(...)` or `@NonNull` annotations.
- **Error Handling:** All error paths map to `LivenessError` enum codes (`LIV_ERR_001` through `LIV_ERR_015`). Never swallow exceptions without logging or rethrowing as `LivenessException`.
- **Test Coverage:** All business logic, parsers, classifiers, and state transitions must have unit tests with AssertJ and JUnit 5.
