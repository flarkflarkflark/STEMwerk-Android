package com.flark.stemwerk

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebSettings
import android.widget.ImageView
import com.caverock.androidsvg.SVG
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var stemCountSpinner: Spinner
    private lateinit var downloadModelButton: Button
    private lateinit var splitButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val selectAudioButton: Button = findViewById(R.id.selectAudioButton)

        var selectedAudioUri: Uri? = null

        val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                selectedAudioUri = uri
                statusText.text = "Status: selected audio: $uri"
            }
        }

        selectAudioButton.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        stemCountSpinner = findViewById(R.id.stemCountSpinner)
        downloadModelButton = findViewById(R.id.downloadModelButton)
        splitButton = findViewById(R.id.splitButton)
        statusText = findViewById(R.id.statusText)

        // Show app version so we can verify which APK is installed
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            statusText.text = "Status: idle (v" + pInfo.versionName + ")"
        } catch (_: Exception) {
            // ignore
        }

        val logoWeb: WebView = findViewById(R.id.logoWeb)
        logoWeb.setBackgroundColor(0x00000000)
        logoWeb.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        logoWeb.settings.allowFileAccess = false
        logoWeb.settings.javaScriptEnabled = false

        // Try animated SVG via WebView (SMIL animations work in Chromium)
        try {
            val svgText = assets.open("stemwerk_dynamic.svg").bufferedReader().use { it.readText() }
            val html = """
                <html><head><meta name="viewport" content="width=device-width, initial-scale=1"/></head>
                <body style="margin:0;background:transparent;display:flex;align-items:center;justify-content:center;">
                  $svgText
                </body></html>
            """.trimIndent()
            logoWeb.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        } catch (_: Exception) {
            // ignore, fallback below
        }

        val logoView: ImageView = findViewById(R.id.logo)
        try {
            val svg = SVG.getFromResource(this, R.raw.stemwerk_dynamic)
            val drawable = svg.renderToPicture().let { android.graphics.drawable.PictureDrawable(it) }
            logoView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            logoView.setImageDrawable(drawable)
            logoView.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            // ignore
        }

        stemCountSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("2 stems", "4 stems")
        )

        val modelManager = ModelManager(this)

        splitButton.setOnClickListener {
            val stems2 = when (stemCountSpinner.selectedItemPosition) {
                0 -> 2
                else -> 4
            }
            val uri = selectedAudioUri
            if (uri == null) {
                statusText.text = "Status: select audio first"
                return@setOnClickListener
            }

            // Ensure we have the model cached (ModelManager will return cached path fast)
            modelManager.ensureModel(stems2,
                onProgress = { pct -> runOnUiThread { statusText.text = "Status: model check… ${pct}%" } },
                onDone = { path ->
                    runOnUiThread {
                        val i = Intent(this, ProcessingActivity::class.java)
                        i.putExtra("stems", stems2)
                        i.putExtra("audioUri", uri.toString())
                        i.putExtra("modelPath", path)
                        startActivity(i)
                    }
                },
                onError = { msg -> runOnUiThread { statusText.text = "Status: model error: $msg" } }
            )
        }


        downloadModelButton.setOnClickListener {
            val stems = when (stemCountSpinner.selectedItemPosition) {
                0 -> 2
                1 -> 4
                else -> 6
            }

            statusText.text = "Status: downloading model for ${stems} stems…"
            downloadModelButton.isEnabled = false

            modelManager.ensureModel(stems,
                onProgress = { pct ->
                    runOnUiThread {
                        statusText.text = "Status: downloading model… ${pct}%"
                    }
                },
                onDone = { path ->
                    runOnUiThread {
                        statusText.text = "Status: model ready at ${path}"
                        downloadModelButton.isEnabled = true
                        splitButton.isEnabled = (selectedAudioUri != null)
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
    }
}
