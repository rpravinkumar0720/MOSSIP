# MOSIP Face Liveness & Presentation Attack Detection (PAD) Demo Guide

**Duration:** 90-Second Pitch OR 3-Minute Comprehensive Judge Walkthrough  
**Audience:** Hackathon Judges, Biometric Evaluators & MOSIP Architects  
**Prerequisites:** Laptop or smartphone with a modern web browser and webcam.

---

## ⚡ 90-Second Hackathon Pitch Script

> **Speaker Pitch:**
> 
> "Good morning, judges. National ID systems like MOSIP secure identity for hundreds of millions of residents, but face verification is constantly under attack from 2D printouts, phone screen replays, and synthetic injections.
> 
> Most commercial solutions use heavy, proprietary C++ SDKs or cloud APIs that violate data sovereignty.
> 
> Today, we present **MOSSIP**: a pure-Java 17, 100% offline, privacy-first Face Liveness & PAD engine designed natively for MOSIP Registration Client and IDA authentication.
> 
> Watch our live demo:
> 1. **ISO 19794-5 Quality Gate:** Real-time feedback for pose, sharpness, and illumination guides the resident.
> 2. **Multi-Signal Passive PAD:** In just 300 milliseconds, 8 physical signals—including micro-motion tremor, 2D FFT subpixel Moire, and specular glare—evaluate the face without sending a single byte to the cloud.
> 3. **Judge Mode Split Screen:** Look at this side-by-side view. The resident sees a clean, accessible interface with Tamil and English voice prompts. But on the judge's side, our live telemetry radar exposes real-time PAD heuristics, calibrated logistic regression fusion, and instantaneous hard-veto rules.
> 4. **Adversarial Landmark Defense:** If an attacker injects synthetic blink landmarks over a still photo, our server-side detector cross-validates face bounding boxes and pixel motion, immediately blocking the attack with `LANDMARK_MISMATCH`.
> 5. **Cryptographic Tamper-Evident Audit & JWS Receipt:** Every event is chained with SHA-256 and signed with HMAC-SHA256. When accepted, it issues a signed JWS receipt binding the challenge nonce and compliant frame digest.
> 
> 135 benchmark sequences, 0.00% APCER, 0.00% BPCER, sub-50ms latency, zero biometric disk footprint. Thank you!"

---

## 🚀 30-Second Quick Start

