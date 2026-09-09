package com.flark.stemwerk

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/** One instance per job; the batch worker calls runBlocking sequentially. */
class RealMdxSeparationEngine(private val context: Context) : SeparationEngine {
    @Volatile private var cancelled = false
    @Volatile private var activeSeparator: OnnxMdxSeparator? = null

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("Cancelled")
    }

    override fun run(request: SeparationRequest, onLog: (String) -> Unit,
        onProgress: (Int, String) -> Unit, onDone: (Boolean, String) -> Unit) {
        thread(name = "stemwerk-separation") {
            try {
                runBlocking(request, onLog, onProgress)
                onDone(true, "Finished — stems written")
            } catch (e: Exception) {
                onDone(false, e.message ?: "Extraction failed")
            }
        }
    }

    fun runBlocking(request: SeparationRequest, onLog: (String) -> Unit,
        onProgress: (Int, String) -> Unit) {
        val work = File(context.cacheDir, "audio-" + java.util.UUID.randomUUID())
        check(work.mkdirs()) { "Cannot create audio workspace" }
        try {
            checkCancelled()
            val models = ModelManager.selectedModels(request.modelId, request.selectedStemNames)
            onProgress(0, "Opening audio…")
            val decoded = AudioDecoder(context).decode(request.audioUri, work, ::checkCancelled, onLog) {
                onProgress(it * 10 / 100, "Decoding audio…")
            }
            val audio = if (decoded.sampleRate == PcmResampler.MODEL_RATE) decoded else {
                onLog("Resampling " + decoded.sampleRate + " → 44100 Hz")
                PcmResampler.convert(decoded, File(work, "model.pcm"),
                    checkCancelled = ::checkCancelled,
                    progress = { onProgress(10 + it * 10 / 100, "Converting to 44.1 kHz…") })
            }
            if (audio.file != decoded.file) decoded.file.delete()
            val manager = ModelManager(context)
            models.forEachIndexed { index, model ->
                checkCancelled()
                fun progress(pct: Int, message: String) {
                    onProgress(20 + (index * 80 + pct * 80 / 100) / models.size,
                        model.primaryStem + ": " + message)
                }
                onLog("Model: " + model.file)
                val file = manager.ensureModel(model, ::checkCancelled) {
                    progress(it / 5, "Checking/downloading model " + it + "%")
                }
                val separator = OnnxMdxSeparator(context, onLog) { pct, msg ->
                    progress(20 + pct * 80 / 100, msg)
                }
                activeSeparator = separator
                separator.cancelled = cancelled
                try {
                    separator.separate(audio, model, file, request.selectedStemNames, request.backend, request.output)
                } catch (e: ai.onnxruntime.OrtException) {
                    checkCancelled()
                    if (request.backend != InferenceBackend.AUTO) throw e
                    onLog("QNN GPU inference failed; retrying this model on CPU: " + e.message)
                    separator.separate(audio, model, file, request.selectedStemNames, InferenceBackend.CPU, request.output)
                } finally {
                    activeSeparator = null
                }
            }
            checkCancelled()
            onProgress(100, "Finished")
        } finally {
            work.deleteRecursively()
        }
    }

    override fun cancel() {
        cancelled = true
        activeSeparator?.cancelled = true
    }
}
