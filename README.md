# flarkaudio STEMWERK-Android

Offline stem separation on Android (ZenFone 10 target) with selectable **2 / 4 / 6 stems**.

## Goals
- Local/offline separation (no cloud upload)
- User chooses 2/4/6 stems (model-dependent)
- Models are **downloaded once** and cached on device
- MVP runs on **CPU**; optional future acceleration via NNAPI

## Architecture (MVP)
- Android app (Kotlin)
- Audio decode/encode: FFmpeg (planned)
- Inference: ONNX Runtime Mobile (planned)
- Models:
  - 2-stem (vocals/instrumental)
  - 4-stem (drums/bass/other/vocals)
  - 6-stem (TBD)

## Status
This repo currently contains a **project skeleton** (UI + model download plumbing stubs). The actual separation engine is not wired yet.

## Next steps
- Pick model family + export path (Demucs→ONNX or alternative)
- Add onnxruntime-mobile (aar) + model runner
- Add FFmpeg (prefab or prebuilt) for audio I/O
- Implement ForegroundService + progress notifications

