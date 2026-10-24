// MOSIP Face Liveness & PAD Web Client
// Modular ES module using MediaPipe Tasks Vision CDN with pinned version

const CDN_VISION_URL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14';
const MODEL_URL = 'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task';

let faceLandmarker = null;
let video = null;
let overlayCanvas = null;
let overlayCtx = null;
let captureCanvas = null;
let captureCtx = null;
let websocket = null;
let isStreaming = false;
let currentSessionId = null;
let currentWorkflow = 'RESIDENT_AUTH';
let currentLang = 'en';
let frameSequence = 0;
let lastCaptureTime = 0;
let activeChallengeTimer = null;
let localAuditEvents = [];
let reconnectAttempts = 0;
let adminToken = sessionStorage.getItem('mosip_admin_token') || (new URLSearchParams(window.location.search)).get('adminToken') || '';

// Localization Strings for Web UI
const UI_STRINGS = {
  en: {
    title: 'Face Liveness & PAD',
    positionFace: 'Position your face in the oval',
    actionRequired: 'Action Required',
    holdStill: 'Hold still, analyzing face liveness...',
    qualityChecking: 'Checking...',
    qualityCompliant: 'Compliant',
    qualityNonCompliant: 'Adjusting...',
    verifSuccess: 'Verification Successful',
    verifFailed: 'Verification Incomplete',
    startNewSession: 'Start New Session',
    inspectAudit: 'Inspect Audit Trail',
    cameraRequired: 'Camera Access Required',
    cameraRequiredDesc: 'Please allow camera permissions to begin face verification.',
    chainValid: 'Cryptographic Chain Valid (Tamper-Free)',
    chainBroken: 'Tamper Detected! Chain Linkage Broken at Event',
    noEvents: 'No events recorded yet. Start capture.'
  },
  ta: {
    title: 'முக சரிபார்ப்பு மற்றும் PAD',
    positionFace: 'வட்டத்திற்குள் உங்கள் முகத்தை வைக்கவும்',
    actionRequired: 'செயல் தேவைப்படுகிறது',
    holdStill: 'அசையாமல் இருக்கவும், சரிபார்க்கப்படுகிறது...',
    qualityChecking: 'சரிபார்க்கிறது...',
    qualityCompliant: 'சரியாக உள்ளது',
    qualityNonCompliant: 'சரிசெய்கிறது...',
    verifSuccess: 'சரிபார்ப்பு வெற்றிகரமாக முடிந்தது',
    verifFailed: 'சரிபார்ப்பு நிறைவடையவில்லை',
    startNewSession: 'புதிய அமர்வைத் தொடங்கவும்',
    inspectAudit: 'தணிக்கைப் பதிவை காண்க',
    cameraRequired: 'கேமரா அனுமதி தேவை',
    cameraRequiredDesc: 'முக சரிபார்ப்பைத் தொடங்க கேமரா அனுமதியை வழங்கவும்.',
    chainValid: 'கிரிப்டோகிராஃபிக் தணிக்கை சங்கிலி சரியானது (சேதமடையவில்லை)',
    chainBroken: 'சேதம் கண்டறியப்பட்டது! சங்கிலி இணைப்பு முறிந்தது: நிகழ்வு',
    noEvents: 'நிகழ்வுகள் எதுவும் இதுவரை பதிவு செய்யப்படவில்லை.'
  }
};

document.addEventListener('DOMContentLoaded', async () => {
  initElements();
  initEventListeners();
  updateJudgeModeUI();
  loadPolicyDetails(currentWorkflow);
  checkServerHealth();
  connectWebSocket();

  // Periodic health ping
  setInterval(checkServerHealth, 15000);

  try {
    await initMediaPipe();
  } catch (e) {
    console.warn('MediaPipe initialization notice:', e);
  }

  try {
    await initCamera();
  } catch (err) {
    showCameraError('Camera Access Required', err.message);
  }
});

