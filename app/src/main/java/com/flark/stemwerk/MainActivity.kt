package com.flark.stemwerk

import android.app.Activity
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

        // Restore output folder
        prefs.getString("outputFolderUri", null)?.let {
            runCatching { outputFolderUri = Uri.parse(it) }
        }

        setupLogo()

        findViewById<Button>(R.id.pickAudioButton).setOnClickListener {
            pickAudio.launch(arrayOf("audio/wav", "audio/*"))
        }

        findViewById<Button>(R.id.pickModelButton).setOnClickListener {
            // Existing model picker logic (from releases) lives elsewhere; keep modelId as-is.
            // For now we just re-use the current flow: model already downloaded/selected in app state.
            ModelPickerDialog.show(this) { chosenId ->
                modelId = chosenId
                updateUi()
            }
        }

        findViewById<Button>(R.id.pickOutputFolderButton).setOnClickListener {
            pickOutputFolder.launch(null)
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            val a = audioUri ?: return@setOnClickListener
            val m = modelId ?: return@setOnClickListener

            val stems = 4
            val selected = selectedStemNames()

            val i = Intent(this, ProcessingActivity::class.java)
            i.putExtra("audioUri", a.toString())
            i.putExtra("modelId", m)
            i.putExtra("stems", stems)
            i.putExtra("selectedStems", selected.toTypedArray())
            i.putExtra("outputFolderUri", outputFolderUri?.toString())
            startActivity(i)
        }

        updateUi()
    }

    private fun selectedStemNames(): List<String> {
        val vocals = findViewById<CheckBox>(R.id.stemVocals).isChecked
        val drums = findViewById<CheckBox>(R.id.stemDrums).isChecked
        val bass = findViewById<CheckBox>(R.id.stemBass).isChecked
        val other = findViewById<CheckBox>(R.id.stemOther).isChecked

        val out = mutableListOf<String>()
        if (drums) out += "drums"
        if (bass) out += "bass"
        if (other) out += "other"
        if (vocals) out += "vocals"
        return out
    }

    private fun updateUi() {
        val status = findViewById<TextView>(R.id.statusText)
        val audioText = findViewById<TextView>(R.id.audioSelectedText)
        val modelText = findViewById<TextView>(R.id.modelSelectedText)
        val outText = findViewById<TextView>(R.id.outputFolderText)

        audioText.text = audioUri?.toString() ?: "No audio selected"
        modelText.text = modelId ?: "No model selected"
        outText.text = outputFolderUri?.toString() ?: "Not set (will use app folder)"

        val ready = (audioUri != null && modelId != null)
        status.text = "Status: idle (v${BuildConfig.VERSION_NAME})"
        findViewById<Button>(R.id.startButton).isEnabled = ready
    }

    private fun setupLogo() {
        val web = findViewById<WebView>(R.id.logoWebView)
        val fallback = findViewById<ImageView>(R.id.logoFallback)

        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.setBackgroundColor(0x00000000)

        try {
            web.loadUrl("file:///android_asset/stemwerk_dynamic.svg")
            fallback.visibility = ImageView.GONE
        } catch (_: Exception) {
            fallback.visibility = ImageView.VISIBLE
        }
    }
}
