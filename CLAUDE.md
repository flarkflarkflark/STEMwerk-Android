# STEMwerk-Android (Claude context)

- Target device: Asus ZenFone 10 (Snapdragon 8 Gen 2, ARM64)
- Offline stem separation app.
- User chooses 2/4/6 stems. Models can be downloaded once.

Constraints:
- Avoid huge APK: models should be downloaded and cached.
- MVP: CPU-only; later optional NNAPI.

Implementation plan:
- Kotlin app skeleton
- ModelManager: download/caching/versioning
- SeparationEngine interface: run(file, stems, modelPath, progress)
- ForegroundService for long-running split jobs
