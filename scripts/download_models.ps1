# ==============================================================================
# MOSIP Liveness & PAD - Deep Learning ONNX Model Downloader (PowerShell)
# ==============================================================================
param (
    [string]$ModelsDir = "models"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ModelsDir)) {
    New-Item -ItemType Directory -Path $ModelsDir | Out-Null
}

Write-Host "==> Downloading MOSIP Liveness ONNX Models to $ModelsDir..." -ForegroundColor Cyan

# 1. UltraFace RFB-320
$UltraFaceUrl = "https://github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB/raw/master/models/onnx/version-RFB-320.onnx"
$UltraFaceFile = Join-Path $ModelsDir "version-RFB-320.onnx"
if (-not (Test-Path $UltraFaceFile)) {
    Write-Host "--> Downloading UltraFace RFB-320..."
    try {
        Invoke-WebRequest -Uri $UltraFaceUrl -OutFile $UltraFaceFile -UseBasicParsing
    } catch {
        Write-Warning "Could not download UltraFace model: $_"
    }
} else {
    Write-Host "--> UltraFace RFB-320 already present." -ForegroundColor Green
}

# 2. MiniFASNetV2
$MiniFasUrl = "https://github.com/minivision-ai/Silent-Face-Anti-Spoofing/raw/master/resources/anti_spoof_models/2.7_80x80_MiniFASNetV2.onnx"
$MiniFasFile = Join-Path $ModelsDir "2.7_80x80_MiniFASNetV2.onnx"
if (-not (Test-Path $MiniFasFile)) {
    Write-Host "--> Downloading MiniFASNetV2..."
    try {
        Invoke-WebRequest -Uri $MiniFasUrl -OutFile $MiniFasFile -UseBasicParsing
    } catch {
        Write-Warning "Could not download MiniFASNet model: $_"
    }
} else {
    Write-Host "--> MiniFASNetV2 already present." -ForegroundColor Green
}

Write-Host "==> Model verification complete." -ForegroundColor Cyan
Get-FileHash -Path (Join-Path $ModelsDir "*.onnx") -Algorithm SHA256 -ErrorAction SilentlyContinue | Format-Table -AutoSize
