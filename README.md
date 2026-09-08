# flarkaudio STEMWERK-Android

Android frontend for STEMwerk, targeting ARM64 devices such as the Asus ZenFone 10.

## Product boundary

The first mobile milestone is intentionally smaller than desktop STEMwerk:

- local/offline 2- or 4-stem separation;
- CPU-first execution on ARM64;
- downloaded and verified model cache;
- no DrumSep in the first mobile milestone;
- no cloud upload requirement.

The Android app is a separate native frontend. It does not run REAPER and does not embed the desktop PySide6 UI.

## Current status

The repository contains the Android UI and model-download plumbing. The current android/v0.4.0-mobile-foundation slice adds an explicit SeparationEngine contract and removes the legacy PyTorch Lite dependency.

This build deliberately does **not** write dummy stems. Until a portable model/backend has passed parity checks against STEMwerk-core, a separation request ends with a clear “backend not available” result.

## Visual direction

The Android GUI follows the STEMwerk REAPER Lua GUI as its visual source:

- Lua classic dark palette and semantic colors;
- four Lua stem colors for vocals, drums, bass and other;
- animated STEMwerk/flarkAUDIO artwork from the REAPER visual assets;
- matching dark surfaces, accent buttons and primary action treatment;
- native Android controls and touch-sized spacing around that visual language.

The mapping is documented in docs/reaper-ui-parity.md. The Android UI is a native adaptation; the Lua files remain the source of truth for visual identity.

## Runtime direction

The runtime is intentionally not hard-coded before model export has been validated. Candidates are:

- ExecuTorch with XNNPACK for the first CPU path, followed later by Vulkan or Qualcomm/MediaTek backends where useful;
- ONNX Runtime Mobile if the exported model and operators fit that runtime;
- LiteRT if the export path gives better Android CPU/GPU/NPU coverage.

The UI talks only to SeparationEngine; the eventual runtime is an implementation detail behind that boundary.

## Model parity gate

Before enabling real separation, the selected 2-/4-stem model must have:

1. a reproducible export from the STEMwerk model path;
2. documented input/output tensor layout, sample rate, channels, chunking and overlap;
3. verified checksum and manifest metadata;
4. numerical/output parity checks against the desktop reference;
5. a real-device smoke test on ARM64.

## Planned next slices

1. Export one 2- or 4-stem model and run the parity gate.
2. Add the chosen mobile runtime behind SeparationEngine.
3. Replace the placeholder processing path with foreground-service execution, progress and cancellation.
4. Add optional Vulkan/NPU acceleration after the CPU path is stable.
5. Add broader model coverage later, including DrumSep.