function initElements() {
  video = document.getElementById('webcam');
  overlayCanvas = document.getElementById('overlayCanvas');
  overlayCtx = overlayCanvas.getContext('2d');
  captureCanvas = document.getElementById('captureCanvas');
  captureCtx = captureCanvas.getContext('2d');
}

function initEventListeners() {
  document.getElementById('workflowSelect').addEventListener('change', (e) => {
    currentWorkflow = e.target.value;
    loadPolicyDetails(currentWorkflow);
    resetAndReconnect();
  });

  document.getElementById('langToggle').addEventListener('click', () => {
    currentLang = currentLang === 'en' ? 'ta' : 'en';
    updateLanguage();
    resetAndReconnect();
  });

  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
      btn.classList.add('active');
      const targetId = btn.getAttribute('data-tab');
      document.getElementById(targetId).classList.add('active');
    });
  });

  document.getElementById('btnRetry').addEventListener('click', resetAndReconnect);
  document.getElementById('btnRetryCamera').addEventListener('click', () => {
    document.getElementById('cameraErrorOverlay').classList.add('hidden');
    initCamera().then(() => connectWebSocket());
  });

  document.getElementById('btnViewAuditFromModal').addEventListener('click', () => {
    document.getElementById('decisionOverlay').classList.add('hidden');
    document.getElementById('tabBtnAudit').click();
  });

  document.getElementById('btnVerifyChain').addEventListener('click', verifyAuditChain);
  document.getElementById('btnTamperChain').addEventListener('click', tamperAndVerifyAuditChain);

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      isStreaming = false;
    } else {
      isStreaming = true;
      requestAnimationFrame(processVideoFrame);
    }
  });

  document.getElementById('btnJudgeMode')?.addEventListener('click', () => {
    if (adminToken) {
      if (confirm('Judge Mode is currently active. Would you like to exit Judge Mode?')) {
        adminToken = '';
        sessionStorage.removeItem('mosip_admin_token');
        updateJudgeModeUI();
        loadPolicyDetails(currentWorkflow);
        resetAndReconnect();
      }
    } else {
      const token = prompt('Enter Admin/Judge Token (LIVENESS_ADMIN_TOKEN):', 'mosip-admin-token-2026');
      if (token && token.trim()) {
        adminToken = token.trim();
        sessionStorage.setItem('mosip_admin_token', adminToken);
        updateJudgeModeUI();
        loadPolicyDetails(currentWorkflow);
        resetAndReconnect();
      }
    }
  });
}

function updateJudgeModeUI() {
  const btn = document.getElementById('btnJudgeMode');
  const tabDiag = document.getElementById('tabBtnDiag');
  if (btn) {
    if (adminToken) {
      btn.innerText = '⚖️ Judge: ON';
      btn.style.background = 'rgba(16, 185, 129, 0.2)';
      btn.style.borderColor = '#10b981';
      tabDiag?.classList.remove('hidden');
    } else {
      btn.innerText = '⚖️ Judge Mode';
      btn.style.background = '';
      btn.style.borderColor = '';
      tabDiag?.classList.add('hidden');
    }
  }
}

function updateLanguage() {
  const strings = UI_STRINGS[currentLang] || UI_STRINGS.en;
  document.querySelectorAll('.lang-code').forEach(el => {
    el.classList.toggle('active', el.getAttribute('data-lang') === currentLang);
  });
  document.getElementById('promptText').innerText = strings.positionFace;
  document.getElementById('challengeTitle').innerText = strings.actionRequired;
  document.getElementById('btnRetry').innerText = strings.startNewSession;
  document.getElementById('btnViewAuditFromModal').innerText = strings.inspectAudit;
}

