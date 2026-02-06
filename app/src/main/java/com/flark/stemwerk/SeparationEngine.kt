package com.flark.stemwerk

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.concurrent.thread

/**
 * MVP engine (NO real Demucs yet, NO FFmpeg yet).
 *
 * This produces "dummy stems" by copying the input file bytes into multiple output files.
 * It validates:
 * - URI handling
 * - background processing window
 * - output folder creation
 *
 * Next step: replace this with real audio decode + Demucs inference + WAV encoding.
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

                // Read all bytes once (ok for short test files; later stream+decode)
                onProgress(5, "Reading input…")
                val bytes = ctx.contentResolver.openInputStream(audioUri).use { ins ->
                    if (ins == null) throw RuntimeException("Unable to open input stream")
                    ins.readBytes()
                }

                onProgress(15, "Writing dummy stems…")
                for ((idx, name) in names.withIndex()) {
                    val pct = 15 + ((idx.toFloat() / names.size) * 80).toInt()
                    onProgress(pct, "Writing $name (copy)…")
                    val outFile = File(outDir, "$name.audio")
                    outFile.writeBytes(bytes)
                }

                onProgress(98, "Done")
                onDone(true, "Finished (dummy stems). Next: Demucs + real audio encoding")
            } catch (e: Exception) {
                onLog("Error: ${e.message}")
                onDone(false, "Failed: ${e.message}")
            }
        }
    }
}
