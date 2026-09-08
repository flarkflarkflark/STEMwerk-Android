package com.flark.stemwerk

import android.content.Context
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and caches the first real mobile model.
 *
 * The model is the public UVR MDX vocals model. It is a genuine ONNX model,
 * not a repository placeholder. The model is used as a 2-stem separator:
 * vocals (primary) and other/instrumental (mixture minus vocals).
 */
class ModelManager(private val ctx: Context) {

    data class ModelEntry(
        val id: String,
        val stems: Int,
        val file: String,
        val url: String,
        val dimF: Int,
        val dimT: Int,
        val nFft: Int,
        val hop: Int,
        val compensation: Float,
        val primaryStem: String,
        val secondaryStem: String,
    )

    private val entries = mapOf(
        "uvr-mdx-voc-ft" to ModelEntry(
            id = "uvr-mdx-voc-ft",
            stems = 2,
            file = "UVR-MDX-NET-Voc_FT.onnx",
            url = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx",
            dimF = 2048,
            dimT = 256,
            nFft = 6144,
            hop = 1024,
            compensation = 1.035f,
            primaryStem = "vocals",
            secondaryStem = "other",
        ),
    )

    private fun modelsDir(): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: ctx.filesDir
        val dir = File(base, "models")
        dir.mkdirs()
        return dir
    }

    fun entry(modelId: String): ModelEntry =
        entries[modelId] ?: throw IllegalArgumentException("Unknown mobile model: $modelId")

    fun ensureModel(
        modelId: String,
        onProgress: (Int) -> Unit,
    ): Pair<ModelEntry, File> {
        val entry = entry(modelId)
        val outFile = File(modelsDir(), entry.file)

        if (!outFile.exists() || outFile.length() < 1_000_000L) {
            onProgress(0)
            downloadToFile(entry.url, outFile, onProgress)
        } else {
            onProgress(100)
        }

        // ONNX Runtime performs the authoritative protobuf/model validation
        // when the session is created. Keep a small sanity check here so a
        // failed/HTML download is not mistaken for a model.
        if (outFile.length() < 1_000_000L) {
            outFile.delete()
            throw IllegalStateException("Downloaded model is unexpectedly small")
        }

        return entry to outFile
    }

    private fun downloadToFile(
        url: String,
        out: File,
        onProgress: (Int) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 0
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "STEMwerk-Android/0.4.0")
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Model download failed with HTTP $code")
            }

            val totalLen = conn.contentLengthLong
            val tmp = File(out.absolutePath + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 256)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (totalLen > 0L) {
                            onProgress(((done * 100L) / totalLen).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }

            if (!tmp.renameTo(out)) {
                throw IllegalStateException("Could not move downloaded model into cache")
            }
        } finally {
            conn.disconnect()
        }
    }
}
