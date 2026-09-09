# STEMwerk Android

Native ARM64 Android frontend. Branch: `android/v0.4.0-mobile-foundation`.
Version 0.4.2 / build 18 adds a Qualcomm QNN GPU route for Snapdragon devices.
Build 17 added local audio decoding, batch input, four stems and a device acceleration test.
It also includes playback and real PCM waveforms for originals and completed stems.

## Use

- Select one or more audio files. WAV PCM16 has a direct decoder; MP3, FLAC, AAC/M4A and Ogg use Android's media codecs. Exact additional format support depends on the device.
- Mono/stereo input at 8–192 kHz is converted to 44.1 kHz using a windowed-sinc resampler with a low-pass filter for downsampling. Output WAVs are stereo PCM16 at 44.1 kHz.
- Choose **4 stems** (vocals, drums, bass, other) or **2 stems** (vocals, instrumental). Choose any subset of available stems.
- Each selected file runs sequentially and gets its own numbered folder. Failed files do not stop the remaining queue. Share exports a ZIP of successful jobs, including when a document-tree output folder was chosen.
- Keep the processing screen open. Rotation is handled without restarting the queue. Leaving the activity cancels work; resumable background execution is still a follow-up.

## Listen before and after separation

Use **Listen / waveform** after selecting input files, or **Listen / waveforms**
after processing. Choose an original or stem from the track menu. Play/pause,
the time slider and tapping the waveform control playback. Switching between
the original and stems of the same file retains the playhead position.
Waveform height uses a fixed full-scale amplitude so quiet stems are not
artificially enlarged. Playback pauses when the player screen is left.

## Models and offline use

Four stems use four separate KUIELab A MDX models, approximately 119 MB total.
Two stems use UVR MDX Voc_FT, approximately 67 MB.
Only models needed for the selected stems are downloaded. Models remain in app storage and are checked against pinned SHA-256 hashes before use. Normal APK updates retain them; uninstalling or clearing app data removes them.

Source weights: [UVR model repository](https://github.com/TRvlvr/model_repo/releases/tag/all_public_uvr_models).
Model configuration: [upstream MDX metadata](https://github.com/TRvlvr/application_data/blob/main/mdx_model_data/model_data_new.json).
The `Verify mobile models` workflow matched model hashes to upstream metadata and executed every model on ONNX Runtime 1.20 CPU.
The previous Voc_FT configuration was incorrect; build 17 corrects it to [1,4,3072,256], FFT 7680 and compensation 1.021.

Four-stem outputs are independent model estimates. They are not forced to sum exactly to the mixture. DrumSep (splitting drums further) remains later scope.
Audio stays local; no inference server is used. Temporary decoded audio is held on disk and removed per job.

## Qualcomm QNN GPU

The delivered ARM64 APK uses Microsoft's official
`onnxruntime-android-qnn` package. **Auto** first tries QNN with
`backend_type=gpu`, which targets the Snapdragon Adreno GPU. CPU fallback is
disabled inside that QNN session. If QNN cannot execute the complete model
graph, Auto reports the reason and retries the model with the regular ARM64 CPU
provider. Selecting **QNN GPU** explicitly fails the job instead of falling
back, which is useful for validation.

The APK includes the QNN GPU and system libraries. HTP/DSP libraries are left
out of this GPU build; they are only useful after separate NPU quantization and
quality validation.

NNAPI remains available only as a legacy manual route. Android 15 deprecated
NNAPI, and the Zenfone 10 device report from build 17 showed only ORT CPU
provider events for all four KUIELab models.

## Test device acceleration

Tap **Test toestelversnelling**. The selected model or four-model pack is tested with a deterministic spectrum, one warm-up and two timed runs on each of CPU and QNN GPU.

- Logs contain actual execution-provider events from ONNX Runtime profiling.
- ORT CPU fallback is disabled inside the measured QNN session.
- A successful session without QNN execution events is reported as unconfirmed acceleration.
- Outputs are checked for finite values and relative RMS difference against CPU (2% diagnostic threshold).
- Reported speed excludes model loading, decoding, STFT and downloads. It does not predict whole-song speed.
- The probe requests QNN's GPU backend; an HTP/NPU route would require separately quantized and quality-validated models.
- Share the text report to inspect hardware results. No audio or sensitive account information is included.

CPU remains available. Auto retries CPU if QNN session creation or inference fails.

## Verification

CI runs resampling unit tests, then Android API 34 x86_64 instrumented tests for the real codecs and the actual Kotlin inference path with all five pinned models. Generated audio and model fixtures are included only in the test APK; the delivered ARM64 app contains no model weights.

A green build/emulator run does not prove GPU/NPU support on a physical phone. Use the in-app probe on that phone.

Artwork and colors continue to follow the REAPER Lua GUI and compact installer logo; see [UI mapping](docs/reaper-ui-parity.md).
