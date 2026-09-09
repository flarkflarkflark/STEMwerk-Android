# STEMwerk Android

Native ARM64 Android frontend. Branch: `android/v0.4.0-mobile-foundation`.
Version 0.4.8 / build 24 confirmed the static-batch fix on-device (QNN GPU
executed the full graph, output matched CPU within 0.00001 relative RMS, 5.5x
faster) and wires it into the real separation route for all four KUIELab
models: **QNN GPU** and **Auto** now download and use a static-batch variant
of the selected model(s) for QNN inference, published in this repo's own
[`qnn-gpu-static-batch-v1`](https://github.com/flarkflarkflark/STEMwerk-Android/releases/tag/qnn-gpu-static-batch-v1)
release. CPU and NNAPI, and the Auto-mode fallback, keep using the original
upstream models unchanged.
Build 23 made the static-batch diagnostic compare output against CPU and
report speed only once QNN execution and output are confirmed, on top of
build 22's internal-storage fix for the diagnostic input file.
Build 21 added verbose QNN session logging and the static-batch diagnostic
for the whole-graph QNN rejection found in build 20.
Build 20 added a second device acceleration test that allows
QNN GPU + CPU fallback, to check for partial-GPU speedups.
Build 19 requests the OEM OpenCL library for QNN GPU.
Build 18 added a Qualcomm QNN GPU route for Snapdragon devices.
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

When the QNN GPU route is used (**QNN GPU** or **Auto**) for a KUIELab model, an
additional static-batch variant of that same model (~28 MB each, up to four)
is downloaded from this repo's own release and cached alongside the original
— see Qualcomm QNN GPU below for why. It is only fetched when needed.

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

The manifest requests `libOpenCL.so` with `uses-native-library` and
`required=false`. Android requires this declaration for apps targeting API 31+
to access public OEM native libraries. It does not make private vendor
libraries accessible; the OEM must expose the driver to apps. Devices without
an exposed OpenCL driver can still install and use CPU extraction.
See [Android native-library declarations](https://developer.android.com/guide/topics/manifest/uses-native-library-element).

The Zenfone 10 build 18 report showed successful CPU inference for all four
models (about 4.6–5.1 seconds each), while every QNN GPU initialization failed
to load `/vendor/lib64/libOpenCL.so`. Build 19 addressed the missing manifest
declaration. The Zenfone 10 build 19 report confirms the linker error is gone
(the OpenCL driver now loads), but the strict whole-graph QNN session still
fails on all four models: ONNX Runtime's `GetCapability` step assigns some
graph nodes to the default CPU execution provider, and the strict test forbids
any CPU fallback. This means QNN GPU initializes correctly but does not cover
every op in these models; which ops are unsupported was not yet known from the
strict test's report alone.

Build 20 adds a second test alongside the strict one: QNN GPU with CPU
fallback allowed, profiled per operation. It reports which op types still ran
on CPU, whether QNN executed any ops at all, the CPU/QNN output difference and
the speed delta versus CPU-only. A session where every operation still ran on
CPU is always reported as a CPU result, never as GPU acceleration, even though
fallback was technically allowed.

The Zenfone 10 build 20 report showed zero QNN provider events on all four
models even with fallback allowed (417 of 417 ops on CPU each time) — QNN
rejects these graphs outright rather than assigning a few unsupported ops to
CPU. The KUIELab MDX models export with a dynamic `batch_size` input/output
dimension, and upstream ONNX Runtime QNN EP documentation states dynamic
shapes (their own example is a dynamic batch size) are not supported and must
be fixed to a specific value; this lines up with an all-or-nothing rejection
of the whole graph. Build 21 adds `setSessionLogLevel(ORT_LOGGING_LEVEL_VERBOSE)`
on QNN sessions, which confirmed this directly: ORT's own log shows the very
first graph node (`Conv_0`) failing QNN validation with
`conv_op_builder.cc:83 IsOpSupported Cannot get shape`, cascading through
every remaining node to `Number of partitions supported by QNN EP: 0`.

Build 21-23 added a hand-placed diagnostic (a static-batch copy of
`kuielab_a_vocals.onnx`, only the batch dimension changed from dynamic to `1`,
verified bit-identical to the original on CPU before use) to test this
hypothesis without touching the shipped models. On the Zenfone 10 it
confirmed the fix: QNN executed the complete graph, output matched CPU within
0.000011 relative RMS, and it ran 5.5x faster than CPU (1089 ms vs. 5994 ms
for `kuielab_a_vocals`).

Build 24 converted all four KUIELab models the same way, verified each
converted file bit-identical to its original on CPU before publishing, and
hosts them in this repo's [`qnn-gpu-static-batch-v1`
release](https://github.com/flarkflarkflark/STEMwerk-Android/releases/tag/qnn-gpu-static-batch-v1)
— `ModelManager.ensureQnnModel()` downloads and SHA-256-verifies them the same
way `ensureModel()` handles the original upstream models. `OnnxMdxSeparator`'s
QNN GPU route now loads this static-batch file instead of the original
whenever one is available for the selected model; CPU, NNAPI, and the
AUTO-mode CPU fallback are untouched and keep using the original file. The
acceleration probe's static-batch check now runs through this same production
`ensureQnnModel()` path for all four models, not just a manually placed
vocals-only diagnostic.

NNAPI remains available only as a legacy manual route. Android 15 deprecated
NNAPI, and the Zenfone 10 device report from build 17 showed only ORT CPU
provider events for all four KUIELab models.

## Test device acceleration

Tap **Test toestelversnelling**. The selected model or four-model pack is tested with a deterministic spectrum, one warm-up and two timed runs on each of CPU, strict QNN GPU, and QNN GPU with CPU fallback allowed.

- Logs contain actual execution-provider events from ONNX Runtime profiling, per route.
- The strict QNN route disables ORT CPU fallback inside the session; a QNN result there must run the complete model graph.
- The mixed route allows CPU fallback and additionally reports which op types still executed on CPU, so a partial-QNN result is visible instead of just pass/fail.
- A successful session without QNN execution events is reported as unconfirmed acceleration, in both the strict and mixed routes; full CPU execution is never reported as a GPU result.
- Outputs are checked for finite values and relative RMS difference against CPU (2% diagnostic threshold), for both QNN routes.
- Reported speed excludes model loading, decoding, STFT and downloads. It does not predict whole-song speed.
- The probe requests QNN's GPU backend; an HTP/NPU route would require separately quantized and quality-validated models.
- Share the text report to inspect hardware results. No audio or sensitive account information is included.
- A fourth section per model downloads and tests the published static-batch QNN variant (see Qualcomm QNN GPU above) the same way the real separation route uses it, confirming both real QNN execution and output agreement with CPU before reporting any speedup.

CPU remains available. Auto retries CPU if QNN session creation or inference fails.

## Verification

CI runs resampling unit tests, then Android API 34 x86_64 instrumented tests for the real codecs and the actual Kotlin inference path with all five pinned models. Generated audio and model fixtures are included only in the test APK; the delivered ARM64 app contains no model weights.

A green build/emulator run does not prove GPU/NPU support on a physical phone. Use the in-app probe on that phone.

Artwork and colors continue to follow the REAPER Lua GUI and compact installer logo; see [UI mapping](docs/reaper-ui-parity.md).
