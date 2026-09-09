package com.flark.stemwerk

import ai.onnxruntime.*
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.*

/** Benchmarks real model execution; no user audio is needed or uploaded. */
class AccelerationProbe(private val context: Context) {
    data class Measurement(val milliseconds: Double, val output: FloatArray, val providers: Map<String, Int>, val opProviders: Map<String, Map<String, Int>>)

    fun run(modelId: String, checkCancelled: () -> Unit, log: (String) -> Unit) {
        log("STEMwerk " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        log(Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE)
        log("ABI: " + Build.SUPPORTED_ABIS.joinToString() + "; CPU cores: " + Runtime.getRuntime().availableProcessors())
        log("Real model, deterministic test spectrum. One warm-up + two timed runs per route.")
        log("Times exclude decoding, STFT, downloads and model loading. Profiling enabled on both routes.")
        log("QNN GPU test targets Snapdragon Adreno through Qualcomm's official execution provider.")
        log("ORT CPU fallback is disabled inside the QNN session: a QNN result must run the complete model graph.")
        log("A second QNN GPU + CPU test (CPU fallback allowed) runs after that, to check for partial-GPU speedups.")
        val stems = if (modelId == ModelManager.FOUR_STEMS) ModelManager.STEMS else listOf("vocals", "other")
        val manager = ModelManager(context)
        for (model in ModelManager.selectedModels(modelId, stems)) {
            checkCancelled()
            log("\nModel: " + model.file)
            val file = manager.ensureModel(model, checkCancelled) { if (it % 25 == 0) log("Model check/download: " + it + "%") }
            log("SHA256: " + model.sha256)
            val random = java.util.Random(42)
            val spectrum = FloatArray(4 * model.dimF * model.dimT) { (random.nextGaussian() * 0.1).toFloat() }
            log("Running CPU warm-up and measurements…")
            val cpu = measure(model, file, spectrum, false, checkCancelled)
            log("CPU mean: " + "%.1f".format(cpu.milliseconds) + " ms")
            try {
            log("Running QNN GPU warm-up and measurements…")
                val hardware = measure(model, file, spectrum, true, checkCancelled)
                val qnnEvents = hardware.providers.filterKeys { it.contains("qnn", true) }.values.sum()
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
                log("QNN GPU mean: " + "%.1f".format(hardware.milliseconds) + " ms; CPU/QNN: " + "%.2f".format(speedup) + "x")
                log("Relative RMS difference vs CPU: " + "%.6f".format(relativeError))
                when {
                    qnnEvents == 0 -> log("NO QNN ACCELERATION CONFIRMED: no QNN operations recorded.")
                    relativeError > 0.02 -> log("QNN GPU ACTIVE, OUTPUT CHECK FAILED: use CPU; result differs by more than 2% relative RMS.")
                    else -> {
                        log("QNN GPU ACCELERATION CONFIRMED; CPU comparison passed.")
                        log(if (speedup > 1.05) "QNN GPU was faster in this test." else "No useful speed gain measured; CPU may be preferable.")
                    }
                }
                log("QNN backend_type=gpu identifies the Adreno route. Thermal state can affect timing.")
            } catch (e: Throwable) {
                checkCancelled()
                log("QNN GPU UNAVAILABLE OR FAILED for this model: " + (e.message ?: e.javaClass.simpleName))
                log("CPU completed successfully.")
            }
            try {
                log("Running QNN GPU + CPU (fallback allowed) warm-up and measurements…")
                val mixed = measure(model, file, spectrum, true, checkCancelled, allowCpuFallback = true)
                val qnnEvents = mixed.providers.filterKeys { it.contains("qnn", true) }.values.sum()
                val cpuEvents = mixed.providers.filterKeys { it.contains("cpu", true) }.values.sum()
                log("Executed provider events (mixed): " + mixed.providers)
                val cpuOps = mixed.opProviders.filterValues { byProvider -> byProvider.keys.any { it.contains("cpu", true) } }.keys
                if (cpuOps.isNotEmpty()) log("Op types still on CPU (mixed): " + cpuOps.sorted().joinToString())
                if (qnnEvents == 0) {
                    log("ALL OPERATIONS RAN ON CPU (mixed): not a GPU result, even though CPU fallback was allowed.")
                } else {
                    var error = 0.0
                    var reference = 0.0
                    require(cpu.output.size == mixed.output.size) { "Different output tensor shapes" }
                    for (i in cpu.output.indices) {
                        val difference = cpu.output[i].toDouble() - mixed.output[i]
                        error += difference * difference
                        reference += cpu.output[i].toDouble() * cpu.output[i]
                    }
                    val relativeError = sqrt(error / max(reference, 1e-12))
                    val speedup = cpu.milliseconds / mixed.milliseconds
                    log("QNN GPU + CPU (mixed) mean: " + "%.1f".format(mixed.milliseconds) + " ms; CPU/mixed: " + "%.2f".format(speedup) + "x")
                    log("Relative RMS difference vs CPU (mixed): " + "%.6f".format(relativeError))
                    when {
                        relativeError > 0.02 -> log("QNN GPU + CPU (mixed) ACTIVE, OUTPUT CHECK FAILED: use CPU; result differs by more than 2% relative RMS.")
                        cpuEvents > 0 -> {
                            log("QNN GPU + CPU (mixed) PARTIAL ACCELERATION CONFIRMED: some operations ran on QNN, some on CPU; CPU comparison passed.")
                            log(if (speedup > 1.05) "Mixed route was faster in this test." else "No useful speed gain measured from partial QNN use; CPU may be preferable.")
                        }
                        else -> {
                            log("QNN GPU + CPU (mixed) FULL ACCELERATION CONFIRMED: entire graph ran on QNN; CPU comparison passed.")
                            log(if (speedup > 1.05) "Mixed route was faster in this test." else "No useful speed gain measured; CPU may be preferable.")
                        }
                    }
                }
            } catch (e: Throwable) {
                checkCancelled()
                log("QNN GPU + CPU (mixed) UNAVAILABLE OR FAILED for this model: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        val diagFile = File(context.filesDir, "diag/static_batch_vocals.onnx")
        if (diagFile.exists()) {
            log("\nDiagnostic: kuielab_a_vocals.onnx with its dynamic batch dimension fixed to 1.")
            log("Offline check: this variant produced bit-identical output to the original on CPU for the same input.")
            log("Runs strict QNN GPU (CPU fallback disabled), same as the whole-graph test above.")
            try {
                val vocalsModel = ModelManager.MODELS.first { it.id == "kuielab-vocals" }
                val random = java.util.Random(42)
                val spectrum = FloatArray(4 * vocalsModel.dimF * vocalsModel.dimT) { (random.nextGaussian() * 0.1).toFloat() }
                val result = measure(vocalsModel, diagFile, spectrum, true, checkCancelled)
                val qnnEvents = result.providers.filterKeys { it.contains("qnn", true) }.values.sum()
                log("Executed provider events (static-batch diagnostic): " + result.providers)
                log(if (qnnEvents > 0) "STATIC BATCH DIAGNOSTIC: QNN executed the full graph — dynamic batch size was the blocker."
                    else "STATIC BATCH DIAGNOSTIC: QNN executed nothing even with a static batch — dynamic batch size alone does not explain the rejection.")
            } catch (e: Throwable) {
                checkCancelled()
                log("Static-batch diagnostic UNAVAILABLE OR FAILED: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        log("\nTest complete. Share this report to inspect the results.")
    }

    private fun measure(model: ModelManager.ModelEntry, file: File, spectrum: FloatArray,
        qnnGpu: Boolean, checkCancelled: () -> Unit, allowCpuFallback: Boolean = false): Measurement {
        val env = OrtEnvironment.getEnvironment()
        val work = File(context.cacheDir, "probe-" + java.util.UUID.randomUUID())
        check(work.mkdirs())
        try {
            return OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                options.enableProfiling(File(work, "profile").absolutePath)
                if (qnnGpu) {
                    options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
                    if (!allowCpuFallback) options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    options.addQnn(mapOf(
                        "backend_type" to "gpu",
                        "profiling_level" to "off",
                    ))
                }
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
                        val opProviders = mutableMapOf<String, MutableMap<String, Int>>()
                        for (i in 0 until events.length()) {
                            val args = events.getJSONObject(i).optJSONObject("args") ?: continue
                            val provider = args.optString("provider")
                            if (provider.isEmpty()) continue
                            providers[provider] = (providers[provider] ?: 0) + 1
                            val opType = args.optString("op_name").ifEmpty { "?" }
                            val byProvider = opProviders.getOrPut(opType) { mutableMapOf() }
                            byProvider[provider] = (byProvider[provider] ?: 0) + 1
                        }
                        Measurement(elapsed / 2_000_000.0, output, providers, opProviders)
                    }
                }
            }
        } finally { work.deleteRecursively() }
    }
}
