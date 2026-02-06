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
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var shareButton: Button

    private var zipFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_processing)

        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        shareButton = findViewById(R.id.shareButton)

        shareButton.isEnabled = false

        val audioUri = Uri.parse(intent.getStringExtra("audioUri") ?: "")
        val modelId = intent.getStringExtra("modelId") ?: ""
        val stems = intent.getIntExtra("stems", 4)
        val selectedStems = intent.getStringArrayExtra("selectedStems")?.toList() ?: listOf("drums", "bass", "other", "vocals")
        val outputFolderUriStr = intent.getStringExtra("outputFolderUri")

        val outDir = File(getExternalFilesDir(null), "outputs/run_${System.currentTimeMillis()}")
        outDir.mkdirs()

        val outputSink: OutputSink = if (!outputFolderUriStr.isNullOrBlank()) {
            SafOutputSink(this, Uri.parse(outputFolderUriStr))
        } else {
            FileOutputSink(outDir)
        }

        val engine = SeparationEngine(this)

        fun log(msg: String) {
            runOnUiThread {
                logText.append(msg + "\n")
            }
        }

        fun progress(pct: Int, msg: String) {
            runOnUiThread {
                progressBar.progress = pct
                statusText.text = msg
            }
        }

        fun done(ok: Boolean, msg: String) {
            runOnUiThread {
                statusText.text = msg
                if (ok) {
                    // Keep zip share as optional convenience (even if stems are written to SAF folder).
                    zipFile = runCatching { zipOutputDir(outDir) }.getOrNull()
                    shareButton.isEnabled = (zipFile != null)
                }
            }
        }

        progress(1, "Starting…")

        // NOTE: ModelId is not yet used in dummy engine; real Demucs will use it.
        engine.run(
            audioUri = audioUri,
            stems = stems,
            selectedStemNames = selectedStems,
            modelPath = modelId,
            output = outputSink,
            onLog = ::log,
            onProgress = ::progress,
            onDone = ::done,
        )

        shareButton.setOnClickListener {
            val z = zipFile ?: return@setOnClickListener
            shareZip(z)
        }

        findViewById<Button>(R.id.closeButton).setOnClickListener {
            finish()
        }
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
