package com.flark.stemwerk

import android.content.Context
import android.net.Uri
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.math.ceil

/**
 * MVP engine (NO real Demucs yet).
 *
 * Current behavior:
 * - Requires WAV PCM16 input.
 * - Writes selected stems as WAV outputs that currently contain the same audio (dummy stems).
 * - Shows chunk progress (like the real Demucs path will).
 */
class SeparationEngine(private val ctx: Context) {

    fun run(
        audioUri: Uri,
        stems: Int,
        selectedStemNames: List<String>,
        modelPath: String,
        output: OutputSink,
        onLog: (String) -> Unit,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        thread {
            try {
                onLog("Audio URI: $audioUri")
                onLog("Model path: $modelPath")

                val available = when (stems) {
                    2 -> listOf("vocals", "instrumental")
                    4 -> listOf("drums", "bass", "other", "vocals")
                    else -> throw RuntimeException("Only 2/4 supported right now")
                }

                val names = selectedStemNames
                    .map { it.lowercase() }
                    .filter { it in available }
                    .distinct()

                if (names.isEmpty()) {
                    throw RuntimeException("No stems selected")
                }

                onProgress(3, "Reading WAV…")
                val bytes = ctx.contentResolver.openInputStream(audioUri).use { ins ->
                    if (ins == null) throw RuntimeException("Unable to open input stream")
                    ins.readBytes()
                }

                if (!WavUtil.sniffWav(bytes)) {
                    throw RuntimeException("Please select a WAV file (PCM16)")
                }

                val (info, pcm) = WavUtil.parsePcm16(bytes)
                val bytesPerFrame = info.channels * 2 // PCM16
                val totalFrames = pcm.size / bytesPerFrame

                onLog("WAV: ${info.sampleRate} Hz, ${info.channels} ch, ${info.bitsPerSample} bit")
                onLog("Frames: $totalFrames")

                // Chunk plan (used later for Demucs overlap-add)
                val segmentSec = 10
                val overlapSec = 1
                val segmentFrames = segmentSec * info.sampleRate
                val overlapFrames = overlapSec * info.sampleRate
                val hopFrames = (segmentFrames - overlapFrames).coerceAtLeast(1)
                val chunks = if (totalFrames <= 0) 0 else ceil((totalFrames.toDouble() - overlapFrames) / hopFrames)
                    .toInt()
                    .coerceAtLeast(1)

                onLog("Chunking: segment=${segmentSec}s overlap=${overlapSec}s hopFrames=$hopFrames chunks=$chunks")

                for (i in 1..chunks) {
                    val pct = 5 + ((i.toFloat() / chunks) * 55).toInt().coerceIn(0, 60)
                    onProgress(pct, "Processing chunk $i/$chunks…")
                    // (dummy path: no heavy work here yet)
                }

                onProgress(70, "Writing stems (dummy)…")
                for ((idx, name) in names.withIndex()) {
                    val pct = 70 + ((idx.toFloat() / names.size) * 25).toInt().coerceIn(70, 95)
                    onProgress(pct, "Writing $name.wav…")

                    val os: OutputStream = output.open("$name.wav", "audio/wav")
                    os.use { out ->
                        WavUtil.writePcm16Wav(out, info, pcm)
                    }
                }

                onProgress(98, "Done")
                onDone(true, "Finished (dummy WAV stems). Next: Demucs + real separation")
            } catch (e: Exception) {
                onLog("Error: ${e.message}")
                onDone(false, "Failed: ${e.message}")
            }
        }
    }
}
