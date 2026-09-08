package com.flark.stemwerk

import android.content.Context
import kotlin.concurrent.thread

/**
 * Production SeparationEngine for the Android MVP.
 *
 * The work runs off the UI thread. Model download is cached; audio and output
 * remain local to the device.
 */
class RealMdxSeparationEngine(
    private val context: Context,
) : SeparationEngine {
    @Volatile
    private var cancelled = false

    @Volatile
    private var activeSeparator: OnnxMdxSeparator? = null

    override fun run(
        request: SeparationRequest,
        onLog: (String) -> Unit,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        cancelled = false

        thread(name = "stemwerk-separation") {
            try {
                onProgress(0, "Reading audio…")
                val audioBytes = context.contentResolver.openInputStream(request.audioUri)
                    ?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Could not open selected audio")

                check(!cancelled) { "Cancelled" }
                onLog("Loaded ${audioBytes.size / (1024 * 1024)} MiB of WAV data")

                val manager = ModelManager(context)
                onProgress(2, "Preparing model…")
                val (model, modelFile) = manager.ensureModel(request.modelId) { downloadPct ->
                    onProgress(2 + (downloadPct * 18 / 100), "Downloading model… $downloadPct%")
                }
                onLog("Model cache path: ${modelFile.absolutePath}")

                check(!cancelled) { "Cancelled" }
                val separator = OnnxMdxSeparator(
                    context = context,
                    onLog = onLog,
                    onProgress = { pct, msg ->
                        onProgress(20 + (pct * 75 / 100), msg)
                    },
                )
                activeSeparator = separator
                separator.cancelled = cancelled

                separator.separate(
                    audioBytes = audioBytes,
                    model = model,
                    modelFile = modelFile,
                    selectedStemNames = request.selectedStemNames,
                    backend = request.backend,
                    output = request.output,
                )

                check(!cancelled) { "Cancelled" }
                onProgress(100, "Finished")
                onDone(true, "Finished — stems written")
            } catch (error: Throwable) {
                val message = error.message ?: error::class.java.simpleName
                onLog("Extraction failed: $message")
                onDone(false, if (message == "Cancelled") "Cancelled" else "Failed: $message")
            } finally {
                activeSeparator = null
            }
        }
    }

    override fun cancel() {
        cancelled = true
        activeSeparator?.cancelled = true
    }
}
