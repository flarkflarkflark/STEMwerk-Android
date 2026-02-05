package com.flark.stemwerk

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Model download/cache manager.
 *
 * v0.1: Downloads a manifest.json from a fixed GitHub release tag (models-v0.1), then downloads
 * the model asset for the selected stem count, verifies sha256, and caches it.
 */
class ModelManager(private val ctx: Context) {

    private val repoOwner = "flarkflarkflark"
    private val repoName = "STEMwerk-Android"
    private val modelReleaseTag = "models-v0.1" // TODO: make this configurable / switch to a channel

    data class ModelEntry(
        val id: String,
        val stems: Int,
        val format: String,
        val file: String,
        val sha256: String,
    )

    private fun modelsDir(): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, "models")
        dir.mkdirs()
        return dir
    }

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Accept", "application/json")
        conn.inputStream.use { ins ->
            return ins.bufferedReader().readText()
        }
    }

    private fun downloadToFile(url: String, out: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.connectTimeout = 15_000
        conn.readTimeout = 0
        val totalLen = conn.contentLengthLong

        val tmp = File(out.absolutePath + ".part")
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1024 * 128)
                var read: Int
                var done: Long = 0
                while (true) {
                    read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    done += read
                    if (totalLen > 0) {
                        val pct = ((done * 100) / totalLen).toInt().coerceIn(0, 100)
                        onProgress(pct)
                    }
                }
            }
        }
        if (!tmp.renameTo(out)) {
            throw RuntimeException("Failed to move downloaded file into place")
        }
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1024 * 128)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchManifestEntries(): List<ModelEntry> {
        // GitHub API for a specific release tag
        val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/tags/$modelReleaseTag"
        val json = JSONObject(httpGetText(apiUrl))
        val assets = json.getJSONArray("assets")

        var manifestUrl: String? = null
        val assetUrlByName = mutableMapOf<String, String>()

        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            val dl = a.getString("browser_download_url")
            assetUrlByName[name] = dl
            if (name == "manifest.json") manifestUrl = dl
        }

        val mUrl = manifestUrl ?: throw RuntimeException("manifest.json not found in release $modelReleaseTag")
        val manifestText = httpGetText(mUrl)
        val manifest = JSONObject(manifestText)
        val models = manifest.getJSONArray("models")

        val out = mutableListOf<ModelEntry>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            out += ModelEntry(
                id = m.getString("id"),
                stems = m.getInt("stems"),
                format = m.getString("format"),
                file = m.getString("file"),
                sha256 = m.getString("sha256"),
            )
        }

        // Validate that the model files exist as release assets
        for (e in out) {
            if (!assetUrlByName.containsKey(e.file)) {
                throw RuntimeException("Model asset missing from release: ${e.file}")
            }
        }

        return out
    }

    fun ensureModel(
        stems: Int,
        onProgress: (Int) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (stems != 2 && stems != 4) {
            onError("Only 2 and 4 stems are supported in this MVP build")
            return
        }

        thread {
            try {
                val entries = fetchManifestEntries()
                val entry = entries.firstOrNull { it.stems == stems }
                    ?: throw RuntimeException("No model entry for ${stems} stems")

                val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/tags/$modelReleaseTag"
                val rel = JSONObject(httpGetText(apiUrl))
                val assets = rel.getJSONArray("assets")
                var modelUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name") == entry.file) {
                        modelUrl = a.getString("browser_download_url")
                        break
                    }
                }
                val dlUrl = modelUrl ?: throw RuntimeException("Download URL not found for ${entry.file}")

                val outFile = File(modelsDir(), entry.file)
                if (!outFile.exists() || outFile.length() == 0L) {
                    onProgress(0)
                    downloadToFile(dlUrl, outFile, onProgress)
                }

                val got = sha256(outFile)
                if (!got.equals(entry.sha256, ignoreCase = true)) {
                    outFile.delete()
                    throw RuntimeException("SHA256 mismatch for ${entry.file}")
                }

                onDone(outFile.absolutePath)
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            }
        }
    }
}
