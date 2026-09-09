package com.flark.stemwerk

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelManager(private val ctx: Context) {
    data class ModelEntry(
        val id: String, val file: String, val sha256: String, val size: Long,
        val dimF: Int, val dimT: Int, val nFft: Int, val hop: Int = 1024,
        val compensation: Float, val primaryStem: String, val secondaryStem: String = "other",
        val qnnFile: String? = null, val qnnSha256: String? = null, val qnnSize: Long? = null,
    )
    companion object {
        const val FOUR_STEMS = "kuielab-4stems"
        const val TWO_STEMS = "uvr-mdx-voc-ft"
        val STEMS = listOf("vocals", "drums", "bass", "other")
        val MODELS = listOf(
            ModelEntry("uvr-mdx-voc-ft", "UVR-MDX-NET-Voc_FT.onnx",
                "534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111", 66762490L,
                3072, 256, 7680, compensation = 1.021f,
                primaryStem = "vocals"),
            ModelEntry("kuielab-vocals", "kuielab_a_vocals.onnx",
                "daba83c2ee1afee9139766ad64c9b6808d6b6f092fff04bed3338be50baac721", 29703204L,
                2048, 512, 6144, compensation = 1.035f,
                primaryStem = "vocals", secondaryStem = "",
                qnnFile = "kuielab_a_vocals_static_batch.onnx",
                qnnSha256 = "f338be7a4a3cde3cc656bfccc9884572383a116144822473cc441da844c1abdc", qnnSize = 29703184L),
            ModelEntry("kuielab-drums", "kuielab_a_drums.onnx",
                "40f586b7091934dd6f5563f0cba8f14bad57ce88440da1098bf388ea716c2901", 29703204L,
                2048, 512, 4096, compensation = 1.035f,
                primaryStem = "drums", secondaryStem = "",
                qnnFile = "kuielab_a_drums_static_batch.onnx",
                qnnSha256 = "b124bcf88c989884f2a4fb8b63e15a72075a587b41ef23214e7e3a9c71e5c1b9", qnnSize = 29703184L),
            ModelEntry("kuielab-bass", "kuielab_a_bass.onnx",
                "0c3e77b9963185b1ea6bb46a4b8924137d9370fc1ccdefec7b1b416ef550dcaa", 29703204L,
                2048, 512, 16384, compensation = 1.035f,
                primaryStem = "bass", secondaryStem = "",
                qnnFile = "kuielab_a_bass_static_batch.onnx",
                qnnSha256 = "b371b7edb828d72edf036633bb0a9d79a39411b51ba8d34be2201a42b9fafc51", qnnSize = 29703184L),
            ModelEntry("kuielab-other", "kuielab_a_other.onnx",
                "7b67a1dcb5f232153528c59960b4c7bf8dc736b8114de360af0e719633f53358", 29703204L,
                2048, 512, 8192, compensation = 1.035f,
                primaryStem = "other", secondaryStem = "",
                qnnFile = "kuielab_a_other_static_batch.onnx",
                qnnSha256 = "d8479157b76e2519b98876270cb62b8dce5eaf6f92b93606d31f7eed8b7d8cb0", qnnSize = 29703184L),
        )
        fun selectedModels(id: String, stems: List<String>): List<ModelEntry> {
            require(stems.isNotEmpty()) { "Select at least one stem" }
            return when (id) {
                FOUR_STEMS -> {
                    require(stems.all { it in STEMS }) { "Invalid four-stem selection" }
                    MODELS.drop(1).filter { it.primaryStem in stems }
                }
                TWO_STEMS -> {
                    require(stems.all { it == "vocals" || it == "other" }) { "This model separates vocals and instrumental only" }
                    listOf(MODELS.first())
                }
                else -> error("Unknown model: " + id)
            }
        }
    }

    fun ensureModel(entry: ModelEntry, checkCancelled: () -> Unit, progress: (Int) -> Unit): File =
        download(
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/" + entry.file,
            entry.file, entry.sha256, entry.size, checkCancelled, progress,
        )

    /** Static-batch variant used only for the QNN GPU route; null if this model has none. */
    fun ensureQnnModel(entry: ModelEntry, checkCancelled: () -> Unit, progress: (Int) -> Unit): File? {
        val file = entry.qnnFile ?: return null
        val sha256 = entry.qnnSha256 ?: return null
        val size = entry.qnnSize ?: return null
        return download(
            "https://github.com/flarkflarkflark/STEMwerk-Android/releases/download/qnn-gpu-static-batch-v1/" + file,
            file, sha256, size, checkCancelled, progress,
        )
    }

    private fun download(
        url: String, file: String, sha256: String, size: Long,
        checkCancelled: () -> Unit, progress: (Int) -> Unit,
    ): File {
        val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, "models")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create model cache" }
        val target = File(dir, file)
        if (valid(target, sha256, size, checkCancelled)) { progress(100); return target }
        val temporary = File.createTempFile("download-", ".part", dir)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode in 200..299) { "Model download HTTP " + connection.responseCode }
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { out ->
                    val buffer = ByteArray(262144)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        require(done <= size) { "Unexpected model size" }
                        out.write(buffer, 0, n)
                        val pct = (done * 100 / size).toInt()
                        if (pct != lastPct) { progress(pct); lastPct = pct }
                    }
                }
            }
            check(valid(temporary, sha256, size, checkCancelled)) { "Model checksum mismatch; retry download" }
            check(temporary.renameTo(target)) { "Cannot save verified model" }
            return target
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun valid(file: File, sha256: String, size: Long, checkCancelled: () -> Unit): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(262144)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }
}

