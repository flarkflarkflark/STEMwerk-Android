package com.flark.stemwerk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProcessingActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logText: TextView
    private lateinit var shareButton: Button
    private lateinit var closeButton: Button

    private var zipFile: File? = null
    private var separationEngine: SeparationEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_processing)

        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        logText = findViewById(R.id.logText)
        shareButton = findViewById(R.id.shareButton)
        closeButton = findViewById(R.id.closeButton)

        shareButton.isEnabled = false
        closeButton.isEnabled = false

        val audioUri = Uri.parse(intent.getStringExtra("audioUri") ?: "")
        val modelId = intent.getStringExtra("modelId") ?: ""
        val stems = intent.getIntExtra("stems", 2)
        val selectedStems = intent.getStringArrayExtra("selectedStems")?.toList()
            ?: listOf("vocals", "other")
        val backend = InferenceBackend.fromId(intent.getStringExtra("backend"))
        val outputFolderUriStr = intent.getStringExtra("outputFolderUri")

        val outDir = File(getExternalFilesDir(null), "outputs/run_${System.currentTimeMillis()}")
        outDir.mkdirs()

        val outputSink: OutputSink = if (!outputFolderUriStr.isNullOrBlank()) {
            SafOutputSink(this, Uri.parse(outputFolderUriStr))
        } else {
            FileOutputSink(outDir)
        }

        fun log(msg: String) {
            runOnUiThread {
                logText.append(msg + "\n")
            }
        }

        fun progress(pct: Int, msg: String) {
            runOnUiThread {
                progressBar.progress = pct.coerceIn(0, 100)
                progressText.text = msg
            }
        }

        fun done(ok: Boolean, msg: String) {
            runOnUiThread {
                progressText.text = msg
                closeButton.isEnabled = true
                if (ok) {
                    zipFile = runCatching { zipOutputDir(outDir) }.getOrNull()
                    shareButton.isEnabled = (zipFile != null)
                } else {
                    shareButton.isEnabled = false
                }
            }
        }

        progress(1, "Starting real extraction…")
        log("Backend: $backend")

        val engine: SeparationEngine = RealMdxSeparationEngine(applicationContext)
        separationEngine = engine
        engine.run(
            request = SeparationRequest(
                audioUri = audioUri,
                modelId = modelId,
                stemCount = stems,
                selectedStemNames = selectedStems,
                output = outputSink,
                backend = backend,
            ),
            onLog = ::log,
            onProgress = ::progress,
            onDone = ::done,
        )

        shareButton.setOnClickListener {
            val z = zipFile ?: return@setOnClickListener
            shareZip(z)
        }

        closeButton.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        separationEngine?.cancel()
        separationEngine = null
        super.onDestroy()
    }

    private fun zipOutputDir(outDir: File): File {
        val zip = File(cacheDir, "stemwerk_output_${System.currentTimeMillis()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            outDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(outDir).path
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return zip
    }

    private fun shareZip(zip: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            zip
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share output"))
    }
}
