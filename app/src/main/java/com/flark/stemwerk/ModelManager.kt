package com.flark.stemwerk

import android.content.Context
import android.os.Environment
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

/**
 * Simple model download/cache manager.
 *
 * MVP assumptions:
 * - Models are static files downloaded from a URL.
 * - Stored in app-specific external files dir (survives across app runs, removable on uninstall).
 */
class ModelManager(private val ctx: Context) {

    data class ModelSpec(
        val stems: Int,
        val version: String,
        val url: String,
        val fileName: String,
    )

    // TODO: Replace placeholder URLs with real model hosting.
    private fun specFor(stems: Int): ModelSpec {
        return when (stems) {
            2 -> ModelSpec(2, "0.1", "https://example.com/models/2stems.onnx", "stemwerk_2stems_v0.1.onnx")
            4 -> ModelSpec(4, "0.1", "https://example.com/models/4stems.onnx", "stemwerk_4stems_v0.1.onnx")
            6 -> ModelSpec(6, "0.1", "https://example.com/models/6stems.onnx", "stemwerk_6stems_v0.1.onnx")
            else -> error("Unsupported stems: $stems")
        }
    }

    private fun modelsDir(): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, "models")
        dir.mkdirs()
        return dir
    }

    fun ensureModel(
        stems: Int,
        onProgress: (Int) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val spec = specFor(stems)
        val outFile = File(modelsDir(), spec.fileName)

        if (outFile.exists() && outFile.length() > 0) {
            onDone(outFile.absolutePath)
            return
        }

        thread {
            try {
                val tmp = File(outFile.absolutePath + ".part")
                URL(spec.url).openStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(1024 * 64)
                        var total: Long = 0
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            output.write(buf, 0, r)
                            total += r
                            // No content-length here, so we just show a fake spinner-ish progress.
                            val pct = ((total / (1024 * 1024)).coerceAtMost(100)).toInt()
                            onProgress(pct)
                        }
                    }
                }
                if (!tmp.renameTo(outFile)) {
                    throw RuntimeException("Failed to move downloaded model into place")
                }
                onDone(outFile.absolutePath)
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            }
        }
    }
}
