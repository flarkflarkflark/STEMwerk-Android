package com.flark.stemwerk

import android.net.Uri

/**
 * Request passed to a mobile inference backend.
 *
 * The request deliberately contains model metadata and the output sink so that
 * the Android UI does not need to know whether the backend is ExecuTorch,
 * ONNX Runtime, LiteRT, or a device-specific accelerator.
 */
data class SeparationRequest(
    val audioUri: Uri,
    val modelId: String,
    val stemCount: Int,
    val selectedStemNames: List<String>,
    val output: OutputSink,
)

/**
 * Stable boundary for a real mobile separation implementation.
 *
 * Implementations must never silently produce placeholder audio. If the
 * selected model/backend is unavailable, they must report a failed job.
 */
interface SeparationEngine {
    fun run(
        request: SeparationRequest,
        onLog: (String) -> Unit,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String) -> Unit,
    )

    fun cancel()
}

/**
 * Explicit placeholder until a portable 2-/4-stem model has passed parity
 * testing against STEMwerk-core.
 */
class UnavailableSeparationEngine : SeparationEngine {

    @Volatile
    private var cancelled = false

    override fun run(
        request: SeparationRequest,
        onLog: (String) -> Unit,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        cancelled = false
        onProgress(0, "Checking mobile inference backend…")
        onLog("Audio URI: ${request.audioUri}")
        onLog("Requested model: ${request.modelId}")
        onLog("Requested stems: ${request.selectedStemNames.joinToString()}")
        onLog("Model count: ${request.stemCount}")

        if (cancelled) {
            onDone(false, "Cancelled")
            return
        }

        onLog("No real mobile separation backend is included in this build.")
        onLog("No placeholder stems will be written.")
        onDone(false, "Mobile separation backend is not available yet")
    }

    override fun cancel() {
        cancelled = true
    }
}