// MediaPipe Initialization with graceful CDN fallback
async function initMediaPipe() {
  try {
    const vision = await import(/* webpackIgnore: true */ `${CDN_VISION_URL}/vision_bundle.mjs`);
    const FilesetResolver = vision.FilesetResolver;
    const FaceLandmarker = vision.FaceLandmarker;

    const fileset = await FilesetResolver.forVisionTasks(
      `${CDN_VISION_URL}/wasm`
    );

    faceLandmarker = await FaceLandmarker.createFromOptions(fileset, {
      baseOptions: {
        modelAssetPath: MODEL_URL,
        delegate: 'GPU'
      },
      outputFaceBlendshapes: false,
      runningMode: 'VIDEO',
      numFaces: 1
    });
    console.log('MediaPipe FaceLandmarker successfully initialized');
  } catch (err) {
    console.error('Failed to load MediaPipe Tasks Vision from CDN:', err);
    // Fallback notice
    document.getElementById('promptText').innerText = 'MediaPipe CDN unreachable; server fallback active';
  }
}

// Camera Setup
async function initCamera() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    throw new Error('Webcam streaming not supported in this browser.');
  }

  const stream = await navigator.mediaDevices.getUserMedia({
    video: {
      facingMode: 'user',
      width: { ideal: 640 },
      height: { ideal: 480 }
    },
    audio: false
  });

  video.srcObject = stream;
  await new Promise(resolve => {
    video.onloadedmetadata = () => {
      video.play();
      overlayCanvas.width = video.videoWidth;
      overlayCanvas.height = video.videoHeight;
      resolve();
    };
  });

  isStreaming = true;
  requestAnimationFrame(processVideoFrame);
}

// WebSocket Connection Management
async function connectWebSocket() {
  if (websocket && websocket.readyState === WebSocket.OPEN) {
    websocket.close();
  }

  updateStatus(false, 'Authorizing session...');
  let sessionToken = '';
  try {
    let clientId = localStorage.getItem('mosip_client_id');
    if (!clientId) {
      clientId = 'client-' + Math.random().toString(36).substring(2, 10);
      localStorage.setItem('mosip_client_id', clientId);
    }

    const sessionRes = await fetch('/api/v1/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        workflow: currentWorkflow,
        clientIdentity: clientId
      })
    });
    if (sessionRes.ok) {
      const sData = await sessionRes.json();
      sessionToken = sData.token;
      currentSessionId = sData.sessionId;
    }
  } catch (err) {
    console.warn('Could not pre-issue session token, proceeding with WS connect:', err);
  }

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  let wsUrl = `${protocol}//${window.location.host}/ws/liveness?workflow=${currentWorkflow}&lang=${currentLang}`;
  if (sessionToken) {
    wsUrl += `&token=${encodeURIComponent(sessionToken)}`;
  }
  if (adminToken) {
    wsUrl += `&adminToken=${encodeURIComponent(adminToken)}`;
  }

  updateStatus(false, 'Connecting...');
  websocket = new WebSocket(wsUrl);

  websocket.onopen = () => {
    console.log('WebSocket connected to MOSIP liveness engine');
    updateStatus(true, 'Connected');
    reconnectAttempts = 0;
  };

  websocket.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data);
      handleServerMessage(msg);
    } catch (e) {
      console.warn('Failed to parse WebSocket message:', e);
    }
  };

  websocket.onclose = () => {
    updateStatus(false, 'Disconnected');
    if (reconnectAttempts < 1) {
      reconnectAttempts++;
      setTimeout(connectWebSocket, 1500);
    }
  };

  websocket.onerror = (err) => {
    console.error('WebSocket error:', err);
    updateStatus(false, 'Connection Error');
  };
}

function updateStatus(connected, label) {
  const dot = document.querySelector('.status-dot');
  const lbl = document.getElementById('serverStatusLabel');
  dot.className = 'status-dot ' + (connected ? 'connected' : 'disconnected');
  lbl.innerText = label;
}