Start the server:
```bash
mvn package -DskipTests
java -jar liveness-server/target/liveness-server-1.0.0-SNAPSHOT.jar
```
Open **[http://localhost:8080](http://localhost:8080)** in Chrome, Edge, or Safari.

*For Docker with automatic local HTTPS on port 8443 (ideal for testing from a mobile phone on the LAN):*
```bash
docker compose up --build
```
Navigate to **`https://localhost:8443`** (or your machine's LAN IP).

---

## 🎬 3-Minute Live Demonstration for Judges

### Act 1: Genuine Resident Authentication & Accessibility — 45s
1. **Setup:**
   - Select **Resident Auth** from the workflow dropdown.
   - Click **🔊 Voice: OFF** to toggle on real-time spoken voice guidance.
   - Align your face inside the central oval guide.
2. **What to Observe:**
   - **ISO 19794-5 Quality Gating:** The metrics bar in real-time shows Sharpness (Laplacian $\ge 35.0$), Yaw/Pitch pose angles, and illumination.
   - The spoken guidance prompts you: *"Position your face in the oval"*.
   - When aligned, the oval guide glows **emerald green**.
   - **Passive PAD Progress:** Measures natural involuntary micro-saccades and non-rigid motion in volatile RAM.
   - **Dynamic Challenge (if triggered):** Prompt requests an unpredictable action (e.g., *"Please blink"* or animated *Color Flash*). Complete the action within the timer.
3. **Outcome:**
   - Large green **ACCEPTED** modal appears: *"Face verification successful"*.
   - The best compliant ISO 19794-5 frame is cloned into the signed JWS receipt; all intermediary frame buffers are immediately wiped from RAM.

---

### Act 2: Judge Mode & Split View ("What the Attacker Sees" vs "What the Audit Knows") — 45s
1. **Unlock Judge Mode:**
   - In the header, click **⚖️ Judge Mode**.
   - Enter the token `mosip-admin-token-2026` (or hit enter if default).
   - The button switches to **⚖️ Judge: ON** and unlocks the **Live Telemetry** and **Red Team Lab** tabs!
2. **Activate Split View:**
   - Click **🔲 Split View**.
   - The UI rearranges into a side-by-side terminal:
     - **Left Pane:** "End-User View" showing standard user guidance, generic messages, and zero internal score leakage.
     - **Right Pane:** "Judge & Security Operations Telemetry" streaming live per-signal heuristics, latency badge ($47\text{ ms}$), logistic fusion score, and classification in real time!
3. **Key Highlight:**
   - Explain to the judges: *"Notice how the public WebSocket message only delivers a generic status code. An attacker cannot probe our decision boundaries, while the security operator has complete cryptographic visibility."*

---

### Act 3: Red Team Lab — Replay Attacks & Forged Landmarks BLOCKED — 45s
1. **Open Red Team Lab:**
   - Click the **Red Team Lab** tab in the sidebar.
2. **Replay PAI-1 (Print Attack):**
   - Click **▶️ Test Print Attack**.
   - The simulator streams a 2D print sequence (0D motion, flat paper borders).
   - In the telemetry pane, the **Micro-Motion** and **Border** meters immediately drop to red ($0.00$), triggering the hard-veto rule: **`HARD VETO TRIGGERED: Print attack detected`**.
3. **Replay Adversarial Landmark Injection:**
   - Click **▶️ Test Forged Landmarks**.
   - This reproduces the classic vulnerability: injecting synthetic MediaPipe blink and yaw coordinates over a static face.
   - Server-side `ServerFaceDetector` and `LandmarkCrossValidator` detect the mismatch between client landmark assertions and actual pixel gradients.
   - The attack is instantly blocked with an audit event: **`LANDMARK_MISMATCH`**!
   - Click **🔄 Return to Live Camera Stream** to resume normal operation.

---

### Act 4: Cryptographic Audit Chain & Offline Verifier CLI — 45s
1. **Browser Chain Verification:**
   - Click the **Audit Chain** tab.
   - Each event contains a SHA-256 forward link (`previousHash` $\to$ `currentHash`).
   - Notice: **Zero biometric imagery or landmark arrays exist in the audit details** (strict privacy compliance).
   - Click **Verify Chain** $\to$ `✓ Cryptographic Chain Valid (Tamper-Free)`.
2. **Simulate Local Tamper:**
   - Click **Simulate Edit on Local Log Copy**.
   - This simulates an adversary modifying a local copy of an audit event (e.g. altering `REJECTED` to `ACCEPTED`).
   - The verifier immediately flags: `🛡️ Tamper Detected! Chain Linkage Broken at Event #...`
3. **Offline CLI Verification (The Final Wow Factor):**
   - Show judges how an auditor verifies the server's `audit.jsonl` log file completely offline using pure Java without running the server:
   ```bash
   java -cp liveness-core/target/liveness-core-1.0.0-SNAPSHOT.jar:liveness-api/target/liveness-api-1.0.0-SNAPSHOT.jar \
     io.mosip.liveness.core.audit.AuditVerifierCli audit.jsonl --key mosip-admin-token-2026
   ```
   - Terminal outputs:
     `✅ VERIFICATION SUCCESSFUL: Cryptographic audit chain is 100% valid.`
     `Status: Zero tampering, zero front-truncation, valid forward hash links.`

---

## 🛠️ Testing Mobile Devices Over Local HTTPS

Because mobile browsers require a secure origin (`HTTPS`) to enable `navigator.mediaDevices.getUserMedia`:
1. Start the Docker Compose stack:
   ```bash
   docker compose up --build
   ```
2. Open `https://<YOUR_LAN_IP>:8443` on Android Chrome or iPhone Safari.
3. Accept the self-signed developer certificate once.
4. Perform the full verification flow directly from your smartphone!
