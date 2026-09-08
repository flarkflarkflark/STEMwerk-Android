package com.flark.stemwerk

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

class AccelerationTestActivity : AppCompatActivity() {
    @Volatile private var cancelled = false
    private var running = true
    private var report: File? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_processing)
        findViewById<Button>(R.id.previewOutputButton).visibility = android.view.View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<TextView>(R.id.procTitle).text = "Test toestelversnelling"
        findViewById<TextView>(R.id.progressText).text = "Vergelijkt CPU en NNAPI; houd de app geopend."
        findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        val share = findViewById<Button>(R.id.shareButton)
        share.text = "Share test report"
        share.isEnabled = false
        val close = findViewById<Button>(R.id.closeButton)
        close.text = "Cancel"
        close.isEnabled = true
        close.setOnClickListener { if (running) { cancelled = true; close.isEnabled = false } else finish() }
        share.setOnClickListener {
            report?.let { file ->
                val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share acceleration report"))
            }
        }
        val modelId = intent.getStringExtra("modelId") ?: ModelManager.FOUR_STEMS
        thread(name = "stemwerk-acceleration-test") {
            val text = StringBuilder()
            fun log(line: String) {
                text.append(line).append('\n')
                runOnUiThread { if (!isDestroyed) findViewById<TextView>(R.id.logText).append(line + "\n") }
            }
            var result = "Test complete"
            try {
                AccelerationProbe(applicationContext).run(modelId,
                    { if (cancelled) throw CancellationException("Cancelled") }, ::log)
            } catch (e: Exception) {
                result = "Test stopped: " + e.message
                log(result)
            } finally {
                report = runCatching {
                    File(cacheDir, "stemwerk_acceleration_" + System.currentTimeMillis() + ".txt")
                        .also { it.writeText(text.toString()) }
                }.getOrNull()
                runOnUiThread {
                    if (!isDestroyed) {
                        running = false
                        findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = false
                        findViewById<TextView>(R.id.progressText).text = result
                        close.text = "Close"
                        close.isEnabled = true
                        share.isEnabled = report != null
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }
    override fun onDestroy() { cancelled = true; super.onDestroy() }
}
