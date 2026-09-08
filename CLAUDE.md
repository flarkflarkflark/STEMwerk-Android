# STEMwerk-Android (Claude context)

- Target device: Asus ZenFone 10 (Snapdragon 8 Gen 2, ARM64).
- Product: native Android frontend for STEMwerk.
- First mobile scope: offline 2-/4-stem separation.
- DrumSep is later scope, not a requirement for the first Android backend.
- Models are downloaded once, verified and cached.
- MVP starts CPU-first; hardware acceleration is an adapter concern.

## Hard constraints

- Do not upload user audio to a cloud service.
- Do not ship large model files inside the APK.
- Do not produce copied or dummy stems when inference is unavailable.
- Do not select a runtime solely because it is convenient before model export/parity is proven.
- Keep Android-specific inference behind SeparationEngine.
- Keep the Lua GUI in STEMwerk-reaper as the visual source of truth.

## Current slice

The mobile foundation contains:

- Kotlin UI and Storage Access Framework plumbing;
- model download/cache plumbing;
- SeparationRequest and SeparationEngine contracts;
- an explicit unavailable-backend implementation;
- no legacy PyTorch Lite dependency;
- Android colors, artwork and launcher mark mapped from the Lua classic dark palette.

The foundation build is expected to report that mobile inference is unavailable. That is intentional until a real model/backend is integrated.

## Visual parity references

Use these STEMwerk-reaper files when changing the Android UI:

- scripts/reaper/_internal/STEMwerk_UI.lua;
- scripts/reaper/_internal/STEMwerk_UI_Tokens.lua;
- scripts/reaper/_internal/STEMwerk_UI_Draw.lua;
- scripts/reaper/_internal/STEMwerk_UI_Backgrounds.lua.

Preserve the Lua stem color order: vocals, drums, bass, other.

## Next engineering gate

Choose one portable 2- or 4-stem model and document:

- export command and source model revision;
- tensor layouts and sample-rate/channel contract;
- chunk/overlap and reconstruction rules;
- model checksum and manifest entry;
- numerical parity against STEMwerk-core;
- ARM64 device smoke results.

Only after that gate should a real ExecuTorch, ONNX Runtime Mobile or LiteRT dependency be added.
