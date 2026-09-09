package com.flark.stemwerk

import android.net.Uri

enum class InferenceBackend {
    AUTO,
    QNN_GPU,
    CPU,
    NNAPI;

    companion object {
        fun fromId(id: String?): InferenceBackend = when (id?.lowercase()) {
            "cpu" -> CPU
            "qnn", "qnn-gpu", "gpu" -> QNN_GPU
            "nnapi", "hardware" -> NNAPI
            else -> AUTO
        }
    }
}

/**
 * Request passed to a mobile inference backend.
 *
 * The request deliberately contains model metadata and the output sink so that
 * the Android UI does not need to know whether the backend is ONNX Runtime,
 * ExecuTorch, LiteRT, or a device-specific accelerator.
 */
data class SeparationRequest(
    val audioUri: Uri,
    val modelId: String,
    val stemCount: Int,
    val selectedStemNames: List<String>,
    val output: OutputSink,
    val backend: InferenceBackend = InferenceBackend.AUTO,
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
 * Kept for callers that want an explicit unavailable implementation.
 * Production Android processing uses RealMdxSeparationEngine.
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
        onLog("Requested backend: ${request.backend}")

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
