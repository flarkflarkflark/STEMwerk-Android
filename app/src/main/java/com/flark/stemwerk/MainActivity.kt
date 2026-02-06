package com.flark.stemwerk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.caverock.androidsvg.SVG

class MainActivity : AppCompatActivity() {

    private lateinit var stemCountSpinner: Spinner
    private lateinit var downloadModelButton: Button
    private lateinit var selectAudioButton: Button
    private lateinit var splitButton: Button
    private lateinit var statusText: TextView

    private var selectedAudioUri: Uri? = null
    private var modelPath: String? = null

    private fun refreshSplitEnabled() {
        splitButton.isEnabled = (selectedAudioUri != null && modelPath != null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stemCountSpinner = findViewById(R.id.stemCountSpinner)
        downloadModelButton = findViewById(R.id.downloadModelButton)
        selectAudioButton = findViewById(R.id.selectAudioButton)
        splitButton = findViewById(R.id.splitButton)
        statusText = findViewById(R.id.statusText)

        // Show app version so we can verify which APK is installed
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            statusText.text = "Status: idle (v" + pInfo.versionName + ")"
        } catch (_: Exception) {
            // ignore
        }

        // Logo: prefer animated SVG via WebView; only show ImageView fallback if WebView fails
        val logoWeb: WebView = findViewById(R.id.logoWeb)
        val logoView: ImageView = findViewById(R.id.logo)
        logoView.visibility = android.view.View.GONE

        logoWeb.setBackgroundColor(0x00000000)
        logoWeb.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        logoWeb.settings.allowFileAccess = false
        logoWeb.settings.javaScriptEnabled = false

        var webOk = false
        try {
            val svgText = assets.open("stemwerk_dynamic.svg").bufferedReader().use { it.readText() }
            val html = """
                <html><head><meta name="viewport" content="width=device-width, initial-scale=1"/></head>
                <body style="margin:0;background:transparent;display:flex;align-items:center;justify-content:center;">
                  $svgText
                </body></html>
            """.trimIndent()
            logoWeb.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            webOk = true
        } catch (_: Exception) {
            webOk = false
        }

        if (!webOk) {
            try {
                val svg = SVG.getFromResource(this, R.raw.stemwerk_dynamic)
                val drawable = svg.renderToPicture().let { android.graphics.drawable.PictureDrawable(it) }
                logoView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                logoView.setImageDrawable(drawable)
                logoView.visibility = android.view.View.VISIBLE
            } catch (_: Exception) {
                // ignore
            }
        }

        stemCountSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("2 stems", "4 stems")
        )

        val modelManager = ModelManager(this)

        val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                selectedAudioUri = uri
                statusText.text = "Status: selected audio: $uri"
                refreshSplitEnabled()
            }
        }

        selectAudioButton.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        downloadModelButton.setOnClickListener {
            val stems = when (stemCountSpinner.selectedItemPosition) {
                0 -> 2
                else -> 4
            }

            statusText.text = "Status: downloading model for ${stems} stems…"
            downloadModelButton.isEnabled = false

            modelManager.ensureModel(
                stems,
                onProgress = { pct ->
                    runOnUiThread { statusText.text = "Status: downloading model… ${pct}%" }
                },
                onDone = { path ->
                    runOnUiThread {
                        modelPath = path
                        statusText.text = "Status: model ready at $path"
                        downloadModelButton.isEnabled = true
                        refreshSplitEnabled()
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        statusText.text = "Status: error: $msg"
                        downloadModelButton.isEnabled = true
                    }
                }
            )
        }

        splitButton.setOnClickListener {
            val stems = when (stemCountSpinner.selectedItemPosition) {
                0 -> 2
                else -> 4
            }
            val uri = selectedAudioUri
            val mp = modelPath
            if (uri == null) {
                statusText.text = "Status: select audio first"
                return@setOnClickListener
            }
            if (mp == null) {
                statusText.text = "Status: download model first"
                return@setOnClickListener
            }

            val i = Intent(this, ProcessingActivity::class.java)
            i.putExtra("stems", stems)
            i.putExtra("audioUri", uri.toString())
            i.putExtra("modelPath", mp)
            startActivity(i)
        }

        refreshSplitEnabled()
    }
}
