package com.flark.stemwerk

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
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

        val logoView: ImageView = findViewById(R.id.logo)
        try {
            val svg = SVG.getFromResource(this, R.raw.stemwerk_dynamic)
            val drawable = svg.renderToPicture().let { android.graphics.drawable.PictureDrawable(it) }
            logoView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            logoView.setImageDrawable(drawable)
        } catch (e: Exception) {
            // ignore
        }

        stemCountSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("2 stems", "4 stems")
        )

        val modelManager = ModelManager(this)

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
                        splitButton.isEnabled = true
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
