package com.flark.stemwerk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class ProcessingActivity : AppCompatActivity() {
    @Volatile private var cancelled = false
    @Volatile private var activeEngine: RealMdxSeparationEngine? = null
    private var zipFile: File? = null
    private val previewUris = mutableListOf<String>()
    private val previewLabels = mutableListOf<String>()
    private val previewGroups = mutableListOf<String>()
    private var running = true
    private lateinit var closeButton: Button
    private lateinit var shareButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_processing)
        closeButton = findViewById(R.id.closeButton)
        shareButton = findViewById(R.id.shareButton)
        shareButton.isEnabled = false
        val listen = findViewById<Button>(R.id.previewOutputButton)
        listen.isEnabled = false
        listen.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("audioUris", previewUris.toTypedArray())
                putExtra("labels", previewLabels.toTypedArray())
                putExtra("groups", previewGroups.toTypedArray())
            })
        }
        closeButton.isEnabled = true
        closeButton.text = "Cancel"
        closeButton.setOnClickListener {
            if (running) {
                cancelled = true
                activeEngine?.cancel()
                closeButton.isEnabled = false
                closeButton.text = "Cancelling…"
            } else finish()
        }
        shareButton.setOnClickListener {
            zipFile?.let { file ->
                val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share stems"))
            }
        }
        val uris = (intent.getStringArrayExtra("audioUris")?.toList()
            ?: listOfNotNull(intent.getStringExtra("audioUri"))).distinct().map(Uri::parse)
        val modelId = intent.getStringExtra("modelId") ?: ModelManager.FOUR_STEMS
        val selected = intent.getStringArrayExtra("selectedStems")?.toList() ?: ModelManager.STEMS
        val backend = InferenceBackend.fromId(intent.getStringExtra("backend"))
        val folder = intent.getStringExtra("outputFolderUri")?.let(Uri::parse)
        thread(name = "stemwerk-batch") {
            val batch = File(getExternalFilesDir(null), "outputs/run_" + System.currentTimeMillis())
            val successes = mutableListOf<File>()
            var failed = 0
            try {
                check(batch.mkdirs()) { "Cannot create output directory" }
                require(uris.isNotEmpty()) { "Select audio first" }
                uris.forEachIndexed { index, uri ->
                    if (cancelled) return@forEachIndexed
                    val name = AudioNames.display(applicationContext, uri)
                    val jobDir = File(batch, AudioNames.folder(index, name))
                    check(jobDir.mkdirs())
                    log("File " + (index + 1) + "/" + uris.size + ": " + name)
                    val engine = RealMdxSeparationEngine(applicationContext)
                    activeEngine = engine
                    if (cancelled) engine.cancel()
                    try {
                        engine.runBlocking(SeparationRequest(uri, modelId,
                            if (modelId == ModelManager.FOUR_STEMS) 4 else 2,
                            selected, FileOutputSink(jobDir), backend), ::log) { pct, message ->
                            progress((index * 100 + pct) / uris.size,
                                (index + 1).toString() + "/" + uris.size + " — " + name + "\n" + message)
                        }
                        successes += jobDir
                        previewUris += uri.toString()
                        previewLabels += name + " — Original"
                        previewGroups += uri.toString()
                        jobDir.listFiles()?.filter { it.extension == "wav" }?.sortedBy { it.name }?.forEach { stem ->
                            previewUris += Uri.fromFile(stem).toString()
                            previewLabels += name + " — " + stem.nameWithoutExtension
                            previewGroups += uri.toString()
                        }
                        log("Finished: " + name)
                        if (folder != null) {
                            try {
                                export(jobDir, folder, batch.name)
                                log("Saved to selected folder: " + jobDir.name)
                            } catch (e: Exception) {
                                log("Folder export failed; stems remain available via Share: " + e.message)
                            }
                        }
                    } catch (e: Exception) {
                        jobDir.deleteRecursively()
                        if (!cancelled && e !is CancellationException) {
                            failed++
                            log("FAILED: " + name + " — " + (e.message ?: e.javaClass.simpleName))
                        }
                    } finally {
                        activeEngine = null
                    }
                }
                if (successes.isNotEmpty()) {
                    progress(100, "Preparing share ZIP…")
                    zipFile = zip(successes)
                }
                val result = (if (cancelled) "Cancelled. " else "") +
                    successes.size + "/" + uris.size + " completed; " + failed + " failed"
                progress(if (cancelled) 0 else 100, result)
                log("Output: " + batch.absolutePath)
            } catch (e: Exception) {
                log("Batch error: " + e.message)
                progress(0, "Failed: " + e.message)
            } finally {
                runOnUiThread {
                    if (!isDestroyed) {
                        running = false
                        closeButton.text = "Close"
                        closeButton.isEnabled = true
                        shareButton.isEnabled = zipFile != null
                        listen.isEnabled = previewUris.isNotEmpty()
                        findViewById<TextView>(R.id.procTitle).text = "Processing complete"
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    private fun log(message: String) = runOnUiThread {
        if (!isDestroyed) findViewById<TextView>(R.id.logText).append(message + "\n")
    }

    private fun progress(pct: Int, message: String) = runOnUiThread {
        if (!isDestroyed) {
            findViewById<ProgressBar>(R.id.progressBar).progress = pct.coerceIn(0, 100)
            findViewById<TextView>(R.id.progressText).text = message
        }
    }

    private fun export(source: File, tree: Uri, runName: String) {
        val root = DocumentFile.fromTreeUri(this, tree) ?: error("Invalid folder")
        val run = root.findFile(runName) ?: root.createDirectory(runName) ?: error("Cannot create run folder")
        val job = run.createDirectory(source.name) ?: error("Cannot create file folder")
        source.listFiles()?.filter { it.isFile }?.forEach { file ->
            val document = job.createFile("audio/wav", file.name) ?: error("Cannot create stem")
            contentResolver.openOutputStream(document.uri, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("Cannot write stem")
        }
    }

    private fun zip(directories: List<File>): File {
        val target = File(cacheDir, "stemwerk_output_" + System.currentTimeMillis() + ".zip")
        try {
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                directories.forEach { dir ->
                    dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                        out.putNextEntry(ZipEntry(dir.name + "/" + file.name))
                        file.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    override fun onDestroy() {
        cancelled = true
        activeEngine?.cancel()
        super.onDestroy()
    }
}
