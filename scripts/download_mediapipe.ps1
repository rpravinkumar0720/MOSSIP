# ==============================================================================
# MOSIP Liveness & PAD - MediaPipe Tasks Vision Asset Downloader (PowerShell)
# ==============================================================================
param (
    [string]$DestDir = "liveness-server\src\main\resources\web\vendor\mediapipe"
)

$ErrorActionPreference = "Stop"
$WasmDir = Join-Path $DestDir "wasm"
New-Item -ItemType Directory -Force -Path $WasmDir | Out-Null

$CdnUrl = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14"
$ModelUrl = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

Write-Host "==> Downloading MediaPipe Tasks Vision assets to $DestDir..."

curl.exe -sSL -o "$DestDir\vision_bundle.mjs" "$CdnUrl/vision_bundle.mjs"
curl.exe -sSL -o "$DestDir\face_landmarker.task" "$ModelUrl"
curl.exe -sSL -o "$WasmDir\vision_wasm_internal.js" "$CdnUrl/wasm/vision_wasm_internal.js"
curl.exe -sSL -o "$WasmDir\vision_wasm_internal.wasm" "$CdnUrl/wasm/vision_wasm_internal.wasm"
curl.exe -sSL -o "$WasmDir\vision_wasm_nosimd_internal.js" "$CdnUrl/wasm/vision_wasm_nosimd_internal.js"
curl.exe -sSL -o "$WasmDir\vision_wasm_nosimd_internal.wasm" "$CdnUrl/wasm/vision_wasm_nosimd_internal.wasm"

Write-Host "==> MediaPipe assets successfully vendored for offline operation."
