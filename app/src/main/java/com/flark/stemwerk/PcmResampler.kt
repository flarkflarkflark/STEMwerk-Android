package com.flark.stemwerk

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.*

/** Disk-backed interleaved PCM16. Ownership stays with the caller. */
data class PcmAudio(val file: File, val sampleRate: Int, val channels: Int) {
    val frames: Long get() = file.length() / (2 * channels)
}

/** Small read cache shared by resampling and model-window loading. */
class PcmReader(private val audio: PcmAudio) : Closeable {
    private val frames = audio.frames
    private val input = RandomAccessFile(audio.file, "r")
    private val bytes = ByteArray(64 * 1024)
    private var block = -1L
    private var size = 0

    fun sample(frame: Long, channel: Int): Float {
        if (frame < 0 || frame >= frames) return 0f
        val offset = (frame * audio.channels + channel) * 2
        val nextBlock = offset / bytes.size
        if (nextBlock != block) {
            input.seek(nextBlock * bytes.size)
            size = input.read(bytes)
            block = nextBlock
        }
        val at = (offset % bytes.size).toInt()
        check(at + 1 < size) { "Truncated PCM sample" }
        val value = (bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)
        return value.toShort().toInt() / 32768f
    }

    override fun close() = input.close()
}

/** Windowed-sinc conversion, including a low-pass filter for downsampling. */
object PcmResampler {
    const val MODEL_RATE = 44100
    private const val PHASES = 1024

    fun convert(
        source: PcmAudio,
        target: File,
        targetRate: Int = MODEL_RATE,
        checkCancelled: () -> Unit = {},
        progress: (Int) -> Unit = {},
    ): PcmAudio {
        require(source.sampleRate in 8000..192000 && targetRate in 8000..192000)
        require(source.channels in 1..2)
        require(source.frames > 0) { "Audio contains no samples" }
        require(source.file.canonicalPath != target.canonicalPath)
        if (source.sampleRate == targetRate) {
            source.file.inputStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
            }
            return source.copy(file = target)
        }
        val ratio = source.sampleRate.toDouble() / targetRate
        val cutoff = min(1.0, 1.0 / ratio) * 0.94
        val radius = ceil(32.0 / cutoff).toInt()
        val taps = radius * 2
        val kernels = Array(PHASES) { phase ->
            val fraction = phase.toDouble() / PHASES
            val weights = DoubleArray(taps) { tap ->
                val distance = tap - radius + 1 - fraction
                val x = Math.PI * distance * cutoff
                val sinc = if (abs(x) < 1e-12) cutoff else cutoff * sin(x) / x
                val window = if (abs(distance) <= radius)
                    0.42 + 0.5 * cos(Math.PI * distance / radius) +
                        0.08 * cos(2 * Math.PI * distance / radius)
                else 0.0
                sinc * window
            }
            val sum = weights.sum()
            FloatArray(taps) { (weights[it] / sum).toFloat() }
        }
        val sourceFrames = source.frames
        val outputFrames = max(1L, (sourceFrames.toDouble() / ratio).roundToLong())
        target.outputStream().buffered().use { out ->
            PcmReader(source).use { input ->
                val buffer = ByteArray(4096 * source.channels * 2)
                var frame = 0L
                var lastPct = -1
                while (frame < outputFrames) {
                    checkCancelled()
                    val count = min(4096L, outputFrames - frame).toInt()
                    var at = 0
                    repeat(count) { index ->
                        val position = (frame + index) * ratio
                        val center = floor(position).toLong()
                        val phase = ((position - center) * PHASES).toInt().coerceIn(0, PHASES - 1)
                        val weights = kernels[phase]
                        repeat(source.channels) { channel ->
                            var value = 0.0
                            for (tap in 0 until taps) {
                                // Extend boundary samples; retain DC and avoid edge attenuation.
                                val sample = (center + tap - radius + 1).coerceIn(0, sourceFrames - 1)
                                value += input.sample(sample, channel) * weights[tap]
                            }
                            val pcm = (value * 32768).roundToInt().coerceIn(-32768, 32767)
                            buffer[at++] = pcm.toByte()
                            buffer[at++] = (pcm shr 8).toByte()
                        }
                    }
                    out.write(buffer, 0, at)
                    frame += count
                    val pct = (frame * 100 / outputFrames).toInt()
                    if (pct != lastPct) { progress(pct); lastPct = pct }
                }
            }
        }
        return PcmAudio(target, targetRate, source.channels)
    }
}
