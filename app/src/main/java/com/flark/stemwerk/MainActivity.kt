package com.flark.stemwerk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var audioUris: List<Uri> = emptyList()
    private var modelId: String = ModelManager.FOUR_STEMS
    private var outputFolderUri: Uri? = null
    private var backendId: String = "auto"

    private val prefs by lazy { getSharedPreferences("stemwerk", MODE_PRIVATE) }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            audioUris = uris.distinct()
            updateUi()
        }
    }

    private val pickOutputFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            outputFolderUri = uri
            prefs.edit().putString("outputFolderUri", uri.toString()).apply()
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs.getString("outputFolderUri", null)?.let {
            runCatching { outputFolderUri = Uri.parse(it) }
        }

        audioUris = savedInstanceState?.getStringArray("audioUris")?.map(Uri::parse) ?: emptyList()
        modelId = savedInstanceState?.getString("modelId") ?: ModelManager.FOUR_STEMS
        backendId = savedInstanceState?.getString("backend") ?: "auto"
        listOf(R.id.stemVocals, R.id.stemDrums, R.id.stemBass, R.id.stemOther).forEach { id ->
            findViewById<CheckBox>(id).setOnCheckedChangeListener { _, _ -> updateUi() }
        }
        findViewById<Button>(R.id.testAccelerationButton).setOnClickListener {
            startActivity(Intent(this, AccelerationTestActivity::class.java)
                .putExtra("modelId", modelId))
        }
        setupLogo()

        findViewById<Button>(R.id.pickAudioButton).setOnClickListener {
            pickAudio.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
        }

        findViewById<Button>(R.id.previewAudioButton).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("audioUris", audioUris.map(Uri::toString).toTypedArray())
                putExtra("labels", audioUris.map { AudioNames.display(this@MainActivity, it) + " — Original" }.toTypedArray())
            })
        }
        findViewById<Button>(R.id.pickModelButton).setOnClickListener {
            ModelPickerDialog.show(this) { chosenId ->
                modelId = chosenId
                updateUi()
            }
        }

        findViewById<Button>(R.id.pickBackendButton).setOnClickListener {
            BackendPickerDialog.show(this, backendId) { chosenId ->
                backendId = chosenId
                updateUi()
            }
        }

        findViewById<Button>(R.id.pickOutputFolderButton).setOnClickListener {
            pickOutputFolder.launch(null)
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (audioUris.isEmpty()) return@setOnClickListener
            val m = modelId

            val i = Intent(this, ProcessingActivity::class.java)
            i.putExtra("audioUris", audioUris.map(Uri::toString).toTypedArray())
            i.putExtra("modelId", m)
            i.putExtra("stems", if (modelId == ModelManager.FOUR_STEMS) 4 else 2)
            i.putExtra("selectedStems", selectedStemNames().toTypedArray())
            i.putExtra("backend", backendId)
            i.putExtra("outputFolderUri", outputFolderUri?.toString())
            startActivity(i)
        }

        updateUi()
    }

    private fun selectedStemNames(): List<String> {
        val out = mutableListOf<String>()
        if (findViewById<CheckBox>(R.id.stemVocals).isChecked) out += "vocals"
        if (findViewById<CheckBox>(R.id.stemOther).isChecked) out += "other"
        if (modelId == ModelManager.FOUR_STEMS) {
            if (findViewById<CheckBox>(R.id.stemDrums).isChecked) out += "drums"
            if (findViewById<CheckBox>(R.id.stemBass).isChecked) out += "bass"
        }
        return out
    }

    private fun updateUi() {
        val status = findViewById<TextView>(R.id.statusText)
        val audioText = findViewById<TextView>(R.id.audioSelectedText)
        val modelText = findViewById<TextView>(R.id.modelSelectedText)
        val backendText = findViewById<TextView>(R.id.backendSelectedText)
        val outText = findViewById<TextView>(R.id.outputFolderText)

        audioText.text = if (audioUris.isEmpty()) "No audio selected" else audioUris.size.toString() + " file(s):\n" + audioUris.joinToString("\n") { AudioNames.display(this, it) }
        modelText.text = if (modelId == ModelManager.FOUR_STEMS) "4 stems — KUIELab MDX" else "2 stems — UVR MDX Vocals"
        val visibility = if (modelId == ModelManager.FOUR_STEMS) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<CheckBox>(R.id.stemDrums).visibility = visibility
        findViewById<CheckBox>(R.id.stemBass).visibility = visibility
        findViewById<CheckBox>(R.id.stemOther).text = if (modelId == ModelManager.FOUR_STEMS) "Other" else "Instrumental"
        backendText.text = BackendPickerDialog.label(backendId)
        outText.text = outputFolderUri?.toString() ?: "Not set (will use app folder)"

        val ready = (audioUris.isNotEmpty() && selectedStemNames().isNotEmpty())
        status.text = "Ready (v${BuildConfig.VERSION_NAME})"
        findViewById<Button>(R.id.startButton).isEnabled = ready
        findViewById<Button>(R.id.previewAudioButton).isEnabled = audioUris.isNotEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArray("audioUris", audioUris.map(Uri::toString).toTypedArray())
        outState.putString("modelId", modelId)
        outState.putString("backend", backendId)
        super.onSaveInstanceState(outState)
    }

    private fun setupLogo() {
        val web = findViewById<WebView>(R.id.logoWebView)
        val fallback = findViewById<ImageView>(R.id.logoFallback)

        web.settings.javaScriptEnabled = false
        web.settings.allowFileAccess = true
        web.setBackgroundColor(0x00000000)

        val html = """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              </head>
              <body style="margin:0;padding:0;background:#1A1A1F;display:flex;align-items:center;justify-content:center;overflow:hidden;">
                <img src="stemwerk_installer.svg"
                     alt="STEMwerk"
                     style="display:block;width:100%;height:100%;object-fit:contain;" />
              </body>
            </html>
        """.trimIndent()

        try {
            web.loadDataWithBaseURL(
                "file:///android_asset/",
                html,
                "text/html",
                "UTF-8",
                null
            )
            fallback.visibility = ImageView.GONE
        } catch (_: Exception) {
            fallback.visibility = ImageView.VISIBLE
        }
    }
}