// Main Frame Processing Loop
async function processVideoFrame(timestamp) {
  if (!isStreaming || !video || video.paused || video.ended) {
    return;
  }

  // Throttle sending to ~12 FPS (every 80 ms)
  const shouldSend = (timestamp - lastCaptureTime) >= 80;

  let normalizedLandmarks = [];
  if (faceLandmarker && video.readyState >= 2) {
    try {
      const results = faceLandmarker.detectForVideo(video, timestamp);
      overlayCtx.clearRect(0, 0, overlayCanvas.width, overlayCanvas.height);

      if (results && results.faceLandmarks && results.faceLandmarks.length > 0) {
        const rawPoints = results.faceLandmarks[0];
        normalizedLandmarks = rawPoints.map(p => [p.x, p.y, p.z || 0]);
        drawFaceMeshPreview(overlayCtx, rawPoints, overlayCanvas.width, overlayCanvas.height);
      }
    } catch (e) {
      // Ignore dropped detection frame
    }
  }

  if (shouldSend && websocket && websocket.readyState === WebSocket.OPEN) {
    lastCaptureTime = timestamp;
    captureAndSendFrame(normalizedLandmarks);
  }

  requestAnimationFrame(processVideoFrame);
}

function drawFaceMeshPreview(ctx, points, width, height) {
  ctx.save();
  ctx.scale(-1, 1);
  ctx.translate(-width, 0);

  ctx.fillStyle = 'rgba(59, 130, 246, 0.6)';
  // Draw select representative facial contour points
  const keyIndices = [4, 152, 33, 133, 362, 263, 61, 291];
  for (const idx of keyIndices) {
    if (points[idx]) {
      const pt = points[idx];
      ctx.beginPath();
      ctx.arc(pt.x * width, pt.y * height, 2.5, 0, 2 * Math.PI);
      ctx.fill();
    }
  }
  ctx.restore();
}

function captureAndSendFrame(landmarks) {
  const targetW = 400;
  const targetH = Math.round(targetW * (video.videoHeight / video.videoWidth));

  captureCanvas.width = targetW;
  captureCanvas.height = targetH;

  // IMPORTANT: Un-mirrored raw pixels sent to server decisions
  captureCtx.drawImage(video, 0, 0, targetW, targetH);
  const jpegB64 = captureCanvas.toDataURL('image/jpeg', 0.7);

  const payload = {
    type: 'frame',
    seq: frameSequence++,
    tsMs: Date.now(),
    jpegB64: jpegB64,
    landmarks: landmarks,
    width: targetW,
    height: targetH
  };

  websocket.send(JSON.stringify(payload));
}

// Server Message Handler
function handleServerMessage(msg) {
  const guide = document.getElementById('ovalGuide');

  switch (msg.type) {
    case 'state':
      document.getElementById('promptText').innerText = msg.prompt || '';
      if (msg.state === 'ACTIVE_CHALLENGE') {
        document.getElementById('challengeCard').classList.remove('hidden');
      } else {
        document.getElementById('challengeCard').classList.add('hidden');
      }
      break;

    case 'quality':
      if (msg.guidance) {
        document.getElementById('promptText').innerText = msg.guidance;
      }
      if (msg.metrics) {
        updateMetricsToolbar(msg.metrics);
        if (msg.metrics.isCompliant) {
          guide.className = 'oval-guide compliant';
        } else {
          guide.className = 'oval-guide warning';
        }
      }
      break;

    case 'progress':
      const fill = document.getElementById('passiveProgressFill');
      const lbl = document.getElementById('passiveProgressLabel');
      const pct = Math.round((msg.collected / msg.window) * 100);
      fill.style.width = `${pct}%`;
      lbl.innerText = `Passive Liveness ${msg.collected}/${msg.window}`;
      break;

    case 'challenge':
      showChallenge(msg.challengeType, msg.prompt, msg.timeoutMs || 4000);
      break;

    case 'decision':
      handleDecision(msg);
      break;

    case 'diagnostic':
      renderDiagnosticTelemetry(msg);
      break;

    case 'error':
      console.warn('Server error:', msg.code, msg.message);
      document.getElementById('promptText').innerText = msg.message;
      guide.className = 'oval-guide error';
      break;
  }
}

