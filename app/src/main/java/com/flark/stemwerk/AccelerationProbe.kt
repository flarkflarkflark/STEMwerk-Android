package com.flark.stemwerk

import ai.onnxruntime.*
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.*

/** Benchmarks real model execution; no user audio is needed or uploaded. */
class AccelerationProbe(private val context: Context) {
    data class Measurement(val milliseconds: Double, val output: FloatArray, val providers: Map<String, Int>)

    fun run(modelId: String, checkCancelled: () -> Unit, log: (String) -> Unit) {
        log("STEMwerk " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        log(Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE)
        log("ABI: " + Build.SUPPORTED_ABIS.joinToString() + "; CPU cores: " + Runtime.getRuntime().availableProcessors())
        log("Real model, deterministic test spectrum. One warm-up + two timed runs per route.")
        log("Times exclude decoding, STFT, downloads and model loading. Profiling enabled on both routes.")
        log("NNAPI CPU_DISABLED: no Android reference CPU device. Unsupported operators may still use ORT CPU.")
        val stems = if (modelId == ModelManager.FOUR_STEMS) ModelManager.STEMS else listOf("vocals", "other")
        val manager = ModelManager(context)
        for (model in ModelManager.selectedModels(modelId, stems)) {
            checkCancelled()
            log("\nModel: " + model.file)
            val file = manager.ensureModel(model, checkCancelled) { if (it % 25 == 0) log("Model check/download: " + it + "%") }
            log("SHA256: " + model.sha256)
            val random = java.util.Random(42)
            val spectrum = FloatArray(4 * model.dimF * model.dimT) { (random.nextGaussian() * 0.1).toFloat() }
            val cpu = measure(model, file, spectrum, false, checkCancelled)
            log("CPU mean: " + "%.1f".format(cpu.milliseconds) + " ms")
            try {
                val hardware = measure(model, file, spectrum, true, checkCancelled)
                val nnapiEvents = hardware.providers.filterKeys { it.contains("nnapi", true) }.values.sum()
                log("Executed provider events: " + hardware.providers)
                var error = 0.0
                var reference = 0.0
                require(cpu.output.size == hardware.output.size) { "Different output tensor shapes" }
                for (i in cpu.output.indices) {
                    val difference = cpu.output[i].toDouble() - hardware.output[i]
                    error += difference * difference
                    reference += cpu.output[i].toDouble() * cpu.output[i]
                }
                val relativeError = sqrt(error / max(reference, 1e-12))
                val speedup = cpu.milliseconds / hardware.milliseconds
                log("NNAPI mean: " + "%.1f".format(hardware.milliseconds) + " ms; CPU/NNAPI: " + "%.2f".format(speedup) + "x")
                log("Relative RMS difference vs CPU: " + "%.6f".format(relativeError))
                when {
                    nnapiEvents == 0 -> log("NO ACCELERATION CONFIRMED: no NNAPI operations recorded; CPU fallback.")
                    relativeError > 0.02 -> log("NNAPI ACTIVE, OUTPUT CHECK FAILED: use CPU; result differs by more than 2% relative RMS.")
                    else -> {
                        log("NNAPI ACCELERATION CONFIRMED; CPU comparison passed.")
                        log(if (speedup > 1.05) "NNAPI was faster in this test." else "No useful speed gain measured; CPU may be preferable.")
                    }
                }
                log("This identifies NNAPI delegation, not the exact GPU/NPU driver. Thermal state can affect timing.")
            } catch (e: Exception) {
                checkCancelled()
                log("NNAPI UNAVAILABLE OR FAILED for this model: " + (e.message ?: e.javaClass.simpleName))
                log("CPU completed successfully.")
            }
        }
        log("\nTest complete. Share this report to inspect the results.")
    }

    private fun measure(model: ModelManager.ModelEntry, file: File, spectrum: FloatArray,
        nnapi: Boolean, checkCancelled: () -> Unit): Measurement {
        val env = OrtEnvironment.getEnvironment()
        val work = File(context.cacheDir, "probe-" + java.util.UUID.randomUUID())
        check(work.mkdirs())
        try {
            return OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                options.enableProfiling(File(work, "profile").absolutePath)
                if (nnapi) options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                env.createSession(file.absolutePath, options).use { session ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(spectrum),
                        longArrayOf(1, 4, model.dimF.toLong(), model.dimT.toLong())).use { input ->
                        checkCancelled()
                        session.run(mapOf("input" to input)).use { }
                        var elapsed = 0L
                        var output = FloatArray(0)
                        repeat(2) {
                            checkCancelled()
                            val start = SystemClock.elapsedRealtimeNanos()
                            session.run(mapOf("input" to input)).use { result ->
                                elapsed += SystemClock.elapsedRealtimeNanos() - start
                                val values = (result.get(0) as OnnxTensor).floatBuffer
                                output = FloatArray(values.remaining())
                                values.get(output)
                            }
                        }
                        require(output.size == spectrum.size && output.all { it.isFinite() }) { "Invalid model output" }
                        val profile = File(session.endProfiling())
                        val events = JSONArray(profile.readText())
                        val providers = mutableMapOf<String, Int>()
                        for (i in 0 until events.length()) {
                            val args = events.getJSONObject(i).optJSONObject("args") ?: continue
                            val provider = args.optString("provider")
                            if (provider.isNotEmpty()) providers[provider] = (providers[provider] ?: 0) + 1
                        }
                        Measurement(elapsed / 2_000_000.0, output, providers)
                    }
                }
            }
        } finally { work.deleteRecursively() }
    }
}

