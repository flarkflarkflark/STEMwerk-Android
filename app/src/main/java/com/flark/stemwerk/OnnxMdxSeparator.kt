package com.flark.stemwerk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.OutputStream
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.jtransforms.fft.FloatFFT_1D

/**
 * Real MDX-Net separator for the public UVR vocals model.
 *
 * The implementation mirrors the reference MDX layout:
 * stereo waveform -> 4-channel complex STFT -> ONNX -> inverse STFT.
 * It processes one model window at a time, so a whole song is never copied
 * into a second large float buffer.
 */
class OnnxMdxSeparator(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onProgress: (Int, String) -> Unit,
) {
    @Volatile
    var cancelled: Boolean = false

    private data class DecodedChunk(
        val primaryLeft: FloatArray,
        val primaryRight: FloatArray,
    )

    private data class SessionHandle(
        val session: OrtSession,
        val providerName: String,
    )

    fun separate(
        audio: PcmAudio,
        model: ModelManager.ModelEntry,
        modelFile: File,
        qnnModelFile: File?,
        selectedStemNames: List<String>,
        backend: InferenceBackend,
        output: OutputSink,
    ) {
        require(audio.sampleRate == PcmResampler.MODEL_RATE)
        require(audio.frames <= Int.MAX_VALUE) { "Audio is too long" }
        val wavInfo = WavUtil.WavInfo(audio.sampleRate, audio.channels, 16, 0, 0)
        val totalSamples = audio.frames.toInt()
        if (totalSamples <= 0) throw IllegalArgumentException("The selected WAV contains no samples")

        val trim = model.nFft / 2
        val chunkSize = model.hop * (model.dimT - 1)
        val generatedSize = chunkSize - (2 * trim)
        require(generatedSize > 0) { "Invalid MDX window configuration" }

        // The reference implementation deliberately adds one full generation
        // window when the source length is exactly divisible by generatedSize.
        val segmentCount = ((totalSamples.toLong() + generatedSize - 1) / generatedSize).toInt()

        val selected = selectedStemNames.map { it.lowercase(Locale.US) }.toSet()
        val wantPrimary = selected.contains(model.primaryStem)
        val wantSecondary = selected.contains(model.secondaryStem)
        if (!wantPrimary && !wantSecondary) {
            throw IllegalArgumentException(
                "Select ${model.primaryStem} and/or ${model.secondaryStem}"
            )
        }

        onLog("Input: ${wavInfo.sampleRate} Hz, ${wavInfo.channels} channel(s), $totalSamples samples")
        onLog("MDX window: $chunkSize samples, $segmentCount segment(s)")
        onLog("Downloading/checking model: ${model.file}")

        val env = OrtEnvironment.getEnvironment()
        val handle = createSession(env, modelFile, qnnModelFile, backend)
        onLog("Inference provider: ${handle.providerName}")

        val primaryRaw = File.createTempFile("stemwerk-primary-", ".pcm", context.cacheDir)
        val secondaryRaw = File.createTempFile("stemwerk-secondary-", ".pcm", context.cacheDir)

        try {
            primaryRaw.outputStream().buffered().use { primaryOut ->
                secondaryRaw.outputStream().buffered().use { secondaryOut ->
                    handle.session.use { session -> PcmReader(audio).use { pcm ->
                        val fft = FloatFFT_1D(model.nFft.toLong())
                        for (segment in 0 until segmentCount) {
                            check(!cancelled) { "Cancelled" }

                            val inputLeft = FloatArray(chunkSize)
                            val inputRight = FloatArray(chunkSize)
                            fillChunk(
                                pcm = pcm,
                                channels = wavInfo.channels,
                                totalSamples = totalSamples,
                                startSample = segment * generatedSize,
                                trim = trim,
                                inputLeft = inputLeft,
                                inputRight = inputRight,
                            )

                            val inputTensor = makeInputTensor(env, inputLeft, inputRight, model, fft)
                            val decoded = try {
                                session.run(mapOf("input" to inputTensor)).use { result ->
                                    val outputTensor = result.get(0) as OnnxTensor
                                    val values = outputTensor.floatBuffer.duplicate()
                                    val output = FloatArray(values.remaining())
                                    values.get(output)
                                    decodeOutput(output, model, fft)
                                }
                            } finally {
                                inputTensor.close()
                            }

                            val samplesToWrite = min(generatedSize, totalSamples - segment * generatedSize)
                            writeChunkPcm16(
                                primaryOut,
                                decoded.primaryLeft,
                                decoded.primaryRight,
                                trim,
                                samplesToWrite,
                            )
                            writeChunkPcm16(
                                secondaryOut,
                                inputLeft,
                                inputRight,
                                trim,
                                samplesToWrite,
                                decoded,
                                1f,
                            )

                            val pct = (((segment + 1) * 100L) / segmentCount).toInt()
                            onProgress(pct, "Separating segment " + (segment + 1) + "/" + segmentCount)
                        }
                    } }
                }
            }

            onProgress(100, "Writing stems")
            if (wantPrimary) copyRawAsWav(primaryRaw, model.primaryStem, wavInfo, output)
            if (wantSecondary) copyRawAsWav(secondaryRaw, model.secondaryStem, wavInfo, output)
            onLog("Wrote ${listOfNotNull(
                if (wantPrimary) model.primaryStem else null,
                if (wantSecondary) model.secondaryStem else null,
            ).joinToString()} stem(s)")
        } finally {
            primaryRaw.delete()
            secondaryRaw.delete()
        }
    }

    private fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        qnnModelFile: File?,
        backend: InferenceBackend,
    ): SessionHandle {
        fun cpu(): SessionHandle {
            return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors().coerceAtMost(4)))
            SessionHandle(
                env.createSession(modelFile.absolutePath, options),
                "CPU",
            )
            }
        }

        fun nnapi(): SessionHandle {
            return OrtSession.SessionOptions().use { options ->
            options.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
            SessionHandle(
                env.createSession(modelFile.absolutePath, options),
                "NNAPI requested (actual CPU/GPU/NPU assignment depends on device)",
            )
            }
        }

        fun qnnGpu(): SessionHandle {
            // QNN GPU cannot resolve shapes through this model's dynamic batch
            // dimension and rejects the whole graph; qnnModelFile is a
            // static-batch variant (batch fixed to 1, verified bit-identical
            // output) used only for this route. The original modelFile is
            // still used for CPU/NNAPI and stays the AUTO-mode fallback.
            val file = qnnModelFile ?: modelFile
            return OrtSession.SessionOptions().use { options ->
                // A successful QNN session must execute the complete graph on
                // QNN. This prevents a nominal GPU selection from silently
                // running unsupported nodes on ORT CPU.
                options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                options.addQnn(mapOf(
                    "backend_type" to "gpu",
                    "profiling_level" to "off",
                ))
                SessionHandle(
                    env.createSession(file.absolutePath, options),
                    if (qnnModelFile != null) "QNN GPU (Adreno; static-batch model; ORT CPU fallback disabled)"
                    else "QNN GPU (Adreno; ORT CPU fallback disabled)",
                )
            }
        }

        return when (backend) {
            InferenceBackend.CPU -> cpu()
            InferenceBackend.QNN_GPU -> qnnGpu()
            InferenceBackend.NNAPI -> nnapi()
            InferenceBackend.AUTO -> {
                try {
                    qnnGpu()
                } catch (error: Throwable) {
                    onLog("QNN GPU unavailable for this model/device: ${error.message ?: error::class.java.simpleName}")
                    onLog("Falling back to ARM64 CPU")
                    cpu()
                }
            }
        }
    }

    private fun makeInputTensor(
        env: OrtEnvironment,
        left: FloatArray,
        right: FloatArray,
        model: ModelManager.ModelEntry,
        fft: FloatFFT_1D,
    ): OnnxTensor {
        val spectrum = stft(left, right, model, fft)
        val shape = longArrayOf(1L, 4L, model.dimF.toLong(), model.dimT.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(spectrum), shape)
    }

    private fun stft(
        left: FloatArray,
        right: FloatArray,
        model: ModelManager.ModelEntry,
        fft: FloatFFT_1D,
    ): FloatArray {
        val frameCount = model.dimT
        val bins = model.dimF
        val n = model.nFft
        val trim = n / 2
        val out = FloatArray(4 * bins * frameCount)
        val leftPadded = reflectPad(left, trim)
        val rightPadded = reflectPad(right, trim)
        val frame = FloatArray(2 * n)
        val window = hann(n)
        val plane = bins * frameCount

        fun fillChannel(source: FloatArray, channel: Int) {
            for (time in 0 until frameCount) {
                java.util.Arrays.fill(frame, 0f)
                val start = time * model.hop
                for (i in 0 until n) frame[i] = source[start + i] * window[i]
                fft.realForwardFull(frame)
                val base = channel * plane
                val imagBase = (channel + 1) * plane
                for (frequency in 0 until bins) {
                    val spectrumIndex = 2 * frequency
                    val offset = base + frequency * frameCount + time
                    out[offset] = frame[spectrumIndex]
                    out[imagBase + frequency * frameCount + time] = frame[spectrumIndex + 1]
                }
            }
        }

        fillChannel(leftPadded, 0)
        fillChannel(rightPadded, 2)
        return out
    }

    private fun decodeOutput(
        values: FloatArray,
        model: ModelManager.ModelEntry,
        fft: FloatFFT_1D,
    ): DecodedChunk {
        val expected = 4 * model.dimF * model.dimT
        if (values.size != expected) {
            throw IllegalStateException(
                "Unexpected ONNX output size ${values.size}; expected at least $expected"
            )
        }

        val primaryLeft = istft(values, 0, model, fft)
        val primaryRight = istft(values, 2, model, fft)
        for (i in primaryLeft.indices) {
            primaryLeft[i] *= model.compensation
            primaryRight[i] *= model.compensation
        }
        return DecodedChunk(primaryLeft, primaryRight)
    }

    private fun istft(
        values: FloatArray,
        channel: Int,
        model: ModelManager.ModelEntry,
        fft: FloatFFT_1D,
    ): FloatArray {
        val n = model.nFft
        val bins = model.dimF
        val frames = model.dimT
        val trim = n / 2
        val paddedLength = (frames - 1) * model.hop + n
        val output = FloatArray(paddedLength)
        val normalizer = FloatArray(paddedLength)
        val complex = FloatArray(2 * n)
        val window = hann(n)
        val plane = bins * frames
        val realPlane = channel * plane
        val imagPlane = (channel + 1) * plane

        for (time in 0 until frames) {
            java.util.Arrays.fill(complex, 0f)
            for (frequency in 0 until bins) {
                val offset = frequency * frames + time
                val real = values[realPlane + offset]
                val imag = values[imagPlane + offset]
                complex[2 * frequency] = real
                complex[2 * frequency + 1] = imag
                if (frequency > 0 && frequency < n / 2) {
                    val mirror = n - frequency
                    complex[2 * mirror] = real
                    complex[2 * mirror + 1] = -imag
                }
            }

            fft.complexInverse(complex, true)
            val start = time * model.hop
            for (i in 0 until n) {
                val sample = complex[2 * i] * window[i]
                output[start + i] += sample
                normalizer[start + i] += window[i] * window[i]
            }
        }

        val chunkSize = model.hop * (model.dimT - 1)
        val result = FloatArray(chunkSize)
        for (i in result.indices) {
            val denominator = normalizer[trim + i]
            result[i] = if (denominator > 1.0e-8f) {
                output[trim + i] / denominator
            } else {
                0f
            }
        }
        return result
    }

    private fun fillChunk(
        pcm: PcmReader,
        channels: Int,
        totalSamples: Int,
        startSample: Int,
        trim: Int,
        inputLeft: FloatArray,
        inputRight: FloatArray,
    ) {
        for (i in inputLeft.indices) {
            val sourceSample = startSample + i - trim
            if (sourceSample in 0 until totalSamples) {
                inputLeft[i] = pcm.sample(sourceSample.toLong(), 0)
                inputRight[i] = pcm.sample(sourceSample.toLong(), min(1, channels - 1))
            }
        }
    }

    private fun reflectPad(input: FloatArray, pad: Int): FloatArray {
        val output = FloatArray(input.size + 2 * pad)
        input.copyInto(output, pad)
        for (i in 0 until pad) {
            output[pad - 1 - i] = input[i + 1]
            output[pad + input.size + i] = input[input.size - 2 - i]
        }
        return output
    }

    private fun hann(size: Int): FloatArray =
        FloatArray(size) { i ->
            (0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * i / size)).toFloat()
        }

    private fun writeChunkPcm16(
        out: OutputStream,
        left: FloatArray,
        right: FloatArray,
        trim: Int,
        count: Int,
        decoded: DecodedChunk? = null,
        compensation: Float = 1f,
    ) {
        val bytes = ByteArray(count * 4)
        var at = 0
        for (i in 0 until count) {
            val leftValue: Float
            val rightValue: Float
            if (decoded == null) {
                leftValue = left[trim + i]
                rightValue = right[trim + i]
            } else {
                leftValue = left[trim + i] - decoded.primaryLeft[trim + i] * compensation
                rightValue = right[trim + i] - decoded.primaryRight[trim + i] * compensation
            }
            val l = (leftValue.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            val r = (rightValue.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[at++] = (l.toInt() and 0xFF).toByte()
            bytes[at++] = (l.toInt() ushr 8).toByte()
            bytes[at++] = (r.toInt() and 0xFF).toByte()
            bytes[at++] = (r.toInt() ushr 8).toByte()
        }
        out.write(bytes)
    }

    private fun copyRawAsWav(
        raw: File,
        stemName: String,
        inputInfo: WavUtil.WavInfo,
        output: OutputSink,
    ) {
        output.open("${stemName}.wav", "audio/wav").use { target ->
            val outputInfo = inputInfo.copy(channels = 2)
            WavUtil.writePcm16WavHeader(target, outputInfo, raw.length())
            raw.inputStream().buffered().use { it.copyTo(target, 1024 * 256) }
        }
    }
}
