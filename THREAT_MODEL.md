# Threat Model & Security Architecture

**System:** MOSIP Biometric Face Liveness & Presentation Attack Detection (PAD)  
**Standard Compliance:** ISO/IEC 30107-1 (Framework), ISO/IEC 30107-3 (Testing & Reporting), ISO/IEC 19794-5 (Face Quality)  
**Architecture Tier:** Pure-Java Core Engine + Javalin 6 WebSocket Server + HTML5/MediaPipe Web Client  

---

## 1. Threat Matrix & Defense Coverage

| Threat Vector | Attack Instrument / Species | Risk Level | Defense Mechanism | Coverage Status |
| :--- | :--- | :--- | :--- | :--- |
| **PAI-1: Print Attack** | Matte/gloss paper photos, passport cutouts | **HIGH** | Procrustes landmark jitter residual, specular highlight ratio, Hough rectangular border line accumulator, hard-veto logistic fusion. | **COVERED** |
| **PAI-2a: Mobile Screen Replay** | OLED smartphone display playback | **CRITICAL** | 2D FFT Moire subpixel frequency spike, specular reflection hotspot, inter-frame timing variance, Color Flash challenge reflection. | **COVERED** |
| **PAI-2b: Tablet / Monitor Replay** | LCD tablet/monitor screen playback | **HIGH** | Moire grid detection, margin bezel boundary detection, animated challenge token echo binding. | **COVERED** |
| **PAI-3: 3D Mask Attack** | Latex, silicone, resin prosthetic masks | **MEDIUM** | Pore texture variance, RGB color saturation statistics, perimeter contour discontinuity (`computeMask3DScore`). | **PARTIALLY COVERED** *(1)* |
| **Video Injection** | Virtual camera / direct frame streaming | **CRITICAL** | Cryptographic 128-bit session nonce, dynamic challenge token echo, monotonic sequence and timestamp verification, frame arrival jitter analysis. | **COVERED** |
| **Client Landmark Forgery** | Injected MediaPipe synthetic landmarks | **CRITICAL** | `ServerFaceDetector` independent Haar/skin pixel detection, `LandmarkCrossValidator` (IoU $\ge 0.40$, pupil distance $\le 0.20$, pose delta $\le 18^\circ$), pixel-level blink corroboration. | **COVERED** |
| **Adversarial Probing** | Automated oracle attacks to map score thresholds | **HIGH** | Public WebSocket payloads return generic enum (`REJECTED`) with zero score/class metadata; `/api/audit*` gated by constant-time `LIVENESS_ADMIN_TOKEN`. | **COVERED** |
| **Lockout Bypass** | Disconnecting socket to reset retry counter | **HIGH** | Rate limiter and lockout budget keyed by persistent client identity token claim + IP; enforced across reconnections. | **COVERED** |
| **Audit Log Tampering** | Altering audit trails, front-truncation, rewriting | **CRITICAL** | Forward SHA-256 hash chaining of canonical JSON details, strict `GENESIS_HASH` root check, per-session verification, terminal HMAC-SHA256 signatures, `AuditVerifierCli`. | **COVERED** |
| **Decompression Bomb** | Giant JPEG payload designed to exhaust JVM RAM | **HIGH** | `ImageReader` header dimension inspection before raster decompression ($\le 1280\times 1280$), 1 MB WebSocket frame payload cap. | **COVERED** |
| **Rooted OS / Kernel Tampering** | Kernel-level sensor tampering, hypervisor camera hook | **CRITICAL** | Out of scope for pure browser JavaScript. Requires hardware-backed attestation (Android KeyStore / MOSIP SBI native SDK). | **NOT COVERED** *(2)* |

---

## 2. Threat Boundary Clarifications

### (1) 3D Mask Scope
Monocular 2D webcams receive 2D RGB projection of light. The pure-Java heuristic `computeMask3DScore` inspects micro-pore frequency distribution, skin pigmentation saturation, and facial boundary gradient discontinuities. While effective against rigid masks and commercial mannequin presentations, high-grade cinematic silicone prosthetics fitted directly to human skin cannot be reliably distinguished without hardware infrared (IR) or Time-of-Flight (ToF) structured light sensors.

### (2) Browser vs Native Device Security Boundary
A web application executing in standard browser sandboxes (`getUserMedia`) operates under the assumption that the underlying browser process is authentic. If the host operating system is compromised (rooted Android, jailbroken iOS, or custom OS kernel with virtual camera loopbacks), a native attacker can intercept camera memory buffers before they reach the browser engine. Full resistance against rooted environments requires an attested MOSIP Secure Biometric Interface (SBI) with hardware key attestation.

---

## 3. Cryptographic Invariants

1. **Deterministic Event Digest:**
   $$\text{Hash}_i = \text{SHA-256}(\text{Hash}_{i-1} \parallel \text{EventId} \parallel \text{SessionId} \parallel \text{Timestamp} \parallel \text{Type} \parallel \text{Decision} \parallel \text{Scores} \parallel \text{CanonicalDetails})$$
2. **First Event Invariant:**
   $$\text{Hash}_0.\text{previousHash} == 0000000000000000000000000000000000000000000000000000000000000000$$
3. **Session Receipt Invariant:**
   $$\text{Receipt} = \text{JWS}(\text{Header}_{\text{HS256}} \parallel \text{Payload}(\text{SessionId}, \text{Nonce}, \text{Decision}, \text{PolicyHash}, \text{BestFrameSha256}, \text{Exp}))$$