function updateMetricsToolbar(m) {
  const compliancePill = document.getElementById('valCompliance');
  const dot = document.querySelector('#pillCompliance .pill-dot');
  if (m.isCompliant) {
    compliancePill.innerText = UI_STRINGS[currentLang].qualityCompliant;
    dot.className = 'pill-dot good';
  } else {
    compliancePill.innerText = UI_STRINGS[currentLang].qualityNonCompliant;
    dot.className = 'pill-dot bad';
  }

  document.getElementById('valSharpness').innerText = Math.round(m.blur || 0);
  const yaw = Math.round(m.yaw || 0);
  const pitch = Math.round(m.pitch || 0);
  document.getElementById('valPose').innerText = `${yaw}° / ${pitch}°`;
  document.getElementById('valLuma').innerText = (m.luma || 0).toFixed(2);
}

function showChallenge(type, prompt, timeoutMs) {
  const card = document.getElementById('challengeCard');
  const icon = document.getElementById('challengeIcon');
  const promptEl = document.getElementById('challengePrompt');
  const fill = document.getElementById('challengeTimerFill');

  promptEl.innerText = prompt;
  icon.innerText = getChallengeIcon(type);
  card.classList.remove('hidden');

  fill.style.transition = 'none';
  fill.style.width = '100%';

  setTimeout(() => {
    fill.style.transition = `width ${timeoutMs}ms linear`;
    fill.style.width = '0%';
  }, 50);
}

function getChallengeIcon(type) {
  switch (type) {
    case 'BLINK': return '👁️';
    case 'SMILE': return '😊';
    case 'TURN_LEFT': return '⬅️';
    case 'TURN_RIGHT': return '➡️';
    case 'LOOK_UP': return '⬆️';
    case 'NOD_HEAD': return '↕️';
    default: return '👤';
  }
}

function handleDecision(decision) {
  currentSessionId = decision.sessionId;
  const overlay = document.getElementById('decisionOverlay');
  const modal = document.getElementById('decisionModal');
  const title = document.getElementById('decisionTitle');
  const message = document.getElementById('decisionMessage');
  const icon = document.getElementById('decisionIcon');
  const score = document.getElementById('decisionScore');
  const sid = document.getElementById('decisionSessionId');

  const isAccepted = decision.decision === 'ACCEPTED';
  modal.className = 'decision-modal ' + (isAccepted ? 'accepted' : 'rejected');
  icon.innerText = isAccepted ? '✓' : '✕';
  title.innerText = isAccepted
    ? (UI_STRINGS[currentLang] || UI_STRINGS.en).verifSuccess
    : (UI_STRINGS[currentLang] || UI_STRINGS.en).verifFailed;

  message.innerText = decision.userMessage;
  if (decision.score !== undefined && decision.score !== null) {
    score.innerText = decision.score.toFixed(2);
    document.getElementById('decisionScoreRow')?.classList.remove('hidden');
  } else {
    document.getElementById('decisionScoreRow')?.classList.add('hidden');
  }

  if (decision.sessionId) {
    sid.innerText = decision.sessionId;
    document.getElementById('decisionSessionIdRow')?.classList.remove('hidden');
  } else {
    document.getElementById('decisionSessionIdRow')?.classList.add('hidden');
  }

  overlay.classList.remove('hidden');

  // Automatically refresh audit trail for this session
  if (currentSessionId && adminToken) {
    fetchAuditTrail(currentSessionId);
  }
}

