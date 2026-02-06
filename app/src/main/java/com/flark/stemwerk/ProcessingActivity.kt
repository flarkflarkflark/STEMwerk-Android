package com.flark.stemwerk

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ProcessingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_processing)

        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        val progressText: TextView = findViewById(R.id.progressText)
        val logText: TextView = findViewById(R.id.logText)
        val closeButton: Button = findViewById(R.id.closeButton)

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
                }
            }
        )
    }
}
