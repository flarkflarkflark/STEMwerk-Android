# Design notes

## Device target
- Asus ZenFone 10 (Snapdragon 8 Gen 2, ARM64)

## Model strategy
- Separate models per stem count (2/4/6)
- Download-on-demand

## Output strategy
- Decode input with FFmpeg to float PCM
- Run model
- Write stems to WAV/FLAC

## Long-running tasks
- ForegroundService + notifications
