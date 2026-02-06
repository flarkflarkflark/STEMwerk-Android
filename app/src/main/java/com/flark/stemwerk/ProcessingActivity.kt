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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProcessingActivity : AppCompatActivity() {

    private fun zipDir(srcDir: File, outZip: File) {
        ZipOutputStream(BufferedOutputStream(outZip.outputStream())).use { zos ->
            val baseLen = srcDir.absolutePath.length + 1
            srcDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.absolutePath.substring(baseLen)
                    val entry = ZipEntry(rel)
                    zos.putNextEntry(entry)
                    FileInputStream(f).use { ins ->
                        ins.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_processing)

        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        val progressText: TextView = findViewById(R.id.progressText)
        val logText: TextView = findViewById(R.id.logText)
        val closeButton: Button = findViewById(R.id.closeButton)
        val shareButton: Button = findViewById(R.id.shareButton)

        val stems = intent.getIntExtra("stems", 4)
        val audioUriStr = intent.getStringExtra("audioUri")
        val modelPath = intent.getStringExtra("modelPath")

        closeButton.setOnClickListener { finish() }

        if (audioUriStr == null || modelPath == null) {
            progressText.text = "Missing input"
            closeButton.isEnabled = true
            return
        }

        val audioUri = Uri.parse(audioUriStr)

        val outDir = File(getExternalFilesDir(null), "outputs/run_${System.currentTimeMillis()}")
        outDir.mkdirs()

        val engine = SeparationEngine(this)
        engine.run(
            audioUri = audioUri,
            stems = stems,
            modelPath = modelPath,
            outDir = outDir,
            onLog = { line ->
                runOnUiThread {
                    logText.append(line + "\n")
                }
            },
            onProgress = { pct, msg ->
                runOnUiThread {
                    progressBar.progress = pct
                    progressText.text = msg
                }
            },
            onDone = { ok, msg ->
                runOnUiThread {
                    progressBar.progress = if (ok) 100 else progressBar.progress
                    progressText.text = msg + "\nOutput: ${outDir.absolutePath}"
                    closeButton.isEnabled = true
                    shareButton.isEnabled = ok

                    shareButton.setOnClickListener {
                        try {
                            val zipFile = File(cacheDir, "stemwerk_output_${outDir.name}.zip")
                            if (zipFile.exists()) zipFile.delete()
                            zipDir(outDir, zipFile)

                            val uri = FileProvider.getUriForFile(
                                this,
                                applicationContext.packageName + ".fileprovider",
                                zipFile
                            )

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(shareIntent, "Share STEMwerk output"))
                        } catch (e: Exception) {
                            logText.append("Share failed: ${e.message}\n")
                        }
                    }
                }
            }
        )
    }
}