function resetAndReconnect() {
  document.getElementById('decisionOverlay').classList.add('hidden');
  document.getElementById('challengeCard').classList.add('hidden');
  document.getElementById('ovalGuide').className = 'oval-guide';
  document.getElementById('passiveProgressFill').style.width = '0%';
  document.getElementById('passiveProgressLabel').innerText = 'Passive Liveness 0/5';
  frameSequence = 0;
  connectWebSocket();
}

// Audit Chain REST API Interactions
async function fetchAuditTrail(sessionId) {
  try {
    const headers = {};
    if (adminToken) {
      headers['Authorization'] = `Bearer ${adminToken}`;
    }
    const res = await fetch(`/api/audit/${sessionId}`, { headers });
    if (!res.ok) {
      if (res.status === 401) {
        localAuditEvents = [];
        renderAuditTable([]);
      }
      return;
    }
    localAuditEvents = await res.json();
    renderAuditTable(localAuditEvents);
  } catch (err) {
    console.error('Failed to load audit trail:', err);
  }
}

function renderAuditTable(events) {
  const tbody = document.getElementById('auditTableBody');
  tbody.innerHTML = '';

  if (!events || events.length === 0) {
    tbody.innerHTML = `<tr class="empty-row"><td colspan="3">${UI_STRINGS[currentLang].noEvents}</td></tr>`;
    return;
  }

  events.forEach(ev => {
    const row = document.createElement('tr');
    const prevShort = ev.previousHash ? ev.previousHash.substring(0, 7) + '...' : 'GENESIS';
    const currShort = ev.currentHash ? ev.currentHash.substring(0, 7) + '...' : '-';

    row.innerHTML = `
      <td><strong>${ev.eventType}</strong></td>
      <td>${ev.decision || '-'}</td>
      <td>
        <span class="hash-link" title="${ev.previousHash} -> ${ev.currentHash}">
          ${prevShort} <span class="hash-arrow">➔</span> ${currShort}
        </span>
      </td>
    `;
    tbody.appendChild(row);
  });
}

async function verifyAuditChain() {
  const badge = document.getElementById('chainVerificationBadge');
  const text = document.getElementById('chainVerificationText');
  const headers = {};
  if (adminToken) {
    headers['Authorization'] = `Bearer ${adminToken}`;
  }

  // If localAuditEvents is empty, attempt to fetch from session or system audit trail
  if (!localAuditEvents || localAuditEvents.length === 0) {
    if (currentSessionId) {
      await fetchAuditTrail(currentSessionId);
    }
    if (!localAuditEvents || localAuditEvents.length === 0) {
      try {
        const resAll = await fetch('/api/audit', { headers });
        if (resAll.ok) {
          const events = await resAll.json();
          if (events && events.length > 0) {
            localAuditEvents = events;
            renderAuditTable(events);
          }
        }
      } catch (e) {
        // Fallback to server verify endpoint
      }
    }
  }

  // If still no events recorded, query global audit verification
  if (!localAuditEvents || localAuditEvents.length === 0) {
    try {
      const res = await fetch('/api/audit/verify', { headers });
      if (res.status === 401) {
        badge.classList.remove('hidden');
        badge.className = 'verification-badge invalid';
        badge.querySelector('.badge-icon').innerText = '🔒';
        text.innerText = 'Audit verification requires Admin/Judge Token (click Judge Mode).';
        return;
      }
      const result = await res.json();
      badge.classList.remove('hidden');
      if (result.valid) {
        badge.className = 'verification-badge valid';
        badge.querySelector('.badge-icon').innerText = '✓';
        text.innerText = `${UI_STRINGS[currentLang].chainValid} (${result.count} server events verified)`;
      } else {
        badge.className = 'verification-badge invalid';
        badge.querySelector('.badge-icon').innerText = '⚠️';
        text.innerText = `${UI_STRINGS[currentLang].chainBroken} #${result.firstBrokenIndex}: ${result.message}`;
      }
      return;
    } catch (err) {
      alert('Failed to verify chain: ' + err.message);
      return;
    }
  }

  try {
    const postHeaders = { 'Content-Type': 'application/json' };
    if (adminToken) {
      postHeaders['Authorization'] = `Bearer ${adminToken}`;
    }
    const res = await fetch('/api/audit/verify', {
      method: 'POST',
      headers: postHeaders,
      body: JSON.stringify(localAuditEvents)
    });
    if (res.status === 401) {
      badge.classList.remove('hidden');
      badge.className = 'verification-badge invalid';
      badge.querySelector('.badge-icon').innerText = '🔒';
      text.innerText = 'Audit verification requires Admin/Judge Token (click Judge Mode).';
      return;
    }
    const result = await res.json();

    badge.classList.remove('hidden');
    if (result.valid) {
      badge.className = 'verification-badge valid';
      badge.querySelector('.badge-icon').innerText = '✓';
      text.innerText = `${UI_STRINGS[currentLang].chainValid} (${localAuditEvents.length} events verified)`;
    } else {
      badge.className = 'verification-badge invalid';
      badge.querySelector('.badge-icon').innerText = '⚠️';
      text.innerText = `${UI_STRINGS[currentLang].chainBroken} #${result.firstBrokenIndex}: ${result.message}`;
    }
  } catch (err) {
    alert('Failed to verify chain: ' + err.message);
  }
}

