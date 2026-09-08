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

    private var audioUri: Uri? = null
    private var modelId: String? = null
    private var outputFolderUri: Uri? = null
    private var backendId: String = "auto"

    private val prefs by lazy { getSharedPreferences("stemwerk", MODE_PRIVATE) }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            audioUri = uri
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

        setupLogo()

        findViewById<Button>(R.id.pickAudioButton).setOnClickListener {
            pickAudio.launch(arrayOf("audio/wav", "audio/*"))
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
            val a = audioUri ?: return@setOnClickListener
            val m = modelId ?: return@setOnClickListener

            val i = Intent(this, ProcessingActivity::class.java)
            i.putExtra("audioUri", a.toString())
            i.putExtra("modelId", m)
            i.putExtra("stems", 2)
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
        return out
    }

    private fun updateUi() {
        val status = findViewById<TextView>(R.id.statusText)
        val audioText = findViewById<TextView>(R.id.audioSelectedText)
        val modelText = findViewById<TextView>(R.id.modelSelectedText)
        val backendText = findViewById<TextView>(R.id.backendSelectedText)
        val outText = findViewById<TextView>(R.id.outputFolderText)

        audioText.text = audioUri?.toString() ?: "No audio selected"
        modelText.text = modelId ?: "No model selected"
        backendText.text = BackendPickerDialog.label(backendId)
        outText.text = outputFolderUri?.toString() ?: "Not set (will use app folder)"

        val ready = (audioUri != null && modelId != null && selectedStemNames().isNotEmpty())
        status.text = "Status: idle — real 2-stem MDX ready (v${BuildConfig.VERSION_NAME})"
        findViewById<Button>(R.id.startButton).isEnabled = ready
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
