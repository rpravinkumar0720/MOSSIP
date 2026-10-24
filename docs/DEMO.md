# MOSIP Face Liveness & Presentation Attack Detection (PAD) Demo Script

**Duration:** ~3 Minutes  
**Audience:** Hackathon Judges, Biometric Evaluators & MOSIP Architects  
**Prerequisites:** Laptop or smartphone with a modern web browser and webcam.

---

## 🚀 30-Second Quick Start

Start the server:
```bash
mvn package -DskipTests
java -jar liveness-server/target/liveness-server-1.0.0-SNAPSHOT.jar
```
Open **[http://localhost:8080](http://localhost:8080)** in Chrome, Edge, or Safari.

---

## 🎬 3-Minute Live Demonstration

### Act 1: Genuine Resident Authentication (Pass Path) — 45s
1. **Setup:**
   - Select **Resident Auth** or **Resident Enrollment** from the workflow dropdown in the top header.
   - Look directly into the webcam and align your face inside the central oval guide.
2. **What to Observe:**
   - **ISO 19794-5 Quality Gate:** The metrics bar in real-time shows Sharpness, Yaw/Pitch pose angles, and illumination score.
   - When aligned properly, the oval guide turns **glowing emerald green**.
   - **Passive PAD Progress:** The progress bar fills `0/5` to `5/5` as natural involuntary eyelid micro-motion and facial non-rigid motion are measured across consecutive frames in volatile RAM.
   - **Dynamic Challenge (if triggered):** If enrollment workflow requires an active challenge, a prompt appears (e.g., *"Please blink"* or *"Turn head slowly to your left"*). Complete the action within the timer.
3. **Outcome:**
   - Large green **ACCEPTED** modal appears: *"Face verification successful"*.
   - A unique 128-bit cryptographic session ID is stamped.
   - The best compliant ISO 19794-5 frame is cloned for downstream biometric matching; all intermediary frame buffers are immediately wiped from RAM.

---

### Act 2: Presentation Attack 1 — 2D Paper / Photo Attack — 45s
1. **Setup:**
   - Click **Start New Session**.
   - Hold up a printed passport photograph or a still portrait photo displayed on a smartphone in front of the camera.
2. **What Happens Behind the Scenes:**
   - `PassiveAnalyzer` detects:
     - **Micro-motion failure:** High rigid-body transform correlation (zero non-rigid facial residuals).
     - **Texture failure:** Flat color channel variance and unnatural paper/screen saturation.
     - **Bezel/Border check:** Straight line Hough detection framing the face rectangle.
3. **Anti-Adversarial Mandate in Action:**
   - The user-facing prompt and modal states **only**:  
     `"Face verification could not be completed. Please try again."`
   - *Notice:* The UI never leaks `"Print attack detected"`, preventing spoofers from gaining intelligence on detection boundaries.
   - The exact PAI classification `PRINT_ATTACK` is securely recorded in the internal cryptographic audit log.

---

### Act 3: Presentation Attack 2 — Screen Video Replay Attack — 45s
1. **Setup:**
   - Click **Start New Session**.
   - Play a video recording of a human face from a smartphone or tablet screen directly facing the webcam.
2. **What Happens Behind the Scenes:**
   - **2D FFT Moire Detector:** The pure Java Cooley-Tukey Radix-2 2D Fourier Transform analyzes a $64 \times 64$ patch and flags high-frequency periodic sampling spikes characteristic of screen subpixel matrices.
   - **Specular Highlight Ratio:** Rectangular glare from glass reflections triggers specular penalties.
   - **Random Active Challenge Protection:** Even if video moves, an active challenge (e.g. *TURN_RIGHT*) cannot be anticipated by a pre-recorded video stream. Any transition detected prior to challenge issuance timestamp is strictly rejected.
3. **Outcome:**
   - Session rejected with generic user guidance. Retries are tracked. After 3 retries, system enters policy fallback (`FALLBACK_SUPERVISOR_OVERRIDE` or `LOCKOUT`).

---

### Act 4: Cryptographic Audit Chain & Tamper Verification — 45s
1. **View Audit Trail:**
   - Click the **Audit Chain** tab in the right-hand panel (or click **Inspect Audit Trail** from the result modal).
   - A chronological table appears with events:
     - `SESSION_START`
     - `QUALITY_CHECK`
     - `PASSIVE_EVALUATED`
     - `CHALLENGE_ISSUED`
     - `FINAL_DECISION`
   - Each event contains a SHA-256 forward link (`previousHash` $\to$ `currentHash`).
   - Notice: **Zero biometric imagery or landmark arrays exist in the audit details** (strict privacy compliance).
2. **Verify Integrity:**
   - Click **Verify Chain**.
   - The system calls `POST /api/audit/verify` which recomputes every event's SHA-256 hash from canonical payload fields and verifies the chain link back to genesis.
   - A bright green badge confirms: `✓ Cryptographic Chain Valid (Tamper-Free)`.
3. **Demonstrate Tamper Detection (The Wow Factor):**
   - Click **Tamper Event**.
   - This intentionally modifies an event payload in the chain (e.g., changing `REJECTED` to `ACCEPTED`).
   - The chain verifier immediately catches the mismatch and displays a red badge:
     `🛡️ Tamper Detected! Chain Linkage Broken at Event #1`.

---

## 🛠️ Diagnostics & Mobile Web Demo Tips

- **Diagnostic Telemetry:**  
  Start with `--diagnostic` flag:
  ```bash
  java -jar liveness-server/target/liveness-server-1.0.0-SNAPSHOT.jar --diagnostic
  ```
  A hidden "Diagnostics" tab will unlock in the UI displaying live heuristic signal breakdowns and configured weights.
- **Mobile Smartphone Demo:**  
  Run `ngrok http 8080` or connect over local Wi-Fi HTTPS to demo directly on iOS Safari or Android Chrome with front-facing camera.
- **Language Toggle:**  
  Click the **EN / தமிழ்** button in the header at any time to toggle instant localized guidance in Tamil.