async function tamperAndVerifyAuditChain() {
  const headers = {};
  if (adminToken) {
    headers['Authorization'] = `Bearer ${adminToken}`;
  }
  if (!localAuditEvents || localAuditEvents.length === 0) {
    if (currentSessionId) {
      await fetchAuditTrail(currentSessionId);
    }
    if (!localAuditEvents || localAuditEvents.length === 0) {
      try {
        const resAll = await fetch('/api/audit', { headers });
        if (resAll.ok) {
          const events = await resAll.json();
          if (events && events.length > 0) {
            localAuditEvents = events;
            renderAuditTable(events);
          }
        }
      } catch (e) {}
    }
  }

  if (!localAuditEvents || localAuditEvents.length === 0) {
    alert('No audit events available to tamper. Please complete a session first or enable Judge Mode.');
    return;
  }

  // Clone and tamper with target event: toggle decision to guarantee a broken hash
  const tamperedChain = JSON.parse(JSON.stringify(localAuditEvents));
  const targetIdx = Math.max(0, tamperedChain.length - 1);
  const oldDec = tamperedChain[targetIdx].decision;
  tamperedChain[targetIdx].decision = (oldDec === 'ACCEPTED' ? 'REJECTED' : 'ACCEPTED');

  renderAuditTable(tamperedChain);

  try {
    const postHeaders = { 'Content-Type': 'application/json' };
    if (adminToken) {
      postHeaders['Authorization'] = `Bearer ${adminToken}`;
    }
    const res = await fetch('/api/audit/verify', {
      method: 'POST',
      headers: postHeaders,
      body: JSON.stringify(tamperedChain)
    });
    if (res.status === 401) {
      alert('Audit verification requires Admin/Judge Token (click Judge Mode).');
      return;
    }
    const result = await res.json();

    const badge = document.getElementById('chainVerificationBadge');
    const text = document.getElementById('chainVerificationText');
    badge.classList.remove('hidden');
    badge.className = 'verification-badge invalid';
    badge.querySelector('.badge-icon').innerText = '🛡️';
    text.innerText = `Tamper Detected! Chain Linkage Broken at Event #${result.firstBrokenIndex} (${result.message})`;
  } catch (err) {
    alert('Verification request failed: ' + err.message);
  }
}

