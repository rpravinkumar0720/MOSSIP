# Synthetic Biometric Benchmark Dataset (ISO/IEC 30107-3)

## Overview

This directory contains evaluation datasets for evaluating biometric Presentation Attack Detection (PAD) and liveness algorithms against ISO/IEC 30107-3 criteria.

## Privacy, Consent & Data Sovereignty

> [!IMPORTANT]
> **Zero Raw Human Biometric Data:** In accordance with MOSIP Data Protection and Sovereignty Standards, CODING_STANDARDS.md, and global biometric privacy regulations (GDPR Art. 9, BIPA), **no raw human faces, camera photographs, or real biometric identities are stored or committed to this repository**.
>
> All test sequences in this dataset are procedurally synthesized geometric manifold models with simulated illumination, sensor noise, 3D shading, and calibrated physical attack artifacts.

## Dataset Structure

The benchmark consists of 135 total multi-frame presentation sequences:

| Category | Species / Type | Sample Count | Frames / Sample | Attack Artifacts Simulated |
|---|---|---|---|---|
| `genuine/` | Bona Fide Presentation | 30 | 10 | Natural 3D shading, physiological non-rigid tremor (0.0025 px), eye/mouth contrast |
| `print/` | PAI-1 (2D Photo Print) | 30 | 10 | Zero 3D depth, zero non-rigid motion, paper boundary margin, flat illumination |
| `replay_phone/` | PAI-2a (OLED Screen) | 30 | 10 | Moire raster frequencies, specular glare reflections, rigid camera shake |
| `replay_tablet/` | PAI-2b (LCD Screen) | 30 | 10 | Tablet bezel boundaries, Moire frequency patterns, rigid 2D planar translation |
| `mask/` | PAI-3 (3D Mask / Cast) | 15 | 10 | Uniform pigment distribution, lack of micro-spectral blood perfusion, eye cutout edges |

Each sample directory contains:
- `frame_000.png` ... `frame_009.png`: Rendered multi-frame image sequence.
- `frame_000.json` ... `frame_009.json`: Synchronized MediaPipe landmark coordinate sidecars.
- `timeline.json`: Presentation metadata, sequence ID, category label, device ID, and lighting condition.

## Reproduction & Generation

The dataset can be deterministically regenerated at any time using:
```bash
mvn test -Dtest=GenerateBenchmarkDataset#generateAllDatasets
```

## Honest Small-N Disclaimer

While this dataset demonstrates robust algorithmic separation on calibrated physical manifolds (Max APCER = 0.00%, BPCER = 0.00%), production deployment at national identity scale requires field evaluation on large-scale accredited biometric cohorts ($N \ge 1,000$) across diverse demographic groups, ambient lighting spectra, and camera optics in compliance with ISO/IEC 19792.
