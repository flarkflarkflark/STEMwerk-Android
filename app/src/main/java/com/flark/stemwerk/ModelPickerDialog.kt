package com.flark.stemwerk

import android.app.AlertDialog
import android.content.Context

object ModelPickerDialog {
    fun show(ctx: Context, onChosen: (String) -> Unit) {
        val ids = arrayOf("uvr-mdx-voc-ft")
        val labels = arrayOf("UVR MDX Vocals — 2 stems (ONNX)")
        AlertDialog.Builder(ctx)
            .setTitle("Pick model")
            .setItems(labels) { _, which ->
                onChosen(ids[which])
            }
            .show()
    }
}