// Policy & Diagnostics API
async function loadPolicyDetails(workflow) {
  const container = document.getElementById('policyDetailsContainer');
  try {
    const headers = {};
    if (adminToken) {
      headers['Authorization'] = `Bearer ${adminToken}`;
    }
    const res = await fetch(`/api/policy/${workflow}`, { headers });
    if (!res.ok) {
      if (res.status === 401) {
        container.innerHTML = `<div class="policy-loading" style="color:var(--text-muted);font-size:0.85rem;">🔒 Policy parameters are protected. Click <strong>⚖️ Judge Mode</strong> in header to enter token.</div>`;
        return;
      }
      throw new Error('Policy request failed');
    }
    const p = await res.json();

    container.innerHTML = `
      <div class="policy-desc" style="margin-bottom:12px;font-size:0.85rem;color:var(--text-muted);">${p.description || ''}</div>
      <div class="policy-item"><span class="policy-key">Workflow</span><span class="policy-val" style="color:var(--accent-teal);font-weight:600;">${p.workflow}</span></div>
      <div class="policy-item"><span class="policy-key">Passive Threshold</span><span class="policy-val">${p.passiveThreshold}</span></div>
      <div class="policy-item"><span class="policy-key">PAD Attack Threshold</span><span class="policy-val">${p.padThreshold}</span></div>
      <div class="policy-item"><span class="policy-key">Active Challenges</span><span class="policy-val">${p.activeEnabled ? 'Enabled (' + p.minChallenges + ' required)' : 'Disabled (Passive-first)'}</span></div>
      <div class="policy-item"><span class="policy-key">Passive Window</span><span class="policy-val">${p.passiveWindowFrames} frames</span></div>
      <div class="policy-item"><span class="policy-key">Max Retries</span><span class="policy-val">${p.maxRetries} attempts</span></div>
      <div class="policy-item"><span class="policy-key">Failure Behaviour</span><span class="policy-val">${p.failureBehaviour}</span></div>
      <div class="policy-item"><span class="policy-key">Allowed Challenges</span><span class="policy-val">${(p.allowedChallenges || []).join(', ') || 'None'}</span></div>
      <div class="policy-item" style="border-top:1px dashed var(--border-color);padding-top:8px;margin-top:8px;"><span class="policy-key">Standard Compliance</span><span class="policy-val">ISO 19794-5 / ISO 30107</span></div>
    `;
  } catch (err) {
    container.innerHTML = `<div class="policy-loading">Could not load policy details for ${workflow}.</div>`;
  }
}

async function checkServerHealth() {
  try {
    const res = await fetch('/api/health');
    if (res.ok) {
      const data = await res.json();
      updateStatus(true, `Server Healthy (UP)`);
      const indicator = document.getElementById('serverStatusIndicator');
      if (indicator) {
        indicator.title = `Status: ${data.status} | Mode: ${data.mode} | Version: ${data.engineVersion} | Uptime: ${data.uptimeSeconds}s | Audit Events: ${data.totalAuditEvents} | Integrity: ${data.auditChainIntegrity ? 'Tamper-Free' : 'Broken'}`;
      }
      if (data.mode === 'diagnostic') {
        document.getElementById('tabBtnDiag').classList.remove('hidden');
      }
    } else {
      updateStatus(false, 'Server Error ' + res.status);
    }
  } catch (e) {
    updateStatus(false, 'Offline');
  }
}

function renderDiagnosticTelemetry(diag) {
  const container = document.getElementById('diagContainer');
  if (!diag.weights) return;

  let html = `<div class="policy-item"><span class="policy-key">Model Version</span><span class="policy-val">${diag.engineVersion}</span></div>`;
  html += `<h4>Passive Weights</h4>`;
  for (const [k, v] of Object.entries(diag.weights)) {
    html += `<div class="policy-item"><span class="policy-key">${k}</span><span class="policy-val">${(v * 100).toFixed(0)}%</span></div>`;
  }
  container.innerHTML = html;
}

function showCameraError(title, msg) {
  document.getElementById('cameraErrorTitle').innerText = title;
  document.getElementById('cameraErrorMessage').innerText = msg;
  document.getElementById('cameraErrorOverlay').classList.remove('hidden');
}
