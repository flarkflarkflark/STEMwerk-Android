package com.flark.stemwerk

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.concurrent.thread

/**
 * MVP engine:
 * - Does NOT run real Demucs yet.
 * - It produces dummy stems by decoding input and re-encoding the same audio into multiple files.
 *
 * This validates:
 * - file picker URI handling
 * - background processing window
 * - ffmpeg integration
 * - output layout
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
                onProgress(2, "Copying input…")

                // Copy input URI into a temp file so ffmpeg can read it reliably
                val inFile = File(outDir, "input")
                ctx.contentResolver.openInputStream(audioUri).use { ins ->
                    if (ins == null) throw RuntimeException("Unable to open input stream")
                    inFile.outputStream().use { outs ->
                        ins.copyTo(outs)
                    }
                }

                onProgress(10, "Running (dummy) separation…")

                val names = when (stems) {
                    2 -> listOf("vocals", "instrumental")
                    4 -> listOf("drums", "bass", "other", "vocals")
                    else -> throw RuntimeException("Only 2/4 supported right now")
                }

                // Encode same audio to multiple outputs
                for ((idx, name) in names.withIndex()) {
                    val pct = 10 + ((idx.toFloat() / names.size) * 80).toInt()
                    onProgress(pct, "Writing $name.wav…")
                    val outFile = File(outDir, "$name.wav")
                    val cmd = "-y -i ${inFile.absolutePath} -acodec pcm_s16le -ar 44100 -ac 2 ${outFile.absolutePath}"
                    onLog("ffmpeg $cmd")
                    val session = FFmpegKit.execute(cmd)
                    val rc = session.returnCode
                    if (!ReturnCode.isSuccess(rc)) {
                        val fail = session.failStackTrace
                        onLog("FFmpeg failed: $rc")
                        if (!fail.isNullOrBlank()) onLog(fail)
                        throw RuntimeException("FFmpeg failed writing $name")
                    }
                }

                onProgress(95, "Done")
                onDone(true, "Finished (dummy stems) — Demucs not integrated yet")
            } catch (e: Exception) {
                onLog("Error: ${e.message}")
                onDone(false, "Failed: ${e.message}")
            }
        }
    }
}
