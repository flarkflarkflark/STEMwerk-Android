# flarkaudio STEMWERK-Android

Android frontend for STEMwerk, targeting ARM64 devices such as the Asus ZenFone 10.

## Current mobile slice

The branch `android/v0.4.0-mobile-foundation` now contains a working local 2-stem extraction path:

- real UVR MDX vocals ONNX model;
- PCM16LE stereo/mono WAV at 44.1 kHz;
- ONNX Runtime on Android;
- selectable ARM64 CPU or Android NNAPI hardware route;
- Auto route: try NNAPI and fall back to CPU if the device/model rejects it;
- cached model download and local WAV output;
- no cloud upload and no dummy stems.

The first run downloads the model to the app's private model cache. The model is roughly a large desktop-sized download, so the first extraction takes longer than later runs.

The current model writes:

- `vocals.wav`;
- `other.wav` (instrumental/residual).

Drums and bass remain deliberately out of scope for this slice, as agreed. Four-stem coverage will use additional portable models after the two-stem path has been verified on the ZenFone 10.

## Product boundary

The Android app is a separate native frontend. It does not run REAPER and does not embed the desktop PySide6 UI.

The GUI follows the STEMwerk REAPER Lua GUI as its visual source:

- Lua classic dark palette and semantic colors;
- the compact STEMwerk installer artwork;
- native Android controls and touch-sized spacing;
- matching dark surfaces and stem colors.

The mapping is documented in [docs/reaper-ui-parity.md](docs/reaper-ui-parity.md).

## Runtime details

The extraction path is implemented behind `SeparationEngine`:

1. `RealMdxSeparationEngine` reads the selected WAV locally and manages the model cache.
2. `OnnxMdxSeparator` performs the MDX STFT, ONNX inference, inverse STFT and residual reconstruction.
3. `OutputSink` writes the selected WAV stems to the app folder or Android document-tree folder.

The NNAPI route means Android selects an available device accelerator through the Android neural-network API. It is not a promise that every phone exposes a GPU delegate for this model; Auto safely falls back to the CPU route. This is the Android equivalent of choosing a hardware path, not Apple's MPS.

## Input requirements

The first real model slice accepts:

- RIFF/WAVE PCM;
- 16-bit little-endian samples;
- mono or stereo;
- 44.1 kHz.

Other formats and sample rates need a decoder/resampler slice before they can be sent to the model. Desktop formats remain supported by the desktop STEMwerk implementations.

## Next slices

1. Test real outputs on the ZenFone 10 and compare against the desktop reference.
2. Improve input decoding/resampling and foreground-service behavior for long jobs.
3. Add portable four-stem coverage.
4. Add optional Vulkan/NPU-specific acceleration if the device/runtime combination benefits from it.
5. Add broader model coverage later, including DrumSep.
