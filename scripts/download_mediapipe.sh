#!/usr/bin/env bash
# ==============================================================================
# MOSIP Liveness & PAD - MediaPipe Tasks Vision Asset Downloader
# ==============================================================================
# Downloads and vendors offline WebAssembly & model assets into web resources
# for 100% offline operation and data sovereignty compliance.
# ==============================================================================

set -euo pipefail

DEST_DIR="${1:-liveness-server/src/main/resources/web/vendor/mediapipe}"
WASM_DIR="${DEST_DIR}/wasm"

mkdir -p "${WASM_DIR}"

CDN_URL="https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14"
MODEL_URL="https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

echo "==> Downloading MediaPipe Tasks Vision assets to ${DEST_DIR}..."

curl -sSL -o "${DEST_DIR}/vision_bundle.mjs" "${CDN_URL}/vision_bundle.mjs"
curl -sSL -o "${DEST_DIR}/face_landmarker.task" "${MODEL_URL}"
curl -sSL -o "${WASM_DIR}/vision_wasm_internal.js" "${CDN_URL}/wasm/vision_wasm_internal.js"
curl -sSL -o "${WASM_DIR}/vision_wasm_internal.wasm" "${CDN_URL}/wasm/vision_wasm_internal.wasm"
curl -sSL -o "${WASM_DIR}/vision_wasm_nosimd_internal.js" "${CDN_URL}/wasm/vision_wasm_nosimd_internal.js"
curl -sSL -o "${WASM_DIR}/vision_wasm_nosimd_internal.wasm" "${CDN_URL}/wasm/vision_wasm_nosimd_internal.wasm"

echo "==> MediaPipe assets successfully vendored for offline operation."
