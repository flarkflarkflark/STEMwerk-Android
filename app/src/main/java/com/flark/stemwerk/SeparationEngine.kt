package com.flark.stemwerk

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.concurrent.thread

/**
 * MVP engine (NO real Demucs yet).
 *
 * Current behavior:
 * - Requires WAV PCM16 input.
 * - Writes multiple WAV outputs that currently contain the same audio (dummy stems).
 *
 * Next: replace with Demucs inference and real stems.
 */
class SeparationEngine(private val ctx: Context) {

    fun run(
        audioUri: Uri,
        stems: Int,
        modelPath: String,
        outDir: File,
        onLog: (String) -> Unit,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        thread {
            try {
                onLog("Audio URI: $audioUri")
                onLog("Model path: $modelPath")

                val names = when (stems) {
                    2 -> listOf("vocals", "instrumental")
                    4 -> listOf("drums", "bass", "other", "vocals")
                    else -> throw RuntimeException("Only 2/4 supported right now")
                }

                onProgress(5, "Reading WAV…")
                val bytes = ctx.contentResolver.openInputStream(audioUri).use { ins ->
                    if (ins == null) throw RuntimeException("Unable to open input stream")
                    ins.readBytes()
                }

                if (!WavUtil.sniffWav(bytes)) {
                    throw RuntimeException("Please select a WAV file (PCM16)")
                }

                val (info, pcm) = WavUtil.parsePcm16(bytes)
                onLog("WAV: ${info.sampleRate} Hz, ${info.channels} ch, ${info.bitsPerSample} bit, data=${info.dataSize} bytes")

                onProgress(20, "Writing stems (dummy)…")
                for ((idx, name) in names.withIndex()) {
                    val pct = 20 + ((idx.toFloat() / names.size) * 75).toInt()
                    onProgress(pct, "Writing $name.wav…")
                    val outFile = File(outDir, "$name.wav")
                    WavUtil.writePcm16Wav(outFile, info, pcm)
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
